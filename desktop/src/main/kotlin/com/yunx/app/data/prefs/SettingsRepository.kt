package com.yunx.app.data.prefs

import com.yunx.app.data.download.DownloadPlatform
import java.util.prefs.Preferences

/**
 * 应用设置（桌面版以 java.util.prefs 持久化，键与原 SharedPreferences 完全一致）。
 */
class SettingsRepository {

    private val prefs: Preferences = Preferences.userRoot().node("yunx/settings")

    /** 下载线程数（通用/手动添加，分片并发数），默认 32，上限 512 */
    var downloadThreads: Int
        get() = downloadThreadsFor(DownloadPlatform.GENERIC)
        set(value) = setDownloadThreads(DownloadPlatform.GENERIC, value)

    /** 获取指定平台的下载线程数；迅雷固定 8，其余默认 32、上限 512 */
    fun downloadThreadsFor(platform: String): Int {
        if (platform == DownloadPlatform.XUNLEI) return XUNLEI_DOWNLOAD_THREADS
        return prefs.getInt(prefsKey(platform), DEFAULT_DOWNLOAD_THREADS)
            .coerceIn(1, MAX_DOWNLOAD_THREADS)
    }

    /** 设置指定平台的下载线程数；迅雷不可修改 */
    fun setDownloadThreads(platform: String, value: Int) {
        if (platform == DownloadPlatform.XUNLEI) return
        prefs.putInt(prefsKey(platform), value.coerceIn(1, MAX_DOWNLOAD_THREADS))
    }

    private fun prefsKey(platform: String): String =
        if (platform.isBlank() || platform == DownloadPlatform.GENERIC) "download_threads"
        else "download_threads_$platform"

    /** 自定义下载保存目录（桌面版为绝对路径字符串；原 Android 为 SAF tree Uri）；null/空 = 系统默认「下载」目录 */
    var downloadDirUri: String?
        get() = prefs.get("download_dir_uri", null)
        set(value) {
            if (value.isNullOrBlank()) prefs.remove("download_dir_uri") else prefs.put("download_dir_uri", value)
        }

    /** 最大同时下载任务数（默认 1：前台任务吃满带宽，其余排队） */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.putInt("max_concurrent_downloads", value.coerceIn(1, 10))
        }

    /** 下载速度限制（字节/秒；0 = 不限速） */
    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L)
        set(value) {
            prefs.putLong("download_speed_limit", value.coerceAtLeast(0L))
        }

    /** 下载失败后自动重试次数（默认 3，范围 0-10） */
    var downloadRetryCount: Int
        get() = prefs.getInt("download_retry_count", DEFAULT_DOWNLOAD_RETRY_COUNT)
        set(value) {
            prefs.putInt("download_retry_count", value.coerceIn(0, 10))
        }

    /** 下载时阻止电脑休眠（Windows：任务运行中 SetThreadExecutionState 阻止系统睡眠） */
    var keepAwakeWhileDownloading: Boolean
        get() = prefs.getBoolean("keep_awake_downloading", prefs.getBoolean("keep_download_when_locked", true))
        set(value) {
            prefs.putBoolean("keep_awake_downloading", value)
        }

    /** 通知中心进度样式（Windows 通知中心 toast：true 时进度条附带下载速度） */
    var notificationShowSpeed: Boolean
        get() = prefs.getBoolean("notification_show_speed", true)
        set(value) {
            prefs.putBoolean("notification_show_speed", value)
        }

    /** 桌面图标样式（桌面版无 activity-alias，保留设置项占位） */
    var appIconVariant: Int
        get() = prefs.getInt("app_icon_variant", 0)
        set(value) {
            prefs.putInt("app_icon_variant", value.coerceIn(0, 1))
        }

    /** 忽略 SSL 证书校验（抓包调试用；桌面版 OkHttp 会实际生效） */
    var ignoreSslCert: Boolean
        get() = prefs.getBoolean("ignore_ssl_cert", false)
        set(value) {
            prefs.putBoolean("ignore_ssl_cert", value)
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.putBoolean("baidu_limit_hint_dismissed", value)
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.putInt("dark_mode", value.coerceIn(0, 2))
        }

    /** 主题色模式：0=默认蓝色，1=默认蓝色，2=自定义种子色（桌面无动态取色，0 与 1 等价） */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.putInt("theme_color_mode", value.coerceIn(0, 2))
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.putLong("theme_seed_color", value)
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 32
        const val MAX_DOWNLOAD_THREADS = 512
        const val XUNLEI_DOWNLOAD_THREADS = 8
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 1
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}
