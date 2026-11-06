package com.yunx.app.data.motrix

import com.yunx.app.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 把一条下载直链推给本机 Motrix（内部是 aria2c 的 JSON-RPC 接口）。
 *
 * 为什么不能只复制 URL：夸克/UC/百度/115 的直链是带防盗链的 CDN 地址，缺少
 * Cookie / Referer / User-Agent 会被直接 412/403。所以这里把 YunX 取链时
 * 已经算好的请求头一并塞进 aria2 的 `header` 选项（aria2 会原样附在每个请求上）。
 *
 * 只做「投递」：任务加入 Motrix 后由它自己下载，YunX 不跟踪进度、不做断点管理。
 */
object MotrixClient {

    private const val TAG = "Motrix"

    /** aria2 JSON-RPC 要求的 Content-Type */
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 单次 RPC 超时（本机回环，正常是毫秒级；慢是异常，别让界面卡住） */
    private const val TIMEOUT_SECONDS = 8L

    /** RPC 返回体上限保护：正常只有几百字节，异常时避免把日志/内存撑爆 */
    private const val MAX_BODY_BYTES = 512 * 1024

    /** 投递结果 */
    sealed interface Result {
        /** 成功：返回 aria2 的 gid（任务 ID），便于排查 */
        data class Success(val gid: String) : Result
        /** 失败：message 直接用于界面提示（已翻译成人话） */
        data class Failure(val message: String) : Result
    }

    /**
     * 把直链加入 Motrix。
     *
     * @param config    RPC 参数（端口 / 密钥 / 默认目录）
     * @param url       下载直链（过了 CDN 优选的最终地址）
     * @param fileName  文件名；aria2 会按它落盘（同名自动加序号，见 Motrix 的 auto-file-renaming）
     * @param headers   请求头（Cookie / Referer / User-Agent / Origin ...），空 = 不带自定义头
     * @param saveDir   本次任务的保存目录；空串 = 跟随 Motrix 自己的下载目录
     * @param keepSubDirs 是否把 fileName 里的相对目录一起交给 aria2。
     *                 文件夹组投递时为 true：`A/B/c.mp4` 会落到 `<saveDir>/A/B/c.mp4`，
     *                 aria2 自己创建缺失的中间目录（已实测）。
     */
    fun addUri(
        config: MotrixConfig,
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        saveDir: String = "",
        keepSubDirs: Boolean = false
    ): Result {
        if (url.isBlank()) return Result.Failure("直链为空，无法发送")
        val options = JSONObject()
        // 文件名：aria2 的 out 正常只写文件名，目录走 dir；文件夹组投递时保留相对层级，
        // 由 aria2 在 dir 下按 out 里的子目录落盘（实测 aria2 会自动建目录）。
        val safeName = if (keepSubDirs) sanitizeRelativePath(fileName) else sanitizeFileName(fileName)
        if (safeName.isNotEmpty()) options.put("out", safeName)
        val dir = saveDir.trim().ifBlank { config.defaultDir.trim() }
        if (dir.isNotEmpty()) options.put("dir", dir)
        // 请求头：aria2 的 header 选项是字符串数组，每个元素形如 "Name: value"
        val headerLines = headers
            .filter { (k, v) -> k.isNotBlank() && v.isNotBlank() }
            .map { (k, v) -> "$k: $v" }
        if (headerLines.isNotEmpty()) options.put("header", JSONArray(headerLines))
        // 覆盖 Motrix 全局 UA：夸克/UC 等要求客户端 UA，用了浏览器 UA 会被降级或拒绝
        headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
            ?.let { options.put("user-agent", it) }

        val params = JSONArray().apply {
            put(JSONArray().put(url))
            put(options)
        }
        return call(config, "aria2.addUri", params).let { outcome ->
            when (outcome) {
                is RpcOutcome.Ok -> {
                    // aria2.addUri 的 result 就是 gid 本身（字符串），不是 {"gid": "..."}
                    val gid = (outcome.result as? String).orEmpty()
                    Log.i(TAG, "已发送到 Motrix：gid=$gid name=$safeName dir=$dir headers=${headers.keys}")
                    Result.Success(gid)
                }
                is RpcOutcome.Error -> Result.Failure(outcome.message)
            }
        }
    }

