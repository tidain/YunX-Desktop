package com.yunx.app.data.preview

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.yunx.app.AppContext
import com.yunx.app.data.network.GitHubMirror
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.Log
import com.yunx.app.util.WindowsTitleBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * FFplay 在线预览管理器。
 *
 * 为什么用 ffplay 而不是 JavaFX `MediaView`：JavaFX 只能吃「已落盘/可随机访问」的单文件，
 * 边下边播要先把文件拉到本地（`Mp4PreviewSession` / `HlsPreviewSession`），起播慢、拖动重缓冲；
 * 而 ffplay（FFmpeg 套件）能直接读 http(s) 直链、原生支持 HLS、也能喂分轨 DASH，
 * 是真正的「边下边播」。
 *
 * 落地方式（用户拍板）：
 * - **只给本应用用**：优先使用应用私有目录 `<dataDir>/tools/ffmpeg/bin/ffplay.exe`，
 *   缺失时下载 ffmpeg 静态构建解包到这里，**绝不改动系统 PATH / 环境变量**；
 * - 检测顺序：私有目录 → 系统 PATH → 常见安装目录（本机若已装 ffmpeg 会命中 PATH 分支）；
 * - 窗口形态：用 Win32 `SetParent` 把 ffplay 窗口嵌进应用窗口（见 [FfplayWindowFx]），
 *   嵌入失败或非 Windows 时降级为独立窗口。
 */
object FfplayManager {

    private const val TAG = "YunX-Ffplay"

    private val EXE_NAME: String
        get() = if (isWindows()) "ffplay.exe" else "ffplay"

    /** DASH 合轨用到的可执行文件（与 ffplay 同包，一起解出来） */
    private val FFMPEG_EXE: String
        get() = if (isWindows()) "ffmpeg.exe" else "ffmpeg"

    /** ffplay 默认 UA 是 `Lavf/...`，很多平台的 CDN 会因此 403，缺省补一个 Chrome UA */
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/132.0.0.0 Safari/537.36"

    private const val DOWNLOAD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/132.0.0.0 Safari/537.36"

    /** 屏幕外的初始窗口位置（Windows 会把窗口夹到 -25607 附近，仍在屏幕外） */
    private const val OFFSCREEN_X = "-32000"
    private const val OFFSCREEN_Y = "-32000"

    /** GyanD/codexffmpeg 的 GitHub Release 精简版（固定版本，可走镜像加速） */
    private const val GITHUB_ZIP =
        "https://github.com/GyanD/codexffmpeg/releases/download/7.1/ffmpeg-7.1-essentials_build.zip"

    /** gyan.dev 官方发布页：始终指向当前 release 的精简版（无镜像可用，作最后兜底） */
    private const val GYAN_ZIP =
        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

    /** 并行分片连接数（Range 分片并发）；镜像不支持 Range 时自动回退单连接流式下载 */
    private const val DOWNLOAD_CONNECTIONS = 8

    /**
     * 候选下载地址（按序尝试）。
     *
     * ★ **只下精简版（essentials）**：约 50–90MB。完整版（git/full，200MB+）包体太大，
     *   不能让用户为此等、为此占盘 —— 预览与合轨只需要 `ffplay.exe` / `ffmpeg.exe`，精简版完全够用。
     *
     * 顺序：当前生效镜像（[GitHubMirror.activePrefix]，自动选最快或用户固定）→ 其余预选镜像
     * → GitHub 直连 → gyan.dev 官方源。
     */
    private fun candidates(): List<String> {
        val best = GitHubMirror.activePrefix()
        val mirrors = listOf(best) + GitHubMirror.PRESETS.filter { it != best }
        return (mirrors.map { it + GITHUB_ZIP } + GITHUB_ZIP + GYAN_ZIP).distinct()
    }

    /**
     * 在飞 OkHttp 调用的登记簿。
     *
     * OkHttp 的 `execute()` 是阻塞调用、不响应协程取消：用户点「取消下载」时若正卡在连接 / 读数据上，
     * 协程 `cancel()` 打不断它（表现为「点了取消没反应」）。把所有 Call 登记进来，取消时统一 `cancel()`，
     * 阻塞的 `execute()` 会立刻抛 IOException，下载随即停止。
     */
    private class CallRegistry {
        private val calls = Collections.synchronizedList(ArrayList<Call>())

