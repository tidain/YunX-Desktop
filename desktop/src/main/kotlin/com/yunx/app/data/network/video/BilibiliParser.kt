package com.yunx.app.data.network.video

import com.yunx.app.data.download.DownloadPathPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * 哔哩哔哩视频解析：登录后走 DASH 高清（下载时合轨），未登录退回单文件（`fnval=1` 的 durl 直链）。
 *
 * 数据来源（都是公开的 web 接口，未登录也能用）：
 * 1. `x/web-interface/view` 取 bvid/aid/cid/标题/作者/封面/时长；
 * 2. 已登录：`x/player/playurl?fnval=4048` 取 **DASH 分轨**（视频轨 + 音轨，优先 AVC），
 *    下载后由 `DashMuxer` 合轨；同时再取一次 `fnval=1&platform=html5` 的**单文件 mp4**
 *    只用于在线预览（分轨流 JavaFX 播不了）；
 * 3. 未登录 / DASH 不可用：`x/player/playurl?fnval=1&platform=html5&high_quality=1`
 *    取单文件 `durl[0]`，能直接下载播放，代价是清晰度偏低。
 *
 * ★ 已知限制：
 * - 只解析**第一个分P**（多 P 视频后续可按 `pages[]` 展开）；
 * - 番剧/课程（`bangumi/play`、`cheese/play`）不在识别范围内，需要完全不同的接口与登录态；
 * - 若 B 站把 `playurl` 也改成必须 WBI 签名，此接口需要换成带签名版本（见 Agent.md 的 TODO）。
 */
object BilibiliParser : VideoParser, QualitySelectableParser {

    override val platform: VideoPlatform = VideoPlatform.BILIBILI

    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    private const val PLAY_URL = "https://api.bilibili.com/x/player/playurl"
    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

    /** DASH 视频流的编码编号：7 = AVC(H.264)，12 = HEVC，13 = AV1 */
    private const val CODEC_AVC = 7

    /** 页面/接口请求头：Referer 必须是 bilibili.com，否则会被风控挡 */
    private fun apiHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 下载直链的请求头。
     *
     * ★ 必须带 `Referer`：B 站 CDN（upos-*.bilivideo.com）会校验来源，缺了直接 403；
     *   带 Cookie 则是为了让登录用户拿到更高清晰度（未登录会退到最低档）。
     */
    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /**
     * 登录态校验：`nav` 接口顺带返回用户名，可以直接作为昵称存下来。
     *
     * ★ `isLogin` 是 JSON 布尔值（`"isLogin":true`），必须用 `optBoolean` 读：
     *   Android 的 org.json 不会把 Boolean 转成 int，`optInt("isLogin")` 恒得 0，
     *   会让所有合法 Cookie 都被判「校验不通过」（网页登录与手动填写一起失败）。
     */
    override suspend fun validateCookie(cookie: String): String? {
        if (cookie.isBlank()) return null
        val json = runCatching { VideoHttp.getJson(NAV_URL, apiHeaders(cookie), "B站登录态校验") }.getOrNull()
            ?: return null
        val data = json.optJSONObject("data") ?: return null
        if (!data.optBoolean("isLogin")) return null
        return data.optString("uname")
    }

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult =
        parseWithQuality(link, cookie, null)

    /**
     * 解析并指定清晰度。
     *
     * @param qn 目标清晰度编号；null = 交给 B 站决定（默认最高可用档，等价于原来的 `qn=120`）
     */
    override suspend fun parseWithQuality(
        link: DetectedVideoLink,
        cookie: String,
        qn: Int?
    ): VideoParseResult {
        val viewUrl = if (link.id.startsWith("BV", ignoreCase = true)) {
            "$VIEW_URL?bvid=${link.id}"
        } else {
            "$VIEW_URL?aid=${link.id.removePrefix("av")}"
        }
        val view = VideoHttp.getJson(viewUrl, apiHeaders(cookie), "B站视频信息")
        if (view.optInt("code") != 0) {
            throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                "B站视频信息接口返回 code=${view.optInt("code")} msg=${view.optString("message")}"
            )
        }
        val data = view.optJSONObject("data")
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站视频信息缺少 data 字段")

