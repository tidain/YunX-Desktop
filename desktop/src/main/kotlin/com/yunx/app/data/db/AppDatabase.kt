package com.yunx.app.data.db

import com.yunx.app.AppContext
import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * 桌面版数据库：以 sqlite-jdbc（JDBC）替代 Room，公共 API（DAO 签名、加密装饰器）保持不变。
 * 库文件：<dataDir>/yunx.db，schema 即 Room v13 的最终形态（全新安装，无需历史迁移）。
 */
class AppDatabase private constructor(private val conn: Connection) {

    /** 凭证加密器（下载请求头 JSON 等也复用同一加密器，与原版一致）。 */
    internal val credentialCipher: CredentialCipher = FileCredentialCipher()

    private val rawQuark = JdbcQuarkAccountDao(conn)
    private val rawUc = JdbcUcAccountDao(conn)
    private val rawXunlei = JdbcXunleiAccountDao(conn)
    private val rawBaidu = JdbcBaiduAccountDao(conn)
    private val rawC139 = JdbcC139AccountDao(conn)
    private val rawPan123 = JdbcPan123AccountDao(conn)
    private val rawDownloadTask = JdbcDownloadTaskDao(conn)
    private val rawBookmark = JdbcBookmarkDao(conn)

    fun quarkAccountDao(): QuarkAccountDao = SecureAccountDaos.quark(rawQuark, credentialCipher)
    fun ucAccountDao(): UCAccountDao = SecureAccountDaos.uc(rawUc, credentialCipher)
    fun xunleiAccountDao(): XunleiAccountDao = SecureAccountDaos.xunlei(rawXunlei, credentialCipher)
    fun baiduAccountDao(): BaiduAccountDao = SecureAccountDaos.baidu(rawBaidu, credentialCipher)
    fun c139AccountDao(): C139AccountDao = SecureAccountDaos.c139(rawC139, credentialCipher)
    fun pan123AccountDao(): Pan123AccountDao = SecureAccountDaos.pan123(rawPan123, credentialCipher)
    fun downloadTaskDao(): DownloadTaskDao = rawDownloadTask
    fun bookmarkDao(): BookmarkDao = rawBookmark

    companion object {
        private const val TAG = "YunX-DB"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: open().also { instance = it }
            }

        private fun open(): AppDatabase {
            Class.forName("org.sqlite.JDBC")
            val dbFile = File(AppContext.dataDir, "yunx.db")
            dbFile.parentFile?.mkdirs()

            // 自愈：进程被强杀/崩溃后，WAL 的 -shm（共享内存索引）可能损坏或 mmap 失败
            // （SQLITE_IOERR_SHMMAP），表现为应用启动即崩。-shm 可由 sqlite 随时重建
            // （-wal 仍会回放，近期数据不丢）；若 -wal 本身也损坏，第二轮连 -wal 一起删，
            // 恢复到最后一次 checkpoint 状态——远好于无法启动。
            var lastError: Exception? = null
            repeat(3) { attempt ->
                var conn: Connection? = null
                try {
                    conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
                    conn.createStatement().use { st ->
                        st.execute("PRAGMA journal_mode=WAL")
                        st.execute("PRAGMA busy_timeout=5000")
                        st.execute("PRAGMA synchronous=NORMAL")
                    }
                    DDL.forEach { sql ->
                        conn.createStatement().use { it.execute(sql) }
                    }
                    Log.i(TAG, "database opened: ${dbFile.absolutePath}")
                    return AppDatabase(conn)
                } catch (e: Exception) {
                    lastError = e
                    runCatching { conn?.close() }
                    Log.e(TAG, "database open attempt ${attempt + 1} failed: ${e.message}")
                    runCatching {
                        when (attempt) {
                            0 -> {
                                // 第一轮：仅删 -shm（保留 -wal 以回放近期事务）
                                File("${dbFile.absolutePath}-shm").delete()
                                Log.w(TAG, "WAL shm removed, retrying open")
                            }
                            1 -> {
                                // 第二轮：-wal 也损坏，一并删除回退到最后 checkpoint
                                listOf("${dbFile.absolutePath}-shm", "${dbFile.absolutePath}-wal")
                                    .forEach { runCatching { File(it).delete() } }
                                Log.w(TAG, "WAL shm+wal removed, retrying open")
                            }
                        }
                        Thread.sleep(300L * (attempt + 1))
                    }
                }
            }
            throw lastError ?: IllegalStateException("database open failed")
        }

