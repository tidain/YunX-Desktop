package com.yunx.app.data.repository

import com.yunx.app.data.db.BaiduAccountDao
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.util.CookieCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 百度账号数据仓库：Room 持久化 + 网络验证（gettemplatevariable 拿昵称）。
 */
class BaiduAccountRepository(
    private val dao: BaiduAccountDao,
    private val api: BaiduApi
) {

    fun observeAccount(): Flow<BaiduAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): BaiduAccountEntity? = dao.getAccount()

    /** 退出登录：清理 JCEF Cookie + 清除本地记录 + 清空 bdstoken 缓存 */
    suspend fun logoutBaidu() {
        CookieCleaner.clearCookiesForDomains(listOf("pan.baidu.com", "yun.baidu.com", "baidu.com"))
        api.clearSessionCache()
        dao.clear()
    }

    /**
     * 校验 Cookie 有效性（需含 BDUSS）；有效则拉取昵称并落库，返回 true；无效返回 false。
     * 校验通过后清空 bdstoken 缓存，避免旧账号的 bdstoken 跨账号复用导致 errno=-6。
     */
    suspend fun saveBaiduAccount(cookie: String): Boolean {
        if (!BaiduConstants.isValidCookie(cookie)) return false
        api.clearSessionCache()
        val nickname = api.fetchNickname(cookie) ?: "百度用户"
        dao.upsert(
            BaiduAccountEntity(
                id = "baidu",
                cookie = cookie,
                nickname = nickname
            )
        )
        return true
    }
}