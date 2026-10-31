package com.yunx.app.data.download

import java.net.URLDecoder

/**
 * 磁力链接（`magnet:?xt=urn:btih:...`）的识别与信息提取。
 *
 * 为什么是纯字符串实现：磁力链接不是 URL —— 它没有 authority，`?` 后面是一串 `&` 连接的参数
 * （`dn` 里还可能带空格），用 URI 解析会把它拆得七零八落。这里只用 JDK 的 URLDecoder，
 * 保证逻辑是纯 JVM、可单测。
 *
 * 只做「够用」的解析：识别 scheme、取 `dn`（显示名）、取 `xt=urn:btih:` 的 info hash。
 * 真正的种子名与文件列表由 Gopeed 引擎解析出元数据后给出（见 GopeedEngine.taskDetail），不在这里猜。
 */
object MagnetLink {

    private const val SCHEME = "magnet:"

    /** 磁力的 info hash：`xt=urn:btih:<40 位 hex 或 32 位 base32>` */
    private val BTIH = Regex("(?i)xt=urn:btih:([A-Za-z0-9]+)")

    /** `dn` 里的换行/制表符：显示名要进通知栏与列表，先压成空格 */
    private val BLANK_CHARS = Regex("[\\r\\n\\t]+")

    /** 显示名上限：`dn` 可能很长（有的站点把简介都塞进去），截断后 UI 与通知栏才不会被撑爆 */
    private const val MAX_NAME_CHARS = 80

    /** 是不是磁力链接（大小写不敏感，允许前后空白；只有 `magnet:` 光杆不算） */
    fun isMagnet(url: String): Boolean {
        val text = url.trim()
        return text.length > SCHEME.length && text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)
    }

    /**
     * 任务的显示名：优先 `dn=`（磁力自带的显示名），没有就用 info hash 前 8 位占位。
     *
     * ★ 只用于「元数据到手前」的界面/通知显示：磁力在解析出元数据之前拿不到真正的种子名，
     *   下完后会用引擎给的真实名字覆盖（见 DownloadManager.completeEngineTask）。
     */
    fun displayName(url: String): String {
        val dn = param(url, "dn").replace(BLANK_CHARS, " ").trim()
        if (dn.isNotEmpty()) return dn.take(MAX_NAME_CHARS)
        val hash = infoHash(url)
        return if (hash.length >= 8) "磁力_${hash.take(8)}" else "磁力任务"
    }

    /** info hash（统一大写，取不到返回空串）；仅用于显示与日志，不参与去重 */
    fun infoHash(url: String): String =
        BTIH.find(url)?.groupValues?.get(1)?.uppercase().orEmpty()

    /** 取第一个 `key=value` 参数的值（`dn` 理论上唯一，出现多次也只认第一个） */
    private fun param(url: String, key: String): String {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return ""
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (!part.substring(0, eq).equals(key, ignoreCase = true)) continue
            return decode(part.substring(eq + 1)).trim()
        }
        return ""
    }

    /**
     * 磁力参数是 percent-encoding（空格写作 `%20`）：这里把 `+` 按**字面**处理，
     * 真正的 `+`（文件名里很常见）才不会被当成空格吃掉。
     */
    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)
}

/**
 * ed2k 链接（`ed2k://|file|<文件名>|<字节数>|<hash>|/`）的识别与信息提取。
 *
 * 与磁力一样是「非 URL」的伪协议：`|` 分段、段内不转义，用 URI 解析只会得到空 host。
 * 只处理 `file` 类型（`server` / `serverlist` 两类不产生下载任务，直接不识别）。
 *
 * ★ ed2k 同样只有 Gopeed 内核里有实现（内核注册了 hls/http/bt/ed2k 四个协议），
 *   内置分片下载器是纯 HTTP Range 实现 —— 必须整体交给引擎（见 P2PLink）。
 */
object Ed2kLink {

    private const val SCHEME = "ed2k://"

    /** 是不是 ed2k **文件**链接（区分大小写不敏感，允许前后空白） */
    fun isEd2k(url: String): Boolean = parts(url)?.first == "file"

