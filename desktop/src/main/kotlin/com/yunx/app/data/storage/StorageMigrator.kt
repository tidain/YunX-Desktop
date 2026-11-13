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
 * ★ 五条准则：
 * 1. **只拷贝、不删除**：搬迁当下旧库与 JCEF 目录仍被本进程占用（Windows 上删不掉，硬删会失败），
 *    所以只把「待清理」记进偏好，交给**下次启动**的 `AppContext.cleanupMigratedStorage()` 删；
 * 2. **目标必须干净**：数据库目标已存在 `yunx.db` 一律拒绝（否则两套数据混在一起，无法判断归谁）；
 *    缓存目标同样必须干净 —— 只接受空目录，或只含本应用缓存条目的目录（见 [checkCacheTarget]）；
 * 3. **失败不切配置**：任何一步失败都原样保留旧配置，宁可“没搬成”，绝不能“搬一半就切过去”；
 * 4. **只碰属于本应用的内容（拷贝方向）**：缓存目录可能被设到盘符根 / 用户主目录 / 含用户资料的普通目录，
 *    搬迁**永远只搬本应用自己的顶层条目**，其余文件既不动也不删 —— 否则「把缓存搬走」等于把整块盘复制一遍；
 *    任何与目标目录重叠的搬迁也不能自我递归
 *    （目标在源内部时，边拷边遍历会把刚写出的副本再拷一层，一层套一层无限嵌套）；
 * 5. **只碰属于本应用的内容（删除方向）**：下次启动清理旧缓存时**绝不整棵删除**，只删本应用的顶层条目，
 *    用户文件（包括搬迁后手动放回旧目录的文件）原样保留；盘符根 / 受保护目录的祖先一律跳过
 *    （见 [isSafeToCleanOldCacheDir] / [cleanupOldCacheDir]）—— 这两条是「切换缓存目录把整块盘搬走、之后文件还会被删」
 *    这个数据事故的最终防线。
 */
object StorageMigrator {

