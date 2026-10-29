package com.yunx.app.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sun.jna.platform.win32.WinDef
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.preview.FfplayManager
import com.yunx.app.data.preview.FfplayWindowFx
import com.yunx.app.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * ffplay 在线预览播放器。
 *
 * 与内置 JavaFX 播放器的根本区别：ffplay 直接读 http(s) 直链（原生支持 HLS），
 * 真正做到「边下边播」，不需要先把文件落盘再播（见 [FfplayManager] 的说明）。
 *
 * 窗口形态：Windows 上把 ffplay 的顶层窗口用 `SetParent` 嵌进应用窗口（见 [FfplayWindowFx]），
 * 视频区由这个原生子窗口占据 —— 所以**控制条必须画在视频区之外**（原生窗口会盖住 Compose 内容）。
 * 嵌入失败（拿不到句柄 / 非 Windows）自动降级为独立窗口，功能不受影响。
 */
@Composable
internal fun FfplayPlayer(
    media: VideoMedia,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    // 每次换片重新定位一次：用户在设置页刚下完 ffmpeg 就能立刻用上
    var exe by remember(media) { mutableStateOf(FfplayManager.locate()) }
    val url = remember(media) { FfplayManager.urlFor(media) }
    val current = exe

    when {
        current == null -> FfplayUnavailablePanel(
            onInstalled = { exe = FfplayManager.locate(forceRefresh = true) }
        )

        url == null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("该视频暂时无法在线预览", color = Color(0xFFE6E6E6))
        }

        else -> FfplayStage(
            exe = current,
            url = url,
            media = media,
            title = title,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onClose = onClose
        )
    }
}

