package com.yunx.app.data.db

import kotlinx.coroutines.flow.Flow

/**
 * 桌面版 DAO 接口：与原 Room DAO 方法签名完全一致。
 */

interface QuarkAccountDao {
    fun observeAccount(): Flow<QuarkAccountEntity?>
    suspend fun upsert(account: QuarkAccountEntity)
    suspend fun getAccount(): QuarkAccountEntity?
    suspend fun clear()
}

interface UCAccountDao {
    fun observeAccount(): Flow<UCAccountEntity?>
    suspend fun upsert(account: UCAccountEntity)
    suspend fun getAccount(): UCAccountEntity?
    suspend fun clear()
}

interface XunleiAccountDao {
    fun observeAccount(): Flow<XunleiAccountEntity?>
    suspend fun upsert(account: XunleiAccountEntity)
    suspend fun getAccount(): XunleiAccountEntity?
    suspend fun clear()
}

interface BaiduAccountDao {
    fun observeAccount(): Flow<BaiduAccountEntity?>
    suspend fun upsert(account: BaiduAccountEntity)
    suspend fun getAccount(): BaiduAccountEntity?
    suspend fun clear()
}

interface C139AccountDao {
    fun observeAccount(): Flow<C139AccountEntity?>
    suspend fun upsert(account: C139AccountEntity)
    suspend fun getAccount(): C139AccountEntity?
    suspend fun clear()
}

interface Pan123AccountDao {
    fun observeAccount(): Flow<Pan123AccountEntity?>
    suspend fun upsert(account: Pan123AccountEntity)
    suspend fun getAccount(): Pan123AccountEntity?
    suspend fun clear()
}

interface DownloadTaskDao {
    fun observeAll(): Flow<List<DownloadTaskEntity>>
    suspend fun insert(task: DownloadTaskEntity): Long
    suspend fun get(id: Long): DownloadTaskEntity?
    suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long)
    suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long)
    suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String)
    suspend fun markInterruptedAsPaused()
    suspend fun updateStatus(id: Long, status: Int)
    suspend fun updateError(id: Long, errorMsg: String)
    suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long = 0L)
    suspend fun delete(id: Long)
}

interface BookmarkDao {
    fun observeAll(): Flow<List<BookmarkEntity>>
    fun observeCategories(): Flow<List<String>>
    suspend fun insert(bookmark: BookmarkEntity): Long
    suspend fun updateCategory(id: Long, category: String)
    suspend fun delete(id: Long)
}
