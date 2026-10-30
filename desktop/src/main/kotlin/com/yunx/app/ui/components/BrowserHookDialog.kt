package com.yunx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.yunx.app.data.bridge.BrowserBridge
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 超过该线程数时，点「下载」需要二次确认（避免把服务器/本机打爆） */
private const val THREAD_CONFIRM_THRESHOLD = 32

/** 线程数快捷档位（常用值，点一下即选中） */
private val THREAD_PRESETS = listOf(4, 8, 16, 32, 64, 128, 256, 512)

/**
 * 主程序侧注册的「交给云析下载」处理器。
 *
 * 弹窗是**独立顶层窗口**（不在主窗口的组合树里），拿不到 MainScreen 中 remember 出来的
 * DownloadManager / coroutineScope，因此由 MainScreen 在挂载时把回调注册进来。
 */
object BrowserHookController {

    /** 用户确认「下载」时调用：(请求, 保存目录, 线程数)；由 MainScreen 注册 */
    @Volatile
    var onAccept: ((BrowserBridge.HookRequest, String, Int) -> Unit)? = null
}

/**
 * 浏览器插件劫持下载的询问弹窗（独立无边框置顶窗口）。
 *
 * 与 [FadeAlertDialog]（窗口内覆盖层）不同：覆盖层绘制在主窗口图层里，弹出时必然连带
 * 主窗口一起露出来，主窗口被最小化到托盘时更是完全弹不出来。劫持场景下用户正在浏览器里
 * 操作、云析可能已在后台/托盘，所以这里改用独立顶层 Window——只弹自己的小窗，不打扰主界面。
 *
 * 挂载于 `application { }` 内，与主窗口平级，保证主窗口隐藏时也能弹出。
 */
@Composable
fun BrowserHookDialogWindow() {
    val pending by BrowserBridge.pending.collectAsState()
    val req = pending.firstOrNull() ?: return

    Window(
        onCloseRequest = { BrowserBridge.cancel(req) },
        title = "云析下载助手",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        state = rememberWindowState(
            size = DpSize(520.dp, 412.dp),
            position = WindowPosition(Alignment.Center)
        )
    ) {
        val window = this.window
        // 无边框窗口：等 AWT 窗口拿到真实尺寸后裁剪为圆角矩形
        LaunchedEffect(Unit) {
            while (true) {
                val w = window.width
                val h = window.height
                if (w > 0 && h > 0) {
                    runCatching {
                        window.shape = java.awt.geom.RoundRectangle2D.Float(
                            0f, 0f, w.toFloat(), h.toFloat(), 28f, 28f
                        )
                    }
                    break
                }
                delay(50)
            }
        }

        ComposeEmptyActivityTheme {
            // 背景取主题 Surface 色（窗口自己的组合树，必须在主题内取）
            val surfaceColor = MaterialTheme.colorScheme.surface
            LaunchedEffect(surfaceColor) {
                runCatching {
                    window.background = java.awt.Color(
                        surfaceColor.red, surfaceColor.green, surfaceColor.blue, surfaceColor.alpha
                    )
                    // UTILITY：不出现在任务栏/Alt+Tab（仍可接收焦点，输入框可正常打字）
                    window.type = java.awt.Window.Type.UTILITY
                }
            }
            BrowserHookDialogContent(req)
        }
    }
}

