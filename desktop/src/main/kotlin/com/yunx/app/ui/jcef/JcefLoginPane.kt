package com.yunx.app.ui.jcef

import com.yunx.app.ui.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.BrowserCookieImporter
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import javax.swing.JPanel

private const val TAG = "YunX-JCEF-UI"

/**
 * 内嵌浏览器登录面板（方案 B）：
 * - SwingPanel 嵌入真实 Chromium（JCEF），加载网盘登录页；
 * - 每 1.5 秒轮询全局 Cookie，检测到必需键后提示「已检测到登录态」，一键保存；
 * - 底部提供「自动导入浏览器 Cookie」（方案 A）与「手动粘贴」备选。
 */
@Composable
fun JcefLoginPane(
    loginUrl: String,
    domains: List<String>,
    requiredKeys: List<String>,
    platform: String,
    onSave: suspend (String) -> Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSwitchToPaste: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val app = JcefHolder.app()
    var detectedCookie by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var browserFailure by remember { mutableStateOf<String?>(null) }

    // 创建浏览器（Compose 桌面 UI 线程即 EDT，JCEF 要求在 EDT 上创建）
    val browserHolder = remember {
        if (app == null) {
            null
        } else {
            try {
                val client: CefClient = app.createClient()
                val browser: CefBrowser = client.createBrowser(loginUrl, false, false)
                // 不调用 createImmediately()：它会在画布尚未挂到窗口（尺寸为 0）时
                // 强行创建原生浏览器窗口，首次绘制丢失 → 白屏直到手动缩放窗口。
                // 交给 java-cef 在画布 addNotify（拿到真实尺寸）时自动创建，
                // 登录页一显示画布即挂载，页面加载与 Cookie 检测仅延迟几毫秒。
                BrowserHolder(client, browser)
            } catch (t: Throwable) {
                browserFailure = "内嵌浏览器启动失败：${t.message}"
                Log.e(TAG, "create browser failed", t)
                null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { browserHolder?.browser?.close(true) }
            runCatching { browserHolder?.client?.dispose() }
        }
    }

    BackHandler(enabled = !isSaving) { onBack() }

    // 轮询 Cookie：检测必需键齐全后置为 detectedCookie
    // （visitAllCookies 回调在 CEF IO 线程异步执行，需等待其完成再判断，见 collectCookies）
    LaunchedEffect(browserHolder) {
        if (browserHolder == null) return@LaunchedEffect
        while (true) {
            val cookie = withContext(Dispatchers.IO) { collectCookies(domains, requiredKeys) }
            if (cookie != null) detectedCookie = cookie
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部导航栏：左上角返回（标准 IconButton 悬停高亮，与手动 Cookie 页一致）。
        // 登录页是提前 return 的覆盖层，不在 MainScreen 的 LocalContentColor 提供者内，
        // 默认黑色会让 IconButton 的悬停状态层在深色主题下变成"变暗"而非亮色高亮——
        // 这里显式提供 onSurface，高亮层与 TopAppBar 内的按钮观感一致。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isSaving) onBack() },
                    enabled = !isSaving
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Text(
                    text = "${platform}登录",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // 状态卡
        Surface(
            color = if (detectedCookie != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (detectedCookie != null) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "已检测到登录态",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val cookie = detectedCookie ?: return@Button
                            scope.launch {
                                isSaving = true
                                val ok = onSave(cookie)
                                isSaving = false
                                if (ok) {
                                    SnackbarController.show("登录成功")
                                    onSaved()
                                } else {
                                    SnackbarController.show("Cookie 校验失败，请确认已登录")
                                }
                            }
                        },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存登录")
                        }
                    }
                } else {
                    Icon(
                        Icons.Outlined.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = browserFailure ?: "请在下方网页中登录，登录成功后自动检测",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 内嵌浏览器区域
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (browserHolder != null) {
                SwingPanel(
                    factory = {
                        // 强制画布尺寸与容器同步：Compose 侧尺寸变化时（窗口缩放/页面切换动画）
                        // BorderLayout 不一定立即重新布局 JCEF 画布，偶发"浏览器不铺满/留白"。
                        // 每次布局后校正画布边界，其内置 ComponentListener 会联动 CEF 原生窗口缩放。
                        object : JPanel(BorderLayout()) {
                            override fun doLayout() {
                                super.doLayout()
                                val canvas = browserHolder.browser.uiComponent
                                if (canvas.width != width || canvas.height != height ||
                                    canvas.x != 0 || canvas.y != 0
                                ) {
                                    canvas.setBounds(0, 0, width, height)
                                }
                            }
                        }.apply {
                            background = java.awt.Color.WHITE
                            add(browserHolder.browser.uiComponent, BorderLayout.CENTER)
                            addComponentListener(object : java.awt.event.ComponentAdapter() {
                                override fun componentResized(e: java.awt.event.ComponentEvent) {
                                    browserHolder.browser.uiComponent.setBounds(0, 0, width, height)
                                }
                            })
                            javax.swing.SwingUtilities.invokeLater { validate() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    background = Color.White
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = browserFailure ?: "内嵌浏览器不可用",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // 底部备选入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutoImportCookieButton(
                platform = platform,
                onImported = onSave,
                onSaved = onSaved,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onSwitchToPaste,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("手动粘贴")
            }
        }
    }
}

private class BrowserHolder(val client: CefClient, val browser: CefBrowser)

/**
 * 从全局 Cookie 管理器收集目标域 Cookie；必需键齐全时返回 "k=v; ..." 串。
 *
 * JCEF 的 visitAllCookies 是**异步**的：CefCookieVisitor.visit 回调在 CEF IO 线程执行，
 * visitAllCookies 返回时回调可能尚未开始/完成。因此调用后必须等待一小段窗口
 * 让回调把结果写入（线程安全收集容器），再判断必需键。
 */
private fun collectCookies(domains: List<String>, requiredKeys: List<String>): String? {
    return runCatching {
        val manager = CefCookieManager.getGlobalManager()
        val pairs = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val domain = cookie.domain ?: ""
                if (domains.any { domain.contains(it) }) {
                    pairs.add((cookie.name ?: "") to (cookie.value ?: ""))
                }
                return true
            }
        })
        // 等待 IO 线程回调完成（CEF 遍历 Cookie 库很快，几百毫秒足够）
        Thread.sleep(400)
        val all = pairs.toList()
        if (all.isNotEmpty()) {
            Log.d(TAG, "cookies for $domains: ${all.size} (keys=${all.map { it.first }.distinct()})")
        }
        val names = all.map { it.first }.toSet()
        if (requiredKeys.any { !names.contains(it) }) return null
        all.joinToString("; ") { "${it.first}=${it.second}" }
    }.getOrNull()
}

/**
 * 方案 A 入口按钮 + 对话框：扫描本机浏览器并列出可导入的 Cookie。
 * 可用于内嵌浏览器模式与纯粘贴模式。
 */
@Composable
fun AutoImportCookieButton(
    platform: String,
    onImported: suspend (String) -> Boolean,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BrowserCookieImporter.FoundCookies>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scope.launch {
            scanning = true
            errorMsg = null
            val found = withContext(Dispatchers.IO) {
                val domains = BrowserCookieImporter.domainSuffixes(platform)
                BrowserCookieImporter.discoverBrowsers().mapNotNull { browser ->
                    BrowserCookieImporter.importFrom(browser, domains)
                }
            }
            results = found
            scanning = false
            if (found.isEmpty()) {
                errorMsg = "未在本机浏览器中找到该平台的登录 Cookie。\n" +
                    "提示：需先在浏览器中登录过对应网盘；Chrome/Edge 较新版本因安全限制可能无法读取，可尝试 Firefox 或 360 浏览器。"
            }
        }
    }

    OutlinedButton(onClick = { show = true; scan() }, modifier = modifier) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
        Spacer(modifier = Modifier.size(6.dp))
        Text("自动导入浏览器 Cookie")
    }

    if (show) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!scanning) show = false },
            title = { Text("自动导入浏览器 Cookie") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("正在扫描本机浏览器…")
                        }
                    } else if (results.isNotEmpty()) {
                        results.forEach { found ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = found.browserName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "找到 ${found.count} 个 Cookie",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                scanning = true
                                                val ok = onImported(found.cookie)
                                                scanning = false
                                                if (ok) {
                                                    show = false
                                                    SnackbarController.show("登录成功")
                                                    onSaved()
                                                } else {
                                                    errorMsg = "该 Cookie 校验失败（可能已过期），请重新登录浏览器后重试"
                                                }
                                            }
                                        }
                                    ) { Text("导入") }
                                }
                            }
                        }
                    } else if (errorMsg != null) {
                        Text(
                            text = errorMsg ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { if (!scanning) show = false },
                    enabled = !scanning
                ) { Text("关闭") }
            }
        )
    }
}
