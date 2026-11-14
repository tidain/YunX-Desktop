package com.yunx.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yunx.app.data.upload.UploadCandidate
import com.yunx.app.data.upload.UploadPhase
import com.yunx.app.data.upload.UploadRunner
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.File
import javax.swing.Timer

/** 离线界面验收入口：真实原生拖放和正式 UI，协议只模拟进度，绝不发送 HTTP。 */
fun main(args: Array<String>) {
    val fixtures = File(args.single()).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    require(fixtures.size == 2) { "Expected exactly two local smoke files" }
    System.setProperty("skiko.renderApi", "SOFTWARE")
    Timer(480_000) { System.exit(0) }.apply { isRepeats = false; start() }
    application {
        // The smoke harness drives Compose from a coroutine so simulated delays are legal.
        Window(onCloseRequest = { System.exit(0) }, title = "YunX 上传界面验证（离线）", state = rememberWindowState(width = 880.dp, height = 700.dp)) {
            ComposeEmptyActivityTheme {
                val scope = rememberCoroutineScope()
                var result by remember { mutableStateOf("尚未上传；所有操作只使用本地测试文件") }
                val runner = remember { UploadRunner(scope, { OkHttpClient() }) {
                    result = it.message()
                    println("UI_OUTCOME " + result)
                } }
                val ui = rememberUploadUi("smoke-dir", "测试网盘 / 中文目标目录", runner.progress == null) { files: List<UploadCandidate> ->
                    runner.startCandidates(files, "upload-ui-smoke", { "local-test-account" }) { io, source ->
                        io.progress(UploadPhase.PREPARING)
                        delay(700)
                        repeat(20) {
                            io.checkpoint()
                            io.progress(UploadPhase.UPLOADING, source.size * (it + 1) / 20)
                            delay(400)
                        }
                        io.progress(UploadPhase.FINISHING)
                        delay(800)
                    }
                }
                DisposableEffect(runner) { onDispose { runner.cancel() } }
                LaunchedEffect(ui.files, ui.visible) { println("UI_SELECTION visible=" + ui.visible + " count=" + ui.files.size + " destination=" + ui.destination) }
                LaunchedEffect(runner.progress?.index, runner.progress?.phase) { println("UI_PROGRESS " + runner.progress) }
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("上传界面验证 · 仅本地模拟", style = MaterialTheme.typography.titleLarge)
                                Row {
                                    TextButton(onClick = { ui.stage(fixtures) }, enabled = runner.progress == null) { Text("载入测试文件") }
                                    UploadIconButton(onClick = ui::open, enabled = runner.progress == null)
                                }
                            }
                            Text("测试网盘 / 中文目标目录", style = MaterialTheme.typography.bodyMedium)
                            Box(Modifier.fillMaxWidth().weight(1f).uploadDropTarget(ui)) {
                                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    UploadDropHint(ui, runner.progress == null)
                                    Text(result)
                                    Text("从系统文件管理器拖入文件，或载入两个本地测试文件；确认前不会启动模拟上传。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    UploadDialogs(ui, runner.progress, runner::cancel)
                    OverlayDialogHost()
                }
            }
        }
    }
}
