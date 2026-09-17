package com.yunx.app.ui.clipboard

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.yunx.app.data.network.ParsedShare
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Toolkit

/**
 * 剪贴板识别到的网盘分享链接。
 * @param text 剪贴板原文（含链接与提取码，交给解析页直接解析）
 * @param parsed 解析结果（平台 / shareId / 提取码）
 */
data class ClipboardDetection(
    val text: String,
    val parsed: ParsedShare
)

/**
 * 剪贴板弹窗的全局状态桥：弹窗是独立顶层窗口，主窗口内只读状态，
 * 「打开」通过 openRequest 交给 MainScreen 切换到解析页。
 */
object ClipboardLinkController {

    /** 当前待展示的弹窗内容；null 表示不显示 */
    var pending by mutableStateOf<ClipboardDetection?>(null)
        private set

    /** 用户点击「打开」后待消费的检测结果 */
    var openRequest by mutableStateOf<ClipboardDetection?>(null)
        private set

    /** 主窗口引用（Main.kt 挂载），用于判断窗口是否失焦、打开时置前 */
    var mainWindow: Frame? = null

    fun show(detection: ClipboardDetection) {
        pending = detection
    }

    /** 忽略 / 关闭 / 超时自动消失 */
    fun dismiss() {
        pending = null
    }

    /** 点击「打开」：关弹窗并通知主窗口解析 */
    fun open() {
        val d = pending
        pending = null
        openRequest = d
    }

    fun consumeOpen(): ClipboardDetection? {
        val d = openRequest
        openRequest = null
        return d
    }

    /** 平台中文名（弹窗标题用） */
    fun platformName(platform: SharePlatform): String = when (platform) {
        SharePlatform.QUARK -> "夸克"
        SharePlatform.UC -> "UC"
        SharePlatform.XUNLEI -> "迅雷"
        SharePlatform.BAIDU -> "百度"
        SharePlatform.C139 -> "139"
        SharePlatform.PAN123 -> "123"
    }
}

/**
 * 剪贴板监听：每 1 秒轮询系统剪贴板。
 * 仅当「剪贴板内容变化 + 是受支持的网盘分享链接 + 主窗口失焦 + 无弹窗在展示」时
 * 触发右下角弹窗——窗口聚焦时用户自己粘贴即可，不打扰。
 *
 * 设置开关在循环内每次迭代重新读取，确保用户关闭开关后立即停止检测
 * （不依赖 Composable 重组——SettingsScreen 和 MainScreen 持有不同的
 * SettingsRepository 实例，设置变更不会触发 MainScreen 重组）。
 */
@Composable
fun ClipboardLinkDetector(
    settings: com.yunx.app.data.prefs.SettingsRepository
) {
    LaunchedEffect(Unit) {
        var lastText: String? = null
        while (true) {
            // 每次迭代重新读取开关，关闭后立即停止检测
            if (!settings.clipboardLinkDetection) {
                delay(1000)
                continue
            }
            // 剪贴板读取放 IO（AWT clipboard 可能阻塞）
            val text = withContext(Dispatchers.IO) { DesktopActions.readClipboard() }
            if (text != null && text != lastText) {
                lastText = text
                // AWT 焦点检查在 EDT（LaunchedEffect 默认上下文）上更可靠
                val main = ClipboardLinkController.mainWindow
                val notFocused = main == null || !main.isActive
                val parsed = ShareLinkParser.parse(text)
                if (parsed != null && notFocused && ClipboardLinkController.pending == null) {
                    ClipboardLinkController.show(ClipboardDetection(text, parsed))
                }
            }
            delay(1000)
        }
    }
}

/**
 * 右下角弹窗（独立无边框置顶窗口）：
 * 云朵图标 + 「发现X网盘分享链接」 + 链接预览，按钮为 忽略 / 打开，右上角 ×。
 * 沿用应用原有 Material3 材质（colorScheme.surface / primaryContainer / shapes），
 * 不抢焦点、10 秒无操作自动消失。
 *
 * **设计偏离 FadeAlertDialog 规范的说明**：
 * FadeAlertDialog 是窗口内覆盖层（OverlayDialogRegistry），主窗口失焦时本就不可见；
 * 但本弹窗的核心场景就是用户切到其它窗口复制链接、主窗口失焦时也能立刻看到提示，
 * 因此必须用独立顶层 Window。Window 设 [focusable]=false + AWT Type.POPUP，
 * 不抢焦点不阻塞 UI 线程，不会触发 ripple 动画卡顿（与 material3 Popup/AlertDialog
 * 创建独立窗口、抢焦点、阻塞 EDT 的行为不同）。
 */
@Composable
fun ClipboardLinkPopup() {
    val detection = ClipboardLinkController.pending ?: return
    Window(
        onCloseRequest = { ClipboardLinkController.dismiss() },
        undecorated = true,
        alwaysOnTop = true,
        visible = true,
        resizable = false,
        focusable = false,
        title = "云析",
        state = rememberWindowState(
            size = DpSize(400.dp, 132.dp),
            position = WindowPosition(Alignment.BottomEnd)
        )
    ) {
        val window = this.window
        // 读取 Surface 色作为窗口背景色，消除圆角裁切后的白边
        val surfaceColor = MaterialTheme.colorScheme.surface
        // 弹窗不抢焦点（否则一出现主窗口就被激活，破坏「失焦检测」语义）
        LaunchedEffect(Unit) {
            runCatching {
                // 窗口背景与 Surface 同色，圆角裁切边缘不会露出白底
                window.background = java.awt.Color(
                    surfaceColor.red, surfaceColor.green, surfaceColor.blue, surfaceColor.alpha
                )
                window.focusableWindowState = false
                window.isFocusable = false
                window.isAutoRequestFocus = false
                window.type = java.awt.Window.Type.POPUP
            }
            // 右下角定位 + 圆角裁剪：屏幕右下角留 24px 边距，避开任务栏/系统托盘
            val margin = 24
            while (true) {
                val w = window.width
                val h = window.height
                if (w > 0 && h > 0) {
                    val gc = window.graphicsConfiguration
                    val bounds = gc.bounds
                    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                    window.setLocation(
                        bounds.x + bounds.width - w - margin - insets.right,
                        bounds.y + bounds.height - h - margin - insets.bottom
                    )
                    // AWT setShape 裁剪窗口为圆角矩形，消除四角白色直角
                    window.shape = java.awt.geom.RoundRectangle2D.Float(
                        0f, 0f, w.toFloat(), h.toFloat(), 32f, 32f
                    )
                    break
                }
                delay(50)
            }
        }
        // 10 秒无操作自动消失
        LaunchedEffect(Unit) {
            delay(10_000)
            ClipboardLinkController.dismiss()
        }

        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)
                ) {
                    // 第一行：云朵图标 + 标题/链接 + 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "发现${ClipboardLinkController.platformName(detection.parsed.platform)}网盘分享链接",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = detection.text.trim().replace("\n", " ").take(50),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 关闭按钮：缩小点击区域，避免 48dp 默认最小尺寸撑高布局
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { ClipboardLinkController.dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 底部按钮行：始终贴底右对齐，与窗口宽度协调
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { ClipboardLinkController.dismiss() }) {
                            Text("忽略")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(onClick = { ClipboardLinkController.open() }) {
                            Text("打开")
                        }
                    }
                }
            }
        }
    }
}
