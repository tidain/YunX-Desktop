package com.yunx.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.network.video.HeadlessWebView
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.clipboard.ClipboardLinkController
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.DarkMode
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.RenderApiChooser
import com.yunx.app.util.SingleInstance
import com.yunx.app.util.TrayManager
import com.yunx.app.util.Win32PopupMenu
import com.yunx.app.util.WindowFx
import androidx.compose.ui.graphics.toComposeImageBitmap

fun main(args: Array<String>) {
    // 渲染后端：默认 OPENGL（GPU 渲染，动画流畅且文字清晰，2026-09-05 用户实测确认）。
    // 实测数据（125% DPI）：SOFTWARE 每帧 CPU 光栅化约 130 万像素，设置/下载页持续交互只有
    // ~10 FPS 且 UI 线程饱和；DIRECT3D 动画流畅但文字灰度发虚且 hinting 调节无效；
    // OPENGL（ANGLE）两者兼顾。如需其他后端可设 YUNXPC_RENDER_API=SOFTWARE / DIRECT3D。
    val osName = System.getProperty("os.name", "").lowercase()

    // 桌面上下文初始化（数据目录等）
    AppContext.init()

    // 单实例：第二个实例只把已有窗口还原到前台，然后自身退出。
    // ★ 必须做：两个实例共用同一数据目录时，CEF 的用户数据目录（cache\jcef）会撞车 ——
    //   Chromium 是单例进程模型，第二个实例抢不到 profile 锁会走它自己的「已有实例」处理，
    //   实测会把第一个实例的渲染 / 网络子进程杀掉（jcef.log：Tab Killed / Network service crashed），
    //   个别机器上还会被 Chromium 反向拉起系统里注册的浏览器。
    if (!SingleInstance.acquire()) {
        SingleInstance.focusExistingWindow()
        return
    }

    // ★ 老旧 / 基础显示适配器（如 ATI Radeon 3000）上，OPENGL(ANGLE) 会出现「界面有形状、
    //   但一个文字都不渲染」，这类设备由 [RenderApiChooser] 自动退到 SOFTWARE。
    //   必须在创建任何窗口 / Skiko 初始化之前设置；环境变量 YUNXPC_RENDER_API 优先级更高。
    var softwareRendering = false
    if (osName.contains("win")) {
        val renderApi = System.getenv("YUNXPC_RENDER_API")?.takeIf { it.isNotBlank() }
            ?: RenderApiChooser.chooseWindows()
        com.yunx.app.util.Log.i("YunX-Render", "渲染后端：$renderApi")
        System.setProperty("skiko.renderApi", renderApi)
        softwareRendering = renderApi.equals("SOFTWARE", true)
    }
    // 动效降级：软件渲染时每帧 CPU 光栅化，位移动画/列表增删只会更卡（§3.7）
    runCatching { com.yunx.app.ui.theme.MotionPolicy.install(softwareRendering) }
    // 诊断日志：读开关（默认关）并定好落盘目录；开着才起写线程，关着这里几乎零开销
    runCatching { com.yunx.app.util.DiagnosticLog.install() }
    // 下载调试：读开关（默认关）。必须在任何下载开始之前装配，否则开关打开后先建的任务收不到事件
    runCatching { com.yunx.app.data.download.DownloadDebugLog.install() }
    // HTTP 代理装配：按用户设置的三选一模式注入全局 OkHttp 客户端。
    // 必须在 application { } 之前、AppContext.init() 之后执行（此时 Preferences 已可用，
    // 且早于任何网络请求发出，代理即时对 API / 下载 / 更新检查全部生效）。
    // 注意：system 模式只在启动（及设置变更）时读一次系统代理，运行中系统代理变化不自动跟随。
    runCatching {
        val settings = com.yunx.app.data.prefs.SettingsRepository()
        when (settings.proxyMode) {
            com.yunx.app.data.prefs.SettingsRepository.PROXY_MODE_MANUAL -> {
                if (settings.proxyHost.isNotBlank()) {
                    com.yunx.app.data.network.HttpClients.setProxy(settings.proxyHost, settings.proxyPort)
                } else {
                    com.yunx.app.util.Log.w("YunX-Proxy", "手动代理模式未填写主机地址，按直连处理")
                    com.yunx.app.data.network.HttpClients.setProxy(null, 0)
                }
            }
            com.yunx.app.data.prefs.SettingsRepository.PROXY_MODE_SYSTEM -> {
                // 解析系统代理；解析不到（未启用 / PAC / 读取失败）时按直连处理（SystemProxy 内部已记日志）
                when (val info = com.yunx.app.data.network.SystemProxy.inspect()) {
                    is com.yunx.app.data.network.SystemProxy.Inspect.Proxy ->
                        com.yunx.app.data.network.HttpClients.setProxy(info.host, info.port)
                    else -> com.yunx.app.data.network.HttpClients.setProxy(null, 0)
                }
            }
            else -> com.yunx.app.data.network.HttpClients.setProxy(null, 0)
        }
        // HTTP/2 开关：默认关闭（仅 HTTP/1.1），开启后允许 ALPN 协商 h2。
        // 与代理同一时机装配，早于任何网络请求发出，启动即生效。
        com.yunx.app.data.network.HttpClients.setHttp2Enabled(settings.http2Enabled)
        // 下载引擎调优参数（读缓冲大小 / 慢连接抢占开关、阈值、判定时长）：
        // 与代理同一时机装配，读取「设置 → 实验性功能」的持久化值，下载任务开始前即生效。
        com.yunx.app.data.download.DownloadTuning.applyFrom(settings)
    }
    // 原生暗色模式：让 Win32 原生菜单（托盘右键）跟随系统暗色。必须在创建任何窗口前调用。
    DarkMode.enable()
    // 迅雷设备指纹（进程启动时初始化一次，等价原 Application.onCreate）
    XunleiDeviceFingerprint.init()

    // 诊断模式：--jcef-smoke [url]，创建内嵌浏览器加载页面并输出 Cookie 统计后退出
    if (args.contains("--jcef-smoke")) {
        val url = args.firstOrNull { it.startsWith("http") } ?: "https://pan.quark.cn"
        runJcefSmoke(url)
        return
    }

    // 启动优化：JCEF（内嵌 Chromium）初始化耗时数秒、首次运行解包 200MB 更久，
    // 若在主线程同步初始化会阻塞窗口显示。Windows/Linux 下改为窗口显示后后台初始化
    // （CEF 消息循环由 CefApp 内部 Swing Timer 在 AWT EDT 泵动，与初始化线程无关）。
    // macOS 受 AppKit 主线程约束，保持阻塞式初始化。
    val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
    if (isMac) {
        JcefHolder.initBlocking()
    }

    // 进程退出前：先把 WAL 落回主库（否则数据只留在 -wal 里，异常退出后可能整库丢失），
    // 再尽力释放 JCEF
    Runtime.getRuntime().addShutdownHook(
        Thread {
            com.yunx.app.data.bridge.BrowserBridgeServer.stop()
            com.yunx.app.data.db.AppDatabase.checkpointOnExit()
            JcefHolder.disposeQuietly()
        }
    )

    // 浏览器插件桥（本地回环 HTTP，供 MV3 扩展嗅探/劫持下载后与云析通信）：
    // 只监听 127.0.0.1，扩展侧按端口区间探测 /ping；端口全被占用时静默跳过（不影响主功能）。
    runCatching { com.yunx.app.data.bridge.BrowserBridgeServer.start() }
        .onFailure { com.yunx.app.util.Log.w("YunX-Bridge", "启动浏览器插件桥失败：${it.message}") }

    application {
        val scope = rememberCoroutineScope()
        // 关闭时先淡出窗口再退出，消除原生窗口销毁瞬间的白屏闪烁
        var mainWindow: java.awt.Frame? = null
        // 关闭请求信号：点击窗口 X 时置 true，由 MainScreen 决定退出 / 托盘 / 询问
        var closeRequested by remember { mutableStateOf(false) }
        // 抑制关闭请求：程序化隐藏窗口（最小化到托盘）时为 true，避免 onCloseRequest 重跑导致弹窗闪烁
        var suppressCloseRequest by remember { mutableStateOf(false) }
        // 启动器闪屏模式（launcher 注入 YUNXPC_SPLASH=1）：窗口可见性由 Compose 控制，
        // WindowFx 在窗口显示瞬间把透明度压到 0，内容首帧就绪后渐入，与闪屏淡出交叉衔接。
        // 注意：不能用 Window(visible=false) + 外部 setVisible(true)——Compose 状态同步会
        // 把可见性回滚，导致窗口永远不显示（进程存活但无窗口）。
        val splashMode = System.getenv("YUNXPC_SPLASH") == "1"

        // 窗口状态提升为可共享状态：视频预览的全屏开关（PreviewFullscreen）需要它
        // 启动位置：按「设置 → 主题与外观 → 窗口」的偏好决定；默认「上次关闭位置」——
        // 该项用平台默认位置起步，随后在窗口内容里按上次记录的坐标还原（见 restoreLastWindowPosition）。
        val startupPosition = remember { com.yunx.app.data.prefs.SettingsRepository().windowStartupPosition }
        val windowState = remember {
            WindowState(
                size = DpSize(1100.dp, 760.dp),
                position = when (startupPosition) {
                    // 桌面为 LTR：Start=左、End=右
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_TOP_LEFT -> WindowPosition(Alignment.TopStart)
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_BOTTOM_LEFT -> WindowPosition(Alignment.BottomStart)
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_CENTER -> WindowPosition(Alignment.Center)
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_TOP_RIGHT -> WindowPosition(Alignment.TopEnd)
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_BOTTOM_RIGHT -> WindowPosition(Alignment.BottomEnd)
                    else -> WindowPosition.PlatformDefault
                }
            )
        }

        Window(
            onCloseRequest = {
                if (!suppressCloseRequest) closeRequested = true
            },
            title = "云析 YunX-Desktop",
            state = windowState,
            icon = remember { loadWindowIcon() },
        ) {
            androidx.compose.runtime.SideEffect {
                mainWindow = window as? java.awt.Frame
                com.yunx.app.ui.components.PreviewFullscreen.windowState = windowState
                // 退出全屏时恢复窗口矩形要用真实窗口（只靠 WindowState 不可靠）
                com.yunx.app.ui.components.PreviewFullscreen.window = window
                // 剪贴板分享链接弹窗：主窗口引用用于「主窗口失焦时才弹」判断 + 「打开」时切回前台
                ClipboardLinkController.mainWindow = mainWindow
            }
            // 「上次关闭位置」/「自定义」还原：用 AWT 屏幕像素坐标，避开 WindowState 的 dp/density 换算。
            // 只在启动时执行一次；记录的坐标不可见（如副屏被拔掉）时不动，交给平台默认位置。
            androidx.compose.runtime.LaunchedEffect(Unit) {
                when (startupPosition) {
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_LAST ->
                        restoreWindowPosition(window as? java.awt.Frame, com.yunx.app.data.prefs.SettingsRepository().windowLastPosition)
                    com.yunx.app.data.prefs.SettingsRepository.WINDOW_POS_CUSTOM ->
                        restoreWindowPosition(window as? java.awt.Frame, com.yunx.app.data.prefs.SettingsRepository().windowCustomPosition)
                }
            }
            // 窗口出现后再后台启动 JCEF，登录页通过 browserReady 状态自动切换
            androidx.compose.runtime.LaunchedEffect(Unit) {
                JcefHolder.initInBackground()
                // 预热抖音解析用的隐藏浏览器：实测单次解析耗时几乎全在 Chromium
                // 冷启动与 acrawler 挑战上（诊断日志 1.8~9.4s），提前把浏览器与站点
                // Cookie 热起来，用户真正粘贴链接时即可省掉这段冷启动。
                HeadlessWebView.prewarm("https://www.douyin.com/")
            }
            // 启动淡入：窗口 displayable 后压 0 → 等内容就绪 → 渐入
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (splashMode) {
                    (window as? java.awt.Frame)?.let { WindowFx.scheduleFadeIn(it) }
                }
            }
            // 系统托盘：JNA 调 Win32 TrackPopupMenu（原生菜单，中文正常，暗色跟随系统）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                TrayManager.loadTrayIcon()?.let { icon ->
                    TrayManager.install(
                        image = icon,
                        onLeftClick = { DesktopActions.bringWindowToFront(mainWindow) },
                        onRightClick = { x, y ->
                            val cmd = Win32PopupMenu.show(
                                items = listOf(
                                    "显示主窗口" to 1,
                                    "-" to 0,
                                    "退出" to 2
                                ),
                                x = x, y = y
                            )
                            when (cmd) {
                                1 -> DesktopActions.bringWindowToFront(mainWindow)
                                2 -> {
                                    saveWindowPosition(mainWindow)
                                    val w = mainWindow
                                    if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                                    else exitApplication()
                                }
                            }
                        }
                    )
                }
            }
            ComposeEmptyActivityTheme {
                MainScreen(
                    closeRequested = closeRequested,
                    onCloseHandled = { closeRequested = false },
                    onExitApplication = {
                        saveWindowPosition(mainWindow)
                        val w = mainWindow
                        if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                        else exitApplication()
                    },
                    onMinimizeToTray = {
                        // 记录当前窗口位置，再隐藏（下次启动「上次关闭位置」可还原）
                        saveWindowPosition(mainWindow)
                        // 隐藏窗口前先抑制关闭请求，避免 Compose 把 hide 当作 close 重跑逻辑
                        suppressCloseRequest = true
                        mainWindow?.isVisible = false
                        // 延迟解除抑制（确保 hide 事件已消费完毕）
                        scope.launch {
                            kotlinx.coroutines.delay(50)
                            suppressCloseRequest = false
                        }
                    }
                )
            }
        }

        // 浏览器插件劫持下载的询问弹窗：独立无边框置顶窗口。
        // 与主窗口平级挂载——主窗口被最小化到托盘 / 被浏览器遮住时也能单独弹出，
        // 不会把整个主界面一起吊起来（区别于窗口内覆盖层的 FadeAlertDialog）。
        com.yunx.app.ui.components.BrowserHookDialogWindow()
    }
}

