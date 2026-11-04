package com.yunx.app.data.storage

import com.yunx.app.AppContext
import com.yunx.app.util.Log
import java.io.File
import java.sql.DriverManager

/**
 * 储存目录搬迁（设置 → 储存目录）。
 *
 * 两类目标：
 * - **数据库目录**：`yunx.db`（含 `-wal` / `-shm`）+ `credential.key`。密钥必须跟着库走 ——
 *   库里所有凭证都是用它加密的，留着旧密钥在新目录会**新建一把随机密钥**，密文解不开会被自愈逻辑清空。
 * - **缓存目录**：整棵缓存树（下载分片 / 合并临时文件 / JCEF 浏览器数据）。
 *
 * ★ 四条准则：
 * 1. **只拷贝、不删除**：搬迁当下旧库与 JCEF 目录仍被本进程占用（Windows 上删不掉，硬删会失败），
 *    所以只把「待清理」记进偏好，交给**下次启动**的 `AppContext.cleanupMigratedStorage()` 删；
 * 2. **目标必须干净**：数据库目标已存在 `yunx.db` 一律拒绝（否则两套数据混在一起，无法判断归谁）；
 * 3. **失败不切配置**：任何一步失败都原样保留旧配置，宁可“没搬成”，绝不能“搬一半就切过去”；
 * 4. **只搬属于本应用的内容**：缓存目录若被设到盘符根 / 用户主目录这类共享位置，只搬本应用的顶层条目 ——
 *    否则「把缓存搬走」实际是把用户整个盘复制一遍；同时任何与目标目录重叠的搬迁都不能自我递归
 *    （目标在源内部时，边拷边遍历会把刚写出的副本再拷一层，一层套一层无限嵌套）。
 */
object StorageMigrator {

    /** 搬迁结果：拷贝的文件数 / 字节数，以及被跳过（占用、读不到）的文件 */
    data class Report(
        val copiedFiles: Int,
        val copiedBytes: Long,
        val skipped: List<String>,
        /** 未拷贝、将在新位置自动重建的条目数（目前只有 JCEF 解包产物） */
        val selfHealingEntries: Int = 0
    ) {
        val skippedCount: Int get() = skipped.size
    }

    /** 数据库相关文件名（credential.key 与库必须同目录） */
    private val DB_FILES = listOf("yunx.db", "yunx.db-wal", "yunx.db-shm")
    private const val KEY_FILE = "credential.key"

    /**
     * 目标目录是否可写（真正写一个探针文件，避免只看 `canWrite()` 被 ACL 骗过）。
     * 不可写时返回原因文本，可直接展示给用户。
     */
    fun checkWritable(target: File): String? {
        if (!target.exists() && !target.mkdirs()) return "无法创建目录：${target.absolutePath}"
        if (!target.isDirectory) return "目标不是目录：${target.absolutePath}"
        val probe = File(target, ".yunx-write-test")
        return runCatching {
            probe.writeText("ok")
            probe.delete()
            null
        }.getOrElse { "该目录不可写（${it.javaClass.simpleName}）：${target.absolutePath}" }
    }

    /**
     * 数据库目标是否可用：已存在 `yunx.db` 时返回错误文本（拒绝覆盖另一套数据）。
     * 返回 null 表示可以搬。
     */
    fun checkDatabaseTarget(target: File): String? {
        if (File(target, "yunx.db").isFile) {
            return "目标目录已存在数据库文件（yunx.db），请换一个空目录，或先在资源管理器里把它移走"
        }
        return checkWritable(target)
    }

    /** 把数据库目录从 [source] 搬到 [target]（拷贝；旧文件由下次启动清理） */
    fun migrateDatabase(source: File, target: File): Report {
        // 先 checkpoint：把 WAL 里的内容并回主库文件，拷贝出的单文件即完整一致
        checkpointWal(source)
        val report = copyDatabaseFiles(source, target)
        require(File(target, "yunx.db").isFile) { "拷贝后未在目标目录找到 yunx.db" }
        return report
    }