        fun add(call: Call) {
            calls.add(call)
        }

        fun remove(call: Call) {
            calls.remove(call)
        }

        fun cancelAll() {
            val snapshot = synchronized(calls) { calls.toList() }
            snapshot.forEach { runCatching { it.cancel() } }
        }
    }

    /** 应用私有 ffmpeg 目录：<dataDir>/tools/ffmpeg（不污染系统） */
    val installDir: File get() = File(AppContext.dataDir, "tools/ffmpeg")

    /** 私有目录下的 ffplay 可执行文件 */
    val privateExe: File get() = File(installDir, "bin/$EXE_NAME")

    /** 私有目录下的 ffmpeg 可执行文件（DASH 合轨用） */
    private val privateFfmpeg: File get() = File(installDir, "bin/$FFMPEG_EXE")

    /** 常见安装目录（PATH 未命中时兜底） */
    private val commonDirs: List<File> = buildList {
        System.getenv("ProgramFiles")?.let { add(File(it, "ffmpeg/bin")) }
        System.getenv("ProgramFiles(x86)")?.let { add(File(it, "ffmpeg/bin")) }
        System.getenv("LOCALAPPDATA")?.let {
            add(File(it, "Microsoft/WinGet/Links"))
            add(File(it, "Programs/ffmpeg/bin"))
        }
        add(File("C:/ffmpeg/bin"))
        add(File("/opt/homebrew/bin"))
        add(File("/usr/local/bin"))
        add(File("/usr/bin"))
    }

    @Volatile
    private var cached: File? = null

    @Volatile
    private var checked = false

    @Volatile
    private var cachedFfmpeg: File? = null

    @Volatile
    private var checkedFfmpeg = false

    fun isWindows(): Boolean = WindowsTitleBar.isWindows

    /**
     * 定位可用的 ffplay（结果带缓存）。顺序：私有目录 → PATH → 常见安装目录。
     */
    @Synchronized
    fun locate(forceRefresh: Boolean = false): File? {
        if (!forceRefresh && checked) return cached
        val found = detectExe(EXE_NAME, privateExe)
        cached = found
        checked = true
        return found
    }

    /**
     * 定位可用的 ffmpeg（结果带缓存），供 DASH 合轨使用。顺序同 [locate]。
     */
    @Synchronized
    fun locateFfmpeg(forceRefresh: Boolean = false): File? {
        if (!forceRefresh && checkedFfmpeg) return cachedFfmpeg
        val found = detectExe(FFMPEG_EXE, privateFfmpeg)
        cachedFfmpeg = found
        checkedFfmpeg = true
        return found
    }

    /** 当前生效的 ffplay 路径（无则 null），供设置页展示 */
    fun currentPath(): String? = locate()?.absolutePath

    /** 私有目录里是否已有本应用下载的 FFmpeg（引擎管理页据此显示「卸载」） */
    fun hasPrivateInstall(): Boolean = privateExe.isFile

    /**
     * 卸载本应用下载到私有目录的 FFmpeg（`ffplay / ffmpeg / ffprobe` 一并删除）。
     * **只删私有副本**：系统 PATH / 常见安装目录里的 FFmpeg 不受影响。
     *
     * @return true=确实删除了私有目录；false=私有目录不存在，或删除失败（如 ffplay 正在运行占用文件）
     */
    @Synchronized
    fun uninstall(): Boolean {
        // 先失效缓存：无论删除成败，下次 locate() 都重新探测磁盘事实
        cached = null
        checked = false
        cachedFfmpeg = null
        checkedFfmpeg = false
        if (!installDir.exists()) return false
        val ok = installDir.deleteRecursively()
        if (ok) {
            // 卸载后恢复「首次提醒」：下次预览 / 合轨再缺引擎时会重新问一次是否下载
            SettingsRepository().ffmpegEngineAsked = false
        }
        Log.i(TAG, "卸载私有 FFmpeg: ${installDir.absolutePath} ok=$ok")
        return ok
    }

