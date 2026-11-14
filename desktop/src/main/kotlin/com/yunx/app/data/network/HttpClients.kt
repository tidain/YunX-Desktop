package com.yunx.app.data.network

import com.yunx.app.data.upload.UploadRequestInterceptor

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端管理：
 * - [apiClient]：平台 API（登录/解析/直链）、HLS 下载、更新检查共用，超时宽松；
 * - [downloadClient]：分片下载专用，大 Dispatcher 保障分片并发（默认实例 maxRequestsPerHost=5 会锁死并发）。
 * 所有构建都使用系统证书链和 OkHttp 主机名校验，不提供进程内绕过开关。
 * 所有客户端共用同一 HTTP 协议开关（默认仅 HTTP/1.1）。
 */
object HttpClients {

    /** 下载客户端排队 Call 上限：分片下载走同步 call.execute()，真实并发受 DownloadManager 的在飞上限约束 */
    private const val MAX_QUEUED_CALLS = 64

    private val lock = Any()

    @Volatile
    private var apiCache: OkHttpClient? = null

    @Volatile
    private var downloadCache: OkHttpClient? = null

    @Volatile
    private var uploadCache: OkHttpClient? = null

    /** 当前生效的 HTTP 代理主机；null 表示直连 */
    @Volatile
    private var proxyHost: String? = null

    /** 当前生效的 HTTP 代理端口；0 表示直连 */
    @Volatile
    private var proxyPort: Int = 0

    /** 是否启用 HTTP/2；false（默认）时所有客户端仅使用 HTTP/1.1 */
    @Volatile
    private var http2Enabled: Boolean = false

    /**
     * 配置全局 HTTP 代理。host 为 null 或 port 不在 1-65535 时视为不使用代理（直连）。
     * 调用后清空已构建的客户端缓存，使下次获取时按新代理重建，立即生效。
     * 本对象不持有外部状态，仅依赖 java.net 标准库与 OkHttp 内置代理支持。
     */
    fun setProxy(host: String?, port: Int) {
        synchronized(lock) {
            proxyHost = host
            proxyPort = port
            // 使既有客户端失效：下次 apiClient()/downloadClient() 时重建，代理立即生效
            apiCache = null
            downloadCache = null
            uploadCache = null
        }
    }

    /**
     * 配置是否启用 HTTP/2。默认关闭：所有客户端仅协商 HTTP/1.1；
     * 开启后允许 ALPN 协商到 h2（理论上更快，实测差异通常不大）。
     * 调用后清空已构建的客户端缓存，使下次获取时按新协议重建，立即生效。
     */
    fun setHttp2Enabled(enabled: Boolean) {
        synchronized(lock) {
            http2Enabled = enabled
            // 使既有客户端失效：下次 apiClient()/downloadClient() 时重建，协议立即生效
            apiCache = null
            downloadCache = null
            uploadCache = null
        }
    }

    /** 按开关决定 ALPN 协商协议：关闭时仅 HTTP/1.1，开启时允许 h2 */
    private fun currentProtocols(): List<Protocol> =
        if (http2Enabled) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        else listOf(Protocol.HTTP_1_1)

    /** 构建代理实例；仅在代理主机与端口均有效时返回非 null，否则返回 null（直连） */
    private fun currentProxy(): Proxy? {
        val host = proxyHost ?: return null
        if (proxyPort !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, proxyPort))
    }

    /** 普通 API 客户端（各平台 API、HLS、更新检查） */
    fun apiClient(): OkHttpClient {
        apiCache?.let { return it }
        synchronized(lock) {
            apiCache?.let { return it }
            return buildApi().also { apiCache = it }
        }
    }

    /** 下载专用客户端：大 Dispatcher + 长超时，不锁死分片并发 */
    fun downloadClient(): OkHttpClient {
        downloadCache?.let { return it }
        synchronized(lock) {
            downloadCache?.let { return it }
            return buildDownload().also { downloadCache = it }
        }
    }

    /** 上传使用独立超时；禁止底层自动重放写请求。 */
    fun uploadClient(): OkHttpClient {
        uploadCache?.let { return it }
        synchronized(lock) {
            uploadCache?.let { return it }
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .protocols(currentProtocols())
                .addInterceptor(UploadRequestInterceptor())
                .apply { currentProxy()?.let { proxy(it) } }
                .build().also { uploadCache = it }
        }
    }

    private fun buildApi(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(currentProtocols())
            // 诊断模式：记方法/脱敏 URL/状态码/耗时，非 200/206 才带 body 摘要（关着时首行短路，零开销）
            .addInterceptor(UploadRequestInterceptor())
            .addInterceptor(DiagnosticNetworkInterceptor())
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                currentProxy()?.let { proxy -> proxy(proxy) }
            }
            .build()
    }

    private fun buildDownload(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            // 排队 Call 上限：分片下载走同步 call.execute()，根本不经过这个队列 —— 真实并发由
            // DownloadManager 的 inflightLimiter 与 chunkIoDispatcher 决定
            maxRequests = MAX_QUEUED_CALLS
            maxRequestsPerHost = MAX_QUEUED_CALLS
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // 空闲连接池收紧：默认 128 条 × 5 分钟会常驻 socket，下载是突发式，1 分钟足够复用
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 8,
                    keepAliveDuration = 1,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .protocols(currentProtocols())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 诊断模式：分片请求也记一行（高频，靠 DiagnosticLog 的每秒行数闸门限流）
            .addInterceptor(DiagnosticNetworkInterceptor())
            .apply {
                // 配置了有效代理时注入；否则保持默认直连，行为与改动前完全一致
                currentProxy()?.let { proxy -> proxy(proxy) }
            }
            .build()
    }
}