    /** 搬迁结果：拷贝的文件数 / 字节数，以及被跳过（占用、读不到）的文件 */
    data class Report(
        val copiedFiles: Int,
        val copiedBytes: Long,
        val skipped: List<String>,
        /** 未拷贝、将在新位置自动重建的条目数（目前只有 JCEF 解包产物） */
        val selfHealingEntries: Int = 0,
        /** 旧缓存目录里不属于本应用的顶层条目数：既不搬也不删，留在原位 */
        val foreignEntries: Int = 0
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
    fun checkDatabaseTarget(
        target: File,
        homeDir: File = File(System.getProperty("user.home").orEmpty())
    ): String? {
        unsafeDatabaseLocationReason(target, homeDir)?.let { return it }
        if (File(target, "yunx.db").isFile) {
            return "目标目录已存在数据库文件（yunx.db），请换一个空目录，或先在资源管理器里把它移走"
        }
        return checkWritable(target)
    }

    /**
     * 数据库目标是否「位置本身就不该放数据库」：盘符根 / 用户主目录（或其上层目录）。
     *
     * 数据库可以放回数据根目录（默认就是），所以这里不拿 AppContext 的数据目录当保护对象 ——
     * 只挡住「把 yunx.db + credential.key 写到整块盘的根目录 / 用户主目录」这种明显错误的设置。
     */
    internal fun unsafeDatabaseLocationReason(target: File, homeDir: File): String? {
        val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull()
            ?: return "无法解析目标路径：${target.absolutePath}"
        if (targetPath.parent == null) return "不能把数据库目录设为磁盘根目录：${target.absolutePath}"
        val homePath = runCatching { homeDir.canonicalFile.toPath() }.getOrNull()
        if (homePath != null) {
            if (targetPath == homePath) return "不能把数据库目录设为用户主目录：${target.absolutePath}"
            if (homePath.startsWith(targetPath)) {
                return "不能把数据库目录设为用户主目录的上层目录：${target.absolutePath}"
            }
        }
        return null
    }

    /** 把数据库目录从 [source] 搬到 [target]（拷贝；旧文件由下次启动清理） */
    fun migrateDatabase(source: File, target: File): Report {
        // 先 checkpoint：把 WAL 里的内容并回主库文件，拷贝出的单文件即完整一致
        checkpointWal(source)
        val report = copyDatabaseFiles(source, target)
        require(File(target, "yunx.db").isFile) { "拷贝后未在目标目录找到 yunx.db" }
        return report
    }

    /**
     * 把缓存目录从 [source] 搬到 [target]（拷贝；旧目录由下次启动清理）。
     *
     * ★ **只搬本应用自己的顶层条目，永远如此**（不再只在「源是盘符根 / 用户主目录」时才过滤）：
     *   缓存目录可能被设成过盘符根、用户主目录、下载目录或任何含用户资料的文件夹，
     *   整棵搬走等于把用户的文件复制到新缓存位置 —— 这正是「切换缓存目录把整块盘搬走」的根因。
     *   非本应用条目一个都不动，只在 [Report.foreignEntries] 里报个数给界面提示。
     */
    fun migrateCache(source: File, target: File): Report {
        // 兜底校验（正常路径上界面已经用 checkCacheTarget 挡过一次）：目标绝不能被设成盘符根 / 用户主目录，
        // 也不能是装着用户文件的目录 —— 往那里拷贝缓存等于把用户文件卷进缓存树。
        require(!isDangerousStorageLocation(target)) {
            "目标位置不能作为缓存目录（盘符根 / 用户主目录）：${target.absolutePath}"
        }
        val foreign = foreignTopLevelEntries(source, target)
        require(foreign.isEmpty()) {
            "目标目录不是空的（含 ${foreign.size} 个非云析条目）：${target.absolutePath}"
        }
        return copyTree(
            source, target,
            skipTopLevel = SELF_HEALING_NAMES,
            onlyOwnedEntries = true
        )
    }

    /** 该位置是否绝不该当应用数据 / 缓存目录：盘符根（没有父目录）或用户主目录 */
    internal fun isDangerousStorageLocation(dir: File): Boolean {
        val canonical = runCatching { dir.canonicalFile }.getOrDefault(dir)
        if (canonical.parentFile == null) return true
        val home = runCatching { File(System.getProperty("user.home").orEmpty()).canonicalFile }.getOrNull()
        return home != null && canonical == home
    }

    /**
     * 目标目录里真正属于「用户 / 其它程序」的顶层条目。
     *
     * ★ 目标可能是源目录的祖先（历史行为允许，StorageMigratorTest 有用例）：此时目标里指向源的那一层
     *   （源目录本身或其祖先目录）不是用户文件，不能算「目标不干净」—— 否则会把这种合法重叠搬迁误判为失败。
     */
    private fun foreignTopLevelEntries(source: File, target: File): List<File> {
        val sourcePath = runCatching { source.canonicalFile.toPath() }.getOrNull()
        return target.listFiles().orEmpty().filter { entry ->
            if (isOwnedCacheEntry(entry.name)) return@filter false
            val entryPath = runCatching { entry.canonicalFile.toPath() }.getOrNull()
            val entryIsAncestorOfSource =
                sourcePath != null && entryPath != null && sourcePath.startsWith(entryPath)
            !entryIsAncestorOfSource
        }
    }

    /**
     * 不需要拷贝、会在新位置自愈的缓存子项：
     * - `jcef-bundle`：内嵌 Chromium 的解包产物（~200MB），jcefmaven 在找不到时会重新解包 ——
     *   拷贝这棵正被 CEF 占用的树最容易残缺（残缺的 bundle 比没有更糟），交给它自己重建更可靠。
     *   而**登录态在 `jcef/`**（root_cache_path），仍会照常搬过去，所以网页登录不会掉。
     */
    private val SELF_HEALING_NAMES = setOf("jcef-bundle")

    /**
     * 缓存目录里属于本应用的顶层目录。
     *
     * jcef-bundle.extracting 是 JCEF 解包时的临时目录（解包失败会残留在缓存顶层），同属本应用。
     */
    private val OWNED_CACHE_DIRS =
        setOf("download_tmp", "merge", "jcef", "jcef-bundle", "jcef-bundle.extracting", "logs")

    /** 缓存目录里属于本应用的顶层条目名前缀（临时 cookie 副本 / ffmpeg 下载包 / 隔离的崩溃 profile） */
    private val OWNED_CACHE_PREFIXES =
        listOf("browser-cookies-", "ff-cookies-", "ffmpeg-download-", "jcef.crashed-")

    /** 缓存目录里属于本应用的顶层文件（JCEF 运行标记 / 可写性探针） */
    private val OWNED_CACHE_FILES = setOf(".yunx-jcef-running", ".yunx-write-test")

    /**
     * 该顶层条目是否由本应用创建。
     *
     * 用于三处安全判断：搬迁只拷本应用条目、切换前检查目标目录是否干净、清理旧目录只删本应用条目。
     */
    internal fun isOwnedCacheEntry(name: String): Boolean =
        name in OWNED_CACHE_DIRS ||
            name in OWNED_CACHE_FILES ||
            OWNED_CACHE_PREFIXES.any { name.startsWith(it) }

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
     * @param onlyOwnedEntries 只搬本应用自己的顶层条目（缓存搬迁恒为 true）。源目录可能被设成盘符根 /
     *   用户主目录 / 含用户资料的普通目录，那里绝大部分内容不属于本应用，整棵搬走等于把用户文件复制一遍。
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
        val allTopLevel = source.listFiles().orEmpty()
        val topLevel = allTopLevel.filter { !onlyOwnedEntries || isOwnedCacheEntry(it.name) }
        // 非本应用条目：不搬、不删，只记个数（界面据此提示用户「旧目录里还有你的文件，没动」）。
        // 目标在源内部时，源顶层会出现「刚建好的目标目录」本身 —— 它不是用户文件，不能算进 foreignEntries。
        val foreignEntries = if (onlyOwnedEntries) {
            allTopLevel.count { entry ->
                if (isOwnedCacheEntry(entry.name)) return@count false
                val isTargetItself = targetInsideSource &&
                    runCatching { entry.canonicalFile.toPath() }.getOrNull() == targetDir
                !isTargetItself
            }
        } else 0
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
        return Report(files, bytes, skipped, selfHealing, foreignEntries)
    }