        /** Room v13 最终 schema 的 DDL（全新库直接建最终形态）。 */
        private val DDL = listOf(
            "CREATE TABLE IF NOT EXISTS quark_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS uc_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS xunlei_account (id TEXT PRIMARY KEY NOT NULL, accessToken TEXT NOT NULL, refreshToken TEXT NOT NULL, deviceId TEXT NOT NULL, captchaToken TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS baidu_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS c139_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, authorization TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pan123_account (id TEXT PRIMARY KEY NOT NULL, accessToken TEXT NOT NULL, account TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS download_task (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, fileName TEXT NOT NULL, totalSize INTEGER NOT NULL, downloadedSize INTEGER NOT NULL, status INTEGER NOT NULL, errorMsg TEXT NOT NULL, savePath TEXT NOT NULL, requestHeadersJson TEXT NOT NULL DEFAULT '{}', chunkCount INTEGER NOT NULL DEFAULT 0, plannedTotalSize INTEGER NOT NULL DEFAULT 0, cleanupId TEXT NOT NULL DEFAULT '', platform TEXT NOT NULL DEFAULT '', avgSpeed INTEGER NOT NULL DEFAULT 0, createTime INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS bookmark (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, link TEXT NOT NULL, title TEXT NOT NULL, platform TEXT NOT NULL, pwd TEXT NOT NULL, category TEXT NOT NULL, createTime INTEGER NOT NULL)"
        )
    }
}

/**
 * 单行表 DAO 骨架：一个 StateFlow 承载当前行，写操作后重新加载并推送。
 * 所有 JDBC 访问在 conn 上同步（sqlite-jdbc 单连接非线程安全），IO 调度由调用方 DAO 包装。
 */
private class JdbcSingleRowDao<T : Any>(
    private val conn: Connection,
    private val table: String,
    private val columns: List<String>,
    private val key: String,
    private val read: (ResultSet) -> T,
    private val bind: (PreparedStatement, T) -> Unit
) {
    private val _state = MutableStateFlow<T?>(null)
    val state: Flow<T?> = _state

    init {
        _state.value = load()
    }

    fun load(): T? = synchronized(conn) {
        conn.prepareStatement("SELECT * FROM `$table` WHERE id = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }
    }

    fun upsert(value: T) {
        synchronized(conn) {
            val colList = columns.joinToString(",") { "`$it`" }
            val placeholders = columns.joinToString(",") { "?" }
            conn.prepareStatement(
                "INSERT OR REPLACE INTO `$table` ($colList) VALUES ($placeholders)"
            ).use { ps ->
                bind(ps, value)
                ps.executeUpdate()
            }
        }
        _state.value = load()
    }

    fun clear() {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM `$table` WHERE id = ?").use { ps ->
                ps.setString(1, key)
                ps.executeUpdate()
            }
        }
        _state.value = null
    }
}

