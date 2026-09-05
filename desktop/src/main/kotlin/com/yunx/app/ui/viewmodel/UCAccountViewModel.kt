package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.UCAccountEntity
import com.yunx.app.data.repository.UCAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UC 账号 ViewModel。
 */
class UCAccountViewModel(
    private val repository: UCAccountRepository
) : ViewModel() {

    val ucAccount: StateFlow<UCAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    suspend fun saveUCAccount(cookie: String): Boolean =
        repository.saveUCAccount(cookie)

    fun logout() {
        viewModelScope.launch { repository.logoutUC() }
    }

    class Factory(
        private val repository: UCAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == UCAccountViewModel::class)
            return UCAccountViewModel(repository) as T
        }
    }
}