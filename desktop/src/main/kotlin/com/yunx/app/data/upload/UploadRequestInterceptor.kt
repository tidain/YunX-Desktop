package com.yunx.app.data.upload

import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.ForwardingSource
import okio.buffer

/** 将上传流程的控制请求也纳入取消范围，并阻止写操作被自动重放。 */
internal class UploadRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val upload = original.tag(UploadIo::class.java) ?: UploadIo.current()
            ?: return chain.proceed(original)
        val body = original.body
        val request = original.newBuilder().tag(UploadIo::class.java, upload).apply {
            if (body != null && !body.isOneShot()) method(original.method, OneShotBody(body))
        }.build()
        val call = chain.call()
        upload.registerCall(call)
        try {
            val response = chain.proceed(request)
            val responseBody = response.body
            if (responseBody == null) {
                upload.unregisterCall(call)
                return response
            }
            // 收到响应头后仍可能阻塞读响应体，必须跟踪到 EOF/close，而非只跟踪 proceed。
            val source = object : ForwardingSource(responseBody.source()) {
                private var released = false
                private fun release() {
                    if (!released) {
                        released = true
                        upload.unregisterCall(call)
                    }
                }
                override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also { if (it == -1L) release() }
                } catch (e: Exception) { release(); throw e }
                override fun close() { try { super.close() } finally { release() } }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = responseBody.contentType()
                override fun contentLength() = responseBody.contentLength()
                override fun source() = source
            }).build()
        } catch (e: Exception) {
            upload.unregisterCall(call)
            throw e
        }
    }

    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }
}
