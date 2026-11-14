/*
 * Adapted from YunX Android UploadRunner, Copyright (C) 2026 CYQawa.
 * Licensed under the GNU Affero General Public License, version 3 or later.
 */
package com.yunx.app.data.upload

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import okhttp3.OkHttpClient
import java.io.File
import java.util.ArrayDeque
import kotlin.math.roundToLong

data class UploadProgress(
    val index: Int,
    val count: Int,
    val name: String,
    val sent: Long,
    val size: Long,
    val phase: UploadPhase,
    val cancelling: Boolean = false,
    val bytesPerSecond: Double? = null,
    val platform: String = "",
    val files: List<UploadFileProgress> = emptyList(),
    val completed: Int = 0,
    val activeCount: Int = 0,
    val batchId: Long = 0L,
    val canAppend: Boolean = true,
    val concurrencyLimit: Int = SettingsRepository.DEFAULT_MAX_CONCURRENT_UPLOADS,
    val finished: Boolean = false,
    val canRetry: Boolean = false,
    val error: String? = null,
    /** 批次内仍有未知大小的文件时，不能用部分总大小计算完成率。 */
    val sizeKnown: Boolean = size > 0L
) {
    val fraction: Float get() = if (!sizeKnown || size <= 0) 0f else (sent.toDouble() / size).toFloat().coerceIn(0f, 1f)
    val remainingSeconds: Long? get() {
        if (finished || cancelling || !sizeKnown) return null
        val remaining = if (files.isEmpty()) (size - sent).coerceAtLeast(0L) else files.sumOf { file ->
            if (file.isActive || file.status == UploadFileStatus.WAITING) (file.candidate.size - file.sent).coerceAtLeast(0L) else 0L
        }
        if (remaining == 0L) return 0L
        return bytesPerSecond?.takeIf { it > 0.0 && it.isFinite() }?.let { (remaining / it).roundToLong() }
    }
    val failedCount: Int get() = files.count { it.status == UploadFileStatus.FAILED }
    val cancelledCount: Int get() = files.count { it.status == UploadFileStatus.CANCELLED }
}

data class UploadOutcome(
    val uploaded: Int,
    val failed: Int,
    val cancelled: Boolean,
    val error: String?,
    val notStarted: Int = 0,
    val cancelledFiles: Int = 0
) {
    fun message(): String = buildString {
        append(if (cancelled) "上传已取消" else if (failed > 0 || error != null) "上传结束，存在失败" else "上传完成")
        append("：成功 ").append(uploaded).append(" 个")
        if (failed > 0) append("，失败 ").append(failed).append(" 个")
        if (cancelledFiles > 0) append("，取消 ").append(cancelledFiles).append(" 个")
        if (notStarted > 0) {
            if (cancelledFiles > 0) append("（其中 ").append(notStarted).append(" 个未开始）")
            else append("，未开始 ").append(notStarted).append(" 个")
        }
        if (!error.isNullOrBlank()) append("。 ").append(error)
    }
}

