package com.yunx.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 顶部轻提示停留时长：2 秒后自动淡出 */
private const val TOAST_DURATION_MS = 2_000L

/** 顺序保存提示；仅最上层仍在组合中的宿主有权显示和消费。 */
internal class SnackbarEventQueue {
    internal data class Event(val seq: Long, val message: String)
    internal data class Snapshot(val events: List<Event> = emptyList(), val hosts: List<Any> = emptyList())
    private val lock = Any()
    private var sequence = 0L
    internal val state = MutableStateFlow(Snapshot())

    fun show(message: String) = synchronized(lock) {
        state.value = state.value.copy(events = state.value.events + Event(++sequence, message))
    }

    fun register(host: Any) = synchronized(lock) {
        state.value = state.value.copy(hosts = state.value.hosts.filterNot { it === host } + host)
    }

    fun unregister(host: Any) = synchronized(lock) {
        state.value = state.value.copy(hosts = state.value.hosts.filterNot { it === host })
    }

    fun consume(host: Any, sequence: Long) = synchronized(lock) {
        val current = state.value
        if (current.hosts.lastOrNull() === host && current.events.firstOrNull()?.seq == sequence) {
            state.value = current.copy(events = current.events.drop(1))
        }
    }

    fun eventsFor(host: Any) = state.map { snapshot ->
        snapshot.events.firstOrNull().takeIf { snapshot.hosts.lastOrNull() === host }
    }.distinctUntilChanged()
}

/** 全局入口保持不变；连续调用按顺序排队，弹窗和覆盖页接管提示宿主。 */
object SnackbarController {
    internal val queue = SnackbarEventQueue()
    fun show(message: String) { queue.show(message) }
}

/**
 * 全局轻提示宿主：在各页面顶层调用一次即可（Scaffold 的 `snackbarHost` 槽或页面根 Box 内）。
 *
 * 提示是**顶部居中的圆角气泡**，2 秒后自动淡出；用独立 [Popup] 绘制：
 * - Popup 自身零尺寸，不占布局、不影响页面排版，也不拦截其它区域的点击；
 * - 层级由调用点决定 —— 写在 ModalBottomSheet / 覆盖页内部时，提示就落在该弹层之上。
 *   （历史实现用 Material3 SnackbarHost 画在底部，那种一长条深色/白色横条观感偏重，已弃用。）
 */
@Composable
fun GlobalSnackbarHost() {
    val host = remember { Any() }
    DisposableEffect(host) {
        SnackbarController.queue.register(host)
        onDispose { SnackbarController.queue.unregister(host) }
    }

    var current by remember { mutableStateOf<SnackbarEventQueue.Event?>(null) }
    // 退场动画期间 current 已置空，用上一条文案兜底，避免气泡内容闪空
    var lastMessage by remember { mutableStateOf("") }

    LaunchedEffect(host) {
        SnackbarController.queue.eventsFor(host).collectLatest { event ->
            if (event == null) {
                current = null
                return@collectLatest
            }
            lastMessage = event.message
            current = event
            delay(TOAST_DURATION_MS)
            current = null
            SnackbarController.queue.consume(host, event.seq)
        }
    }

    val topOffset = with(LocalDensity.current) { 16.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, topOffset),
        properties = PopupProperties(focusable = false)
    ) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }
}
