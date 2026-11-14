package com.yunx.app.data.network

import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class QuarkUploadTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source() = UploadSource.resolve(temp.newFile("中文文件.txt").apply { writeText("abcde") })

    @Test fun `夸克秒传只登记哈希不发文件字节且固定目标目录`() {
        val file = source()
        val calls = mutableListOf<String>()
        val protocol = QuarkUpload("https://drive.test", "ucpro", "https://pan.quark.cn", "YunX-Test") { url, body ->
            calls += url
            when {
                url.contains("upload/pre") -> { assertEquals("target-dir", body.getString("pdir_fid")); JSONObject().put("task_id", "task") }
                url.contains("update/hash") -> { assertEquals(32, body.getString("md5").length); assertEquals(40, body.getString("sha1").length); JSONObject().put("finish", true) }
                else -> error("unexpected $url")
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { error("秒传不得请求对象存储") }.build()
        protocol.upload(UploadIo(file, { http }) { _, _ -> }, file, "target-dir")
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.endsWith("?pr=ucpro&fr=pc") })
    }

    @Test fun `UC分片签名日期一致且合并回调成功后才finish`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("ETag", "\"a\""))
            server.enqueue(MockResponse().setHeader("ETag", "\"b\""))
            server.enqueue(MockResponse().setBody("{}"))
            val file = source()
            val metadata = mutableListOf<String>()
            val control = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertNull(request.header("Cookie"))
                val local = server.url(request.url.encodedPath + "?" + request.url.encodedQuery)
                chain.proceed(request.newBuilder().url(local).build())
            }.build()
            val protocol = QuarkUpload("https://uc.test", "UCBrowser", "https://drive.uc.cn", "YunX-Test") { url, body ->
                control += url.substringBefore('?').substringAfter("clouddrive/")
                assertTrue(url.endsWith("pr=UCBrowser&fr=pc"))
                when {
                    url.contains("upload/pre") -> pre()
                    url.contains("update/hash") -> JSONObject().put("finish", false)
                    url.contains("upload/auth") -> { metadata += body.getString("auth_meta"); JSONObject().put("auth_key", "signed-key") }
                    url.contains("upload/finish") -> JSONObject()
                    else -> error("unexpected control request")
                }
            }
            protocol.upload(UploadIo(file, { http }) { _, _ -> }, file, "dir")
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            val merged = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("abc", first.body.readUtf8())
            assertEquals("de", second.body.readUtf8())
            assertTrue(metadata[0].contains("x-oss-date:${first.getHeader("x-oss-date")}\n"))
            assertTrue(metadata[1].contains("x-oss-date:${second.getHeader("x-oss-date")}\n"))
            assertEquals("application/xml", merged.getHeader("Content-Type"))
            assertNotNull(merged.getHeader("x-oss-callback"))
            assertTrue(merged.body.readUtf8().contains("<PartNumber>2</PartNumber>"))
            assertEquals("file/upload/finish", control.last())
        }
    }

    @Test fun `HTTP200但合并XML错误不得调用finish`() {
        val file = source()
        var finished = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "part")
                .body(okhttp3.ResponseBody.create(null, if (request.method == "POST") "<Error><Code>BadDigest</Code></Error>" else ""))
                .build()
        }.build()
        val protocol = QuarkUpload("https://drive.test", "ucpro", "https://pan.quark.cn", "test") { url, _ ->
            when {
                url.contains("upload/pre") -> pre()
                url.contains("update/hash") -> JSONObject()
                url.contains("upload/auth") -> JSONObject().put("auth_key", "signed")
                else -> { finished = true; JSONObject() }
            }
        }
        assertThrows(IllegalStateException::class.java) { protocol.upload(UploadIo(file, { http }) { _, _ -> }, file, "dir") }
        assertFalse(finished)
    }

    private fun pre() = JSONObject().put("task_id", "task").put("bucket", "bucket")
        .put("obj_key", "folder/a b.txt").put("upload_id", "upload-id")
        .put("upload_url", "https://oss.test").put("auth_info", "info")
        .put("metadata", JSONObject().put("part_size", 3)).put("callback", JSONObject().put("callbackUrl", "https://callback.test"))
}
