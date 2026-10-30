package com.yunx.app.data.download

import com.yunx.app.AppContext
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
import com.yunx.app.util.LogRedactor
import com.yunx.app.util.WindowsKeepAwake
import com.yunx.app.util.WindowsToastNotifier
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.preview.FfplayManager
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.ui.EngineInstallPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,        // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1,      // 分片（线程）数
    /**
     * 分片合并进度（0~100）：-1 表示不在合并阶段。
     * 合并只活在内存里、不写 DB —— 进程退出时合并本来就会中断，无需在库里留状态。
     */
    val mergePercent: Int = -1,
    /** 实验性多线程直写：当前处于“提交前逐片校验”阶段（复用 [mergePercent] 表示百分比） */
    val verifying: Boolean = false
)

private const val TAG = "YunX-DL"

/**
 * 单个「在飞分片」的调试快照（下载调试页用）。
 * 一路在飞分片就对应一个正在干活的 worker，所以这张表就是「每个线程此刻在干什么」。
 * [key] 形如 `m<片号>`（主池）/ `seg@<起点>`（弹性区）/ `retry@<起点>`（失败重试）。
 */
data class ChunkDebug(
    val key: String,
    val start: Long,
    val size: Long,
    /** 负责这一片的 worker 序号（0 起）：`worker #k`，方便看「哪个线程在干什么」 */
    val worker: Int,
    val received: Long,
    /** 看门狗上一次采样算出的瞬时速度（字节/秒） */
    val bps: Long,
    val elapsedMs: Long,
    /** 距上次收到字节已过多久：判断「连接被挂住」最直接的证据 */
    val idleMs: Long,
    /** 已被「慢连接抢占」换连接的次数 */
    val preemptCount: Int,
    /** 是否已置位抢占标志（下一次检查点会断开换连接） */
    val preempting: Boolean
)

/** 下载引擎的全局资源快照（下载调试页的「全局资源」面板用） */
data class DownloadResourceDebug(
    /** 全进程在飞分片配额（MAX_INFLIGHT_CHUNKS） */
    val inflightCap: Int,
    /** 当前已占用的在飞槽位 */
    val inflightUsed: Int,
    /** 正在跑的任务协程数 */
    val activeTasks: Int,
    val heapUsed: Long,
    val heapMax: Long,
    /** 分片 IO 线程池当前线程数 */
    val poolSize: Int,
    /** 分片 IO 线程池正在执行任务的线程数 */
    val poolActive: Int,
    /** 分片 IO 线程池等待队列长度（>0 说明有 worker 在排队等线程，常因并发开得比线程池大） */
    val poolQueue: Int
)

// plan.txt 解析（用于判断旧分片计划能否复用）：格式 `chunks=N total=N main=N`
private val PLAN_CHUNKS = Regex("chunks=(\\d+)")
private val PLAN_TOTAL = Regex("total=(\\d+)")
private val PLAN_MAIN = Regex("main=(\\d+)")

/** 单文件 Range 分片的安全并发上限。迅雷等 CDN 对单文件并发 Range 有阈值，
 *  超过约 8 个并发会把多余请求降级为 200 整文件（忽略 Range），
 *  进而触发整任务回退单流、速度暴跌。压在安全上限内，所有分片都能稳定拿到 206。 */
private const val RANGE_WORKERS_CAP = 8

/** DASH 分轨多连接下载：单个 Range 分块的最小字节数（太小的轨切块没有收益） */
private const val DASH_CHUNK_MIN = 2L * 1024 * 1024

/**
 * DASH 分轨的并发上限（视频轨 / 音轨各自）。
 *
 * 与 [RANGE_WORKERS_CAP] 分开：那条 8 的上限是为网盘 CDN 的 Range 阈值设置的；
 * 视频 CDN（B 站）没有这个问题，反而是「单连接限速」—— 实测 PCDN 节点单连接只有几十 KB/s，
 * 8 路合计只有几百 KB/s。这里放宽到 32（仍尊重用户按平台的线程数设置）。
 */
private const val DASH_WORKERS_CAP = 32

/** DASH 分轨最多切成多少块（只防极端超大文件把队列撑爆） */
private const val DASH_MAX_CHUNKS = 1024

/** DASH 单个分块的重试次数（`downloadRangeToChannel` 内部已重试，这里只兜最终失败） */
private const val DASH_CHUNK_ATTEMPTS = 2

/** DASH 两轨合计低于该字节数时不切块、单流顺序下载 */
private const val PARALLEL_MIN_BYTES = 4L * 1024 * 1024

/** 错峰建连上限（序号）：第 i 个分片首次请求前延迟 (min(i, STAGGER_CAP) * STAGGER_MS) */
private const val STAGGER_CAP = 8
private const val STAGGER_MS = 25L

/** 慢连接抢占的采样间隔：看门狗每隔这么久刷新一次每路瞬时速度，再据此判定是否换连接 */
private const val PREEMPT_TICK_MS = 5_000L

// ---------- ★ 慢连接抢占（治「收尾塌到 KB 级」，勿删）----------
// 网盘 CDN 是**按连接**限速的，且个别连接会落在慢节点上：实测多数连接 40~80KB/s，少数只有 3~7KB/s。
// 慢分片如果正好是收尾时唯一在跑的那几路，总速就塌到 KB 级。抢占 = 断开这条慢连接、换一条新连接续传。
// 开关、判定阈值与判定时长改由「设置 → 实验性功能」调节（见 DownloadTuning）：
// 默认 = 开 / 12KB/s / 15s，与改动前完全一致；关闭后看门狗不再抢占（等价于上游该功能之前的行为）。
/** 同一分片两次抢占之间的冷却期（换完连接要给新连接爬坡时间，避免反复重连） */
private const val PREEMPT_COOLDOWN_MS = 10_000L
/** 剩余不足这个数就不再折腾（换连接本身也有握手成本） */
private const val PREEMPT_MIN_REMAIN = 128 * 1024L
/** 单个分片最多被抢占几次：全局都慢时（如整站限速）避免无意义的重连风暴 */
private const val PREEMPT_MAX = 3
/** 每轮看门狗最多抢占几路，避免同一时刻大批连接同时重建 */
private const val PREEMPT_PER_TICK = 2
/** 收尾判定：在飞分片不超过这个数就认为「其余连接已无事可做」，放宽抢占门槛 */
private const val PREEMPT_ENDGAME_INFLIGHT = 3
/** 收尾时的最小存活时间：比常规门槛短得多，但也要避开刚建连的爬坡期 */
private const val PREEMPT_ENDGAME_MIN_AGE_MS = 3_000L

/** RANGE_IGNORED 容忍次数：CDN 偶发 200（限流中间态）前 N 次不触发整任务回退，继续领新片；超过才回退单流 */
private const val RANGE_IGNORED_TOLERANCE = 3

/** 重试区间（主池 part_i 或弹性区间 seg_{start}_{end}） */
private data class RetryRange(val start: Long, val end: Long, val file: File)

/**
 * 弹性区动态分片分配器（IDM 式）。
 * 核心矛盾：块太大会让尾部只剩少数几个大块 → 空闲线程空转（"最后一点特别慢"）；
 * 块太小会产生海量 Range 请求 → 拖慢快连接。故不再用固定块，改为**实时速度自适应 + 尾部收缩**：
 * - 按字节顺序发块（物理相邻），保持连接复用（不搞"中点劈分"，避免中后段掉速）；
 * - 起步块 = 主池 chunkSize（快连接不会一上来被切成碎片）；
 * - 稳定后块 ≈ 单 worker 下载 TARGET_SECONDS 秒的量：快则大块（省 Range 开销），慢则小块（充分并行摊平拖尾）；
 * - 尾部收缩：剩余字节 <= workers*块 时，把块压到 remaining/workers，让全部连接并行冲完最后一段，
 *   彻底消除"其他分片下完、只剩最后一个线程慢慢爬"的拖尾塌缩。
 */
private class ElasticAllocator(
    private val total: Long,
    private val elasticStart: Long,
    private val workers: Int,
    private val chunkSize: Long
) {
    private val lock = Any()
    private var nextStart = elasticStart

    /** 最近实测总速度（字节/秒），由外部实时注入；<=0 表示尚未探测到 */
    @Volatile
    var recentSpeedBps: Long = 0L

    /** 领取下一个弹性块（按字节顺序；块大小随速度与剩余量动态收缩） */
    fun take(): LongRange? = synchronized(lock) {
        if (nextStart >= total) return null
        val remaining = total - nextStart
        val base = baseBlockSize()
        // ★ 尾部收缩：剩余不足 workers 个整块时，均分到约 workers 份，让全部连接忙到最后；
        //   下限 MIN_TAIL_BLOCK（不碎成海量请求），上限 base（块不会越滚越大）。
        val w = workers.coerceAtLeast(1)
        val block = if (remaining <= w * base) {
            (remaining / w).coerceIn(MIN_TAIL_BLOCK, base)
        } else {
            base
        }
        val s = nextStart
        val e = minOf(s + block - 1, total - 1)
        nextStart = e + 1
        s..e
    }

    /** 速度自适应块大小：块 ≈ 单 worker 下载 TARGET_SECONDS 秒的量，夹在 [MIN, MAX]；未探测到速度时沿用主池 chunkSize */
    private fun baseBlockSize(): Long {
        val w = workers.coerceAtLeast(1)
        val perWorker = (recentSpeedBps / w).coerceAtLeast(0L)
        if (perWorker <= 0L) return chunkSize.coerceIn(MIN_ELASTIC_BLOCK, MAX_ELASTIC_BLOCK)
        return (perWorker * TARGET_SECONDS).coerceIn(MIN_ELASTIC_BLOCK, MAX_ELASTIC_BLOCK)
    }

    /** 断点续传：跳过已下载前缀（nextStart 只前进） */
    fun skipTo(start: Long) = synchronized(lock) {
        if (start > nextStart) nextStart = start
    }

    companion object {
        /** 常态块下限：慢连接稳定态每块至少 256KB（避免过多请求）；快连接起步也不低于此 */
        const val MIN_ELASTIC_BLOCK = 256 * 1024L
        /** 常态块上限：快连接单块封顶 4MB（过大块会降低动态性/连接复用友好度） */
        const val MAX_ELASTIC_BLOCK = 4 * 1024 * 1024L
        /** 尾部收缩下限：最后一段可细到 64KB，尽量榨干全部连接；再小则 Range 开销不划算 */
        const val MIN_TAIL_BLOCK = 64 * 1024L
        /** 自适应支点：每块 ≈ 单 worker 下载此秒数（快则大块、慢则小块） */
        const val TARGET_SECONDS = 5L
    }
}

/**
 * 一个在飞分片的采样状态（慢连接抢占的判定依据，**永久结构，勿删**）。
 *
 * 累加已收字节（每个读块一次 `AtomicLong.addAndGet`，开销可忽略）、记住起点/块大小，供看门狗协程
 * 每 PREEMPT_TICK_MS 刷新一次瞬时速度（[lastBps]）；[preempt] / [preemptCount] / [lastPreemptAtMs]
 * 决定该路是否换连接续传——删掉它们收尾长尾就会回来。
 */
private class InflightChunk(val start: Long, val size: Long, val worker: Int) {
    val bytes = AtomicLong(0L)
    val startedAtMs = System.currentTimeMillis()

    /** 上一次快照时的字节数与时刻（只由看门狗协程读写） */
    var lastBytes = 0L
    var lastAtMs = startedAtMs

    /** 本次快照算出的瞬时速度（只由看门狗协程读写） */
    var lastBps = 0L

    /** ★ 慢连接抢占标志：置位后 ChunkDownloader 断开当前连接、换新连接从已收字节续传（不丢数据） */
    val preempt = AtomicBoolean(false)

    /** 本分片已被抢占次数（上限 PREEMPT_MAX） */
    var preemptCount = 0

    /** 上次被抢占的时刻（冷却期用） */
    var lastPreemptAtMs = 0L

    /** 最后一次收到字节的时刻；「已 Ns 无数据」与「零字节卡死」判定都看它 */
    var lastActivityAtMs = startedAtMs

    val elapsedMs: Long get() = System.currentTimeMillis() - startedAtMs

    /** 距上次收到字节已过多久（连接被 CDN 挂住时这个值会一直涨） */
    val idleMs: Long get() = System.currentTimeMillis() - lastActivityAtMs
}

/**
 * 实验性多线程直写无法继续（服务器不支持 Range）：上层改用缓存分片模式重试。
 * 不算一次失败重试，避免把任务的 retryCount 消耗掉。
 */
private class DirectWriteFallbackException(message: String) : Exception(message)

/**
 * 下载任务管理器：
 * - 任务持久化（Room），状态流转 PENDING → DOWNLOADING → COMPLETED / PAUSED / FAILED；
 * - 分片多线程下载（每片一个协程，信号量限并发）；
 * - 断点续传：part 文件保留，暂停/重启后从已有大小继续；
 * - 完成后合并分片并保存到公共 Download 目录。
 */
