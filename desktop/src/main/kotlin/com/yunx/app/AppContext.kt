package com.yunx.app

import com.yunx.app.data.storage.StorageMigrator
import com.yunx.app.util.Log
import java.io.File
import java.util.prefs.Preferences

/**
 * 桌面应用上下文门面：替代 Android Context。
 * 数据/缓存/临时目录、以及非设置类的杂项偏好（onboarding、忽略版本、迅雷设备指纹）。
 *
 * 目录分三层（自 2026-10-11 起后两层可在「设置 → 储存目录」里改，改完需重启生效）：
 * 1. [dataDir] 数据根目录：默认 `<用户目录>/.yunx-desktop`，可用环境变量 `YUNX_DESKTOP_DATA_DIR` 覆盖；
 *    日志（[filesDir]）与诊断日志目录跟随它，**不可在应用内更改**。
 * 2. [dbDir] 数据库目录：默认＝[dataDir]，放 `yunx.db` 与 `credential.key`（两者必须同处一地）。
 * 3. [cacheDir] 缓存根目录：默认＝`<dataDir>/cache`，放下载分片、合并临时文件、JCEF 浏览器数据。
 *
 * ★ 目录配置存在 [settingsPrefs]（java.util.prefs，与数据目录无关）——否则「把数据目录搬走」之后
 *   就再也读不到「该去哪找数据」了。启动时只读一次，因此改动天然是「重启后生效」。
 */
object AppContext {

