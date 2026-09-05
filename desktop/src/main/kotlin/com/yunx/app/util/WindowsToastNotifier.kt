package com.yunx.app.util

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows 通知中心下载进度（toast 进度条）：
 * - 常驻 PowerShell 子进程持有 WinRT ToastNotificationManager，Kotlin 通过 stdin 逐行喂进度；
 * - AUMID 注册在 HKCU\Software\Classes\AppUserModelId\YunX.Desktop（通知显示「云析」与应用图标）；
 * - 同 tag/group 重发会原地替换 Action Center 里的既有 toast（进度条原地刷新，不刷屏）。
 * 非 Windows 平台全部 no-op。
 */
object WindowsToastNotifier {
    private const val AUMID = "YunX.Desktop"
    private const val UPDATE_THROTTLE_MS = 1000L

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private val starting = AtomicBoolean(false)
    private val lastUpdateTs = AtomicLong(0)

    /** 会话内最后一次发送的聚合进度（会话结束时判断是否弹完成通知） */
    @Volatile private var lastDoneSum = 0L
    @Volatile private var lastTotalSum = 0L

    /** 下载会话开始（首个任务启动时调用，幂等） */
    @Synchronized
    fun sessionStart() {
        if (!isWindows || proc != null || !starting.compareAndSet(false, true)) return
        try {
            val script = extractScript() ?: return
            registerAumid()
            val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
                .redirectErrorStream(false)
            val p = pb.start()
            // 必须消费 stderr，否则 PS 报错写满管道缓冲会把子进程卡死
            Thread {
                p.errorStream.use { it.readBytes() }
            }.isDaemon = true
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            proc = p
        } catch (e: Exception) {
            Log.w(TAG, "toast session start failed: ${e.message}")
        } finally {
            starting.set(false)
        }
    }

    /**
     * 聚合进度更新（内部 1s 节流）。
     * @param line1 主标题（文件名 / N 个任务）
     * @param statusLine 第二行说明（大小信息）
     * @param speedText 速度文本（空串则只显示百分比）
     * @param statusLabel 进度条状态标签
     */
    @Synchronized
    fun updateProgress(doneSum: Long, totalSum: Long, line1: String, statusLine: String, speedText: String, statusLabel: String) {
        lastDoneSum = doneSum
        lastTotalSum = totalSum
        if (!isWindows || writer == null) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateTs.get() < UPDATE_THROTTLE_MS) return
        lastUpdateTs.set(now)
        val pct = if (totalSum > 0) ((doneSum * 100) / totalSum).toInt().coerceIn(0, 99) else 0
        val value = if (speedText.isBlank()) "$pct%" else "$speedText · $pct%"
        sendLine("prog|${esc(line1)}|$pct|${esc(statusLine)}|${esc(value)}|${esc(statusLabel)}")
    }

    /**
     * 下载会话结束（全部任务停止：完成/暂停/失败）。
     * 全部下载完成时弹完成通知，否则静默清理进度条。
     */
    @Synchronized
    fun sessionEnd() {
        if (!isWindows) return
        val completed = lastTotalSum > 0 && lastDoneSum >= lastTotalSum
        lastDoneSum = 0; lastTotalSum = 0
        if (completed) {
            sendLine("done|${esc("云析 · 下载完成")}|${esc("全部任务已下载完毕")}")
            closeQuietly()
        } else {
            sendLine("exit")
            closeQuietly()
        }
    }

    private fun sendLine(line: String) {
        try {
            val w = writer ?: return
            w.write(line)
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "toast write failed: ${e.message}")
        }
    }

    private fun closeQuietly() {
        runCatching { writer?.close() }
        writer = null
        proc = null
    }

    /** 通知显示名「云析」+ 图标（存在 exe 旁的 ico 时） */
    private fun registerAumid() {
        try {
            val key = "Software\\Classes\\AppUserModelId\\$AUMID"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "DisplayName", "云析")
            iconPath()?.let { Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "IconUri", it) }
        } catch (e: Exception) {
            Log.w(TAG, "AUMID register failed: ${e.message}")
        }
    }

    private fun iconPath(): String? {
        val exePath = runCatching {
            ProcessHandle.current().info().command().orElse(null)
        }.getOrNull() ?: return null
        val ico = File(exePath).parentFile?.resolve("YunX-Desktop.ico") ?: return null
        return if (ico.isFile) ico.toURI().toString() else null
    }

    /** 从 classpath 解包 toast_progress.ps1 到临时目录（jar 内资源无法直接执行） */
    private fun extractScript(): File? {
        return try {
            val stream = WindowsToastNotifier::class.java.classLoader.getResourceAsStream("toast_progress.ps1")
                ?: return null
            val out = File(System.getProperty("java.io.tmpdir"), "yunx-toast.ps1")
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        } catch (e: Exception) {
            Log.w(TAG, "extract toast script failed: ${e.message}")
            null
        }
    }

    /** XML 转义（文件名可能含 & < > 等） */
    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val TAG = "YunX-Toast"
}