class DownloadManager(
    private val dao: DownloadTaskDao,
    private val downloader: ChunkDownloader,
    /** 下载线程数提供者（按平台，可在设置中修改，动态生效），默认 32 */
    private val threadProvider: (String) -> Int = { 32 },
    /** 自定义下载保存目录提供者（SAF tree Uri，可空）；null 时保存到系统默认 Download */
    private val saveDirProvider: () -> String? = { null },
    /** 最大同时下载任务数提供者（默认 3）：限制后台并发任务，避免占满带宽/耗尽路由器连接 */
    private val concurrencyProvider: () -> Int = { 3 },
    /** 全局下载速度限制提供者（字节/秒；0 = 不限速） */
    private val speedLimitProvider: () -> Long = { 0L },
    /** 下载失败后自动重试次数提供者（默认 3，上限 10） */
    private val retryCountProvider: () -> Int = { 3 },
    /** 下载期间阻止系统休眠开关（Windows：SetThreadExecutionState，开启时任务运行中系统不睡眠） */
    private val keepAwakeProvider: () -> Boolean = { true },
    /** 通知栏显示下载速度开关（false 时仅显示通知，隐藏速度） */
    private val showSpeedProvider: () -> Boolean = { true },
    /** 实验性：线程数=1 时直接写目标目录（不占缓存盘） */
    private val directWriteSingleProvider: () -> Boolean = { false },
    /** 实验性：线程数>1 时预分配单个目标文件、按偏移并行直写（不占缓存盘） */
    private val directWriteMultiProvider: () -> Boolean = { false },
    /**
     * 是否启用外部 Gopeed 引擎（设置页「下载引擎」选择 Gopeed 时为 true）。
     * 只决定**新任务**的归属；实际还会再判一次平台（GitHub 除外）与内核是否已导入。
     */
    private val engineEnabledProvider: () -> Boolean = { false }
) {
    private val credentialCipher: CredentialCipher = FileCredentialCipher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前实际下载中的任务数（用于最大同时下载任务数限制） */
    private val activeDownloads = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 空间守卫：进行中任务的预期缓存体积（taskId → 文件总大小）。
     * 默认同时下载 3 个任务，若每个任务只检查「自己放不放得下」，3 个大文件会同时通过检查、
     * 合计把缓存盘写爆 —— 所以检查时要把它人预计还要写入的字节数一起算上。
     * 读写都由 [spaceGuardLock] 保护。
     */
    private val plannedCacheBytes = HashMap<Long, Long>()

    /** 空间守卫：正在合并写最终文件的任务（taskId → 文件大小），避免多个合流同时写满下载盘 */
    private val mergingDestBytes = HashMap<Long, Long>()
    private val spaceGuardLock = Any()

    /** 实验性多线程直写：服务器不支持 Range 时，本任务改走缓存分片，避免反复进入直写 */
    private val forceCacheMode = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private fun clearDirectState(id: Long) {
        forceCacheMode.remove(id)
    }

    private fun clearSpacePlan(id: Long) {
        synchronized(spaceGuardLock) {
            plannedCacheBytes.remove(id)
            mergingDestBytes.remove(id)
        }
    }

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒 */
    private val speedLimiter = SpeedLimiter()

    /**
     * ★ 全进程在飞分片信号量（跨任务共享）。
     *   这里钉的是「同时在飞的下载请求数」，而不是设置里的线程数：分片 IO 都是**同步阻塞**的
     *   `call.execute()`，设置写 512 也只是「允许 512 路同时下」，真正的闸门是这道信号量
     *   （容量 [MAX_INFLIGHT_CHUNKS]，按最大堆预算推导、封顶 512）。
     *   旧实现是「每任务一个容量 = 自身 worker 数的信号量」，永不阻塞 ⇒ 等于不限流，多个任务叠加即撑爆堆。
     *   主池 / 弹性区 / 失败重试三条路径统一过闸。★ 绝不手动 release。
     */
    private val inflightLimiter = Semaphore(MAX_INFLIGHT_CHUNKS)

    /**
     * 保存前存储权限检查（Android 9- 写公共 Download 需 WRITE_EXTERNAL_STORAGE 运行时授权）。
     * UI 层注入：无权限时动态申请并等待授权结果；已授权/Android 10+ 直接返回 true。
     * 授权后会自动继续保存（同一协程 await 授权结果再往下走）。
     */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /**
     * 运行中的任务 Job：value 为 CompletableDeferred，注册/移除全程由 jobsLock 保护，
     * 保证 start/pause/remove 之间无 TOCTOU 竞态（防止"暂停/删除瞬间任务继续跑"）。
     */
    private val activeJobs = ConcurrentHashMap<Long, CompletableDeferred<Job>>()
    private val jobsLock = Any()

    /** 前台服务计数：有任务在下载时保持前台（避免切后台限速/进程被杀） */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 前台通知进度节流（毫秒）：2 秒更新一次，避免频繁刷新系统通知 */
    private val notifyThrottleMs = 2000L
    private val lastNotifyTs = AtomicLong(0)

    /** 内存高压日志节流（毫秒时间戳，30s 一次） */
    @Volatile
    private var lastMemLogTs = 0L

    /** 合并阶段进度上报节流（毫秒）：分片合并回调很密，百分比不变时最多这么久报一次 */
    private val mergeReportIntervalMs = 300L

    /** Windows 通知中心聚合进度的每任务快照（id → 名称/总量/已完成） */
    private class ToastMeta(val name: String, val total: Long) {
        @Volatile var done = 0L
    }
    private val toastMeta = ConcurrentHashMap<Long, ToastMeta>()

    /** 更新 Windows 通知中心进度（聚合所有活动任务，1s 节流；非 Windows no-op） */
    private fun notifyProgress(id: Long, fileName: String, new: Long, total: Long) {
        if (total <= 0) return
        // ★ 内存观测（与进度回调同频，自带 30s 节流）：已用堆超 3/4 时打一行，并把「在飞分片
        //   已用/上限」一起带上，导出日志即可看到在飞上限是否真的生效（OOM 前的堆时间线）
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMemLogTs >= 30_000L) {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            if (used * 4 > rt.maxMemory() * 3) {
                lastMemLogTs = nowMs
                Log.w(TAG, "内存高压 used=${used / 1024 / 1024}MB max=${rt.maxMemory() / 1024 / 1024}MB " +
                    "tasks=${_stats.value.size} inflightCap=$MAX_INFLIGHT_CHUNKS " +
                    "inflightUsed=${MAX_INFLIGHT_CHUNKS - inflightLimiter.availablePermits}")
            }
        }
        val meta = toastMeta[id] ?: ToastMeta(fileName, total).also { toastMeta[id] = it }
        meta.done = new
        val doneSum = toastMeta.values.sumOf { it.done }
        val totalSum = toastMeta.values.sumOf { it.total }
        val first = toastMeta.values.firstOrNull()?.name ?: fileName
        val line1 = if (toastMeta.size > 1) "$first 等 ${toastMeta.size} 个任务" else first
        val statusLine = "${formatSize(doneSum)} / ${formatSize(totalSum)}"
        // 速度直接取下载页显示的那份（SpeedRecorder 的 250ms 窗口测速）之和，
        // 不再另算一套 EMA —— 否则两处数字永远对不上
        val speed = _stats.value.values.sumOf { it.speed }
        val speedText = if (showSpeedProvider() && speed > 0) "${formatSpeed(speed)} ↓" else ""
        WindowsToastNotifier.updateProgress(doneSum, totalSum, line1, statusLine, speedText, "正在下载")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return if (i >= 2) "%.2f %s".format(value, units[i]) else "%.0f %s".format(value, units[i])
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    /**
     * 刷新每路在飞分片的瞬时速度（抢占判定的依据，不产生日志）。
     * 每个采样周期调一次：`lastBps = 本周期新增字节 / 本周期耗时`。
     */
    private fun sampleInflightChunks(diag: Map<String, InflightChunk>) {
        val now = System.currentTimeMillis()
        for (d in diag.values) {
            val bytes = d.bytes.get()
            d.lastBps = (bytes - d.lastBytes).coerceAtLeast(0L) * 1000 / (now - d.lastAtMs).coerceAtLeast(1L)
            if (bytes > d.lastBytes) d.lastActivityAtMs = now
            d.lastBytes = bytes
            d.lastAtMs = now
        }
    }

    /**
     * ★ 慢连接抢占（永久逻辑）：把「跑得远低于同伴」的在飞分片换到新连接上续传。
     *
     * 判定阈值 = max(设置里的绝对下限, 本任务平均单连接速度 / 2)：
     * 用相对值是为了适配不同 CDN 的限速档次（夸克单连接几十 KB/s、迅雷更低），绝对下限兜住
     * 「收尾只剩一两路、平均值被自己拉低」的退化情况。命中后只把 [InflightChunk.preempt] 置位，
     * 由 ChunkDownloader 断开连接并从已收字节续传——不丢数据、不退避、不改变对外结果。
     *
     * 开关 / 阈值 / 判定时长均取自 [DownloadTuning]（默认 开 / 12KB/s / 15s，与改动前一致）；
     * 关闭后本方法直接返回，不再做抢占。
     */
    private fun preemptSlowChunks(
        id: Long,
        downloaded: Long,
        elapsedMs: Long,
        workers: Int,
        diag: Map<String, InflightChunk>
    ) {
        if (!DownloadTuning.preemptEnabled) return
        if (diag.isEmpty()) return
        val avgPerConn = if (elapsedMs > 0 && workers > 0) downloaded * 1000 / elapsedMs / workers else 0L
        val floor = DownloadTuning.preemptFloorBps(avgPerConn)
        val now = System.currentTimeMillis()
        var taken = 0
        for ((chunkKey, d) in diag.entries.sortedBy { it.value.lastBps }) {
            if (taken >= PREEMPT_PER_TICK) break
            if (d.preemptCount >= PREEMPT_MAX) continue
            if (now - d.lastPreemptAtMs < PREEMPT_COOLDOWN_MS) continue
            // 收尾（在飞 ≤ PREEMPT_ENDGAME_INFLIGHT）时放宽「跑够久」和「剩余够多」两条门槛
            if (diag.size <= PREEMPT_ENDGAME_INFLIGHT) {
                if (d.elapsedMs < PREEMPT_ENDGAME_MIN_AGE_MS) continue
            } else {
                if (d.elapsedMs < DownloadTuning.preemptMinAgeMs) continue
                if (d.size - d.bytes.get() < PREEMPT_MIN_REMAIN) continue
            }
            if (d.lastBps >= floor) continue
            d.preemptCount++
            d.lastPreemptAtMs = now
            d.preempt.set(true)
            // ★ 必须主动掐断在飞连接：只置标志位时，连接被 CDN 挂住（read 阻塞）就永远轮不到检查标志，
            //   日志里就会看到「标了卡死 / 记了抢占，但那条连接一直不换」
            downloader.cancelChunkCall(id, d.start.toString())
            taken++
            Log.w(TAG, "runTask: id=$id 抢占慢连接 分片=$chunkKey(worker#${d.worker}) 起点=${d.start} 块=${diagSize(d.size)} 已收=${diagSize(d.bytes.get())} " +
                "瞬时=${diagSpeed(d.lastBps)} 阈值=${diagSpeed(floor)} 第${d.preemptCount}/$PREEMPT_MAX 次（换连接续传）")
            DownloadDebugLog.record(
                id, "自动重连",
                "分片=$chunkKey worker#${d.worker} 起点=${d.start} 块=${diagSize(d.size)} 已收=${diagSize(d.bytes.get())} " +
                    "瞬时=${diagSpeed(d.lastBps)} 阈值=${diagSpeed(floor)} 已跑=${d.elapsedMs / 1000}s " +
                    "无数据=${d.idleMs / 1000}s " +
                    "第${d.preemptCount}/$PREEMPT_MAX 次 → 已掐断当前连接、换新连接从已收字节续传"
            )
        }
    }

    /** 字节数转可读文本（抢占日志用） */
    private fun diagSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2fGB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    /**
     * 速度转可读文本（调试日志用）：与 [formatSpeed] 的区别是**取不到就写「未知」而不是空串**——
     * 看门狗刚判定慢连接时 lastBps 可能还是 0，留空会让日志出现 `瞬时=` 这种没有值的字段。
     */
    private fun diagSpeed(bytesPerSec: Long): String =
        if (bytesPerSec > 0) formatSpeed(bytesPerSec) else "未知"

    /**
     * 进度落盘节流：多 worker 并发回调下，每 progressPersistIntervalMs 最多写一次 DB。
     * - force / (total>0 且 new>=total)：完成时强制写，确保最终进度准确；
     * - total<=0（大小未知）时仅按时间节流；
     * - 用 lastAt 的 CAS 保证并发下同一任务只有一个回调写库（避免多线程重复 UPDATE）。
     */
    private suspend fun persistProgressIfDue(
        id: Long,
        new: Long,
        total: Long,
        force: Boolean,
        lastAt: AtomicLong
    ) {
        val now = System.currentTimeMillis()
        val last = lastAt.get()
        if (force || (total > 0 && new >= total) || now - last >= progressPersistIntervalMs) {
            if (lastAt.compareAndSet(last, now)) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
            }
        }
    }

    /** 完成任务并写入平均速度（字节/秒）：avg = total / 本次运行耗时 */
    private suspend fun completeWithAvg(id: Long, savedPath: String, total: Long) {
        val start = taskStartTimes.remove(id) ?: 0L
        val elapsedSec = ((System.currentTimeMillis() - start) / 1000.0).coerceAtLeast(1.0)
        val avg = if (total > 0 && elapsedSec > 0) (total / elapsedSec).toLong() else 0L
        DownloadDebugLog.record(id, "完成", "总大小=$total 本段耗时=${elapsedSec.toInt()}s 平均速度=$avg B/s 保存=$savedPath")
        DiagnosticLog.dbOp("download_task", "complete", rowsOf = { -1 }) {
            dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, avg)
        }
    }

    /** 每个任务一把互斥锁：暂停后立即恢复时避免新旧协程并发写分片 */
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    /** 任务请求头（Cookie/UA），暂停后恢复仍需使用 */
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /**
     * DASH 分轨任务的**音轨**请求头（音轨可能与视频轨属于不同账号，Cookie 不同）。
     * 与 [taskHeaders] 同寿命：入队写入、删除任务/被拦下时清理；进程重启后从
     * `download_task.mergeAudioHeadersJson`（加密）解回，见 [loadPersistedMergeAudioHeaders]。
     */
    private val taskMergeAudioHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /**
     * 单文件多源分片（实验性 C2）的备选源：同一文件其他账号的直链 + 各自请求头。
     * 与 [taskHeaders] 同寿命（入队写入、任务终态清理），重启后从 download_task 里恢复。
     */
    private val taskAltSources = ConcurrentHashMap<Long, List<DownloadSource>>()

    /** 已知文件大小（API 返回，避免探测失败）；-1 表示未知 */
    private val taskSizes = ConcurrentHashMap<Long, Long>()

    /** 镜像主 URL 的回退直连（仅 GitHub 等显式传入）；主 URL 探测失败时整任务切到此 URL 重下 */
    private val taskFallbackUrls = ConcurrentHashMap<Long, String>()

    /** 任务开始时间（毫秒）：完成时计算平均速度用（暂停/恢复会重置，表示最近一次运行段均值） */
    private val taskStartTimes = ConcurrentHashMap<Long, Long>()

    /** 任务下载完成后的清理回调（如删除网盘临时转存文件；下载成功后才触发） */
    private val taskCallbacks = ConcurrentHashMap<Long, suspend () -> Unit>()

    /** 实时下载统计（速度/剩余时间/线程数） */
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /**
     * 各任务当前的在飞分片表（taskId → 该任务 runTask 里的在飞表）。
     *
     * ★ 只为「下载调试」按需读取，不参与任何下载逻辑：`runTask` 建表时登记、`start` 的 finally 里移除。
     *   用拉取式快照（[chunkDebugSnapshot]）而不是定时推送，是为了让没打开调试页时**零额外开销**。
     */
    private val activeChunkMaps = ConcurrentHashMap<Long, ConcurrentHashMap<String, InflightChunk>>()

    /**
     * 分片级校验：taskId → 每个分片**下载时**算出的 SHA-256（文件名 → 十六进制）。
     * 合并时逐片重算比对（[finishDownload]），内容对不上就拒绝保存损坏文件。
     * 只活在内存里、随任务结束清理；断点续传前缀由 [newPartDigest] 预先喂好，不额外读整片。
     */
    private val taskPartDigests = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()

    /**
     * 为某个分片准备分片级校验摘要：先把**断点续传已有的前缀**喂进去，之后由
     * [ChunkDownloader.downloadChunk] 在写入时增量更新（全程只多读一次前缀，不额外读整个分片）。
     * ★ 前缀读取失败时返回 null（这一片放弃校验）：校验只做"额外保险"，绝不能把正常下载弄挂。
     */
    private fun newPartDigest(partFile: File): MessageDigest? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        if (partFile.length() > 0) {
            FileInputStream(partFile).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = fis.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
        }
        md
    }.onFailure {
        Log.w(TAG, "分片摘要前缀读取失败（该片跳过校验）：${partFile.name} ${it.message}")
    }.getOrNull()

    /** 下载调试：某任务当前每个在飞分片（= 每个线程正在干什么）；无在飞分片时返回空表 */
    fun chunkDebugSnapshot(taskId: Long): List<ChunkDebug> {
        val map = activeChunkMaps[taskId] ?: return emptyList()
        return map.map { (key, d) ->
            ChunkDebug(
                key = key,
                start = d.start,
                size = d.size,
                worker = d.worker,
                received = d.bytes.get(),
                bps = d.lastBps,
                elapsedMs = d.elapsedMs,
                idleMs = d.idleMs,
                preemptCount = d.preemptCount,
                preempting = d.preempt.get()
            )
        }.sortedBy { it.start }
    }

    /** 下载调试：手动把某一路标记为「慢连接、换连接续传」（调试页的「强制重连」） */
    fun forcePreemptChunk(taskId: Long, key: String): Boolean {
        val d = activeChunkMaps[taskId]?.get(key) ?: return false
        DownloadDebugLog.record(
            taskId, "手动重连",
            "key=$key 起点=${d.start} 已收=${diagSize(d.bytes.get())}/${diagSize(d.size)} " +
                "瞬时=${diagSpeed(d.lastBps)} 已跑=${d.elapsedMs / 1000}s 无数据=${d.idleMs / 1000}s " +
                "已抢占${d.preemptCount}/$PREEMPT_MAX 次 → 已掐断当前连接、换新连接从已收字节续传"
        )
        d.preempt.set(true)
        // ★ 必须真的掐断在飞连接：只置标志位时，连接被 CDN 挂住（read 阻塞）就永远轮不到检查标志，
        //   界面表现就是「点了重连没反应」
        downloader.cancelChunkCall(taskId, d.start.toString())
        return true
    }

    /** 下载调试：全局资源快照（在飞配额占用 / 活跃任务数 / 堆内存 / 分片 IO 线程池） */
    fun resourceDebugSnapshot(): DownloadResourceDebug {
        val rt = Runtime.getRuntime()
        val pool = chunkIoExecutor
        return DownloadResourceDebug(
            inflightCap = MAX_INFLIGHT_CHUNKS,
            inflightUsed = MAX_INFLIGHT_CHUNKS - inflightLimiter.availablePermits,
            activeTasks = activeJobs.size,
            heapUsed = rt.totalMemory() - rt.freeMemory(),
            heapMax = rt.maxMemory(),
            poolSize = pool.poolSize,
            poolActive = pool.activeCount,
            poolQueue = pool.queue.size
        )
    }

    /** 进度落盘节流（毫秒）：updateProgress 写库会触发全表 Flow 重发 → 主线程全列表重组；
     *  按字节（256KB）节流时高速下载每秒写库几十次，主线程重组洪峰 → ANR。
     *  改为按时间节流落盘，UI 进度由内存 _stats 高频展示、DB 低频持久化（断点续传最多丢几百 ms 进度）。 */
    private val progressPersistIntervalMs = 500L

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /**
     * 当前解析中的分享链接；解析开始时由 ResolveViewModel 写入，任务入队时若调用方未显式
     * 传入 shareUrl 则用此值兜底填充 task.shareUrl，供「复制分享链接」右键菜单使用。
     */
    @Volatile
    var currentShareUrl: String = ""

    /** 入队并立即开始下载 */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        /** 已知文件大小（字节）；-1 表示未知，需探测 */
        size: Long = -1L,
        /** 下载来源平台标识（按平台应用下载线程数设置）；通用/手动添加传空串 */
        platform: String = "",
        /** 分享链接（用于右键菜单「复制分享链接」）；空串时用 currentShareUrl 兜底 */
        shareUrl: String = "",
        /** 镜像主 URL 不可达时的回退直连（默认空=不回退）；仅 GitHub 等镜像下载传入 */
        fallbackUrl: String = "",
        /**
         * 单文件多源分片（实验性 C2）的备选源（同一文件其他账号的直链 + 各自请求头）。
         * 空 = 单源，行为与未开启该功能时完全一致；只对内置分片下载器生效，Gopeed 引擎任务忽略它。
         */
        altSources: List<DownloadSource> = emptyList(),
        /** 本任务单独的保存目录（绝对路径）；空串 = 跟随「设置 → 储存目录」的全局下载目录。
         *  单次下载「下载到…」用它，避免为一两个文件反复改全局设置。 */
        saveDir: String = "",
        /**
         * DASH 分轨（哔哩哔哩高清：音视频分离）的**音轨**地址；空串 = 普通单文件下载。
         * 非空时 runTask 走「双轨下载 + 合轨」，见 [dashMergeDownload]。
         */
        mergeAudioUrl: String = "",
        /**
         * 音轨请求头（可能和视频轨属于不同账号，Cookie 不一定相同）。
         * 落库前按 `download.mergeAudioHeaders` 加密（与 requestHeadersJson 同一套凭据加密）。
         */
        mergeAudioHeaders: Map<String, String> = emptyMap(),
        /** 动图资源地址（Motion Photo 合成用）；空串 = 普通静图，见 [motionPhotoDownload] */
        motionUrl: String = "",
        /** 本任务的下载线程数覆盖（浏览器插件按用户弹窗选择传入）；0 = 跟随平台的线程数设置 */
        threads: Int = 0,
        /** 下载成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发。
         * 注意：必须是最后一个参数（调用点有大量尾随 lambda 用法，放它之后会编译失败）。 */
        onComplete: suspend () -> Unit = {}
    ): Long {
        // 磁力链接只能交给 Gopeed 引擎（见 magnetBlockReason）；平台标识同时落库，便于事后区分
        val isMagnet = MagnetLink.isMagnet(url)
        val taskPlatform = if (isMagnet) DownloadPlatform.MAGNET else platform
        // 文件名兜底：空白时从 URL 推导，避免保存时变成时间戳。
        // 磁力链接走 dn= 里自带的显示名（元数据到手后再换成真正的种子名，见 completeEngineTask）
        val safeName = fileName.ifBlank {
            if (isMagnet) {
                MagnetLink.displayName(url)
            } else {
                url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "download_${System.currentTimeMillis()}" }
            }
        }
        val effectiveShareUrl = shareUrl.ifBlank { currentShareUrl }
        Log.d(TAG, "enqueue: origin=${LogRedactor.url(url)} fileName=$safeName headers=${headers.keys} size=$size shareUrl=$effectiveShareUrl " +
            "dashAudio=${mergeAudioUrl.isNotBlank()} motion=${motionUrl.isNotBlank()}")
        val id = try {
            DiagnosticLog.dbOp("download_task", "insert", rowsOf = { 1 }) {
                dao.insert(
                    DownloadTaskEntity(
                        url = url,
                        fileName = safeName,
                        requestHeadersJson = encodeHeaders(headers),
                        altSourcesJson = encodeAltSources(altSources),
                        platform = taskPlatform,
                        shareUrl = effectiveShareUrl,
                        saveDir = saveDir.trim(),
                        mergeAudioUrl = mergeAudioUrl.trim(),
                        // 音轨头里同样有 Cookie：与 requestHeadersJson 一样加密落库（空表存空串）
                        mergeAudioHeadersJson = if (mergeAudioHeaders.isEmpty()) {
                            ""
                        } else {
                            encodeHeaders(mergeAudioHeaders, "download.mergeAudioHeaders")
                        },
                        motionUrl = motionUrl.trim(),
                        threads = threads.coerceAtLeast(0)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "enqueue: dao.insert FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        Log.d(TAG, "enqueue: dao.insert returned id=$id, calling start()")
        // 诊断日志：任务入队（下载模块记体积/平台，操作模块记一笔「用户加了任务」）
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "task_enqueue", taskId = id, status = "pending",
            size = size.takeIf { it > 0 },
            summary = "platform=$taskPlatform name=$safeName url=${LogRedactor.url(url)}"
        )
        DiagnosticLog.event(
            DiagnosticLog.OPERATION, "download_enqueue",
            "id=$id platform=$taskPlatform size=$size name=$safeName"
        )
        DownloadDebugLog.record(
            id, "入队",
            "平台=${taskPlatform.ifBlank { "-" }} 文件名=$safeName " +
                "大小=${if (size > 0) size else "未知"} 直链=${LogRedactor.url(url)}"
        )
        // 保存请求头（Cookie/UA），暂停后恢复仍需携带
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        // DASH 分轨的音轨头与视频轨分开保存（可能属于不同账号），同样用于暂停后恢复
        if (mergeAudioHeaders.isNotEmpty()) taskMergeAudioHeaders[id] = mergeAudioHeaders
        // 备选源（多源分片）同样留在内存里，恢复/续传时不必回查数据库
        if (altSources.isNotEmpty()) taskAltSources[id] = altSources
        if (size > 0) taskSizes[id] = size
        if (fallbackUrl.isNotBlank()) taskFallbackUrls[id] = fallbackUrl
        taskCallbacks[id] = onComplete
        // 磁力但引擎不可用：立刻落一条**带原因的失败任务**并结束。若照常走内置下载器，
        // 它会把 magnet: 当普通 URL 发 HTTP 请求，最后抛一个和「该去导入内核」毫无关系的协议错误。
        val blocked = magnetBlockReason(isMagnet)
        if (blocked != null) {
            Log.w(TAG, "磁力任务被拦下：id=$id 原因=$blocked")
            DownloadDebugLog.record(id, "拦截失败", blocked)
            taskCallbacks.remove(id)
            // 不会有下载协程来消费这几份内存数据了，直接清掉
            taskHeaders.remove(id)
            taskMergeAudioHeaders.remove(id)
            taskAltSources.remove(id)
            taskSizes.remove(id)
            taskFallbackUrls.remove(id)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, blocked)
            return id
        }
        // 引擎分流：选了 Gopeed 且内核已导入时由外部引擎执行（GitHub 除外，见 shouldUseEngine）。
        // ★ DASH 合轨 / 动图合成只能在应用内做：引擎只会下主 URL，不会拉音轨、不会合成，
        //   所以这两类任务强制走内置下载器。
        val needsLocalPipeline = mergeAudioUrl.isNotBlank() || motionUrl.isNotBlank()
        if (!needsLocalPipeline && shouldUseEngine(taskPlatform)) {
            startViaEngine(id, url, safeName, headers, taskPlatform, saveDir)
        } else {
            start(id, headers)
        }
        return id
    }

    /**
     * 磁力链接是否可下：返回 null = 可以（交给引擎），否则是给用户看的拦截原因。
     *
     * 磁力（BT）只有 Gopeed 内核里有实现（内核注册了 hls/http/bt/ed2k 四个协议），内置分片下载器
     * 是纯 HTTP Range 实现，喂它 magnet: 只会得到没意义的报错 —— 所以这里宁可拦住并说清楚要做什么。
     */
    private fun magnetBlockReason(isMagnet: Boolean): String? {
        if (!isMagnet) return null
        if (!GopeedEngine.isInstalled()) {
            return "磁力下载需要先在「设置 → 下载 → 下载引擎」里导入 Gopeed 内核"
        }
        if (!engineEnabledProvider()) {
            return "磁力下载需要先在「设置 → 下载 → 下载引擎」里把下载引擎切换为 Gopeed"
        }
        return null
    }

    /**
     * 重新下载：用原直链新建任务（任务卡右键菜单「重新下载」）。
     * 先做 Range 探测校验直链有效性：403/404/网络错误视为直链已过期，返回 false 由 UI 提示。
     * 磁力链接跳过探测——getTotalSize 是 HTTP Range 探测，喂它 magnet: 必然失败，
     * 会让「重新下载」永远提示直链失效；磁力直接重新入队，引擎不可用时由 enqueue 的拦截说明原因。
     */
    suspend fun redownload(id: Long): Boolean {
        val task = dao.get(id) ?: return false
        val headers = loadPersistedHeaders(id)
        if (!MagnetLink.isMagnet(task.url)) {
            val valid = runCatching { downloader.getTotalSize(task.url, headers) != null }.getOrDefault(false)
            if (!valid) return false
        }
        // 「重新下载」语义是重头下：旧任务的分片不会再被新任务复用，留着只会继续占缓存
        val freed = DownloadCacheMaintenance.deleteChunkDir(id)
        if (freed > 0) {
            DownloadDebugLog.record(id, "清理旧分片", "重新下载前释放 ${DownloadCacheMaintenance.formatBytes(freed)}")
        }
        // 实验性直写：旧任务的 .part 同样作废
        DownloadSaver.deleteDirectPart(task.directPart)
        if (task.directPart.isNotBlank()) dao.updateDirect(id, "", 0)
        enqueue(
            task.url, task.fileName, headers, task.totalSize, task.platform,
            saveDir = task.saveDir,
            // DASH 合轨 / 动态照片任务的「重新下载」也必须带上另外几路资源，否则只会下到半截内容
            mergeAudioUrl = task.mergeAudioUrl,
            mergeAudioHeaders = loadPersistedMergeAudioHeaders(task.mergeAudioHeadersJson),
            motionUrl = task.motionUrl
        )
        return true
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        // 引擎任务：转发「继续」给 Gopeed（绝不能让内置下载器用同一 URL 再下一次）
        val engineId = engineTaskIds[id]
        if (engineId != null) {
            resumeEngineTask(id, engineId)
            return
        }
        // 恢复时未传 headers：沿用入队时保存的（Cookie/UA 对直链下载是必需的）
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
        DownloadDebugLog.record(id, "开始/恢复", "请求头=${effectiveHeaders.keys.joinToString(",").ifBlank { "-" }}")
        synchronized(jobsLock) {
            // 原子注册：检查 + 占位 + launch + complete 在同一锁内完成，
            // pause/remove 要么拿到已注册的 job，要么拿不到（视为未运行）
            val existing = activeJobs[id]
            if (existing != null) {
                // job 仍活跃（正在下载/收尾）：忽略本次 start，避免重复启动
                if (existing.isCompleted && existing.getCompleted().isActive) return
                // job 已结束但 finally 尚未清理（暂停后立即恢复的残留）：
                // 移除旧引用，继续注册新 job，保证"点开始"立即生效
                activeJobs.remove(id)
            }
            val deferred = CompletableDeferred<Job>()
            activeJobs[id] = deferred
            val job = scope.launch {
                try {
                    // 任务开始：有任务在下载时保持前台服务（避免切后台限速/进程被杀）
                    onTaskStarted(id)
                    // 任务级互斥：同一任务串行执行，暂停后立刻恢复不会并发写分片
                    taskLocks.getOrPut(id) { Mutex() }.withLock {
                        val restoredHeaders = if (effectiveHeaders.isNotEmpty()) {
                            effectiveHeaders
                        } else {
                            loadPersistedHeaders(id)
                        }
                        if (restoredHeaders.isNotEmpty()) taskHeaders[id] = restoredHeaders
                        runTaskWithRetry(id, restoredHeaders)
                    }
                } catch (e: CancellationException) {
                    // 主动暂停/删除：part 文件保留（或由 remove 清理）；状态已由调用方设置
                    _stats.update { it - id }
                } catch (e: Exception) {
                    _stats.update { it - id }
                    // 协程已被取消（暂停/删除）：不标记失败，避免覆盖 PAUSED 状态
                    if (isTaskActive()) {
                        Log.e(TAG, "task $id failed: ${e.message ?: e.javaClass.simpleName}", e)
                        val diskFull = DownloadCacheMaintenance.isDiskFullError(e)
                        val message = if (diskFull) {
                            // 真的把盘写满了：分片继续留着只会让磁盘一直满着，先删掉释放空间
                            val freed = DownloadCacheMaintenance.deleteChunkDir(id)
                            DownloadDebugLog.record(
                                id, "清理缓存",
                                "磁盘写满，已删除该任务分片并释放 ${DownloadCacheMaintenance.formatBytes(freed)}"
                            )
                            "磁盘空间不足，下载已停止；已清理该任务的分片缓存（释放 " +
                                "${DownloadCacheMaintenance.formatBytes(freed)}）。" +
                                "请到「设置 → 储存目录」更换缓存目录，或清理磁盘后重试。"
                        } else {
                            e.message ?: e.javaClass.simpleName
                        }
                        DownloadDebugLog.record(id, "失败", message)
                        dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(id, message)
                    } else {
                        Log.w(TAG, "task $id cancelled: ${e.message}")
                    }
                } finally {
                    // 任务结束（成功/失败/暂停/删除）：无任务时停止前台服务
                    onTaskFinished(id)
                    // 释放空间守卫里的预留名额（暂停后恢复会重新登记）
                    clearSpacePlan(id)
                    clearDirectState(id)
                    // 任务协程已结束，在飞表不会再更新：撤掉下载调试的登记（否则会留下一个空表占位）
                    activeChunkMaps.remove(id)
                    taskPartDigests.remove(id)
                    // 只移除自己注册的 deferred：
                    // 若暂停后立即恢复（新 job 已注册到同一 id），不能误删新任务的注册，
                    // 否则新任务将无法再被暂停/删除（后台继续下载）
                    synchronized(jobsLock) {
                        if (activeJobs[id] === deferred) activeJobs.remove(id)
                    }
                    // 注意：taskLocks 不在此清理 —— 若新任务已 getOrPut 拿到锁，
                    // 旧任务 finally 的 remove 会误删新任务的锁导致并发写分片
                }
            }
            // launch 是同步返回 Job 的，锁内 complete，pause/remove 的 await 立即返回
            deferred.complete(job)
        }
    }

    /** 任务开始/结束计数 + Windows 系统集成（阻止休眠 / 通知中心进度） */
    private suspend fun onTaskStarted(id: Long) {
        activeTaskCount.getAndIncrement()
        if (keepAwakeProvider()) WindowsKeepAwake.acquire()
        WindowsToastNotifier.sessionStart()
    }

    private fun onTaskFinished(id: Long) {
        toastMeta.remove(id)
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            WindowsKeepAwake.release()
            WindowsToastNotifier.sessionEnd()
        }
    }

    /** 暂停下载（保留 part 文件与请求头） */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        DiagnosticLog.event(DiagnosticLog.OPERATION, "download_pause", "id=$id")
        DownloadDebugLog.record(id, "暂停", "用户暂停，分片文件保留")
        // 引擎任务：转发「暂停」给 Gopeed，进度以引擎侧为准
        val engineId = engineTaskIds[id]
        if (engineId != null) {
            pauseEngineTask(id, engineId)
            return
        }
        // 立即中断该任务所有分片网络请求（不依赖协程取消传播，阻塞 IO 马上停止）
        downloader.cancelCalls(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        _stats.update { it - id }
        scope.launch {
            // 等协程真正退出（确保没有半截写入）后，以磁盘 part/seg 真实大小为准回写进度：
            // 暂停瞬间最后一次 onBytes 可能被取消丢弃，DB 落后于磁盘 → 恢复时进度回跳
            deferred?.let { runCatching { it.await().cancelAndJoin() } }
            val real = chunkDirOf(id).listFiles()
                ?.filter {
                    it.name.startsWith("part_") ||
                        (it.name.startsWith("seg_") && it.name.endsWith(".part"))
                }
                ?.sumOf { it.length() } ?: 0L
            val t = dao.get(id)
            if (t != null && real > t.downloadedSize) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_PAUSED, real, t.totalSize)
            } else {
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            }
        }
    }

    /**
     * 删除任务：取消下载 + 清 DB + 清 part 文件。
     * @param deleteLocal 同时删除已保存到本地的文件（savePath）
     */
    fun remove(id: Long, deleteLocal: Boolean = false) {
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        DownloadDebugLog.record(id, "删除", "deleteLocal=$deleteLocal")
        DownloadDebugLog.drop(id)
        clearSpacePlan(id)
        clearDirectState(id)
        // 引擎任务：顺带通知引擎删除（失败不阻断本地清理）
        engineTaskIds.remove(id)?.let { engineId ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { GopeedEngine.deleteTask(engineId) } }
                    .onFailure { Log.w(TAG, "通知引擎删除任务失败：${it.message}") }
            }
        }
        engineDestPaths.remove(id)
        finishEngineTask(id)
        // 立即中断该任务所有分片网络请求
        downloader.cancelCalls(id)
        _stats.update { it - id }
        taskHeaders.remove(id)
        taskMergeAudioHeaders.remove(id)
        taskAltSources.remove(id)
        taskFallbackUrls.remove(id)
        // 删除任务同样触发清理回调（如删除网盘临时转存文件）：
        // 用户放弃下载时云盘里已转存的临时文件也应一并清理
        val cleanup = taskCallbacks.remove(id)
        taskLocks.remove(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        scope.launch {
            // 若任务正在下载：取消并等待协程真正退出，
            // 确保没有后台残留下载、part 文件无 fd 占用（否则删了仍占空间）
            if (deferred != null) {
                deferred.await().cancelAndJoin()
            }
            if (deleteLocal) {
                dao.get(id)?.savePath?.let {
                    val deleted = DownloadSaver.delete(it)
                    Log.d(TAG, "remove: id=$id 删除本地文件 ${if (deleted) "成功" else "失败/未找到"} ($it)")
                }
            }
            // 实验性直写：删除目标目录里的 .part / .plan / .done
            DownloadSaver.deleteDirectPart(dao.get(id)?.directPart)
            DiagnosticLog.dbOp("download_task", "delete", rowsOf = { -1 }) { dao.delete(id) }
            DiagnosticLog.event(
                DiagnosticLog.OPERATION, "download_remove",
                "id=$id deleteLocal=$deleteLocal"
            )
            chunkDirOf(id).deleteRecursively()
            // 删除任务后清理云盘转存（与下载成功完成同语义）；失败不阻断
            cleanup?.let { runCatching { it() } }
        }
    }

    // ---------- 外部 Gopeed 引擎（exe 子进程 + 本地 HTTP API）----------

    /** 本地任务 id → 引擎任务 ID（内存快判；持久化真源是 download_task.engineTaskId） */
    private val engineTaskIds = ConcurrentHashMap<Long, String>()

    /** 引擎任务的预期落盘绝对路径（完成时写库；进程重启后回退为「当前下载目录 + 文件名」） */
    private val engineDestPaths = ConcurrentHashMap<Long, String>()

    /** 已计入保活/通知计数的引擎任务：保证 onTaskStarted / onTaskFinished 恰好各配一次 */
    private val engineRunningIds = ConcurrentHashMap.newKeySet<Long>()

    private val engineSyncLock = Any()
    private var engineSyncJob: Job? = null

    /** 引擎进度轮询间隔（毫秒）：本地 HTTP 调用，开销极小 */
    private val engineSyncIntervalMs = 1000L

    /**
     * 上次下发给引擎的运行配置签名（并发上限 + 连接数 + 代理，见 [GopeedEngine.settingsSignature]）。
     * null 表示尚未下发过；签名变化（含用户在设置里改代理/线程数）时重新下发。
     */
    @Volatile
    private var lastEngineConfigKey: String? = null

    /**
     * 把「最大同时下载数 + 下载线程数 + 代理」下发给引擎，并顺带下发 BT 不做种。
     *
     * 引擎内的并发是原生调度：超出上限的任务被置为 `wait` 排队，有任务结束时引擎自己补位。
     * 但「调大上限」时引擎不会主动放行队列，所以在 [GopeedEngine.applyRuntimeConfig] 里按空位数补唤醒。
     * 失败不阻断下载（引擎用库里的旧值），只有引擎在跑却拿不到值才算失败。
     */
    private suspend fun syncEngineRuntimeConfig() {
        val desired = concurrencyProvider().coerceAtLeast(1)
        // 签名：并发上限 + 设置里影响引擎的其它项（线程数/代理）。任一项变了都要重新下发。
        val key = "$desired|${GopeedEngine.settingsSignature()}"
        if (key == lastEngineConfigKey) return
        val applied = runCatching {
            withContext(Dispatchers.IO) { GopeedEngine.applyRuntimeConfig(desired) }
        }.onFailure { Log.w(TAG, "下发引擎运行配置失败（并发/连接数/代理）：${it.message}") }.getOrNull()
        // null = 引擎没在跑（下次启动会带上新值）；此时不写缓存，等引擎起来后会重试
        if (applied != null) lastEngineConfigKey = key
    }

    init {
        // 进程重启后接管历史引擎任务：必要时拉起引擎并继续轮询进度
        scope.launch { ensureEngineSync() }
        // 启动缓存治理（Issue #17）：
        // ① 上次进程没跑完的「下载中 / 等待中」任务实际已停止，改回暂停，避免 UI 一直显示下载中；
        // ② 清掉已完成任务、已删除任务残留的分片目录与合并临时文件 —— 旧实现只在成功回调里删，
        //    进程退出/删除文件失败时这些分片会永久占着缓存目录。
        scope.launch {
            runCatching { dao.markInterruptedAsPaused() }
                .onFailure { Log.w(TAG, "启动状态修复失败：${it.message}") }
            runCatching { DownloadCacheMaintenance.cleanAfterStartup(dao) }
                .onFailure { Log.w(TAG, "启动缓存清理失败：${it.message}") }
            // 兼容旧版本：已完成任务的轮询/进度可能略小于 total，补到总大小（只影响显示）
            runCatching { dao.repairCompletedProgress() }
                .onFailure { Log.w(TAG, "启动修复已完成任务进度失败：${it.message}") }
            // 兼容旧版本：Gopeed 完成任务时曾把「文件夹/子目录/文件」覆盖成纯文件名，
            // 导致下载页无法按文件夹分组。启动时从 savePath 反推相对路径修复一次（幂等）。
            runCatching { repairCompletedEngineFolderNames() }
                .onFailure { Log.w(TAG, "启动修复 Gopeed 文件夹显示名失败：${it.message}") }
        }
    }

    /**
     * 新任务是否交给外部引擎：平台不是 GitHub（引擎无法按镜像回退）、设置里选了 Gopeed、
     * 且内核已导入。放在 enqueue 末尾统一判断，所有调用点自动生效。
     */
    private fun shouldUseEngine(platform: String): Boolean =
        platform != DownloadPlatform.GITHUB && engineEnabledProvider() && GopeedEngine.isInstalled()

    /** 引擎落盘基准目录：任务级覆盖优先，否则设置里的自定义下载目录，最后系统「下载」目录 */
    private fun engineBaseDir(saveDirOverride: String? = null): File =
        if (!saveDirOverride.isNullOrBlank()) File(saveDirOverride)
        else DownloadSaver.resolveSaveDir(saveDirProvider())

    /** 任务实际使用的保存目录：任务级覆盖优先，空串回退全局设置 */
    private fun effectiveSaveDir(task: DownloadTaskEntity): String? =
        task.saveDir.takeIf { it.isNotBlank() } ?: saveDirProvider()

    /** 任务实际使用的分片线程数：任务级覆盖优先（浏览器插件弹窗选择），0 = 跟随平台设置 */
    private fun threadsFor(task: DownloadTaskEntity): Int =
        if (task.threads > 0) task.threads else threadProvider(task.platform)

    /** 新建引擎任务（enqueue 的引擎分流入口） */
    private suspend fun startViaEngine(
        id: Long,
        url: String,
        fileName: String,
        headers: Map<String, String>,
        platform: String,
        /** 任务级保存目录覆盖；空串 = 全局下载目录 */
        saveDirOverride: String = ""
    ) {
        val base = engineBaseDir(saveDirOverride)
        // 与内置下载器同一套路径净化规则：防目录穿越、非法字符替换、名字截断
        val safe = DownloadPathPolicy.sanitize(fileName, "download_${System.currentTimeMillis()}")
        val destDir = if (safe != null && safe.relativeDirectory.isNotBlank()) {
            File(base, safe.relativeDirectory)
        } else {
            base
        }
        // 用户自选目录可能尚未创建；引擎不会替我们建，先建好
        runCatching { destDir.mkdirs() }
        val name = safe?.fileName ?: fileName
        // 磁力（BT）在引擎解析出元数据前没有名字：交给引擎自己命名（真实种子名稍后写回本地记录）；
        // 连接数是 http 协议的分片并发参数，BT 不吃
        val isMagnet = platform == DownloadPlatform.MAGNET
        val engineId = try {
            withContext(Dispatchers.IO) {
                GopeedEngine.start(base)
                // 在建首个任务前把「最大同时下载数」/不做种推给引擎，让引擎从一开始就按上限排队
                syncEngineRuntimeConfig()
                // 引擎不会替我们建目录：目录不存在会把任务建在一个不存在的路径上（随后落盘失败）
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw java.io.IOException("无法创建目录：$destDir")
                }
                GopeedEngine.createTask(
                    url = url,
                    saveDir = destDir.absolutePath,
                    fileName = if (isMagnet) "" else name,
                    headers = headers,
                    // 分片并发沿用按平台的线程数设置（映射到引擎的 extra.connections）
                    connections = if (isMagnet) 0 else threadProvider(platform),
                    label = id.toString()
                )
            }
        } catch (e: Exception) {
            // 建任务失败按失败任务落库，不静默回退内置（避免用户以为在用引擎、实际在用内置）
            Log.e(TAG, "startViaEngine: id=$id 创建引擎任务失败：${e.message}", e)
            DownloadDebugLog.record(id, "失败", "引擎建任务失败：${e.message ?: e.javaClass.simpleName}")
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, e.message ?: e.javaClass.simpleName)
            return
        }
        engineTaskIds[id] = engineId
        engineDestPaths[id] = File(destDir, name).absolutePath
        runCatching { dao.updateEngineTaskId(id, engineId) }
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        beginEngineTask(id)
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "engine_task_created", taskId = id, status = "downloading",
            summary = "engineId=$engineId platform=$platform name=$name"
        )
        Log.i(TAG, "startViaEngine: id=$id engineId=$engineId dest=${engineDestPaths[id]}")
        DownloadDebugLog.record(id, "引擎任务创建", "engineId=$engineId 平台=$platform 连接数=${if (isMagnet) 0 else threadProvider(platform)}")
    }

    /** 转发暂停给引擎 */
    private fun pauseEngineTask(id: Long, engineId: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GopeedEngine.pauseTask(engineId) } }
                .onFailure { Log.w(TAG, "引擎暂停失败：${it.message}") }
            dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            finishEngineTask(id)
        }
    }

    /** 转发继续给引擎 */
    private fun resumeEngineTask(id: Long, engineId: String) {
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { GopeedEngine.continueTask(engineId) } }
            if (ok.isFailure) {
                val msg = ok.exceptionOrNull()?.message ?: "引擎继续失败"
                Log.w(TAG, "引擎继续失败：$msg")
                dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                dao.updateError(id, msg)
                return@launch
            }
            taskStartTimes[id] = System.currentTimeMillis()
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            beginEngineTask(id)
        }
    }

    /** 计入保活/通知计数（幂等）并确保同步协程在跑 */
    private fun beginEngineTask(id: Long) {
        if (engineRunningIds.add(id)) {
            scope.launch { onTaskStarted(id) }
        }
        ensureEngineSync()
    }

    /** 结束计数（幂等）并从实时统计里移除 */
    private fun finishEngineTask(id: Long) {
        if (engineRunningIds.remove(id)) onTaskFinished(id)
        _stats.update { it - id }
    }

    /** 确保引擎同步协程在跑（单例，幂等） */
    private fun ensureEngineSync() {
        synchronized(engineSyncLock) {
            if (engineSyncJob?.isActive == true) return
            engineSyncJob = scope.launch { engineSyncLoop() }
        }
    }

    /**
     * 引擎进度轮询：逐条查 `/status` 并回写本地任务（Gopeed 没有推送订阅，
     * 上游 Android 版同样是轮询；本地 HTTP 开销极小，此处 1s 一次）。
     * 没有可同步的任务时退出循环，下次建任务由 [beginEngineTask] 重新拉起。
     */
    private suspend fun engineSyncLoop() {
        while (isTaskActive()) {
            val syncable = runCatching { dao.listSyncableEngineTasks() }.getOrDefault(emptyList())
            if (syncable.isEmpty()) return
            // 引擎没在跑（应用重启 / 手动停过）时按需拉起
            if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                val started = runCatching {
                    withContext(Dispatchers.IO) { GopeedEngine.start(engineBaseDir()) }
                }.isSuccess
                if (!started) {
                    Log.w(TAG, "引擎同步：启动引擎失败，稍后重试")
                    delay(engineSyncIntervalMs)
                    continue
                }
                // 引擎刚拉起：重置缓存，确保运行配置重新推送（bolt 里可能是旧值）
                lastEngineConfigKey = null
            }
            // 引擎运行中：按需同步「最大同时下载数」（设置变化时 1s 内生效）
            syncEngineRuntimeConfig()
            for (task in syncable) {
                val engineId = task.engineTaskId
                // 内存快判表回填：进程重启后也要能暂停/继续/删除引擎任务
                engineTaskIds[task.id] = engineId
                val viewResult = runCatching {
                    withContext(Dispatchers.IO) { GopeedEngine.taskStatus(engineId) }
                }
                val view = viewResult.getOrNull()
                if (view == null) {
                    Log.w(TAG, "引擎同步：查询任务 $engineId 失败：${viewResult.exceptionOrNull()?.message}")
                    continue
                }
                when (view.status) {
                    GopeedEngine.TaskStatus.DONE -> completeEngineTask(task, view)
                    GopeedEngine.TaskStatus.ERROR -> {
                        DiagnosticLog.error(
                            DiagnosticLog.DOWNLOAD, "engine_task_failed", code = "ENGINE_ERROR",
                            summary = "task=${task.id} engineId=${task.engineTaskId} name=${task.fileName}"
                        )
                        dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(task.id, "Gopeed 引擎下载失败")
                        DownloadDebugLog.record(task.id, "失败", "Gopeed 引擎下载失败 engineId=${task.engineTaskId}")
                        finishEngineTask(task.id)
                    }
                    GopeedEngine.TaskStatus.PAUSE -> {
                        dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                        finishEngineTask(task.id)
                    }
                    GopeedEngine.TaskStatus.WAIT -> {
                        // 超出「最大同时下载任务数」，被引擎排在队列里等空位
                        // → 本地记成「等待中」。★ 不能落到下面的 else：那会显示成 0% 的「下载中」，看着像卡死。
                        // 排队期间进度不动、状态也只写一次，避免每秒重复写库触发无谓的 UI 刷新。
                        if (task.status != DownloadTaskEntity.STATUS_PENDING) {
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PENDING)
                            DiagnosticLog.log(
                                DiagnosticLog.DOWNLOAD, "task_queued", taskId = task.id, status = "wait",
                                summary = "engineId=${task.engineTaskId} 超出最大同时下载任务数，引擎排队中"
                            )
                        }
                        _stats.update { it - task.id }
                    }
                    else -> {
                        // ready / running：真正在传输：更新进度、速度、通知
                        dao.updateProgress(
                            task.id,
                            DownloadTaskEntity.STATUS_DOWNLOADING,
                            view.downloaded,
                            view.total
                        )
                        val remain = if (view.speed > 0 && view.total > view.downloaded) {
                            (view.total - view.downloaded) * 1000 / view.speed
                        } else {
                            -1L
                        }
                        // 显示**实际**并发连接数（配置里传了 32 不代表引擎真开了 32 条连接）；
                        // 取不到（老版本内核无 /stats）时回退到设置里的线程数
                        val realConnections = runCatching {
                            withContext(Dispatchers.IO) { GopeedEngine.taskConnections(engineId) }
                        }.getOrNull()
                        _stats.update {
                            it + (task.id to DownloadStats(
                                speed = view.speed,
                                remainMillis = remain,
                                chunkCount = realConnections ?: threadsFor(task)
                            ))
                        }
                        notifyProgress(task.id, task.fileName, view.downloaded, view.total)
                    }
                }
            }
            delay(engineSyncIntervalMs)
        }
    }

    /** 引擎侧任务完成：写完成态 + 平均速度，触发清理回调并收尾保活 */
    private suspend fun completeEngineTask(task: DownloadTaskEntity, view: GopeedEngine.TaskView) {
        val total = if (view.total > 0) view.total else task.totalSize
        val expectedDest = engineDestPaths.remove(task.id)
        // 磁力（BT）任务的真实名字/落盘结构只有引擎解析完元数据才知道：
        // 引擎侧用种子名建目录 ⇒ 多文件种子落成 <下载目录>/<种子名>/...，单文件种子落成 <下载目录>/<种子名>。
        // 本地记录里的 fileName 是元数据到手前自己起的显示名，直接拿它拼路径会指向不存在的文件
        // （打开文件 / 删除本地文件 / 重新下载全会错）。非磁力任务读一次详情也无害，故不做分支区别对待。
        val detail = runCatching {
            withContext(Dispatchers.IO) { GopeedEngine.taskDetail(task.engineTaskId) }
        }.onFailure { Log.w(TAG, "读取引擎任务详情失败，用本地文件名兜底：id=${task.id} ${it.message}") }.getOrNull()
        val realName = detail?.name?.takeIf { it.isNotBlank() } ?: task.fileName
        // 落盘目录：优先用建任务时记录的目标目录（含任务级「下载到…」覆盖与相对子目录）；
        // 进程重启后内存表为空，则按任务记录的文件名/保存目录重建
        val fallbackDir = run {
            val safe = DownloadPathPolicy.sanitize(task.fileName, "")
            val base = engineBaseDir(task.saveDir)
            if (safe != null && safe.relativeDirectory.isNotBlank()) File(base, safe.relativeDirectory) else base
        }
        val destBase = expectedDest?.let { File(it).absoluteFile.parentFile } ?: fallbackDir
        val savedPath = File(destBase, realName).absolutePath
        completeWithAvg(task.id, savedPath, total)
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "engine_task_complete", taskId = task.id, status = "completed",
            size = total,
            summary = "path=$savedPath 文件数=${detail?.fileCount ?: -1} 目录=${detail?.folder ?: false}"
        )
        if (task.platform == DownloadPlatform.MAGNET) {
            // 磁力：把界面上占位的显示名换成真正的种子名（失败只记日志，不影响已完成状态）
            if (realName != task.fileName) {
                runCatching { dao.updateFileName(task.id, realName) }
                    .onFailure { Log.w(TAG, "回写种子名失败：id=${task.id} ${it.message}") }
            }
        } else {
            // 非磁力：引擎 detail.name 只是“文件名”，不能直接覆盖文件夹下载的相对路径
            // （如「文件夹A/子目录/文件.mp4」），否则下载页按 fileName 分组的逻辑会失效。
            // 保留原相对目录，只替换最后一段文件名（引擎可能因重名自动改名）。
            val displayName = engineDisplayName(task.fileName, realName)
            if (displayName.isNotBlank() && displayName != task.fileName) {
                runCatching { dao.updateFileName(task.id, displayName) }
                    .onFailure { Log.w(TAG, "回写文件名失败：id=${task.id} ${it.message}") }
            }
        }
        taskCallbacks.remove(task.id)?.let { runCatching { it() } }
        taskHeaders.remove(task.id)
        taskAltSources.remove(task.id)
        taskSizes.remove(task.id)
        taskFallbackUrls.remove(task.id)
        finishEngineTask(task.id)
        Log.i(
            TAG,
            "引擎任务完成：id=${task.id} path=$savedPath name=$realName " +
                "files=${detail?.fileCount ?: -1} folder=${detail?.folder ?: false}"
        )
    }

    /**
     * 修复旧版本 Gopeed 文件夹任务的显示路径。
     *
     * 旧版 `completeEngineTask` 用引擎返回的纯文件名覆盖了 `fileName`，
     * 使「文件夹A/子目录/文件.mp4」变成「文件.mp4」，下载页按 `/` 分组的逻辑失效。
     * 这里只在能安全推导时修复：已完成、非磁力、fileName 不含 `/`、savePath 是文件且位于保存目录内。
     * 纯幂等操作：修复后 fileName 含 `/`，下次启动自动跳过。
     */
    private suspend fun repairCompletedEngineFolderNames() {
        val tasks = runCatching { dao.listAll() }.getOrElse { return }
        var fixed = 0
        for (task in tasks) {
            if (task.engineTaskId.isBlank()) continue
            if (task.platform == DownloadPlatform.MAGNET) continue
            if (task.status != DownloadTaskEntity.STATUS_COMPLETED) continue
            if (task.fileName.contains('/')) continue
            if (task.savePath.isBlank()) continue
            val base = DownloadSaver.resolveSaveDir(
                task.saveDir.takeIf { it.isNotBlank() } ?: saveDirProvider()
            )
            val rel = folderRelativeName(base, task.savePath) ?: continue
            runCatching { dao.updateFileName(task.id, rel) }.onSuccess { fixed++ }
        }
        if (fixed > 0) Log.i(TAG, "已修复 $fixed 个 Gopeed 文件夹任务的显示路径")
    }

    /**
     * 非磁力 Gopeed 任务完成后的显示名：保留原相对目录，只替换最后一段文件名。
     * 例：requested=`文件夹/子/文件.mp4`, actual=`文件(1).mp4` → `文件夹/子/文件(1).mp4`
     */
    internal fun engineDisplayName(requestedName: String, actualName: String): String {
        val safe = DownloadPathPolicy.sanitize(requestedName, "")
        val relDir = safe?.relativeDirectory.orEmpty()
        val actualBase = actualName.substringAfterLast('/').substringAfterLast('\\')
        return if (relDir.isBlank()) actualBase else "$relDir/$actualBase"
    }

    /**
     * 目标文件相对保存根目录的路径；只有位于子目录（含 `/`）时才返回，根目录单文件返回 null。
     * 用于把 Gopeed 完成后丢失的相对路径从 `savePath` 反推回来。
     */
    internal fun folderRelativeName(baseDir: File, savePath: String): String? {
        val base = runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
        val file = File(savePath)
        if (!file.isFile) return null
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val basePath = base.toPath()
        val targetPath = target.toPath()
        if (!targetPath.startsWith(basePath)) return null
        val rel = basePath.relativize(targetPath).toString().replace(File.separatorChar, '/')
        return rel.takeIf { it.contains('/') }
    }

    // ---------- 内部实现 ----------

    /** 请求头落库：整体按 [key] 加密（下载主链用 `download.requestHeaders`，DASH 音轨用 `download.mergeAudioHeaders`） */
    private fun encodeHeaders(
        headers: Map<String, String>,
        key: String = "download.requestHeaders"
    ): String {
        val json = JSONObject().apply { headers.forEach { (name, value) -> put(name, value) } }.toString()
        return credentialCipher.encrypt(json, key)
    }

    /**
     * 备选源（实验性 C2）落库：数组 JSON 整体按 `download.altSources` 加密 ——
     * 里面是各账号的直链与 Cookie，敏感级别与请求头一致，必须加密。
     * 空列表存空串，与「从未开启该功能」完全同形。
     */
    private fun encodeAltSources(sources: List<DownloadSource>): String {
        if (sources.isEmpty()) return ""
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject().apply {
                    put("url", source.url)
                    put(
                        "headers",
                        JSONObject().apply { source.headers.forEach { (name, value) -> put(name, value) } }
                    )
                }
            )
        }
        return credentialCipher.encrypt(array.toString(), "download.altSources")
    }

    /**
     * 取回备选源。解析失败（密文损坏 / 换了密钥）时按**单源**处理而不是清任务：
     * 多源只是提速手段，丢了它照样能把文件下完，没必要因此让用户丢进度。
     */
    private suspend fun loadPersistedAltSources(id: Long): List<DownloadSource> {
        val stored = dao.get(id)?.altSourcesJson.orEmpty()
        if (stored.isBlank()) return emptyList()
        return runCatching {
            val text = credentialCipher.decrypt(stored, "download.altSources")
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val url = obj.optString("url")
                    if (url.isBlank()) continue
                    val headersJson = obj.optJSONObject("headers")
                    val headers = buildMap {
                        headersJson?.keys()?.forEach { name -> put(name, headersJson.getString(name)) }
                    }
                    add(DownloadSource(url, headers))
                }
            }
        }.getOrElse {
            Log.w(TAG, "loadPersistedAltSources: id=$id 解析失败，本次按单源下载")
            emptyList()
        }
    }

    private suspend fun loadPersistedHeaders(id: Long): Map<String, String> {
        val stored = dao.get(id)?.requestHeadersJson.orEmpty()
        if (stored.isBlank()) return emptyMap()
        return runCatching {
            val jsonText = credentialCipher.decrypt(stored, "download.requestHeaders")
            val json = JSONObject(jsonText)
            buildMap {
                json.keys().forEach { name -> put(name, json.getString(name)) }
            }.also {
                if (!credentialCipher.isEncrypted(stored)) {
                    dao.updateRequestHeaders(id, encodeHeaders(it))
                }
            }
        }.getOrElse {
            dao.updateRequestHeaders(id, encodeHeaders(emptyMap()))
            emptyMap()
        }
    }

    /** 当前协程是否仍活跃（暂停/删除触发取消后为 false） */
    private suspend fun isTaskActive(): Boolean = coroutineContext[Job]?.isActive == true

    /**
     * 取回 DASH 音轨请求头（`download_task.mergeAudioHeadersJson`，加密落库）。
     *
     * 解析失败（密文损坏 / 换了密钥）按「无音轨头」处理：很多直链只校验 UA，
     * 不能因为解不出 Cookie 就让整个合轨任务失败。
     */
    private fun loadPersistedMergeAudioHeaders(stored: String): Map<String, String> {
        if (stored.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(credentialCipher.decrypt(stored, "download.mergeAudioHeaders"))
            buildMap { json.keys().forEach { name -> put(name, json.getString(name)) } }
        }.getOrElse {
            Log.w(TAG, "loadPersistedMergeAudioHeaders: 解密失败，本次音轨不带请求头：${it.message}")
            emptyMap()
        }
    }

    /** 等待并发许可：当前下载任务数 >= 上限时轮询等待（暂停/取消可退出等待） */
    private suspend fun awaitConcurrencySlot() {
        val max = concurrencyProvider().coerceAtLeast(1)
        while (isTaskActive() && activeDownloads.get() >= max) {
            delay(300)
        }
    }

    /**
     * 执行任务并支持失败自动重试（断点续传，part 文件保留）。
     * 同时负责「最大同时下载任务数」并发许可的获取/释放。
     */
    private suspend fun runTaskWithRetry(id: Long, headers: Map<String, String>) {
        var attempts = 0
        val maxRetries = retryCountProvider().coerceIn(0, 10)
        while (true) {
            // 并发许可：排队等待，直到有空闲下载槽位（或任务被暂停/取消）
            awaitConcurrencySlot()
            if (!isTaskActive()) return
            activeDownloads.incrementAndGet()
            try {
                try {
                    runTask(id, headers)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DirectWriteFallbackException) {
                    // 多线程直写发现服务器不支持 Range：已清掉直写临时文件，下一轮走缓存分片。
                    // 不计入失败重试次数，避免把任务的 retryCount 消耗掉。
                    Log.w(TAG, "runTaskWithRetry: id=$id 直写回退缓存模式：${e.message}")
                    continue
                } catch (e: Exception) {
                    // 磁盘空间不足是确定性错误：重试只会继续写盘/继续失败，直接交给 UI 展示原因
                    if (e is DownloadCacheMaintenance.LowDiskSpaceException) throw e
                    attempts++
                    if (isTaskActive() && attempts <= maxRetries) {
                        Log.d(TAG, "runTaskWithRetry: id=$id 失败，自动重试 $attempts/$maxRetries：${e.message}")
                        DiagnosticLog.warn(
                            DiagnosticLog.DOWNLOAD, "task_retry",
                            "task=$id | attempt=$attempts/$maxRetries | err=${e.message}"
                        )
                        // 逐次递增延迟，避免失败风暴
                        delay(1200L * attempts)
                    } else {
                        throw e
                    }
                }
            } finally {
                activeDownloads.decrementAndGet()
            }
        }
    }

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        // 协程已被取消（暂停/删除）：直接退出，不写状态
        if (!isTaskActive()) return
        var task = dao.get(id) ?: return
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        taskStartTimes[id] = System.currentTimeMillis()
        Log.d(TAG, "runTask: id=$id fileName=${task.fileName}")

        // ★ 以下两条分支必须排在 HLS 判断之前：它们是「多资源合成一个文件」的语义，
        //   主 URL 的扩展名（静图 jpg / 视频轨 m4s）不能决定下载方式。
        // ① 动态照片（Motion Photo）：静图 + 动图资源 → 单文件
        if (task.motionUrl.isNotBlank()) {
            Log.d(
                TAG, "runTask: id=$id 动态照片合成下载 still=${LogRedactor.url(task.url)} " +
                    "motion=${LogRedactor.url(task.motionUrl)}"
            )
            DownloadDebugLog.record(id, "动图任务", "静图 + 动图 → 动态照片（合成失败降级为静图）")
            motionPhotoDownload(id, task, headers)
            return
        }
        // ② DASH 双轨（音视频分离）→ 合轨成普通 MP4
        if (task.mergeAudioUrl.isNotBlank()) {
            Log.d(
                TAG, "runTask: id=$id DASH 双轨下载 video=${LogRedactor.url(task.url)} " +
                    "audio=${LogRedactor.url(task.mergeAudioUrl)}"
            )
            DownloadDebugLog.record(id, "DASH 双轨", "视频轨 + 音轨 → 合轨（不重编码）")
            dashMergeDownload(id, task, headers)
            return
        }

        // HLS（m3u8 转码流，如 UC play）：不走 Range 分片，直接拉分片合并
        if (task.url.contains(".m3u8", true) || task.url.contains(".m3u", true)) {
            Log.d(TAG, "runTask: id=$id HLS 转码流下载 origin=${LogRedactor.url(task.url)}")
            if (directWriteSingleProvider() && id !in forceCacheMode) {
                hlsDownloadDirect(id, task, headers)
            } else {
                // 关闭直写/已回退缓存时，先清掉上次 HLS 直写遗留的临时文件（HLS 不支持断点续传）
                if (task.directPart.isNotBlank()) {
                    DownloadSaver.deleteDirectPart(task.directPart)
                    dao.updateDirect(id, "", 0)
                    task = task.copy(directPart = "", directMode = 0)
                }
                hlsDownload(id, task, headers)
            }
            return
        }

        // 总大小以服务器探测为准（Range0-0 的 Content-Range 是真实总大小），
        // 避免各平台传入的 size 与实际不符导致分片区间错误 → 文件截断/膨胀损坏
        // 镜像主 URL 探测失败且带回退直连时（GitHub 下载），整任务切到原始直连重下，避免镜像挂掉整任务失败
        val fallbackUrl = taskFallbackUrls[id]
        var probedSize = downloader.getTotalSize(task.url, headers)
        if (probedSize == null && !fallbackUrl.isNullOrBlank()) {
            Log.w(TAG, "runTask: id=$id 镜像主 URL 不可达，回退原始直连下载")
            task = task.copy(url = fallbackUrl)
            probedSize = downloader.getTotalSize(task.url, headers)
        }
        val total = probedSize
            ?: taskSizes[id]?.takeIf { it > 0 }
        // 实验性直写：优先按任务已记录的模式恢复；新任务再按开关 + 线程数选择。
        // 已存在的直写临时文件是上次暂停/失败留下的，不能因为用户后来关掉开关就丢掉。
        val threadCountForMode = threadsFor(task).coerceAtLeast(1)
        if (task.directPart.isNotBlank() &&
            (task.directMode !in 1..2 || !File(task.directPart).isFile)
        ) {
            dao.updateDirect(id, "", 0)
            task = task.copy(directPart = "", directMode = 0)
        }
        val directMode = when {
            id in forceCacheMode -> 0
            task.directMode in 1..2 && task.directPart.isNotBlank() ->
                if (task.directMode == 2 && total == null) 0 else task.directMode
            directWriteSingleProvider() && threadCountForMode == 1 -> 1
            directWriteMultiProvider() && threadCountForMode > 1 && (total ?: 0L) > 0 -> 2
            else -> 0
        }
        if (directMode != 0) {
            val directSaveDir = DownloadSaver.resolveSaveDir(effectiveSaveDir(task))
            val directError = DownloadCacheMaintenance.checkBeforeDirectDownload(directSaveDir, total ?: 0L)
            if (directError != null) {
                Log.w(TAG, "runTask: id=$id 直写空间不足：$directError")
                DownloadDebugLog.record(id, "空间不足", directError)
                throw DownloadCacheMaintenance.LowDiskSpaceException(directError)
            }
            if (directMode == 1) {
                runDirectSingle(id, task, headers, total)
            } else {
                runDirectMulti(id, task, headers, total ?: 0L)
            }
            return
        }
        // 直写不可用（例如多线程直写但服务器没给总大小，或已回退缓存）：清掉遗留直写文件，走缓存分片
        if (task.directPart.isNotBlank()) {
            DownloadSaver.deleteDirectPart(task.directPart)
            dao.updateDirect(id, "", 0)
            task = task.copy(directPart = "", directMode = 0)
        }
        // 写盘空间守卫（Issue #17）：开始下载前先确认缓存盘 / 下载盘放得下。
        // 同一磁盘按 2 倍文件大小校验（分片与最终文件在合并阶段会同时存在）；
        // 同时把其它进行中任务预计还要写入缓存盘的字节数一起算上（避免并发任务合计写爆缓存盘）。
        val saveBaseDir = DownloadSaver.resolveSaveDir(effectiveSaveDir(task))
        var spaceWaitLogged = false
        while (true) {
            var existingOnDisk = 0L
            val spaceError = synchronized(spaceGuardLock) {
                val actualByTask = DownloadCacheMaintenance.chunkBytesByTask()
                existingOnDisk = actualByTask[id] ?: 0L
                val sameVolume = DownloadCacheMaintenance.sameVolume(AppContext.cacheDir, saveBaseDir)
                val reserved = plannedCacheBytes.entries
                    .filter { it.key != id }
                    .sumOf { (otherId, expected) ->
                        val written = actualByTask[otherId] ?: 0L
                        val remaining = (expected - written).coerceAtLeast(0L)
                        // 同盘时对方合并阶段还要再占一份最终文件
                        if (sameVolume) remaining + expected else remaining
                    }
                DownloadCacheMaintenance.checkBeforeDownload(
                    cacheDir = AppContext.cacheDir,
                    saveDir = saveBaseDir,
                    totalBytes = total ?: 0L,
                    alreadyOnDiskBytes = existingOnDisk,
                    reservedCacheBytes = reserved
                ).also { reason ->
                    if (reason == null) plannedCacheBytes[id] = (total ?: 0L).coerceAtLeast(0L)
                }
            }
            if (spaceError == null) break

            if (!isTaskActive()) return
            // ① 单个文件本身就放不下（比磁盘还大 / 成品盘放不下）：等待也不会变好，直接失败
            val permanent = DownloadCacheMaintenance.isPermanentSpaceShortage(
                cacheFree = DownloadCacheMaintenance.freeSpace(AppContext.cacheDir),
                destFree = DownloadCacheMaintenance.freeSpace(saveBaseDir),
                sameVolume = DownloadCacheMaintenance.sameVolume(AppContext.cacheDir, saveBaseDir),
                totalBytes = total ?: 0L,
                alreadyOnDiskBytes = existingOnDisk
            )
            if (permanent) {
                Log.w(TAG, "runTask: id=$id 空间不足：$spaceError")
                DownloadDebugLog.record(id, "空间不足", spaceError)
                DiagnosticLog.warn(DiagnosticLog.DOWNLOAD, "task_no_space", "task=$id | $spaceError")
                throw DownloadCacheMaintenance.LowDiskSpaceException(spaceError)
            }
            // 没有任何其它任务会释放缓存：继续等待也不会变好，按空间不足失败并提示用户处理
            val hasOtherActiveTask = synchronized(spaceGuardLock) {
                plannedCacheBytes.keys.any { it != id }
            }
            if (!hasOtherActiveTask) {
                Log.w(TAG, "runTask: id=$id 空间不足且无其它任务可等待：$spaceError")
                DownloadDebugLog.record(id, "空间不足", spaceError)
                throw DownloadCacheMaintenance.LowDiskSpaceException(spaceError)
            }
            // ② 只是被其它进行中的任务暂时占着缓存：排队等待它们完成，避免整批任务瞬间全失败
            if (!spaceWaitLogged) {
                spaceWaitLogged = true
                Log.w(TAG, "runTask: id=$id 等待磁盘空间：$spaceError")
                DownloadDebugLog.record(id, "等待空间", spaceError)
                DiagnosticLog.warn(DiagnosticLog.DOWNLOAD, "task_space_wait", "task=$id | $spaceError")
                // 卡片显示「等待中」；等待期间不重复写库
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PENDING)
            }
            delay(2_000)
        }
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        if (total == null) {
            // 服务器不返回文件大小（Range/Content-Length 均缺失）：降级为流式下载（开放区间 Range）
            Log.w(TAG, "runTask: id=$id 无法获取总大小，降级流式下载 origin=${LogRedactor.url(task.url)}")
            DiagnosticLog.warn(DiagnosticLog.DOWNLOAD, "task_stream_fallback", "task=$id | 服务器没给大小，降级流式下载")
            streamDownload(id, task, headers)
            return
        }
        Log.d(TAG, "getTotalSize: id=$id total=$total origin=${LogRedactor.url(task.url)}")
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "task_size_probed", taskId = id, status = "downloading", size = total,
            summary = "已有进度=${task.downloadedSize} url=${LogRedactor.url(task.url)}"
        )
        // ★ 钳制后再回写：库里可能残留旧版本写下的虚高进度（> total），别把它原样再写一遍
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, minOf(task.downloadedSize, total), total)
        // 取到大小后再次检查取消（暂停可能发生在 getTotalSize 期间）
        if (!isTaskActive()) return

        val threadCount = threadsFor(task).coerceAtLeast(1)
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        // 分片级校验：本次运行内记录每个分片下载时算出的摘要，合并时逐片重算比对（下面清空旧分片时会一并清掉）
        val partDigests = ConcurrentHashMap<String, String>()
        taskPartDigests[id] = partDigests
        val planFile = File(chunkDir, "plan.txt")
        // ★ 分片计划：part_$i 按索引命名，但区间由 chunks/total 推导 ⇒ 计划一变旧 part 就错位、不可信。
        //   规则：total 变了（文件被换掉）必须整目录重下；**只是线程数变了则沿用旧计划**，
        //   避免"改一下线程数就把已下载的几 GB 全删掉重下"（这正是"自动重新下载"的来源之一）。
        val storedPlan = planFile.takeIf { it.exists() }?.readText().orEmpty()
        val storedTotal = PLAN_TOTAL.find(storedPlan)?.groupValues?.get(1)?.toLongOrNull()
        val storedChunks = PLAN_CHUNKS.find(storedPlan)?.groupValues?.get(1)?.toIntOrNull()
        val storedMain = PLAN_MAIN.find(storedPlan)?.groupValues?.get(1)?.toIntOrNull()
        val canReusePlan = storedTotal != null && storedTotal == total &&
            storedChunks != null && storedChunks > 0 &&
            storedMain != null && storedMain in 1..storedChunks
        val chunkCount: Int
        val mainPoolCount: Int
        if (canReusePlan) {
            chunkCount = storedChunks!!
            mainPoolCount = storedMain!!
            if (chunkCount != chunkCountFor(total, threadCount)) {
                Log.i(TAG, "runTask: id=$id 线程数已改，沿用旧分片计划续传（chunks=$chunkCount）")
            }
        } else {
            if (storedPlan.isNotBlank()) {
                Log.w(TAG, "runTask: id=$id 分片计划不可复用（旧 total=$storedTotal，新 total=$total），清空旧 part 重下")
                DownloadDebugLog.record(id, "断点失效", "分片计划不可复用（旧 total=$storedTotal，新 total=$total），清空旧分片重下")
                chunkDir.deleteRecursively()
                chunkDir.mkdirs()
                partDigests.clear()   // 旧分片已删，摘要一并作废
            }
            chunkCount = chunkCountFor(total, threadCount)
            mainPoolCount = (chunkCount * 0.7).toInt().coerceIn(1, chunkCount) // 主池片数（70%）
            planFile.writeText("chunks=$chunkCount total=$total main=$mainPoolCount")
        }
        val chunkSize = ceil(total.toDouble() / chunkCount).toLong()
        val elasticStart = mainPoolCount * chunkSize                          // 弹性区起始字节
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "chunk_plan", taskId = id, status = "downloading", size = total,
            summary = "线程数=$threadCount 分片数=$chunkCount 分片大小=$chunkSize 已完成=${task.downloadedSize}" +
                if (canReusePlan) "（沿用旧计划）" else ""
        )
        DownloadDebugLog.record(
            id, "分片规划",
            "分片数=$chunkCount 主池=$mainPoolCount 弹性起点=$elasticStart 单片=$chunkSize " +
                "线程=$threadCount 沿用旧计划=$canReusePlan"
        )
        // 有效并发：仅迅雷（CDN 对单文件并发 Range 有阈值，约 8 个，超过会降级 200 整文件）封顶安全上限；
        // 其他平台保持用户设置的线程数（满并发）
        val isXunlei = headers["User-Agent"]?.contains("xunlei", ignoreCase = true) == true ||
            task.url.contains("xunlei", ignoreCase = true)
        val effectiveWorkers = if (isXunlei) {
            min(threadCount, RANGE_WORKERS_CAP).coerceAtLeast(1)
        } else {
            threadCount.coerceAtLeast(1)
        }
        // ★ 实际 worker 再钳一道「全进程在飞上限」：真实并行度还受内存预算约束，超出的 worker
        //   只会排队等信号量、白白多占协程与排队 Call；钳掉后吞吐不变（分片盈余仍由任务池 + 弹性区提供）。
        //   注意：threadCount 仍原样传给 chunkCountFor —— plan.txt 签名不能变，否则断点续传失效。
        val actualWorkers = min(effectiveWorkers, MAX_INFLIGHT_CHUNKS)
        Log.d(TAG, "分片规划: id=$id chunks=$chunkCount main=$mainPoolCount elasticStart=$elasticStart " +
            "size=$chunkSize threads=$threadCount effectiveWorkers=$effectiveWorkers " +
            "actualWorkers=$actualWorkers inflightCap=$MAX_INFLIGHT_CHUNKS isXunlei=$isXunlei")

        // ---------- 单文件多源分片（实验性 C2）----------
        // 同一个文件的多条直链分属不同账号，分片按序号轮流交给不同源（每条直链带着它自己账号的请求头）。
        // ★ 分片规划一个字节都不动（chunkCount / total / plan.txt 全不变）⇒ 与单源共用同一套断点续传：
        //   开关该功能只影响「哪条直链下哪一片」，分片区间与文件名不变，已有断点不会失效。
        // 未开启 / 平台不支持 / 只有一个账号时 sources 只有主源一项，行为与开启前完全一致。
        val altSources = taskAltSources[id] ?: loadPersistedAltSources(id).also {
            if (it.isNotEmpty()) taskAltSources[id] = it
        }
        val sources = buildList {
            add(DownloadSource(task.url, headers))
            altSources.forEach { source -> if (source.url.isNotBlank()) add(source) }
        }
        /** 某个字节偏移所在分片用哪个源：offset / chunkSize 就是分片序号，按序号轮转 */
        fun sourceOf(offset: Long): DownloadSource =
            if (sources.size == 1) sources[0] else sources[((offset / chunkSize) % sources.size).toInt()]
        if (sources.size > 1) {
            DownloadDebugLog.record(id, "多源分片", "源数=${sources.size}（含主源）分片按序号轮流使用")
        }

        // 注册实时统计：线程数 = 实际并发（受安全上限与内存预算约束）
        _stats.update { it + (id to DownloadStats(0L, -1L, actualWorkers)) }

        // 统计已有 part/seg 大小（断点续传起点）。
        // ★★ 必须按「当前计划」逐片校验，不能直接按磁盘长度求和：
        //    旧 job 残留的分片可能与当前计划重叠（分片数/片大小改过就必然错位），
        //    求和会得到「比总大小还大」的进度 → 被钳到 100% → 界面永远卡 100% 但任务下不完。
        //    规则：主池第 i 片只认 part_i 且长度不超过该片区间长度；弹性区只认位于弹性起点
        //    （= mainPoolCount × chunkSize）之后的 seg，且长度不超过该段自身区间。
        val downloaded = AtomicLong(0)
        (0 until mainPoolCount).forEach { i ->
            val begin = i.toLong() * chunkSize
            val end = minOf(begin + chunkSize, total)
            val len = File(chunkDir, "part_$i").length()
            downloaded.addAndGet(len.coerceIn(0L, (end - begin).coerceAtLeast(0L)))
        }
        val segRegionStart = mainPoolCount.toLong() * chunkSize
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.forEach { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val segStart = name.substringBefore('_').toLongOrNull()
                val segEnd = name.substringAfter('_', "").toLongOrNull()
                // 落在主池区间里的 seg 属于旧计划残留：不计入（否则与主池的 part 重复计数）
                if (segStart != null && segEnd != null && segStart >= segRegionStart) {
                    downloaded.addAndGet(f.length().coerceIn(0L, (segEnd - segStart + 1).coerceAtLeast(0L)))
                }
            }
        // ★ 钳制到 total：防旧 job 残留累加导致显示"已下载 > 总大小"
        val init = minOf(downloaded.get(), total)
        downloaded.set(init)
        // ★ 以**磁盘真实长度**为准双向回写 DB：
        //   - init > DB：恢复时 DB 滞后于磁盘（暂停瞬间未上报的字节）→ 避免进度回跳；
        //   - init < DB：DB 残留了更高的旧值（例如上一次合并失败/中断后留下的 100%）→
        //     必须压下来，否则界面会一直显示 100% 却实际在重新下载，看起来就是「下载到 100% 卡死」。
        if (init != task.downloadedSize) {
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, init, total)
        }
        val lastPersistAt = AtomicLong(0L)
        val speedRecorder = SpeedRecorder()

        // ---------- 任务池（主池 70% 等分）+ 弹性区（30%，空闲线程中点劈分） ----------
        val results = arrayOfNulls<ChunkResult?>(mainPoolCount)
        val nextIdx = AtomicInteger(0)
        val fallback = AtomicBoolean(false)              // 任一分片检测到「服务器忽略 Range」→ 整任务回退单流
        val failReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val rangeIgnoredCount = AtomicInteger(0)         // RANGE_IGNORED 累计次数（偶发 200 容忍）

        // ★ 弹性区分配器（IDM 式动态分片）：按字节顺序发块、区间物理相邻，替代中点劈分（根治中后段掉速）。
        //   续传：不完整 seg 删除重下；完整 seg 前缀推进 nextStart（弹性区按序分配，完成块天然是字节前缀）。
        //   块大小随实时速度自适应并尾部收缩，保证全部连接忙到最后一块，消除"末尾只剩少数大块 → 拖尾特别慢"。
        val elasticAllocator = ElasticAllocator(total, elasticStart, actualWorkers, chunkSize)
        if (elasticStart < total) {
            // 不完整 seg 删除（重下）
            chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }?.forEach { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@forEach
                val e = name.substringAfter('_').toLongOrNull() ?: return@forEach
                if (f.length() < (e - s + 1)) f.delete()
            }
            // 推进到已完整前缀末尾（只前进，跳过已下载弹性块）
            val doneSegs = chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
                ?.mapNotNull { f ->
                    val name = f.name.removePrefix("seg_").removeSuffix(".part")
                    val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                    val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                    if (f.length() >= (e - s + 1)) s to e else null
                }?.sortedBy { it.first } ?: emptyList()
            var resumeNext = elasticStart
            for ((s, e) in doneSegs) {
                if (s == resumeNext) resumeNext = e + 1 else break
            }
            elasticAllocator.skipTo(resumeNext)
        }
        val elasticResults = ConcurrentHashMap<String, ChunkResult>()
        // 在飞分片表（key = m<片号> / seg@<起点> / retry@<起点>）：仅供看门狗算瞬时速度与抢占判定
        val inflightChunks = ConcurrentHashMap<String, InflightChunk>()
        // 登记给「下载调试」按需读取（start 的 finally 里移除）；不在下载热路径上做任何额外工作
        activeChunkMaps[id] = inflightChunks

        val allOk = coroutineScope {
            // ★ worker 数已钳到 actualWorkers；在飞槽位由全进程共享的 inflightLimiter 控制
            //   （绝不手动 release，也不要改回「每任务一个信号量」）
            val workers = List(actualWorkers) { workerIndex ->
                async(chunkIoDispatcher) {
                    // 阶段 1：主池循环领取
                    while (true) {
                        if (fallback.get()) break
                        val i = nextIdx.getAndIncrement()
                        if (i >= mainPoolCount) break
                        // 错峰建连：首请求前按序号微延迟，平摊 TCP/TLS 突发（仅影响首请求，不影响稳态并发）
                        if (i > 0) delay(min(i.toLong(), STAGGER_CAP.toLong()) * STAGGER_MS)
                        inflightLimiter.withPermit {
                            if (fallback.get()) return@withPermit
                            val start = i * chunkSize
                            val end = min(start + chunkSize - 1, total - 1)
                            // 登记在飞分片（key=m<片号>）：看门狗据此算单路瞬时速度、判定慢连接抢占
                            val diagKey = "m${i + 1}"
                            val diag = InflightChunk(start, end - start + 1, workerIndex)
                            inflightChunks[diagKey] = diag
                            // 分片级校验：本片（含续传前缀）的摘要，写完即登记，合并时比对
                            val partFile = File(chunkDir, "part_$i")
                            val partDigest = newPartDigest(partFile)
                            // 多源分片：这一片交给哪个账号的直链（单源时恒为主源）
                            val src = sourceOf(start)
                            val res = try {
                                downloader.downloadChunk(
                                    taskId = id, url = src.url, start = start, end = end,
                                    partFile = partFile, headers = src.headers,
                                    // ★ chunkKey = 该片的起点：让「重连」/抢占能据此主动掐断这条连接
                                    chunkKey = diag.start.toString(),
                                    preempt = diag.preempt,
                                    contentDigest = partDigest
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                    // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                    val new = minOf(downloaded.addAndGet(bytes), total)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        // ★ 实时速度注入弹性分配器：块大小随真实吞吐自适应（IDM 式动态分片）
                                        elasticAllocator.recentSpeedBps = speed
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, actualWorkers)) }
                                    }
                                    notifyProgress(id, task.fileName, new, total)
                                    persistProgressIfDue(id, new, total, force = false, lastAt = lastPersistAt)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failReason.compareAndSet(null, "分片 ${i + 1}/$mainPoolCount：${e.message ?: e.javaClass.simpleName}")
                                ChunkResult.FAILED
                            } finally {
                                inflightChunks.remove(diagKey)
                            }
                            // 只在成功时登记摘要：失败/忽略 Range 的片会在后续重试里重新算
                            if (res == ChunkResult.OK && partDigest != null) {
                                partDigests[partFile.name] = partDigest.digest().toHexDigest()
                            }
                            // 抢占后的结果：这条分片换过连接，最终是续传成功还是仍失败（含换连接后跑出的成绩）
                            if (diag.preemptCount > 0) {
                                DownloadDebugLog.record(
                                    id, "抢占结果",
                                    "分片=$diagKey worker#$workerIndex 结果=${res.name} " +
                                        "已收=${diagSize(diag.bytes.get())}/${diagSize(diag.size)} " +
                                        "用时=${diag.elapsedMs / 1000}s 共抢占${diag.preemptCount}次"
                                )
                            }
                            results[i] = res
                            when (res) {
                                ChunkResult.RANGE_IGNORED -> {
                                    // 偶发 200（CDN 限流中间态）不算真降级：前 N 次不触发回退，继续领新片；
                                    // 持续 RANGE_IGNORED 才回退单流
                                    val n = rangeIgnoredCount.incrementAndGet()
                                    Log.w(TAG, "runTask: id=$id 分片${i + 1} 检测到服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                                    if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                                }
                                ChunkResult.FAILED -> failReason.compareAndSet(null, "分片 ${i + 1}/$mainPoolCount 下载失败")
                                else -> {}
                            }
                        }
                    }
                    // 阶段 2：主池取空 → 弹性区按字节顺序领取自适应块（空闲线程逐个平滑转入，并发形态不突变）
                    while (!fallback.get()) {
                        val range = elasticAllocator.take() ?: break
                        val s = range.first
                        val e = range.last
                        val key = "${s}_${e}"
                        // 登记在飞弹性块（key=seg@<起点>）：看门狗据此算单路瞬时速度、判定慢连接抢占
                        val diagKey = "seg@$s"
                        val diag = InflightChunk(s, e - s + 1, workerIndex)
                        inflightChunks[diagKey] = diag
                        // 分片级校验：弹性块的摘要
                        val segFile = File(chunkDir, "seg_$key.part")
                        val segDigest = newPartDigest(segFile)
                        val res = try {
                            inflightLimiter.withPermit {
                                if (fallback.get()) return@withPermit ChunkResult.FAILED
                                // 多源分片：弹性块按它在文件里的偏移选源
                                val src = sourceOf(s)
                                downloader.downloadChunk(
                                    taskId = id, url = src.url, start = s, end = e,
                                    partFile = segFile, headers = src.headers,
                                    chunkKey = diag.start.toString(),
                                    preempt = diag.preempt,
                                    contentDigest = segDigest
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                    // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                    val new = minOf(downloaded.addAndGet(bytes), total)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        // ★ 实时速度注入弹性分配器：块大小随真实吞吐自适应（IDM 式动态分片）
                                        elasticAllocator.recentSpeedBps = speed
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, actualWorkers)) }
                                    }
                                    notifyProgress(id, task.fileName, new, total)
                                    persistProgressIfDue(id, new, total, force = false, lastAt = lastPersistAt)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChunkResult.FAILED
                        } finally {
                            inflightChunks.remove(diagKey)
                        }
                        if (res == ChunkResult.OK && segDigest != null) {
                            partDigests[segFile.name] = segDigest.digest().toHexDigest()
                        }
                        // 抢占后的结果（弹性区）
                        if (diag.preemptCount > 0) {
                            DownloadDebugLog.record(
                                id, "抢占结果",
                                "分片=$diagKey worker#$workerIndex 结果=${res.name} " +
                                    "已收=${diagSize(diag.bytes.get())}/${diagSize(diag.size)} " +
                                    "用时=${diag.elapsedMs / 1000}s 共抢占${diag.preemptCount}次"
                            )
                        }
                        elasticResults[key] = res
                        when (res) {
                            ChunkResult.RANGE_IGNORED -> {
                                val n = rangeIgnoredCount.incrementAndGet()
                                Log.w(TAG, "runTask: id=$id 弹性区间 $key 检测到服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                                if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                            }
                            ChunkResult.FAILED -> failReason.compareAndSet(null, "弹性区间 ${s}-${e} 下载失败")
                            else -> {}
                        }
                    }
                }
            }
            // 看门狗：周期刷新每路瞬时速度，并判定是否把慢连接换掉（worker 全部跑完即停）
            val preemptJob = launch(Dispatchers.IO) {
                val runStartMs = System.currentTimeMillis()
                var lastNetSig = DownloadDebugLog.networkSignature()
                while (true) {
                    delay(PREEMPT_TICK_MS)
                    // ★ 慢连接抢占（永久逻辑，勿删）：先刷新瞬时速度，再以本任务的平均单连接速度为参照
                    sampleInflightChunks(inflightChunks)
                    preemptSlowChunks(id, downloaded.get(), System.currentTimeMillis() - runStartMs, actualWorkers, inflightChunks)
                    // 下载调试：网络特征串变化 = 换了网络（切网卡、掉线重连），附完整环境
                    if (DownloadDebugLog.isEnabled()) {
                        val sig = DownloadDebugLog.networkSignature()
                        if (sig != lastNetSig) {
                            lastNetSig = sig
                            DownloadDebugLog.record(id, "网络变化", DownloadDebugLog.environmentSnapshot())
                        }
                    }
                }
            }
            workers.awaitAll()
            preemptJob.cancel()
            !fallback.get() && results.all { it == ChunkResult.OK } &&
                elasticResults.values.all { it == ChunkResult.OK }
        }

        // ---------- 收尾 ----------
        if (fallback.get()) {
            // 服务器忽略 Range：回退单条整文件流（只下一次，不按分片重复下载整文件）
            Log.w(TAG, "runTask: id=$id 回退单流整文件下载（避免重复下载整文件）")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        if (!allOk) Log.w(TAG, "runTask: id=$id 有分片未成功（${failReason.get()}），按缺失区间补齐")
        // ★ 一律以「磁盘真实长度」为准收集缺失区间（不能只信 worker 的返回值）：
        //   分片可能报了 OK 但长度不足（连接提前关闭 / 抢占竞态 / 旧 job 残留），
        //   那种情况只在合并阶段的大小校验才会暴露，会白白多跑一轮整任务重试。
        val missing = buildList {
            val seen = HashSet<Long>()
            for (i in 0 until mainPoolCount) {
                val f = File(chunkDir, "part_$i")
                val s = i * chunkSize
                val e = min(s + chunkSize - 1, total - 1)
                if (f.length() < (e - s + 1) && seen.add(s)) add(RetryRange(s, e, f))
            }
            // 弹性区：worker 报失败的区间（文件可能已被删除，必须以 worker 结果为准）
            elasticResults.forEach { (key, res) ->
                if (res != ChunkResult.OK) {
                    val s = key.substringBefore('_').toLong()
                    val e = key.substringAfter('_').toLong()
                    if (seen.add(s)) add(RetryRange(s, e, File(chunkDir, "seg_$key.part")))
                }
            }
            // 弹性区：磁盘上长度不足的区间（worker 报 OK 也不放行）
            chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }?.forEach { f ->
                val key = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = key.substringBefore('_').toLongOrNull() ?: return@forEach
                val e = key.substringAfter('_').toLongOrNull() ?: return@forEach
                if (f.length() < (e - s + 1) && seen.add(s)) add(RetryRange(s, e, f))
            }
        }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "runTask: id=$id 缺失区间 ${missing.size} 个 reason=${failReason.get()}，并行补齐")
            val retryOk = coroutineScope {
                val retryIdx = AtomicInteger(0)
                val retryResults = arrayOfNulls<ChunkResult?>(missing.size)
                val retryWorkers = List(min(actualWorkers, missing.size)) { retryWorkerIndex ->
                    async(chunkIoDispatcher) {
                        while (true) {
                            if (!isTaskActive()) break
                            val pos = retryIdx.getAndIncrement()
                            if (pos >= missing.size) break
                            val m = missing[pos]
                            // 重试区间同样登记：重试期间的慢连接也会被看门狗采样、抢占
                            val diagKey = "retry@${m.start}"
                            val diag = InflightChunk(m.start, m.end - m.start + 1, retryWorkerIndex)
                            inflightChunks[diagKey] = diag
                            // 分片级校验：重试区间的摘要
                            val retryDigest = newPartDigest(m.file)
                            val res = try {
                                // ★ 重试同样走全进程在飞信号量：少这一处会让「主池 + 弹性区 + 重试」
                                //   三路并发叠加，正是 OOM 的成因之一
                                inflightLimiter.withPermit {
                                    // 重试区间按起始偏移选源：与首次下载落在同一个源上
                                    val src = sourceOf(m.start)
                                    downloader.downloadChunk(
                                        taskId = id, url = src.url, start = m.start, end = m.end,
                                        partFile = m.file, headers = src.headers,
                                        chunkKey = diag.start.toString(),
                                        preempt = diag.preempt,
                                        contentDigest = retryDigest
                                    ) { bytes ->
                                        speedLimiter.awaitAllow(bytes)
                                        diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                        // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                        val new = minOf(downloaded.addAndGet(bytes), total)
                                        if (!isTaskActive()) return@downloadChunk
                                        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
                                        notifyProgress(id, task.fileName, new, total)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ChunkResult.FAILED
                            } finally {
                                inflightChunks.remove(diagKey)
                            }
                            if (res == ChunkResult.OK && retryDigest != null) {
                                partDigests[m.file.name] = retryDigest.digest().toHexDigest()
                            }
                            // 抢占后的结果（失败区间重试）
                            if (diag.preemptCount > 0) {
                                DownloadDebugLog.record(
                                    id, "抢占结果",
                                    "分片=$diagKey worker#$retryWorkerIndex 结果=${res.name} " +
                                        "已收=${diagSize(diag.bytes.get())}/${diagSize(diag.size)} " +
                                        "用时=${diag.elapsedMs / 1000}s 共抢占${diag.preemptCount}次"
                                )
                            }
                            retryResults[pos] = res
                            if (res != ChunkResult.OK) {
                                failReason.compareAndSet(null, "区间 ${m.start}-${m.end} 重试仍失败")
                            }
                        }
                    }
                }
                retryWorkers.awaitAll()
                retryResults.all { it == ChunkResult.OK }
            }
            if (retryOk) {
                Log.d(TAG, "runTask: id=$id 重试补齐所有区间，开始合并")
                finishDownload(id, chunkDir, finalChunkFiles(chunkDir, mainPoolCount), task.fileName, total, effectiveSaveDir(task))
                return
            }
            // 重试仍失败：回退单流
            Log.w(TAG, "runTask: id=$id 分片重试失败，回退单流整文件下载")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        Log.d(TAG, "runTask: id=$id 所有区间完成，开始合并")
        finishDownload(id, chunkDir, finalChunkFiles(chunkDir, mainPoolCount), task.fileName, total, effectiveSaveDir(task))
    }

    /** 最终合并文件列表：主池 part_0..part_{n-1}（连续前半段）+ 弹性区 seg_{start}_{end} 按 start 排序（后半段） */
    private fun finalChunkFiles(chunkDir: File, mainPoolCount: Int): List<File> {
        val mainFiles = (0 until mainPoolCount).map { File(chunkDir, "part_$it") }
        val elasticFiles = chunkDir.listFiles { f ->
            f.name.startsWith("seg_") && f.name.endsWith(".part")
        }?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLong() }
            ?: emptyList()
        return mainFiles + elasticFiles
    }

    /**
     * 回退：单条整文件流下载（服务器忽略 Range 时）。
     * 写入**独立**的 full_single.bin（从 0 开始），不复用 part_0，避免与已下分片错位/重复。
     */
    private suspend fun singleStreamFallback(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long,
        chunkDir: File,
        failReason: java.util.concurrent.atomic.AtomicReference<String?>
    ) {
        val fullFile = File(chunkDir, "full_single.bin").apply { delete() } // 全新整文件，从 0 开始
        val fullDownloaded = AtomicLong(0)
        val fullLastAt = AtomicLong(0L)
        val ok = downloader.downloadFull(id, task.url, fullFile, headers, total) { bytes ->
            speedLimiter.awaitAllow(bytes)
            // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
            val new = minOf(fullDownloaded.addAndGet(bytes), total)
            if (!isTaskActive()) return@downloadFull
            persistProgressIfDue(id, new, total, force = false, lastAt = fullLastAt)
            notifyProgress(id, task.fileName, new, total)
        }
        if (!ok) throw IllegalStateException(failReason.get() ?: "分片与单流下载均失败")
        finishDownload(id, chunkDir, listOf(fullFile), task.fileName, total, effectiveSaveDir(task))
    }

    /** 流式降级下载：总大小未知时单分片开放区间下载（Range: bytes=from-），读到 EOF */
    private suspend fun streamDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, 0)
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val partFile = File(chunkDir, "part_0")
        val downloaded = AtomicLong(partFile.length())
        val streamLastAt = AtomicLong(0L)
        val ok = downloader.downloadChunk(
            taskId = id,
            url = task.url,
            start = 0,
            end = Long.MAX_VALUE,
            partFile = partFile,
            headers = headers
        ) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            if (!isTaskActive()) return@downloadChunk
            // 大小未知：只更新已下载量（total=0 表示未知）
            persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            // 前台通知进度（2 秒节流，total 未知时仅更新标题）
            notifyProgress(id, task.fileName, new, 0)
        }
        if (ok != ChunkResult.OK) {
            // Range 被 CDN 拒绝（416/403）或忽略（200 整文件）：回退为无 Range 完整 GET
            Log.w(TAG, "streamDownload: id=$id Range 失败，回退完整 GET 下载")
            downloaded.set(0)
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0, 0)
            val ok2 = downloader.downloadFull(
                taskId = id,
                url = task.url,
                partFile = partFile,
                headers = headers
            ) { bytes ->
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return@downloadFull
                persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            }
            if (!ok2) throw IllegalStateException("下载失败（Range 与完整下载均失败）")
        }
        if (!isTaskActive()) return
        finishDownload(id, chunkDir, listOf(partFile), task.fileName, 0, effectiveSaveDir(task))
    }

    /** HLS（m3u8 转码流，如 UC play）下载：拉取分片合并 → 保存 → 完成回调 */
    private suspend fun hlsDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        // HLS 先写合并临时文件再拷贝到下载目录，同样需要至少 2 份空间（这里至少守住最低水位）
        DownloadCacheMaintenance.checkBeforeDownload(
            cacheDir = AppContext.cacheDir,
            saveDir = DownloadSaver.resolveSaveDir(effectiveSaveDir(task)),
            totalBytes = 0L
        )?.let { reason ->
            DownloadDebugLog.record(id, "空间不足", reason)
            throw DownloadCacheMaintenance.LowDiskSpaceException(reason)
        }
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val hlsFile = File(AppContext.mergeDir, "hls_$id")
        hlsFile.delete()
        val downloaded = AtomicLong(0)
        val hlsLastAt = AtomicLong(0L)
        val ok = HlsDownloader.download(task.url, headers, hlsFile) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            persistProgressIfDue(id, new, 0, force = false, lastAt = hlsLastAt)
            notifyProgress(id, task.fileName, new, 0)
        }
        if (!isTaskActive()) return
        if (!ok) {
            hlsFile.delete()
            throw IllegalStateException("HLS 转码流下载失败")
        }
        // Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            hlsFile.delete()
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        val savedPath = withContext(Dispatchers.IO) {
            DownloadSaver.save(task.fileName, hlsFile, effectiveSaveDir(task))
        }
            ?: throw IllegalStateException("保存到下载目录失败")
        val hlsTotal = dao.get(id)?.totalSize ?: 0L
        completeWithAvg(id, savedPath, hlsTotal)
        Log.d(TAG, "hlsDownload: id=$id 下载完成 savedPath=$savedPath size=${hlsFile.length()}")
        taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        _stats.update { it - id }
        hlsFile.delete()
    }

    // ---------- DASH 双轨合轨 / 动态照片合成 ----------

    /**
     * DASH 双轨（音视频分离）下载 + 合轨，产物是一个普通 MP4。
     *
     * ★ 与上游 Android 的差异：
     *   - Android 版用 `MediaExtractor` 抽轨样本、`MediaMuxer` 写标准 MP4；桌面 JVM 没有这两个类，
     *     改由 [DashMuxer]（自研分片解析 + 写 moov/mdat，纯 Java、无原生库）读 fragmented MP4
     *     的 moof/trun 并重建时间轴，同样只重封装、不重编码。
     *   - Android 版两路可分片并行；这里同样是**两轨并行 + 每轨多连接 Range 并发**
     *     （B 站 CDN 单连接通常只有 1~2 MB/s，串行单连接是「下载慢」的主因）。
     *     连接数取用户设置（按 VIDEO 平台）并封顶 [DASH_WORKERS_CAP]，每块至少 [DASH_CHUNK_MIN]。
     *     服务器忽略 Range 时自动回退单流整文件（[ChunkDownloader.downloadFull]）。
     *     ⇒ 本路径**不做分片级断点续传**：暂停/失败后临时文件一律丢弃，恢复时两路整文件重下。
     *
     * 进度：下载阶段占 0~90%（两路字节累加，总大小取两路之和），合轨阶段占 90~100%。
     * 收口：成功 = COMPLETED，失败 = FAILED + 具体原因，绝不停在「下载中」。
     * 失败**不再抛给 runTaskWithRetry**：合轨失败不是网络抖动，自动重试只会白下几十 MB；
     * 暂停/取消仍按 CancellationException 原样上抛（交给 pause 的状态处理）。
     */
    private suspend fun dashMergeDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        val mergeDir = AppContext.mergeDir.apply { mkdirs() }
        val videoFile = File(mergeDir, "dash_video_$id")
        val audioFile = File(mergeDir, "dash_audio_$id")
        val mergedFile = File(mergeDir, "dash_merged_$id")
        // 音轨头：内存表优先（入队/恢复时写入）；进程重启后从加密落库字段解回；都没有就不带头
        val audioHeaders = taskMergeAudioHeaders[id]
            ?: loadPersistedMergeAudioHeaders(task.mergeAudioHeadersJson)
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        try {
            // 视频轨 + 音轨 + 合轨产物会同时存在（≈2 份），先过统一的下载前空间守卫
            DownloadCacheMaintenance.checkBeforeDownload(
                cacheDir = AppContext.cacheDir,
                saveDir = DownloadSaver.resolveSaveDir(effectiveSaveDir(task)),
                totalBytes = 0L
            )?.let { reason ->
                DownloadDebugLog.record(id, "空间不足", reason)
                throw DownloadCacheMaintenance.LowDiskSpaceException(reason)
            }
            videoFile.delete()
            audioFile.delete()
            mergedFile.delete()
            val videoTotal = downloader.getTotalSize(task.url, headers) ?: 0L
            val audioTotal = downloader.getTotalSize(task.mergeAudioUrl, audioHeaders) ?: 0L
            val total = if (videoTotal > 0 && audioTotal > 0) videoTotal + audioTotal else 0L
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0, total)
            val downloaded = AtomicLong(0)
            val lastAt = AtomicLong(0L)
            // 两轨并行 + 每轨多连接：CDN 对单连接的限速是 B 站下载慢的主因
            // 线程数按任务所属视频平台取（旧任务的 platform 可能是 "video"，同样能取到兜底值）
            val workerPlatform = task.platform.ifBlank { DownloadPlatform.VIDEO }
            val workers = min(threadProvider(workerPlatform), DASH_WORKERS_CAP).coerceAtLeast(1)
            val speedRecorder = SpeedRecorder()
            // 下载阶段只映射到 0~90%：把最后 10% 留给合轨，否则合轨时进度会从 100% 往回跳
            fun reported(done: Long): Long = if (total > 0) minOf(done, total) * 9 / 10 else done
            suspend fun reportProgress(bytes: Long) {
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return
                persistProgressIfDue(id, reported(new), total, force = false, lastAt = lastAt)
                // 与普通下载同一套滑动窗口：不写 _stats 的话 B 站任务在界面上连速度都不显示
                speedRecorder.onBytes(new)?.let { speed ->
                    val remain = if (speed > 0 && total > new) (total - new) * 1000 / speed else -1L
                    _stats.update { it + (id to DownloadStats(speed, remain, workers)) }
                }
                notifyProgress(id, task.fileName, reported(new), total)
            }

            val parallel = videoTotal > 0 && audioTotal > 0 && videoTotal + audioTotal >= PARALLEL_MIN_BYTES
            val (videoOk, audioOk) = coroutineScope {
                val video = async {
                    downloadDashTrack(id, task.url, videoFile, headers, videoTotal, parallel, workers) { bytes ->
                        reportProgress(bytes)
                    }
                }
                val audio = async {
                    downloadDashTrack(id, task.mergeAudioUrl, audioFile, audioHeaders, audioTotal, parallel, workers) { bytes ->
                        reportProgress(bytes)
                    }
                }
                video.await() to audio.await()
            }
            if (!videoOk) throw IllegalStateException("视频轨下载失败（直链可能已过期）")
            if (!audioOk) throw IllegalStateException("音轨下载失败（直链可能已过期，或需要独立的 Cookie）")
            if (!isTaskActive()) return
            // 合轨阶段没有字节流动，速度归零（否则界面会一直显示上一个采样值）
            _stats.update { it + (id to DownloadStats(0L, -1L, workers)) }

            // 合轨阶段（90%~100%）：合轨的进度回调不是挂起函数，进度先落在 AtomicLong，
            // 由同一协程作用域里的 ticker 节流写库（回调里直接写库就得 runBlocking 阻塞 IO 线程）。
            val muxRatio = AtomicLong(0L)
            val muxLastAt = AtomicLong(0L)
            val onMuxRatio: (Float) -> Unit = { p ->
                muxRatio.set((p.coerceIn(0f, 1f) * 1000).toInt().toLong())
            }
            val muxOk = coroutineScope {
                val ticker = launch {
                    while (true) {
                        delay(300)
                        val done = if (total > 0) {
                            total * 9 / 10 + total / 10 * muxRatio.get() / 1000
                        } else {
                            0L
                        }
                        persistProgressIfDue(id, done, total, force = false, lastAt = muxLastAt)
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        // 合轨引擎：ffmpeg 为主（只重封装），缺引擎 / 失败时退回内置纯 Java 合轨。
                        val ffmpeg = FfplayManager.locateFfmpeg()
                        if (ffmpeg != null) {
                            Log.d(TAG, "dashMergeDownload: id=$id 使用 ffmpeg 合轨 ${ffmpeg.absolutePath}")
                            if (FfmpegMuxer.mux(videoFile, audioFile, mergedFile, onMuxRatio)) {
                                return@withContext true
                            }
                            Log.w(TAG, "dashMergeDownload: id=$id ffmpeg 合轨失败，退回内置 Java 合轨")
                        } else {
                            // 缺引擎：只提示不阻塞 —— 本次照常用内置 Java 合轨完成；
                            // 用户同意后后台下载引擎，之后的任务会自动切回 ffmpeg。
                            EngineInstallPrompt.ask(EngineInstallPrompt.Reason.MERGE)
                        }
                        DashMuxer.mux(videoFile, audioFile, mergedFile, onMuxRatio)
                    }
                } finally {
                    ticker.cancel()
                }
            }
            if (!muxOk) throw IllegalStateException("合轨失败（视频轨/音轨不是可解析的 MP4 分轨）")
            if (!isTaskActive()) return
            if (total > 0) dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, total, total)
            if (!storagePermissionProvider()) throw IllegalStateException("未授予存储权限，无法保存到下载目录")
            val savedPath = withContext(Dispatchers.IO) {
                DownloadSaver.save(task.fileName, mergedFile, effectiveSaveDir(task))
            } ?: throw IllegalStateException("保存到下载目录失败")
            val size = mergedFile.length().takeIf { it > 0 } ?: total
            completeWithAvg(id, savedPath, size)
            Log.d(TAG, "dashMergeDownload: id=$id 合轨完成 savedPath=$savedPath size=$size")
            DownloadDebugLog.record(
                id, "合轨完成",
                "视频=${videoFile.length()} 音轨=${audioFile.length()} 成品=$size 保存=$savedPath"
            )
            taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "dashMergeDownload: id=$id 失败：$reason")
            DownloadDebugLog.record(id, "合轨失败", reason)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, reason)
        } finally {
            // 临时文件一律清理：本路径不支持分片续传，半截文件下一轮也用不上
            runCatching { videoFile.delete() }
            runCatching { audioFile.delete() }
            runCatching { mergedFile.delete() }
            _stats.update { it - id }
        }
    }

    /**
     * DASH 单轨下载：能切块就「多连接 Range 并发」，否则/被 CDN 忽略 Range 时回退单流。
     *
     * 并发度与网络无关的部分都在这里收口：块数 = min(workers, total / [DASH_CHUNK_MIN])，
     * 每块至少 2MB —— 音轨常常只有 1~2MB，切块只会多一次握手。
     */
    private suspend fun downloadDashTrack(
        id: Long,
        url: String,
        target: File,
        headers: Map<String, String>,
        total: Long,
        parallel: Boolean,
        workers: Int,
        onBytes: suspend (Long) -> Unit
    ): Boolean {
        if (parallel && total > 0) {
            val ranged = downloadTrackByRanges(id, url, target, headers, total, workers, onBytes)
            if (ranged != null) return ranged
            Log.w(TAG, "dashMergeDownload: id=$id 服务器忽略 Range，回退单流下载")
        }
        return downloader.downloadFull(
            taskId = id,
            url = url,
            partFile = target,
            headers = headers,
            total = total,
            onBytes = onBytes
        )
    }

    /**
     * 把一个分轨按 [workers] 路并发写进 [target]（预分配长度）。
     *
     * ★ 分块是**动态队列**而不是「一人一块」：一次切出很多小块，谁先下完谁领下一块。
     *   一人一块时，跑得快的 worker 早早完工、慢的那条连接独自收尾 —— 这就是「越到后面越慢」。
     *   队列模式让并发一直保持到只剩最后几块。
     * ★ 同时接入「慢连接抢占」看门狗：CDN 会对单连接渐进限速，低于同伴的连接会被断开重连续传。
     *
     * 返回 true/false = 成功/失败；**null = 服务器忽略 Range**（调用方回退单流）。
     */
    private suspend fun downloadTrackByRanges(
        id: Long,
        url: String,
        target: File,
        headers: Map<String, String>,
        total: Long,
        workers: Int,
        onBytes: suspend (Long) -> Unit
    ): Boolean? {
        val chunkBytes = dashChunkBytes(total)
        val chunkCount = ceil(total.toDouble() / chunkBytes).toLong()
            .coerceIn(1L, DASH_MAX_CHUNKS.toLong()).toInt()
        // 均分一遍，避免最后一块特别小
        val perChunk = ceil(total.toDouble() / chunkCount).toLong()
        val workerCount = min(workers, chunkCount).coerceAtLeast(1)
        target.delete()
        val channel = FileChannel.open(
            target.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )
        try {
            channel.truncate(0)
            RandomAccessFile(target, "rw").use { it.setLength(total) }
            val nextIndex = AtomicInteger(0)
            val rangeIgnored = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val preempted = AtomicInteger(0)
            val moved = AtomicLong(0)
            val startedAt = System.currentTimeMillis()
            val inflight = ConcurrentHashMap<String, InflightChunk>()
            coroutineScope {
                // 看门狗：每 5s 采样一次每路瞬时速度，把明显跑不动的连接换掉（与主下载路径同一套阈值）
                val watchdog = launch {
                    while (isActive) {
                        delay(PREEMPT_TICK_MS)
                        if (inflight.isEmpty()) continue
                        sampleInflightChunks(inflight)
                        preemptSlowChunks(
                            id = id,
                            downloaded = moved.get(),
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            workers = workerCount,
                            diag = inflight
                        )
                    }
                }
                try {
                    List(workerCount) { workerIndex ->
                        async(DownloadManager.chunkIoDispatcher) {
                            while (true) {
                                if (rangeIgnored.get() > 0) break
                                val index = nextIndex.getAndIncrement()
                                if (index >= chunkCount) break
                                val start = index.toLong() * perChunk
                                val end = min(start + perChunk, total) - 1
                                val diagKey = "dash${index + 1}"
                                val diag = InflightChunk(start, end - start + 1, workerIndex)
                                inflight[diagKey] = diag
                                try {
                                    var attempt = 0
                                    while (true) {
                                        val result = try {
                                            inflightLimiter.withPermit {
                                                downloader.downloadRangeToChannel(
                                                    taskId = id,
                                                    url = url,
                                                    start = start,
                                                    end = end,
                                                    channel = channel,
                                                    headers = headers,
                                                    // 与看门狗 cancelChunkCall(id, d.start) 用同一个 key
                                                    chunkKey = start.toString(),
                                                    preempt = diag.preempt
                                                ) { bytes ->
                                                    diag.bytes.addAndGet(bytes)
                                                    moved.addAndGet(bytes)
                                                    onBytes(bytes)
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "dash track $id 分块${index + 1}/$chunkCount 异常: ${e.message}")
                                            ChunkResult.FAILED
                                        }
                                        if (result == ChunkResult.OK) break
                                        if (result == ChunkResult.RANGE_IGNORED) {
                                            rangeIgnored.incrementAndGet()
                                            break
                                        }
                                        attempt++
                                        if (attempt >= DASH_CHUNK_ATTEMPTS) {
                                            failed.incrementAndGet()
                                            break
                                        }
                                        delay(300L * attempt)
                                    }
                                } finally {
                                    inflight.remove(diagKey)
                                    if (diag.preemptCount > 0) preempted.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
                } finally {
                    watchdog.cancel()
                }
            }
            val ok = when {
                rangeIgnored.get() > 0 -> null
                failed.get() > 0 -> false
                else -> true
            }
            if (ok == true) {
                val elapsed = System.currentTimeMillis() - startedAt
                val avgBps = if (elapsed > 0) total * 1000 / elapsed else 0L
                val summary = "${target.name} 块=$chunkCount 路=$workerCount 用时=${elapsed / 1000}s " +
                    "平均=${diagSpeed(avgBps)}/${workerCount}路 换连接=${preempted.get()}"
                Log.d(TAG, "dash 分轨下载完成: id=$id $summary")
                DownloadDebugLog.record(id, "分轨下载完成", summary)
            }
            return ok
        } finally {
            runCatching { channel.close() }
            // 失败/回退的残片一律丢掉：单流下载从 0 开始，留着只会误导
            if (!target.isFile || target.length() != total) runCatching { target.delete() }
        }
    }

    /** DASH 分块大小：轨越大块越大（减少请求开销），收尾的并发衰减窗口约为「块大小 × 路数」 */
    private fun dashChunkBytes(total: Long): Long = when {
        total > 512L * 1024 * 1024 -> 8L * 1024 * 1024
        total > 128L * 1024 * 1024 -> 4L * 1024 * 1024
        else -> DASH_CHUNK_MIN
    }

    /**
     * 动态照片（Motion Photo）合成下载：静图 + 动图资源 → 一个「会动的照片」文件。
     *
     * ★ 与上游 Android 的差异：Android 端用 `BitmapFactory` 取尺寸、`Bitmap.compress` 兜底转 JPEG；
     *   桌面端由 [MotionPhotoWriter] 换成 `ImageIO`。字节层结构（JPEG 段 / XMP / OPPO Exif /
     *   尾部追加 MP4）完全一致，这里只负责「取料 →（必要时）重封装 → 合成 → 保存」。
     *
     * ★ 降级策略：动图只是加分项。动图下载/重封装/合成任一步失败都**不让任务失败**，
     *   而是把静图原样存成普通图片并标记完成（Log.w + 调试日志记录降级原因）。
     *   只有静图本身都下不下来时才报 FAILED —— 那时确实没有任何可保存的内容。
     */
    private suspend fun motionPhotoDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        val mergeDir = AppContext.mergeDir.apply { mkdirs() }
        val stillFile = File(mergeDir, "motion_still_$id")
        val videoFile = File(mergeDir, "motion_video_$id")
        val remuxedFile = File(mergeDir, "motion_video_$id.mp4")
        val outFile = File(mergeDir, "motion_out_$id")
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        try {
            DownloadCacheMaintenance.checkBeforeDownload(
                cacheDir = AppContext.cacheDir,
                saveDir = DownloadSaver.resolveSaveDir(effectiveSaveDir(task)),
                totalBytes = 0L
            )?.let { reason ->
                DownloadDebugLog.record(id, "空间不足", reason)
                throw DownloadCacheMaintenance.LowDiskSpaceException(reason)
            }
            listOf(stillFile, videoFile, remuxedFile, outFile).forEach { runCatching { it.delete() } }
            val stillTotal = downloader.getTotalSize(task.url, headers) ?: 0L
            val motionTotal = downloader.getTotalSize(task.motionUrl, headers) ?: 0L
            val total = if (stillTotal > 0 && motionTotal > 0) stillTotal + motionTotal else 0L
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0, total)
            val downloaded = AtomicLong(0)
            val lastAt = AtomicLong(0L)
            // 下载阶段占 0~90%，最后 10% 留给「重封装 + 合成」（这一段没有细粒度回调）
            fun reported(done: Long): Long = if (total > 0) minOf(done, total) * 9 / 10 else done
            suspend fun reportProgress(bytes: Long) {
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return
                persistProgressIfDue(id, reported(new), total, force = false, lastAt = lastAt)
                notifyProgress(id, task.fileName, reported(new), total)
            }

            val stillOk = downloader.downloadFull(
                taskId = id,
                url = task.url,
                partFile = stillFile,
                headers = headers,
                total = stillTotal
            ) { bytes -> reportProgress(bytes) }
            // 静图是唯一兜底内容：拿不到就没有任何可保存的东西，只能按失败收尾
            if (!stillOk) throw IllegalStateException("静图下载失败（直链可能已过期）")
            if (!isTaskActive()) return

            // 动图这一段失败不致命：记下原因，后面走降级
            val motionError: String? = try {
                val motionOk = downloader.downloadFull(
                    taskId = id,
                    url = task.motionUrl,
                    partFile = videoFile,
                    headers = headers,
                    total = motionTotal
                ) { bytes -> reportProgress(bytes) }
                if (!motionOk) throw IllegalStateException("动图资源下载失败（直链可能已过期）")
                if (!isTaskActive()) return
                // 微博的动图是 QuickTime（.mov）：先换标准 mp4 容器再内嵌；重封装失败就用原文件再试一次
                val motionInput = if (needsMp4Remux(videoFile, task.motionUrl)) {
                    val remuxed = withContext(Dispatchers.IO) { DashMuxer.remuxToMp4(videoFile, remuxedFile) }
                    if (remuxed) {
                        remuxedFile
                    } else {
                        Log.w(TAG, "motionPhotoDownload: id=$id 动图重封装失败，改用原文件继续尝试合成")
                        videoFile
                    }
                } else {
                    videoFile
                }
                val composed = withContext(Dispatchers.IO) {
                    MotionPhotoWriter.write(stillFile, motionInput, outFile)
                }
                if (!composed) throw IllegalStateException("静图与动图合成失败")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (!isTaskActive()) return
            if (total > 0) dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, total, total)
            if (!storagePermissionProvider()) throw IllegalStateException("未授予存储权限，无法保存到下载目录")

            val composeMissing = outFile.length() <= 0L
            val degradedReason = if (motionError != null || composeMissing) {
                motionError ?: "合成产物为空"
            } else {
                null
            }
            val source = if (degradedReason == null) outFile else stillFile
            if (degradedReason != null) {
                Log.w(TAG, "motionPhotoDownload: id=$id 动态照片合成失败，降级为静图：$degradedReason")
                DownloadDebugLog.record(id, "动图降级", "合成失败，改为保存静图：$degradedReason")
            }
            val savedPath = withContext(Dispatchers.IO) {
                DownloadSaver.save(task.fileName, source, effectiveSaveDir(task))
            } ?: throw IllegalStateException("保存到下载目录失败")
            val size = source.length()
            completeWithAvg(id, savedPath, size)
            Log.d(
                TAG, "motionPhotoDownload: id=$id 完成 savedPath=$savedPath size=$size " +
                    "degraded=${degradedReason != null}"
            )
            DownloadDebugLog.record(
                id, "保存完成",
                "文件名=${task.fileName} 大小=$size 降级=${degradedReason != null}"
            )
            taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "motionPhotoDownload: id=$id 失败：$reason")
            DownloadDebugLog.record(id, "动图任务失败", reason)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, reason)
        } finally {
            runCatching { stillFile.delete() }
            runCatching { videoFile.delete() }
            runCatching { remuxedFile.delete() }
            runCatching { outFile.delete() }
            _stats.update { it - id }
        }
    }

    /**
     * 动图资源是否需要先重封装成 MP4。
     *
     * 先看 URL 扩展名（微博是 `.mov`），再看文件头的 `ftyp` 品牌：QuickTime 的品牌是 `qt  `；
     * 读不出 `ftyp`（不是 ISO BMFF）时也按「需要重封装」处理（失败后调用方会用原文件重试）。
     */
    private fun needsMp4Remux(file: File, url: String): Boolean {
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext != "mp4" && ext != "m4v") return true
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(12)
                raf.readFully(head)
                if (String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return@runCatching true
                String(head, 8, 4, Charsets.US_ASCII) == "qt  "
            }
        }.getOrDefault(true)
    }

    // ---------- 实验性直写（缓存 0；目标目录 .yunx-*.part → 完成后改名） ----------

    private fun directFinalName(task: DownloadTaskEntity): String =
        DownloadPathPolicy.sanitize(task.fileName, "download_${System.currentTimeMillis()}")?.fileName
            ?: "download_${System.currentTimeMillis()}"

    /** 复用任务已记录的临时文件；没有则按当前保存目录新建 */
    private fun prepareDirectLocation(id: Long, task: DownloadTaskEntity): DownloadSaver.DirectPartLocation? {
        if (task.directPart.isNotBlank()) {
            val part = File(task.directPart)
            val dir = part.parentFile
            if (dir != null && dir.isDirectory) {
                return DownloadSaver.DirectPartLocation(part, dir, directFinalName(task))
            }
        }
        return DownloadSaver.prepareDirectPart(task.fileName, effectiveSaveDir(task), id)
    }

    /**
     * 单线程直写：顺序写目标目录的 `.part`，完成校验大小后改名。
     * 暂停/失败保留 `.part`，下次按文件长度续传；服务器忽略 Range 时用完整 GET 从头写。
     */
    private suspend fun runDirectSingle(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long?
    ) {
        val loc = prepareDirectLocation(id, task)
        if (loc == null) {
            // 目标目录不可建/临时文件路径过长：回退缓存分片（至少先把文件下下来，合并时再报目标目录问题）
            forceCacheMode.add(id)
            dao.updateDirect(id, "", 0)
            throw DirectWriteFallbackException("直写临时文件创建失败，已回退缓存分片模式")
        }
        val part = loc.partFile
        val destDir = part.parentFile ?: throw IllegalStateException("直写：目标目录不可用")
        // 安全：如果同名路径上残留了多线程直写的 .plan，说明它不是单线程顺序文件，
        // 不能拿“文件长度 == total”直接当成完成；清掉重下。
        if (DownloadSaver.directPlanFile(part).isFile) {
            DownloadSaver.deleteDirectPart(part.absolutePath)
        }
        // 新任务若同名临时文件已存在（数据库重置/任务 id 复用），先删掉，避免误续传
        if (task.directPart.isBlank() && part.exists()) part.delete()
        if (total != null && total > 0 && part.length() > total) part.delete()
        dao.updateDirect(id, part.absolutePath, 1)
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        try {
            val initial = if (total != null && total > 0) part.length().coerceAtMost(total) else part.length()
            if (!isTaskActive()) return
            if (total != null && total > 0 && initial >= total) {
                // 上次已下完但没来得及改名（例如提交前崩溃）
                commitDirectSingle(id, task, part, destDir, loc.finalName, total)
                return
            }
            val downloaded = AtomicLong(initial)
            val lastAt = AtomicLong(0L)
            val end = if (total != null && total > 0) total - 1 else Long.MAX_VALUE
            val onBytes: suspend (Long) -> Unit = { bytes ->
                speedLimiter.awaitAllow(bytes)
                val raw = downloaded.addAndGet(bytes)
                val new = if (total != null && total > 0) minOf(raw, total) else raw
                if (isTaskActive()) {
                    persistProgressIfDue(id, new, total ?: 0L, force = false, lastAt = lastAt)
                    notifyProgress(id, task.fileName, new, total ?: 0L)
                }
            }
            var result = downloader.downloadChunk(
                taskId = id,
                url = task.url,
                start = 0L,
                end = end,
                partFile = part,
                headers = headers,
                preempt = null,
                contentDigest = null,
                onBytes = onBytes
            )
            if (result == ChunkResult.RANGE_IGNORED) {
                // 服务器忽略 Range：完整 GET 从头写（downloadFull 会先截断旧内容）
                downloaded.set(0L)
                dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0L, total ?: 0L)
                val ok = downloader.downloadFull(
                    taskId = id,
                    url = task.url,
                    partFile = part,
                    headers = headers,
                    total = total ?: -1L,
                    onBytes = onBytes
                )
                result = if (ok) ChunkResult.OK else ChunkResult.FAILED
            }
            if (!isTaskActive()) return
            if (result != ChunkResult.OK) throw IllegalStateException("直写下载失败")
            if (total != null && total > 0) {
                val actual = part.length()
                if (actual != total) {
                    throw IllegalStateException(
                        "直写文件大小校验失败：期望 $total 字节，实际 $actual 字节（已保留临时文件可重试）"
                    )
                }
            }
            commitDirectSingle(id, task, part, destDir, loc.finalName, total ?: 0L)
        } finally {
            _stats.update { it - id }
        }
    }

    private suspend fun commitDirectSingle(
        id: Long,
        task: DownloadTaskEntity,
        part: File,
        destDir: File,
        finalName: String,
        total: Long
    ) {
        val savedPath = withContext(Dispatchers.IO) {
            DownloadSaver.commitDirectPart(part, destDir, finalName)
        } ?: throw IllegalStateException("直写：无法创建最终文件（目标目录不可用或文件名冲突）")
        dao.updateDirect(id, "", 0)
        completeWithAvg(id, savedPath, total)
        Log.d(TAG, "runDirectSingle: id=$id 直写完成 savedPath=$savedPath size=$total")
        DownloadDebugLog.record(id, "直写完成", "path=$savedPath size=$total")
        taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
    }

    /** 实验性：HLS 直写（HLS 无断点续传，失败/取消即删除临时文件） */
    private suspend fun hlsDownloadDirect(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>
    ) {
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        var directPath = task.directPart
        try {
            val loc = DownloadSaver.prepareDirectPart(task.fileName, effectiveSaveDir(task), id)
            if (loc == null) {
                forceCacheMode.add(id)
                throw DirectWriteFallbackException("直写临时文件创建失败，已回退缓存模式")
            }
            // HLS 不支持断点续传：每次从 0 开始
            DownloadSaver.deleteDirectPart(loc.partFile.absolutePath)
            directPath = loc.partFile.absolutePath
            dao.updateDirect(id, directPath, 1)
            val downloaded = AtomicLong(0L)
            val lastAt = AtomicLong(0L)
            val ok = HlsDownloader.download(task.url, headers, loc.partFile) { bytes ->
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                persistProgressIfDue(id, new, 0L, force = false, lastAt = lastAt)
                notifyProgress(id, task.fileName, new, 0L)
            }
            if (!isTaskActive()) throw CancellationException("下载被取消")
            if (!ok) throw IllegalStateException("HLS 转码流下载失败")
            val savedPath = withContext(Dispatchers.IO) {
                DownloadSaver.commitDirectPart(loc.partFile, loc.destDir, loc.finalName)
            } ?: throw IllegalStateException("直写：无法创建最终文件（目标目录不可用或文件名冲突）")
            dao.updateDirect(id, "", 0)
            val hlsTotal = dao.get(id)?.totalSize ?: 0L
            completeWithAvg(id, savedPath, hlsTotal)
            Log.d(TAG, "hlsDownloadDirect: id=$id 直写完成 savedPath=$savedPath")
            taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        } catch (e: CancellationException) {
            DownloadSaver.deleteDirectPart(directPath)
            dao.updateDirect(id, "", 0)
            throw e
        } catch (e: Exception) {
            DownloadSaver.deleteDirectPart(directPath)
            dao.updateDirect(id, "", 0)
            throw e
        } finally {
            _stats.update { it - id }
        }
    }

    /**
     * 多线程直写：预分配目标目录的单个 `.part`，各线程按字节偏移并行写。
     *
     * - `.plan` 记录 total / 分片数 / 分片大小；total 变化或计划不匹配即整文件重来；
     * - `.done` 只记录完整分片（index + SHA-256）；恢复时逐片重读校验，坏片/半片重下；
     * - 最终提交前再逐片读一遍校验，然后改名；整个过程不占缓存盘、峰值 1 份文件空间。
     */
    private suspend fun runDirectMulti(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long
    ) {
        if (total <= 0) throw IllegalStateException("多线程直写需要文件总大小")
        val loc = prepareDirectLocation(id, task)
        if (loc == null) {
            forceCacheMode.add(id)
            dao.updateDirect(id, "", 0)
            throw DirectWriteFallbackException("直写临时文件创建失败，已回退缓存分片模式")
        }
        val part = loc.partFile
        val destDir = part.parentFile ?: throw IllegalStateException("直写：目标目录不可用")
        val threadCount = threadsFor(task).coerceAtLeast(2)
        // 迅雷 CDN 对单文件并发 Range 有阈值（与缓存路径同一套限制），直写也必须钳到安全上限
        val isXunlei = headers["User-Agent"]?.contains("xunlei", ignoreCase = true) == true ||
            task.url.contains("xunlei", ignoreCase = true)
        val effectiveThreads = if (isXunlei) min(threadCount, RANGE_WORKERS_CAP) else threadCount
        val workers = min(effectiveThreads, MAX_INFLIGHT_CHUNKS).coerceAtLeast(1)
        // 先把临时文件路径落到 DB：即使预分配过程中进程崩溃，下次启动也能识别并续传/清理
        dao.updateDirect(id, part.absolutePath, 2)

        val existingPlan = withContext(Dispatchers.IO) { DirectPartManifest.readPlan(part) }
        val reusable = existingPlan?.takeIf { it.total == total && it.chunkCount > 0 && it.chunkSize > 0 }
        val chunkCount = reusable?.chunkCount ?: chunkCountFor(total, threadCount)
        val chunkSize = reusable?.chunkSize ?: ceil(total.toDouble() / chunkCount).toLong()
        val plan = DirectPartManifest.Plan(total, chunkCount, chunkSize)
        withContext(Dispatchers.IO) {
            if (reusable == null || !part.isFile) {
                DownloadSaver.deleteDirectPart(part.absolutePath)
                RandomAccessFile(part, "rw").use { it.setLength(total) }
                DirectPartManifest.writePlan(part, plan)
            } else {
                RandomAccessFile(part, "rw").use { if (it.length() != total) it.setLength(total) }
            }
        }
        _stats.update { it + (id to DownloadStats(0L, -1L, workers)) }
        try {
            // 恢复：只保留“清单里有记录且重读校验通过”的分片
            val valid = ConcurrentHashMap<Int, String>()
            val verified = AtomicLong(0L)
            withContext(Dispatchers.IO) {
                val done = DirectPartManifest.readDone(part)
                var lastPct = -1
                for ((idx, hash) in done) {
                    if (!isTaskActive()) throw CancellationException("下载被取消")
                    if (idx !in 0 until chunkCount) continue
                    val range = chunkRange(idx, chunkSize, total) ?: continue
                    val len = range.last - range.first + 1
                    val actual = DirectPartManifest.hashRange(part, range.first, len)
                    if (actual == hash) {
                        valid[idx] = hash
                        val now = verified.addAndGet(len)
                        val pct = (now * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            _stats.update { it + (id to DownloadStats(mergePercent = pct, chunkCount = workers, verifying = true)) }
                        }
                    }
                }
                if (valid.size != done.size) DirectPartManifest.rewriteDone(part, valid)
            }
            val initial = verified.get()
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, initial, total)
            _stats.update { it + (id to DownloadStats(0L, -1L, workers)) }

            val channel = FileChannel.open(
                part.toPath(),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            )
            val next = AtomicInteger(0)
            val downloaded = AtomicLong(initial)
            val failure = java.util.concurrent.atomic.AtomicReference<ChunkResult?>(null)
            try {
                coroutineScope {
                    val jobs = List(workers) {
                        async(chunkIoDispatcher) {
                            while (true) {
                                if (failure.get() != null) break
                                if (!isTaskActive()) throw CancellationException("下载被取消")
                                val idx = next.getAndIncrement()
                                if (idx >= chunkCount) break
                                if (valid.containsKey(idx)) continue
                                val range = chunkRange(idx, chunkSize, total) ?: break
                                val start = range.first
                                val end = range.last
                                val digest = MessageDigest.getInstance("SHA-256")
                                val lastAt = AtomicLong(0L)
                                val res = inflightLimiter.withPermit {
                                    downloader.downloadRangeToChannel(
                                        taskId = id,
                                        url = task.url,
                                        start = start,
                                        end = end,
                                        channel = channel,
                                        headers = headers,
                                        preempt = null,
                                        contentDigest = digest
                                    ) { bytes ->
                                        speedLimiter.awaitAllow(bytes)
                                        val raw = downloaded.addAndGet(bytes)
                                        val new = minOf(raw, total)
                                        if (isTaskActive()) {
                                            persistProgressIfDue(id, new, total, force = false, lastAt = lastAt)
                                            notifyProgress(id, task.fileName, new, total)
                                        }
                                    }
                                }
                                if (res == ChunkResult.OK) {
                                    val hex = digest.digest().toHexDigest()
                                    DirectPartManifest.appendDone(part, idx, hex)
                                    valid[idx] = hex
                                } else {
                                    failure.compareAndSet(null, res)
                                    break
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } finally {
                runCatching { channel.force(false) }
                runCatching { channel.close() }
            }

            val fail = failure.get()
            if (fail == ChunkResult.RANGE_IGNORED) {
                // 服务器不支持 Range：优先改单线程直写（完整 GET），否则回退缓存分片
                if (directWriteSingleProvider()) {
                    DownloadSaver.deleteDirectPart(part.absolutePath)
                    dao.updateDirect(id, "", 0)
                    runDirectSingle(id, task.copy(directPart = "", directMode = 0), headers, total)
                    return
                }
                forceCacheMode.add(id)
                DownloadSaver.deleteDirectPart(part.absolutePath)
                dao.updateDirect(id, "", 0)
                throw DirectWriteFallbackException("服务器不支持 Range，已回退缓存分片模式")
            }
            if (fail != null) throw IllegalStateException("直写分片下载失败（${fail.name}）")
            if (valid.size != chunkCount) {
                throw IllegalStateException("直写分片未全部完成（${valid.size}/$chunkCount）")
            }

            // 提交前逐片重读校验（与缓存模式合并时逐片校验等价）
            withContext(Dispatchers.IO) {
                var verifyDone = 0L
                var verifyPct = -1
                for (i in 0 until chunkCount) {
                    if (!isTaskActive()) throw CancellationException("下载被取消")
                    val range = chunkRange(i, chunkSize, total)
                        ?: throw IllegalStateException("直写分片区间异常：$i")
                    val expected = valid[i] ?: throw IllegalStateException("直写分片缺少校验记录：$i")
                    val len = range.last - range.first + 1
                    val actual = DirectPartManifest.hashRange(part, range.first, len)
                        ?: throw IllegalStateException("直写文件读取失败（分片 $i）")
                    if (actual != expected) {
                        throw IllegalStateException("直写文件校验失败（分片 $i，已保留临时文件可重试）")
                    }
                    verifyDone += len
                    val pct = (verifyDone * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != verifyPct) {
                        verifyPct = pct
                        _stats.update { it + (id to DownloadStats(mergePercent = pct, chunkCount = workers, verifying = true)) }
                    }
                }
                if (part.length() != total) {
                    throw IllegalStateException("直写文件大小校验失败：期望 $total 字节，实际 ${part.length()} 字节")
                }
            }
            val savedPath = withContext(Dispatchers.IO) {
                DownloadSaver.commitDirectPart(part, destDir, loc.finalName)
            } ?: throw IllegalStateException("直写：无法创建最终文件（目标目录不可用或文件名冲突）")
            dao.updateDirect(id, "", 0)
            completeWithAvg(id, savedPath, total)
            Log.d(TAG, "runDirectMulti: id=$id 直写完成 savedPath=$savedPath size=$total chunks=$chunkCount")
            DownloadDebugLog.record(id, "直写完成", "path=$savedPath size=$total chunks=$chunkCount")
            taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        } finally {
            _stats.update { it - id }
        }
    }

    private fun chunkRange(index: Int, chunkSize: Long, total: Long): LongRange? {
        val start = index.toLong() * chunkSize
        if (start >= total) return null
        val end = min(start + chunkSize - 1, total - 1)
        return start..end
    }

    /**
     * 分片流式合并 → **直接写入最终保存位置** → 校验通过后清空分片目录。
     *
     * ★ 旧实现先合并到私有缓存 `merge/merged_$id` 再复制到目标目录，峰值占用 3 份
     *   （分片 + 合并副本 + 目标副本），大文件在磁盘吃紧时 ENOSPC，且多一次全量拷贝；
     *   现直接写向最终目标，一次成型。
     * ★ 合并期间**不删除分片**（见 [ChunkDownloader.mergeChunksToStream]）：合并失败/暂停时
     *   分片仍在，重试只是重新合并一遍；若边写边删，失败后就只能整文件重下 ——
     *   这正是「进度到 100% 卡住、随后自动重新下载」的成因。峰值磁盘占用因此 ≈ 2 份。
     * ★ 完整性校验：分片非空 + 写入字节 == total，任一不符即 abort 半成品并抛错，绝不保存损坏文件。
     */
    private suspend fun finishDownload(
        id: Long,
        chunkDir: File,
        chunkFiles: List<File>,
        fileName: String,
        total: Long,
        /** 本次任务的保存目录（已合并任务级「下载到…」覆盖与全局设置）；null = 系统默认下载目录 */
        saveDir: String?
    ) {
        if (!isTaskActive()) return
        // 分片级校验：下载时记录的分片摘要（合并时逐片重算比对；流式/单流/HLS 路径没有记录，跳过校验）
        val expectedDigests = taskPartDigests[id].orEmpty()
        // 1) 分片完整性
        for (part in chunkFiles) {
            if (!part.exists() || part.length() <= 0) {
                Log.e(TAG, "finishDownload: id=$id 分片缺失/为空 $part")
                throw IllegalStateException("分片文件缺失或为空，拒绝合并（防止文件损坏）")
            }
        }
        // 下载调试：合并顺序 + 完整性与大小校验（校验口径 = 每片非空 + 每片字节数之和，见下方 written == total）
        if (DownloadDebugLog.isEnabled()) {
            val orderText = chunkFiles.take(8).joinToString(",") { "${it.name}=${it.length()}B" } +
                if (chunkFiles.size > 8) "…共${chunkFiles.size}片" else ""
            DownloadDebugLog.record(
                id, "分片校验",
                "合并=${chunkFiles.size}片 非空=通过 摘要=已记录${expectedDigests.size}片（合并时逐片比对） 顺序=$orderText " +
                    "字节合计=${chunkFiles.sumOf { it.length() }} 预期=${if (total > 0) total else "未知"}"
            )
        }
        // 2) 保存前权限检查（桌面版恒为 true，保留扩展点）
        if (!storagePermissionProvider()) {
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        // 3) 流式写入最终位置：边合并边删分片，不再产生中间合并副本
        // ★ 同步阻塞写入必须切 IO 线程：任务跑在 Dispatchers.Default（CPU 池），
        //   大文件写盘若占满 Default 线程会让整个下载器协程饿死（"100% 卡死保存不了"）
        // 合并阶段单独上报进度（界面显示「合并中 n%」）：大文件合并要几十秒，
        // 一直停在 100% 不动会让用户以为卡死。进度只走内存态 stats、不写 DB ——
        // 进程退出时合并本就中断，库里不需要再多一个会卡住的状态。
        val mergeTotal = if (total > 0) total else chunkFiles.sumOf { it.length() }
        var mergeLastPercent = -1
        var mergeLastAtMs = 0L
        fun reportMergeProgress(done: Long) {
            if (mergeTotal <= 0) return
            val percent = (done * 100 / mergeTotal).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            if (percent == mergeLastPercent && now - mergeLastAtMs < mergeReportIntervalMs) return
            mergeLastPercent = percent
            mergeLastAtMs = now
            _stats.update { it + (id to DownloadStats(mergePercent = percent)) }
            // 诊断日志：合并回调很密，按 10% 采样（高频日志必须降频，见 DiagnosticLog 的限流说明）
            if (percent % 10 == 0) {
                DiagnosticLog.log(
                    DiagnosticLog.DOWNLOAD, "merge_progress", taskId = id, status = "merging",
                    size = done, summary = "percent=$percent%"
                )
            }
        }
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "merge_start", taskId = id, status = "merging", size = mergeTotal,
            summary = "分片数=${chunkFiles.size} 目标=$fileName"
        )
        DownloadDebugLog.record(id, "开始合并", "分片数=${chunkFiles.size} 目标=$fileName 合计=$mergeTotal")
        reportMergeProgress(0L)
        // 合并前再确认一次下载盘空间：分片还占着缓存，目标位置需要完整一份文件的空间。
        // 不足时保留分片并给出原因，用户清理磁盘后可继续（不会生成半截文件）。
        // 多个任务可能同时进入合并阶段，这里把其它正在合并的任务所需空间一起算上。
        val destSpaceError = synchronized(spaceGuardLock) {
            val reserved = mergingDestBytes.entries
                .filter { it.key != id }
                .sumOf { it.value }
            DownloadCacheMaintenance.checkBeforeMerge(
                DownloadSaver.resolveSaveDir(saveDir),
                mergeTotal,
                reservedBytes = reserved
            ).also { reason ->
                if (reason == null) mergingDestBytes[id] = mergeTotal
            }
        }
        if (destSpaceError != null) {
            DownloadDebugLog.record(id, "空间不足", destSpaceError)
            throw DownloadCacheMaintenance.LowDiskSpaceException(destSpaceError)
        }
        // 全量 SHA-256（设置项，默认关）：合并这一遍本来就在读所有字节，顺带算掉即可，不额外读盘。
        // ★ 只在「下载调试」开着时才算：摘要只写进调试日志，调试关着算它没有任何去处（设置页也把该开关藏了）
        val fullShaEnabled = DownloadDebugLog.isEnabled() &&
            runCatching { com.yunx.app.data.prefs.SettingsRepository().fullFileSha256 }.getOrDefault(false)
        val savedPath = try {
            withContext(Dispatchers.IO) {
            val dest = DownloadSaver.openDestination(fileName, saveDir)
                ?: throw IllegalStateException("无法创建下载目标（下载目录不可用）")
            try {
                val out = dest.open() ?: throw IllegalStateException("无法打开下载目标输出流")
                val fullDigest = if (fullShaEnabled) MessageDigest.getInstance("SHA-256") else null
                val sink = if (fullDigest != null) DigestOutputStream(out, fullDigest) else out
                val written = sink.use {
                    downloader.mergeChunksToStream(chunkFiles, it, ::reportMergeProgress) { part, hex ->
                        // ★ 分片级校验：重算该片摘要，与下载时记录的对不上 ⇒ 内容级损坏，拒绝保存
                        val expected = expectedDigests[part.name]
                        if (expected != null && expected != hex) {
                            Log.e(TAG, "finishDownload: id=$id 分片校验失败 ${part.name} 期望=$expected 实际=$hex")
                            DownloadDebugLog.record(
                                id, "分片校验失败",
                                "${part.name} 内容与下载时不一致（期望 $expected 实际 $hex）"
                            )
                            throw IllegalStateException(
                                "分片 ${part.name} 校验失败：内容与下载时不一致（已拒绝保存损坏文件）"
                            )
                        }
                    }
                }
                if (fullDigest != null) {
                    DownloadDebugLog.record(
                        id, "全量校验",
                        "SHA-256=${fullDigest.digest().toHexDigest()} 共 $written 字节"
                    )
                }
                if (total > 0 && written != total) {
                    DownloadDebugLog.record(
                        id, "校验失败",
                        "期望 $total 字节，实际 $written 字节（拒绝保存损坏文件）"
                    )
                    throw IllegalStateException("文件大小校验失败：期望 $total 字节，实际 $written 字节（已拒绝保存损坏文件）")
                }
                dest.commit()
                dest.path
            } catch (e: Exception) {
                // 失败/取消：删掉半成品目标文件；★ 分片一律保留（mergeChunksToStream 不再边写边删），
                // 于是重试只需重新合并一遍，绝不会退化成「从头重下整个文件」
                dest.abort()
                DownloadDebugLog.record(
                    id, "合并失败",
                    "${e.message ?: e.javaClass.simpleName}（已删除半成品，分片保留可重试）"
                )
                // ★ 清掉内存里的「合并中」进度：否则任务失败后卡片会一直停在「合并中 100%」，看着像卡死
                _stats.update { it + (id to DownloadStats()) }
                throw e
            }
            }
        } finally {
            // 合并写入结束（成功或失败）：释放目标盘的预留名额
            synchronized(spaceGuardLock) { mergingDestBytes.remove(id) }
        }
        completeWithAvg(id, savedPath, total)
        DiagnosticLog.log(
            DiagnosticLog.DOWNLOAD, "task_complete", taskId = id, status = "completed", size = total,
            summary = "合并完成 path=$savedPath 分片数=${chunkFiles.size}"
        )
        Log.d(TAG, "finishDownload: id=$id 下载完成 savedPath=$savedPath size=$total")
        taskCallbacks.remove(id)?.let { cb ->
            runCatching { cb() }
        }
        _stats.update { it - id }
        chunkDir.deleteRecursively()
        DownloadDebugLog.record(
            id, "清理临时文件",
            "删除分片目录 ${chunkDir.name}（${chunkFiles.size} 片）"
        )
    }

    /**
     * 速度采样器：取近 [WINDOW_MS] 秒滑动窗口的平均速度，平滑多线程下载的速度波动。
     * 多线程并发下瞬时速率波动大，短窗口估算剩余时长会剧烈跳动；
     * 改用 5 秒窗口均值后，剩余时长更稳定可靠。
     */
    private class SpeedRecorder {
        private data class Sample(val timeMs: Long, val bytes: Long)

        private val samples = ArrayDeque<Sample>()
        private var lastEmit = 0L

        @Synchronized
        fun onBytes(total: Long): Long? {
            val now = System.currentTimeMillis()
            samples.addLast(Sample(now, total))
            // 剔除窗口外的旧样本，但始终保留至少 2 个（下载起步阶段窗口尚短）
            while (samples.size > 2 && now - samples.first().timeMs > WINDOW_MS) {
                samples.removeFirst()
            }
            // 250ms 发射一次，避免高频刷新 UI/通知
            if (now - lastEmit < 250) return null
            val first = samples.first()
            val elapsed = now - first.timeMs
            val speed = if (elapsed > 0) {
                ((total - first.bytes) * 1000 / elapsed).coerceAtLeast(0)
            } else 0L
            lastEmit = now
            return speed
        }

        private companion object {
            const val WINDOW_MS = 5000L
        }
    }

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒；0 = 不限速 */
    private inner class SpeedLimiter {
        @Volatile
        private var tokens = 0L
        @Volatile
        private var lastRefillNanos = System.nanoTime()

        @Synchronized
        private fun refill(limit: Long) {
            val now = System.nanoTime()
            val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0)
            lastRefillNanos = now
            tokens = minOf(limit, tokens + (elapsedSec * limit).toLong())
        }

        /** 消耗 bytes 字节额度；不足则挂起等待（限速生效） */
        suspend fun awaitAllow(bytes: Long) {
            val limit = speedLimitProvider().coerceAtLeast(0L)
            if (limit <= 0L) return
            while (true) {
                val waitMs = synchronized(this) {
                    refill(limit)
                    if (bytes <= tokens) {
                        tokens -= bytes
                        return
                    }
                    ((bytes - tokens) * 1000 / limit).coerceIn(1L, 200L)
                }
                // 锁外挂起等待，避免持锁阻塞其他任务
                delay(waitMs)
            }
        }
    }

    /** 下载临时文件缓存根目录：外部缓存（/storage/emulated/0/Android/data/com.yunx.app/cache），
     *  与最终保存目录解耦，系统可自动清理；外部存储不可用时回退内部缓存目录。 */
    private fun cacheBase(): File = AppContext.downloadTmpDir

    /** 分片临时文件目录：cacheBase()/download_tmp/$id */
    private fun chunkDirOf(id: Long): File = File(cacheBase(), "download_tmp/$id")

    /** 分片数规划（任务池模型）：分片数 = 线程数 × 8，远多于并发线程数。
     *  worker 循环领取盈余块，任一分片慢时其他线程继续领新片，根治"尾部并发塌缩"；
     *  保留 256KB 单片下限（小文件也能切出数倍于线程数的盈余片，同时避免过多小片）与 512 封顶。 */
    private fun chunkCountFor(total: Long, threads: Int): Int {
        if (total <= 0) return 1
        // ★ 单片下限 1MB → 256KB：1MB 会把小文件（如 33.7MB）的分片数夹到 ≈ 线程数，失去盈余；
        //   低单连接速率（如夸克 10KB/s）下每个 1MB 重片耗时约 100s，尾部极易空转。
        //   降到 256KB 后同样文件能切出 4~8 倍于线程数的片，工作窃取把尾部空转压到最后一个 256KB。
        //   大文件仍受 want / 512 封顶约束，单片自然变大，吞吐不受影响。
        val minChunkBytes = 256 * 1024L
        val bySize = when {
            total < 5 * 1024 * 1024 -> 1          // < 5MB 不分片
            total < 50 * 1024 * 1024 -> 8         // < 50MB
            total < 500 * 1024 * 1024 -> 32       // < 500MB
            else -> 64                            // ≥ 500MB 基础值
        }
        // 任务池：每线程平均领 8 片，天然抗慢片拖尾（比 1:1 映射多 8 倍盈余）
        val want = maxOf(bySize, threads * 8)
        return minOf(want, (total / minChunkBytes).toInt().coerceAtLeast(1), 512)
    }

    companion object {
        /**
         * 读缓冲预算占最大堆的比例：取 1/8。
         * 下载客户端固定 HTTP/1.1（无 HTTP/2 每流 16MB 窗口）后，每路在飞只占一份 64KB 读缓冲，
         * 同样的堆预算可以安全地多放路数。
         */
        private const val BUFFER_BUDGET_DIVISOR = 8

        /** 在飞分片下限：低于 8 路会让慢 CDN 明显掉速 */
        private const val INFLIGHT_MIN = 8

        /** 在飞分片硬上限：512（与设置页最高档位一致 ⇒ 用户选 512 就是真的 512 路） */
        private const val INFLIGHT_MAX = 512

        /**
         * 按堆预算推导「全进程在飞分片上限」：maxHeap / 8 / 单路读缓冲大小，夹在 [8, 512]。
         * 纯函数（无副作用），便于核对边界。
         */
        internal fun inflightChunksFor(maxHeapBytes: Long, bufferSize: Int = BUFFER_SIZE): Int =
            (maxHeapBytes / BUFFER_BUDGET_DIVISOR / bufferSize.coerceAtLeast(1))
                .toInt()
                .coerceIn(INFLIGHT_MIN, INFLIGHT_MAX)

        /**
         * 全进程在飞分片上限（进程启动时按最大堆算一次）。
         * 由 `inflightLimiter` 用作信号量容量：无论用户怎么调线程数、同时开几个任务，
         * 同时在飞的下载请求数都不会超过它。
         *
         * ★ 缓冲大小取自运行时设置（「设置 → 实验性功能」允许把读缓冲调到远超默认的 64KB）：
         *   缓冲越大、在飞路数上限越低，**总缓冲内存始终被压在堆预算的 1/[BUFFER_BUDGET_DIVISOR] 以内**，
         *   不会因为用户把缓冲调到 1MB/4MB 就把内存撑爆。默认 64KB 时结果与改动前完全一致。
         */
        val MAX_INFLIGHT_CHUNKS: Int =
            inflightChunksFor(Runtime.getRuntime().maxMemory(), DownloadTuning.bufferSize)

        /**
         * 分片阻塞 IO 的专用线程池（进程级），worker 与 [ChunkDownloader] 内部的 `withContext` 都跑在它上面。
         *
         * 为什么不能直接用 `Dispatchers.IO`：它的并行度被钉在 `max(64, 核数)`，超出的 worker 只能在队列里
         * 干等 —— 于是「设置里 256/512 线程」永远只跑得出 64 路。线程**按需创建**（最多 [MAX_INFLIGHT_CHUNKS]
         * 条）、空闲 30 秒回收，只有真用到大并发时才会存在那么多线程。
         * 必须 core = max 而不是 core = 0 + 无界队列：后者在 ThreadPoolExecutor 里只会养出 1 个 worker。
         */
        /** 分片 IO 线程池本体：保留引用是为了让「下载调试」能读到队列长度 / 活跃线程数等运行时状态 */
        private val chunkIoExecutor = ThreadPoolExecutor(
            MAX_INFLIGHT_CHUNKS,
            MAX_INFLIGHT_CHUNKS,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>()
        ) { r -> Thread(r, "yunx-chunk-io").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }

        internal val chunkIoDispatcher: CoroutineDispatcher = chunkIoExecutor.asCoroutineDispatcher()
    }
}
