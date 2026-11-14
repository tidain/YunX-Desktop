package com.yunx.app.data.upload

import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.repository.AccountDirectory

/** 已知的账号缺失、失效或切换；区别于某一个文件的普通上传错误。 */
internal class UploadSessionException(message: String) : IllegalStateException(message)

/** 上传期间固定平台身份；不保存 Cookie、Token 或本地文件内容。 */
object UploadSessions {
    private val active = mutableMapOf<String, () -> Unit>()

    @Synchronized
    internal fun acquire(platform: String, cancel: () -> Unit): Boolean {
        if (platform in active) return false
        active[platform] = cancel
        return true
    }

    @Synchronized
    internal fun release(platform: String) { active.remove(platform) }

    @Synchronized
    fun isActive(platform: String): Boolean = platform in active

    fun requireIdle(platform: String) {
        check(!isActive(platform)) { "该网盘正在上传，请完成或取消上传后再切换、添加或退出账号" }
    }

    /** 账号写入与上传启动互斥，避免检查空闲后、数据库落盘前开始上传。 */
    internal suspend fun changeAccount(platform: String, change: suspend () -> Unit) {
        check(acquire(platform) {}) { "该网盘正在上传，请完成或取消上传后再操作账号" }
        try { change() } finally { release(platform) }
    }

    fun cancelAll() {
        val callbacks = synchronized(this) { active.values.toList() }
        callbacks.forEach { it() }
    }

    internal suspend fun primaryAccountId(platform: String): String? {
        val accounts = AccountDirectory(AppDatabase.get()).list(platform)
        return (accounts.firstOrNull { it.isPrimary } ?: accounts.firstOrNull())?.id
    }
}