/**
 * 从 classpath 读取 icon.png 构建窗口图标（标题栏 / 任务栏）；
 * 失败返回 null（使用默认图标）。
 */
private fun loadWindowIcon(): androidx.compose.ui.graphics.painter.Painter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("icon.png")?.use { it.readBytes() }
        ?: return@runCatching null
    androidx.compose.ui.graphics.painter.BitmapPainter(
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    )
}.getOrNull()

/**
 * 按偏好里记录的 AWT 屏幕像素坐标（"x,y"）把主窗口放回指定位置。
 * 用 AWT 坐标而非 WindowState 的 dp，避开缩放（density）换算误差；
 * 坐标落在任何屏幕之外（如副屏被拔掉）时不动，交给平台默认位置。
 * 供「上次关闭位置」与「自定义」两种启动位置共用（[saved] 为 null 时直接返回）。
 */
private fun restoreWindowPosition(frame: java.awt.Frame?, saved: String?) {
    if (saved.isNullOrBlank()) return
    val f = frame ?: return
    val parts = saved.split(',')
    if (parts.size != 2) return
    val x = parts[0].trim().toIntOrNull() ?: return
    val y = parts[1].trim().toIntOrNull() ?: return
    if (!isPointOnAnyScreen(x, y)) return
    runCatching { f.setLocation(x, y) }
}

