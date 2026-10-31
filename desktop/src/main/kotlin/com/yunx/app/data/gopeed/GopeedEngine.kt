package com.yunx.app.data.gopeed

import com.yunx.app.AppContext
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 内置 Gopeed 下载引擎（**外部 exe 子进程** 版）。
 *
 * 与上游 Android 版的根本差异：上游把 gomobile 编译出的 `libgojni.so` 用 `System.load` 装进本进程、
 * 配 `apiEnable=false` 走进程内 `rest.Dispatch`；桌面没有 Windows 版内核库，改为把官方
 * `gopeed-web-<ver>-windows-amd64.zip` 里的 `gopeed.exe` 作为**子进程**拉起，走它的本地 HTTP API。
 * 因此 REST 契约（路径 / 请求体 / 响应信封 `{code,msg,data}` / 状态枚举）与上游完全一致。
 *
 * 进程约定：
 * - 只监听 `127.0.0.1`（随机空闲端口），**不设 `-p` 密码** ⇒ 服务端不启用 Web 鉴权、也无需 apiToken；
 * - 内核与存储（bolt）落在应用数据目录 `<dataDir>/gopeed/` 下，不污染安装目录；
 *   ★ 启动参数只认 gopeed 服务端真实存在的开关（`-A/-P/-u/-p/-T/-d/-w/-c`，见上游 `cmd/web/flags.go`）——
 *   传不存在的参数会让 gopeed 打印 usage 后立刻退出（引擎永远起不来），所以这里只传 `-A/-P/-d`；
 * - JDK 17 在 Windows 上默认以 `CREATE_NO_WINDOW` 创建子进程（仅显式继承 stdio 时才清除），
 *   所以不会冒出控制台黑窗；stdout/stderr 重定向到日志文件，避免管道写满卡死子进程。
 *
 * 线程约定：[start] / [stop] / [createTask] / [taskStatus] 等全部**阻塞**，必须在 IO 线程调用。
 */
object GopeedEngine {

    /** 引擎状态：未导入内核 / 已导入未运行 / 运行中 */
    enum class State { NOT_INSTALLED, INSTALLED, RUNNING }

    /** 计划任务状态：与 Gopeed `TaskRuntimeStatus.status` 取值一一对应 */
    object TaskStatus {
        const val READY = "ready"
        const val RUNNING = "running"
        const val WAIT = "wait"
        const val PAUSE = "pause"
        const val ERROR = "error"
        const val DONE = "done"
    }

    /** 任务状态快照 */
    data class TaskView(
        val id: String,
        val status: String,
        val downloaded: Long,
        val total: Long,
        val speed: Long
    )

    private const val TAG = "GopeedEngine"
    private const val DIR_NAME = "gopeed"
    private const val EXE_NAME = "gopeed.exe"
    private const val BIN_DIR_NAME = "bin"

    /** 引擎 HTTP 协议的默认 UA（与 gopeed 自带默认一致）：任务未自带 UA 时使用，避免空 UA 被 CDN 限速 */
    private const val DEFAULT_HTTP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    /** 启动后等待 HTTP API 就绪的上限（毫秒）；冷启动解压/建库一般 1-3 秒 */
    private const val READY_TIMEOUT_MS = 20_000L

    /** 停止时等待进程自行退出的上限（毫秒），超时强杀 */
    private const val STOP_TIMEOUT_MS = 3_000L