    /** 链接自带的文件名（`ed2k://|file|<文件名>|…` 里 `file` 段之后的第 1 段）；取不到返回空串 */
    fun fileName(url: String): String {
        val p = parts(url) ?: return ""
        if (p.first != "file") return ""
        return p.second.getOrNull(1).orEmpty().replace(BLANK_CHARS, " ").trim().take(MAX_NAME_CHARS)
    }

    /** 链接自带的文件大小（`file` 段之后的第 2 段，字节）；取不到或非法返回 -1 */
    fun fileSize(url: String): Long {
        val p = parts(url) ?: return -1L
        if (p.first != "file") return -1L
        return p.second.getOrNull(2)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
    }

    /** `ed2k://` 之后的段列表（第 0 段是类型：file / server / serverlist）。
     *  链接写作 `ed2k://|file|…`：第一个 `|` 之前是空的，切分后要先丢掉空段，类型才落在第 0 段。 */
    private fun parts(url: String): Pair<String, List<String>>? {
        val text = url.trim()
        if (text.length <= SCHEME.length) return null
        if (!text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
        val segments = text.substring(SCHEME.length).split('|').filter { it.isNotEmpty() }
        return segments.firstOrNull().orEmpty().lowercase() to segments
    }

    /** 文件名里的换行/制表符（要进列表与通知栏，先压成空格） */
    private val BLANK_CHARS = Regex("[\\r\\n\\t]+")

    /** 显示名上限：与 [MagnetLink] 取同一口径，避免超长名字撑爆界面 */
    private const val MAX_NAME_CHARS = 80
}

/**
 * 只能交给 Gopeed 引擎的 P2P 资源（磁力 / ed2k / 本地 .torrent 种子文件）的统一入口。
 *
 * 内置分片下载器是纯 HTTP Range 实现，喂它 `magnet:` / `ed2k://` / 种子文件路径只会得到和
 * 「该去装引擎」毫无关系的协议错误 —— 所有调用点（添加弹窗、入队分流、重新下载）都从这里判类型，
 * 新增一种 P2P 形式时只改这一处。
 */
object P2PLink {

    /** 是否需要 Gopeed 引擎（内置下载器不支持） */
    fun matches(url: String): Boolean =
        MagnetLink.isMagnet(url) || Ed2kLink.isEd2k(url) || isTorrentFile(url)

    /** 是不是**本地** `.torrent` 种子文件路径（文件选择/拖入得到）。
     *  http(s) 直链指向的 .torrent 不在其列：那是普通下载，不该按 BT 处理。 */
    fun isTorrentFile(url: String): Boolean {
        val text = url.trim()
        if (text.contains("://")) return false
        return text.length > TORRENT_SUFFIX.length && text.endsWith(TORRENT_SUFFIX, ignoreCase = true)
    }

    /** 归档用的平台标识：ed2k 单独记，磁力与种子文件同属 BT（都记 [DownloadPlatform.MAGNET]） */
    fun platformFor(url: String): String =
        if (Ed2kLink.isEd2k(url)) DownloadPlatform.ED2K else DownloadPlatform.MAGNET

    /** 任务显示名：磁力取 `dn=`，ed2k 取链接自带文件名，种子文件取文件名（去扩展名）；取不到返回空串 */
    fun displayName(url: String): String = when {
        MagnetLink.isMagnet(url) -> MagnetLink.displayName(url)
        Ed2kLink.isEd2k(url) -> Ed2kLink.fileName(url)
        isTorrentFile(url) -> torrentDisplayName(url)
        else -> ""
    }

    /** 链接自带的文件大小（仅 ed2k 有）；未知返回 -1 */
    fun knownSize(url: String): Long = if (Ed2kLink.isEd2k(url)) Ed2kLink.fileSize(url) else -1L

    /** 种子文件名去掉 `.torrent` 后缀（真正的种子名等引擎解析出元数据后会覆盖它） */
    private fun torrentDisplayName(url: String): String {
        val base = url.trim().replace('\\', '/').substringAfterLast('/')
        return if (base.length > TORRENT_SUFFIX.length && base.endsWith(TORRENT_SUFFIX, ignoreCase = true)) {
            base.dropLast(TORRENT_SUFFIX.length)
        } else {
            base
        }
    }

    private const val TORRENT_SUFFIX = ".torrent"
}