        val bvid = data.optString("bvid")
        val cid = data.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid") ?: data.optLong("cid")
        if (bvid.isBlank() || cid <= 0L) {
            throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站视频信息缺少 bvid/cid")
        }

        val title = data.optString("title")
        val fileBase = fileNameBase(title, "bilibili_$bvid")

        // ① 已登录才试 DASH 高清：音视频分离的两路流，下载时由 DashMuxer 合轨。
        //    ★ 未登录不试：匿名拿到的 DASH 往往也是最低清，不值得为它引入更长、更易失败的合轨链路。
        if (cookie.isNotBlank()) {
            dashQuality(bvid, cid, cookie, qn)?.let { dash ->
                return buildResult(
                    data = data,
                    bvid = bvid,
                    media = VideoMedia(
                        url = dash.videoUrl,
                        kind = VideoMediaKind.VIDEO,
                        fileName = "$fileBase.mp4",
                        headers = mediaHeaders(cookie),
                        // quality 会拼进下载文件名（`标题_1080P.mp4`），别带额外说明文字
                        quality = qualityLabel(dash.quality),
                        audioUrl = dash.audioUrl,
                        audioHeaders = mediaHeaders(cookie),
                        // 分轨流不能直接预览（.m4s + 两路），另取一份单文件直链给预览用
                        previewUrl = mergedPreviewUrl(bvid, cid, cookie)
                    ),
                    qualities = dash.qualities,
                    selectedQn = dash.quality
                )
            }
        }