@Composable
private fun FfplayStage(
    exe: File,
    url: String,
    media: VideoMedia,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    val controller = remember(exe, url) {
        FfplayPreviewController(exe, url, FfplayManager.headersFor(media), title)
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.dispose() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 视频区：只是「告诉原生窗口该占哪块地方」，本身不画东西
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onGloballyPositioned { coords ->
                    if (!coords.isAttached) return@onGloballyPositioned
                    val r = coords.boundsInWindow()
                    controller.updateBounds(
                        r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt()
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val err = controller.error
            val frozen = controller.frozenFrame
            when {
                err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = err,
                        color = Color(0xFFE6E6E6),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { controller.restart() }) { Text("重试") }
                }

                // 已嵌入：这块区域被原生 ffplay 子窗口占据
                controller.embedded == true -> Unit

                // seek 重启进程期间：原生窗口已销毁，把抓到的「最后一帧」画出来盖住黑底
                frozen != null -> {
                    Image(
                        bitmap = frozen,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("加载中…", color = Color(0xFFE6E6E6))
                    }
                }

                controller.embedded == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(controller.statusText, color = Color(0xFFE6E6E6))
                }

                else -> Text(
                    text = controller.statusText,
                    color = Color(0xFF9E9E9E),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        FfplayControls(
            controller = controller,
            title = title,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onClose = onClose
        )
    }
}

/**
 * ffplay 控制条。放在视频区之外（原生子窗口会盖住 Compose 控件）。
 *
 * 注意 ffplay 运行时**没有倍速控制**，所以这里不提供倍速按钮；
 * 暂停/静音靠向 ffplay 窗口转发 `p` / `m` 按键实现，seek 走「重启进程 + `-ss`」。
 */
@Composable
private fun FfplayControls(
    controller: FfplayPreviewController,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    val durationMs = controller.durationMs
    val seekable = durationMs > 0
    val positionMs = if (dragging && seekable) (dragValue * durationMs).toLong() else controller.positionMs
    val progress = if (seekable) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.togglePlayPause() }) {
                Icon(
                    imageVector = if (controller.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (controller.playing) "暂停" else "播放",
                    tint = Color.White
                )
            }
            Text(
                text = formatTime(positionMs),
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = progress,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (seekable) controller.seekTo((dragValue * durationMs).toLong())
                    dragging = false
                },
                enabled = seekable,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                text = if (seekable) formatTime(durationMs) else "--:--",
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { controller.toggleMute() }) {
                Icon(
                    imageVector = if (controller.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = if (controller.muted) "取消静音" else "静音",
                    tint = Color.White
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    tint = Color.White
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
            }
        }
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

/** 本机没有 ffplay 时的引导页：一键下载 ffmpeg 精简版到应用私有目录（不动系统 PATH） */
@Composable
private fun FfplayUnavailablePanel(onInstalled: () -> Unit) {
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var read by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "未检测到 FFplay（FFmpeg 套件）",
            color = Color(0xFFE6E6E6),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "在线预览（边下边播）需要 ffplay。可一键下载 FFmpeg 精简版到本应用私有目录，" +
                "不会改动系统环境变量。",
            color = Color(0xFF9E9E9E),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        if (downloading) {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (total > 0) {
                    "已下载 ${formatBytes(read)} / ${formatBytes(total)}"
                } else {
                    "已下载 ${formatBytes(read)}"
                },
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { job?.cancel() }) { Text("取消下载") }
        } else {
            Button(onClick = {
                message = null
                downloading = true
                read = 0L
                total = 0L
                job = scope.launch {
                    try {
                        FfplayManager.download { r, t ->
                            read = r
                            total = t
                        }.onSuccess { onInstalled() }
                            .onFailure { message = "下载失败：${it.message ?: "未知错误"}" }
                    } catch (c: CancellationException) {
                        message = "已取消下载"
                        throw c
                    } catch (t: Throwable) {
                        message = "下载失败：${t.message ?: "未知错误"}"
                    } finally {
                        downloading = false
                    }
                }
            }) { Text("下载 FFmpeg 精简版") }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "也可以自行安装 FFmpeg（或把 ffplay.exe 所在目录加入 PATH）后重新打开预览。",
            color = Color(0xFF6E6E6E),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

// ---- ffplay 进程控制 ----

/**
 * 一个 ffplay 子进程的生命周期控制器。
 *
 * 三个要点：
 * 1. **进度靠解析 stderr**：ffplay 的 `Duration:` 行与状态行（`   1.02 M-V: ...`）都走 stderr，
 *    状态行以裸 `\r` 结尾（`readLine` 可正常切分），日志里混着 ANSI 转义序列且可能出现在行首/行尾，
 *    所以正则一律用 `find` 匹配、不锚定行首；
 * 2. **seek = 重启进程 + `-ss`**：ffplay 没有可靠的运行时 seek 接口，模拟按键又容易失焦，
 *    重启是最稳的做法（代价是重新建连，但这点延迟换取「拖到哪播到哪」是值的）；
 * 3. **按键转发**：暂停/静音往 ffplay 窗口 PostMessage `p` / `m`（见 [FfplayWindowFx.sendKey]）。
 */
private class FfplayPreviewController(
    private val exe: File,
    private val url: String,
    private val headers: Map<String, String>,
    private val title: String
) {
    /** 是否成功嵌进应用窗口：null = 尚未确定，true = 已嵌入，false = 独立窗口 */
    var embedded by mutableStateOf<Boolean?>(null)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var playing by mutableStateOf(true)
        private set
    var muted by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var statusText by mutableStateOf("正在启动在线预览…")
        private set

    /** seek 期间用来顶替黑屏的「最后一帧」（进程重启后原生窗口已销毁，由 Compose 画出来） */
    var frozenFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var window: WinDef.HWND? = null
    private var bounds: IntArray? = null
    private var startMs = 0L
    private var seenPlayback = false
    private var lastFailure: String? = null
    private var disposed = false

    fun start() = startAt(0L)

    fun restart() = startAt(startMs)

    /**
     * 拖动进度条：先抓下当前画面当「最后一帧」，再重启进程从目标位置起播。
     *
     * 抓帧必须在杀进程之前（[startAt] 一进来就 [stopProcess]，原生窗口随之销毁）；
     * 抓不到就沿用上一次的帧，保证加载期间不黑屏。
     */
    fun seekTo(ms: Long) {
        if (disposed) return
        val target = if (durationMs > 0) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)
        window?.let { FfplayWindowFx.captureWindow(it) }
            ?.let { frozenFrame = it.toComposeImageBitmap() }
        startAt(target)
    }

    private fun startAt(ms: Long) {
        if (disposed) return
        stopProcess()
        startMs = ms.coerceAtLeast(0L)
        embedded = null
        // durationMs 故意不清零：拖动/重试期间进度条要「不回退、不锁定」
        positionMs = startMs
        playing = true
        error = null
        seenPlayback = false
        lastFailure = null
        statusText = "正在启动在线预览…"

        val cmd = FfplayManager.buildCommand(exe, url, headers, title, startMs)
        val p = try {
            ProcessBuilder(cmd).start()
        } catch (t: Throwable) {
            // 不打印完整命令行：里面带 Cookie
            Log.e(FFPLAY_TAG, "ffplay 启动失败: ${t.message}")
            error = "无法启动 ffplay：${t.message ?: "未知错误"}"
            return
        }
        process = p
        FfplayManager.registerProcess(p)
        Thread({ readLog(p) }, "yunx-ffplay-log").apply { isDaemon = true }.start()
        scope.launch { watchExit(p) }
        scope.launch { embedWindow(p) }
    }

    private fun readLog(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    if (disposed || process !== p) return
                    val line = ANSI.replace(raw, "")
                    DURATION.find(line)?.let { m ->
                        val h = m.groupValues[1].toLongOrNull() ?: 0L
                        val min = m.groupValues[2].toLongOrNull() ?: 0L
                        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
                        durationMs = (h * 3600 + min * 60) * 1000 + (sec * 1000).toLong()
                    }
                    POSITION.find(line)?.let { m ->
                        // -ss 之后状态行给的是「媒体绝对位置」，可直接当进度用
                        m.groupValues[1].toDoubleOrNull()?.let { seconds ->
                            positionMs = (seconds * 1000).toLong()
                            if (!seenPlayback) {
                                seenPlayback = true
                                statusText = ""
                            }
                        }
                    }
                    if (FAILURE.containsMatchIn(line)) lastFailure = line.trim()
                }
            }
        } catch (t: Throwable) {
            Log.w(FFPLAY_TAG, "ffplay 日志读取结束: ${t.message}")
        }
    }

    /** 轮询等 ffplay 窗口出现 + 视频区尺寸就绪，然后把窗口嵌进应用窗口 */
    private suspend fun embedWindow(p: Process) {
        if (!FfplayManager.isWindows()) {
            if (process === p && !disposed) {
                embedded = false
                frozenFrame = null
                statusText = "FFplay 已在独立窗口播放"
            }
            return
        }
        val startedAt = System.currentTimeMillis()
        var hinted = false
        // 4K 直链要先建连接、探测流，ffplay 的窗口可能十几秒后才出现。
        // 不能一到 5s 就放弃嵌入：放弃后窗口留在了屏幕外，用户看到的就是
        // 「有声音、预览窗口黑屏、任务栏多一个 ffplay 图标」。
        while (!disposed && process === p) {
            val child = FfplayWindowFx.findWindowForProcess(p.pid())
            val parent = FfplayManager.hostWindowHandle(PreviewFullscreen.window)
            val b = bounds
            if (child != null && parent != null && b != null && b[2] > 0 && b[3] > 0) {
                window = child
                val ok = FfplayWindowFx.embed(child, parent, b[0], b[1], b[2], b[3])
                if (disposed || process !== p) return
                embedded = ok
                frozenFrame = null // 新窗口已就位，撤掉「最后一帧」遮罩
                if (ok) {
                    if (statusText.isNotEmpty()) statusText = ""
                    Log.d(
                        FFPLAY_TAG,
                        "embed: 成功 hwnd=$child pid=${p.pid()} 等待 ${System.currentTimeMillis() - startedAt}ms"
                    )
                } else {
                    // 窗口是创建在屏幕外的，嵌入失败必须搬回可见区
                    FfplayWindowFx.revealWindow(child)
                    statusText = "FFplay 已在独立窗口播放（无法嵌入应用窗口）"
                }
                return
            }
            val waited = System.currentTimeMillis() - startedAt
            if (!hinted && waited >= EMBED_TIMEOUT_MS) {
                hinted = true
                if (child == null) statusText = "正在等待播放窗口…"
                Log.d(FFPLAY_TAG, "embed: 已等待 ${EMBED_TIMEOUT_MS}ms 仍未拿到可嵌入的窗口 pid=${p.pid()}")
            }
            // 等了很久还没窗口：先按「独立窗口」提示（不再转圈），但**继续在后台等**。
            // 4K 直链偶发几十秒后才建窗，只要窗口一出现就会被嵌入，不必让用户重开预览。
            if (embedded == null && waited >= EMBED_GIVE_UP_MS) {
                embedded = false
                frozenFrame = null
                statusText = "FFplay 窗口出现较慢，仍在后台等待嵌入…"
                Log.w(FFPLAY_TAG, "embed: 超过 ${EMBED_GIVE_UP_MS}ms 仍无窗口（继续重试）pid=${p.pid()}")
            }
            // 进程已退出且始终没有窗口就没必要继续等了
            if (!p.isAlive && child == null) break
            delay(60)
        }
        if (disposed || process !== p) return
        if (embedded == true) return
        embedded = false
        frozenFrame = null
        // 一直没等到可用的宿主/尺寸：窗口可能已经出现在屏幕外，搬回可见区
        FfplayWindowFx.findWindowForProcess(p.pid())?.let { FfplayWindowFx.revealWindow(it) }
        statusText = "FFplay 已在独立窗口播放（未能及时嵌入）"
    }

    /** 视频区位置/尺寸变化 → 同步原生子窗口（坐标为宿主客户区坐标） */
    fun updateBounds(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val prev = bounds
        if (prev != null && prev[0] == x && prev[1] == y && prev[2] == w && prev[3] == h) return
        bounds = intArrayOf(x, y, w, h)
        if (embedded == true) window?.let { FfplayWindowFx.moveWindow(it, x, y, w, h) }
    }

    /** 暂停/继续：ffplay 无可靠的状态回读，乐观切换 + 转发 `p` */
    fun togglePlayPause() {
        playing = !playing
        sendKey(VK_P)
    }

    fun toggleMute() {
        muted = !muted
        sendKey(VK_M)
    }

    private fun sendKey(vk: Int) {
        val w = window ?: return
        FfplayWindowFx.sendKey(w, vk, keyUp = false)
        FfplayWindowFx.sendKey(w, vk, keyUp = true)
    }

    private suspend fun watchExit(p: Process) {
        val code = try {
            p.waitFor()
        } catch (t: Throwable) {
            return
        }
        if (process !== p) return
        process = null
        FfplayManager.unregisterProcess(p)
        playing = false
        if (disposed) return
        when {
            code == 0 -> {
                if (durationMs > 0) positionMs = durationMs
                statusText = "播放结束"
            }
            error == null && !seenPlayback -> error = lastFailure ?: "FFplay 未能开始播放（退出码 $code）"
        }
    }

    private fun stopProcess() {
        val p = process ?: return
        process = null
        window = null
        FfplayManager.unregisterProcess(p)
        runCatching { p.destroy() }
    }

    fun dispose() {
        disposed = true
        stopProcess()
        scope.cancel()
    }

    private companion object {
        // 日志混有 ANSI 转义序列，且可能出现在行首/行尾（如 "[2KInput #0,"），先清掉再解析
        val ANSI = Regex("\\x1b\\[[0-9;?]*[A-Za-z]")
        val DURATION = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")

        /** 状态行形如 `   1.02 M-V:  0.000 fd= ...`；`nan` 伪状态天然不匹配 */
        val POSITION = Regex("""(\d+\.\d+)\s+[AM]-V:""")

        val FAILURE = Regex(
            "(?i)(HTTP error|Server returned|Failed to open|Invalid data found|" +
                "No such file|Connection refused|403 Forbidden|401 Unauthorized|404 Not Found)"
        )

        const val EMBED_TIMEOUT_MS = 5000L

        /** 嵌入等待上限：大文件/慢直链下 ffplay 建窗很慢，最多等这么久才降级为独立窗口 */
        const val EMBED_GIVE_UP_MS = 45_000L
        const val VK_P = 0x50
        const val VK_M = 0x4D
    }
}

private const val FFPLAY_TAG = "YunX-FfplayPlayer"

/** 人类可读的字节数（设置页与预览页的下载进度共用） */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) {
        "${bytes} ${units[0]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[i])
    }
}
