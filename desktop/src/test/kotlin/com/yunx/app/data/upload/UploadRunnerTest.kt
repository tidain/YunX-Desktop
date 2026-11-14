package com.yunx.app.data.upload

import com.yunx.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class UploadRunnerTest {
    @get:Rule val temp = TemporaryFolder()
    private val settings = SettingsRepository()
    private var previousConcurrency = SettingsRepository.DEFAULT_MAX_CONCURRENT_UPLOADS
    @Before fun useSerialQueue() {
        previousConcurrency = settings.maxConcurrentUploads
        settings.maxConcurrentUploads = 1
    }
    @After fun restoreConcurrency() { settings.maxConcurrentUploads = previousConcurrency }
    private fun files(count: Int) = (1..count).map { temp.newFile().apply { writeText("file-$it") } }

    @Test fun `批次串行运行并统计全部成功`() = runTest {
        val outcome = CompletableDeferred<UploadOutcome>()
        val runner = UploadRunner(this, { OkHttpClient() }) { outcome.complete(it) }
        val selected = files(3)
        val visited = mutableListOf<String>()
        runner.start(selected, "test-success", { "account-A" }) { _, source -> visited += source.name }
        assertNotNull(runner.progress)
        val result = outcome.await()
        assertEquals(selected.map { it.name }, visited)
        assertEquals(3, result.uploaded)
        assertEquals(0, result.failed)
        assertFalse(result.cancelled)
        assertNull(runner.progress)
        assertFalse(UploadSessions.isActive("test-success"))
    }

    @Test fun `第二个文件失败后保留失败并继续后续文件`() = runTest {
        val outcome = CompletableDeferred<UploadOutcome>()
        val runner = UploadRunner(this, { OkHttpClient() }) { outcome.complete(it) }
        var calls = 0
        runner.start(files(4), "test-failure", { "account-A" }) { _, _ ->
            if (++calls == 2) error("simulated failure")
        }
        val result = outcome.await()
        assertEquals(4, calls)
        assertEquals(3, result.uploaded)
        assertEquals(1, result.failed)
        assertEquals(0, result.notStarted)
        assertTrue(result.message().contains("simulated failure"))
    }

    @Test fun `账号变化后不会执行下一文件协议`() = runTest {
        val outcome = CompletableDeferred<UploadOutcome>()
        val runner = UploadRunner(this, { OkHttpClient() }) { outcome.complete(it) }
        var identity = "account-A"
        var calls = 0
        runner.start(files(2), "test-account", { identity }) { _, _ ->
            calls++
            identity = "account-B"
        }
        val result = outcome.await()
        assertEquals(1, calls)
        assertEquals(1, result.uploaded)
        assertEquals(1, result.failed)
        assertTrue(result.error!!.contains("账号已变化"))
    }

    @Test fun `同一平台不能同时启动两个批次`() = runTest {
        val started = CompletableDeferred<Unit>()
        val firstDone = CompletableDeferred<UploadOutcome>()
        val rejected = CompletableDeferred<UploadOutcome>()
        val first = UploadRunner(this, { OkHttpClient() }) { firstDone.complete(it) }
        val second = UploadRunner(this, { OkHttpClient() }) { rejected.complete(it) }
        first.start(files(1), "test-lock", { "A" }) { _, _ -> started.complete(Unit); awaitCancellation() }
        started.await()
        second.start(files(1), "test-lock", { "A" }) { _, _ -> error("must not run") }
        assertTrue(rejected.await().error!!.contains("已有上传任务"))
        assertThrows(IllegalStateException::class.java) { UploadSessions.requireIdle("test-lock") }
        first.cancel()
        assertTrue(firstDone.await().cancelled)
        assertFalse(UploadSessions.isActive("test-lock"))
    }

    @Test fun `账号写入期间不能开始上传`() = runTest {
        UploadSessions.changeAccount("test-mutation") {
            assertFalse(UploadSessions.acquire("test-mutation") {})
        }
        assertFalse(UploadSessions.isActive("test-mutation"))
    }

    @Test fun `页面作用域销毁能中断阻塞请求并释放账号锁`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            server.start()
            val http = OkHttpClient.Builder().addInterceptor(UploadRequestInterceptor()).build()
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val done = CompletableDeferred<UploadOutcome>()
            val runner = UploadRunner(scope, { http }) { done.complete(it) }
            try {
                runner.start(files(2), "test-parent-cancel", { "A" }) { _, _ ->
                    http.newCall(Request.Builder().url(server.url("/blocked")).build()).execute().use { it.body?.string() }
                }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                scope.cancel()
                val result = kotlinx.coroutines.withTimeout(3000) { done.await() }
                assertTrue(result.cancelled)
                assertEquals(0, result.uploaded)
                assertEquals(1, result.notStarted)
                assertFalse(UploadSessions.isActive("test-parent-cancel"))
            } finally { scope.cancel() }
        }
    }

    @Test fun `作用域已销毁时启动不会遗留平台锁`() = runTest {
        val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
        val runner = UploadRunner(scope, { OkHttpClient() }) {}
        runner.start(files(1), "test-dead-scope", { "A" }) { _, _ -> error("must not run") }
        assertFalse(UploadSessions.isActive("test-dead-scope"))
        assertFalse(runner.isRunning)
    }

    @Test fun `单项重试只重排指定失败项并清除旧错误`() = runTest {
        val outcome = CompletableDeferred<UploadOutcome>()
        val runner = UploadRunner(this, { OkHttpClient() }) { outcome.complete(it) }
        val selected = files(2)
        val failing = selected[1]
        var attempt = 0
        runner.start(selected, "test-single-retry", { "A" }) { _, source ->
            if (source.path.toFile() == failing && attempt++ == 0) error("first failure")
            if (source.path.toFile() == failing) awaitCancellation()
        }
        repeat(100) {
            if (runner.progress?.files?.firstOrNull { it.candidate.file == failing }?.status == UploadFileStatus.FAILED) return@repeat
            kotlinx.coroutines.yield()
        }
        assertEquals(UploadFileStatus.FAILED, runner.progress?.files?.firstOrNull { it.candidate.file == failing }?.status)
        val failed = runner.progress!!.files.first { it.candidate.file == failing }
        assertEquals("first failure", failed.error)
        assertEquals(1, runner.retrySingle(runner.progress!!.batchId, failed.candidate))
        val queued = runner.progress!!.files.first { it.candidate.file == failing }
        assertNull(queued.error)
        assertEquals(0L, queued.sent)
        runner.cancel()
        assertTrue(outcome.await().cancelled)
    }

    @Test fun `未知文件大小不会显示虚假的总完成率`() = runTest {
        val progress = UploadProgress(
            index = 1,
            count = 1,
            name = "unknown.bin",
            sent = 0L,
            size = 0L,
            phase = UploadPhase.PREPARING,
            files = listOf(UploadFileProgress(UploadCandidate(temp.newFile(), 0L)))
        )
        assertFalse(progress.sizeKnown)
        assertEquals(0f, progress.fraction)
        assertNull(progress.remainingSeconds)
    }
}