@Composable
private fun BrowserHookDialogContent(req: BrowserBridge.HookRequest) {
    val settings = remember { SettingsRepository() }
    // 记住上次在弹窗里选过的目录；为空 = 跟随「设置 → 储存目录」的全局下载目录
    var saveDir by remember { mutableStateOf(settings.browserHookLastSaveDir) }
    var threadsText by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf(1) }
    var confirmHigh by remember { mutableStateOf(false) }

    // 每次换一条请求就把控件重置为「跟随设置」的默认值
    LaunchedEffect(req.id) {
        saveDir = settings.browserHookLastSaveDir
        threads = settings.downloadThreadsFor(DownloadPlatform.GENERIC)
            .coerceIn(1, SettingsRepository.MAX_DOWNLOAD_THREADS)
        threadsText = threads.toString()
        confirmHigh = false
    }

    /** 校验并同步输入框 → 线程数（非法输入保持当前值，不写回） */
    fun commitThreads(raw: String) {
        threadsText = raw
        val n = raw.trim().toIntOrNull() ?: return
        threads = n.coerceIn(1, SettingsRepository.MAX_DOWNLOAD_THREADS)
    }

    fun setThreads(n: Int) {
        threads = n.coerceIn(1, SettingsRepository.MAX_DOWNLOAD_THREADS)
        threadsText = threads.toString()
    }

    val doAccept: () -> Unit = {
        // 记住这次选的目录，下次弹窗沿用
        settings.browserHookLastSaveDir = saveDir
        val handler = BrowserHookController.onAccept
        if (handler != null) {
            // 先把决定回传给扩展：完成长轮询 + 把自己从 pending 摘掉（弹窗随即消失）。
            // 这里走 Accept 而不是 ReturnToBrowser —— 扩展不会写根域名冷却，
            // 之后同一域名的下载照常继续劫持。
            BrowserBridge.accept(req, saveDir, threads)
            handler(req, saveDir, threads)
        } else {
            BrowserBridge.returnToBrowser(req)
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                ) {
                    DialogHeader(onCancel = { BrowserBridge.cancel(req) })
                    Spacer(Modifier.height(14.dp))

                    // 文件信息
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = req.fileName.ifBlank { "未命名文件" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = buildString {
                                    append(if (req.fileSize > 0) formatBytes(req.fileSize) else "大小未知")
                                    if (req.rootDomain.isNotBlank()) append(" · ").append(req.rootDomain)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 保存目录
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "保存到",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = saveDir.ifBlank { "跟随「设置 → 储存目录」" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = {
                                DesktopActions.pickDirectory()?.let { saveDir = it }
                            }) { Text("选择目录") }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // 线程数：数字输入（精确） + 档位（快捷） + 滑块（粗调）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "线程数",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        OutlinedTextField(
                            value = threadsText,
                            onValueChange = { commitThreads(it.filter { c -> c.isDigit() }.take(3)) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.width(86.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "/ ${SettingsRepository.MAX_DOWNLOAD_THREADS}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        THREAD_PRESETS.forEach { preset ->
                            ThreadPresetChip(
                                label = preset.toString(),
                                selected = threads == preset,
                                onClick = { setThreads(preset) }
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    ThreadSlider(
                        value = threads,
                        max = SettingsRepository.MAX_DOWNLOAD_THREADS,
                        onValueChange = { setThreads(it) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (threads > THREAD_CONFIRM_THRESHOLD) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "超过 $THREAD_CONFIRM_THRESHOLD 线程需二次确认",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { BrowserBridge.cancel(req) }) { Text("取消") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { BrowserBridge.returnToBrowser(req) }) {
                            Text("归还浏览器下载")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (threads > THREAD_CONFIRM_THRESHOLD) confirmHigh = true else doAccept()
                        }) { Text("开始下载") }
                    }
                }

                // 线程数 > 阈值：窗口内遮罩二次确认（不再开第二个窗口）
                if (confirmHigh) {
                    HighThreadConfirm(
                        threads = threads,
                        onAdjust = { confirmHigh = false },
                        onConfirm = {
                            confirmHigh = false
                            doAccept()
                        }
                    )
                }
            }
        }
    }
}

/** 头部：下载图标 + 标题 + 关闭 */
@Composable
private fun DialogHeader(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "云析下载助手",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "拦截到浏览器下载，请选择保存位置与线程数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onCancel() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "取消",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 线程数快捷档位胶囊 */
@Composable
private fun ThreadPresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg
        )
    }
}

/**
 * 自绘线程数滑块：6dp 圆角轨道 + 18dp 圆形滑块。
 * 比 material3 Slider 更紧凑、无默认的「值气泡/刻度」视觉噪音。
 */
@Composable
private fun ThreadSlider(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableStateOf(0f) }
    val thumbSize = 18.dp
    val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumbSize.toPx() }
    val range = (max - 1).coerceAtLeast(1)
    val frac = ((value - 1).toFloat() / range).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary

    fun pick(x: Float) {
        val span = widthPx - thumbPx
        if (span <= 0f) return
        val f = ((x - thumbPx / 2f) / span).coerceIn(0f, 1f)
        onValueChange((1 + f * range).roundToInt().coerceIn(1, max))
    }

    Box(
        modifier = modifier
            .height(thumbSize)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(max) { detectTapGestures { pick(it.x) } }
            .pointerInput(max) { detectDragGestures { change, _ -> pick(change.position.x) } },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(trackColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(activeColor)
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((frac * (widthPx - thumbPx)).roundToInt(), 0) }
                .size(thumbSize)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

/** 高线程数二次确认：半透明遮罩 + 居中卡片 */
@Composable
private fun HighThreadConfirm(threads: Int, onAdjust: () -> Unit, onConfirm: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures { /* 吞掉点击，避免误触下层 */ } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "确认使用 $threads 个线程？",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "高线程数会同时发起大量连接：部分服务器会限速或直接拒绝，" +
                        "也可能明显占用本机带宽与内存。建议先用 32 或更少线程，速度不理想时再逐步提高。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onAdjust) { Text("调整线程数") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("仍然下载") }
                }
            }
        }
    }
}
