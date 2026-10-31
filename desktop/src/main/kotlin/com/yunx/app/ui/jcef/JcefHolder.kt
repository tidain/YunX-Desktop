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

    /** 隔离目录前缀：`cache\jcef.crashed-<时间>` */
    private const val QUARANTINE_PREFIX = "jcef.crashed-"

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

    /** 一次性：本次进程是否已做过「上次异常退出」的检测（多次重试只做一次） */
    @Volatile
    private var crashRecoveryDone = false

    fun initBlocking() {
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
                    recoverFromAbnormalExit()
                    val builder = CefAppBuilder()
                    val installDir = File(AppContext.cacheDir, "jcef-bundle")
                    builder.setInstallDir(installDir)
                    // 发布包带的是 app\jcef-natives.tar.gz（不含原生 jar）：
                    // 自己解包到 installDir 后让 jcefmaven 跳过安装步骤。开发运行无此压缩包，
                    // ensureInstalled 返回 false，仍由 jcefmaven 从 classpath 的原生 jar 安装。
                    if (JcefNativesInstaller.ensureInstalled(installDir)) {
                        builder.setSkipInstallation(true)
                    }
                    val settings = builder.getCefSettings()
                    // 发布包只随带 locales/zh-CN.pak（见 tools/JcefNativesPacker.kt），
                    // 这里显式指定 locale，避免按系统语言去找不存在的语言包
                    settings.locale = "zh-CN"
                    settings.windowless_rendering_enabled = false
                    // CEF 要求 cache_path 必须是 root_cache_path 的子目录
                    settings.root_cache_path = File(AppContext.cacheDir, "jcef").absolutePath
                    settings.cache_path = File(AppContext.cacheDir, "jcef/cache").absolutePath
                    settings.log_file = File(AppContext.filesDir, "jcef.log").absolutePath
                    settings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_WARNING
                    cefApp = builder.build()
                    browserReady = true
                    initFailure = null
                    markRunning()
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
    fun initInBackground() {
        synchronized(lock) {
            if (browserReady) return
            if (initThread?.isAlive == true) return
            initThread = Thread({ initBlocking() }, "JCEF-Init").apply {
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
        // 正常释放：清掉运行标记，避免下次启动被误判为异常退出
        runCatching { runningMarker().delete() }
    }

    /** 运行中标记：CEF 初始化成功后写入，正常退出时删除；启动时若仍存在，说明上次没有正常释放 */
    private fun runningMarker(): File = File(AppContext.cacheDir, ".yunx-jcef-running")

    private fun markRunning() {
        runCatching { runningMarker().writeText(System.currentTimeMillis().toString()) }
            .onFailure { Log.w(TAG, "写入运行标记失败: ${it.message}") }
    }

    /**
     * 处理上次异常退出（issue #34）：
     * 启动时若发现「CEF 已初始化成功、但没有正常释放」的残留标记，说明上个进程多半是被 libcef 的
     * fail-fast 直接带走（日志最后一行停在 `JCEF initialized`，随后 csrss 弹「中断点 已到达中断点」，
     * 退出码 0x80000003）。这种情况下 CEF 用户数据目录（cache\jcef）往往已损坏且不会自愈，
     * 因此把它整体隔离（重命名为 `jcef.crashed-<时间>`，留一份供排查）后由 CEF 重建全新 profile。
     * 只隔离一次：隔离 / 删除成功才清标记，失败则保留标记供下次启动重试。
     */
    private fun recoverFromAbnormalExit() {
        if (crashRecoveryDone) return
        crashRecoveryDone = true
        val marker = runningMarker()
        if (!marker.isFile) return
        Log.w(TAG, "检测到上次异常退出（CEF 未正常释放），隔离并重建浏览器数据目录")
        if (quarantineProfile()) {
            runCatching { marker.delete() }
        } else {
            Log.w(TAG, "浏览器数据目录隔离失败，保留标记供下次启动重试")
        }
    }

    /** 隔离（优先重命名，失败则退回删除）疑似损坏的 CEF profile；处理成功返回 true */
    private fun quarantineProfile(): Boolean {
        val profile = File(AppContext.cacheDir, "jcef")
        if (!profile.exists()) return true
        val quarantined = File(AppContext.cacheDir, "$QUARANTINE_PREFIX${System.currentTimeMillis()}")
        if (runCatching { profile.renameTo(quarantined) }.getOrDefault(false)) {
            Log.w(TAG, "已将疑似损坏的浏览器数据目录隔离到 ${quarantined.name}")
            pruneQuarantines(keep = quarantined.name)
            return true
        }
        Log.w(TAG, "重命名隔离失败，改为直接删除浏览器数据目录")
        return runCatching { profile.deleteRecursively() }.getOrDefault(false)
    }

    /** 只保留最近一次隔离目录，避免崩溃反复发生时把磁盘撑满 */
    private fun pruneQuarantines(keep: String) {
        AppContext.cacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(QUARANTINE_PREFIX) && it.name != keep }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }
}
