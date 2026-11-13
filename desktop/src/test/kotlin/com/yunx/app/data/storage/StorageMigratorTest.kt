package com.yunx.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 储存目录搬迁 / 清理的安全用例。
 *
 * 背景一（目标与源重叠）：搬迁是「遍历源目录 → 拷到目标目录」。目标若落在源目录内部（把缓存搬进自己的子目录），
 * 边拷边遍历会把刚写出的副本再枚举出来、再拷一层，一层套一层无限嵌套，直到磁盘写满。
 *
 * 背景二（整块盘被搬走 / 被删）：缓存目录可能被设成盘符根 / 用户主目录 / 含用户资料的普通目录。
 * 这时若「整棵搬走」，等于把用户的文件复制到新缓存目录；下次启动若再「整棵删除」旧目录，
 * 用户文件就真的没了。这些用例锁住修复后的行为：
 * - 搬迁**永远只拷贝本应用自己的顶层条目**，用户文件一个都不搬；
 * - 清理旧目录**只删本应用条目**，用户文件（包括搬迁后手动放回旧目录的文件）原样保留；
 * - 盘符根 / 用户主目录 / 受保护目录的祖先在切换前就被拒绝，清理时也一律跳过。
 *
 * ★ 这些用例只在系统临时目录里造数据，绝不触碰真实盘符根 / 用户目录。
 */
class StorageMigratorTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "yunx-storage-test/$name-${System.nanoTime()}")
            .canonicalFile
            .apply {
                deleteRecursively()
                mkdirs()
            }

    private fun write(file: File, text: String = "x") {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun targetInsideSourceCopiesOnceWithoutNesting() {
        val source = tempDir("nested")
        write(File(source, "download_tmp/1/part_0"))
        write(File(source, "merge/hls_1"))
        // 目标在源目录内部：旧实现会在 moved 里再放出 moved/moved/moved…
        val target = File(source, "moved")

        val report = StorageMigrator.migrateCache(source, target)

        assertTrue("应有文件被搬迁", report.copiedFiles >= 2)
        assertTrue(File(target, "download_tmp/1/part_0").isFile)
        assertTrue(File(target, "merge/hls_1").isFile)
        assertFalse("目标内部不应出现自我嵌套", File(target, target.name).exists())
    }

    @Test
    fun targetBeingAncestorCopiesIntoIt() {
        val parent = tempDir("ancestor")
        val source = File(parent, "cache").apply { mkdirs() }
        write(File(source, "download_tmp/7/part_1"))

        val report = StorageMigrator.migrateCache(source, parent)

        assertEquals(1, report.copiedFiles)
        assertTrue(File(parent, "download_tmp/7/part_1").isFile)
        // 源目录本身不会被搬进目标（相对路径以源为根，不含源目录名）
        assertTrue(File(source, "download_tmp/7/part_1").isFile)
    }

    @Test
    fun selfHealingBundleIsSkippedNotCopied() {
        val source = tempDir("selfheal")
        write(File(source, "download_tmp/1/part_0"))
        write(File(source, "jcef-bundle/libcef.dll"))
        val target = tempDir("selfheal-dst")

        val report = StorageMigrator.migrateCache(source, target)

        assertTrue(File(target, "download_tmp/1/part_0").isFile)
        assertFalse("jcef-bundle 应交由内嵌浏览器自行重建", File(target, "jcef-bundle").exists())
        assertTrue(report.selfHealingEntries > 0)
    }

    @Test
    fun sourceEqualsTargetIsNoOp() {
        val source = tempDir("same")
        write(File(source, "download_tmp/1/part_0"))

        val report = StorageMigrator.migrateCache(source, source)

        assertEquals(0, report.copiedFiles)
        assertTrue(File(source, "download_tmp/1/part_0").isFile)
    }

    // ---------- 数据安全：缓存搬迁 / 清理只碰本应用条目 ----------

    @Test
    fun cacheMigrationOnlyCopiesOwnedEntries() {
        val source = tempDir("mixed-source")
        write(File(source, "download_tmp/1/part_0"))
        write(File(source, "jcef/Cookies"))
        write(File(source, "我的视频.mp4"))
        write(File(source, "照片/2024/01.jpg"))
        val target = tempDir("mixed-target")

        val report = StorageMigrator.migrateCache(source, target)

        assertTrue(File(target, "download_tmp/1/part_0").isFile)
        assertTrue(File(target, "jcef/Cookies").isFile)
        assertFalse("缓存搬迁绝不能把用户文件复制到新缓存目录", File(target, "我的视频.mp4").exists())
        assertFalse("缓存搬迁绝不能把用户目录复制到新缓存目录", File(target, "照片").exists())
        assertEquals(2, report.foreignEntries)
        // 搬迁只拷贝、不删除：源目录里的用户文件必须原样还在
        assertTrue(File(source, "我的视频.mp4").isFile)
        assertTrue(File(source, "照片/2024/01.jpg").isFile)
    }

    @Test
    fun cacheMigrationSkipsForeignEntriesForUserFolderSource() {
        // 模拟「缓存目录被设成盘符根 / 用户目录 / 下载目录」：源顶层大部分不是本应用的东西
        val source = tempDir("shared-source")
        write(File(source, "download_tmp/9/part_1"))
        write(File(source, "merge/hls_9"))
        (1..5).forEach { write(File(source, "user-doc-$it.txt")) }
        val target = tempDir("shared-target")

        val report = StorageMigrator.migrateCache(source, target)

        assertEquals(2, report.copiedFiles)
        assertEquals(5, report.foreignEntries)
        assertFalse("用户文件不能被复制到新缓存位置", File(target, "user-doc-1.txt").exists())
        assertTrue("源目录里的用户文件不能被删", File(source, "user-doc-1.txt").isFile)
    }

    @Test
    fun cleanupOldCacheDirDeletesOnlyOwnedEntries() {
        val old = tempDir("cleanup-mixed")
        write(File(old, "download_tmp/1/part_0"))
        write(File(old, "jcef/Cookies"))
        write(File(old, "搬到新位置的缓存.txt"))
        write(File(old, "我的资料/笔记.md"))

        val report = StorageMigrator.cleanupOldCacheDir(old)

        assertFalse(File(old, "download_tmp").exists())
        assertFalse(File(old, "jcef").exists())
        assertTrue("用户手动放回旧目录的文件不能被删除", File(old, "搬到新位置的缓存.txt").isFile)
        assertTrue("旧目录里的用户目录不能被删除", File(old, "我的资料/笔记.md").isFile)
        assertTrue("目录里还有用户文件时必须保留目录本身", old.isDirectory)
        assertEquals(2, report.deletedEntries.size)
        assertTrue(report.pendingEntries.isEmpty())
        assertEquals(2, report.keptForeignEntries.size)
    }

    @Test
    fun cleanupOldCacheDirRemovesAppOnlyDirectory() {
        val old = tempDir("cleanup-app-only")
        write(File(old, "download_tmp/1/part_0"))
        write(File(old, ".yunx-jcef-running"))

        val report = StorageMigrator.cleanupOldCacheDir(old)

        assertFalse("旧目录里只剩本应用内容时，删净后连空目录一起清掉", old.exists())
        assertEquals(2, report.deletedEntries.size)
        assertTrue(report.keptForeignEntries.isEmpty())
    }

    @Test
    fun cleanupOldCacheDirOnMissingDirIsNoOp() {
        val missing = File(tempDir("cleanup-missing-parent"), "not-there")
        val report = StorageMigrator.cleanupOldCacheDir(missing)
        assertTrue(report.deletedEntries.isEmpty())
        assertTrue(report.pendingEntries.isEmpty())
    }

    // ---------- 切换前的目标位置校验 ----------

    @Test
    fun unsafeCacheLocationRejectsRootHomeAndProtectedAncestors() {
        val base = tempDir("unsafe-base")
        val home = File(base, "home").apply { mkdirs() }
        val protectedChild = File(File(home, "app-data"), "cache").apply { mkdirs() }
        val protectedDirs = listOf(protectedChild)

        // 磁盘根目录：只做路径判断，不写、不删
        File.listRoots().firstOrNull()?.let { root ->
            assertNotNull(
                "盘符根必须是非法缓存位置",
                StorageMigrator.unsafeCacheLocationReason(root, protectedDirs, home)
            )
        }
        assertNotNull(
            "用户主目录必须是非法缓存位置",
            StorageMigrator.unsafeCacheLocationReason(home, protectedDirs, home)
        )
        assertNotNull(
            "受保护目录的祖先必须是非法缓存位置",
            StorageMigrator.unsafeCacheLocationReason(home, protectedDirs, File(base, "other-home"))
        )
        assertNull(
            "普通独立目录应当被接受",
            StorageMigrator.unsafeCacheLocationReason(tempDir("unsafe-ok"), protectedDirs, home)
        )
    }

    @Test
    fun checkCacheTargetRejectsFolderWithUserFiles() {
        val target = File(tempDir("target-check"), "user-folder").apply { mkdirs() }
        write(File(target, "我的视频.mp4"))
        write(File(target, "download_tmp/1/part_0"))

        val reason = StorageMigrator.checkCacheTarget(
            target,
            listOf(tempDir("target-protected")),
            tempDir("target-home")
        )

        assertNotNull("目标目录混有用户文件时必须拒绝", reason)
        assertTrue("错误信息应点出非云析文件", reason!!.contains("非云析"))
    }

    @Test
    fun checkCacheTargetAcceptsEmptyOrAppOwnedFolder() {
        val home = tempDir("target-home-ok")
        val protectedDirs = listOf(tempDir("target-protected-ok"))

        assertNull(
            "空目录应当被接受",
            StorageMigrator.checkCacheTarget(tempDir("target-empty-ok"), protectedDirs, home)
        )
        val reused = tempDir("target-reused")
        write(File(reused, "download_tmp/1/part_0"))
        assertNull(
            "只含本应用条目的目录（上一次搬迁的半成品）应当被接受",
            StorageMigrator.checkCacheTarget(reused, protectedDirs, home)
        )
    }

    @Test
    fun safeOldCacheDirOnlyForNormalDirectories() {
        val base = tempDir("safe-old-base")
        val oldDir = File(base, "old-cache").apply { mkdirs() }
        val protectedChild = File(File(oldDir, "app-data"), "cache").apply { mkdirs() }

        assertFalse(
            "旧目录是受保护目录的祖先时不能清理",
            StorageMigrator.isSafeToCleanOldCacheDir(oldDir, listOf(protectedChild))
        )
        assertTrue(
            "普通旧缓存目录可以清理",
            StorageMigrator.isSafeToCleanOldCacheDir(
                tempDir("safe-old-normal"),
                listOf(tempDir("safe-old-protected"))
            )
        )
        File.listRoots().firstOrNull()?.let { root ->
            assertFalse("盘符根绝不能清理", StorageMigrator.isSafeToCleanOldCacheDir(root, emptyList()))
        }
    }

    @Test
    fun cleanupOldCacheDirSkipsFolderWithoutAppSignature() {
        // 旧目录可能是用户自己的普通文件夹（恰好有 logs / merge 这类同名目录）：
        // 没有 download_tmp / jcef 等云析缓存强特征时，一个文件都不能删
        val old = tempDir("cleanup-user-folder")
        write(File(old, "logs/my-notes.txt"))
        write(File(old, "merge/old.zip"))

        val report = StorageMigrator.cleanupOldCacheDir(old)

        assertTrue(report.notAppCacheDir)
        assertTrue(report.deletedEntries.isEmpty())
        assertTrue("用户目录里的同名目录不能被删除", File(old, "logs/my-notes.txt").isFile)
        assertTrue(File(old, "merge/old.zip").isFile)
    }

    @Test
    fun migrateCacheRejectsPollutedTargetFolder() {
        val source = tempDir("polluted-source")
        write(File(source, "download_tmp/1/part_0"))
        val target = File(tempDir("polluted-target"), "user-folder").apply { mkdirs() }
        write(File(target, "movie.mp4"))

        var rejected = false
        try {
            StorageMigrator.migrateCache(source, target)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("目标目录混有用户文件时必须拒绝搬迁", rejected)
        assertFalse("被拒绝时不能把缓存拷进去", File(target, "download_tmp").exists())
    }

    @Test
    fun unsafeDatabaseLocationRejectsRootHomeAndItsParents() {
        val base = tempDir("db-unsafe-base")
        val home = File(base, "home").apply { mkdirs() }

        File.listRoots().firstOrNull()?.let { root ->
            assertNotNull("盘符根不能作为数据库目录", StorageMigrator.unsafeDatabaseLocationReason(root, home))
        }
        assertNotNull("用户主目录不能作为数据库目录", StorageMigrator.unsafeDatabaseLocationReason(home, home))
        assertNotNull(
            "用户主目录的上层目录不能作为数据库目录",
            StorageMigrator.unsafeDatabaseLocationReason(base, home)
        )
        assertNull(
            "普通独立目录可以作为数据库目录（是否为空由 checkDatabaseTarget 再判）",
            StorageMigrator.unsafeDatabaseLocationReason(tempDir("db-ok"), home)
        )
    }

    @Test
    fun ancestorTargetStillSupportedAfterSafetyChecks() {
        // 目标在源的外层是历史允许的用法：新增的「目标必须干净」检查不能把这条路径误杀
        val parent = tempDir("ancestor-safe")
        val source = File(parent, "cache").apply { mkdirs() }
        write(File(source, "download_tmp/3/part_0"))

        val report = StorageMigrator.migrateCache(source, parent)

        assertEquals(1, report.copiedFiles)
        assertTrue(File(parent, "download_tmp/3/part_0").isFile)
    }

    @Test
    fun extractingBundleCountsAsOwnedEntry() {
        // JCEF 解包失败会在缓存顶层残留 jcef-bundle.extracting：它属于云析自己的条目，
        // 既不能被「目标必须干净」误拒，也不能被当成用户文件留在旧目录里
        val target = tempDir("extracting-target")
        write(File(target, "jcef-bundle.extracting/libcef.dll"))

        assertNull(
            "解包临时目录属于云析自己的条目",
            StorageMigrator.checkCacheTarget(
                target,
                listOf(tempDir("extracting-protected")),
                tempDir("extracting-home")
            )
        )
        assertTrue(StorageMigrator.isOwnedCacheEntry("jcef-bundle.extracting"))
    }
}