/** 每个平台一个批次，文件并发数跟随设置，追加文件沿用批次的目录与账号。 */
class UploadRunner(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OkHttpClient,
    private val onFinished: (UploadOutcome) -> Unit
) {
    var progress by mutableStateOf<UploadProgress?>(null)
        private set
    var lastResult by mutableStateOf<UploadProgress?>(null)
        private set
    private var retryAction: ((List<UploadCandidate>) -> Unit)? = null
    private var onActiveRetry: (() -> Unit)? = null
    private var retrySingleAction: ((UploadCandidate) -> Unit)? = null

    private class Item(candidate: UploadCandidate) {
        val file: File get() = progress.candidate.file
        var progress = UploadFileProgress(candidate)
        var io: UploadIo? = null
        var running = false
        var lastEmit = 0L
        var speedSampleTime = 0L
        var speedSampleSent = 0L
        var lastGrowthTime = 0L
    }

    private val lock = Any()
    private val queue = mutableListOf<Item>()
    private var queueSignal: Channel<Unit>? = null
    private var acceptingFiles = false
    private var batchError: String? = null
    private var batchId = 0L
    @Volatile private var cancelRequested = false
    private var job: Job? = null
    private var uploadPlatform = ""
    private var concurrencyLimit = SettingsRepository.DEFAULT_MAX_CONCURRENT_UPLOADS
    private var lastPublishTime = 0L
    private val batchSpeedSamples = ArrayDeque<Pair<Long, Long>>()
    private var batchTransferredBytes = 0L
    private var lastBatchSpeedSampleTime = 0L
    private var batchBytesPerSecond = 0.0
    val isRunning: Boolean get() = progress != null

    /** 显式重排失败项，沿用原账号及目录；成功、取消和正在清理的项目不重传。 */
    fun retryFailed(expectedBatchId: Long): Int {
        val (files, action) = synchronized(lock) {
            if (isRunning) {
                if (expectedBatchId != batchId || !acceptingFiles || cancelRequested || batchError != null) return -1
                val failed = queue.filter { it.progress.status == UploadFileStatus.FAILED && !it.running }
                if (failed.isEmpty()) return 0
                onActiveRetry?.invoke()
                failed.forEach(::resetForRetry)
                publishLocked()
                queueSignal?.trySend(Unit)
                return failed.size
            }
            val result = lastResult ?: return -1
            val retry = retryAction
            if (isRunning || result.batchId != expectedBatchId || !result.canRetry || retry == null) return -1
            result.files.filter { it.status == UploadFileStatus.FAILED }.map { it.candidate } to retry
        }
        action(files)
        return files.size
    }

    /** 只重排指定失败项；成功、取消、仍在运行或账号已变化的项目不会被重排。 */
    fun retrySingle(expectedBatchId: Long, candidate: UploadCandidate): Int {
        val path = candidate.file.toPath().toAbsolutePath().normalize()
        val (action, next) = synchronized(lock) {
            if (isRunning) {
                if (expectedBatchId != batchId || !acceptingFiles || cancelRequested || batchError != null) return -1
                val item = queue.firstOrNull {
                    it.progress.status == UploadFileStatus.FAILED && !it.running &&
                        it.file.toPath().toAbsolutePath().normalize() == path
                } ?: return 0
                onActiveRetry?.invoke()
                resetForRetry(item)
                publishLocked()
                queueSignal?.trySend(Unit)
                return 1
            }
            val result = lastResult ?: return -1
            val action = retrySingleAction ?: return -1
            if (result.batchId != expectedBatchId || !result.canRetry) return -1
            val item = result.files.firstOrNull {
                it.status == UploadFileStatus.FAILED &&
                    it.candidate.file.toPath().toAbsolutePath().normalize() == path
            } ?: return 0
            val remaining = result.files.filterNot { it === item }
            lastResult = result.copy(
                files = remaining,
                canRetry = remaining.any { it.status == UploadFileStatus.FAILED }
            )
            action to item.candidate
        }
        action(next)
        return 1
    }

    private fun resetForRetry(item: Item) {
        item.progress = item.progress.copy(
            status = UploadFileStatus.WAITING,
            sent = 0L,
            bytesPerSecond = null,
            error = null,
            stalled = false
        )
        item.lastEmit = 0L
        item.speedSampleTime = 0L
        item.speedSampleSent = 0L
        item.lastGrowthTime = 0L
    }

    fun dismissResult(expectedBatchId: Long) {
        synchronized(lock) {
            if (lastResult?.batchId == expectedBatchId) {
                lastResult = null
                retryAction = null
                retrySingleAction = null
            }
        }
    }

    fun cancel() {
        if (!isRunning) return
        synchronized(lock) {
            cancelRequested = true
            acceptingFiles = false
            publishLocked()
        }
        cancelActive()
        job?.cancel()
    }

    /** 返回新增数量；-1 表示原批次已停止接收，不能把迟到的文件加进下一批。 */
    fun append(files: List<File>, expectedBatchId: Long): Int =
        appendCandidates(files.map { UploadCandidate(it, it.length().coerceAtLeast(0L)) }, expectedBatchId)

    fun appendCandidates(files: List<UploadCandidate>, expectedBatchId: Long): Int = synchronized(lock) {
        if (!acceptingFiles || cancelRequested || batchError != null || expectedBatchId != batchId) return@synchronized -1
        val known = queue.mapTo(mutableSetOf()) { it.file.toPath().toAbsolutePath().normalize() }
        val added = files.filter { known.add(it.file.toPath().toAbsolutePath().normalize()) }
        queue.addAll(added.map(::Item))
        publishLocked()
        queueSignal?.trySend(Unit)
        added.size
    }

    fun start(
        files: List<File>,
        platform: String,
        accountIdProvider: suspend () -> String? = { UploadSessions.primaryAccountId(platform) },
        upload: suspend (UploadIo, UploadSource) -> Unit
    ) = startCandidates(files.map { UploadCandidate(it, it.length().coerceAtLeast(0L)) }, platform, accountIdProvider, upload = upload)

    fun startCandidates(
        files: List<UploadCandidate>,
        platform: String,
        accountIdProvider: suspend () -> String? = { UploadSessions.primaryAccountId(platform) },
        onRetry: () -> Unit = {},
        upload: suspend (UploadIo, UploadSource) -> Unit
    ) {
        if (isRunning || files.isEmpty()) return
        if (!UploadSessions.acquire(platform, ::cancel)) {
            onFinished(UploadOutcome(0, 0, false, "该网盘已有上传任务", files.size))
            return
        }
        val signal = Channel<Unit>(Channel.CONFLATED)
        synchronized(lock) {
            cancelRequested = false
            uploadPlatform = platform
            concurrencyLimit = SettingsRepository.uploadConcurrency.value
            batchId++
            batchError = null
            lastResult = null
            retryAction = null
            retrySingleAction = null
            onActiveRetry = onRetry
            acceptingFiles = true
            queueSignal = signal
            queue.clear()
            queue.addAll(files.distinctBy { it.file.toPath().toAbsolutePath().normalize() }.map(::Item))
            batchSpeedSamples.clear()
            batchTransferredBytes = 0L
            lastBatchSpeedSampleTime = 0L
            batchBytesPerSecond = 0.0
            publishLocked()
        }
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            var cancelled = false
            var batchIdentity: String? = null
            val cancellationWatcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { cancelActive() }
            }
            val concurrencyWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                SettingsRepository.uploadConcurrency.collect { limit ->
                    synchronized(lock) {
                        concurrencyLimit = limit
                        publishLocked()
                    }
                    signal.trySend(Unit)
                }
            }
            val speedWatcher = launch {
                while (isActive) {
                    delay(500L)
                    synchronized(lock) {
                        val now = System.nanoTime()
                        var changed = false
                        queue.forEach { item ->
                            if (item.progress.status == UploadFileStatus.UPLOADING && !item.progress.stalled && now - item.lastGrowthTime >= 2_000_000_000L) {
                                item.progress = item.progress.copy(bytesPerSecond = null, stalled = true)
                                item.speedSampleTime = 0L
                                item.speedSampleSent = item.progress.sent
                                changed = true
                            }
                        }
                        if (sampleBatchSpeedLocked(now) || changed) publishLocked()
                    }
                }
            }
            try {
                val identity = withContext(Dispatchers.IO) { accountIdProvider() }
                check(!identity.isNullOrBlank()) { "请先登录该网盘" }
                batchIdentity = identity
                coroutineScope {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val (ready, finished) = synchronized(lock) {
                            val running = queue.filter { it.running }
                            val names = running.mapTo(mutableSetOf()) { it.progress.candidate.relativePath }
                            // 同名文件保持原先的顺序，避免并发覆盖同一个远端文件名。
                            val ready = if (batchError != null || cancelRequested) emptyList() else queue
                                .asSequence()
                                .filter { it.progress.status == UploadFileStatus.WAITING && names.add(it.progress.candidate.relativePath) }
                                .take((concurrencyLimit - running.size).coerceAtLeast(0))
                                .toList()
                            ready.forEach {
                                it.running = true
                                it.progress = it.progress.copy(status = UploadFileStatus.PREPARING)
                            }
                            val finished = running.isEmpty() && ready.isEmpty()
                            if (finished) acceptingFiles = false
                            publishLocked()
                            ready to finished
                        }
                        if (ready.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val launchReady = synchronized(lock) {
                                if (cancelRequested || batchError != null) {
                                    ready.forEach {
                                        it.running = false
                                        it.progress = it.progress.copy(status = UploadFileStatus.WAITING)
                                    }
                                    emptyList()
                                } else ready
                            }
                            launchReady.forEach { item ->
                                launch(Dispatchers.IO) { uploadOne(item, identity, accountIdProvider, upload, signal) }
                            }
                            if (launchReady.isEmpty()) { synchronized(lock) { publishLocked() }; signal.trySend(Unit) }
                        }
                        if (finished) break
                        signal.receive()
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                if (cancelRequested || !currentCoroutineContext().isActive) cancelled = true
                else synchronized(lock) { batchError = e.message ?: "网络错误" }
            } finally {
                cancelActive()
                cancellationWatcher.cancel()
                concurrencyWatcher.cancel()
                speedWatcher.cancel()
                val outcome = synchronized(lock) {
                    val notStarted = queue.count { it.progress.status == UploadFileStatus.WAITING }
                    queue.filter { it.progress.isActive || it.progress.status == UploadFileStatus.WAITING }.forEach {
                        it.progress = it.progress.copy(status = UploadFileStatus.CANCELLED, bytesPerSecond = null, stalled = false)
                    }
                    val outcome = UploadOutcome(
                        queue.count { it.progress.status == UploadFileStatus.COMPLETED },
                        queue.count { it.progress.status == UploadFileStatus.FAILED },
                        cancelled || cancelRequested,
                        batchError ?: queue.firstOrNull { it.progress.status == UploadFileStatus.FAILED }?.let {
                            "${it.file.name}：${it.progress.error}"
                        },
                        notStarted,
                        queue.count { it.progress.status == UploadFileStatus.CANCELLED }
                    )
                    acceptingFiles = false
                    publishLocked()
                    if (outcome.failed > 0 || outcome.cancelled || outcome.error != null) {
                        val identity = batchIdentity
                        lastResult = progress?.copy(finished = true, canAppend = false, canRetry = outcome.failed > 0 && identity != null, error = batchError)
                        if (identity != null) {
                            val retryFiles: (List<UploadCandidate>) -> Unit = { failedFiles ->
                                onRetry()
                                startCandidates(failedFiles, platform, {
                                    accountIdProvider().also {
                                        check(it == identity) { "账号已变化，请切回原账号后重试失败文件" }
                                    }
                                }, onRetry, upload)
                            }
                            retryAction = retryFiles
                            retrySingleAction = { failedFile -> retryFiles(listOf(failedFile)) }
                        }
                    }
                    progress = null
                    queue.clear()
                    queueSignal = null
                    onActiveRetry = null
                    // Keep the result retry callbacks until the result is closed or a new batch starts.
                    outcome
                }
                signal.close()
                UploadSessions.release(platform)
                onFinished(outcome)
            }
        }
        job = worker
        // 即使 scope 在 lazy 协程进入前已销毁，也不能遗留账号锁。
        worker.invokeOnCompletion {
            if (job === worker && progress != null) {
                cancelActive()
                synchronized(lock) {
                    acceptingFiles = false
                    progress = null
                    queue.clear()
                    queueSignal = null
                    onActiveRetry = null
                    retrySingleAction = null
                }
                signal.close()
                UploadSessions.release(platform)
            }
        }
        worker.start()
    }

    private suspend fun uploadOne(
        item: Item,
        identity: String,
        accountIdProvider: suspend () -> String?,
        upload: suspend (UploadIo, UploadSource) -> Unit,
        signal: Channel<Unit>
    ) {
        var io: UploadIo? = null
        try {
            currentCoroutineContext().ensureActive()
            if (accountIdProvider() != identity) throw UploadSessionException("账号已变化，上传已停止，请重新选择文件")
            val source = UploadSource.resolve(item.progress.candidate)
            val transfer = UploadIo(source, clientProvider) { phase, sent -> emit(item, source, phase, sent) }
            io = transfer
            synchronized(lock) {
                item.io = transfer
                item.progress = item.progress.copy(candidate = item.progress.candidate.copy(size = source.size))
            }
            transfer.job = currentCoroutineContext()[Job]
            if (cancelRequested) transfer.cancel()
            transfer.checkpoint()
            transfer.progress(UploadPhase.PREPARING)
            withContext(transfer.contextElement()) { upload(transfer, source) }
            currentCoroutineContext().ensureActive()
            // 请求体写完不算成功，仍须协议确认并检查本地文件未变化。
            source.verifyUnchanged()
            synchronized(lock) {
                item.progress = item.progress.copy(status = UploadFileStatus.COMPLETED, sent = source.size, bytesPerSecond = null, stalled = false)
            }
        } catch (e: CancellationException) {
            synchronized(lock) { item.progress = item.progress.copy(status = UploadFileStatus.CANCELLED, bytesPerSecond = null, stalled = false) }
            cancel()
            throw e
        } catch (e: Exception) {
            val cancelled = cancelRequested || !currentCoroutineContext().isActive
            synchronized(lock) {
                item.progress = item.progress.copy(
                    status = if (cancelled) UploadFileStatus.CANCELLED else UploadFileStatus.FAILED,
                    bytesPerSecond = null,
                    stalled = false,
                    error = if (cancelled) null else e.message ?: "网络错误"
                )
                if (!cancelled && e is UploadSessionException) {
                    acceptingFiles = false
                    if (batchError == null) batchError = "${item.file.name}：${e.message ?: "网络错误"}"
                }
            }
        } finally {
            io?.cancel()
            synchronized(lock) {
                item.io = null
                item.running = false
                publishLocked()
            }
            signal.trySend(Unit)
        }
    }

    private fun cancelActive() {
        val transfers = synchronized(lock) { queue.mapNotNull { it.io } }
        transfers.forEach { it.cancel() }
    }

    private fun emit(item: Item, source: UploadSource, phase: UploadPhase, sent: Long) = synchronized(lock) {
        val now = System.nanoTime()
        val current = item.progress
        val status = when (phase) {
            UploadPhase.PREPARING -> UploadFileStatus.PREPARING
            UploadPhase.UPLOADING -> UploadFileStatus.UPLOADING
            UploadPhase.FINISHING -> UploadFileStatus.FINISHING
        }
        val boundedSent = when (phase) {
            UploadPhase.PREPARING -> current.sent.coerceIn(0L, source.size)
            UploadPhase.UPLOADING -> sent.coerceIn(0L, source.size)
            UploadPhase.FINISHING -> source.size
        }
        val changed = current.status != status || boundedSent < current.sent
        val growing = boundedSent > current.sent
        if (changed || growing) item.lastGrowthTime = now
        if (!changed && boundedSent != source.size && now - item.lastEmit < 120_000_000L) return@synchronized
        item.lastEmit = now
        // 只统计实际上传回调的字节增长，不把建目录、秒传或完成确认的进度跳变当作网络速度。
        if (phase == UploadPhase.UPLOADING && growing) {
            if (batchSpeedSamples.isEmpty()) {
                batchSpeedSamples.addLast(now to 0L)
                lastBatchSpeedSampleTime = now
            }
            batchTransferredBytes += boundedSent - current.sent
        }
        var speed = current.bytesPerSecond
        if (changed || item.speedSampleTime == 0L) {
            item.speedSampleTime = now
            item.speedSampleSent = boundedSent
            speed = null
        } else if (phase == UploadPhase.UPLOADING) {
            val elapsed = now - item.speedSampleTime
            if (elapsed >= 500_000_000L || (boundedSent == source.size && boundedSent > item.speedSampleSent && elapsed > 0L)) {
                val sample = (boundedSent - item.speedSampleSent) * 1_000_000_000.0 / elapsed
                speed = speed?.let { it * 0.7 + sample * 0.3 } ?: sample
                item.speedSampleTime = now
                item.speedSampleSent = boundedSent
            }
        }
        item.progress = current.copy(status = status, sent = boundedSent, bytesPerSecond = speed, stalled = !changed && !growing && current.stalled)
        publishLocked(force = changed || boundedSent == source.size)
    }

    /** 跨文件连续统计近五秒的整批传输，复用现有心跳，避免小文件切换时丢失速度。 */
    private fun sampleBatchSpeedLocked(now: Long): Boolean {
        if (batchSpeedSamples.isEmpty() || now - lastBatchSpeedSampleTime < 500_000_000L) return false
        lastBatchSpeedSampleTime = now
        batchSpeedSamples.addLast(now to batchTransferredBytes)
        while (batchSpeedSamples.size > 1 && now - batchSpeedSamples.first().first > 5_000_000_000L) {
            batchSpeedSamples.removeFirst()
        }
        val (sampleTime, sampleBytes) = batchSpeedSamples.first()
        val elapsed = now - sampleTime
        batchBytesPerSecond = if (elapsed > 0L) (batchTransferredBytes - sampleBytes) * 1_000_000_000.0 / elapsed else 0.0
        return true
    }

    /** 仅在 lock 内生成不可变快照，避免不同文件的字节数、阶段和速度相互覆盖。 */
    private fun publishLocked(force: Boolean = true) {
        if (queue.isEmpty()) return
        val now = System.nanoTime()
        // 普通字节刷新按整批合并，避免并发数放大完整列表快照的分配；状态变化仍立即发布。
        if (!force && now - lastPublishTime < 200_000_000L) return
        lastPublishTime = now
        sampleBatchSpeedLocked(now)
        val files = queue.map { it.progress }
        val active = files.filter { it.isActive }
        val current = active.firstOrNull() ?: files.firstOrNull { it.status == UploadFileStatus.WAITING } ?: files.last()
        val phase = when {
            active.any { it.status == UploadFileStatus.UPLOADING } -> UploadPhase.UPLOADING
            active.isNotEmpty() && active.all { it.status == UploadFileStatus.FINISHING } && files.none { it.status == UploadFileStatus.WAITING } -> UploadPhase.FINISHING
            else -> UploadPhase.PREPARING
        }
        val sizeKnown = files.all { it.candidate.isDirectory || it.candidate.size > 0L }
        progress = UploadProgress(
            files.indexOf(current) + 1, files.size, current.candidate.relativePath,
            files.sumOf { it.sent }, files.sumOf { it.candidate.size }, phase, cancelRequested,
            if (cancelRequested || (active.isEmpty() && !acceptingFiles)) 0.0 else batchBytesPerSecond, uploadPlatform, files,
            files.count { it.status == UploadFileStatus.COMPLETED }, active.size, batchId,
            acceptingFiles && !cancelRequested && batchError == null, concurrencyLimit,
            canRetry = acceptingFiles && !cancelRequested && batchError == null && queue.any {
                it.progress.status == UploadFileStatus.FAILED && !it.running
            },
            sizeKnown = sizeKnown
        )
    }
}
