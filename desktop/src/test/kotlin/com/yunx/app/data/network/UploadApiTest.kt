package com.yunx.app.data.network

import com.yunx.app.data.upload.UploadIo
import com.yunx.app.data.upload.UploadSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLDecoder

/** 在真实 API/请求体上模拟服务端；不使用真实账号或向公网发送文件。 */
class UploadApiTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = UploadSource.resolve(temp.newFile().apply { writeText("abc") })
    private fun io(file: UploadSource, http: OkHttpClient) = UploadIo(file, { http }) { _, _ -> }
    private fun client(handler: (Request, String) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val buffer = Buffer().also { request.body?.writeTo(it) }
        request.body?.let { assertEquals(it.contentLength(), buffer.size) }
        val result = handler(request, buffer.readUtf8())
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("ETag", "\"etag\"").body(result.toResponseBody()).build()
    }.build()

    @Test fun `百度按块校验MD5并提交到选中目录`() = runBlocking {
        val file = file()
        var committed = false
        val http = client { request, body ->
            when {
                request.url.encodedPath.endsWith("gettemplatevariable") -> """{"errno":0,"result":{"bdstoken":"session"}}"""
                request.url.encodedPath.endsWith("precreate") -> {
                    assertTrue(URLDecoder.decode(body, "UTF-8").contains("path=/folder/${file.name}"))
                    """{"errno":0,"uploadid":"upload","block_list":[0]}"""
                }
                request.url.queryParameter("method") == "locateupload" -> """{"servers":[{"server":"https://unit.pcs.baidu.com"}]}"""
                request.url.encodedPath.endsWith("superfile2") -> {
                    assertEquals("abc", body)
                    """{"md5":"900150983cd24fb0d6963f7d28e17f72"}"""
                }
                request.url.encodedPath.endsWith("create") -> { committed = true; """{"errno":0,"fs_id":"bd-file"}""" }
                else -> error("unexpected ${request.url}")
            }
        }
        assertEquals("bd-file", BaiduApi { http }.upload(io(file, http), file, "/folder", "cookie=A"))
        assertTrue(committed)
    }

    @Test fun `百度分片校验失败不得提交合并`() = runBlocking {
        val file = file()
        var committed = false
        val http = client { request, _ ->
            when {
                request.url.encodedPath.endsWith("gettemplatevariable") -> """{"errno":0,"result":{"bdstoken":"session"}}"""
                request.url.encodedPath.endsWith("precreate") -> """{"errno":0,"uploadid":"upload","block_list":[0]}"""
                request.url.queryParameter("method") == "locateupload" -> """{"host":"unit.pcs.baidu.com"}"""
                request.url.encodedPath.endsWith("superfile2") -> """{"md5":"invalid"}"""
                else -> { committed = true; "{}" }
            }
        }
        expectFailure { BaiduApi { http }.upload(io(file, http), file, "/", "cookie=A") }
        assertFalse(committed)
    }

    @Test fun `139签发分片后直传不泄露网关鉴权再完成合并`() = runBlocking {
        val file = file()
        val paths = mutableListOf<String>()
        val http = client { request, body ->
            paths += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/hcy/file/create") -> {
                    assertEquals("folder", JSONObject(body).getString("parentFileId"))
                    """{"code":"0000","data":{"fileId":"139-file","uploadId":"upload","partInfos":[{"partNumber":1,"uploadUrl":"https://storage.test/part"}]}}"""
                }
                request.url.host == "storage.test" -> {
                    assertEquals("abc", body)
                    assertNull(request.header("Authorization"))
                    assertNull(request.header("mcloud-sign"))
                    ""
                }
                request.url.encodedPath.endsWith("/complete") -> """{"code":"0000"}"""
                else -> error("unexpected ${request.url}")
            }
        }
        assertEquals("139-file", C139Api { http }.upload(io(file, http), file, "folder", "authorization=Basic test"))
        assertTrue(paths.last().endsWith("/complete"))
    }

    @Test fun `123有UploadId时优先预签名地址并用v2合并`() = runBlocking {
        val file = file()
        var merged = false
        val http = client { request, body ->
            when {
                request.url.encodedPath.endsWith("upload_request") -> {
                    assertEquals(99, JSONObject(body).getInt("parentFileId"))
                    """{"code":0,"data":{"Info":{"FileId":"123"},"Key":"key","UploadId":"upload","AccessKeyId":"also-present","SecretAccessKey":"also-present"}}"""
                }
                request.url.encodedPath.endsWith("s3_upload_object/auth") -> {
                    val json = JSONObject(body)
                    assertTrue(json.has("StorageNode"))
                    assertTrue(json.isNull("StorageNode"))
                    """{"code":0,"data":{"presignedUrls":{"1":"https://storage.test/part?signature=test"}}}"""
                }
                request.url.host == "storage.test" -> {
                    assertNull(request.header("Authorization"))
                    assertNull(request.header("Cookie"))
                    assertEquals("abc", body)
                    ""
                }
                request.url.encodedPath.endsWith("upload_complete/v2") -> {
                    merged = true
                    assertFalse(JSONObject(body).getBoolean("isMultipart"))
                    """{"code":0,"data":{"file_info":{"FileId":"123"}}}"""
                }
                else -> error("unexpected ${request.url}")
            }
        }
        assertEquals("123", Pan123Api { http }.upload(io(file, http), file, "99", "token"))
        assertTrue(merged)
    }

    @Test fun `115表单不带Cookie且确认回调后成功`() = runBlocking {
        val file = file()
        val http = client { request, body ->
            if (request.url.host == "uplb.115.com") {
                assertTrue(body.contains("target=U_1_8"))
                policy115()
            } else {
                assertNull(request.header("Cookie"))
                assertTrue(body.contains("name=\"policy\""))
                assertTrue(body.contains("abc"))
                """{"state":true,"data":{"file_id":"115-file"}}"""
            }
        }
        assertEquals("115-file", Pan115Api { http }.upload(io(file, http), file, "8", "cookie=A"))
    }

    @Test fun `115收到非JSON回调不得当作上传成功`() = runBlocking {
        val file = file()
        val http = client { request, _ -> if (request.url.host == "uplb.115.com") policy115() else "<Error/>" }
        expectFailure { Pan115Api { http }.upload(io(file, http), file, "0", "cookie=A") }
    }

    @Test fun `迅雷有文件ID但没有秒传或上传凭据必须失败`() = runBlocking {
        val file = file()
        val http = client { _, _ -> """{"file":{"id":"xl-file"}}""" }
        expectFailure { XunleiApi { http }.upload(io(file, http), file, "", "token", "device", "captcha") }
    }

    @Test fun `迅雷传完S3后查询网盘完成状态`() = runBlocking {
        val file = file()
        var checked = false
        val http = client { request, body ->
            when {
                request.method == "POST" -> {
                    assertEquals("folder", JSONObject(body).getString("parent_id"))
                    assertEquals(40, JSONObject(body).getString("hash").length)
                    """{"file":{"id":"xl-file"},"resumable":{"params":{"endpoint":"https://storage.test","bucket":"bucket","key":"key","access_key_id":"ak","access_key_secret":"sk"}}}"""
                }
                request.method == "PUT" -> { assertEquals("abc", body); "" }
                request.method == "GET" -> { checked = true; """{"phase":"PHASE_TYPE_COMPLETE"}""" }
                else -> error("unexpected request")
            }
        }
        assertEquals("xl-file", XunleiApi { http }.upload(io(file, http), file, "folder", "token", "device", "captcha"))
        assertTrue(checked)
    }

    @Test fun `光鸭OSS合并后轮询到文件ID才返回`() = runBlocking {
        val file = file()
        var checked = false
        val http = client { request, body ->
            when {
                request.url.encodedPath.endsWith("get_res_center_token") -> {
                    assertEquals("folder", JSONObject(body).getString("parentId"))
                    """{"code":"0","data":{"taskId":"task","fullEndPoint":"https://oss.test","bucketName":"bucket","objectPath":"object","creds":{"accessKeyID":"ak","secretAccessKey":"sk","sessionToken":"sts"}}}"""
                }
                request.url.encodedPath.endsWith("get_info_by_task_id") -> { checked = true; """{"code":"0","data":{"fileId":"gy-file"}}""" }
                request.url.queryParameterNames.contains("uploads") -> { assertEquals("", body); "<r><UploadId>upload</UploadId></r>" }
                request.method == "PUT" -> { assertEquals("abc", body); "" }
                request.method == "POST" && request.url.queryParameterNames.contains("uploadId") -> "<OK/>"
                else -> error("unexpected ${request.url}")
            }
        }
        assertEquals("gy-file", GuangYaApi { http }.upload(io(file, http), file, "folder", "token"))
        assertTrue(checked)
    }

    @Test fun `蓝奏优享七牛直传后检查异步结果且不把appToken发给七牛`() = runBlocking {
        val file = file()
        var checked = false
        val http = client { request, body ->
            when {
                request.url.encodedPath.endsWith("getUpToken") -> {
                    assertEquals("folder", JSONObject(body).getString("folderId"))
                    assertEquals(1, JSONObject(body).getInt("fileSize"))
                    """{"code":200,"upToken":"qiniu-token"}"""
                }
                request.url.encodedPath.endsWith("account/map") -> """{"code":200,"map":{"userId":"user","account":"account"}}"""
                request.url.host == "upload.qiniup.com" -> {
                    assertNull(request.url.queryParameter("appToken"))
                    assertNull(request.header("Cookie"))
                    assertTrue(body.contains("qiniu-token"))
                    """{"token":"result-token"}"""
                }
                request.url.encodedPath.endsWith("/results") -> {
                    checked = true
                    assertNull(request.url.queryParameter("appToken"))
                    """{"code":200,"list":[{"status":1,"fileId":"il-file"}]}"""
                }
                else -> error("unexpected ${request.url}")
            }
        }
        assertEquals("f:il-file", ILanzouApi { http }.upload(io(file, http), file, "folder", "app-token", "device-uuid"))
        assertTrue(checked)
    }

    @Test fun `蓝奏成功响应必须含文件ID`() = runBlocking {
        val file = file()
        var success = true
        val http = client { request, body ->
            assertEquals("upload_file", Regex("name=\"(upload_file)\"").find(body)!!.groupValues[1])
            assertEquals("uid", request.url.queryParameter("uid"))
            if (success) """{"zt":1,"text":[{"id":"lz-file"}]}""" else """{"zt":1,"text":[]}"""
        }
        val api = LanzouApi { http }
        assertEquals("f:lz-file", api.upload(io(file, http), file, "-1", "cookie=A", "uid", "vei"))
        success = false
        expectFailure { api.upload(io(file, http), file, "-1", "cookie=A", "uid", "vei") }
    }

    private fun policy115() = JSONObject().put("host", "https://bucket.oss-unit.aliyuncs.com")
        .put("object", "object").put("policy", "policy").put("accessid", "id")
        .put("signature", "signature").put("callback", "callback").toString()

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try { action() } catch (_: IllegalStateException) { return }
        error("Expected upload to fail")
    }
}