    /** 把缓存目录从 [source] 整棵搬到 [target]（拷贝；旧目录由下次启动删除） */
    fun migrateCache(source: File, target: File): Report =
        copyTree(
            source, target,
            skipTopLevel = SELF_HEALING_NAMES,
            onlyOwnedEntries = isSharedLocation(source)
        )

    /** 目录是否是「共享位置」：盘符根（`D:\`）或用户主目录 —— 从不专属本应用 */
    private fun isSharedLocation(dir: File): Boolean {
        val canonical = runCatching { dir.canonicalFile }.getOrDefault(dir)
        // 盘符根（D:\）没有父目录
        if (canonical.parentFile == null) return true
        val home = runCatching { File(System.getProperty("user.home").orEmpty()).canonicalFile }.getOrNull()
        return home != null && canonical == home
    }

    /**
     * 不需要拷贝、会在新位置自愈的缓存子项：
     * - `jcef-bundle`：内嵌 Chromium 的解包产物（~200MB），jcefmaven 在找不到时会重新解包 ——
     *   拷贝这棵正被 CEF 占用的树最容易残缺（残缺的 bundle 比没有更糟），交给它自己重建更可靠。
     *   而**登录态在 `jcef/`**（root_cache_path），仍会照常搬过去，所以网页登录不会掉。
     */
    private val SELF_HEALING_NAMES = setOf("jcef-bundle")

    /** 缓存目录里属于本应用的顶层目录 */
    private val OWNED_CACHE_DIRS = setOf("download_tmp", "merge", "jcef", "jcef-bundle", "logs")

    /** 缓存目录里属于本应用的顶层条目名前缀（临时 cookie 副本 / ffmpeg 下载包 / 隔离的崩溃 profile） */
    private val OWNED_CACHE_PREFIXES =
        listOf("browser-cookies-", "ff-cookies-", "ffmpeg-download-", "jcef.crashed-")

    /** JCEF 运行标记文件（同样落在缓存目录顶层） */
    private const val JCEF_RUNNING_MARKER = ".yunx-jcef-running"

    /**
     * 该顶层条目是否由本应用创建。
     *
     * 用于两处安全判断：共享位置（盘符根）只搬自己的条目；旧缓存目录里混着用户文件时不敢整棵删除。
     */
    internal fun isOwnedCacheEntry(name: String): Boolean =
        name in OWNED_CACHE_DIRS || name == JCEF_RUNNING_MARKER || OWNED_CACHE_PREFIXES.any { name.startsWith(it) }

