package com.yunx.app.ui.viewmodel

import com.yunx.app.data.upload.UploadSessions
import com.yunx.app.ui.SnackbarController

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yunx.app.data.db.MultiAccountEntity
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.repository.AccountDirectory
import com.yunx.app.util.CookieCleaner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * 多账号的账号维度操作：列表 / 设主账号 / 改备注 / 退出。
 *
 * ★ 为什么单独一个 ViewModel：这四件事对 10 个平台是同构的（见 [AccountDirectory]），
 *   塞进 10 个平台的 `*AccountViewModel` 只会得到 10 份一样的代码，
 *   还要给网盘页加几十个回调参数。平台专属的凭证读写仍留在各自的 Repository。
 */
class MultiAccountViewModel(
    private val directory: AccountDirectory
) : ViewModel() {

    /** 按平台缓存的账号列表：Compose 重组时不能每次都新建 Flow（会丢状态、重复查库） */
    private val caches = mutableMapOf<String, StateFlow<List<MultiAccountEntity>>>()

    /** 该平台全部已登录账号（主账号在前） */
    fun accountsOf(platform: String): StateFlow<List<MultiAccountEntity>> =
        caches.getOrPut(platform) {
            directory.observe(platform).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
        }

    fun setPrimary(platform: String, id: String) {
        viewModelScope.launch {
            try { directory.setPrimary(platform, id) }
            catch (e: IllegalStateException) { SnackbarController.show(e.message.orEmpty()) }
        }
    }

    fun rename(platform: String, id: String, remark: String) {
        viewModelScope.launch { directory.setRemark(platform, id, remark.trim()) }
    }

    /**
     * 准备「添加账号」：先清一次该平台的**JCEF 登录态**，上层随后打开该平台的登录页。
     *
     * ★ 不清的话，网页登录型平台（夸克 / UC / 百度 / 139 / 123 / 115 / 迅雷网页登录）的登录页
     *   会把内嵌浏览器里已有的登录态自动检测出来并保存 —— 于是「添加账号」加进来的还是同一个账号。
     * ★ 必须**等待**清理完成再开登录页（且不能阻塞 EDT，CEF 回调要靠 EDT 上的 timer 泵动）：
     *   旧实现在点击时同步等 3 秒，正好卡死回调泵，表现为「加第二个账号时登录页打不开」。
     */
    suspend fun prepareAddAccount(platform: String) {
        UploadSessions.requireIdle(platform)
        CookieCleaner.clearCookiesAndWait(webDomains(platform))
    }

    /**
     * 退出某个账号（只删这一条，其他账号照常可用）。
     *
     * ★ 该平台最后一个账号被删掉时，顺手清一次 JCEF 登录态：否则下次打开网页登录页会被
     *   残留 Cookie 自动登回旧账号，让「退出」形同虚设。
     */
    fun remove(platform: String, id: String) {
        viewModelScope.launch {
            try { directory.remove(platform, id) }
            catch (e: IllegalStateException) {
                SnackbarController.show(e.message.orEmpty())
                return@launch
            }
            if (directory.list(platform).isEmpty()) {
                CookieCleaner.clearCookiesForDomains(webDomains(platform))
            }
        }
    }

    /** 各平台网页登录所在的域名（清 JCEF Cookie 用；账号密码型平台没有网页登录态） */
    private fun webDomains(platform: String): List<String> = when (platform) {
        DownloadPlatform.QUARK -> listOf("pan.quark.cn", "quark.cn")
        DownloadPlatform.UC -> listOf("drive.uc.cn", "uc.cn")
        DownloadPlatform.XUNLEI -> listOf("pan.xunlei.com", "xunlei.com")
        DownloadPlatform.BAIDU -> listOf("pan.baidu.com", "yun.baidu.com", "baidu.com")
        DownloadPlatform.C139 -> listOf("yun.139.com", "139.com")
        DownloadPlatform.PAN123 -> listOf("www.123pan.com", "123pan.com")
        DownloadPlatform.PAN115 -> listOf("115.com", "115cdn.com")
        else -> emptyList()
    }

    class Factory(
        private val directory: AccountDirectory
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == MultiAccountViewModel::class)
            return MultiAccountViewModel(directory) as T
        }
    }
}