    private val _state = MutableStateFlow(State.NOT_INSTALLED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近一次失败原因（引擎/子进程原文），供引擎页原样展示便于排查 */
    @Volatile
    var lastError: String? = null
        private set

    /** 引擎 HTTP API 端口；未运行时为 0 */
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var process: Process? = null

    private var shutdownHook: Thread? = null

    /** 专用于 localhost 的客户端：**必须绕开应用代理**（否则用户配了系统/手动代理时本地请求会被代理走） */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // ---------------------------------------------------------------------------------------------
    // 内核文件
    // ---------------------------------------------------------------------------------------------

    /** 引擎数据根目录 `<dataDir>/gopeed` */
    fun engineDir(): File = File(AppContext.dataDir, DIR_NAME)

    /** 应用设置（读取「下载线程数 / 代理」用于下发引擎运行配置；Preferences 句柄复用一份） */
    private val settings: SettingsRepository by lazy { SettingsRepository() }

    /** 内核可执行文件 */
    fun exeFile(): File = File(File(engineDir(), BIN_DIR_NAME), EXE_NAME)

    /** 引擎存储目录（bolt 数据库） */
    private fun storageDir(): File = File(engineDir(), "storage")

    /** 引擎日志文件（子进程 stdout/stderr 重定向到这里） */
    private fun logFile(): File = File(engineDir(), "gopeed.log")

    private fun pidFile(): File = File(engineDir(), "engine.pid")

    fun isInstalled(): Boolean = exeFile().isFile

    /** 内核体积（引擎页展示用） */
    fun kernelSize(): Long = exeFile().takeIf { it.isFile }?.length() ?: 0L

    /**
     * 同步「是否已导入内核」这一事实到 [state]（只做一次 stat）。
     * 运行中不动；未运行时按 exe 是否存在写回 INSTALLED / NOT_INSTALLED。
     * 应用启动与引擎页进入时各调一次即可（否则进程内状态可能滞后于磁盘）。
     */
    fun syncInstalledState() {
        if (_state.value == State.RUNNING) return
        _state.value = if (isInstalled()) State.INSTALLED else State.NOT_INSTALLED
    }

    /**
     * 从内核压缩包 / 可执行文件导入内核，支持：
     * - `.zip`（官方 `gopeed-web-<ver>-windows-amd64.zip`）：在包内按名找 `gopeed.exe`（任意层级）；
     * - `.exe`：直接作为内核拷入。
     * 导入前会先停掉正在运行的引擎（否则 exe 被占用、写入失败）。
     * @return 安装后的内核文件
     */
    fun installFromArchive(archive: File): File {
        require(archive.isFile) { "内核文件不存在：${archive.absolutePath}" }
        // 运行中的老内核必须先停：Windows 上正在执行的 exe 无法被覆盖
        if (_state.value == State.RUNNING) stop()
        val target = exeFile()
        target.parentFile?.mkdirs()

        val name = archive.name.lowercase()
        if (name.endsWith(".zip")) {
            var found = false
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && entryName.substringAfterLast('/').equals(EXE_NAME, ignoreCase = true)) {
                        target.outputStream().buffered().use { out -> zip.copyTo(out) }
                        found = true
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            if (!found) {
                throw IllegalStateException("压缩包里没有找到 $EXE_NAME（请使用 Gopeed 官方 windows-amd64 包）")
            }
        } else {
            archive.copyTo(target, overwrite = true)
        }
        lastError = null
        _state.value = State.INSTALLED
        Log.i(TAG, "内核已导入：${target.absolutePath}（${kernelSize()} 字节）")
        return target
    }

    /** 删除内核（先停引擎）；存储目录保留，避免误删用户未完成任务的元数据 */
    fun uninstall() {
        if (_state.value == State.RUNNING) stop()
        runCatching { exeFile().delete() }
        _state.value = State.NOT_INSTALLED
        Log.i(TAG, "内核已删除")
    }

    // ---------------------------------------------------------------------------------------------
    // 进程生命周期
    // ---------------------------------------------------------------------------------------------

    /**
     * 启动引擎（幂等：已在运行直接返回端口）。
     * @param downloadDir 引擎默认下载目录（实际落盘位置由每个任务的 `opts.path` 决定）
     * @return HTTP API 端口
     */
    fun start(downloadDir: File): Int {
        if (_state.value == State.RUNNING) return port
        val exe = exeFile()
        if (!exe.isFile) throw IllegalStateException("还没有导入 Gopeed 内核（缺少 ${exe.absolutePath}）")
        lastError = null
        // 上一次应用被强杀可能留下孤儿进程：它仍占着 bolt 存储锁，必须先清掉
        killStaleProcess()
        runCatching { downloadDir.mkdirs() }
        val localPort = pickFreePort()
        val args = mutableListOf(
            exe.absolutePath,
            "-A", "127.0.0.1",
            "-P", localPort.toString(),
            "-d", storageDir().absolutePath
        )
        return try {
            val log = logFile().also { it.parentFile?.mkdirs() }
            val pb = ProcessBuilder(args)
                .directory(engineDir())
                // stdout/stderr 落地到文件：既不阻塞子进程，也留一份可排查的运行日志
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
            val p = pb.start()
            process = p
            port = localPort
            registerShutdownHook()
            if (!awaitReady()) {
                // 进程还活着 ⇒ 真的没在限时内响应；已经退出 ⇒ 多半是启动参数/内核不匹配，
                // 此时把日志路径一并给出，避免统一报成「启动超时」误导排查方向。
                val exitCode = runCatching { p.exitValue() }.getOrNull()
                runCatching { p.destroyForcibly() }
                process = null
                port = 0
                val detail = if (exitCode != null) {
                    "引擎进程已退出（退出码 $exitCode），未能提供 HTTP 服务；" +
                        "详见子进程日志 ${log.absolutePath}"
                } else {
                    "启动超时（${READY_TIMEOUT_MS / 1000}s 内未响应 /api/v1/info），详见 ${log.absolutePath}"
                }
                lastError = detail
                throw IllegalStateException(detail)
            }
            runCatching { pidFile().writeText(p.pid().toString()) }
            _state.value = State.RUNNING
            Log.i(TAG, "引擎已启动：pid=${p.pid()} port=$port 内核版本=${version() ?: "未知"}")
            port
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "引擎启动失败：${e.message}", e)
            throw e
        }
    }