    private fun detectExe(name: String, privateFile: File): File? {
        privateFile.takeIf { it.isFile }?.let { return it }
        pathSearch(name)?.let { return it }
        commonDirs.forEach { dir ->
            File(dir, name).takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun pathSearch(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparatorChar)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile }
    }

    /**
     * 下载并解包 ffmpeg 到应用私有目录（不动系统 PATH）。
     * 依次尝试 [candidates]，全部失败返回最后一个错误；协程取消会原样抛出。
     */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<File> {
        if (!isWindows()) return Result.failure(IOException("仅支持 Windows"))
        return withContext(Dispatchers.IO) {
            // OkHttp 的 execute() 是阻塞的、不响应协程取消；用「看门狗」子协程在取消瞬间
            // 把所有在飞 Call 一并 cancel()，否则用户点「取消下载」会毫无反应。
            val registry = CallRegistry()
            val watchdog = launch {
                try {
                    awaitCancellation()
                } finally {
                    registry.cancelAll()
                }
            }
            try {
                val client = HttpClients.downloadClient()
                var lastError: Throwable? = null
                for (src in candidates()) {
                    try {
                        val exe = downloadFrom(client, registry, src, onProgress)
                        cached = exe
                        checked = true
                        // 同一次解包也带来了 ffmpeg.exe / ffprobe.exe，顺手刷新缓存
                        cachedFfmpeg = privateFfmpeg.takeIf { it.isFile }
                        checkedFfmpeg = true
                        Log.i(TAG, "ffmpeg 下载完成: ${exe.absolutePath}")
                        return@withContext Result.success(exe)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        // cancel 打断阻塞调用时抛的是 IOException("Canceled")，这里补一次取消检查
                        currentCoroutineContext().ensureActive()
                        lastError = t
                        Log.w(TAG, "从 $src 获取 ffmpeg 失败: ${t.message}")
                    }
                }
                Result.failure(lastError ?: IOException("ffmpeg 下载失败"))
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun downloadFrom(
        client: OkHttpClient,
        registry: CallRegistry,
        url: String,
        onProgress: (Long, Long) -> Unit
    ): File {
        val tmp = File(AppContext.cacheDir, "ffmpeg-download-${System.currentTimeMillis()}.zip")
        try {
            // 先试多连接 Range 并行（快得多）；该镜像不支持 / 分片失败时退回单连接流式下载
            val parallelOk = try {
                downloadParallel(client, registry, url, tmp, onProgress)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "并行分片失败，回退单连接: ${t.message}")
                false
            }
            if (!parallelOk) downloadStream(client, registry, url, tmp, onProgress)
            return extractPlayable(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * 多连接（[DOWNLOAD_CONNECTIONS] 路）Range 并行下载。
     *
     * @return true = 已下载完成；false = 镜像不支持 Range（交给调用方回退单连接流式下载）
     */
    private suspend fun downloadParallel(
        client: OkHttpClient,
        registry: CallRegistry,
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        // 一次 `Range: bytes=0-0` 探测同时拿到「是否支持分片」与「总大小」
        val total = probeTotal(client, registry, url) ?: return false
        val connections = minOf(DOWNLOAD_CONNECTIONS.toLong(), total).toInt().coerceAtLeast(1)
        // 预分配固定长度：各分片按绝对偏移写入，需要文件先有确定大小
        RandomAccessFile(target, "rw").use { it.setLength(total) }
        Log.i(TAG, "FFmpeg 并行下载：分片=$connections 总大小=$total url=$url")

        val downloaded = AtomicLong(0L)
        val reportLock = Any()
        var lastReportAt = 0L

        coroutineScope {
            val sliceLen = total / connections
            (0 until connections).map { index ->
                val start = index * sliceLen
                val end = if (index == connections - 1) total - 1 else start + sliceLen - 1
                async(Dispatchers.IO) {
                    downloadSlice(client, registry, url, target, start, end) { delta ->
                        val done = downloaded.addAndGet(delta)
                        val now = System.currentTimeMillis()
                        // 进度节流 300ms：多分片并发回调，不节流会把 UI 打爆
                        if (now - lastReportAt >= 300L) {
                            synchronized(reportLock) {
                                if (now - lastReportAt >= 300L) {
                                    lastReportAt = now
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        onProgress(total, total)
        return true
    }

    /** 探测镜像是否支持 Range 并取出总大小；返回 null 即表示不支持分片（或探测失败） */
    private fun probeTotal(client: OkHttpClient, registry: CallRegistry, url: String): Long? {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DOWNLOAD_UA)
                .header("Range", "bytes=0-0")
                .build()
        )
        registry.add(call)
        return try {
            call.execute().use { resp ->
                if (resp.code != 206) return@use null
                (resp.header("Content-Range") ?: return@use null)
                    .substringAfterLast('/').trim().toLongOrNull()?.takeIf { it > 0L }
            }
        } catch (t: Throwable) {
            null
        } finally {
            registry.remove(call)
        }
    }

    /** 单个 Range 分片：每片各开一个 fd 按偏移写入（RandomAccessFile 非线程安全，不能共享） */
    private suspend fun downloadSlice(
        client: OkHttpClient,
        registry: CallRegistry,
        url: String,
        target: File,
        start: Long,
        end: Long,
        onBytes: (Long) -> Unit
    ) {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DOWNLOAD_UA)
                .header("Range", "bytes=$start-$end")
                .build()
        )
        registry.add(call)
        try {
            call.execute().use { resp ->
                if (resp.code != 206) throw IOException("分片请求失败 HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("响应体为空")
                RandomAccessFile(target, "rw").use { raf ->
                    raf.seek(start)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var pos = start
                        while (pos <= end) {
                            // 用户点「取消」时立刻退出，不再白下
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            val allow = minOf(read.toLong(), end - pos + 1).toInt()
                            if (allow <= 0) break
                            raf.write(buffer, 0, allow)
                            pos += allow
                            onBytes(allow.toLong())
                        }
                        if (pos <= end) throw IOException("分片下载不完整（$start-$end）")
                    }
                }
            }
        } finally {
            registry.remove(call)
        }
    }

    /** 单连接流式下载（镜像不支持 Range 时的兜底） */
    private suspend fun downloadStream(
        client: OkHttpClient,
        registry: CallRegistry,
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val call = client.newCall(
            Request.Builder().url(url).header("User-Agent", DOWNLOAD_UA).build()
        )
        registry.add(call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("响应体为空")
                val total = body.contentLength()
                var read = 0L
                var lastReportAt = 0L
                target.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            // 进度节流 300ms，避免每 256KB 都回调一次
                            if (now - lastReportAt >= 300L || (total > 0 && read >= total)) {
                                lastReportAt = now
                                onProgress(read, total)
                            }
                        }
                    }
                }
            }
        } finally {
            registry.remove(call)
        }
    }

    /** 从 zip 中只提取 bin 目录下的 exe / dll 到 `installDir/bin`（静态构建只需解出这几个文件） */
    private fun extractPlayable(zip: File): File {
        val outBin = File(installDir, "bin").apply { mkdirs() }
        var found = false
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                val lower = name.lowercase(Locale.US)
                if (name.contains("/bin/") && (lower.endsWith(".exe") || lower.endsWith(".dll"))) {
                    val outFile = File(outBin, name.substringAfterLast('/'))
                    outFile.outputStream().buffered().use { out -> zis.copyTo(out) }
                    if (outFile.name.equals(EXE_NAME, ignoreCase = true)) found = true
                }
                zis.closeEntry()
            }
        }
        val exe = privateExe
        if (!found || !exe.isFile) throw IOException("压缩包中未找到 ffplay.exe")
        return exe
    }

    /**
     * 组装 ffplay 命令行。
     *
     * 关键选项：
     * - `-loglevel info -stats`：info 才能同时拿到 `Duration:` 行与状态行（进度解析用）；
     * - `-autoexit`：播完自动退出进程 → 上层显示「播放结束」；
     * - `-ss <秒>`：启动即定位（seek 走「重启 + -ss」，比模拟按键可靠）；
     * - `-user_agent` / `-referer` / `-cookies`：各平台直链所需的请求头，用独立选项避免 `-headers` 里塞 `\r\n`。
     */
    fun buildCommand(
        exe: File,
        url: String,
        headers: Map<String, String>,
        title: String,
        startMs: Long = 0L
    ): List<String> {
        val cmd = mutableListOf(
            exe.absolutePath,
            "-hide_banner",
            "-loglevel", "info",
            "-stats",
            "-autoexit",
            "-window_title", title.ifBlank { "云析 预览" }
        )
        // ffplay 的窗口是「首帧解码后才创建」的，创建时默认落在屏幕中间，
        // 等我们轮询到再 SetParent 搬进应用窗口，用户就会看到窗口先弹出来再"飞"进去。
        // 所以先把窗口创建在屏幕外，嵌入成功后再搬到位（嵌入失败会再搬回可见区）。
        if (isWindows()) {
            cmd += "-left"
            cmd += OFFSCREEN_X
            cmd += "-top"
            cmd += OFFSCREEN_Y
        }
        if (startMs > 0L) {
            cmd += "-ss"
            cmd += String.format(Locale.US, "%.3f", startMs / 1000.0)
        }
        val lookup = { key: String ->
            headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        }
        cmd += "-user_agent"
        cmd += lookup("User-Agent") ?: DEFAULT_UA
        lookup("Referer")?.takeIf { it.isNotBlank() }?.let {
            cmd += "-referer"
            cmd += it
        }
        lookup("Cookie")?.takeIf { it.isNotBlank() }?.let {
            cmd += "-cookies"
            cmd += it
        }
        // 其余自定义头（如 Origin）走 -headers，多行用 \r\n 分隔；
        // ProcessBuilder 不经 shell，转义字符可原样传递。
        val rest = headers.filterKeys {
            !it.equals("User-Agent", true) && !it.equals("Referer", true) &&
                !it.equals("Cookie", true) && !it.equals("Range", true)
        }
        if (rest.isNotEmpty()) {
            cmd += "-headers"
            cmd += rest.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
        }
        cmd += url
        return cmd
    }

    /** 可交给 ffplay 的直链；null 表示这条媒体无法在线预览 */
    fun urlFor(media: VideoMedia): String? = when {
        // 预览直链优先（哔哩哔哩 DASH：主直链是分轨 .m4s，解析时另给一份合好的 mp4）
        media.previewUrl.isNotBlank() -> media.previewUrl
        // HLS：ffplay 原生支持 m3u8，直接喂流地址
        media.isHls -> media.url
        // 分轨流（音视频分离）且无合轨直链：单路 ffplay 播不完整，不预览
        media.audioUrl.isNotBlank() -> null
        else -> media.url
    }

    /** ffplay 播放时的请求头：HLS 用 media.headers，其余同样用 media.headers */
    fun headersFor(media: VideoMedia): Map<String, String> = media.headers

    /**
     * 宿主窗口句柄：优先 JNA 从 AWT 窗口取原生指针，失败再枚举本进程可见顶层窗口取面积最大者。
     * 返回 null 表示拿不到 → 上层降级为独立窗口。
     */
    fun hostWindowHandle(awtWindow: java.awt.Window?): WinDef.HWND? {
        if (!isWindows()) return null
        if (awtWindow != null) {
            runCatching {
                val p: Pointer? = Native.getWindowPointer(awtWindow)
                val value = p?.let { Pointer.nativeValue(it) } ?: 0L
                if (value != 0L) return WinDef.HWND(Pointer(value))
            }
        }
        return FfplayWindowFx.largestWindowOfCurrentProcess()
    }

    // ---- 进程登记：JVM 退出时兜底杀掉 ffplay，避免留下孤儿窗口 ----

    private val liveProcesses = java.util.Collections.synchronizedSet(mutableSetOf<Process>())

    @Volatile
    private var hookInstalled = false

    internal fun registerProcess(process: Process) {
        liveProcesses.add(process)
        if (!hookInstalled) {
            synchronized(this) {
                if (!hookInstalled) {
                    runCatching {
                        Runtime.getRuntime().addShutdownHook(Thread {
                            liveProcesses.toList().forEach { runCatching { it.destroyForcibly() } }
                        })
                    }
                    hookInstalled = true
                }
            }
        }
    }

    internal fun unregisterProcess(process: Process) {
        liveProcesses.remove(process)
    }
}
