package com.yunx.app.data.upload

import com.yunx.app.data.network.DiagnosticNetworkInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UploadIoTest {
    @get:Rule val temp = TemporaryFolder()

    private fun source(bytes: ByteArray = "0123456789".toByteArray()): UploadSource =
        UploadSource.resolve(temp.newFile().apply { writeBytes(bytes) })

    private fun client() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .addInterceptor(UploadRequestInterceptor())
        .addInterceptor(DiagnosticNetworkInterceptor()).build()

    @Test fun `区间读取不会越过边界且空区间可读`() {
        val file = source()
        file.open(2, 7).use { input ->
            assertEquals(3L, input.skip(3))
            assertEquals("56", input.readBytes().toString(Charsets.UTF_8))
            assertEquals(-1, input.read())
            assertEquals(0, input.read(ByteArray(0)))
        }
        file.open(5, 5).use { assertEquals(-1, it.read()) }
        assertThrows(IllegalArgumentException::class.java) { file.open(-1, 5) }
        assertThrows(IllegalArgumentException::class.java) { file.open(0, Long.MAX_VALUE) }
    }

    @Test fun `源文件变化后拒绝读取旧区间`() {
        val file = temp.newFile().apply { writeText("abc") }
        val resolved = UploadSource.resolve(file)
        file.writeText("changed")
        assertThrows(IOException::class.java) { resolved.open() }
    }

    @Test fun `相同大小但修改时间变化仍拒绝上传`() {
        val file = temp.newFile().apply { writeText("abc") }
        val resolved = UploadSource.resolve(file)
        file.writeText("xyz")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 5000))
        assertThrows(IOException::class.java) { resolved.verifyUnchanged() }
    }

    @Test fun `摘要按指定区间流式计算`() {
        val file = source()
        val io = UploadIo(file, ::client) { _, _ -> }
        val expected = MessageDigest.getInstance("MD5").digest("23456".toByteArray())
        assertEquals(UploadIo.hex(expected), io.digest("MD5", 2, 7))
    }

    @Test fun `二进制分片长度准确且只发送目标区间`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("OK"))
            server.start()
            val file = source()
            val progress = mutableListOf<Long>()
            UploadIo(file, ::client) { _, sent -> progress += sent }.send(server.url("/part").toString(), start = 3, end = 8)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("5", request.getHeader("Content-Length"))
            assertNull(request.getHeader("Transfer-Encoding"))
            assertEquals("34567", request.body.readUtf8())
            assertEquals(8L, progress.last())
        }
    }

    @Test fun `multipart中文文件名与请求体长度逐字节相同`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val file = source("中文内容".toByteArray())
            UploadIo(file, ::client) { _, _ -> }.send(
                server.url("/form").toString(), method = "POST", fields = listOf("key" to "值"),
                fileName = "中文\"\r\n.txt", fieldName = "upload_file"
            )
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(request.bodySize, request.getHeader("Content-Length")!!.toLong())
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"upload_file\"; filename=\"中文%22%0D%0A.txt\""))
            assertTrue(body.contains("中文内容"))
            assertTrue(body.contains("name=\"key\"\r\n\r\n值"))
        }
    }

    @Test fun `XML控制请求不携带文件且Content-Type不追加charset`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("OK")) }
            server.start()
            val io = UploadIo(source(), ::client) { _, _ -> }
            io.send(server.url("/init").toString(), method = "POST", body = "", contentType = "application/xml")
            io.send(server.url("/merge").toString(), method = "POST", body = "<Complete/>", contentType = "application/xml")
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(0L, first.bodySize)
            assertEquals("application/xml", second.getHeader("Content-Type"))
            assertEquals("<Complete/>", second.body.readUtf8())
        }
    }

    @Test fun `无显式重试时网络断开不重放文件写入`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            assertThrows(IOException::class.java) {
                UploadIo(source(), ::client) { _, _ -> }.send(server.url("/once").toString())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `控制响应体阻塞也能被取消`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed body").setBodyDelay(3, TimeUnit.SECONDS))
            server.start()
            val http = client()
            val io = UploadIo(source(), { http }) { _, _ -> }
            val result = async(Dispatchers.IO + io.contextElement()) {
                runCatching { http.newCall(Request.Builder().url(server.url("/control")).build()).execute().use { it.body!!.string() } }
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            io.cancel()
            assertTrue(kotlinx.coroutines.withTimeout(2000) { result.await() }.isFailure)
        }
    }

    @Test fun `S3初始化和合并使用空控制体及有序ETag`() {
        val file = temp.newFile().apply { outputStream().use { output -> repeat(9) { output.write(ByteArray(1024 * 1024) { 7 }) } } }
        val requests = mutableListOf<Pair<String, String>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val bytes = Buffer().also { request.body?.writeTo(it) }
            val action = request.method + " " + request.url.encodedQuery.orEmpty()
            requests += action to if (request.method == "PUT") bytes.size.toString() else bytes.readUtf8()
            val response = when {
                request.method == "POST" && request.url.queryParameterNames.contains("uploads") -> "<r><UploadId>id</UploadId></r>"
                else -> "<OK/>"
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "\"part-${requests.size}\"")
                .body(response.toByteArray().let { okhttp3.ResponseBody.create(null, it) }).build()
        }.build()
        UploadIo(UploadSource.resolve(file), { http }) { _, _ -> }.s3Multipart("https://storage.test", "bucket", "object", "ak", "sk", "test", pathStyle = true)
        assertEquals(4, requests.size)
        assertEquals("", requests.first().second)
        assertEquals((8L * 1024 * 1024).toString(), requests[1].second)
        assertEquals((1024L * 1024).toString(), requests[2].second)
        assertTrue(requests.last().second.contains("<PartNumber>2</PartNumber>"))
        assertFalse(requests.last().second.contains("Signature"))
    }
}