    /** 停止引擎（幂等）：先正常结束，超时强杀；非 Windows 平台行为一致 */
    fun stop() {
        val p = process
        process = null
        pidFile().takeIf { it.isFile }?.delete()
        port = 0
        if (p != null) {
            runCatching {
                p.destroy()
                if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "引擎未在 ${STOP_TIMEOUT_MS}ms 内退出，强制结束")
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            }.onFailure { Log.w(TAG, "停止引擎异常：${it.message}") }
        }
        if (_state.value == State.RUNNING) _state.value = State.INSTALLED
        Log.i(TAG, "引擎已停止")
    }

    /** 重启（引擎页「重启引擎」）：先停再起 */
    fun restart(downloadDir: File): Int {
        stop()
        return start(downloadDir)
    }

    /**
     * 清掉上一次应用被强杀后残留的引擎进程（按 pid 文件精确匹配，不误伤用户自己装的 Gopeed）。
     * 匹配条件：pid 存活 且 该进程可执行文件路径就是本应用的内核 exe。
     */
    private fun killStaleProcess() {
        val pid = pidFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return
        pidFile().delete()
        if (process?.pid() == pid) return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (!handle.isAlive) return
        val cmd = runCatching { handle.info().command().orElse("") }.getOrDefault("")
        if (!cmd.equals(exeFile().absolutePath, ignoreCase = true)) {
            Log.w(TAG, "pid $pid 存活但不是本应用的内核（$cmd），跳过清理")
            return
        }
        Log.w(TAG, "清理残留引擎进程 pid=$pid")
        runCatching { handle.destroyForcibly() }
    }

    private fun registerShutdownHook() {
        if (shutdownHook != null) return
        val hook = Thread {
            runCatching { stop() }
        }
        shutdownHook = hook
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
    }

    /** 取一个本机空闲端口（先探测再释放；与引擎实际绑定之间存在极小竞态，失败会重启重试） */
    private fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

    /** 轮询 `/api/v1/info` 直到引擎就绪 */
    private fun awaitReady(): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            process?.let { if (!it.isAlive) return false }
            if (runCatching { infoJson() }.isSuccess) return true
            Thread.sleep(200L)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP API
    // ---------------------------------------------------------------------------------------------

    /** 引擎核心版本（`GET /api/v1/info` 的 `data.version`） */
    fun version(): String? =
        runCatching { infoJson().optString("version").takeIf { it.isNotBlank() } }.getOrNull()

    private fun infoJson(): JSONObject = get("/api/v1/info")

    /**
     * 新建下载任务。
     * @param saveDir 落盘目录（**真实文件系统绝对路径**，引擎写不了虚拟路径）
     * @param fileName 落盘文件名（引擎自己拼 `saveDir/fileName`）；BT（磁力/种子）传空串，由引擎按元数据命名
     * @param headers 请求头（网盘直链需要 UA/Referer/Cookie）
     * @param connections 分片并发连接数（<=0 时不写，用引擎默认）；BT 不吃这个参数
     * @param label 业务标签（这里放本地任务 id，便于引擎侧排查）
     * @param trackers BT 任务的 tracker 列表（写进 `req.extra.trackers`，与全局 customTrackers 双保险）
     * @return 引擎任务 ID
     */
    fun createTask(
        url: String,
        saveDir: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 0,
        label: String = "",
        trackers: List<String> = emptyList()
    ): String {
        val req = JSONObject().apply {
            put("url", url)
            // BT 的 req.extra 是 {trackers:[...]}，HTTP 的 req.extra 是 {header:{...}}：按需组装，互不影响
            if (headers.isNotEmpty() || trackers.isNotEmpty()) {
                put("extra", JSONObject().apply {
                    if (headers.isNotEmpty()) {
                        put("header", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
                    }
                    if (trackers.isNotEmpty()) {
                        put("trackers", JSONArray().apply { trackers.forEach { put(it) } })
                    }
                })
            }
            if (label.isNotBlank()) {
                put("labels", JSONObject().apply { put("yunxTaskId", label) })
            }
        }
        val opts = JSONObject().apply {
            put("path", saveDir)
            if (fileName.isNotBlank()) put("name", fileName)
            if (connections > 0) {
                put("extra", JSONObject().apply { put("connections", connections) })
            }
        }
        val body = JSONObject().apply {
            put("req", req)
            put("opts", opts)
        }
        // 注意：新建任务的 `data` 是**任务 ID 字符串**，不是对象（与查询类接口不同）
        val id = requestEnvelope("POST", "/api/v1/tasks", body.toString()).optString("data").trim()
        if (id.isBlank()) throw IllegalStateException("引擎未返回任务 ID")
        // 把实际下发的并发连接数打进日志，便于对照「界面显示的线程数」排查速度问题
        Log.i(TAG, "建任务：engineId=$id name=$fileName connections=${if (connections > 0) connections else "引擎默认"}")
        return id
    }

    /**
     * 查询任务状态（`GET /api/v1/tasks/{id}` → Task 对象）。
     *
     * ★ 绝不能再用上游移植过来的 `/api/v1/tasks/{id}/status`：gopeed 服务端**没有这个路由**
     *   （真实路由只有 `/{id}` 与 `/{id}/stats`，见上游 `pkg/rest/server.go`），调用必然 404，
     *   于是进度同步永远失败、界面一直看不到速度（看起来像引擎没启动）。
     *   改为读 Task 对象本身：
     *   - `status`：ready / running / wait / pause / error / done（见 `pkg/base/constants.go`）；
     *   - `progress.downloaded` / `progress.speed`（Task 内嵌的 Progress 结构）；
     *   - 总大小取 `meta.res.size`（Progress 里**没有** total）。
     */
    fun taskStatus(engineTaskId: String): TaskView {
        val data = get("/api/v1/tasks/$engineTaskId")
        val progress = data.optJSONObject("progress")
        val res = data.optJSONObject("meta")?.optJSONObject("res")
        return TaskView(
            id = data.optString("id").ifBlank { engineTaskId },
            status = data.optString("status"),
            downloaded = progress?.optLong("downloaded") ?: 0L,
            total = res?.optLong("size") ?: 0L,
            speed = progress?.optLong("speed") ?: 0L
        )
    }

    /**
     * 引擎任务的失败详情（`GET /api/v1/tasks/{id}`）。
     *
     * Gopeed 的 Task 对象里带 `error` 字段（失败时的原始原因），但它**只存在 REST 响应里**——
     * 子进程日志只有启动 banner，不记任务级错误。取不到 `error` 时兜底返回整份 JSON，
     * 保证日志里一定能看到引擎给出的原因（否则只剩「引擎下载失败」这种没信息量的统一文案）。
     */
    fun taskError(engineTaskId: String): String? = runCatching {
        val data = get("/api/v1/tasks/$engineTaskId")
        data.optString("error").trim().ifEmpty { data.optString("err").trim() }
            .ifEmpty { data.toString() }
    }.getOrNull()

    /**
     * 该任务**实际**的并发连接数（`GET /api/v1/tasks/{id}/stats` 的 `connections` 数组长度）。
     * 配置里传了 32 不等于引擎真的开了 32 条连接：界面用真实值显示，取不到返回 null（回退到配置值）。
     */
    fun taskConnections(engineTaskId: String): Int? = runCatching {
        get("/api/v1/tasks/$engineTaskId/stats").optJSONArray("connections")?.length()
    }.getOrNull()

    /**
     * P2P 任务的 peer 概览（BT 磁力/种子 / ed2k）。
     *
     * `GET /api/v1/tasks/{id}/stats` 的 `data` 是 `{snapshot, runtime}`：
     * - BT：runtime = `{totalPeers, activePeers, connectedSeeders, connectedLeechers, peers[]}`；
     * - ed2k：runtime = `{activePeers, totalPeers, peers[]}`（没有 seeds 概念，`seeds` 记 -1）；
     * - HTTP：没有 runtime（连接信息是 `snapshot.connections`），这里返回 null。
     *
     * 取不到的字段一律记 -1，UI 据此决定要不要显示（不显示比显示个假的 0 诚实）。
     */
    data class PeerInfo(
        /** 已连接的做种者数（BT 有，ed2k 无 → -1） */
        val seeds: Int,
        /** 当前活跃 peer 连接数 */
        val peers: Int,
        /** 已知 peer 总数（含未连接的） */
        val total: Int
    )

    fun taskPeerInfo(engineTaskId: String): PeerInfo? = runCatching {
        val data = get("/api/v1/tasks/$engineTaskId/stats")
        // 兼容：老版本内核可能把字段平铺在 data 上而非包在 runtime 里
        val rt = data.optJSONObject("runtime") ?: data
        val seeds = rt.optInt("connectedSeeders", -1)
        val peers = rt.optInt("activePeers", -1)
        val total = rt.optInt("totalPeers", -1)
        if (seeds < 0 && peers < 0 && total < 0) null else PeerInfo(seeds, peers, total)
    }.getOrNull()

    /**
     * 引擎里是否还有未结束的任务（ready / running / wait）。
     * 用于「切回内置下载器时要不要停引擎」这类判断：有任务在跑就别停，否则会把它们打断。
     */
    fun hasActiveTasks(): Boolean = runCatching {
        val tasks = getList("/api/v1/tasks")
        (0 until tasks.length()).any { i ->
            val status = tasks.optJSONObject(i)?.optString("status").orEmpty()
            status == TaskStatus.READY || status == TaskStatus.RUNNING || status == TaskStatus.WAIT
        }
    }.getOrDefault(false)

    fun pauseTask(engineTaskId: String) {
        requestEnvelope("PUT", "/api/v1/tasks/$engineTaskId/pause", null)
    }

    fun continueTask(engineTaskId: String) {
        requestEnvelope("PUT", "/api/v1/tasks/$engineTaskId/continue", null)
    }

    fun deleteTask(engineTaskId: String) {
        requestEnvelope("DELETE", "/api/v1/tasks/$engineTaskId", null)
    }

    /**
     * 引擎任务详情（`GET /api/v1/tasks/{id}` → `meta.res`）。
     *
     * `/status` 只给进度，**不给名字**；而磁力（BT）任务的真实名字是引擎解析出元数据之后才有的，
     * 落盘位置也随之下发。所以完成磁力任务时必须补读一次详情来修正保存路径与显示名。
     */
    data class TaskDetail(
        /** 资源名：磁力就是种子名（单文件种子=文件名，多文件种子=种子的顶级目录名） */
        val name: String,
        /** 文件个数（多文件种子 > 1） */
        val fileCount: Int,
        /** 落盘是**目录**（`<下载目录>/<种子名>/...`）还是单个文件（`<下载目录>/<种子名>`） */
        val folder: Boolean
    )

    fun taskDetail(id: String): TaskDetail {
        val data = get("/api/v1/tasks/$id")
        val res = data.optJSONObject("meta")?.optJSONObject("res")
        val files = res?.optJSONArray("files")
        val count = files?.length() ?: 0
        // 文件带 path ⇒ 种子有自己的根目录（BT 侧 FilePathMaker 返回「种子名/子路径」）；
        // 只有单个文件且 path 为空时，落盘才是「下载目录/文件名」这一个文件
        val hasSubPath = (0 until count).any {
            files?.optJSONObject(it)?.optString("path").orEmpty().isNotBlank()
        }
        return TaskDetail(
            name = res?.optString("name").orEmpty().ifBlank { data.optString("name") },
            fileCount = count,
            folder = hasSubPath || count > 1
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 全局配置（最大同时运行任务数等）
    // ---------------------------------------------------------------------------------------------

    /**
     * 读取引擎当前全局配置（`GET /api/v1/config` 的 `data` 对象）。
     * 包含 `downloadDir` / `maxRunning` / `proxy` / `protocolConfig` 等全部字段。
     */
    fun getConfig(): JSONObject = get("/api/v1/config")

    /**
     * 全量写入引擎全局配置（`PUT /api/v1/config`）。
     * 注意：Gopeed 的 `PutConfig` 是**整体替换**，调用方必须传完整配置对象
     * （通常先 [getConfig] 再改个别字段后回写，避免冲掉 downloadDir / proxy 等）。
     */
    fun putConfig(config: JSONObject) {
        requestEnvelope("PUT", "/api/v1/config", config.toString())
    }

    /**
     * 下发「引擎运行期由应用决定的配置」：并发上限 `maxRunning` + BT 不做种。
     *
     * ★ 为什么必须在启动后走 REST 写回：启动参数里的 `downloadConfig` **只在空库首次生效**——
     *   Gopeed 的 `Downloader.Setup()` 一旦读到 bolt 里存过的配置，就整个替换掉启动配置。
     *   导入内核后每次冷启动读到的都是库里那份旧值，启动参数里写什么都不算数。
     *
     * ★ 只改这两处、其余字段原样带回：GET 到的整份配置直接 PUT 回去，
     *   `downloadDir` / `proxy` / `trackers` 等仍是库里已有的值，不会被冲掉。
     *
     * 并发上限——引擎自己就有多任务调度：超出上限的新任务置为 `wait` 塞进队列，
     * 有任务结束时引擎自己补位。这里只负责把设置值下发下去（默认曾是 1，等于引擎永远单任务）。
     *
     * BT 不做种——Gopeed 的 bt 默认 `seedKeep=false, seedRatio=1.0, seedTime=7200`：
     * 下载完还会继续上传，直到分享率 1.0 或满 2 小时。桌面同样不希望下完还占带宽。
     * ★ 坑：`seedRatio=0 && seedTime=0 && seedKeep=false` **不是「关」**——三个停止条件都不成立
     *   ⇒ 循环永不退出，等于永远做种。真正能立刻停的是 `seedTime=1`（秒）。
     *
     * @param maxRunning 最大同时运行任务数，<1 时按 1 处理
     * @param wakeQueued 是否顺手唤醒在队列里等待的任务（调大上限时需要；引擎自己不会放行队列）
     * @return 实际下发的上限；引擎未运行时返回 null（下次启动会带上新值）
     */
    fun applyRuntimeConfig(maxRunning: Int, wakeQueued: Boolean = true): Int? {
        if (_state.value != State.RUNNING) return null
        val value = maxRunning.coerceAtLeast(1)
        val cfg = getConfig()
        cfg.put("maxRunning", value)
        val protocols = cfg.optJSONObject("protocolConfig") ?: JSONObject()
        // BT：不做种 + tracker 列表 + DHT 开关
        val bt = protocols.optJSONObject("bt") ?: JSONObject()
        bt.put("seedKeep", false)
        bt.put("seedRatio", 0)
        bt.put("seedTime", 1)
        // tracker 列表：内置优质默认列表（用户可在「下载引擎」页自定义）。
        // 公网磁力/种子在 DHT 受限（运营商封 UDP、无公网 IP）时全靠它找到 peer —— 缺了它就会一直「解析中」。
        val trackers = settings.resolvedBtTrackers()
        bt.put("customTrackers", JSONArray().apply { trackers.forEach { put(it) } })
        // DHT：默认开（disableDht=false）。关掉后只靠 tracker/PEX 找 peer。
        bt.put("disableDht", !settings.btDhtEnabled)
        protocols.put("bt", bt)
        // HTTP 全局连接数：对齐设置里的「下载线程数（通用）」。引擎自带的默认只有 16，
        // 且任务级 opts.extra.connections 才是最终生效值——这里只是兜底，保证任何任务都不会退化成低连接。
        val httpConnections = defaultHttpConnections()
        val http = protocols.optJSONObject("http") ?: JSONObject()
        http.put("connections", httpConnections)
        // 任务没自带 UA 时（如手动粘贴直链）gopeed 会用这里的值；留空会让 CDN 收到空 UA 而被限速
        if (http.optString("userAgent").isBlank()) {
            http.put("userAgent", DEFAULT_HTTP_UA)
        }
        protocols.put("http", http)
        cfg.put("protocolConfig", protocols)
        // 代理：与内置下载器保持一致（引擎默认不启用代理 ⇒ 用户配了代理时会被绕过，海外线路可能极慢）
        val proxy = proxyJson()
        cfg.put("proxy", proxy)
        putConfig(cfg)
        if (wakeQueued) wakeQueuedTasks(value)
        Log.i(
            TAG,
            "已下发引擎运行配置：maxRunning=$value http连接数=$httpConnections 代理=$proxy BT不做种=$bt"
        )
        return value
    }

    /** 全局 HTTP 连接数默认值：与「设置 → 下载线程数（通用）」一致（引擎自带默认仅 16） */
    private fun defaultHttpConnections(): Int =
        runCatching { settings.downloadThreadsFor(DownloadPlatform.GENERIC) }.getOrDefault(32)
            .coerceIn(1, 512)

    /**
     * 把应用里的代理设置映射成 gopeed 的 `DownloaderProxyConfig`：
     * - 直连：`enable=false`
     * - 系统代理：`enable=true, system=true`（gopeed 自己读系统代理，且原生支持 PAC）
     * - 手动：`enable=true, scheme=http, host=host:port`
     *
     * 为什么要同步：应用自身的 HTTP 客户端（含内置下载器）都跟随该设置，而引擎默认直连，
     * 用户配了代理时引擎会被绕过 —— 海外线路可能慢到不可用。两套下载器必须口径一致。
     */
    private fun proxyJson(): JSONObject {
        val json = JSONObject()
        when (settings.proxyMode) {
            SettingsRepository.PROXY_MODE_SYSTEM -> {
                json.put("enable", true)
                json.put("system", true)
            }
            SettingsRepository.PROXY_MODE_MANUAL -> {
                val host = settings.proxyHost.trim()
                val port = settings.proxyPort
                if (host.isNotBlank() && port in 1..65535) {
                    json.put("enable", true)
                    json.put("system", false)
                    json.put("scheme", "http")
                    // gopeed 的 Host 直接进 url.URL.Host ⇒ 必须带端口
                    json.put("host", "$host:$port")
                } else {
                    json.put("enable", false)
                }
            }
            else -> json.put("enable", false)
        }
        return json
    }

    /**
     * 影响「引擎运行配置」的设置签名（并发上限之外的部分：连接数、代理、BT tracker/DHT）。
     * 调用方把它与并发上限拼成缓存键：任一项在设置里改了都要重新下发，而不是只在并发数变化时。
     */
    fun settingsSignature(): String = runCatching {
        "${defaultHttpConnections()}|${settings.proxyMode}|${settings.proxyHost}|${settings.proxyPort}" +
            "|${settings.btDhtEnabled}|${settings.btTrackers}"
    }.getOrDefault("")

    /**
     * 按空位数唤醒引擎里 status=wait 的排队任务。
     *
     * ★ 为什么还要自己补位：引擎只在**有任务结束**时补一个空位（`notifyRunning` 每次只放行一个），
     *   把上限从 3 调到 5 时已排队的任务不会自己动，得由我们按空位数逐个唤醒。
     *   唤醒用 `PUT /api/v1/tasks/{id}/continue`：对 `wait` 任务等价于「上车」。
     *   注意 `Continue` 的 `needPauseCount = min(上限, 要继续的数量) - 空位数`，**只有没有空位时才 > 0**
     *   （那时它会去暂停一个正在跑的任务给新任务让路）——所以这里严格按「空位数」放行，
     *   绝不越过上限，也就绝不会把正在下载的任务挤下去。
     *
     * ★ 调小时不打断已经在跑的任务：它们继续跑完，只是不再补位（引擎的 PutConfig 不做重新平衡）。
     */
    private fun wakeQueuedTasks(maxRunning: Int) {
        val tasks = runCatching { getList("/api/v1/tasks") }.getOrNull() ?: return
        var running = 0
        val waiting = ArrayList<String>()
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            when (t.optString("status")) {
                TaskStatus.RUNNING -> running++
                TaskStatus.WAIT -> t.optString("id").takeIf { it.isNotBlank() }?.let { waiting.add(it) }
            }
        }
        var free = maxRunning - running
        var woken = 0
        for (id in waiting) {
            if (free <= 0) break
            runCatching { continueTask(id) }
                .onFailure { Log.w(TAG, "唤醒排队任务失败：engineId=$id ${it.message}") }
            free--
            woken++
        }
        Log.i(TAG, "并发上限已更新：maxRunning=$maxRunning 在跑=$running 排队=${waiting.size} 唤醒=$woken")
    }

    /** `GET` 列表类接口：`data` 是 JSON 数组（与返回对象的 [get] 区分） */
    private fun getList(path: String): JSONArray =
        requestEnvelope("GET", path, null).optJSONArray("data") ?: JSONArray()

    /** `GET`：返回信封里的 `data` 对象（查询类接口的 `data` 都是对象） */
    private fun get(path: String): JSONObject =
        requestEnvelope("GET", path, null).optJSONObject("data") ?: JSONObject()

    /**
     * 发一次请求并解析统一信封 `{code,msg,data}`，返回整个信封。
     * `code != 0` 或 HTTP 非 2xx 一律抛 [IllegalStateException]（消息取服务端 `msg`，便于直接展示）。
     * 调用方按需从信封里取 `data`（对象或字符串）。
     */
    private fun requestEnvelope(method: String, path: String, body: String?): JSONObject {
        val currentPort = port
        if (currentPort <= 0) throw IllegalStateException("引擎未运行")
        val url = "http://127.0.0.1:$currentPort$path"
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                val payload = (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
                if (method == "POST") builder.post(payload) else builder.put(payload)
            }
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) {
                if (!resp.isSuccessful) throw IllegalStateException("引擎返回 HTTP ${resp.code}")
                return JSONObject()
            }
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("引擎响应不是合法 JSON（HTTP ${resp.code}）：${text.take(200)}")
            }
            val code = json.optInt("code")
            if (code != 0) {
                val msg = json.optString("msg").takeIf { it.isNotBlank() } ?: "引擎调用失败（code=$code）"
                throw IllegalStateException(msg)
            }
            return json
        }
    }
}
