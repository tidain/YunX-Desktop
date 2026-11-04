package com.yunx.app.data.network.video

import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.network.CefRequest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * 隐藏浏览器取数：桌面等价于上游 Android 的隐藏 WebView。
 *
 * 抖音作品页要由真实浏览器跑完 `acrawler` 反爬挑战 → 重载 → 挂载 → 自己发详情请求，
 * 纯 HTTP 拿不到，必须用 JCEF（完整 Chromium）渲染。这里维护一个**常驻**、**从不显示**
 * 的窗口态 `CefBrowser`（UI 组件不 attach 到任何窗口）加载页面并回读结果。
 *
 * 为什么要常驻：实测单次解析耗时几乎全在「新建 Chromium + 首次导航跑挑战」上
 * （诊断日志 `headless_load_start` → `headless_ready` 为 1.8~9.4s，而同进程第二次
 * 解析只要 1.8s，`ready` → `extract_ok` 仅 0.2~0.5s）。所以复用同一个 client/router/
 * browser，并配合 [prewarm] 在程序启动后后台预热站点 Cookie 与渲染进程。
 *
 * 实现要点（JCEF 与 Android WebView 的能力差异适配）：
 * - **document-start 注入**：JCEF 没有等价 API，在 [CefLoadHandlerAdapter.onLoadStart]
 *   （仅 main frame）里补注当次会话的 `extraDocumentStartJs`；抖音挑战-重载每次导航都
 *   触发 onLoadStart，所以每次都要重新注入。
 * - **JS 回读**：`executeJavaScript` 没有返回值，用 `CefMessageRouter` 注入页面的
 *   `window.cefQuery` 把结果送回来，在 [CefMessageRouterHandlerAdapter.onQuery] 里按
 *   前缀分发。为避免复用浏览器时「迟到的回调串台」，所有载荷都带当次会话的 token，
 *   收到后与 [currentSession] 比对，不匹配即丢弃。
 * - **会话可被顶替**：常驻浏览器同一时刻只能跑一个页面，[loadAndExtract] 用 [parseMutex]
 *   串行化；若已有会话在跑，新会话直接顶替并把旧会话以 null 结束。
 * - **userAgent 保留但故意不改请求头**：JCEF 是完整 Chromium，默认 UA 就是桌面
 *   Chrome 形态；改写 HTTP UA 会造成与 `navigator.userAgent` 不一致，反而触发反爬。
 * - **线程**：java-cef 的 browser 创建 / executeJavaScript / loadURL 统一走 AWT EDT。
 *
 * JCEF 不可用（[JcefHolder.isAvailable] 为 false）时返回 null：调用方自动落回
 * HTTP/SSR 路线，等价于「网页兜底不可用」，不影响其它平台。
 */
object HeadlessWebView {

    private const val TAG = "YunX-Headless"
    private const val PREFIX_PROBE = "tokenok:"
    private const val PREFIX_READY = "ready:"
    private const val PREFIX_RESULT = "result:"

    /** 预热最多等待 JCEF 初始化的时间：超时就放弃这次预热（不影响后续解析）。 */
    private const val PREWARM_WAIT_MS = 60_000L

    /** 空闲停放的空白页。 */
    private const val ABOUT_BLANK = "about:blank"

    /**
     * 空闲停放延迟：解析/预热结束后再等这么久，确认期间没有新解析才开始停放，
     * 避免连续解析（用户连点）被误伤成两次冷启动。
     */
    private const val PARK_DELAY_MS = 3_000L

    /** 真正的解析请求串行；预热不占这把锁，可被解析顶替。 */
    private val parseMutex = Mutex()

    /** 常驻的原生资源；创建后直到进程退出才由 JcefHolder 统一释放。 */
    @Volatile private var client: CefClient? = null
    @Volatile private var router: CefMessageRouter? = null
    @Volatile private var browser: CefBrowser? = null

    private val browserLock = Any()
    private val lock = Any()
    private val tokenSeq = AtomicLong(0L)

    /** 当前生效的解析会话（同一时刻最多一个）。 */
    @Volatile private var active: Session? = null

