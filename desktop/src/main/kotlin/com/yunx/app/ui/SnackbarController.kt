package com.yunx.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 顶部轻提示停留时长：2 秒后自动淡出 */
private const val TOAST_DURATION_MS = 2_000L

/** 提示进出场动画时长 */
private const val TOAST_ANIM_MS = 180

/** 提示类型：决定气泡的标记颜色与图标 */
enum class SnackbarKind { Info, Success, Warning, Error }

/** 文案关键词 → 状态色推断（未显式指定类型时使用） */
private val ERROR_HINTS = listOf(
    "失败", "错误", "无效", "异常", "出错", "无法", "不能", "不存在", "未找到",
    "请先", "过期", "已失效", "已存在", "冲突", "超时", "拒绝"
)
private val SUCCESS_HINTS = listOf(
    "成功", "已保存", "已复制", "已删除", "已添加", "已完成", "已开始", "已恢复",
    "已导入", "已导出", "已清理", "已取消收藏", "已收藏", "已下载"
)
private val WARNING_HINTS = listOf("请", "注意", "警告", "仅支持", "需要", "上限", "已达", "已用完")

/** 顺序保存提示；仅最上层仍在组合中的宿主有权显示和消费。 */
internal class SnackbarEventQueue {
    internal data class Event(val seq: Long, val message: String, val kind: SnackbarKind)
    internal data class Snapshot(val events: List<Event> = emptyList(), val hosts: List<Any> = emptyList())
    private val lock = Any()
    private var sequence = 0L
    internal val state = MutableStateFlow(Snapshot())

    fun show(message: String, kind: SnackbarKind) = synchronized(lock) {
        state.value = state.value.copy(events = state.value.events + Event(++sequence, message, kind))
    }

    fun register(host: Any) = synchronized(lock) {
        state.value = state.value.copy(hosts = state.value.hosts.filterNot { it === host } + host)
    }

    fun unregister(host: Any) = synchronized(lock) {
        state.value = state.value.copy(hosts = state.value.hosts.filterNot { it === host })
    }

    fun consume(host: Any, sequence: Long) = synchronized(lock) {
        val current = state.value
        if (current.hosts.lastOrNull() === host) {
            state.value = current.copy(events = current.events.filterNot { it.seq == sequence })
        }
    }

    /** 返回当前应展示的全部提示（仅最上层宿主有内容），按到达顺序排列。 */
    fun eventsFor(host: Any) = state.map { snapshot ->
        if (snapshot.hosts.lastOrNull() === host) snapshot.events else emptyList()
    }.distinctUntilChanged()
}

/** 全局入口保持不变；未显式指定类型时按文案推断状态色。 */
object SnackbarController {
    internal val queue = SnackbarEventQueue()

    fun show(message: String, kind: SnackbarKind = inferKind(message)) {
        queue.show(message, kind)
    }

    private fun inferKind(message: String): SnackbarKind = when {
        ERROR_HINTS.any { message.contains(it) } -> SnackbarKind.Error
        SUCCESS_HINTS.any { message.contains(it) } -> SnackbarKind.Success
        WARNING_HINTS.any { message.contains(it) } -> SnackbarKind.Warning
        else -> SnackbarKind.Info
    }
}

/** 一条正在展示的提示：自带到退场动画状态，动画结束后自行移除。 */
private class ToastItem(val event: SnackbarEventQueue.Event) {
    val transition = MutableTransitionState(false)
}

/**
 * 全局轻提示宿主：在各页面顶层调用一次即可（Scaffold 的 `snackbarHost` 槽或页面根 Box 内）。
 *
 * 行为：
 * - 提示是**顶部居中**的一列圆角气泡，多个提示自上而下依次排列，互不重叠；
 * - 每条提示出现 / 消失都有过渡，最上方（最早）的一条 2 秒后淡出，下方提示平滑向上补位；
 * - 颜色标记状态：成功=绿、失败=红、警告=橙、普通=主题色（也可调用处显式指定 [SnackbarKind]）；
 * - 用独立 [Popup] 绘制：Popup 自身零尺寸，不占布局、不影响页面排版；
 *   层级由调用点决定 —— 写在 ModalBottomSheet / 覆盖页内部时，提示就落在该弹层之上。
 */
@Composable
fun GlobalSnackbarHost() {
    val host = remember { Any() }
    val queue = SnackbarController.queue
    DisposableEffect(host) {
        queue.register(host)
        onDispose { queue.unregister(host) }
    }

    val items = remember { mutableStateListOf<ToastItem>() }

    LaunchedEffect(host) {
        queue.eventsFor(host).collect { events ->
            events.forEach { event ->
                if (items.none { it.event.seq == event.seq }) items.add(ToastItem(event))
            }
        }
    }

    val topOffset = with(LocalDensity.current) { 16.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, topOffset),
        properties = PopupProperties(focusable = false)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { item ->
                key(item.event.seq) {
                    // 入场 → 停留 2 秒 → 退场 → 移除并消费（下方提示随高度收起平滑上移）
                    LaunchedEffect(item) {
                        item.transition.targetState = true
                        delay(TOAST_DURATION_MS)
                        item.transition.targetState = false
                        snapshotFlow { item.transition.isIdle && !item.transition.currentState }
                            .filter { it }
                            .first()
                        items.remove(item)
                        queue.consume(host, item.event.seq)
                    }
                    AnimatedVisibility(
                        visibleState = item.transition,
                        enter = fadeIn(tween(TOAST_ANIM_MS)) +
                            expandVertically(tween(TOAST_ANIM_MS), expandFrom = Alignment.Top),
                        exit = fadeOut(tween(TOAST_ANIM_MS)) +
                            shrinkVertically(tween(TOAST_ANIM_MS), shrinkTowards = Alignment.Top)
                    ) {
                        ToastBubble(item.event)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastBubble(event: SnackbarEventQueue.Event) {
    val accent = when (event.kind) {
        SnackbarKind.Success -> Color(0xFF22A06B)
        SnackbarKind.Warning -> Color(0xFFD98E00)
        SnackbarKind.Error -> MaterialTheme.colorScheme.error
        SnackbarKind.Info -> MaterialTheme.colorScheme.primary
    }
    val icon: ImageVector = when (event.kind) {
        SnackbarKind.Success -> Icons.Outlined.CheckCircle
        SnackbarKind.Warning -> Icons.Outlined.Warning
        SnackbarKind.Error -> Icons.Outlined.ErrorOutline
        SnackbarKind.Info -> Icons.Outlined.Info
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shadowElevation = 6.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 340.dp)
            )
        }
    }
}
