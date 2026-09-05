package com.yunx.app.ui.jcef

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.AppContext
import com.yunx.app.util.Log
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefSettings
import java.io.File

/**
 * JCEF（内嵌 Chromium）全局持有者。
 *
 * 初始化方式（启动优化）：
 * - Windows/Linux：后台线程初始化（initInBackground），不阻塞窗口显示。
 *   依据：CefApp 的 CEF 消息循环由内部 workTimer_（Swing Timer）在 AWT EDT 上泵动，
 *   与调用 CefInitialize 的线程无关；且消息泵本来就要等 EDT 转起来才工作。
 * - macOS：保持阻塞式主线程初始化（AppKit 要求主线程）。
 *
 * 可靠性策略：
 * - 单次初始化最多尝试 [MAX_INIT_ATTEMPTS] 次（含短退避），失败常见诱因是上次异常退出
 *   残留的 jcef_helper.exe 锁住缓存目录，每次尝试前先清理残留子进程；
 * - 失败不熔断：登录页打开时会再次调用 [initInBackground] 触发新一轮尝试，
 *   成功后 [browserReady] 翻转，登录页自动从粘贴模式切回内嵌浏览器模式。
 */
object JcefHolder {

    private const val TAG = "YunX-JCEF"
    private const val MAX_INIT_ATTEMPTS = 3

    @Volatile
    private var cefApp: CefApp? = null

    /** 最近一次初始化失败的原因（成功或未开始时为 null），登录页可用于提示 */
    @Volatile
    var initFailure: Throwable? = null
        private set

    /** Compose 可观察状态：CEF 初始化成功后为 true，登录页据此自动启用内嵌浏览器 */
    var browserReady by mutableStateOf(false)
        private set

    private val lock = Any()
    private var initThread: Thread? = null

    fun initBlocking(noSandbox: Boolean = false) {
        synchronized(lock) {
            if (browserReady) return
            var lastError: Throwable? = null
            for (attempt in 1..MAX_INIT_ATTEMPTS) {
                try {
                    if (attempt > 1) {
                        Log.w(TAG, "JCEF init retry #$attempt")
                        Thread.sleep(600L * (attempt - 1))
                    }
                    killStaleJcefHelpers()
                    val builder = CefAppBuilder()
                    builder.setInstallDir(File(AppContext.cacheDir, "jcef-bundle"))
                    if (noSandbox) {
                        // 受限/沙箱环境（如 CI）下 Chromium 进程沙箱拿不到目录授权，禁用之（仅诊断冒烟用）
                        builder.addJcefArgs("--no-sandbox")
                    }
                    val settings = builder.getCefSettings()
                    settings.windowless_rendering_enabled = false
                    // CEF 要求 cache_path 必须是 root_cache_path 的子目录
                    settings.root_cache_path = File(AppContext.cacheDir, "jcef").absolutePath
                    settings.cache_path = File(AppContext.cacheDir, "jcef/cache").absolutePath
                    settings.log_file = File(AppContext.filesDir, "jcef.log").absolutePath
                    settings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
                    cefApp = builder.build()
                    browserReady = true
                    initFailure = null
                    Log.i(TAG, "JCEF initialized (attempt $attempt)")
                    return
                } catch (t: Throwable) {
                    lastError = t
                    Log.e(TAG, "JCEF init attempt $attempt failed", t)
                }
            }
            initFailure = lastError
            Log.e(TAG, "JCEF init failed after $MAX_INIT_ATTEMPTS attempts, fallback to paste mode")
        }
    }

    /**
     * 后台线程初始化：立即返回，不阻塞调用线程。
     * - 已就绪或已有初始化线程在跑时调用是空操作；
     * - 上一次尝试失败后再次调用会开启新一轮重试（登录页打开时调用，见 CookieLoginContent）。
     * 完成后 [browserReady] 置为 true，登录页自动切换为内嵌浏览器模式。
     */
    fun initInBackground(noSandbox: Boolean = false) {
        synchronized(lock) {
            if (browserReady) return
            if (initThread?.isAlive == true) return
            initThread = Thread({ initBlocking(noSandbox) }, "JCEF-Init").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun app(): CefApp? = cefApp

    fun isAvailable(): Boolean = browserReady

    /**
     * 清理上次异常退出残留的 CEF 子进程：它们持有缓存目录/配置锁，
     * 不清理会导致下一次 CefInitialize 间歇性失败（"内嵌浏览器经常初始化失败"的根因）。
     * 只杀可执行文件位于本应用 jcef-bundle 目录下的进程，不误伤系统其他 CEF 应用。
     */
    private fun killStaleJcefHelpers() {
        runCatching {
            val bundleDir = File(AppContext.cacheDir, "jcef-bundle")
                .canonicalPath.lowercase().replace('/', '\\')
            ProcessHandle.allProcesses().forEach { ph ->
                runCatching {
                    val cmd = ph.info().command().orElse("").lowercase().replace('/', '\\')
                    if (cmd.startsWith(bundleDir)) {
                        Log.w(TAG, "kill stale jcef helper pid=${ph.pid()}: $cmd")
                        ph.destroyForcibly()
                    }
                }
            }
            // 给被杀进程一点时间释放文件锁
            Thread.sleep(200)
        }
    }

    /** 进程退出前释放（尽力而为；进程即将结束，失败无碍） */
    fun disposeQuietly() {
        runCatching { cefApp?.dispose() }.onFailure {
            Log.w(TAG, "CefApp dispose failed: ${it.message}")
        }
        cefApp = null
    }
}
