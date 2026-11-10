package com.yunx.app.ui.login

import com.yunx.app.data.network.BaiduConstants

/** 登录跳转分步写 Cookie：页面加载或凭证变化后重新计数，连续两次完整样本才可验证。 */
internal class BaiduLoginCookiePolicy {
    private var candidate = ""
    private var stableSamples = 0

    fun sample(cookie: String?, pageLoading: Boolean): String? {
        val cleaned = BaiduConstants.sanitizeCookie(cookie)
        if (pageLoading || !BaiduConstants.isValidCookie(cleaned)) {
            candidate = ""
            stableSamples = 0
            return null
        }
        stableSamples = if (cleaned == candidate) stableSamples + 1 else 1
        candidate = cleaned
        return cleaned.takeIf { stableSamples >= 2 }
    }
}
