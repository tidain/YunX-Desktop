package com.yunx.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yunx.app.data.bridge.BrowserBridge
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.DesktopActions

/** 超过该线程数时，点「下载」需要二次确认（避免把服务器/本机打爆） */
private const val THREAD_CONFIRM_THRESHOLD = 32

/**
 * 浏览器插件劫持下载的询问弹窗宿主。
 *
 * 扩展通过本地回环桥（见 `BrowserBridgeServer`）把劫持到的下载挂起在这里；
 * 用户选择「下载到哪里 + 用多少线程」后交给云析下载，或者「归还浏览器下载」。
 * 挂在应用根部常驻组合中（与 [EngineInstallPromptHost] 同级），使后台触发的劫持也能弹出。
 *
 * @param onAccept 用户确认「下载」时回调（由 MainScreen 接线到 DownloadManager.enqueue）
 */
@Composable
fun BrowserHookDialogHost(onAccept: (BrowserBridge.HookRequest, String, Int) -> Unit) {
    val pending by BrowserBridge.pending.collectAsState()
    val req = pending.firstOrNull() ?: return

    val settings = remember { SettingsRepository() }
    // 记住上次在弹窗里选过的目录；为空 = 跟随「设置 → 储存目录」的全局下载目录
    var saveDir by remember { mutableStateOf(settings.browserHookLastSaveDir) }
    var threadsText by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf(0) }
    var confirmHigh by remember { mutableStateOf(false) }

    // 每次换一条请求就把控件重置为「跟随设置」的默认值
    LaunchedEffect(req.id) {
        saveDir = settings.browserHookLastSaveDir
        threads = settings.downloadThreadsFor(DownloadPlatform.GENERIC)
            .coerceIn(1, SettingsRepository.MAX_DOWNLOAD_THREADS)
        threadsText = threads.toString()
        confirmHigh = false
    }

    /** 校验并同步输入框 → 线程数（非法输入回退到当前值） */
    fun commitThreads(raw: String) {
        threadsText = raw
        val n = raw.trim().toIntOrNull() ?: return
        threads = n.coerceIn(1, SettingsRepository.MAX_DOWNLOAD_THREADS)
    }

    val doAccept: () -> Unit = {
        // 记住这次选的目录，下次弹窗沿用
        settings.browserHookLastSaveDir = saveDir
        onAccept(req, saveDir, threads)
    }

    FadeAlertDialog(
        visible = true,
        // 点击遮罩关闭 = 放弃本次下载
        onDismissRequest = { BrowserBridge.cancel(req) },
        title = { Text("云析下载助手：拦截到下载") },
        text = {
            Column {
                Text(
                    text = req.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(if (req.fileSize > 0) formatBytes(req.fileSize) else "大小未知")
                        if (req.rootDomain.isNotBlank()) append(" · ").append(req.rootDomain)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // 下载到
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("下载到", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = saveDir.ifBlank { "跟随设置中的保存目录" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    TextButton(onClick = {
                        DesktopActions.pickDirectory()?.let { saveDir = it }
                    }) { Text("选择目录…") }
                }

                Spacer(Modifier.height(12.dp))

                // 线程数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("线程数", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = threadsText,
                        onValueChange = { commitThreads(it.filter { c -> c.isDigit() }) },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "（1 - ${SettingsRepository.MAX_DOWNLOAD_THREADS}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = threads.toFloat(),
                    onValueChange = {
                        val n = it.toInt().coerceIn(1, SettingsRepository.MAX_DOWNLOAD_THREADS)
                        threads = n
                        threadsText = n.toString()
                    },
                    valueRange = 1f..SettingsRepository.MAX_DOWNLOAD_THREADS.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (threads > THREAD_CONFIRM_THRESHOLD) {
                    Text(
                        text = "线程数超过 $THREAD_CONFIRM_THRESHOLD，点击「下载」需二次确认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (threads > THREAD_CONFIRM_THRESHOLD) confirmHigh = true else doAccept()
            }) { Text("下载") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { BrowserBridge.returnToBrowser(req) }) {
                    Text("归还浏览器下载")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { BrowserBridge.cancel(req) }) { Text("取消") }
            }
        }
    )

    // 线程数 > 32 的二次确认
    if (confirmHigh) {
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { confirmHigh = false },
            title = { Text("确认使用 $threads 个线程？") },
            text = {
                Column {
                    Text(
                        text = "高线程数会同时发起大量连接：部分服务器会限速或直接拒绝，" +
                            "也可能明显占用本机带宽与内存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "建议先使用 32 或更少线程，速度不理想时再逐步提高。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmHigh = false
                    doAccept()
                }) { Text("仍然下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmHigh = false }) { Text("调整线程数") }
            }
        )
    }
}