    /**
     * 缓存目标目录是否可用：返回 null 表示可以搬，否则返回可直接展示给用户的原因。
     *
     * ★ 与数据库目标一样，缓存目标也必须「干净」：只接受不存在 / 空目录 / 只含本应用缓存条目的目录。
     *   把缓存目录直接指向盘符根、用户主目录或装满用户资料的文件夹时，搬迁会把用户的文件当缓存处理
     *   （旧版本甚至会在下次启动把整个目录删掉）—— 在切换前就挡住这类位置。
     */
    fun checkCacheTarget(
        target: File,
        protectedDirs: List<File> = defaultProtectedDirs(),
        homeDir: File = File(System.getProperty("user.home").orEmpty())
    ): String? {
        unsafeCacheLocationReason(target, protectedDirs, homeDir)?.let { return it }
        if (target.exists() && !target.isDirectory) return "目标不是目录：${target.absolutePath}"
        val foreign = target.listFiles().orEmpty().filter { !isOwnedCacheEntry(it.name) }
        if (foreign.isNotEmpty()) {
            val names = foreign.take(3).joinToString("、") { it.name }
            return "目标目录不是空的（含 ${foreign.size} 个非云析文件，如 ${names}）。" +
                "请换一个空目录，或专用于云析缓存的目录"
        }
        return checkWritable(target)
    }

    /**
     * 目标位置是否「不该被当成缓存目录」：返回原因文本，null 表示位置本身没问题
     * （不检查是否可写 / 是否为空，那是 [checkCacheTarget] 后续的事）。
     *
     * - 盘符根 / UNC 根（`D:\`、`\\server\share`）：绝不是本应用的缓存目录，清理旧缓存时可能带走整块盘；
     * - 用户主目录：里面全是用户文件；
     * - 受保护目录（当前缓存 / 数据根 / 数据库目录 / 应用根目录）本身或它们的祖先：搬迁 / 清理会波及它们。
     */
    internal fun unsafeCacheLocationReason(
        target: File,
        protectedDirs: List<File>,
        homeDir: File
    ): String? {
        val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull()
            ?: return "无法解析目标路径：${target.absolutePath}"
        // 盘符根（D:\）没有父目录
        if (targetPath.parent == null) return "不能把缓存目录设为磁盘根目录：${target.absolutePath}"
        val homePath = runCatching { homeDir.canonicalFile.toPath() }.getOrNull()
        if (homePath != null) {
            if (targetPath == homePath) return "不能把缓存目录设为用户主目录：${target.absolutePath}"
            if (homePath.startsWith(targetPath)) {
                return "不能把缓存目录设为用户主目录的上层目录：${target.absolutePath}"
            }
        }
        protectedDirs.forEach { protected ->
            val protectedPath = runCatching { protected.canonicalFile.toPath() }.getOrNull() ?: return@forEach
            if (protectedPath == targetPath) {
                return "不能把缓存目录设为数据 / 程序目录本身：${target.absolutePath}"
            }
            if (protectedPath.startsWith(targetPath)) {
                return "不能把缓存目录设为数据 / 程序目录的上层目录：${target.absolutePath}"
            }
        }
        return null
    }

