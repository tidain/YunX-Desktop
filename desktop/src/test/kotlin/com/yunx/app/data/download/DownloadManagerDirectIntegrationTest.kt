package com.yunx.app.data.download

import com.yunx.app.AppContext
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.util.WindowsToastNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 直写模式端到端测试（MockWebServer + 内存 DAO）：
 * - 单线程直写：不产生缓存分片，目标目录出现完整文件；
 * - 多线程直写：预分配 + 按偏移并行写 + 逐片校验 + 改名；
 * - 两种模式完成后都不能残留 .yunx-*.part / .plan / .done。
 *
 * 运行前需把 YUNX_DESKTOP_DATA_DIR 指到一个临时目录，避免使用真实用户数据。
 */
class DownloadManagerDirectIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var data: ByteArray
    private lateinit var saveDir: File

    @Before
    fun setUp() {
        // 集成测试会走真实 DownloadManager：关掉 Windows 通知，避免测试期间右下角弹提示
        WindowsToastNotifier.enabled = false
        server = MockWebServer()
        server.start()
        data = ByteArray(10 * 1024 * 1024) { (it % 251).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                    ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(data))
                val (s, e) = parseRange(range)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $s-$e/${data.size}")
                    .setBody(Buffer().write(data, s.toInt(), (e - s + 1).toInt()))
            }
        }
        saveDir = File("build/direct-it/downloads").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        WindowsToastNotifier.enabled = true
    }

    @Test
    fun singleThreadDirectWritesToDestinationWithoutCache() = runBlocking {
        val dao = MemoryDownloadTaskDao()
        val manager = newManager(dao, threads = 1, single = true, multi = false)
        val id = manager.enqueue(
            url = server.url("/file").toString(),
            fileName = "single.bin",
            size = data.size.toLong(),
            saveDir = saveDir.absolutePath
        )
        awaitCompleted(dao, id)

        val saved = File(saveDir, "single.bin")
        assertTrue("目标文件应存在", saved.isFile)
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        assertEquals("", dao.get(id)?.directPart)
        assertNoDirectTempFiles()
        assertNoCacheChunks(id)
    }

    @Test
    fun multiThreadDirectWritesToDestinationWithoutCache() = runBlocking {
        val dao = MemoryDownloadTaskDao()
        val manager = newManager(dao, threads = 4, single = false, multi = true)
        val id = manager.enqueue(
            url = server.url("/file").toString(),
            fileName = "multi.bin",
            size = data.size.toLong(),
            saveDir = saveDir.absolutePath
        )
        awaitCompleted(dao, id)

        val saved = File(saveDir, "multi.bin")
        assertTrue("目标文件应存在", saved.isFile)
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        assertEquals("", dao.get(id)?.directPart)
        assertNoDirectTempFiles()
        assertNoCacheChunks(id)
    }

    @Test
    fun singleThreadDirectResumesFromPartLength() = runBlocking {
        val dao = MemoryDownloadTaskDao()
        val part = File(saveDir, "resume-single.bin.yunx-1.part")
        val half = data.size / 2
        part.writeBytes(data.copyOfRange(0, half))
        val id = dao.insert(
            DownloadTaskEntity(
                url = server.url("/file").toString(),
                fileName = "resume-single.bin",
                totalSize = data.size.toLong(),
                status = DownloadTaskEntity.STATUS_PAUSED,
                saveDir = saveDir.absolutePath,
                directPart = part.absolutePath,
                directMode = 1
            )
        )
        val manager = newManager(dao, threads = 1, single = true, multi = false)
        manager.start(id)
        awaitCompleted(dao, id)

        val saved = File(saveDir, "resume-single.bin")
        assertTrue("续传后目标文件应存在", saved.isFile)
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        assertNoDirectTempFiles()
        assertNoCacheChunks(id)
    }

    @Test
    fun multiThreadDirectResumesFromPlanAndDone() = runBlocking {
        val dao = MemoryDownloadTaskDao()
        val part = File(saveDir, "resume-multi.bin.yunx-1.part")
        val chunkCount = 4
        val chunkSize = ((data.size + chunkCount - 1) / chunkCount).toLong()
        RandomAccessFile(part, "rw").use { it.setLength(data.size.toLong()) }
        FileChannel.open(
            part.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { channel ->
            for (i in 0 until 2) {
                val s = i * chunkSize
                val e = minOf((i + 1) * chunkSize - 1, data.size - 1L)
                val len = (e - s + 1).toInt()
                channel.write(ByteBuffer.wrap(data, s.toInt(), len), s)
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(data, s.toInt(), len)
                DirectPartManifest.appendDone(part, i, digest.digest().toHexDigest())
            }
        }
        DirectPartManifest.writePlan(part, DirectPartManifest.Plan(data.size.toLong(), chunkCount, chunkSize))
        val id = dao.insert(
            DownloadTaskEntity(
                url = server.url("/file").toString(),
                fileName = "resume-multi.bin",
                totalSize = data.size.toLong(),
                status = DownloadTaskEntity.STATUS_PAUSED,
                saveDir = saveDir.absolutePath,
                directPart = part.absolutePath,
                directMode = 2
            )
        )
        val manager = newManager(dao, threads = 4, single = false, multi = true)
        manager.start(id)
        awaitCompleted(dao, id)

        val saved = File(saveDir, "resume-multi.bin")
        assertTrue("续传后目标文件应存在", saved.isFile)
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        assertNoDirectTempFiles()
        assertNoCacheChunks(id)
    }

    @Test
    fun multiFallsBackToSingleDirectWhenRangeIsIgnored() = runBlocking {
        // 服务器永远返回 200（忽略 Range）：多线程直写应改走单线程直写（完整 GET）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(Buffer().write(data))
        }
        val dao = MemoryDownloadTaskDao()
        val manager = newManager(dao, threads = 4, single = true, multi = true)
        val id = manager.enqueue(
            url = server.url("/file").toString(),
            fileName = "fallback-single.bin",
            size = data.size.toLong(),
            saveDir = saveDir.absolutePath
        )
        awaitCompleted(dao, id)

        val saved = File(saveDir, "fallback-single.bin")
        assertTrue(saved.isFile)
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        assertEquals("", dao.get(id)?.directPart)
        assertNoDirectTempFiles()
        assertNoCacheChunks(id)
    }

    @Test
    fun multiFallsBackToCacheWhenRangeIsIgnoredAndSingleDisabled() = runBlocking {
        // 服务器永远返回 200，且单线程直写关闭：多线程直写应回退到缓存分片模式并完成
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(Buffer().write(data))
        }
        val dao = MemoryDownloadTaskDao()
        val manager = newManager(dao, threads = 4, single = false, multi = true)
        val id = manager.enqueue(
            url = server.url("/file").toString(),
            fileName = "fallback-cache.bin",
            size = data.size.toLong(),
            saveDir = saveDir.absolutePath
        )
        awaitCompleted(dao, id)

        val saved = File(saveDir, "fallback-cache.bin")
        assertTrue(saved.isFile)
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        assertEquals("", dao.get(id)?.directPart)
        assertNoDirectTempFiles()
        assertNoCacheChunks(id)
    }

    @Test
    fun derivesFolderRelativeNameFromSavePath() {
        val base = File("build/grouping-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val nested = File(base, "夸克精选壁纸/sub/01.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val root = File(base, "root.jpg").apply { writeBytes(byteArrayOf(2)) }
        val outside = File(base.parentFile, "outside.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(3))
        }
        val manager = newManager(MemoryDownloadTaskDao(), threads = 1, single = false, multi = false)

        assertEquals("夸克精选壁纸/sub/01.jpg", manager.folderRelativeName(base, nested.absolutePath))
        assertEquals(null, manager.folderRelativeName(base, root.absolutePath))
        assertEquals(null, manager.folderRelativeName(base, outside.absolutePath))
    }

    @Test
    fun engineDisplayNameKeepsFolderPrefix() {
        val manager = newManager(MemoryDownloadTaskDao(), threads = 1, single = false, multi = false)
        assertEquals(
            "夸克精选壁纸/sub/01.jpg",
            manager.engineDisplayName("夸克精选壁纸/sub/01.jpg", "01.jpg")
        )
        assertEquals(
            "夸克精选壁纸/01 (1).jpg",
            manager.engineDisplayName("夸克精选壁纸/01.jpg", "01 (1).jpg")
        )
        assertEquals("root.jpg", manager.engineDisplayName("root.jpg", "root.jpg"))
        // 引擎若返回带路径的名字，只取最后一段，避免相对目录重复
        assertEquals(
            "夸克精选壁纸/01.jpg",
            manager.engineDisplayName("夸克精选壁纸/01.jpg", "sub/01.jpg")
        )
    }

    @Test
    fun cachedResumePreservesCompletedIslandsAndMergesWithoutDuplicates() = runBlocking {
        val dao = MemoryDownloadTaskDao()
        val id = dao.insert(DownloadTaskEntity(
            url = server.url("/file").toString(),
            fileName = "resume-cache.bin",
            totalSize = data.size.toLong(),
            status = DownloadTaskEntity.STATUS_PAUSED,
            saveDir = saveDir.absolutePath
        ))
        val chunkDir = File(AppContext.downloadTmpDir, "download_tmp/$id").apply { mkdirs() }
        File(chunkDir, "plan.txt").writeText("chunks=4 total=${data.size} main=2")
        val chunkSize = data.size / 4
        for (i in 0 until 2) {
            File(chunkDir, "part_$i").writeBytes(data.copyOfRange(i * chunkSize, (i + 1) * chunkSize))
        }
        val mb = 1024 * 1024
        val completed = listOf(6 * mb until 7 * mb, 8 * mb until data.size)
        for (range in completed) {
            File(chunkDir, "seg_${range.first}_${range.last}.part")
                .writeBytes(data.copyOfRange(range.first, range.last + 1))
        }
        // 模拟较早的块未完成，但后方多个块已完成的暂停状态。
        File(chunkDir, "seg_${5 * mb}_${6 * mb - 1}.part").writeBytes(ByteArray(100))

        val manager = newManager(dao, threads = 4, single = false, multi = false)
        manager.start(id)
        awaitCompleted(dao, id)

        val saved = File(saveDir, "resume-cache.bin")
        assertEquals(data.size.toLong(), saved.length())
        assertArrayEquals(data, saved.readBytes())
        repeat(server.requestCount) {
            val request = server.takeRequest()
            val (start, end) = parseRange(request.getHeader("Range")!!)
            for (range in completed) {
                assertTrue("不应重新请求已完成区间：$start-$end", end < range.first || start > range.last)
            }
        }
        assertNoCacheChunks(id)
    }

    private fun newManager(
        dao: DownloadTaskDao,
        threads: Int,
        single: Boolean,
        multi: Boolean
    ): DownloadManager = DownloadManager(
        dao = dao,
        downloader = ChunkDownloader { OkHttpClient() },
        threadProvider = { threads },
        saveDirProvider = { saveDir.absolutePath },
        concurrencyProvider = { 1 },
        retryCountProvider = { 0 },
        keepAwakeProvider = { false },
        showSpeedProvider = { false },
        directWriteSingleProvider = { single },
        directWriteMultiProvider = { multi }
    )

    private suspend fun awaitCompleted(dao: DownloadTaskDao, id: Long) {
        withTimeout(60_000) {
            while (true) {
                val task = dao.get(id)
                when (task?.status) {
                    DownloadTaskEntity.STATUS_COMPLETED -> return@withTimeout
                    DownloadTaskEntity.STATUS_FAILED -> fail("任务失败：${task.errorMsg}")
                }
                delay(50)
            }
        }
    }

    private fun assertNoDirectTempFiles() {
        val leftovers = saveDir.listFiles()?.filter { it.name.contains(".yunx-") } ?: emptyList()
        assertTrue("不应残留直写临时文件：${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    private suspend fun assertNoCacheChunks(id: Long) {
        // 任务状态先落库（completeWithAvg）、分片目录随后才删（finishDownload 收尾）：
        // 直接断言会与清理赛跑，机器慢时偶发失败 —— 先等清理完成再断言。
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            File(AppContext.downloadTmpDir, "download_tmp/$id").exists()
        ) {
            delay(50)
        }
        assertFalse(File(AppContext.downloadTmpDir, "$id").exists())
        assertFalse(File(AppContext.downloadTmpDir, "download_tmp/$id").exists())
    }

    private fun parseRange(header: String): Pair<Long, Long> {
        val body = header.removePrefix("bytes=")
        return body.substringBefore('-').trim().toLong() to body.substringAfter('-').trim().toLong()
    }
}