    /**
     * 连通性测试：读 aria2 版本。成功说明端口对、密钥对、Motrix 在跑。
     * 返回版本号；失败抛 [IOException]（调用方直接展示 message）。
     */
    fun version(config: MotrixConfig): String {
        return when (val outcome = call(config, "aria2.getVersion", JSONArray())) {
            // aria2.getVersion 的 result 是 {"version":"1.36.0","enabledFeatures":[...]}，
            // 版本号直接在 result 下，不是嵌套的 version 对象
            is RpcOutcome.Ok -> (outcome.result as? JSONObject)
                ?.optString("version").orEmpty().ifBlank { "未知版本" }
            is RpcOutcome.Error -> throw IOException(outcome.message)
        }
    }

    /** 读 Motrix 的全局下载目录（设置页展示「任务将保存到」用）；读不到返回空串 */
    fun globalDir(config: MotrixConfig): String {
        return when (val outcome = call(config, "aria2.getGlobalOption", JSONArray())) {
            is RpcOutcome.Ok -> (outcome.result as? JSONObject)?.optString("dir").orEmpty()
            is RpcOutcome.Error -> ""
        }
    }

    // ---------- 底层 RPC ----------

    /**
     * 专用客户端：**绕过用户配置的代理**。
     *
     * Motrix 的 RPC 在本机回环地址上，如果沿用 [com.yunx.app.data.network.HttpClients.apiClient]
     * （它会注入设置里的 HTTP 代理），127.0.0.1 的请求也可能被转到代理上而失败。
     */
    private fun rpcClient(): OkHttpClient = rpcClientCache ?: synchronized(this) {
        rpcClientCache ?: OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
            .build()
            .also { rpcClientCache = it }
    }

    @Volatile
    private var rpcClientCache: OkHttpClient? = null

    private sealed interface RpcOutcome {
        /**
         * result 的类型**因方法而异**，不能一律当 JSONObject：
         * - `aria2.addUri` → 字符串（新任务的 gid）
         * - `aria2.getVersion` / `aria2.getGlobalOption` → 对象
         * 所以这里保留原始 JSON 值，由各调用点自己转型。
         */
        data class Ok(val result: Any?) : RpcOutcome
        data class Error(val message: String) : RpcOutcome
    }

