package com.yunx.app.data.update

import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 * 真实实现：GET https://api.github.com/repos/CYQawa/YunX/releases/latest
 */
object UpdateChecker {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/CYQawa/YunX/releases/latest"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：https://cdn.gh-proxy.org/<原直链> */
    fun mirrorUrl(url: String): String = MIRROR_PREFIX + url

    data class Asset(
        val name: String,
        val downloadUrl: String
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String
    )

    /** 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0 */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trimStart('v').split(".")
        val parts2 = v2.trimStart('v').split(".")
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val num1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    /** 当前应用版本号（桌面版固定常量，发布时随 packageVersion 同步更新） */
    const val PC_VERSION = "1.1.1"

    fun currentVersion(): String = PC_VERSION

    /**
     * 请求 GitHub 最新 Release；网络失败 / 仓库无 Release（404）返回 null。
     */
    suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClients.apiClient()
            val request = Request.Builder()
                .url(RELEASES_LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "YunX")
                .get()
                .build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string() ?: return@runCatching null
            }
            val json = JSONObject(body)
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
            Release(
                tagName = tag,
                body = json.optString("body"),
                assets = assets,
                publishedAt = json.optString("published_at")
            )
        }.getOrNull()
    }
}