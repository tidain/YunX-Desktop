package com.yunx.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * 桌面版「返回」处理：Escape 键触发，语义对齐 Android 的 BackHandler。
 *
 * 多个 BackHandler 同时注册时（全屏覆盖层 + 底层页面），**最后注册的（最上层）优先**，
 * 与 Android 的返回栈行为一致。仅当 enabled=true 时生效。
 */
private object BackHandlerRegistry {

    private val handlers = mutableListOf<() -> Unit>()

    @Volatile
    private var installed = false

    @Synchronized
    private fun ensureInstalled() {
        if (installed) return
        installed = true
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                val handler = synchronized(handlers) { handlers.lastOrNull() }
                if (handler != null) {
                    runCatching { handler() }
                    true // 消费事件，不再向下传播
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    @Synchronized
    fun add(handler: () -> Unit) {
        ensureInstalled()
        handlers.add(handler)
    }

    @Synchronized
    fun remove(handler: () -> Unit) {
        handlers.remove(handler)
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (enabled) {
            val handler: () -> Unit = { currentOnBack.value() }
            BackHandlerRegistry.add(handler)
            onDispose { BackHandlerRegistry.remove(handler) }
        } else {
            onDispose { }
        }
    }
}
