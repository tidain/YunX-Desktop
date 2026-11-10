package com.yunx.app.util

import com.yunx.app.data.network.BaiduConstants
import java.net.URI

/** 浏览器 Cookie 的纯 JVM 快照，供 JCEF 与浏览器导入共享域筛选规则。 */
internal data class BaiduBrowserCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/"
)

/** 只选实际发送给 Pan/根域的 Cookie；Pan 同名字段优先，Passport 的 STOKEN 不会混入。 */
internal fun baiduCookieHeader(cookies: List<BaiduBrowserCookie>): String =
    BaiduConstants.extractCookies { url ->
        val host = URI(url).host.orEmpty().lowercase()
        cookies.asSequence()
            .filter { cookie ->
                val domain = cookie.domain.lowercase()
                val base = domain.removePrefix(".")
                val matches = host == base || (domain.startsWith('.') && host.endsWith(".$base"))
                matches && cookie.path == "/"
            }
            .sortedByDescending { it.domain.removePrefix(".").length }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }
