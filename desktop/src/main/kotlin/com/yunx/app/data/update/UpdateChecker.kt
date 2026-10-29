package com.yunx.app.data.update

import com.yunx.app.APP_VERSION
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 * 正式版通道（默认）：GET https://api.github.com/repos/tidain/YunX-Desktop/releases/latest
 * 预发布通道（设置页开启「接受预发布版更新」后）：GET .../releases
 */
object UpdateChecker {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/tidain/YunX-Desktop/releases/latest"

    /** Release 列表（按发布时间倒序，含 Pre-release）：开启「接受预发布版更新」时用它取最新一条 */
    private const val RELEASES_LIST_URL =
        "https://api.github.com/repos/tidain/YunX-Desktop/releases"

    private const val TAG = "YunX-Update"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：<prefix><原直链>；默认使用内置镜像前缀 */
    fun mirrorUrl(url: String, prefix: String = MIRROR_PREFIX): String = prefix + url

    data class Asset(
        val name: String,
        val downloadUrl: String
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String,
        /** 是否为 GitHub Pre-release：正式版通道恒为 false，预发布通道可能为 true */
        val prerelease: Boolean = false,
        /** 发布说明里的「强制更新：1」；没写或为 0 = 不强制 */
        val mandatory: Boolean = false,
        /** 发布说明里的「最低强制更新版本」（如 v1.3.0）；没写/为空 = 不强制 */
        val minMandatoryVersion: String? = null
    ) {

        /**
         * 当前版本是否需要**强制更新**（不更新就不能继续使用）。
         *
         * 规则（与发布说明约定一致）：`强制更新 = 1` 且 **当前版本 ≥ 最低强制更新版本**
         * 且 **当前版本 < 新版本**。两个字段缺任意一个 / 为空 → 一律不强制
         * （老用户、过旧版本都不拦，避免把人锁死在没法升级的版本上）。
         */
        fun isMandatoryFor(currentVersion: String): Boolean {
            if (!mandatory) return false
            val min = minMandatoryVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return compareVersions(currentVersion, min) >= 0 &&
                compareVersions(currentVersion, tagName) < 0
        }
    }

    /** 发布说明里的 `> 强制更新：1`（行首允许 markdown 引用/列表符号） */
    private val FORCE_FLAG_LINE = Regex("""^强制更新\s*[：:]\s*(\d+)$""")

    /** 发布说明里的 `> 最低强制更新版本：v1.3.0` */
    private val MIN_MANDATORY_LINE = Regex("""^最低强制更新版本\s*[：:]\s*v?(\d+(?:\.\d+)*)$""")

    /**
     * 从发布说明正文解析强制更新字段。
     *
     * 约定写成开头的一小段引用（见 Releases.md）：
     * ```
     * > 强制更新：1
     * > 最低强制更新版本：v1.3.0
     * ```
     * 逐行匹配、允许行首 `>`/`*`/`-` 等 markdown 装饰；字段缺失或值非法时按「不强制」处理。
     */
    fun parseMandatory(body: String): Pair<Boolean, String?> {
        var flag = false
        var minVersion: String? = null
        for (raw in body.lines()) {
            val line = raw.trim().trimStart('>', '*', '-', '+', ' ').trim()
            if (line.isEmpty()) continue
            FORCE_FLAG_LINE.find(line)?.let { match ->
                flag = match.groupValues[1].toIntOrNull() == 1
            }
            MIN_MANDATORY_LINE.find(line)?.let { match ->
                minVersion = match.groupValues[1]
            }
        }
        return flag to minVersion
    }

