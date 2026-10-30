package com.yunx.app.data.db

import com.yunx.app.AppContext
import com.yunx.app.data.security.CredentialCipher
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.app.util.DiagnosticLog
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
    private val rawPan115 = JdbcPan115AccountDao(conn)
    private val rawGuangYa = JdbcGuangYaAccountDao(conn)
    private val rawILanzou = JdbcILanzouAccountDao(conn)
    private val rawLanzou = JdbcLanzouAccountDao(conn)
    private val rawVideo = JdbcVideoAccountDao(conn)
    private val rawDownloadTask = JdbcDownloadTaskDao(conn)
    private val rawBookmark = JdbcBookmarkDao(conn)
    private val rawLinkHistory = JdbcLinkHistoryDao(conn)

    fun quarkAccountDao(): QuarkAccountDao = SecureAccountDaos.quark(rawQuark, credentialCipher)
    fun ucAccountDao(): UCAccountDao = SecureAccountDaos.uc(rawUc, credentialCipher)
    fun xunleiAccountDao(): XunleiAccountDao = SecureAccountDaos.xunlei(rawXunlei, credentialCipher)
    fun baiduAccountDao(): BaiduAccountDao = SecureAccountDaos.baidu(rawBaidu, credentialCipher)
    fun c139AccountDao(): C139AccountDao = SecureAccountDaos.c139(rawC139, credentialCipher)
    fun pan123AccountDao(): Pan123AccountDao = SecureAccountDaos.pan123(rawPan123, credentialCipher)
    fun pan115AccountDao(): Pan115AccountDao = SecureAccountDaos.pan115(rawPan115, credentialCipher)
    fun guangyaAccountDao(): GuangYaAccountDao = SecureAccountDaos.guangya(rawGuangYa, credentialCipher)
    fun ilanzouAccountDao(): ILanzouAccountDao = SecureAccountDaos.ilanzou(rawILanzou, credentialCipher)
    fun lanzouAccountDao(): LanzouAccountDao = SecureAccountDaos.lanzou(rawLanzou, credentialCipher)
    fun videoAccountDao(): VideoAccountDao = SecureAccountDaos.videoAccount(rawVideo, credentialCipher)
    fun downloadTaskDao(): DownloadTaskDao = rawDownloadTask
    fun bookmarkDao(): BookmarkDao = rawBookmark
    fun linkHistoryDao(): LinkHistoryDao = rawLinkHistory

    /**
     * 把 WAL 里的已提交事务全部落回主库并截断 WAL（进程退出前调用）。
     * 这样即使下次启动时 -wal 出问题，主库里也有完整数据。
     */
    internal fun checkpoint() {
        runCatching {
            conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
    }

    companion object {
        private const val TAG = "YunX-DB"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: open().also { instance = it }
            }

        /** 进程退出前调用：把 WAL 落回主库（库没打开过就什么都不做） */
        fun checkpointOnExit() {
            instance?.checkpoint()
        }

        private fun open(): AppDatabase {
            Class.forName("org.sqlite.JDBC")
            // 数据库目录可在「设置 → 储存目录」改（默认＝数据根目录）；启动时已由 AppContext 解析好
            val dbFile = File(AppContext.dbDir, "yunx.db")
            dbFile.parentFile?.mkdirs()

            // 自愈：进程被强杀/崩溃后，WAL 的 -shm（共享内存索引）可能损坏或 mmap 失败
            // （SQLITE_IOERR_SHMMAP），表现为应用启动即崩。-shm 由 sqlite 随时可重建，
            // 删它不影响数据（-wal 仍会回放）。
            // ★ 绝不再无条件删 -wal：WAL 模式下「主库可能只有 4KB、全部数据都在 -wal 里」
            //   （进程被强杀 = 没做过 checkpoint），删掉 -wal 等于清空数据库。
            //   真要绕开损坏的 -wal 时，先把 db 与 wal 备份到 yunx.db.bak-<时间戳> 再重命名。
            var lastError: Exception? = null
            repeat(3) { attempt ->
                var conn: Connection? = null
                try {
                    conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
                    conn.createStatement().use { st ->
                        st.execute("PRAGMA journal_mode=WAL")
                        st.execute("PRAGMA busy_timeout=5000")
                        st.execute("PRAGMA synchronous=NORMAL")
                        // 每次提交后就把 WAL 落回主库（本应用写入量很小，代价可忽略）。
                        // 默认阈值是 1000 页，小库永远触发不到 —— 一旦进程被强杀，
                        // 全部数据都还躺在 -wal 里，-wal 丢了就等于整库丢失（登录态消失的根因）。
                        st.execute("PRAGMA wal_autocheckpoint=1")
                    }
                    DDL.forEach { sql ->
                        conn.createStatement().use { it.execute(sql) }
                    }
                    // 迁移：旧库缺少 shareUrl 列时补上（CREATE TABLE IF NOT EXISTS 不会改既有表结构）
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN shareUrl TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    // 迁移：旧库缺少 engineTaskId 列时补上（外部 Gopeed 引擎任务 ID；空串=内置下载器）
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN engineTaskId TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    // 迁移：旧库缺少 altSourcesJson 列时补上（多账号下载 C2 的备选直链，加密落库；空串=单源）
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN altSourcesJson TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    // 迁移：旧库缺少 saveDir 列时补上（单次下载的保存目录覆盖；空串=跟随全局设置）
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN saveDir TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    // 迁移：实验性直写临时文件路径 / 模式
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN directPart TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN directMode INTEGER NOT NULL DEFAULT 0")
                        }
                    }
                    // 迁移：DASH 分轨下载（哔哩哔哩高清）与动图合成所需的三列
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN mergeAudioUrl TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN mergeAudioHeadersJson TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN motionUrl TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    // 迁移：浏览器插件任务级线程数覆盖（0 = 跟随平台的线程数设置）
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE download_task ADD COLUMN threads INTEGER NOT NULL DEFAULT 0")
                        }
                    }
                    // 迁移：旧库缺少 authType 列时补上（迅雷登录方式；空串=App 通道，webToken=网页登录）
                    runCatching {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE xunlei_account ADD COLUMN authType TEXT NOT NULL DEFAULT ''")
                        }
                    }
                    // 迁移：多账号（isPrimary / remark）。旧库逐表补列，已存在时忽略。
                    ACCOUNT_TABLES.forEach { table ->
                        runCatching {
                            conn.createStatement().use {
                                it.execute("ALTER TABLE $table ADD COLUMN isPrimary INTEGER NOT NULL DEFAULT 1")
                            }
                        }
                        runCatching {
                            conn.createStatement().use {
                                it.execute("ALTER TABLE $table ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
                            }
                        }
                    }
                    Log.i(TAG, "database opened: ${dbFile.absolutePath}")
                    // 诊断模式：库开好时记一条（业务表清单 + 每张表行数）
                    logSchema(conn)
                    return AppDatabase(conn)
                } catch (e: Exception) {
                    lastError = e
                    runCatching { conn?.close() }
                    Log.e(TAG, "database open attempt ${attempt + 1} failed: ${e.message}")
                    runCatching {
                        val dbPath = dbFile.absolutePath
                        when (attempt) {
                            0 -> {
                                // 第一轮：仅删 -shm（共享内存索引，sqlite 会重建；-wal 保留以回放）
                                File("$dbPath-shm").delete()
                                Log.w(TAG, "WAL shm removed, retrying open")
                            }
                            1 -> {
                                // 第二轮：-wal 读不出来。**先备份再移走**，绝不直接删 ——
                                // 主库可能只有 4KB，全部数据都在 -wal 里，删了就是清库。
                                val ts = System.currentTimeMillis()
                                runCatching { File(dbPath).copyTo(File("$dbPath.bak-$ts"), overwrite = true) }
                                runCatching { File("$dbPath-wal").copyTo(File("$dbPath-wal.bak-$ts"), overwrite = true) }
                                File("$dbPath-shm").delete()
                                runCatching { File("$dbPath-wal").delete() }
                                Log.w(TAG, "WAL unreadable; backed up as $dbPath.bak-$ts / -wal.bak-$ts and reopened")
                            }
                        }
                        Thread.sleep(300L * (attempt + 1))
                    }
                }
            }
            throw lastError ?: IllegalStateException("database open failed")
        }

        /**
         * 诊断模式：记录业务表清单与每张表的行数（只在库打开那一次跑）。
         * 排查「表不存在 / 迁移没跑 / 数据莫名清空」时这一条最有用；关着时首行短路，零开销。
         */
        private fun logSchema(conn: Connection) {
            if (!DiagnosticLog.isEnabled()) return
            runCatching {
                val tables = mutableListOf<String>()
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rs ->
                        while (rs.next()) {
                            val name = rs.getString(1) ?: continue
                            // sqlite 自己的内部表不算业务表
                            if (!name.startsWith("sqlite_")) tables.add(name)
                        }
                    }
                }
                DiagnosticLog.log(
                    DiagnosticLog.DB, "db_open",
                    size = tables.size.toLong(),
                    summary = "file=yunx.db | tables=${tables.joinToString(",")}"
                )
                // 顺手把每张表的行数也记一笔（只在诊断模式开启、且只在库打开那一次执行）
                tables.forEach { table ->
                    runCatching {
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                                if (rs.next()) DiagnosticLog.db(table, "count", 0L, rs.getInt(1))
                            }
                        }
                    }
                }
            }
        }

        private val ACCOUNT_TABLES = listOf(
            "quark_account", "uc_account", "xunlei_account", "baidu_account", "c139_account",
            "pan123_account", "pan115_account", "guangya_account", "ilanzou_account", "lanzou_account"
        )

        /** Room v13 最终 schema 的 DDL（全新库直接建最终形态）。 */
        private val DDL = listOf(
            "CREATE TABLE IF NOT EXISTS quark_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS uc_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS xunlei_account (id TEXT PRIMARY KEY NOT NULL, accessToken TEXT NOT NULL, refreshToken TEXT NOT NULL, deviceId TEXT NOT NULL, captchaToken TEXT NOT NULL, nickname TEXT NOT NULL, authType TEXT NOT NULL DEFAULT '', updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS baidu_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS c139_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, authorization TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS pan123_account (id TEXT PRIMARY KEY NOT NULL, accessToken TEXT NOT NULL, account TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS pan115_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS guangya_account (id TEXT PRIMARY KEY NOT NULL, accessToken TEXT NOT NULL, refreshToken TEXT NOT NULL, deviceId TEXT NOT NULL, deviceSign TEXT NOT NULL, account TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS ilanzou_account (id TEXT PRIMARY KEY NOT NULL, appToken TEXT NOT NULL, uuid TEXT NOT NULL, account TEXT NOT NULL, password TEXT NOT NULL, userId TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS lanzou_account (id TEXT PRIMARY KEY NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS video_account (id TEXT PRIMARY KEY NOT NULL, platform TEXT NOT NULL, cookie TEXT NOT NULL, nickname TEXT NOT NULL, updatedAt INTEGER NOT NULL, isPrimary INTEGER NOT NULL DEFAULT 1, remark TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS download_task (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, fileName TEXT NOT NULL, totalSize INTEGER NOT NULL, downloadedSize INTEGER NOT NULL, status INTEGER NOT NULL, errorMsg TEXT NOT NULL, savePath TEXT NOT NULL, requestHeadersJson TEXT NOT NULL DEFAULT '{}', chunkCount INTEGER NOT NULL DEFAULT 0, plannedTotalSize INTEGER NOT NULL DEFAULT 0, cleanupId TEXT NOT NULL DEFAULT '', platform TEXT NOT NULL DEFAULT '', shareUrl TEXT NOT NULL DEFAULT '', avgSpeed INTEGER NOT NULL DEFAULT 0, engineTaskId TEXT NOT NULL DEFAULT '', altSourcesJson TEXT NOT NULL DEFAULT '', saveDir TEXT NOT NULL DEFAULT '', directPart TEXT NOT NULL DEFAULT '', directMode INTEGER NOT NULL DEFAULT 0, mergeAudioUrl TEXT NOT NULL DEFAULT '', mergeAudioHeadersJson TEXT NOT NULL DEFAULT '', motionUrl TEXT NOT NULL DEFAULT '', threads INTEGER NOT NULL DEFAULT 0, createTime INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS bookmark (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, link TEXT NOT NULL, title TEXT NOT NULL, platform TEXT NOT NULL, pwd TEXT NOT NULL, category TEXT NOT NULL, createTime INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS link_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, platform TEXT NOT NULL, pwd TEXT NOT NULL, createTime INTEGER NOT NULL)"
        )
    }
}

