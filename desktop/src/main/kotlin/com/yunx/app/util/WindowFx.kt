package com.yunx.app.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows 窗口过渡特效。
 *
 * 背景：AWT/Swing 顶层窗口销毁瞬间会以窗口类背景色（白色）重绘一帧，
 * 深色主题下表现为"关闭时闪白屏"。这里在真正销毁前把整窗透明度
 * （WS_EX_LAYERED + SetLayeredWindowAttributes）动画到 0 并隐藏窗口，
 * 再执行退出，肉眼即不可见销毁过程。
 *
 * 非 Windows 或 API 不可用时直接执行回调，行为退化为原有关闭逻辑。
 */
object WindowFx {

    private interface User32Native : Library {
        fun GetWindowLongW(hwnd: WinDef.HWND, nIndex: Int): Int
        fun SetWindowLongW(hwnd: WinDef.HWND, nIndex: Int, dwNewLong: Int): Int
        fun SetLayeredWindowAttributes(hwnd: WinDef.HWND, crKey: Int, bAlpha: Byte, dwFlags: Int): Boolean
    }

    private val user32Native: User32Native? by lazy {
        runCatching {
            if (!WindowsTitleBar.isWindows) return@lazy null
            Native.load("user32", User32Native::class.java)
        }.getOrNull()
    }

    private const val GWL_EXSTYLE = -20
    private const val WS_EX_LAYERED = 0x00080000
    private const val LWA_ALPHA = 2

    /** 防止重复触发（双击 X / WM_CLOSE 重入） */
    private val fading = AtomicBoolean(false)

    /**
     * 启动淡入（启动器闪屏模式，YUNXPC_SPLASH=1）：在 Compose 主窗口显示的瞬间把整窗
     * 透明度压到 0（轮询 isDisplayable，确保不晚于首帧上屏），等待 [settleMs] 让内容
     * 完成首帧组合与绘制，再在 EDT 上以 [durationMs] 渐入到不透明，与启动器闪屏淡出
     * 交叉衔接。可见性完全由 Compose 控制（外部 setVisible 会被 Compose 状态同步回滚，
     * 导致窗口永不显示）。不支持逐像素透明时退化为直接显示。
     */
    fun scheduleFadeIn(frame: java.awt.Frame, settleMs: Long = 250L, durationMs: Int = 190) {
        Thread {
            try {
                // 等待窗口进入可显示状态（Compose 在首帧组合后才 show），一旦可显示立即压全透明
                var waited = 0L
                while (!frame.isDisplayable && waited < 5000) {
                    Thread.sleep(10)
                    waited += 10
                }
                EventQueue.invokeLater { runCatching { frame.opacity = 0f } }
                Thread.sleep(settleMs)
                val steps = 16
                var step = 0
                EventQueue.invokeLater {
                    val timer = javax.swing.Timer(durationMs / steps) { evt ->
                        step++
                        runCatching {
                            frame.opacity = if (step >= steps) 1f else step / steps.toFloat()
                        }
                        if (step >= steps) (evt.source as javax.swing.Timer).stop()
                    }
                    timer.isRepeats = true
                    timer.start()
                }
            } catch (t: Throwable) {
                Log.w("YunX-Frame", "startup fade-in failed: ${t.message}")
                runCatching { EventQueue.invokeLater { frame.opacity = 1f } }
            }
        }.apply {
            isDaemon = true
            name = "window-fade-in"
        }.start()
    }

    /**
     * 淡出窗口后执行 [onDone]（一般为退出应用）。
     * 动画在后台线程执行（透明度由 DWM 合成，不依赖 UI 线程重绘），
     * 完成后回 EDT 隐藏窗口并执行回调。
     */
    fun fadeOutThen(frame: java.awt.Frame, onDone: () -> Unit) {
        val api = user32Native
        if (api == null || !fading.compareAndSet(false, true)) {
            onDone()
            return
        }
        val title = frame.title
        Thread {
            val faded = runCatching {
                val hwnd = findHwnd(title) ?: return@runCatching false
                // 先加分层样式并设为不透明（加样式瞬间窗口会停止渲染，必须立刻设属性）
                val ex = api.GetWindowLongW(hwnd, GWL_EXSTYLE)
                api.SetWindowLongW(hwnd, GWL_EXSTYLE, ex or WS_EX_LAYERED)
                api.SetLayeredWindowAttributes(hwnd, 0, 255.toByte(), LWA_ALPHA)
                var alpha = 255
                while (alpha > 0) {
                    alpha = maxOf(0, alpha - 14)
                    api.SetLayeredWindowAttributes(hwnd, 0, alpha.toByte(), LWA_ALPHA)
                    Thread.sleep(10)
                }
                true
            }.getOrDefault(false)
            EventQueue.invokeLater {
                runCatching { if (faded) frame.isVisible = false }
                onDone()
            }
        }.apply {
            isDaemon = true
            name = "window-fade-out"
        }.start()
    }

    /** 枚举本进程可见顶层窗口：优先精确匹配标题，否则取第一个有标题的 */
    private fun findHwnd(title: String): WinDef.HWND? {
        val user32 = User32.INSTANCE
        val pid = ProcessHandle.current().pid()
        var firstVisible: WinDef.HWND? = null
        var matched: WinDef.HWND? = null
        runCatching {
            user32.EnumWindows({ hwnd, _ ->
                val pidRef = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pidRef)
                if (pidRef.value.toLong() == pid && user32.IsWindowVisible(hwnd)) {
                    val buf = CharArray(256)
                    val len = user32.GetWindowText(hwnd, buf, 256)
                    if (len > 0) {
                        if (firstVisible == null) firstVisible = hwnd
                        if (title.isNotEmpty() && String(buf, 0, len) == title) {
                            matched = hwnd
                            return@EnumWindows false
                        }
                    }
                }
                true
            }, null)
        }
        return matched ?: firstVisible
    }
}