    /** capsule-render 头图（波浪 banner）：只要 `<img>` 里出现这个域名就整段删掉（不依赖属性顺序） */
    private val CAPSULE_BANNER_REGEX =
        Regex("""<img\b[^>]*capsule-render\.vercel\.app[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * QQ 群徽章（带外链的形式）：`[![任意文字](https://img.shields.io/badge/...)](http://qm.qq.com/...)`
     *
     * ★ 必须排在裸图片规则**前面**（见 [DECORATION_REGEXES]）：先整体吃掉外层链接，
     *   否则裸图片规则会把里面那截删掉，只剩一个 `[](http://qm.qq.com/...)` 的空链接。
     */
    private val QQ_BADGE_LINKED_REGEX = Regex(
        """\[!\[[^\]]*]\(\s*https://img\.shields\.io/[^)\s]*\)]\(\s*https?://[^)\s]*qm\.qq\.com[^)\s]*\s*\)"""
    )

    /** QQ 群徽章（裸图片形式）：`![任意文字](https://img.shields.io/badge/...&logo=qq...)` */
    private val QQ_BADGE_BARE_REGEX =
        Regex("""!\[[^\]]*]\(\s*https://img\.shields\.io/[^)\s]*logo=qq[^)\s]*\)""")

    /** Release 说明里对纯文本展示毫无意义、只该整段丢掉的装饰片段（顺序即执行顺序，别调换前两条） */
    private val DECORATION_REGEXES =
        listOf(CAPSULE_BANNER_REGEX, QQ_BADGE_LINKED_REGEX, QQ_BADGE_BARE_REGEX)

    /**
     * 过滤 Release 说明里对用户没用的装饰，只去这两类（**别扩大范围**，其余内容一字不动）：
     *
     * ① capsule-render 的头图 `<img src="https://capsule-render.vercel.app/api?..." />`；
     * ② QQ 群徽章那一行 `[![QQ交流群](https://img.shields.io/badge/QQ%E7%BE%A4-...?logo=qq)](http://qm.qq.com/...)`。
     *
     * 为什么要在客户端去：更新说明是用**纯文本** `Text()` 显示的（没上 markdown/HTML 渲染），
     * 这两样在 GitHub Release 页是图片，到我们这儿只会显示成一长串 URL，白占高度。
     * ★ 因此这里只认「capsule-render 的 img」和「指向 qm.qq.com / 带 logo=qq 的 shields.io 徽章」，
     *   不认 shields.io 的其它徽章（别的徽章可能是有信息量的）。
     *
     * 整行只剩装饰时**整行删掉**（这两样都是独占一行），否则会在说明开头留下一片空行；
     * 顺带把连续空行压成一个 —— markdown 本来就把多个空行当一个，压缩不改语义，只影响观感。
     */
    fun cleanReleaseNotes(body: String): String {
        if (body.isBlank()) return body
        val kept = ArrayList<String>()
        for (line in body.lines()) {
            val stripped = DECORATION_REGEXES.fold(line) { acc, regex -> regex.replace(acc, "") }.trim()
            // 整行都是装饰 → 丢掉整行；本来就是空行的原样留着（后面统一压缩连续空行）
            if (stripped.isEmpty() && line.isNotBlank()) continue
            if (stripped.isEmpty()) {
                if (kept.isNotEmpty() && kept.last().isEmpty()) continue
                kept.add("")
            } else {
                // 行内还夹着装饰（如「文字 <img ...>」）时只去掉片段，其余文字原样保留
                kept.add(stripped)
            }
        }
        // trim() 顺手去掉说明开头/结尾的空行（正文第一个字符往往就是换行）
        return kept.joinToString("\n").trim()
    }

    /**
     * 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0。
     * 兼容 fork 构建后缀（如 "1.2.6-gh1"）：每段取数字前缀比较，后缀不影响主版本比较。
     * 数字段完全相同时，只有两边都带后缀（预发布版，如 "1.3.0-beta1"）才继续比后缀：先比后缀里第一段数字
     * （beta2 > beta1），再按字符串比较；一边没有后缀则视为相等，免得把 fork 构建（1.2.6-gh1）判成比同号正式版旧。
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trimStart('v').split(".")
        val parts2 = v2.trimStart('v').split(".")
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val num1 = DIGITS.find(parts1.getOrNull(i).orEmpty())?.value?.toIntOrNull() ?: 0
            val num2 = DIGITS.find(parts2.getOrNull(i).orEmpty())?.value?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        if (v1 == v2) return 0
        val suffix1 = v1.trimStart('v').substringAfter('-', "")
        val suffix2 = v2.trimStart('v').substringAfter('-', "")
        if (suffix1.isEmpty() || suffix2.isEmpty()) return 0
        val pre1 = DIGITS.find(suffix1)?.value?.toIntOrNull() ?: 0
        val pre2 = DIGITS.find(suffix2)?.value?.toIntOrNull() ?: 0
        if (pre1 != pre2) return pre1 - pre2
        return suffix1.compareTo(suffix2)
    }

    private val DIGITS = Regex("\\d+")

    /** 当前应用版本号，来源：仓库根 version.txt（构建时生成 APP_VERSION，勿在此硬编码） */
    const val PC_VERSION = APP_VERSION

    fun currentVersion(): String = PC_VERSION

    /**
     * 请求 GitHub 最新 Release。
     * [includePrerelease] = true 时改走预发布通道（Release 列表接口，按发布时间倒序取第一条非 Draft 版本），
     * 这样标了 Pre-release 的版本也会被当成可更新版本；false 时走 `/releases/latest`（GitHub 只给正式版）。
     * 网络失败 / 仓库无 Release（404）返回 null。
     */
    suspend fun fetchLatestRelease(includePrerelease: Boolean = false): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val json = (if (includePrerelease) requestReleaseList() else requestLatest())
                ?: return@runCatching null
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return@runCatching null
            val assets = buildList {
                json.optJSONArray("assets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        add(Asset(a.optString("name"), a.optString("browser_download_url")))
                    }
                }
            }
            val rawBody = json.optString("body")
            // 强制更新字段从**原始正文**解析（清洗只去头图/徽章，这里用原文更稳）
            val (mandatory, minMandatoryVersion) = parseMandatory(rawBody)
            Release(
                tagName = tag,
                // 说明正文只在这一处清洗（更新弹窗读到的就是清洗后的那份，别再各清一遍）
                body = cleanReleaseNotes(rawBody),
                assets = assets,
                publishedAt = json.optString("published_at"),
                prerelease = json.optBoolean("prerelease"),
                mandatory = mandatory,
                minMandatoryVersion = minMandatoryVersion
            )
        }.onFailure { e ->
            Log.e(TAG, "获取最新 Release 异常：${e.javaClass.simpleName}: ${e.message}", e)
        }.getOrNull()
    }

    /** 正式版通道：GET /releases/latest，GitHub 保证返回最新的非 Pre-release、非 Draft 版本 */
    private fun requestLatest(): JSONObject? {
        val body = fetchBody(RELEASES_LATEST_URL) ?: return null
        return runCatching { JSONObject(body) }.getOrElse {
            Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON（前 200 字=${body.take(200)}）", it)
            null
        }
    }

    /** 预发布通道：GET /releases（数组、按发布时间倒序），取第一条非 Draft 且带 tag_name 的版本 */
    private fun requestReleaseList(): JSONObject? {
        val body = fetchBody(RELEASES_LIST_URL) ?: return null
        val array = runCatching { JSONArray(body) }.getOrElse {
            Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON 数组（前 200 字=${body.take(200)}）", it)
            return null
        }
        val json = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .firstOrNull { !it.optBoolean("draft") && it.optString("tag_name").isNotBlank() }
        if (json == null) {
            Log.e(TAG, "获取最新 Release 失败：列表里没有可用版本（全为 Draft 或缺 tag_name）")
        }
        return json
    }

    /** 发 GitHub API GET 请求；非 2xx / 空响应返回 null 并打 E 级日志 */
    private fun fetchBody(url: String): String? {
        val client = HttpClients.apiClient()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunX")
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "获取最新 Release 失败：HTTP ${resp.code}（$url）")
                return@use null
            }
            val text = resp.body?.string()
            if (text.isNullOrBlank()) {
                Log.e(TAG, "获取最新 Release 失败：响应体为空")
                null
            } else {
                text
            }
        }
    }
}