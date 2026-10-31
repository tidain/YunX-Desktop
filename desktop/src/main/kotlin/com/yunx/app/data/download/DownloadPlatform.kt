package com.yunx.app.data.download

import com.yunx.app.data.network.video.VideoPlatform

/**
 * 下载来源平台标识（用于按平台独立设置下载线程数）。
 * 字符串常量而非枚举：便于直接持久化到 Room 字段，也与各 ViewModel 解耦。
 */
object DownloadPlatform {
    const val QUARK = "quark"
    const val UC = "uc"
    const val XUNLEI = "xunlei"
    const val BAIDU = "baidu"
    const val C139 = "c139"
    const val PAN123 = "pan123"
    const val PAN115 = "pan115"
    /** 光鸭云盘 */
    const val GUANGYA = "guangya"
    /** 蓝奏云优享版 */
    const val ILANZOU = "ilanzou"
    /** 蓝奏云 */
    const val LANZOU = "lanzou"
    /** 磁力链接（BT）：只能交给 Gopeed 引擎，内置分片下载器不支持 */
    const val MAGNET = "magnet"
    /** ed2k 链接：同样只有 Gopeed 引擎里有实现，内置分片下载器不支持 */
    const val ED2K = "ed2k"
    /** GitHub 仓库/Release 下载 */
    const val GITHUB = "github"
    /** 通用/未知来源（手动添加、应用更新下载等） */
    const val GENERIC = "generic"
    /** 浏览器插件劫持的下载（任务级线程数由用户在弹窗里单独选择） */
    const val BROWSER = "browser"
    /** 视频平台（抖音/B站等）：任务来源展示与按平台线程数设置 */
    const val VIDEO = "video"

    // 视频平台细分：每个平台可以单独设置线程数（旧的 VIDEO 任务仍按 VIDEO 的键兜底）
    const val VIDEO_BILIBILI = "video_bilibili"
    const val VIDEO_DOUYIN = "video_douyin"
    const val VIDEO_KUAISHOU = "video_kuaishou"
    const val VIDEO_WEIBO = "video_weibo"
    const val VIDEO_XHS = "video_xhs"
    const val VIDEO_ACFUN = "video_acfun"
    const val VIDEO_PIXIV = "video_pixiv"

    /** 视频解析平台 → 线程数设置用的平台标识 */
    fun forVideoPlatform(platform: VideoPlatform): String = when (platform) {
        VideoPlatform.BILIBILI -> VIDEO_BILIBILI
        VideoPlatform.DOUYIN -> VIDEO_DOUYIN
        VideoPlatform.KUAISHOU -> VIDEO_KUAISHOU
        VideoPlatform.WEIBO -> VIDEO_WEIBO
        VideoPlatform.XHS -> VIDEO_XHS
        VideoPlatform.ACFUN -> VIDEO_ACFUN
        VideoPlatform.PIXIV -> VIDEO_PIXIV
    }
}
