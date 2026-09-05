package com.yunx.app

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.ui.MainScreen
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.WindowFx
import androidx.compose.ui.graphics.toComposeImageBitmap

fun main(args: Array<String>) {
    // 渲染后端：默认 OPENGL（GPU 渲染，动画流畅且文字清晰，2026-09-05 用户实测确认）。
    // 实测数据（125% DPI）：SOFTWARE 每帧 CPU 光栅化约 130 万像素，设置/下载页持续交互只有
    // ~10 FPS 且 UI 线程饱和；DIRECT3D 动画流畅但文字灰度发虚且 hinting 调节无效；
    // OPENGL（ANGLE）两者兼顾。如需其他后端可设 YUNXPC_RENDER_API=SOFTWARE / DIRECT3D。
    val osName = System.getProperty("os.name", "").lowercase()
    if (osName.contains("win")) {
        System.setProperty(
            "skiko.renderApi",
            System.getenv("YUNXPC_RENDER_API")?.takeIf { it.isNotBlank() } ?: "OPENGL"
        )
    }

    // 桌面上下文初始化（数据目录等）
    AppContext.init()
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

    // 进程退出前尽力释放 JCEF
    Runtime.getRuntime().addShutdownHook(
        Thread { JcefHolder.disposeQuietly() }
    )

    application {
        // 关闭时先淡出窗口再退出，消除原生窗口销毁瞬间的白屏闪烁
        var mainWindow: java.awt.Frame? = null
        // 启动器闪屏模式（launcher 注入 YUNXPC_SPLASH=1）：窗口可见性由 Compose 控制，
        // WindowFx 在窗口显示瞬间把透明度压到 0，内容首帧就绪后渐入，与闪屏淡出交叉衔接。
        // 注意：不能用 Window(visible=false) + 外部 setVisible(true)——Compose 状态同步会
        // 把可见性回滚，导致窗口永远不显示（进程存活但无窗口）。
        val splashMode = System.getenv("YUNXPC_SPLASH") == "1"
        Window(
            onCloseRequest = {
                val w = mainWindow
                if (w != null) WindowFx.fadeOutThen(w) { exitApplication() }
                else exitApplication()
            },
            title = "云析 YunX-Desktop",
            state = WindowState(size = DpSize(1100.dp, 760.dp)),
            icon = remember { loadWindowIcon() },
        ) {
            androidx.compose.runtime.SideEffect {
                mainWindow = window as? java.awt.Frame
            }
            // 窗口出现后再后台启动 JCEF，登录页通过 browserReady 状态自动切换
            androidx.compose.runtime.LaunchedEffect(Unit) {
                JcefHolder.initInBackground()
            }
            // 启动淡入：窗口 displayable 后压 0 → 等内容就绪 → 渐入
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (splashMode) {
                    (window as? java.awt.Frame)?.let { WindowFx.scheduleFadeIn(it) }
                }
            }
            ComposeEmptyActivityTheme {
                MainScreen()
            }
        }
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
 * 内嵌浏览器诊断冒烟：主线程初始化 JCEF → 创建浏览器加载页面 → 等待 20s → 输出 Cookie 统计。
 */
private fun runJcefSmoke(url: String) {
    println("SMOKE: init JCEF...")
    JcefHolder.initBlocking(noSandbox = true)
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