    /** 一次解析会话：承载当次 URL/脚本/回调，finish 用 CAS 保证只收口一次。 */
    private class Session(
        val token: String,
        val url: String,
        val pollIntervalMs: Long,
        val readyProbeJs: String,
        val extractRunJs: String,
        val documentStartJs: List<String>,
        val cont: CancellableContinuation<String?>
    ) {
        private val finished = AtomicBoolean(false)

        @Volatile var poller: Thread? = null

        val isFinished: Boolean get() = finished.get()

        fun finish(value: String?) {
            if (!finished.compareAndSet(false, true)) return
            poller?.interrupt()
            if (cont.isActive) cont.resume(value)
        }
    }

    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 25_000L,
        pollIntervalMs: Long = 120L,
        extraDocumentStartJs: List<String> = emptyList()
    ): String? {
        if (!JcefHolder.isAvailable()) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK,
                "headless_unavailable",
                "JCEF 不可用，跳过网页兜底：url=$url"
            )
            return null
        }
        DiagnosticLog.event(DiagnosticLog.NETWORK, "headless_load_start", "url=$url timeoutMs=$timeoutMs")
        return try {
            withTimeout(timeoutMs) {
                parseMutex.withLock {
                    runSession(url, readyCheckJs, extractJs, pollIntervalMs, extraDocumentStartJs)
                }
            }
        } catch (t: TimeoutCancellationException) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK,
                "headless_timeout",
                "url=$url timeoutMs=$timeoutMs"
            )
            null
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "headless extract failed: url=$url", t)
            null
        }
    }

    /**
     * 后台预热：等 JCEF 就绪后创建（或复用）隐藏浏览器并导航到 [url]，把站点的
     * Cookie（ttwid / acrawler）与渲染进程热起来，让用户真正解析时少走冷启动。
     * 失败无副作用。
     */
    fun prewarm(url: String) {
        Thread({
            val deadline = System.currentTimeMillis() + PREWARM_WAIT_MS
            while (!JcefHolder.isAvailable() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (!JcefHolder.isAvailable()) return@Thread
            // 浏览器已存在（可能正在解析）就不再插手，预热只是锦上添花
            if (browser != null || currentSession() != null) return@Thread
            val created = try {
                ensureBrowser(url)
            } catch (t: Throwable) {
                Log.e(TAG, "prewarm failed: url=$url", t)
                false
            }
            if (created) {
                DiagnosticLog.event(DiagnosticLog.NETWORK, "headless_prewarm", "url=$url created=true")
            }
        }, "YunX-Prewarm").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 关闭隐藏浏览器并释放 client / router（进程退出前调用）。
     *
     * CEF 的契约是「所有 browser 都关闭之后才能 CefShutdown」。以前退出时直接 dispose CefApp，
     * 隐藏浏览器还活着就被强关，GPU / renderer 子进程被硬杀 —— Windows 上退出瞬间会闪两个崩溃框。
     * 这里先关浏览器，再让 JcefHolder 释放 CefApp；任何一步失败都忽略（进程即将结束）。
     */
    fun closeQuietly() {
        synchronized(browserLock) {
            runCatching { parkScheduler.shutdownNow() }
            val b = browser
            browser = null
            // force=true：不等待页面 beforeunload，立刻关闭
            runCatching { b?.close(true) }
            val r = router
            router = null
            runCatching { r?.dispose() }
            val c = client
            client = null
            runCatching { c?.dispose() }
        }
        synchronized(lock) { active = null }
    }

    private suspend fun runSession(
        url: String,
        readyCheckJs: String,
        extractJs: String,
        pollIntervalMs: Long,
        extraDocumentStartJs: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        val token = "t" + tokenSeq.incrementAndGet()
        val session = Session(
            token = token,
            url = url,
            pollIntervalMs = pollIntervalMs,
            readyProbeJs = buildReadyProbeJs(readyCheckJs, token),
            extractRunJs = buildExtractRunJs(extractJs, token),
            // 会话标记 + 调用方钩子一起在 document-start 注入：标记让就绪探测只认
            // 本次导航产生的文档，避免复用浏览器时读到上一页残留的数据
            documentStartJs = listOf(sessionMarkerJs(token)) + extraDocumentStartJs,
            cont = cont
        )
        // 先登记会话再建浏览器：首次创建是 createBrowser(url) + createImmediately，
        // 导航随即发起，onLoadStart 必须能立刻拿到本会话才能注入 document-start 脚本
        val superseded = synchronized(lock) { active.also { active = session } }
        // 顶替上一个（正常情况下被 parseMutex 挡住，这里只兜底）
        superseded?.finish(null)

        cont.invokeOnCancellation {
            session.finish(null)
            clearActive(session)
        }

        // 首次创建直接用目标 URL（与已验证可用的旧实现一致：createImmediately 立即导航）；
        // 已存在则复用，稍后用 loadURL 跳转
        val created = try {
            ensureBrowser(url)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureBrowser failed: url=$url", t)
            false
        }
        val b = browser
        if (b == null) {
            session.finish(null)
            clearActive(session)
            return@suspendCancellableCoroutine
        }

        val navTask = Runnable {
            try {
                DiagnosticLog.event(
                    DiagnosticLog.NETWORK,
                    "hl_diag_nav",
                    "burl=${b.url} target=$url created=$created"
                )
                // 新建时 createBrowser(url) 已发起导航，无需再 loadURL
                if (created) return@Runnable
                // 复用浏览器：目标相同就 reload（拿到全新 V8 context 清掉旧全局变量），
                // 否则整页导航
                if (b.url == url) b.reload() else b.loadURL(url)
            } catch (t: Throwable) {
                Log.e(TAG, "headless navigate failed: url=$url", t)
                session.finish(null)
                clearActive(session)
            }
        }
        if (SwingUtilities.isEventDispatchThread()) navTask.run() else SwingUtilities.invokeLater(navTask)

        session.poller = startPoller(session)
    }

    private fun startPoller(session: Session): Thread {
        return Thread({
            try {
                // 先探测再睡：去掉首轮无谓的等待，页面可能已经就绪
                while (!session.isFinished && isCurrent(session)) {
                    val b = browser
                    if (b != null) {
                        SwingUtilities.invokeLater {
                            if (!session.isFinished && isCurrent(session)) {
                                runCatching {
                                    b.executeJavaScript(session.readyProbeJs, session.url, 0)
                                }
                            }
                        }
                    }
                    Thread.sleep(session.pollIntervalMs)
                }
            } catch (_: InterruptedException) {
                // 完成/取消时退出轮询
            }
        }, "YunX-Headless-Poll").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 创建（或复用）常驻隐藏浏览器；必须在 EDT 上创建（与 JcefLoginPane 同一约定）。
     *
     * 首次创建直接用 [initialUrl] 建浏览器：实测 `createBrowser("about:blank")` 后再
     * `loadURL` 有竞态（原生创建是异步的，紧随的 loadURL 会被吞掉），而
     * `createBrowser(url) + createImmediately()` 是旧实现已验证可用的路径。
     *
     * @return true 表示本次新建（已按 [initialUrl] 发起导航）；false 表示复用已有浏览器。
     */
    private fun ensureBrowser(initialUrl: String): Boolean = synchronized(browserLock) {
        if (browser != null) return false
        val app = JcefHolder.app() ?: return false
        var created = false
        val createTask = Runnable {
            var c: CefClient? = null
            var r: CefMessageRouter? = null
            try {
                c = app.createClient()
                r = CefMessageRouter.create()
                r.addHandler(queryHandler, true)
                c.addMessageRouter(r)
                c.addLoadHandler(loadHandler)
                val b = c.createBrowser(initialUrl, false, false)
                // 画布永不挂到窗口 → 等不到 addNotify 的自动创建，
                // 必须手动触发原生浏览器创建（隐藏加载的关键，同时发起首次导航）
                b.createImmediately()
                client = c
                router = r
                browser = b
                created = true
            } catch (t: Throwable) {
                Log.e(TAG, "create hidden browser failed", t)
                runCatching { c?.let { cc -> r?.let(cc::removeMessageRouter) } }
                runCatching { r?.dispose() }
                runCatching { c?.dispose() }
            }
        }
        try {
            if (SwingUtilities.isEventDispatchThread()) createTask.run()
            else SwingUtilities.invokeAndWait(createTask)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureBrowser failed", t)
        }
        created
    }

    /** 只创建一次、被所有会话复用的查询处理器：按前缀分发，并用 token 过滤迟到回调。 */
    private val queryHandler = object : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            queryBrowser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback
        ): Boolean {
            when {
                request.startsWith(PREFIX_PROBE) -> {
                    callback.success("")
                    DiagnosticLog.event(
                        DiagnosticLog.NETWORK,
                        "hl_diag_token_ok",
                        "token=${request.substring(PREFIX_PROBE.length)}"
                    )
                    return true
                }
                request.startsWith(PREFIX_READY) -> {
                    callback.success("")
                    val token = request.substring(PREFIX_READY.length)
                    val s = currentSession()
                    if (s != null && s.token == token && !s.isFinished) {
                        DiagnosticLog.event(
                            DiagnosticLog.NETWORK, "headless_ready", "url=${s.url}"
                        )
                        val b = browser
                        if (b != null) {
                            SwingUtilities.invokeLater {
                                if (!s.isFinished && isCurrent(s)) {
                                    runCatching {
                                        b.executeJavaScript(s.extractRunJs, s.url, 0)
                                    }
                                }
                            }
                        }
                    }
                    return true
                }
                request.startsWith(PREFIX_RESULT) -> {
                    callback.success("")
                    val body = request.substring(PREFIX_RESULT.length)
                    val sep = body.indexOf(':')
                    val token = if (sep >= 0) body.substring(0, sep) else ""
                    val payload = if (sep >= 0) body.substring(sep + 1) else ""
                    val s = currentSession()
                    if (s != null && s.token == token && !s.isFinished) {
                        DiagnosticLog.event(
                            DiagnosticLog.NETWORK,
                            "headless_extract_ok",
                            "url=${s.url} bytes=${payload.length}"
                        )
                        s.finish(payload)
                        clearActive(s)
                    }
                    return true
                }
            }
            return false
        }
    }

    /** 只创建一次、被所有会话复用的加载处理器。 */
    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadStart(
            loadBrowser: CefBrowser,
            frame: CefFrame,
            transitionType: CefRequest.TransitionType
        ) {
            if (!frame.isMain) return
            val s = currentSession()
            DiagnosticLog.event(
                DiagnosticLog.NETWORK,
                "hl_diag_frame_start",
                "frame=${frame.url} hasSession=${s != null} current=${s?.let { isCurrent(it) }} " +
                    "inject=${s?.documentStartJs?.size ?: 0}"
            )
            if (s == null) return
            if (s.isFinished || !isCurrent(s) || s.documentStartJs.isEmpty()) return
            // document-start 注入：挑战-重载每次导航都触发，逐次重注
            SwingUtilities.invokeLater {
                if (s.isFinished || !isCurrent(s)) return@invokeLater
                s.documentStartJs.forEach { js ->
                    runCatching { loadBrowser.executeJavaScript(js, s.url, 0) }
                }
            }
        }

        override fun onLoadEnd(
            loadBrowser: CefBrowser,
            frame: CefFrame,
            httpStatusCode: Int
        ) {
            if (!frame.isMain) return
            DiagnosticLog.event(
                DiagnosticLog.NETWORK,
                "hl_diag_frame_end",
                "frame=${frame.url} status=$httpStatusCode"
            )
            // 预热导航自己跑完（无会话）时也安排停放：否则会一直停在抖音视频流页自动播放
            if (currentSession() == null) parkWhenIdle()
        }

        override fun onLoadError(
            errorBrowser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String
        ) {
            if (!frame.isMain) return
            // ERR_ABORTED 是重定向/挑战重载掐掉的导航，不算失败
            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            // 无会话（预热导航）失败无所谓，静默
            val s = currentSession() ?: return
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK,
                "headless_load_error",
                "code=$errorCode text=$errorText url=$failedUrl"
            )
            s.finish(null)
            clearActive(s)
        }
    }

    private fun currentSession(): Session? = synchronized(lock) { active }

    private fun isCurrent(s: Session): Boolean = currentSession() === s

    private fun clearActive(s: Session) {
        synchronized(lock) { if (active === s) active = null }
        // 会话收口即安排空闲停放：见 [parkWhenIdle]
        parkWhenIdle()
    }

    /** 停放调度器：单线程，只负责延迟把空闲的隐藏浏览器拉回空白页。 */
    private val parkScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "YunX-Headless-Park").apply { isDaemon = true }
    }

    /**
     * 让隐藏浏览器在空闲时回到 [ABOUT_BLANK]。
     *
     * 为什么必须停放：抖音首页会 302 到 `/jingxuan` 视频流页并**自动播放**，作品详情页同样
     * 自带自动播放。常驻浏览器虽然从不显示，Chromium 仍会持续解码渲染，实测空闲时单个
     * `jcef_helper` 进程稳定占用 ~27% CPU（整整一个核），与预览播放器（jfxmedia 软件解码）
     * 抢 CPU/GPU，表现就是**解析成功但预览特别卡**。
     *
     * 为什么停放不亏预热：预热的价值在 CEF 渲染进程与 profile（Cookie：ttwid / acrawler、
     * HTTP 缓存、TLS 会话）常驻，而 `acrawler` 挑战本就是每个新文档都要重跑的（挑战-重载），
     * 停在空白页不损失这层；下次解析照旧走 [runSession] 复用浏览器 + `loadURL`。
     *
     * 延迟 [PARK_DELAY_MS] 且停放前复查会话：连续解析（用户连点）不会被误伤。
     */
    private fun parkWhenIdle() {
        parkScheduler.schedule({
            try {
                if (currentSession() != null) return@schedule
                val b = browser ?: return@schedule
                if (b.url == null || b.url == ABOUT_BLANK) return@schedule
                SwingUtilities.invokeLater {
                    if (currentSession() != null) return@invokeLater
                    runCatching { b.loadURL(ABOUT_BLANK) }
                        .onFailure { Log.w(TAG, "停放隐藏浏览器失败: ${it.message}") }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "parkWhenIdle 异常: ${t.message}")
            }
        }, PARK_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    // 就绪探测 / 取数脚本统一包成 cefQuery 回传（executeJavaScript 拿不到返回值）。
    // 探测脚本异常时静默吞掉等下一轮轮询；取数脚本异常时也回传一条 result，
    // 避免一直挂到超时（EXTRACT_JS 内部自己也会 catch，这层只防包装层语法问题）。
    //
    // 两处必须注意：
    // - 就绪判断写成 `r===true||r==='true'`：readyCheckJs 返回的是字符串，`if(r)` 对
    //   'false' 也为真。
    // - 先校验 `window.__yunxSessionToken` 是本会话的标记：浏览器常驻复用，导航提交
    //   前旧文档还活着，若不加这道门，探测会命中上一页残留的全局变量（例如
    //   `window.__yunxAwemeDetail`）从而立刻"就绪"并取回上一条作品的数据。标记随
    //   document-start 注入，只会出现在本次导航产生的文档里，且不依赖 URL 形态
    //   （抖音会把 /video/<id> 302 到 /note/<id>，URL 门禁对图文/动图必然失效）。
    private fun buildReadyProbeJs(readyCheckJs: String, token: String): String =
        "(function(){try{" +
            "if(window.__yunxSessionToken!=='${token}')return;" +
            "if(!window.__yunxProbeReported){window.__yunxProbeReported=1;" +
            "window.cefQuery({request:'$PREFIX_PROBE${token}'});}" +
            "var r=(\n$readyCheckJs\n);" +
            "if(r===true||r==='true'){window.cefQuery({request:'$PREFIX_READY${token}'});}" +
            "}catch(e){}})()"

    /** 会话标记：随 document-start 一起注入，让就绪探测只认本次导航产生的文档。 */
    private fun sessionMarkerJs(token: String): String =
        "window.__yunxSessionToken='$token';"

    private fun buildExtractRunJs(extractJs: String, token: String): String =
        "(function(){try{var r=(\n$extractJs\n);" +
            "var s=(typeof r==='string')?r:JSON.stringify(r);" +
            "window.cefQuery({request:'$PREFIX_RESULT${token}:'+s});" +
            "}catch(e){window.cefQuery({request:'$PREFIX_RESULT${token}:{\"error\":\"js-wrap\"}'});}})()"
}