    /** 应用自己的位置：清旧缓存时不能让「旧目录」是它们的祖先（否则会顺带删掉） */
    private fun defaultProtectedDirs(): List<File> =
        listOf(AppContext.cacheDir, AppContext.dataDir, AppContext.dbDir, AppContext.appRootDir)

    /**
     * 旧缓存目录能否安全清理：不能是盘符根，也不能是受保护目录（当前缓存 / 数据根 / 数据库目录 / 应用根目录 /
     * 用户主目录）本身或其祖先 —— 清理旧缓存只允许动「旧缓存目录」自身，绝不能波及它们。
     */
    internal fun isSafeToCleanOldCacheDir(oldDir: File, protectedDirs: List<File>): Boolean {
        val oldPath = runCatching { oldDir.canonicalFile.toPath() }.getOrNull() ?: return false
        if (oldPath.parent == null) return false
        return protectedDirs.none { protected ->
            val protectedPath = runCatching { protected.canonicalFile.toPath() }.getOrNull()
            // 解析不出受保护目录的路径时无法证明安全 —— 按「不安全」处理（fail-closed，宁可不清理）
            if (protectedPath == null) return false
            protectedPath.startsWith(oldPath)
        }
    }

    /** 判断「旧目录确实像云析缓存目录」的强特征：下载分片 / JCEF 数据 / 运行标记 */
    private val STRONG_CACHE_NAMES =
        setOf("download_tmp", "jcef", "jcef-bundle", ".yunx-jcef-running")

    /** 清理旧缓存目录的结果 */
    data class CleanupReport(
        /** 已删除的本应用顶层条目名 */
        val deletedEntries: List<String> = emptyList(),
        /** 因占用 / 权限没删掉、留待下次启动重试的本应用顶层条目名 */
        val pendingEntries: List<String> = emptyList(),
        /** 旧目录里的非本应用条目名：不删、原样保留 */
        val keptForeignEntries: List<String> = emptyList(),
        /** 旧目录里连一个云析缓存的强特征都没有：判定为「不是云析缓存目录」，一个文件都没删 */
        val notAppCacheDir: Boolean = false
    ) {
        val hasPending: Boolean get() = pendingEntries.isNotEmpty()
    }

    /**
     * 清理迁移后的旧缓存目录：**只删属于本应用的顶层条目，绝不整棵删除**。
     *
     * 旧目录可能被设成过盘符根 / 用户目录 / 混有用户资料的文件夹（见 [unsafeCacheLocationReason]），
     * 也可能在搬迁后又被用户手动放入 / 移回文件 —— 这些都不是本应用数据，必须原样保留。
     * 只有本应用条目全部删净、且目录已经空了，才顺手把空目录本身删掉。
     */
    internal fun cleanupOldCacheDir(oldDir: File): CleanupReport {
        if (!oldDir.isDirectory) return CleanupReport()
        val entries = oldDir.listFiles().orEmpty()
        // ★ 先确认「这看起来确实是云析缓存目录」：至少要有一个强特征（download_tmp / jcef / jcef-bundle / 运行标记）。
        //   否则旧目录可能只是用户自己的普通文件夹（恰好有 logs、merge 这类同名目录），一个文件都不能删。
        val looksLikeCacheDir = entries.any { entry ->
            entry.name in STRONG_CACHE_NAMES || OWNED_CACHE_PREFIXES.any { entry.name.startsWith(it) }
        }
        if (!looksLikeCacheDir) return CleanupReport(notAppCacheDir = true)
        val deleted = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val foreign = mutableListOf<String>()
        entries.forEach { entry ->
            if (!isOwnedCacheEntry(entry.name)) {
                foreign.add(entry.name)
                return@forEach
            }
            val ok = runCatching { entry.deleteRecursively() }.getOrDefault(false)
            if (ok || !entry.exists()) deleted.add(entry.name) else pending.add(entry.name)
        }
        // 只有目录已经空了才删目录本身：里面可能还有用户的文件，绝不递归删除目录
        if (runCatching { oldDir.listFiles().isNullOrEmpty() }.getOrDefault(false)) {
            runCatching { oldDir.delete() }
        }
        return CleanupReport(deleted, pending, foreign)
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
