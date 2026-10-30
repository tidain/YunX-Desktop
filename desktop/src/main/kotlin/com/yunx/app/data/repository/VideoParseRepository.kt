package com.yunx.app.data.repository

import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.video.DetectedVideoLink
import com.yunx.app.data.network.video.VideoHttp
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoParseErrorKind
import com.yunx.app.data.network.video.VideoParseException
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.VideoParsers
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.data.network.video.QualitySelectableParser
import okhttp3.OkHttpClient

/**
 * 视频链接解析仓库：把「识别链接 → 展开短链 → 按平台取 Cookie → 交给解析器」串成一步。
 *
 * 存在的意义：解析页只拿到一段用户文本，不应该知道短链展开、账号 Cookie 这些细节；
 * 各平台解析器也只需要「已识别的链接 + Cookie」。
 *
 * ★ 可见性：构造函数是 internal 的（参数 [VideoAccountRepository] 是 internal），
 *   但类本身是 public —— 否则 public 的 `ResolveViewModel` / `Factory` 无法把它放进构造签名。
 */
class VideoParseRepository internal constructor(
    private val accounts: VideoAccountRepository,
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {

    /** 从任意文本里识别并解析视频；识别不到视频链接返回 null（调用方据此回退到网盘解析） */
    suspend fun parse(text: String, qualityQn: Int? = null): VideoParseResult? {
        val link = VideoLinkParser.parse(text) ?: return null
        return parse(link, qualityQn)
    }

    /**
     * 解析一条已识别的链接；识别到但解析失败一律抛 [VideoParseException]。
     *
     * @param qualityQn 目标清晰度编号；null = 跟随平台默认（取最高可用档）。
     *   只有实现了 [QualitySelectableParser] 的解析器（B站）支持指定，其它平台忽略该参数。
     */
    suspend fun parse(link: DetectedVideoLink, qualityQn: Int? = null): VideoParseResult {
        val resolved = if (link.isShortLink) expandShortLink(link) else link
        val parser = VideoParsers.of(resolved.platform)
            ?: throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                "暂不支持解析 ${resolved.platform.displayName}"
            )
        // Cookie 用「当前生效账号」（主账号优先）；未登录为空串，由解析器决定匿名可用还是提示登录
        val cookie = accounts.activeCookie(resolved.platform)
        val result = if (qualityQn != null && parser is QualitySelectableParser) {
            parser.parseWithQuality(resolved, cookie, qualityQn)
        } else {
            parser.parse(resolved, cookie)
        }
        return result.withSecureMediaUrls()
    }

    /**
     * 展开短链（v.douyin.com / xhslink.com / b23.tv …）：跟随一次重定向拿到最终地址后重新识别。
     * 最多跟 5 跳防御性兜底；拿到终址却识别不出资源 id 时，退回带终址的原结果交给解析器处理。
     */
    private suspend fun expandShortLink(link: DetectedVideoLink): DetectedVideoLink {
        var current = link
        repeat(5) {
            val next = VideoHttp.expandRedirect(
                url = current.url,
                // ★ 短链也要带对 UA：抖音的分享短链认 iOS UA（参考实现用的就是 ios_headers），
                //   拿桌面 UA 去请求容易被引导到需要 JS 的页面，拿不到 Location
                headers = mapOf("User-Agent" to shortLinkUserAgent(current.platform)),
                clientProvider = clientProvider
            ) ?: return current
            val reidentified = VideoLinkParser.parse(next) ?: return current.copy(url = next)
            if (!reidentified.isShortLink) return reidentified
            current = reidentified
        }
        return current
    }

    private fun shortLinkUserAgent(platform: VideoPlatform): String = when (platform) {
        // 抖音与快手的分享短链都认移动端 UA（参考实现用的是 ios_headers）：
        // 拿桌面 UA 请求时会被引到「桌面 SPA」页，既拿不到 Location 也拿不到 SSR 数据
        VideoPlatform.DOUYIN, VideoPlatform.KUAISHOU -> VideoHttp.UA_IPHONE
        else -> VideoHttp.UA_CHROME
    }

    /**
     * 把结果里所有直链归一化成 https（含 B站 DASH 的音轨）。
     *
     * ★ 只在这一处做：所有平台都从这里出结果，新增平台不必各自记得处理；
     *   而不做的后果是「预览 + 下载同时失败」（小红书返回的就是 http 明文链），
     *   原因藏在系统网络策略里、光看解析结果看不出来 —— 详见 [VideoHttp.secureUrl]。
     */
    private fun VideoParseResult.withSecureMediaUrls(): VideoParseResult = copy(
        medias = medias.map {
            it.copy(
                url = VideoHttp.secureUrl(it.url),
                audioUrl = VideoHttp.secureUrl(it.audioUrl)
            )
        }
    )
}