/** 记录主窗口当前屏幕坐标，供下次启动「上次关闭位置」还原；最大化时不记录（否则还原到最大化矩形）。 */
private fun saveWindowPosition(frame: java.awt.Frame?) {
    val f = frame ?: return
    if (f.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0) return
    runCatching {
        com.yunx.app.data.prefs.SettingsRepository().windowLastPosition = "${f.x},${f.y}"
    }
}

/** 点 (x, y) 是否落在任一屏幕范围内（含多显示器负坐标布局） */
private fun isPointOnAnyScreen(x: Int, y: Int): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .any { it.defaultConfiguration.bounds.contains(x, y) }
}.getOrDefault(true)

/**
 * 内嵌浏览器诊断冒烟：主线程初始化 JCEF → 创建浏览器加载页面 → 等待 20s → 输出 Cookie 统计。
 */
private fun runJcefSmoke(url: String) {
    println("SMOKE: init JCEF...")
    JcefHolder.initBlocking()
    val app = JcefHolder.app()
    if (app == null) {
        println("SMOKE: JCEF init FAILED")
        return
    }
    println("SMOKE: creating browser for $url ...")
    val client = app.createClient()
    client.addLoadHandler(object : org.cef.handler.CefLoadHandler {
        override fun onLoadingStateChange(
            browser: org.cef.browser.CefBrowser,
            isLoading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean
        ) {
            println("SMOKE: loading=$isLoading url=${browser.url}")
        }

        override fun onLoadStart(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            transitionType: org.cef.network.CefRequest.TransitionType
        ) {
        }

        override fun onLoadEnd(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            httpStatusCode: Int
        ) {
            println("SMOKE: loadEnd status=$httpStatusCode url=${frame.url}")
        }

        override fun onLoadError(
            browser: org.cef.browser.CefBrowser,
            frame: org.cef.browser.CefFrame,
            errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) {
            println("SMOKE: LOAD ERROR $errorCode $errorText $failedUrl")
        }
    })
    val browser = client.createBrowser(url, false, false)
    // JCEF 默认延迟到 UI 组件显示时才真正创建；冒烟无 UI，需立即创建
    browser.createImmediately()
    println("SMOKE: browser created, waiting 25s for page load...")
    Thread.sleep(25000)

    // 竞态实证：visitAllCookies 回调异步，0ms 立即读 vs 500ms 后读
    val manager = org.cef.network.CefCookieManager.getGlobalManager()
    fun readCookies(): List<String> {
        val list = java.util.concurrent.ConcurrentLinkedQueue<String>()
        manager.visitAllCookies(
            object : org.cef.callback.CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: org.cef.misc.BoolRef
                ): Boolean {
                    list.add("${cookie.name}=${cookie.value}")
                    return true
                }
            }
        )
        Thread.sleep(500)
        return list.toList()
    }
    // 第一轮：visitAllCookies 返回后先不等待，立即取快照（用另一队列演示竞态）
    val immediate = java.util.concurrent.ConcurrentLinkedQueue<String>()
    manager.visitAllCookies(
        object : org.cef.callback.CefCookieVisitor {
            override fun visit(
                cookie: org.cef.network.CefCookie,
                count: Int,
                total: Int,
                delete: org.cef.misc.BoolRef
            ): Boolean {
                immediate.add("${cookie.name}=${cookie.value}")
                return true
            }
        }
    )
    val immediateCount = immediate.size
    // 等待回调完成后再取一遍
    val waitedCount = readCookies().size
    println("SMOKE: cookies read immediately(no wait)=$immediateCount, after 500ms=$waitedCount")
    println("SMOKE: cookie samples=${readCookies().take(5)}")

    // API 回环验证：写入测试 Cookie 再读取
    val testCookie = org.cef.network.CefCookie(
        "smoke.test", "roundtrip", ".quark.cn", "/",
        true, false, null, null, false, null
    )
    runCatching { manager.setCookie("https://pan.quark.cn", testCookie) }
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    runCatching {
        manager.visitAllCookies(
            object : org.cef.callback.CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: org.cef.misc.BoolRef
                ): Boolean {
                    counter.incrementAndGet()
                    if (cookie.name == "smoke.test") {
                        println("SMOKE: roundtrip cookie value=${cookie.value}")
                    }
                    return true
                }
            }
        )
    }
    Thread.sleep(500)
    println("SMOKE: total cookies=${counter.get()}")
    runCatching { browser.close(true) }
    runCatching { client.dispose() }
    println("SMOKE: done")
}
