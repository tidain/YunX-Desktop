package com.yunx.app.data.network

import com.yunx.app.data.download.P2PLink
import com.yunx.app.data.network.video.VideoLinkParser

/**
 * 主页输入框「这段文本该交给谁」的纯逻辑分流。
 *
 * 先判 P2P（磁力 / ed2k / 本地种子文件）再判视频链接：磁力链接的 `dn` 里可能带视频站地址，
 * 但整条串以 `magnet:` 开头，应该按 P2P 处理。两者都不是时仍走网盘 / GitHub 解析。
 */
object LinkInputRouter {

    enum class Target {
        /** 视频平台链接：交给视频解析页 */
        VIDEO,

        /** 磁力 / ed2k / 本地 .torrent：交给下载页的「添加下载任务」 */
        P2P,

        /** 其余：网盘分享链接、GitHub 链接、中文口令等，走解析页原有链路 */
        RESOLVE
    }

    fun targetOf(text: String): Target = when {
        P2PLink.matches(text) -> Target.P2P
        VideoLinkParser.looksLikeVideoLink(text) -> Target.VIDEO
        else -> Target.RESOLVE
    }
}
