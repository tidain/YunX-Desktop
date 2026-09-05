package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.repository.BaiduAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 百度账号 ViewModel：暴露登录态，供主页与登录页共享。
 */
class BaiduAccountViewModel(
    private val repository: BaiduAccountRepository
) : ViewModel() {

    val baiduAccount: StateFlow<BaiduAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 保存百度 Cookie；返回是否保存成功 */
    suspend fun saveBaiduAccount(cookie: String): Boolean =
        repository.saveBaiduAccount(cookie)

    /** 退出登录：清除本地 Cookie */
    fun logout() {
        viewModelScope.launch { repository.logoutBaidu() }
    }

    class Factory(
        private val repository: BaiduAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == BaiduAccountViewModel::class)
            return BaiduAccountViewModel(repository) as T
        }
    }
}