private suspend fun <T> dbIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private class JdbcQuarkAccountDao(conn: Connection) : QuarkAccountDao {
    private val d = JdbcSingleRowDao(
        conn, "quark_account", listOf("id", "cookie", "nickname", "updatedAt"), "quark",
        read = { rs -> QuarkAccountEntity("quark", rs.getString("cookie"), rs.getString("nickname"), rs.getLong("updatedAt")) },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname); ps.setLong(4, a.updatedAt)
        }
    )

    override fun observeAccount(): Flow<QuarkAccountEntity?> = d.state
    override suspend fun upsert(account: QuarkAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): QuarkAccountEntity? = dbIo { d.load() }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcUcAccountDao(conn: Connection) : UCAccountDao {
    private val d = JdbcSingleRowDao(
        conn, "uc_account", listOf("id", "cookie", "nickname", "updatedAt"), "uc",
        read = { rs -> UCAccountEntity("uc", rs.getString("cookie"), rs.getString("nickname"), rs.getLong("updatedAt")) },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname); ps.setLong(4, a.updatedAt)
        }
    )

    override fun observeAccount(): Flow<UCAccountEntity?> = d.state
    override suspend fun upsert(account: UCAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): UCAccountEntity? = dbIo { d.load() }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcXunleiAccountDao(conn: Connection) : XunleiAccountDao {
    private val d = JdbcSingleRowDao(
        conn, "xunlei_account",
        listOf("id", "accessToken", "refreshToken", "deviceId", "captchaToken", "nickname", "updatedAt"), "xunlei",
        read = { rs ->
            XunleiAccountEntity(
                "xunlei",
                rs.getString("accessToken"), rs.getString("refreshToken"), rs.getString("deviceId"),
                rs.getString("captchaToken"), rs.getString("nickname"), rs.getLong("updatedAt")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.accessToken); ps.setString(3, a.refreshToken)
            ps.setString(4, a.deviceId); ps.setString(5, a.captchaToken); ps.setString(6, a.nickname)
            ps.setLong(7, a.updatedAt)
        }
    )

    override fun observeAccount(): Flow<XunleiAccountEntity?> = d.state
    override suspend fun upsert(account: XunleiAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): XunleiAccountEntity? = dbIo { d.load() }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcBaiduAccountDao(conn: Connection) : BaiduAccountDao {
    private val d = JdbcSingleRowDao(
        conn, "baidu_account", listOf("id", "cookie", "nickname", "updatedAt"), "baidu",
        read = { rs -> BaiduAccountEntity("baidu", rs.getString("cookie"), rs.getString("nickname"), rs.getLong("updatedAt")) },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname); ps.setLong(4, a.updatedAt)
        }
    )

    override fun observeAccount(): Flow<BaiduAccountEntity?> = d.state
    override suspend fun upsert(account: BaiduAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): BaiduAccountEntity? = dbIo { d.load() }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcC139AccountDao(conn: Connection) : C139AccountDao {
    private val d = JdbcSingleRowDao(
        conn, "c139_account", listOf("id", "cookie", "nickname", "authorization", "updatedAt"), "c139",
        read = { rs ->
            C139AccountEntity(
                "c139", rs.getString("cookie"), rs.getString("nickname"),
                rs.getString("authorization"), rs.getLong("updatedAt")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setString(4, a.authorization); ps.setLong(5, a.updatedAt)
        }
    )

    override fun observeAccount(): Flow<C139AccountEntity?> = d.state
    override suspend fun upsert(account: C139AccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): C139AccountEntity? = dbIo { d.load() }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcPan123AccountDao(conn: Connection) : Pan123AccountDao {
    private val d = JdbcSingleRowDao(
        conn, "pan123_account", listOf("id", "accessToken", "account", "nickname", "updatedAt"), "pan123",
        read = { rs ->
            Pan123AccountEntity(
                "pan123", rs.getString("accessToken"), rs.getString("account"),
                rs.getString("nickname"), rs.getLong("updatedAt")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.accessToken); ps.setString(3, a.account)
            ps.setString(4, a.nickname); ps.setLong(5, a.updatedAt)
        }
    )

    override fun observeAccount(): Flow<Pan123AccountEntity?> = d.state
    override suspend fun upsert(account: Pan123AccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): Pan123AccountEntity? = dbIo { d.load() }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcDownloadTaskDao(private val conn: Connection) : DownloadTaskDao {

    private val _tasks = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())

    init {
        _tasks.value = loadAll()
    }

    private fun loadAll(): List<DownloadTaskEntity> = synchronized(conn) {
        conn.prepareStatement("SELECT * FROM download_task ORDER BY createTime DESC").use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DownloadTaskEntity>()
                while (rs.next()) out += readTask(rs)
                out
            }
        }
    }

    private fun reload() {
        _tasks.value = loadAll()
    }

    private fun readTask(rs: ResultSet) = DownloadTaskEntity(
        id = rs.getLong("id"),
        url = rs.getString("url"),
        fileName = rs.getString("fileName"),
        totalSize = rs.getLong("totalSize"),
        downloadedSize = rs.getLong("downloadedSize"),
        status = rs.getInt("status"),
        errorMsg = rs.getString("errorMsg"),
        savePath = rs.getString("savePath"),
        requestHeadersJson = rs.getString("requestHeadersJson"),
        chunkCount = rs.getInt("chunkCount"),
        plannedTotalSize = rs.getLong("plannedTotalSize"),
        cleanupId = rs.getString("cleanupId"),
        platform = rs.getString("platform"),
        avgSpeed = rs.getLong("avgSpeed"),
        createTime = rs.getLong("createTime")
    )

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = _tasks

    override suspend fun insert(task: DownloadTaskEntity): Long = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "INSERT INTO download_task(url,fileName,totalSize,downloadedSize,status,errorMsg,savePath," +
                    "requestHeadersJson,chunkCount,plannedTotalSize,cleanupId,platform,avgSpeed,createTime) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, task.url)
                ps.setString(2, task.fileName)
                ps.setLong(3, task.totalSize)
                ps.setLong(4, task.downloadedSize)
                ps.setInt(5, task.status)
                ps.setString(6, task.errorMsg)
                ps.setString(7, task.savePath)
                ps.setString(8, task.requestHeadersJson)
                ps.setInt(9, task.chunkCount)
                ps.setLong(10, task.plannedTotalSize)
                ps.setString(11, task.cleanupId)
                ps.setString(12, task.platform)
                ps.setLong(13, task.avgSpeed)
                ps.setLong(14, task.createTime)
                ps.executeUpdate()
                ps.generatedKeys.use { gk -> if (gk.next()) gk.getLong(1) else task.id }
            }
        }.also { reload() }
    }

    override suspend fun get(id: Long): DownloadTaskEntity? = dbIo {
        synchronized(conn) {
            conn.prepareStatement("SELECT * FROM download_task WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) readTask(rs) else null }
            }
        }
    }

    override suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long) = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "UPDATE download_task SET status = ?, downloadedSize = ?, totalSize = ? WHERE id = ?"
            ).use { ps ->
                ps.setInt(1, status)
                ps.setLong(2, downloadedSize)
                ps.setLong(3, totalSize)
                ps.setLong(4, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long) = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "UPDATE download_task SET chunkCount = ?, plannedTotalSize = ? WHERE id = ?"
            ).use { ps ->
                ps.setInt(1, chunkCount)
                ps.setLong(2, totalSize)
                ps.setLong(3, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET requestHeadersJson = ? WHERE id = ?").use { ps ->
                ps.setString(1, encryptedHeaders)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun markInterruptedAsPaused() = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET status = 2 WHERE status = 1 OR status = 0").use {
                it.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun updateStatus(id: Long, status: Int) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET status = ? WHERE id = ?").use { ps ->
                ps.setInt(1, status)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun updateError(id: Long, errorMsg: String) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET errorMsg = ? WHERE id = ?").use { ps ->
                ps.setString(1, errorMsg)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long) = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "UPDATE download_task SET status = ?, savePath = ?, avgSpeed = ? WHERE id = ?"
            ).use { ps ->
                ps.setInt(1, status)
                ps.setString(2, savePath)
                ps.setLong(3, avgSpeed)
                ps.setLong(4, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun delete(id: Long) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM download_task WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
        reload()
    }
}