    /**
     * 把 WAL 落盘（`wal_checkpoint(TRUNCATE)`）：避免拷贝时主库文件落后于 WAL。
     * 失败不抛（可能连不上/被占用），后面仍会把 `-wal` 一起拷过去兜底。
     */
    private fun checkpointWal(dbDir: File) {
        val dbFile = File(dbDir, "yunx.db")
        if (!dbFile.isFile) return
        runCatching {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            }
        }.onFailure { Log.w(TAG, "WAL checkpoint 失败（继续按文件拷贝）：${it.message}") }
    }

    private fun copyDatabaseFiles(source: File, target: File): Report {
        target.mkdirs()
        var files = 0
        var bytes = 0L
        val skipped = mutableListOf<String>()
        val pending = DB_FILES + listOf(KEY_FILE)
        pending.forEach { name ->
            val src = File(source, name)
            if (!src.isFile) return@forEach
            val dst = File(target, name)
            runCatching {
                src.copyTo(dst, overwrite = true)
                files++
                bytes += dst.length()
            }.onFailure {
                skipped.add(name)
                Log.w(TAG, "拷贝失败（跳过）：$name ${it.message}")
            }
        }
        return Report(files, bytes, skipped)
    }

    /**
     * 递归拷贝目录树；单个文件失败只记跳过，不中断整体搬迁。
     *
     * ★ 与目标目录重叠时的安全处理（否则会自我递归、一层套一层无限嵌套）：
     * - 先对源目录做**快照**再拷贝：遍历期间新写出的文件不会进入本次搬迁（目标在源内部时，
     *   边拷边遍历会把刚写出的副本重新枚举出来）；
     * - 目标子树（仅目标在源内部时）整棵跳过：不算「新写出的文件」，但它本身就在源目录里；
     *   目标在源之外 / 是源的祖先时**不能**跳，否则所有条目都命中「在目标里」，一个文件都搬不走。
     *
     * @param onlyOwnedEntries 只搬本应用自己的顶层条目。源目录是盘符根 / 用户主目录这类共享位置时启用：
     *   那里绝大部分内容不属于本应用，整棵搬走等于把用户整个盘复制到新位置。
     */
    private fun copyTree(
        source: File,
        target: File,
        skipTopLevel: Set<String> = emptySet(),
        onlyOwnedEntries: Boolean = false
    ): Report {
        target.mkdirs()
        var files = 0
        var bytes = 0L
        var selfHealing = 0
        val skipped = mutableListOf<String>()
        // 用 Path 做包含判断与相对路径计算（按路径分段比较，盘符根 D:\ 不会因为多一个分隔符而误判；
        // 源路径含符号链接 / 目录联接时，也不会把绝对路径当成相对路径拼到目标里）
        val sourceDir = runCatching { source.canonicalFile.toPath() }.getOrNull()
        val targetDir = runCatching { target.canonicalFile.toPath() }.getOrNull()
        val targetInsideSource = sourceDir != null && targetDir != null && targetDir.startsWith(sourceDir)
        val topLevel = source.listFiles()
            ?.filter { !onlyOwnedEntries || isOwnedCacheEntry(it.name) }
            .orEmpty()
        val snapshot = topLevel.flatMap { it.walkTopDown().toList() }
        snapshot.forEach { src ->
            val srcPath = runCatching { src.canonicalFile.toPath() }.getOrNull() ?: return@forEach
            val relative = if (sourceDir != null) {
                runCatching { sourceDir.relativize(srcPath).toString() }.getOrNull()
            } else null
            if (relative.isNullOrEmpty() || relative.startsWith("..")) return@forEach
            // 自愈目录（JCEF 解包产物）：整棵跳过，不在新位置留半份
            if (skipTopLevel.any { relative == it || relative.startsWith("$it${File.separator}") }) {
                selfHealing++
                return@forEach
            }
            // 目标自身及其内容：跳过（只可能在「目标位于源内部」时出现）
            if (targetInsideSource && targetDir != null && srcPath.startsWith(targetDir)) return@forEach
            val dst = File(target, relative)
            if (src.isDirectory) {
                runCatching { dst.mkdirs() }
                return@forEach
            }
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                files++
                bytes += dst.length()
            }.onFailure {
                // 被占用的文件（JCEF 正在用）会走到这里：跳过即可，重启后 JCEF 会自行补齐/重建
                skipped.add(relative)
            }
        }
        return Report(files, bytes, skipped, selfHealing)
    }

    /** 迁移成功后记录「旧位置待清理」，由下次启动删除（本进程还占着旧文件，删不掉） */
    fun rememberCleanupForDatabase(oldDir: File) {
        AppContext.miscPrefs.put(AppContext.KEY_DB_MIGRATED_FROM, oldDir.absolutePath)
    }

    fun rememberCleanupForCache(oldDir: File) {
        AppContext.miscPrefs.put(AppContext.KEY_CACHE_MIGRATED_FROM, oldDir.absolutePath)
    }

    private const val TAG = "YunX-Storage"
}

/**
 * 「储存目录」三个预设：[DEFAULT] 用户目录（数据根目录）、[APP_DIR] 应用所在目录（便携）、[CUSTOM] 自定义。
 */
enum class StoragePreset { DEFAULT, APP_DIR, CUSTOM }