/** 仅测试用的内存 DAO（不碰真实数据库） */
private class MemoryDownloadTaskDao : DownloadTaskDao {
    private val state = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
    private val nextId = AtomicLong(1L)

    private fun mutate(id: Long, block: (DownloadTaskEntity) -> DownloadTaskEntity) {
        state.value = state.value.map { if (it.id == id) block(it) else it }
    }

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = state
    override suspend fun insert(task: DownloadTaskEntity): Long {
        val id = nextId.getAndIncrement()
        state.value = state.value + task.copy(id = id)
        return id
    }
    override suspend fun get(id: Long): DownloadTaskEntity? = state.value.firstOrNull { it.id == id }
    override suspend fun listAll(): List<DownloadTaskEntity> = state.value
    override suspend fun repairCompletedProgress() {
        state.value = state.value.map {
            if (it.status == DownloadTaskEntity.STATUS_COMPLETED && it.totalSize > 0 && it.downloadedSize < it.totalSize) {
                it.copy(downloadedSize = it.totalSize)
            } else {
                it
            }
        }
    }
    override suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long) =
        mutate(id) { it.copy(status = status, downloadedSize = downloadedSize, totalSize = totalSize) }
    override suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long) =
        mutate(id) { it.copy(chunkCount = chunkCount, plannedTotalSize = totalSize) }
    override suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String) =
        mutate(id) { it.copy(requestHeadersJson = encryptedHeaders) }
    override suspend fun markInterruptedAsPaused() = Unit
    override suspend fun updateStatus(id: Long, status: Int) = mutate(id) { it.copy(status = status) }
    override suspend fun updateError(id: Long, errorMsg: String) = mutate(id) { it.copy(errorMsg = errorMsg) }
    override suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long) =
        mutate(id) {
            it.copy(
                status = status,
                savePath = savePath,
                avgSpeed = avgSpeed,
                downloadedSize = if (it.totalSize > 0) it.totalSize else it.downloadedSize
            )
        }
    override suspend fun delete(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }
    override suspend fun updateEngineTaskId(id: Long, engineTaskId: String) =
        mutate(id) { it.copy(engineTaskId = engineTaskId) }
    override suspend fun updateFileName(id: Long, name: String) = mutate(id) { it.copy(fileName = name) }
    override suspend fun listSyncableEngineTasks(): List<DownloadTaskEntity> = emptyList()
    override suspend fun updateDirect(id: Long, partPath: String, mode: Int) =
        mutate(id) { it.copy(directPart = partPath, directMode = mode) }
}
