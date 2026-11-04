package com.yunx.app.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent

/**
 * Windows 桌面端：原生窗口边框 / 标题栏深色模式适配。
 *
 * Compose Desktop (Skiko/Swing) 默认不处理 Windows 10/11 标题栏颜色，
 * 深色主题下窗口外框（标题栏、边框、任务栏缩略图背景）仍为亮白色。
 * 这里通过 JNA 调用 dwmapi.dll 的 DwmSetWindowAttribute
 * 设置 DWMWA_USE_IMMERSIVE_DARK_MODE，通知 DWM 对窗口启用暗色标题栏。
 *
 * HWND 获取方式：用 User32.EnumWindows 枚举本进程的顶层窗口，
 * 不走 AWT peer 反射（sun.awt.windows 在 JDK 17 强封装下反射不可用）。
 *
 * 非 Windows 平台：所有方法均为 no-op，可安全调用。
 */
object WindowsTitleBar {

    /** Win10 20H1+ / Win11 的属性编号 */
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    /** Win10 1809~1909 的旧属性编号 */
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: IntArray,
            cbAttribute: Int
        ): Int
    }

    private val dwm: DwmApi? by lazy {
        runCatching {
            if (!isWindows) return@runCatching null
            Native.load("dwmapi", DwmApi::class.java)
        }.getOrNull()
    }

    val isWindows: Boolean by lazy {
        System.getProperty("os.name", "").lowercase().contains("win")
    }

    /** 当前期望的深色状态（之后新开的窗口在显示事件里自动套用） */
    @Volatile
    private var desiredDark: Boolean = false

    @Volatile
    private var openListenerInstalled: Boolean = false

    /**
     * 对本进程所有顶层窗口设置深色/浅色标题栏。
     * 对尚不可见的窗口同样生效（DWM 会记住，显示时应用）。
     *
     * @return 至少对一个窗口设置成功返回 true
     */
    fun applyToAllWindows(dark: Boolean): Boolean {
        if (!isWindows) return false
        val api = dwm ?: return false
        val user32 = User32.INSTANCE
        val myPid = ProcessHandle.current().pid()
        var anySuccess = false

        return runCatching {
            user32.EnumWindows({ hwnd, _ ->
                val pidRef = com.sun.jna.ptr.IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pidRef)
                // 只处理本进程的顶层窗口（GA_ROOT 即自身），子窗口无需也无效
                if (pidRef.value.toLong() == myPid && isTopLevelWindow(user32, hwnd) &&
                    isStyleableWindow(user32, hwnd)
                ) {
                    if (setDarkAttribute(api, hwnd, dark)) anySuccess = true
                }
                true
            }, null)
            anySuccess
        }.getOrDefault(false)
    }

    /**
     * 记录期望的主题状态，并安装 AWT 全局监听：
     * 之后任何新创建的窗口（对话框、弹窗等）在打开/显示时自动套用当前主题。
     */
    fun registerDesired(dark: Boolean) {
        desiredDark = dark
        if (!isWindows || openListenerInstalled) return
        openListenerInstalled = true
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                AWTEventListener { event ->
                    when (event.id) {
                        WindowEvent.WINDOW_OPENED,
                        ComponentEvent.COMPONENT_SHOWN -> applyToAllWindows(desiredDark)
                    }
                },
                AWTEvent.WINDOW_EVENT_MASK or AWTEvent.COMPONENT_EVENT_MASK
            )
        }
    }

    /** 单窗口设置深色标题栏；属性 20 失败时回退旧属性 19 */
    private fun setDarkAttribute(api: DwmApi, hwnd: WinDef.HWND, dark: Boolean): Boolean {
        val pvAttr = intArrayOf(if (dark) 1 else 0)
        return runCatching {
            var hr = api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, pvAttr, 4)
            if (hr != 0) {
                hr = api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, pvAttr, 4)
            }
            hr == 0
        }.getOrDefault(false)
    }

    private fun isTopLevelWindow(user32: User32, hwnd: WinDef.HWND): Boolean =
        runCatching { user32.GetAncestor(hwnd, WinUser.GA_ROOT) == hwnd }.getOrDefault(false)

    /**
     * 是否值得设置深色标题栏（只认「我们自己画、带标题」的窗口）。
     *
     * - **内嵌浏览器的隐藏窗口**（JCEF 窗口类名含 `Cef`）：它没有标题栏，设 DWM 属性没有任何效果，
     *   反而可能打乱它的合成状态 —— 切主题后退出应用时闪的那两个崩溃框就出在这里的子进程上；
     * - **无标题窗口**（工具窗 / 无边框弹层）：本来就没有标题栏可上色。
     */
    private fun isStyleableWindow(user32: User32, hwnd: WinDef.HWND): Boolean = runCatching {
        val buf = CharArray(256)
        val len = user32.GetClassName(hwnd, buf, buf.size)
        val className = if (len > 0) String(buf, 0, len) else ""
        if (className.contains("Cef", ignoreCase = true)) return@runCatching false
        user32.GetWindowTextLength(hwnd) > 0
    }.getOrDefault(true)

    /**
     * 查询 Windows 系统级「应用深色模式」开关（用于「跟随系统」模式）。
     * 从注册表 HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize
     * 的 AppsUseLightTheme 值判断：0=深色，1=浅色，未配置时默认浅色。
     */
    fun systemIsDarkMode(): Boolean {
        if (!isWindows) return false
        return runCatching {
            val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
            val value = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, key, "AppsUseLightTheme")
            value == 0
        }.getOrDefault(false)
    }
}
