package com.yunx.app.data.repository

import com.yunx.app.data.db.BaiduAccountDao
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.db.resolveAccountSlot
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.util.CookieCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 百度账号数据仓库：数据库持久化 + 网络验证（gettemplatevariable 拿昵称）。
 */
class BaiduAccountRepository(
    private val dao: BaiduAccountDao,
    private val api: BaiduApi
) {

    /** 旧版本缺少 STOKEN 的中间态记录不作为可用登录；按主账号优先选择下一条完整凭证。 */
    fun observeAccount(): Flow<BaiduAccountEntity?> = dao.observeAccounts().map { accounts ->
        accounts.firstOrNull { BaiduConstants.isValidCookie(it.cookie) }
    }

    suspend fun getAccount(): BaiduAccountEntity? =
        dao.getAccounts().firstOrNull { BaiduConstants.isValidCookie(it.cookie) }

    /** 该平台全部账号可用的凭证（主账号在前）：多账号下载（实验性 C1）轮换取链用 */
    suspend fun allCredentials(): List<String> =
        dao.getAccounts().map { BaiduConstants.sanitizeCookie(it.cookie) }
            .filter { BaiduConstants.isValidCookie(it) }

    /** 退出登录：清理 JCEF Cookie + 清除本地记录 + 清空 bdstoken 缓存 */
    suspend fun logoutBaidu() {
        CookieCleaner.clearCookiesForDomains(listOf("pan.baidu.com", "yun.baidu.com", "baidu.com"))
        api.clearSessionCache()
        dao.clear()
    }

    /**
     * BDUSS + STOKEN 完整且服务端校验通过后才保存，避免中间态 Cookie 被当成成功登录。
     * 校验通过后清空 bdstoken 缓存，避免旧账号的 bdstoken 跨账号复用导致 errno=-6。
     */
    suspend fun saveBaiduAccount(cookie: String, newAccount: Boolean = false): Boolean {
        val cleaned = BaiduConstants.sanitizeCookie(cookie)
        if (!BaiduConstants.isValidCookie(cleaned)) return false
        api.clearSessionCache()
        val nickname = api.fetchNickname(cleaned) ?: return false
        // 重新登录时仍覆盖原始主账号，保留旧记录的备注和主账号标记。
        val slot = resolveAccountSlot(DownloadPlatform.BAIDU, dao.getAccount(), newAccount)
        dao.upsert(
            BaiduAccountEntity(
                id = slot.id,
                cookie = cleaned,
                nickname = nickname.ifBlank { "百度用户" },
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
        )
        return true
    }
}