    /**
     * 发一次 JSON-RPC 调用。
     *
     * aria2 的返回有两种形态：
     * - 成功：`{"id":"yunx","jsonrpc":"2.0","result":{...}}`
     * - 失败：`{"id":"yunx","jsonrpc":"2.0","error":{"code":1,"message":"..."}}`
     * 密钥错误时端口是通的但 error 里是 "Unauthorized"，要翻译成用户看得懂的话。
     */
    private fun call(config: MotrixConfig, method: String, params: JSONArray): RpcOutcome {
        // aria2 的密钥参数放在 params 数组**第一个**，形如 "token:xxx"
        val finalParams = if (config.secret.isBlank()) {
            params
        } else {
            JSONArray().apply {
                put("token:${config.secret}")
                for (i in 0 until params.length()) put(params.opt(i))
            }
        }
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "yunx")
            put("method", method)
            put("params", finalParams)
        }
        val request = Request.Builder()
            .url(config.endpoint)
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        val outcome = runCatching {
            rpcClient()
                .newCall(request)
                .execute()
                .use { response ->
                    val body = response.body?.string().orEmpty().take(MAX_BODY_BYTES)
                    if (!response.isSuccessful) {
                        // 400 是最常见的：要么没带密钥，要么密钥错（aria2 用 HTTP 400 表达 RPC 层错误）
                        return@use RpcOutcome.Error(describeHttpFailure(response.code, body))
                    }
                    parseResponse(body)
                }
        }
        return outcome.getOrElse { e ->
            RpcOutcome.Error(describeTransportFailure(e))
        }
    }

    /** 解析正常返回体；结构异常时给出可读原因 */
    private fun parseResponse(body: String): RpcOutcome {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return RpcOutcome.Error("Motrix 返回了非 JSON 内容，可能端口被别的程序占用")
        json.optJSONObject("error")?.let { err ->
            val raw = err.optString("message").orEmpty()
            val code = err.optInt("code", 0)
            return RpcOutcome.Error(translateRpcError(raw, code))
        }
        // 注意：不能用 optJSONObject —— 它会把字符串类型的 result 也判成 null，
        // 而 aria2.addUri 返回的正是字符串 gid（"1a2b3c..."），当成对象会误报「缺少 result 字段」。
        if (!json.has("result") || json.isNull("result")) {
            return RpcOutcome.Error("Motrix 返回内容缺少 result 字段")
        }
        return RpcOutcome.Ok(json.opt("result"))
    }

    /** aria2 的错误码/错误串 → 中文提示 */
    private fun translateRpcError(raw: String, code: Int): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("unauthorized") || code == 1 && lower.contains("secret") ->
                "Motrix RPC 密钥不正确（$raw）"
            lower.contains("download aborted") -> "Motrix 拒绝了该直链（$raw）"
            raw.isBlank() -> "Motrix 返回错误码 $code"
            else -> "Motrix 返回错误：$raw"
        }
    }

    /** HTTP 层失败（状态码非 2xx）→ 中文提示 */
    private fun describeHttpFailure(code: Int, body: String): String {
        val snippet = body.trim().take(200)
        return when (code) {
            400 -> "Motrix 拒绝了请求（HTTP 400）：多半是 RPC 密钥不对，或 Motrix 未开启 RPC。$snippet".trim()
            401, 403 -> "Motrix 拒绝访问（HTTP $code）：请检查 RPC 密钥。$snippet".trim()
            404 -> "Motrix RPC 地址不对（HTTP 404）：请检查端口。$snippet".trim()
            else -> "Motrix 返回 HTTP $code。$snippet".trim()
        }
    }

    /** 传输层失败（连不上/超时）→ 中文提示 */
    private fun describeTransportFailure(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when (e) {
            is java.net.ConnectException -> "连不上 Motrix（$msg）：请确认 Motrix 正在运行，且已开启 RPC"
            is java.net.SocketTimeoutException -> "连接 Motrix 超时：请确认端口与 Motrix 设置一致"
            is java.net.UnknownHostException -> "Motrix RPC 地址无法解析：请检查设置"
            else -> "发送到 Motrix 失败：$msg"
        }
    }

    /**
     * 清理成 aria2 能接受的**相对路径**（保留 `/` 层级，供文件夹组投递用）。
     *
     * 逐段套用 [sanitizeFileName]，并丢掉空段、`.` 与 `..`：文件名里可能混进这些，
     * 拼进 out 会把文件写出下载目录。
     */
    private fun sanitizeRelativePath(name: String): String {
        val segments = name.replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitizeFileName(it) }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ""
        return segments.joinToString("/").take(1000)
    }

    /**
     * 清理成 aria2 能接受的文件名：去掉路径分隔符与控制字符（aria2 的 out 是文件名，
     * 带 `/` 会被它当成目录，`\` 在 Windows 上同理）。
     */
    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val cleaned = buildString {
            for (ch in trimmed) {
                when {
                    ch == '/' || ch == '\\' -> append('_')
                    ch.code < 0x20 -> Unit
                    else -> append(ch)
                }
            }
        }.trim().trim('.')
        return cleaned.take(200)
    }
}
