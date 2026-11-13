package com.yunx.app.data.network

import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 百度网盘分享解析数据（xpan/share list 响应）。
 */
data class BaiduShareList(
    val title: String,
    val shareId: String,
    val uk: String,
    val files: List<ShareFile>
)

/**
 * 百度转存结果：转存后的新 fs_id + 新路径（locatedownload 用路径）。
 */
data class BaiduTransferResult(
    val fsId: String,
    val path: String
)

/** locatedownload 返回的一条 CDN/HTTP 候选。 */
data class BaiduCdnUrl(
    val url: String,
    val rank: Int,
    val encrypt: Int
)

/** locatedownload 的完整响应。 */
data class BaiduLocatedownloadInfo(
    /** 客户端发起 locatedownload 时使用的 Unix 秒（TPDATA 字段 16）。 */
    val localTime: Long,
    /** locatedownload 服务端返回的 Unix 秒（TPDATA 字段 17）。 */
    val timestamp: Long,
    val urls: List<BaiduCdnUrl>,
    val encryptKey: BaiduEncryptKey?,
    val encryptKeyList: List<BaiduEncryptKey>,
    val sl: Long,
    val fsl: Long,
    val maxContinuousFailure: Int,
    val bakRankSliceNum: Int
) {
    /**
     * 明文 CDN 候选：HTTP Range 可以直接写盘，不需要在下载器里再做一层 AES 解密。
     * 仅把明确标记为明文的 HTTPS 地址交给普通 HTTP 下载器，避免写入加密内容。
     */
    fun bestPlainCdnUrl(): String? {
        return urls.asSequence()
            .filter { it.encrypt == 0 && it.url.startsWith("https://", ignoreCase = true) }
            .sortedBy { it.rank }
            .firstOrNull()
            ?.url
    }
}

/** `/cms/fgid` 返回：P2P Seed 必需的文件全局 ID 与真实大小。 */
data class BaiduFgidInfo(
    val fgid: String,
    val size: Long
)

/**
 * 百度网盘 API 封装（OkHttp + Cookie 认证）：
 * 登录态 BDUSS 经 Cookie 携带；分享解析链路 share/verify → xpan/share → share/transfer → filemetas。
 * 全部基于抓包字段，错误码用 errno（0 表示成功）判定。
 */
class BaiduApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() },
    private val downloadModeProvider: () -> String = { BaiduConstants.DOWNLOAD_MODE_CDN }
) {
    /** 每次请求动态获取全局客户端（忽略 SSL 开关切换即时生效） */
    private val client get() = clientProvider()

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    /** 按登录态隔离缓存，防止切换账号时复用其他账号的 token/下载身份。 */
    private val bdstokenCache = ConcurrentHashMap<String, String>()
    private data class BaiduIdentity(val uid: String, val uinfo: String, val sk: ByteArray)
    private val identityCache = ConcurrentHashMap<String, BaiduIdentity>()

    private fun sessionKey(cookie: String): String =
        BaiduDownloadCrypto.sha1Hex(BaiduConstants.sanitizeCookie(cookie))

    // ---------- 账号 ----------

    /** 失败返回 null；验证成功而服务端未给昵称时返回空串。 */
    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val result = templateVariable(cookie, """["username"]""") ?: return@withContext null
        result.optString("username").trim().takeUnless { it == "null" }.orEmpty()
    }

    suspend fun getBdstoken(cookie: String): String? = withContext(Dispatchers.IO) {
        val key = sessionKey(cookie)
        bdstokenCache[key]?.let { return@withContext it }
        val result = templateVariable(cookie, """["bdstoken"]""") ?: return@withContext null
        val token = result.optString("bdstoken").takeIf { it.isNotBlank() && it != "null" }
            ?: return@withContext null
        bdstokenCache[key] = token
        token
    }

    suspend fun refreshBdstoken(cookie: String): String? {
        bdstokenCache.remove(sessionKey(cookie))
        return getBdstoken(cookie)
    }

    fun clearSessionCache() {
        bdstokenCache.clear()
        identityCache.clear()
    }

    private suspend fun templateVariable(cookie: String, fields: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val url = "https://pan.baidu.com/api/gettemplatevariable" +
                "?clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1&fields=" +
                URLEncoder.encode(fields, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("errno", -1) != 0) return@use null
                    json.optJSONObject("result")
                }
            }.getOrNull()
        }

    /** 获取当前账号 UID（gettemplatevariable 的 uk）。 */
    suspend fun fetchUid(cookie: String): String? = withContext(Dispatchers.IO) {
        val result = templateVariable(cookie, """["uk"]""") ?: return@withContext null
        result.optString("uk").trim().takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * 获取登录上报接口的 uinfo。
     *
     * 这是 BrowserEngine 的 sign_key，也是内核 encode_sk 的原文；
     * 不能把它当成 psign_sk，也不能把它直接当文本拼进 rand。
     */
    suspend fun fetchUinfo(cookie: String): String? = withContext(Dispatchers.IO) {
        val body = buildString {
            append("action=pc_uniyunguanjia_login&timestamp=")
            append(System.currentTimeMillis() / 1000)
        }
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/report/user")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: "{}")
                if (json.optInt("errno", -1) != 0) return@use null
                json.optString("uinfo").takeIf { it.isNotBlank() && it != "null" }
            }
        }.getOrNull()
    }

    /**
     * 查询文件 fgid（P2P Seed/TPDATA 的必需字段）。
     *
     * 实测接口只需 fsid、uk、path 三项，未签名的 GET 即可返回：
     * `{"error_code":0,"fgid":"...","fsize":...}`。
     */
    suspend fun fetchFgid(uid: String, fsId: String, path: String, cookie: String): BaiduFgidInfo? =
        withContext(Dispatchers.IO) {
            if (uid.isBlank() || fsId.isBlank() || path.isBlank()) return@withContext null
            val url = "https://pan.baidu.com/cms/fgid?method=query" +
                "&fsid=${urlEncode(fsId)}&uk=${urlEncode(uid)}&path=${urlEncode(path)}"
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("error_code", -1) != 0) return@use null
                    val fgid = json.optString("fgid").takeIf { it.isNotBlank() && it != "null" }
                        ?: return@use null
                    BaiduFgidInfo(
                        fgid = fgid,
                        size = json.optLong("fsize", -1L)
                    )
                }
            }.getOrNull()
        }

    private suspend fun identity(cookie: String): BaiduIdentity? {
        identityCache[sessionKey(cookie)]?.let { return it }
        val uid = fetchUid(cookie) ?: return null
        val uinfo = fetchUinfo(cookie) ?: return null
        val sk = BaiduDownloadCrypto.decodeUinfo(uinfo, uid)?.takeIf { it.isNotEmpty() } ?: return null
        return BaiduIdentity(uid, uinfo, sk).also { identityCache[sessionKey(cookie)] = it }
    }

    /**
     * 账号稳定设备标识。不采集硬件标识，只从 BDUSS 派生：
     * devuid 为 32 位大写十六进制，deviceid 为十进制数值。
     */
    private fun deviceIdentity(cookie: String): Pair<String, String> {
        val bduss = cookieValue(cookie, "BDUSS").orEmpty()
        val digest = BaiduDownloadCrypto.sha256("yunx|download|$bduss".toByteArray())
        val devuid = BaiduDownloadCrypto.hex(digest).take(32).uppercase()
        var numeric = 0L
        for (i in 0 until 8) {
            numeric = (numeric shl 8) or (digest[i].toLong() and 0xFF)
        }
        numeric = (numeric and Long.MAX_VALUE).coerceAtLeast(1L)
        return devuid to numeric.toString()
    }

    // ---------- 分享解析 ----------

    /**
     * 验证提取码：POST /share/verify，返回 randsk（URL 编码形式，直接作为 sekey 使用）。
     * @return sekey；失败抛异常（携带服务端 errmsg）
     */
    suspend fun verifyShare(surl: String, pwd: String, cookie: String): String =
        withContext(Dispatchers.IO) {
            val body = "pwd=${urlEncode(pwd)}&vcode_str=&vcode="
            val request = Request.Builder()
                .url("https://pan.baidu.com/share/verify?surl=$surl")
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("Referer", "https://pan.baidu.com/s/$surl")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body.toRequestBody(formMediaType))
                .build()
            val json = executeJson(request)
            checkErrno(json, "验证提取码失败")
            json.optString("randsk").takeIf { it.isNotBlank() }
                ?: throw BaiduApiException("未返回分享密钥")
        }

    /**
 * 列出分享文件：GET xpan/share?method=list。
 * 修复（文档《百度网盘解析问题修复》）：
 *  - 顶层 root=1，子目录 root=0（root=1 下百度忽略 dir → 子文件夹进不去）；
 *  - 子目录（root=0）必须携带 BDCLND cookie（= verify 返回的 randsk），否则 errno=2；
 *  - sekey 为空（公共分享）时省略 &sekey= 参数；
 *  - 无 sekey 却 errno!=0 → 实为加密分享，抛"该分享需要提取码"。
 * @param dir 分享内目录路径，根目录传 "/"（子目录传 "/folder"）
 * @return 文件列表 + share_id/uk（转存需要）
 */