    /** 应用数据根目录：默认 <用户目录>/.yunx-desktop；可用环境变量 YUNX_DESKTOP_DATA_DIR 覆盖（测试/沙箱环境用） */
    val dataDir: File = System.getenv("YUNX_DESKTOP_DATA_DIR")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".yunx-desktop")

    /** 旧版数据根目录（.yunx-pc）：首次启动时整体迁移到新目录 */
    private val legacyDataDir: File = File(System.getProperty("user.home"), ".yunx-pc")

    // ---------- 杂项偏好（必须在下面解析目录之前声明：Kotlin 单例按声明顺序初始化）----------

    /** 杂项偏好（原 "yunx_prefs" SharedPreferences：onboarding_shown / ignored_version） */
    val miscPrefs: Preferences = Preferences.userRoot().node("yunx/misc")

    /**
     * 设置偏好：与 [com.yunx.app.data.prefs.SettingsRepository] **共用同一个节点**。
     *
     * ★ 储存目录的配置就存在这里。启动早期必须先读到它才能定好数据 / 缓存 / 数据库目录，
     *   所以 AppContext 直接读这个节点 —— 若自己另开一个节点，就会出现「设置页写进 A、这里读 B」，
     *   表现为切换目录后重启仍显示「当前生效（重启前）」（读不到配置 → 一直回退默认）。
     */
    val settingsPrefs: Preferences = Preferences.userRoot().node("yunx/settings")

    /** 迅雷设备指纹偏好（原 "xunlei_device_fp"） */
    val xunleiFpPrefs: Preferences = Preferences.userRoot().node("yunx/xunlei_fp")

    // ---------- 可在应用内更改的目录 ----------

    /** 应用根目录（便携版 = exe 所在目录；开发运行 = 工程目录），供「储存目录」的「应用所在目录」预设使用 */
    val appRootDir: File = resolveAppRoot()

    /** 数据库目录（`yunx.db` + `credential.key`）：默认 [dataDir]，可改为 <应用根>/data 或自定义 */
    val dbDir: File = resolveConfiguredDir(KEY_DB_DIR, dataDir)

    /** 缓存根目录（下载分片 / 合并临时文件 / JCEF 浏览器数据）：默认 `<dataDir>/cache` */
    val cacheDir: File = resolveConfiguredDir(KEY_CACHE_DIR, File(dataDir, "cache"))

    /** 下载分片目录（原 externalCacheDir/download_tmp） */
    val downloadTmpDir: File = File(cacheDir, "download_tmp")

    /** 合并暂存目录（原 cacheDir/merged_*） */
    val mergeDir: File = File(cacheDir, "merge")

    /** 杂项文件目录（日志等）；跟随数据根目录，不随缓存/数据库设置变化 */
    val filesDir: File = File(dataDir, "files")

    // 偏好键（与 SettingsRepository 的 storage* 属性一一对应；这里直接读是为了在 SettingsRepository 可用之前就定好目录）
    internal const val KEY_DB_DIR = "storage_db_dir"
    internal const val KEY_CACHE_DIR = "storage_cache_dir"

    /** 迁移完成后待清理的旧目录（下次启动时删掉旧库文件 / 旧缓存目录） */
    internal const val KEY_DB_MIGRATED_FROM = "storage_db_migrated_from"
    internal const val KEY_CACHE_MIGRATED_FROM = "storage_cache_migrated_from"

    fun init() {
        migrateLegacyDataDir()
        listOf(dataDir, dbDir, cacheDir, downloadTmpDir, mergeDir, filesDir).forEach { it.mkdirs() }
        cleanupMigratedStorage()
    }

    /** 「应用所在目录」预设对应的目标目录：<应用根>/data（不直接写在程序根目录里，避免和 app/ runtime/ 混在一起） */
    fun appDataDirPreset(): File = File(appRootDir, "data")

    /** 「应用所在目录」预设对应的缓存目录：<应用根>/data/cache */
    fun appCacheDirPreset(): File = File(appRootDir, "data/cache")

    /**
     * 旧数据目录迁移：<用户目录>/.yunx-pc → .yunx-desktop。
     * 仅在新目录不存在、且未用环境变量覆盖时执行；整体改名（不复制，下载分片可能很大），
     * 改名失败则退回使用新建的空目录（登录态与任务需重新登录/重新添加）。
     */
    private fun migrateLegacyDataDir() {
        if (System.getenv("YUNX_DESKTOP_DATA_DIR") != null) return
        if (dataDir.exists() || !legacyDataDir.exists()) return
        if (!legacyDataDir.renameTo(dataDir)) {
            Log.w("YunX-DataDir", "旧数据目录迁移失败：${legacyDataDir.absolutePath}")
            return
        }
        // 日志文件一并改名，避免迁移后旧日志成为游离文件
        File(filesDir, "yunx-pc.log").takeIf { it.exists() }
            ?.renameTo(File(filesDir, "yunx-desktop.log"))
        Log.i("YunX-DataDir", "已迁移数据目录：${legacyDataDir.name} -> ${dataDir.name}")
    }

    /**
     * 清理「储存目录」迁移后留下的旧文件（只在**下一次启动**做，因为搬迁当下旧库 / 旧 JCEF 目录
     * 仍被本进程占用，Windows 上删不掉）。
     *
     * ★ 目的：不留下敏感残留 —— 旧库里是任务 / 历史 / 账号凭证，旧缓存里含网页版登录态 cookie。
     * ★ 安全前提：只有新位置**确实可用**时才删旧位置（否则宁可留着下次重试，也不能删掉唯一一份数据）；
     *   没删干净（文件仍被占用）时保留「待清理」标记，下次启动继续尝试。
     */
    private fun cleanupMigratedStorage() {
        runCatching {
            // ---- 旧数据库位置 ----
            val oldDbRaw = miscPrefs.get(KEY_DB_MIGRATED_FROM, null)?.trim().orEmpty()
            if (oldDbRaw.isNotEmpty()) {
                val oldDir = File(oldDbRaw)
                if (sameDir(oldDir, dbDir)) {
                    // 旧=新（配置没生效或又切了回来）：无事可做，撤掉标记
                    miscPrefs.remove(KEY_DB_MIGRATED_FROM)
                } else if (File(dbDir, "yunx.db").isFile) {
                    // ★ 旧位置往往就是数据根目录（还含日志、缓存），所以只删库文件与凭证密钥，绝不删目录。
                    //   credential.key 里是凭证主密钥，同属敏感残留，一并删掉；新位置已有副本，账号不会掉。
                    listOf("yunx.db", "yunx.db-wal", "yunx.db-shm", "credential.key").forEach {
                        runCatching { File(oldDir, it).delete() }
                    }
                    if (!File(oldDir, "yunx.db").exists() && !File(oldDir, "credential.key").exists()) {
                        Log.i("YunX-DataDir", "已清理迁移后的旧数据库残留：${oldDir.absolutePath}")
                        miscPrefs.remove(KEY_DB_MIGRATED_FROM)
                    }
                    // 没删干净（仍被占用）：保留标记，下次启动再试
                }
            }

            // ---- 旧缓存位置：下载分片 / JCEF 浏览器数据（含登录态 cookie），整棵删除 ----
            val oldCacheRaw = miscPrefs.get(KEY_CACHE_MIGRATED_FROM, null)?.trim().orEmpty()
            if (oldCacheRaw.isNotEmpty()) {
                val oldDir = File(oldCacheRaw)
                val newReady = cacheDir.isDirectory && cacheDir.listFiles()?.isNotEmpty() == true
                when {
                    sameDir(oldDir, cacheDir) -> miscPrefs.remove(KEY_CACHE_MIGRATED_FROM)
                    // ★ 仅当新缓存目录确已就位、且旧目录确实是本应用独占的缓存目录时才删，
                    //   避免把唯一一份数据删掉 —— 也避免删掉盘符根 / 新目录的祖先（那是整块盘）
                    oldDir.isDirectory && newReady && canDeleteOldCacheDir(oldDir) -> {
                        if (oldDir.deleteRecursively()) {
                            Log.i("YunX-DataDir", "已清理迁移后的旧缓存目录：${oldDir.absolutePath}")
                            miscPrefs.remove(KEY_CACHE_MIGRATED_FROM)
                        }
                        // 删除失败（文件被占用）：保留标记，下次启动再试
                    }
                    oldDir.isDirectory && newReady -> {
                        // 旧位置是盘符根 / 新目录的祖先 / 混着用户自己的文件：宁可留着旧缓存，
                        // 也绝不整棵递归删除（曾经把用户整个盘清空的正是这里）
                        Log.w("YunX-DataDir", "旧缓存目录不适合整棵删除，已跳过：${oldDir.absolutePath}")
                        miscPrefs.remove(KEY_CACHE_MIGRATED_FROM)
                    }
                    // 新位置还没就位：保留标记，下次启动再试
                }
            }
        }.onFailure { Log.w("YunX-DataDir", "清理迁移残留失败：${it.message}") }
    }

    /** 两个 File 是否指向同一位置（canonicalPath 取不到时退回绝对路径比较） */
    private fun sameDir(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(a.absolutePath == b.absolutePath)

    /**
     * 旧缓存目录能否整棵删除。
     *
     * ★ 曾把缓存目录设到盘符根（`D:\`）的用户，若在这里直接 `deleteRecursively()`，会被清空一整块盘。
     *   所以只有「确实是本应用独占的缓存目录」才允许递归删除：
     * - 不是盘符根（`D:\` 没有父目录）；
     * - 不是当前缓存 / 数据根 / 用户主目录 / 应用根目录的祖先（删旧会把它们一起删掉）；
     * - 顶层条目全部属于本应用（混着用户自己的文件就只保留，宁可留下旧缓存）。
     */
    private fun canDeleteOldCacheDir(oldDir: File): Boolean {
        val oldPath = runCatching { oldDir.canonicalFile.toPath() }.getOrNull() ?: return false
        if (oldPath.parent == null) return false
        val mustKeep = listOfNotNull(
            runCatching { cacheDir.canonicalFile.toPath() }.getOrNull(),
            runCatching { dataDir.canonicalFile.toPath() }.getOrNull(),
            runCatching { File(System.getProperty("user.home").orEmpty()).canonicalFile.toPath() }.getOrNull(),
            runCatching { appRootDir.canonicalFile.toPath() }.getOrNull()
        )
        if (mustKeep.any { it.startsWith(oldPath) }) return false
        return oldDir.listFiles()?.all { StorageMigrator.isOwnedCacheEntry(it.name) } == true
    }

    /** 读取配置的目录；未配置或目录不可用（被删/无权限）时回退默认值，绝不让应用起不来 */
    private fun resolveConfiguredDir(key: String, defaultDir: File): File {
        val raw = settingsPrefs.get(key, null)?.trim().orEmpty()
        if (raw.isEmpty()) return defaultDir
        val configured = File(raw)
        if (configured.isDirectory) return configured
        if (configured.mkdirs()) return configured
        Log.w("YunX-DataDir", "配置的目录不可用，回退默认：$raw")
        return defaultDir
    }

    /**
     * 应用根目录：优先取 jpackage 注入的 `jpackage.app-path`（指向 `<root>/YunX-Desktop.exe`），
     * 便携版启动器会把工作目录设为程序根目录（开发运行时为工程目录）。
     */
    private fun resolveAppRoot(): File {
        System.getProperty("jpackage.app-path")?.let { p ->
            File(p).parentFile?.takeIf { it.isDirectory }?.let { return it }
        }
        return File(System.getProperty("user.dir") ?: ".")
    }
}