/**
 * 账号表 DAO 骨架（多账号）：所有行常驻内存。
 *
 * `getAccount()/observeAccount()` 返回**主账号**（没有主账号时退回最近更新的那条），
 * 供列目录 / 转存 / 上传等「单一身份」场景使用；列表 / 设主账号 / 改备注 / 删单个账号
 * 走下面新增的方法。
 *
 * ★ 约束：同一张表只允许一条 `isPrimary = 1`。写入主账号、设主账号、删除主账号后都会
 *   自动把「最近更新的那条」补成主账号，避免出现「有账号但一条都不是主账号」的状态。
 */
private class JdbcAccountTable<T : Any>(
    private val conn: Connection,
    private val table: String,
    private val columns: List<String>,
    private val read: (ResultSet) -> T,
    private val bind: (PreparedStatement, T) -> Unit,
    private val primaryOf: (T) -> Boolean
) {
    private val _rows = MutableStateFlow<List<T>>(emptyList())
    val rows: Flow<List<T>> = _rows

    init {
        _rows.value = loadAll()
    }

    fun loadAll(): List<T> = synchronized(conn) {
        conn.prepareStatement(
            "SELECT * FROM `$table` ORDER BY isPrimary DESC, updatedAt DESC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += read(rs)
                out
            }
        }
    }

    fun reload() {
        _rows.value = loadAll()
    }

    fun primary(): T? {
        val all = loadAll()
        return all.firstOrNull { primaryOf(it) } ?: all.firstOrNull()
    }

    fun upsert(value: T) {
        synchronized(conn) {
            if (primaryOf(value)) {
                conn.prepareStatement("UPDATE `$table` SET isPrimary = 0").use { it.executeUpdate() }
            }
            val colList = columns.joinToString(",") { "`$it`" }
            val placeholders = columns.joinToString(",") { "?" }
            conn.prepareStatement(
                "INSERT OR REPLACE INTO `$table` ($colList) VALUES ($placeholders)"
            ).use { ps ->
                bind(ps, value)
                ps.executeUpdate()
            }
            ensurePrimaryExists()
        }
        reload()
    }

    fun setPrimary(id: String) {
        synchronized(conn) {
            conn.prepareStatement("UPDATE `$table` SET isPrimary = 0").use { it.executeUpdate() }
            conn.prepareStatement("UPDATE `$table` SET isPrimary = 1 WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    fun updateRemark(id: String, remark: String) {
        synchronized(conn) {
            conn.prepareStatement("UPDATE `$table` SET remark = ? WHERE id = ?").use { ps ->
                ps.setString(1, remark)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    fun deleteById(id: String) {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM `$table` WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            ensurePrimaryExists()
        }
        reload()
    }

    fun clear() {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM `$table`").use { it.executeUpdate() }
        }
        reload()
    }

    /** 库里一条主账号都没有时，把最近更新那条补成主账号 */
    private fun ensurePrimaryExists() {
        val hasPrimary = conn.prepareStatement("SELECT COUNT(*) FROM `$table` WHERE isPrimary = 1").use { ps ->
            ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
        if (hasPrimary) return
        conn.prepareStatement(
            "UPDATE `$table` SET isPrimary = 1 WHERE id = (SELECT id FROM `$table` ORDER BY updatedAt DESC LIMIT 1)"
        ).use { it.executeUpdate() }
    }
}

private suspend fun <T> dbIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private class JdbcQuarkAccountDao(conn: Connection) : QuarkAccountDao {
    private val d = JdbcAccountTable(
        conn, "quark_account",
        listOf("id", "cookie", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            QuarkAccountEntity(
                id = rs.getString("id"),
                cookie = rs.getString("cookie"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setLong(4, a.updatedAt); ps.setInt(5, if (a.isPrimary) 1 else 0); ps.setString(6, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<QuarkAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<QuarkAccountEntity>> = d.rows
    override suspend fun upsert(account: QuarkAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): QuarkAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<QuarkAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcUcAccountDao(conn: Connection) : UCAccountDao {
    private val d = JdbcAccountTable(
        conn, "uc_account",
        listOf("id", "cookie", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            UCAccountEntity(
                id = rs.getString("id"),
                cookie = rs.getString("cookie"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setLong(4, a.updatedAt); ps.setInt(5, if (a.isPrimary) 1 else 0); ps.setString(6, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<UCAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<UCAccountEntity>> = d.rows
    override suspend fun upsert(account: UCAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): UCAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<UCAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcXunleiAccountDao(conn: Connection) : XunleiAccountDao {
    private val d = JdbcAccountTable(
        conn, "xunlei_account",
        listOf("id", "accessToken", "refreshToken", "deviceId", "captchaToken", "nickname", "authType", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            XunleiAccountEntity(
                id = rs.getString("id"),
                accessToken = rs.getString("accessToken"),
                refreshToken = rs.getString("refreshToken"),
                deviceId = rs.getString("deviceId"),
                captchaToken = rs.getString("captchaToken"),
                nickname = rs.getString("nickname"),
                authType = runCatching { rs.getString("authType") }.getOrDefault(""),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.accessToken); ps.setString(3, a.refreshToken)
            ps.setString(4, a.deviceId); ps.setString(5, a.captchaToken); ps.setString(6, a.nickname)
            ps.setString(7, a.authType); ps.setLong(8, a.updatedAt)
            ps.setInt(9, if (a.isPrimary) 1 else 0); ps.setString(10, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<XunleiAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<XunleiAccountEntity>> = d.rows
    override suspend fun upsert(account: XunleiAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): XunleiAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<XunleiAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcBaiduAccountDao(conn: Connection) : BaiduAccountDao {
    private val d = JdbcAccountTable(
        conn, "baidu_account",
        listOf("id", "cookie", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            BaiduAccountEntity(
                id = rs.getString("id"),
                cookie = rs.getString("cookie"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setLong(4, a.updatedAt); ps.setInt(5, if (a.isPrimary) 1 else 0); ps.setString(6, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<BaiduAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<BaiduAccountEntity>> = d.rows
    override suspend fun upsert(account: BaiduAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): BaiduAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<BaiduAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcC139AccountDao(conn: Connection) : C139AccountDao {
    private val d = JdbcAccountTable(
        conn, "c139_account",
        listOf("id", "cookie", "nickname", "authorization", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            C139AccountEntity(
                id = rs.getString("id"),
                cookie = rs.getString("cookie"),
                nickname = rs.getString("nickname"),
                authorization = rs.getString("authorization"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setString(4, a.authorization); ps.setLong(5, a.updatedAt)
            ps.setInt(6, if (a.isPrimary) 1 else 0); ps.setString(7, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<C139AccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<C139AccountEntity>> = d.rows
    override suspend fun upsert(account: C139AccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): C139AccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<C139AccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcPan123AccountDao(conn: Connection) : Pan123AccountDao {
    private val d = JdbcAccountTable(
        conn, "pan123_account",
        listOf("id", "accessToken", "account", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            Pan123AccountEntity(
                id = rs.getString("id"),
                accessToken = rs.getString("accessToken"),
                account = rs.getString("account"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.accessToken); ps.setString(3, a.account)
            ps.setString(4, a.nickname); ps.setLong(5, a.updatedAt)
            ps.setInt(6, if (a.isPrimary) 1 else 0); ps.setString(7, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<Pan123AccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<Pan123AccountEntity>> = d.rows
    override suspend fun upsert(account: Pan123AccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): Pan123AccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<Pan123AccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcPan115AccountDao(conn: Connection) : Pan115AccountDao {
    private val d = JdbcAccountTable(
        conn, "pan115_account",
        listOf("id", "cookie", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            Pan115AccountEntity(
                id = rs.getString("id"),
                cookie = rs.getString("cookie"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setLong(4, a.updatedAt); ps.setInt(5, if (a.isPrimary) 1 else 0); ps.setString(6, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<Pan115AccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<Pan115AccountEntity>> = d.rows
    override suspend fun upsert(account: Pan115AccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): Pan115AccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<Pan115AccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcGuangYaAccountDao(conn: Connection) : GuangYaAccountDao {
    private val d = JdbcAccountTable(
        conn, "guangya_account",
        listOf("id", "accessToken", "refreshToken", "deviceId", "deviceSign", "account", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            GuangYaAccountEntity(
                id = rs.getString("id"),
                accessToken = rs.getString("accessToken"),
                refreshToken = rs.getString("refreshToken"),
                deviceId = rs.getString("deviceId"),
                deviceSign = rs.getString("deviceSign"),
                account = rs.getString("account"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.accessToken); ps.setString(3, a.refreshToken)
            ps.setString(4, a.deviceId); ps.setString(5, a.deviceSign); ps.setString(6, a.account)
            ps.setString(7, a.nickname); ps.setLong(8, a.updatedAt)
            ps.setInt(9, if (a.isPrimary) 1 else 0); ps.setString(10, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<GuangYaAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<GuangYaAccountEntity>> = d.rows
    override suspend fun upsert(account: GuangYaAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): GuangYaAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<GuangYaAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcILanzouAccountDao(conn: Connection) : ILanzouAccountDao {
    private val d = JdbcAccountTable(
        conn, "ilanzou_account",
        listOf("id", "appToken", "uuid", "account", "password", "userId", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            ILanzouAccountEntity(
                id = rs.getString("id"),
                appToken = rs.getString("appToken"),
                uuid = rs.getString("uuid"),
                account = rs.getString("account"),
                password = rs.getString("password"),
                userId = rs.getString("userId"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.appToken); ps.setString(3, a.uuid)
            ps.setString(4, a.account); ps.setString(5, a.password); ps.setString(6, a.userId)
            ps.setString(7, a.nickname); ps.setLong(8, a.updatedAt)
            ps.setInt(9, if (a.isPrimary) 1 else 0); ps.setString(10, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<ILanzouAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<ILanzouAccountEntity>> = d.rows
    override suspend fun upsert(account: ILanzouAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): ILanzouAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<ILanzouAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

private class JdbcLanzouAccountDao(conn: Connection) : LanzouAccountDao {
    private val d = JdbcAccountTable(
        conn, "lanzou_account",
        listOf("id", "cookie", "nickname", "updatedAt", "isPrimary", "remark"),
        read = { rs ->
            LanzouAccountEntity(
                id = rs.getString("id"),
                cookie = rs.getString("cookie"),
                nickname = rs.getString("nickname"),
                updatedAt = rs.getLong("updatedAt"),
                isPrimary = runCatching { rs.getInt("isPrimary") != 0 }.getOrDefault(true),
                remark = runCatching { rs.getString("remark") }.getOrDefault("")
            )
        },
        bind = { ps, a ->
            ps.setString(1, a.id); ps.setString(2, a.cookie); ps.setString(3, a.nickname)
            ps.setLong(4, a.updatedAt); ps.setInt(5, if (a.isPrimary) 1 else 0); ps.setString(6, a.remark)
        },
        primaryOf = { it.isPrimary }
    )

    override fun observeAccount(): Flow<LanzouAccountEntity?> =
        d.rows.map { list -> list.firstOrNull { it.isPrimary } ?: list.firstOrNull() }
    override fun observeAccounts(): Flow<List<LanzouAccountEntity>> = d.rows
    override suspend fun upsert(account: LanzouAccountEntity) = dbIo { d.upsert(account) }
    override suspend fun getAccount(): LanzouAccountEntity? = dbIo { d.primary() }
    override suspend fun getAccounts(): List<LanzouAccountEntity> = dbIo { d.loadAll() }
    override suspend fun setPrimary(id: String) = dbIo { d.setPrimary(id) }
    override suspend fun updateRemark(id: String, remark: String) = dbIo { d.updateRemark(id, remark) }
    override suspend fun deleteById(id: String) = dbIo { d.deleteById(id) }
    override suspend fun clear() = dbIo { d.clear() }
}

/**
 * `video_account` 专用 DAO。
 *
 * 与 [JdbcAccountTable] 的区别：这张表里**所有视频平台共用**，所以设主账号、清空账号都必须带
 * `platform` 条件，不能像单平台表那样整表 UPDATE。主账号唯一性也按平台各自维护。
 */
private class JdbcVideoAccountDao(private val conn: Connection) : VideoAccountDao {

    private val _rows = MutableStateFlow<List<VideoAccountEntity>>(emptyList())

    init {
        _rows.value = loadAll()
    }

    override fun observeAccounts(): Flow<List<VideoAccountEntity>> = _rows

    override fun observeAccountsOf(platform: String): Flow<List<VideoAccountEntity>> =
        _rows.map { list -> list.filter { it.platform == platform } }

    override suspend fun getAccounts(): List<VideoAccountEntity> = dbIo { loadAll() }

    override suspend fun getActive(platform: String): VideoAccountEntity? =
        dbIo { loadOf(platform).firstOrNull() }

    override suspend fun countOf(platform: String): Int = dbIo { countRows(platform) }

    override suspend fun upsert(account: VideoAccountEntity) = dbIo { upsertRaw(account) }

    override suspend fun setPrimary(platform: String, id: String) = dbIo { setPrimaryRaw(platform, id) }

    override suspend fun updateRemark(id: String, remark: String) = dbIo { updateRemarkRaw(id, remark) }

    override suspend fun deleteById(id: String) = dbIo { deleteRaw(id) }

    override suspend fun clearPlatform(platform: String) = dbIo { clearRaw(platform) }

    // ------------------------------------------------------------------ 内部实现

    private fun read(rs: ResultSet) = VideoAccountEntity(
        id = rs.getString("id"),
        platform = rs.getString("platform"),
        cookie = rs.getString("cookie"),
        nickname = rs.getString("nickname"),
        updatedAt = rs.getLong("updatedAt"),
        isPrimary = rs.getInt("isPrimary") != 0,
        remark = rs.getString("remark")
    )

    private fun loadAll(): List<VideoAccountEntity> = synchronized(conn) {
        conn.prepareStatement(
            "SELECT * FROM video_account ORDER BY platform ASC, isPrimary DESC, updatedAt DESC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<VideoAccountEntity>()
                while (rs.next()) out += read(rs)
                out
            }
        }
    }

    private fun loadOf(platform: String): List<VideoAccountEntity> = synchronized(conn) {
        conn.prepareStatement(
            "SELECT * FROM video_account WHERE platform = ? ORDER BY isPrimary DESC, updatedAt DESC"
        ).use { ps ->
            ps.setString(1, platform)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<VideoAccountEntity>()
                while (rs.next()) out += read(rs)
                out
            }
        }
    }

    private fun countRows(platform: String): Int = synchronized(conn) {
        conn.prepareStatement("SELECT COUNT(*) FROM video_account WHERE platform = ?").use { ps ->
            ps.setString(1, platform)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun upsertRaw(value: VideoAccountEntity) {
        synchronized(conn) {
            if (value.isPrimary) {
                conn.prepareStatement("UPDATE video_account SET isPrimary = 0 WHERE platform = ?").use { ps ->
                    ps.setString(1, value.platform)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement(
                "INSERT OR REPLACE INTO video_account (id,platform,cookie,nickname,updatedAt,isPrimary,remark) " +
                    "VALUES(?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, value.id)
                ps.setString(2, value.platform)
                ps.setString(3, value.cookie)
                ps.setString(4, value.nickname)
                ps.setLong(5, value.updatedAt)
                ps.setInt(6, if (value.isPrimary) 1 else 0)
                ps.setString(7, value.remark)
                ps.executeUpdate()
            }
            ensurePrimaryExists(value.platform)
        }
        _rows.value = loadAll()
    }

    private fun setPrimaryRaw(platform: String, id: String) {
        synchronized(conn) {
            conn.prepareStatement("UPDATE video_account SET isPrimary = 0 WHERE platform = ?").use { ps ->
                ps.setString(1, platform)
                ps.executeUpdate()
            }
            conn.prepareStatement("UPDATE video_account SET isPrimary = 1 WHERE platform = ? AND id = ?").use { ps ->
                ps.setString(1, platform)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        _rows.value = loadAll()
    }

    private fun updateRemarkRaw(id: String, remark: String) {
        synchronized(conn) {
            conn.prepareStatement("UPDATE video_account SET remark = ? WHERE id = ?").use { ps ->
                ps.setString(1, remark)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        _rows.value = loadAll()
    }

    private fun deleteRaw(id: String) {
        synchronized(conn) {
            val platform = conn.prepareStatement("SELECT platform FROM video_account WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            conn.prepareStatement("DELETE FROM video_account WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            if (platform != null) ensurePrimaryExists(platform)
        }
        _rows.value = loadAll()
    }

    private fun clearRaw(platform: String) {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM video_account WHERE platform = ?").use { ps ->
                ps.setString(1, platform)
                ps.executeUpdate()
            }
        }
        _rows.value = loadAll()
    }

    /** 该平台一条主账号都没有时，把最近更新的那条补成主账号（不影响其它平台） */
    private fun ensurePrimaryExists(platform: String) {
        val hasPrimary = conn.prepareStatement(
            "SELECT COUNT(*) FROM video_account WHERE platform = ? AND isPrimary = 1"
        ).use { ps ->
            ps.setString(1, platform)
            ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
        if (hasPrimary) return
        conn.prepareStatement(
            "UPDATE video_account SET isPrimary = 1 WHERE platform = ? AND id = (" +
                "SELECT id FROM video_account WHERE platform = ? ORDER BY updatedAt DESC LIMIT 1)"
        ).use { ps ->
            ps.setString(1, platform)
            ps.setString(2, platform)
            ps.executeUpdate()
        }
    }
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
        // 旧库迁移期间 shareUrl 列可能尚未添加，runCatching 兜底返回空串
        shareUrl = runCatching { rs.getString("shareUrl") }.getOrDefault(""),
        avgSpeed = rs.getLong("avgSpeed"),
        // 旧库迁移期间 engineTaskId 列可能尚未添加，runCatching 兜底返回空串
        engineTaskId = runCatching { rs.getString("engineTaskId") }.getOrDefault(""),
        // 旧库迁移期间 altSourcesJson 列可能尚未添加，runCatching 兜底返回空串（按单源处理）
        altSourcesJson = runCatching { rs.getString("altSourcesJson") }.getOrDefault(""),
        // 旧库迁移期间 saveDir 列可能尚未添加（空串 = 跟随全局保存目录）
        saveDir = runCatching { rs.getString("saveDir") }.getOrDefault(""),
        // 实验性直写：旧库迁移期间列可能尚未添加
        directPart = runCatching { rs.getString("directPart") }.getOrDefault(""),
        directMode = runCatching { rs.getInt("directMode") }.getOrDefault(0),
        // DASH 分轨 / 动图：旧库迁移期间列可能尚未添加
        mergeAudioUrl = runCatching { rs.getString("mergeAudioUrl") }.getOrDefault(""),
        mergeAudioHeadersJson = runCatching { rs.getString("mergeAudioHeadersJson") }.getOrDefault(""),
        motionUrl = runCatching { rs.getString("motionUrl") }.getOrDefault(""),
        // 浏览器插件线程数覆盖：旧库迁移期间列可能尚未添加（0 = 跟随平台设置）
        threads = runCatching { rs.getInt("threads") }.getOrDefault(0),
        createTime = rs.getLong("createTime")
    )

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = _tasks

    override suspend fun insert(task: DownloadTaskEntity): Long = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "INSERT INTO download_task(url,fileName,totalSize,downloadedSize,status,errorMsg,savePath," +
                    "requestHeadersJson,chunkCount,plannedTotalSize,cleanupId,platform,shareUrl,avgSpeed,engineTaskId," +
                    "altSourcesJson,saveDir,directPart,directMode,mergeAudioUrl,mergeAudioHeadersJson,motionUrl,threads,createTime) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
                ps.setString(13, task.shareUrl)
                ps.setLong(14, task.avgSpeed)
                ps.setString(15, task.engineTaskId)
                ps.setString(16, task.altSourcesJson)
                ps.setString(17, task.saveDir)
                ps.setString(18, task.directPart)
                ps.setInt(19, task.directMode)
                ps.setString(20, task.mergeAudioUrl)
                ps.setString(21, task.mergeAudioHeadersJson)
                ps.setString(22, task.motionUrl)
                ps.setInt(23, task.threads)
                ps.setLong(24, task.createTime)
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

    override suspend fun listAll(): List<DownloadTaskEntity> = dbIo { loadAll() }

    override suspend fun repairCompletedProgress() = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "UPDATE download_task SET downloadedSize = totalSize " +
                    "WHERE status = 3 AND totalSize > 0 AND downloadedSize < totalSize"
            ).use { it.executeUpdate() }
        }
        reload()
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
                // 完成任务：已知总大小时把已下载量补到总大小，避免 UI 显示「已完成 929KB / 1023KB」
                // （Gopeed 轮询最后一次进度可能略小于 total）；未知大小（total=0）保留实际已下载量
                "UPDATE download_task SET status = ?, savePath = ?, avgSpeed = ?, " +
                    "downloadedSize = CASE WHEN totalSize > 0 THEN totalSize ELSE downloadedSize END " +
                    "WHERE id = ?"
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

    override suspend fun updateEngineTaskId(id: Long, engineTaskId: String) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET engineTaskId = ? WHERE id = ?").use { ps ->
                ps.setString(1, engineTaskId)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun updateDirect(id: Long, partPath: String, mode: Int) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET directPart = ?, directMode = ? WHERE id = ?").use { ps ->
                ps.setString(1, partPath)
                ps.setInt(2, mode)
                ps.setLong(3, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun updateFileName(id: Long, name: String) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("UPDATE download_task SET fileName = ? WHERE id = ?").use { ps ->
                ps.setString(1, name)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun listSyncableEngineTasks(): List<DownloadTaskEntity> = dbIo {
        synchronized(conn) {
            // 还在引擎里跑（未完成、未失败）且带引擎任务 ID 的记录：进度由同步协程逐条轮询回写
            conn.prepareStatement(
                "SELECT * FROM download_task WHERE engineTaskId != '' AND status != 3 AND status != 4"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<DownloadTaskEntity>()
                    while (rs.next()) out += readTask(rs)
                    out
                }
            }
        }
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

private class JdbcLinkHistoryDao(private val conn: Connection) : LinkHistoryDao {

    private val _history = MutableStateFlow<List<LinkHistoryEntity>>(emptyList())

    init {
        _history.value = loadAll()
    }

    private fun loadAll(): List<LinkHistoryEntity> = synchronized(conn) {
        conn.prepareStatement("SELECT * FROM link_history ORDER BY createTime DESC").use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<LinkHistoryEntity>()
                while (rs.next()) {
                    out += LinkHistoryEntity(
                        id = rs.getLong("id"),
                        url = rs.getString("url"),
                        title = rs.getString("title"),
                        platform = rs.getString("platform"),
                        pwd = rs.getString("pwd"),
                        createTime = rs.getLong("createTime")
                    )
                }
                out
            }
        }
    }

    private fun reload() {
        _history.value = loadAll()
    }

    override fun observeAll(): Flow<List<LinkHistoryEntity>> = _history

    override fun search(query: String): Flow<List<LinkHistoryEntity>> {
        val q = query.trim()
        if (q.isEmpty()) return _history
        return _history.map { list ->
            list.filter {
                it.url.contains(q, ignoreCase = true) ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.platform.contains(q, ignoreCase = true)
            }
        }
    }

    override suspend fun insert(entity: LinkHistoryEntity): Long = dbIo {
        synchronized(conn) {
            conn.prepareStatement(
                "INSERT INTO link_history(url,title,platform,pwd,createTime) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, entity.url)
                ps.setString(2, entity.title)
                ps.setString(3, entity.platform)
                ps.setString(4, entity.pwd)
                ps.setLong(5, entity.createTime)
                ps.executeUpdate()
                ps.generatedKeys.use { gk -> if (gk.next()) gk.getLong(1) else entity.id }
            }
        }.also { reload() }
    }

    override suspend fun delete(id: Long) = dbIo {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM link_history WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
        reload()
    }

    override suspend fun clear() = dbIo {
        synchronized(conn) {
            conn.prepareStatement("DELETE FROM link_history").use { it.executeUpdate() }
        }
        reload()
    }
}