suspend fun listShare(surl: String, sekey: String, dir: String, cookie: String, page: Int = 1): BaiduShareList =
    withContext(Dispatchers.IO) {
        val isRoot = dir.isBlank() || dir == "/"
        val root = if (isRoot) "1" else "0"
        val sekeyPart = if (sekey.isNotBlank()) "&sekey=$sekey" else ""
        val url = "https://pan.baidu.com/rest/2.0/xpan/share?method=list" +
            "&shorturl=$surl&page=$page&num=100&root=$root&dir=" +
            URLEncoder.encode(if (dir.isBlank()) "/" else dir, "UTF-8") +
            sekeyPart
        // 子目录(root=0)必须携带 BDCLND（= verify 的 randsk），否则 errno=2；顶层(root=1)无需
        val authCookie = if (sekey.isNotBlank() && !cookie.contains("BDCLND="))
            "$cookie; BDCLND=$sekey" else cookie
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authCookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Referer", "https://pan.baidu.com/s/$surl")
            .get()
            .build()
        val json = executeJson(request)
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 无 sekey 却失败 → 多数情况是加密分享，提示用户索取提取码。
            // 但 errno=-6 是「未登录/身份验证失败」（游客模式下常见），此时光提示提取码会让人白找密码，单独区分。
            if (sekey.isBlank()) {
                throw BaiduApiException(
                    if (errno == -6) "该分享需要提取码，或需要登录百度网盘（errno=$errno）"
                    else "该分享需要提取码（errno=$errno）"
                )
            }
            checkErrno(json, "获取分享文件列表失败")
        }
        val array = json.optJSONArray("list") ?: org.json.JSONArray()
        val files = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val isdir = item.optString("isdir") == "1"
                val path = item.optString("path")
                    add(
                        ShareFile(
                            // 目录用 path 作 fid（导航传参），文件用 fs_id（转存传参）
                            fid = if (isdir) path else item.optString("fs_id"),
                            fname = item.optString("server_filename"),
                            fsize = item.optLong("size"),
                            isdir = isdir,
                            pdirFid = path,
                            fidToken = "",
                            modifyTime = item.optString("server_mtime")
                        )
                    )
                }
            }
            BaiduShareList(
                title = json.optString("title"),
                shareId = json.optString("share_id"),
                uk = json.optString("uk"),
                files = files
            )
        }

    // ---------- 个人网盘 / 转存 ----------

    /** 创建目录（个人网盘根目录下），返回是否成功 */
    suspend fun createDir(path: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        // 官方新建文件夹用的是 api/create?a=commit（对齐抓包）：
        // filemanager?opera=mkdir 在纯 Cookie 认证下恒 errno=2（接口校验路径不同）。
        // 统一使用 UA_WEB + Referer pan.baidu.com/disk/main + body 完整参数
        val body = "path=${urlEncode(path)}&isdir=1&size&block_list=%5B%5D&method=post&dataType=json"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/create?a=commit&channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&clienttype=0&bdstoken=$bdstoken")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Referer", "https://pan.baidu.com/disk/main")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 列出个人网盘目录（检查临时转存目录是否存在），返回子项 path 集合 */
    suspend fun listDir(dir: String, cookie: String): List<String> = withContext(Dispatchers.IO) {
        val url = "https://pan.baidu.com/api/list?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&order=time&desc=1&dir=" + URLEncoder.encode(dir, "UTF-8") + "&num=100&page=1"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .get()
            .build()
        runCatching {
            val json = executeJson(request)
            if (json.optInt("errno") != 0) return@runCatching emptyList()
            val array = json.optJSONArray("list") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(it.optString("path")) }
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 转存分享文件到指定目录（同步返回结果）。
     * @return 转存后的新 fs_id + 新路径
     */
    suspend fun transfer(
        shareId: String,
        uk: String,
        sekey: String,
        fsId: String,
        toDir: String,
        cookie: String
    ): BaiduTransferResult = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val url = "https://pan.baidu.com/share/transfer?shareid=$shareId&from=$uk" +
            "&channel=chunlei&sekey=$sekey&ondup=newcopy&web=1&app_id=${BaiduConstants.APP_ID}" +
            "&bdstoken=$bdstoken&clienttype=0"
        val body = "fsidlist=%5B%22$fsId%22%5D&path=${urlEncode(toDir)}"
        // verify 响应会 Set-Cookie: BDCLND=<randsk>，transfer 必须携带（分享验证标识），
        // 缺失会 errno=2；BDCLND 值即 sekey（randsk），手动补齐
        val authCookie = if (cookie.contains("BDCLND=")) cookie else "$cookie; BDCLND=$sekey"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authCookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Origin", "https://pan.baidu.com")
            .header("Referer", "https://pan.baidu.com/s/")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "转存失败")
        val extra = json.optJSONObject("extra")
        val list = extra?.optJSONArray("list")
        val first = list?.optJSONObject(0)
        val fsIdNew = first?.optString("to_fs_id")?.takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("转存失败：未返回新文件")
        val pathNew = first?.optString("to")?.takeIf { it.isNotBlank() }
            ?: "$toDir/"
        BaiduTransferResult(fsId = fsIdNew, path = pathNew)
    }

    // ---------- 下载直链 ----------

    /** 兼容原有 CDN 调用；新下载调用传 fsId/fileSize 后可按设置选择 P2P。 */
    suspend fun locateDownload(path: String, cookie: String): String =
        locateDownload(path = path, fsId = "", cookie = cookie, forceCdn = true)

    suspend fun locateDownload(
        path: String,
        fsId: String,
        cookie: String,
        fileSize: Long = -1L,
        forceCdn: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val info = fetchLocatedownloadInfo(path, cookie)
        val cdn = info.bestPlainCdnUrl()
        val wantP2p = !forceCdn && downloadModeProvider() == BaiduConstants.DOWNLOAD_MODE_P2P
        if (!wantP2p || fsId.isBlank()) {
            return@withContext cdn
                ?: throw BaiduApiException("未返回可用明文 CDN 下载链接")
        }
        val account = identity(cookie)
        if (account == null) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "baidu_p2p_fallback",
                "无法取得 uid/uinfo，已回退 CDN"
            )
            return@withContext cdn
                ?: throw BaiduApiException("获取百度下载身份失败，请重新登录")
        }
        val fgid = fetchFgid(account.uid, fsId, path, cookie)
        if (fgid == null) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "baidu_p2p_fallback",
                "fgid 查询失败，已回退 CDN"
            )
            return@withContext cdn
                ?: throw BaiduApiException("百度 P2P 资源初始化失败，且没有可回退的 CDN 链接")
        }
        val size = fileSize.takeIf { it > 0L } ?: fgid.size
        if (size <= 0L) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK, "baidu_p2p_fallback",
                "文件大小未知，已回退 CDN"
            )
            return@withContext cdn
                ?: throw BaiduApiException("百度 P2P 缺少文件大小")
        }
        val (devuid, deviceId) = deviceIdentity(cookie)
        val keys = info.encryptKeyList.ifEmpty {
            listOfNotNull(info.encryptKey)
        }
        BaiduP2pSpec(
            fgid = fgid.fgid,
            fsId = fsId,
            uid = account.uid,
            path = path,
            fileSize = size,
            encryptKeys = keys,
            sl = info.sl,
            fsl = info.fsl,
            localTime = info.localTime,
            serverTime = info.timestamp,
            devuid = devuid,
            deviceId = deviceId,
            cdnUrls = info.urls
                .asSequence()
                .filter { it.encrypt == 0 && it.url.startsWith("https://", ignoreCase = true) }
                .sortedBy { it.rank }
                .map { it.url }
                .distinct()
                .take(3)
                .toList()
        ).toUrl()
    }

    /**
     * 动态签名 locatedownload，并解析 CDN 候选与 P2P 需要的加密 key。
     *
     * 签名顺序严格遵循内核：
     * 1. 所有值先做 URL 编码；
     * 2. 按 query key 字典序排序，只拼 value，两级 MD5 生成 psign；
     * 3. 重建 query，并把 psign 放最后。
     */
    private suspend fun fetchLocatedownloadInfo(
        path: String,
        cookie: String
    ): BaiduLocatedownloadInfo {
        val bduss = cookieValue(cookie, "BDUSS")
            ?: throw BaiduApiException("百度登录态缺少 BDUSS，请重新登录")
        val account = identity(cookie)
            ?: throw BaiduApiException("获取百度下载身份失败，请重新登录")
        val time = System.currentTimeMillis() / 1000
        val (devuid, deviceId) = deviceIdentity(cookie)
        val rand = BaiduDownloadCrypto.makeRand(
            bduss = bduss,
            uid = account.uid,
            sk = account.sk,
            time = time,
            devuid = devuid
        )
        val params = linkedMapOf(
            "ant" to "1",
            "app_id" to BaiduConstants.APP_ID,
            "channel" to "00000000000000000000000040000001",
            "check_blue" to "1",
            "clienttype" to "9",
            "deviceid" to deviceId,
            "devuid" to devuid,
            "dpkg" to "1",
            "dsize" to "4194304",
            "dtype" to "1",
            "eck" to "1",
            "ehps" to "1",
            "err_ver" to "1.0",
            "es" to "1",
            "esl" to "1",
            "method" to "locatedownload",
            "open_pflag" to "0",
            "path" to urlEncode(path),
            "rand" to rand,
            "sd" to "0",
            "time" to time.toString(),
            "ver" to "4.0",
            "version" to BaiduConstants.P2P_SDK_VERSION,
            "version_app" to "8.9.7.101",
            "vip" to "0"
        ).toSortedMap()
        val psign = BaiduDownloadCrypto.makePsign(params)
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" } +
            "&psign=$psign"
        val request = Request.Builder()
            .url("https://d.pcs.baidu.com/rest/2.0/pcs/file?$query")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_P2SP)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("0".toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取高速下载链接失败")

        val urls = buildList {
            val array = json.optJSONArray("urls")
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array?.optJSONObject(i) ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                add(
                    BaiduCdnUrl(
                        url = url,
                        rank = item.optInt("rank", Int.MAX_VALUE),
                        encrypt = item.optInt("encrypt", 1)
                    )
                )
            }
        }
        val rankParam = json.optJSONObject("rank_param")
        val primaryKey = json.optJSONObject("encrypt_key")?.let(BaiduEncryptKey::fromJson)
        val keyList = buildList {
            val array = json.optJSONArray("encrypt_key_list")
            for (i in 0 until (array?.length() ?: 0)) {
                array?.optJSONObject(i)?.let { add(BaiduEncryptKey.fromJson(it)) }
            }
            if (primaryKey != null && none {
                    it.version == primaryKey.version && it.key == primaryKey.key
                }
            ) {
                add(primaryKey)
            }
        }
        return BaiduLocatedownloadInfo(
            localTime = time,
            timestamp = json.optLong("timestamp", time),
            urls = urls,
            encryptKey = primaryKey,
            encryptKeyList = keyList,
            sl = json.optLong("sl", 120L),
            fsl = json.optLong("fsl", -1L),
            maxContinuousFailure = rankParam?.optInt("max_continuous_failure", 5) ?: 5,
            bakRankSliceNum = rankParam?.optInt("bak_rank_slice_num", 30) ?: 30
        )
    }

    /**
     * 获取下载直链：filemetas（按 fs_id 取链，个人网盘文件无 path 时使用）。
     * 注意：该 dlink（d.pcs.baidu.com）在文件被删除后立即失效，仅限文件仍存在时下载。
     * @return dlink；失败抛异常
     */
    suspend fun fileMetasDlink(fsId: String, cookie: String): String = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val fsids = URLEncoder.encode("""["$fsId"]""", "UTF-8")
        val url = "https://pan.baidu.com/api/filemetas?dlink=1&fsids=$fsids&bdstoken=$bdstoken" +
            "&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .get()
            .build()
        val json = executeJson(request)
        checkErrno(json, "获取下载链接失败")
        val info = json.optJSONArray("info")
        val dlink = info?.optJSONObject(0)?.optString("dlink")?.takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("未返回下载链接")
        dlink
    }

    /** 删除个人网盘文件（转存后清理），按完整路径删除 */
    suspend fun deleteFile(path: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val body = "filelist=${URLEncoder.encode("""["$path"]""", "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete" +
                "&bdstoken=$bdstoken&newVerify=1&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    // ---------- 云盘文件管理（百度网盘功能） ----------

    /**
     * 列出个人网盘目录（修复版）：
     *  - URL 使用 pan.baidu.com（旧 yun.baidu.com 在部分账号下偶发 errno=-6/191）
     *  - 携带 bdstoken + channel=chunlei + showempty=0，对齐官方 web 抓包
     *  - errno=-6（bdstoken 失效）→ 强制 refreshBdstoken 后重试一次
     *  - 其它非零 errno → 抛 IllegalStateException（不静默吞掉，避免误判为空目录）
     */
    suspend fun listCloudFiles(dir: String, cookie: String): List<ShareFile> = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
        // 首试：用当前 bdstoken（可能为空，仍尝试一次让服务端兜底）
        var (array, errno) = requestListCloudFiles(dir, cookie, bdstoken)
        if (errno == -6) {
            // bdstoken 失效：强制刷新后重试一次
            val fresh = refreshBdstoken(cookie) ?: bdstoken
            val (array2, errno2) = requestListCloudFiles(dir, cookie, fresh)
            array = array2
            errno = errno2
        }
        if (errno != 0) {
            throw IllegalStateException("百度网盘接口错误 (errno=$errno)")
        }
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    ShareFile(
                        fid = item.optString("fs_id"),
                        fname = item.optString("server_filename"),
                        fsize = item.optLong("size"),
                        isdir = item.optInt("isdir") == 1,
                        pdirFid = dir,
                        fidToken = item.optString("path"),
                        modifyTime = item.optString("server_mtime")
                    )
                )
            }
        }
    }

    /**
     * 构造并发送一次 list 请求（私有 helper，不重试不缓存）。
     * @return (list 数组, errno)；errno 由调用方判断是否重试
     */
    private suspend fun requestListCloudFiles(
        dir: String,
        cookie: String,
        bdstoken: String?
    ): Pair<JSONArray, Int> = withContext(Dispatchers.IO) {
        val bdstokenPart = bdstoken?.takeIf { it.isNotBlank() }?.let { "&bdstoken=$it" } ?: ""
        val url = "https://pan.baidu.com/api/list?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&channel=chunlei&showempty=0&order=time&desc=1&dir=" +
            URLEncoder.encode(dir, "UTF-8") + "&num=100&page=1" + bdstokenPart
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .get()
            .build()
        val json = executeJson(request)
        JSONArray(json.optJSONArray("list") ?: JSONArray()) to json.optInt("errno")
    }

    /** 重命名（filemanager opera=rename，按完整路径） */
    suspend fun renameFile(path: String, newName: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val filelist = """[{"path":"$path","newname":"$newName"}]"""
        val body = "filelist=${URLEncoder.encode(filelist, "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=0&onnest=fail&opera=rename" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 移动单个/多个文件（filemanager opera=move，path→dest，newname 保持原名） */
    suspend fun moveFiles(
        paths: List<String>,
        dest: String,
        cookie: String
    ): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val items = paths.joinToString(",") { p ->
            val name = p.substringAfterLast('/')
            """{"path":"$p","dest":"$dest","newname":"$name"}"""
        }
        val body = "filelist=${URLEncoder.encode("[$items]", "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=move" +
                "&bdstoken=$bdstoken&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 批量删除（filemanager opera=delete，按完整路径） */
    suspend fun deleteFiles(paths: List<String>, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie) ?: return@withContext false
        val body = "filelist=${URLEncoder.encode(paths.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete" +
                "&bdstoken=$bdstoken&newVerify=1&clienttype=0&app_id=${BaiduConstants.APP_ID}&web=1")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        runCatching {
            val json = executeJson(request)
            json.optInt("errno") == 0
        }.getOrDefault(false)
    }

    /** 百度创建分享结果
     *
     *  @param expiredType 服务端返回的有效期（0永久/1一天/7七天/30三十天）；响应未返回该字段时为 null，
     *    由调用方回退到用户所选值（别用 0 兜底——0 是「永久有效」，会把创建失败静默显示成永久）。
     */
    data class BaiduShareResult(
        val link: String,
        val pwd: String,
        val shareId: String,
        val expiredType: Int?
    )

    /** 创建分享（share/set，按 fs_id 列表 + 有效期 period + 4 位提取码）
     *
     *  @param period 百度 API 的有效期**字面值**：0=永久、1=一天、7=七天、30=三十天。
     *    ⚠️ 不是 UI 中性码（1/2/3/4），调用方必须先用
     *    [com.yunx.app.data.network.model.ShareExpire.baiduPeriod] 转换。
     */
    suspend fun createShare(
        fsIds: List<String>,
        period: Int,
        pwd: String,
        cookie: String
    ): BaiduShareResult = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(cookie)
            ?: throw BaiduApiException("获取 bdstoken 失败，请重新登录")
        val fidList = fsIds.joinToString(",", "[", "]")
        val body = "fid_list=${URLEncoder.encode(fidList, "UTF-8")}" +
            "&schannel=4&channel_list=%5B%5D&period=$period&pwd=${urlEncode(pwd)}"
        val request = Request.Builder()
            .url("https://pan.baidu.com/share/set?channel=chunlei&web=1" +
                "&app_id=${BaiduConstants.APP_ID}&bdstoken=$bdstoken&clienttype=0")
            .header("Cookie", cookie)
            .header("User-Agent", BaiduConstants.UA_WEB)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", "https://pan.baidu.com/disk/main")
            .post(body.toRequestBody(formMediaType))
            .build()
        val json = executeJson(request)
        checkErrno(json, "创建分享失败")
        val link = json.optString("shorturl").ifBlank { json.optString("link") }
            .takeIf { it.isNotBlank() } ?: throw BaiduApiException("未返回分享链接")
        BaiduShareResult(
            link = link,
            pwd = pwd,
            shareId = json.optString("shareid"),
            // 用 opt + Number 转换：字段缺失 / 显式 null / 非数字一律回 null，
            // 交给调用方回退到用户所选值（optInt 会把它们变成 0，而 0 是「永久有效」，会显示成假的有效期）
            expiredType = (json.opt("expiredType") as? Number)?.toInt()
        )
    }

    // ---------- 网盘空间详情 ----------

    /** 网盘空间详情（GET pan.baidu.com/api/quota：total / used） */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        val url = "https://pan.baidu.com/api/quota?clienttype=0&app_id=${BaiduConstants.APP_ID}" +
            "&web=1&channel=chunlei&version=${System.currentTimeMillis()}"
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", BaiduConstants.UA_WEB)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://pan.baidu.com/disk/main")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.use { it.body?.string() ?: return@runCatching null }
            val json = JSONObject(body)
            if (json.optInt("errno") != 0) return@runCatching null
            QuotaInfo(
                used = json.optLong("used"),
                total = json.optLong("total")
            )
        }.getOrNull()
    }

    // ---------- 公共 ----------

    /**
     * 执行 JSON 请求（suspend，支持 errno=8888 限流自动重试）：
     *  - 百度网盘偶发 errno=8888（接口限流），自动重试 3 次（间隔 2 秒）；
     *  - 每次 retry 都重新构造 Request（OkHttp Request 一旦执行即被消耗，不能复用）；
     *  - 重试期间补充 Accept / Accept-Language 头，模拟浏览器正常请求降低被限流概率；
     *  - 最终仍为 8888 → 抛 BaiduApiException，提示用户稍后再试。
     */
    private suspend fun executeJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        // 首试：直接执行原始 request
        var json = doExecuteOnce(request)
        var errno = json.optInt("errno", 0)
        if (errno == 8888) {
            // 限流：重试 3 次，每次重建 Request 并补头
            repeat(3) { attempt ->
                delay(2000)
                val rebuilt = Request.Builder()
                    .url(request.url)
                    .headers(request.headers)
                    .method(request.method, request.body)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                json = doExecuteOnce(rebuilt)
                errno = json.optInt("errno", 0)
                if (errno != 8888) return@repeat
            }
        }
        json
    }

    /** 单次执行并解析 JSON（不重试，仅做基础响应校验） */
    private fun doExecuteOnce(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw BaiduApiException("请求失败：响应为空") }
        return runCatching { JSONObject(body) }.getOrElse {
            throw BaiduApiException("响应解析失败")
        }
    }

    private fun checkErrno(json: JSONObject, fallback: String) {
        val errno = json.optInt("errno")
        if (errno != 0) {
            // 常见：errno=-12 提取码错误 / 403 分享已失效 / 31066 文件不存在
            val msg = json.optString("err_msg").ifBlank { json.optString("show_msg") }.ifBlank { fallback }
            val hint = if (errno == -6) "；${BaiduConstants.INCOMPLETE_LOGIN_HINT}" else ""
            throw BaiduApiException("$msg（errno=$errno）$hint")
        }
    }

    private fun cookieValue(cookie: String, name: String): String? =
        BaiduConstants.sanitizeCookie(cookie).split(';').asSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
