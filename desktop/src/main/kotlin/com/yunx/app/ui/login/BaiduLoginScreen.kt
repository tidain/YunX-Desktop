package com.yunx.app.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.jcef.JcefLoginPane
import com.yunx.app.ui.viewmodel.BaiduAccountViewModel

/**
 * 百度网盘登录页：内嵌浏览器优先，粘贴/自动导入兜底。
 * Cookie 需包含 BDUSS=。
 */
@Composable
fun BaiduLoginScreen(
    viewModel: BaiduAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    // CEF 后台初始化：browserReady 变化时自动切换浏览器模式；用户可手动切粘贴模式
    var pasteMode by remember { mutableStateOf(false) }
    val useBrowser = JcefHolder.isAvailable() && !pasteMode
    if (useBrowser) {
        JcefLoginPane(
            loginUrl = BaiduConstants.LOGIN_URL,
            domains = listOf(".baidu.com"),
            requiredKeys = listOf("BDUSS"),
            platform = "BAIDU",
            onSave = { cookie -> viewModel.saveBaiduAccount(cookie) },
            onBack = onBack,
            onSaved = onSaved,
            onSwitchToPaste = { pasteMode = true }
        )
    } else {
        CookieLoginContent(
            title = "百度网盘登录",
            loginUrl = BaiduConstants.LOGIN_URL,
            platform = "BAIDU",
            cookieRequirement = "Cookie 需包含 BDUSS=",
            validityHint = "Cookie 失效后需重新登录",
            onSave = { cookie -> viewModel.saveBaiduAccount(cookie) },
            onBack = onBack,
            onSaved = onSaved
        )
    }
}