        // ② 退回单文件 durl（未登录 / DASH 不可用）：能直接下载播放，代价是清晰度偏低
        val play = VideoHttp.getJson(
            "$PLAY_URL?bvid=$bvid&cid=$cid&qn=${qn ?: 80}&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1",
            apiHeaders(cookie),
            "B站取直链"
        )
        val playData = play.optJSONObject("data")
        val durl = playData?.optJSONArray("durl")
        if (durl == null || durl.length() == 0) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "B站既未返回单文件直链、也未返回可合轨的分轨流" +
                    "（code=${play.optInt("code")} msg=${play.optString("message")}）：该视频可能是番剧/付费内容"
            )
        }
        val first = durl.optJSONObject(0)
            ?: throw VideoParseException(VideoParseErrorKind.UNKNOWN, "B站 durl 为空")
        val url = first.optString("url")
        if (url.isBlank()) {
            throw VideoParseException(VideoParseErrorKind.BLOCKED, "B站直链为空（可能需要登录或已被风控）")
        }

        return buildResult(
            data = data,
            bvid = bvid,
            media = VideoMedia(
                url = httpsUrl(url),
                kind = VideoMediaKind.VIDEO,
                fileName = "$fileBase.mp4",
                size = first.optLong("size", -1L),
                headers = mediaHeaders(cookie),
                quality = qualityLabel(playData.optInt("quality"))
            ),
            qualities = qualityOptions(playData),
            selectedQn = playData.optInt("quality")
        )
    }

    /** 组装解析结果（DASH 与单文件两条路径共用，避免字段各写一遍） */
    private fun buildResult(
        data: JSONObject,
        bvid: String,
        media: VideoMedia,
        qualities: List<VideoQuality> = emptyList(),
        selectedQn: Int = 0
    ): VideoParseResult =
        VideoParseResult(
            platform = platform,
            title = data.optString("title"),
            author = data.optJSONObject("owner")?.optString("name").orEmpty(),
            avatar = data.optJSONObject("owner")?.optString("face").orEmpty(),
            cover = httpsUrl(data.optString("pic")),
            text = data.optString("desc"),
            timestampSeconds = data.optLong("pubdate"),
            url = "https://www.bilibili.com/video/$bvid",
            medias = listOf(media),
            qualities = qualities,
            selectedQn = selectedQn
        )

    /** DASH 分轨直链（视频轨 + 音轨 + 清晰度编号 + 可选档位） */
    private data class DashStream(
        val videoUrl: String,
        val audioUrl: String,
        val quality: Int,
        val qualities: List<VideoQuality>
    )

    /**
     * 取 DASH 分轨直链：视频轨取目标清晰度（[qn] 为空则取最高）、音轨取码率最高的一路。
     *
     * ★ `fnval=4048` 是「DASH + HDR + 4K + 杜比」的组合位；`qn` 不指定时用 120（「能给多高给多高」），
     *   实际返回哪几档由账号权限与版权决定。任何一步不对（没有 dash 字段、两轨缺一）
     *   都返回 null，由调用方退回单文件路径 —— 宁可低清可用，也不要在这里抛错。
     */
    private suspend fun dashQuality(bvid: String, cid: Long, cookie: String, qn: Int?): DashStream? {
        val json = runCatching {
            VideoHttp.getJson(
                "$PLAY_URL?bvid=$bvid&cid=$cid&qn=${qn ?: 120}&fnval=4048&fnver=0&fourk=1",
                apiHeaders(cookie),
                "B站取 DASH 直链"
            )
        }.getOrNull() ?: return null
        val data = json.optJSONObject("data") ?: return null
        val dash = data.optJSONObject("dash") ?: return null
        val videos = dash.optJSONArray("video") ?: return null
        val audios = dash.optJSONArray("audio") ?: return null

        val bestVideo = pickVideoTrack(videos, qn) ?: return null
        val bestAudio = (0 until audios.length())
            .mapNotNull { audios.optJSONObject(it) }
            .maxByOrNull { it.optInt("id") } ?: return null

        // 直链字段名有两种（baseUrl / base_url），且可能被下发成 JSON null（optString 会得到 "null"）
        val videoUrl = bestVideo.dashPreferredUrl() ?: return null
        val audioUrl = bestAudio.dashPreferredUrl() ?: return null
        return DashStream(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            quality = bestVideo.optInt("id"),
            qualities = qualityOptions(data)
        )
    }

    /**
     * 从 `dash.video` 里挑一路视频轨。
     *
     * ★ 优先 AVC（codecid=7）：同样的清晰度下 H.264 兼容性最好，合轨后的 mp4 在系统播放器/
     *   JavaFX 里都能直接播；HEVC（12）/AV1（13）只作为兜底。
     * 指定 [qn] 时优先取该档；该档缺失则取不高于它的最高档；全都高于 [qn] 时退回最高档。
     */
    private fun pickVideoTrack(videos: JSONArray, qn: Int?): JSONObject? {
        val all = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
        if (all.isEmpty()) return null
        val pool = all.filter { it.optInt("codecid") == CODEC_AVC }.ifEmpty { all }
        if (qn != null && qn > 0) {
            return pool.firstOrNull { it.optInt("id") == qn }
                ?: pool.filter { it.optInt("id") <= qn }.maxByOrNull { it.optInt("id") }
                ?: pool.maxByOrNull { it.optInt("id") }
        }
        return pool.maxByOrNull { it.optInt("id") }
    }

    /**
     * 解析可选清晰度档位（`playurl` 的 `data`）。
     *
     * 优先用 `support_formats[]`（每项自带 `quality` 与 `new_description`，文案最准）；
     * 老接口只给 `accept_quality[]` 与平行的 `accept_description[]` 两个数组，作为兜底。
     * 结果按 qn 从高到低排序，界面从上到下即「从清晰到模糊」。
     */
    private fun qualityOptions(data: JSONObject): List<VideoQuality> {
        val out = LinkedHashMap<Int, String>()
        data.optJSONArray("support_formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optInt("quality", -1)
                if (q <= 0) continue
                val label = o.optString("new_description")
                    .ifBlank { o.optString("display_desc") }
                    .ifBlank { qualityLabel(q) }
                out.putIfAbsent(q, label)
            }
        }
        if (out.isEmpty()) {
            val qs = data.optJSONArray("accept_quality")
            val ds = data.optJSONArray("accept_description")
            if (qs != null) {
                for (i in 0 until qs.length()) {
                    val q = qs.optInt(i, -1)
                    if (q <= 0) continue
                    val label = ds?.optString(i).orEmpty().ifBlank { qualityLabel(q) }
                    out.putIfAbsent(q, label)
                }
            }
        }
        return out.entries.sortedByDescending { it.key }.map { VideoQuality(it.key, it.value) }
    }

    private fun JSONObject.dashUrl(): String? =
        (optString("baseUrl").takeIf { it.isNotBlank() } ?: optString("base_url"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { httpsUrl(it) }

    /**
     * 该 DASH 流的全部直链：`baseUrl` 在前，`backupUrl` / `backup_url` 随后（去重、规范化 https）。
     *
     * B 站会给同一个流下发多个镜像（`upos-sz-mirror*.bilivideo.com` 为官方 CDN，
     * 而 `baseUrl` 现在经常是 PCDN 节点 `*.mcdn.bilivideo.cn:8082`）。实测 PCDN 单连接
     * 只有几十 KB/s，官方镜像几十 MB/s，所以不能只取 `baseUrl`。
     */
    private fun JSONObject.dashUrls(): List<String> {
        val out = LinkedHashSet<String>()
        dashUrl()?.let { out += it }
        for (key in listOf("backupUrl", "backup_url")) {
            val arr = optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i)
                if (raw.isBlank() || raw == "null") continue
                httpsUrl(raw).takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        return out.toList()
    }

    /** 首选直链：优先非 PCDN 的官方 CDN，全为 PCDN 时退回 baseUrl（保持原行为） */
    private fun JSONObject.dashPreferredUrl(): String? =
        dashUrls().let { urls -> urls.firstOrNull { !isPcdn(it) } ?: urls.firstOrNull() }

    /** PCDN 节点（去中心化分发，单连接限速严重）：主机名里带 mcdn */
    private fun isPcdn(url: String): Boolean =
        url.substringAfter("://", "").substringBefore('/').contains("mcdn", ignoreCase = true)

    /**
     * 取「单文件 mp4」直链专供**在线预览**（DASH 分轨流没法直接喂给 JavaFX）。
     *
     * 走 `fnval=1&platform=html5`：拿到的是音视频已合好的普通 mp4（H.264 + AAC），
     * 清晰度通常比 DASH 低一档，但预览够用、且不下载整片。失败返回空串（预览端会退回主直链，
     * 看到的是「无法在线预览」而不是崩溃）。
     */
    private suspend fun mergedPreviewUrl(bvid: String, cid: Long, cookie: String): String {
        val json = runCatching {
            VideoHttp.getJson(
                "$PLAY_URL?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1",
                apiHeaders(cookie),
                "B站取预览直链"
            )
        }.getOrNull() ?: return ""
        val url = json.optJSONObject("data")?.optJSONArray("durl")
            ?.optJSONObject(0)?.optString("url").orEmpty()
        return httpsUrl(url)
    }

    /** B站清晰度编号 → 展示文案（只列常见的几档，其余原样显示编号） */
    private fun qualityLabel(qn: Int): String = when (qn) {
        6, 7 -> "240P"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        else -> if (qn > 0) "画质 $qn" else ""
    }

    /** 接口偶尔给 `//` 开头的协议相对地址 */
    private fun httpsUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    /** 文件名：标题清洗后截断，标题为空时用兜底名 */
    private fun fileNameBase(title: String, fallback: String): String {
        val cleaned = DownloadPathPolicy.sanitizeName(title).take(80)
        return cleaned.ifBlank { fallback }
    }
}