private class JdbcBookmarkDao(private val conn: Connection) : BookmarkDao {

    private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())

    init {
        _bookmarks.value = loadAll()
    }

    private fun loadAll(): List<BookmarkEntity> = synchronized(conn) {
        conn.prepareStatement("SELECT * FROM bookmark ORDER BY createTime DESC").use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<BookmarkEntity>()
                while (rs.next()) {
                    out += BookmarkEntity(
                        id = rs.getLong("id"),
                        link = rs.getString("link"),
                        title = rs.getString("title"),
                        platform = rs.getString("platform"),
                        pwd = rs.getString("pwd"),
                        category = rs.getString("category"),
                        createTime = rs.getLong("createTime")
                    )
                }
                out
            }
        }
    }

    private fun reload() {
        _bookmarks.value = loadAll()
    }

    override fun observeAll(): Flow<List<BookmarkEntity>> = _bookmarks

    override fun observeCategories(): Flow<List<String>> = _bookmarks.map { list ->
        list.map { it.category }.distinct().sorted()
    }

    override suspend fun insert(bookmark: BookmarkEntity): Long = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "INSERT INTO bookmark(link,title,platform,pwd,category,createTime) VALUES(?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, bookmark.link)
                ps.setString(2, bookmark.title)
                ps.setString(3, bookmark.platform)
                ps.setString(4, bookmark.pwd)
                ps.setString(5, bookmark.category)
                ps.setLong(6, bookmark.createTime)
                ps.executeUpdate()
                ps.generatedKeys.use { gk -> if (gk.next()) gk.getLong(1) else bookmark.id }
            }
        }.also { reload() }
    }

    override suspend fun updateCategory(id: Long, category: String) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE bookmark SET category = ? WHERE id = ?").use { ps ->
                ps.setString(1, category)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun delete(id: Long) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM bookmark WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
        reload()
    }
}
