package com.yunx.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 全局弹窗覆盖层注册表：FadeAlertDialog 把内容注册到这里，
 * 由应用根部的 OverlayDialogHost 统一渲染（主窗口自身图层内直接绘制）。
 *
 * 为什么不用 Popup / AlertDialog：桌面端二者底层都会创建独立原生窗口，
 * 打开瞬间阻塞 UI 线程，导致触发按钮的水波动画卡顿、弹窗突然出现；
 * SOFTWARE 渲染下新窗口表面的创建/合成开销更大，卡顿更明显。
 * 窗口内覆盖层零窗口创建、零新渲染表面，淡入淡出为纯 Compose 动画，丝滑无卡顿。
 */
object OverlayDialogRegistry {
    class Entry(val content: @Composable () -> Unit)

    val entries = mutableStateListOf<Entry>()

    internal fun add(entry: Entry) {
        entries.add(entry)
    }

    internal fun remove(entry: Entry) {
        entries.remove(entry)
    }
}

/**
 * 渲染所有已注册的覆盖层弹窗。
 * 放在 MainScreen 根部 Box 的最后（内容之上）：遮罩自然覆盖整个窗口（含导航栏/顶栏），
 * 后注册的弹窗绘制在上层（如导出弹窗淡出时加载弹窗淡入，加载层在顶）。
 */
@Composable
fun OverlayDialogHost() {
    Box(modifier = Modifier.fillMaxSize()) {
        OverlayDialogRegistry.entries.forEach { entry ->
            entry.content()
        }
    }
}

/**
 * 窗口内轻量弹窗：替代 material3 AlertDialog / Popup（桌面端均为独立原生窗口）。
 *
 * 遮罩淡入淡出 + 卡片淡入淡出 + 轻微缩放，全部由 Compose 动画驱动，
 * 无原生窗口创建开销，触发按钮的水波动画不会被阻塞。
 *
 * 关闭流程：调用方把 visible 置 false → 播放淡出动画 → 动画结束后注销内容。
 */
@Composable
fun FadeAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null
) {
    // 是否仍需渲染内容（淡出动画期间保持渲染，结束后注销）
    var composed by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        when {
            visible && !composed -> {
                // 先让点击水波干净地跑一帧：弹窗内容组合（首次较重）推迟到下一帧，
                // 避免与水波起始帧挤在一起造成「点击瞬间卡一下」的感觉
                withFrameNanos { }
                composed = true
                alpha.snapTo(0f)
                alpha.animateTo(1f, animationSpec = tween(durationMillis = 170, easing = LinearOutSlowInEasing))
            }
            !visible && composed -> {
                alpha.animateTo(0f, animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing))
                composed = false
            }
        }
    }

    if (!composed) return

    // 始终捕获最新参数/lambda：Entry 只注册一次，内容经由 State 读取最新值
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentIcon by rememberUpdatedState(icon)
    val currentTitle by rememberUpdatedState(title)
    val currentText by rememberUpdatedState(text)
    val currentConfirm by rememberUpdatedState(confirmButton)
    val currentDismiss by rememberUpdatedState(dismissButton)

    val entry = remember {
        OverlayDialogRegistry.Entry {
            DialogSurface(
                alpha = alpha,
                onDismissRequest = { currentOnDismiss() },
                icon = currentIcon,
                title = currentTitle,
                text = currentText,
                confirmButton = currentConfirm,
                dismissButton = currentDismiss
            )
        }
    }

    DisposableEffect(Unit) {
        OverlayDialogRegistry.add(entry)
        onDispose {
            OverlayDialogRegistry.remove(entry)
        }
    }
}

/** 遮罩 + 卡片（由 OverlayDialogHost 在窗口根部绘制） */
@Composable
private fun DialogSurface(
    alpha: Animatable<Float, AnimationVector1D>,
    onDismissRequest: () -> Unit,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?
) {
    // 遮罩层：点击空白处关闭。
    // alpha 全程只在绘制阶段读取（graphicsLayer），动画期间零重组、零布局，
    // SOFTWARE 渲染下每帧开销降到最低
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismissRequest() },
        contentAlignment = Alignment.Center
    ) {
        // 卡片：轻微缩放（0.95 → 1.0）；透明度继承自外层遮罩层的 graphicsLayer alpha，
        // 避免双层 alpha 相乘造成 α² 衰减
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .graphicsLayer {
                    val s = 0.95f + 0.05f * alpha.value
                    scaleX = s
                    scaleY = s
                }
                // 吞掉卡片上的点击，避免误触遮罩关闭
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .padding(24.dp)
            ) {
                icon?.let {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { it() }
                }
                title?.let {
                    Box(modifier = Modifier.padding(bottom = 16.dp)) { it() }
                }
                text?.invoke()
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    Spacer(modifier = Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}
