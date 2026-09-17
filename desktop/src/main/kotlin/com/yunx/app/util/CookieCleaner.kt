package com.yunx.app.util

import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookieManager

/**
 * 退出登录时清除 JCEF 浏览器中指定网盘域名的 Cookie。
 *
 * 所有 AccountRepository.logout 都会调用本工具，避免再次登录时被
 * JCEF 已保留的会话 Cookie 自动登录。JCEF 未初始化（用户从未打开过登录页）时静默忽略。
 */
object CookieCleaner {

    /**
     * 清除给定域名列表下的所有 Cookie。
     * @param domains 域名片段列表，如 ["pan.baidu.com", "baidu.com"]；
     *                cookie 的 domain 命中任一（忽略前导点、忽略大小写）即清除。
     */
    fun clearCookiesForDomains(domains: List<String>) {
        if (domains.isEmpty()) return
        runCatching {
            val manager = CefCookieManager.getGlobalManager() ?: return@runCatching

            // 方案 1：遍历全部 cookie，命中域名时通过 visit 的 delete 出参直接删除。
            // visit 回调在 CEF IO 线程异步执行，用锁+条件等待其遍历完成。
            val lock = Object()
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            manager.visitAllCookies(object : CefCookieVisitor {
                override fun visit(
                    cookie: org.cef.network.CefCookie,
                    count: Int,
                    total: Int,
                    delete: BoolRef
                ): Boolean {
                    val cookieDomain = (cookie.domain ?: "").removePrefix(".").lowercase()
                    val hit = domains.any { d ->
                        val base = d.lowercase()
                        cookieDomain == base || cookieDomain.endsWith(".$base")
                    }
                    if (hit) {
                        // 设置 delete 出参为 true，CEF 会在遍历结束后删除该 cookie
                        runCatching { delete.set(true) }
                    }
                    // 最后一条（count+1==total）后 CEF 会以 count=total 再回调一次表示结束
                    if (count + 1 >= total) {
                        synchronized(lock) { finished.set(true); lock.notifyAll() }
                    }
                    return true
                }
            })
            // 等待遍历（含删除）完成，最多 3 秒
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + 3000
                while (!finished.get() && System.currentTimeMillis() < deadline) {
                    runCatching { lock.wait(300) }
                }
            }

            // 方案 2（兜底）：按 URL 批量删除，http/https 都覆盖，cookieName=null 表示该 URL 下全部。
            domains.forEach { d ->
                runCatching { manager.deleteCookies("https://$d", null) }
                runCatching { manager.deleteCookies("http://$d", null) }
            }
        }
        Unit
    }
}
