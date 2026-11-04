package com.yunx.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 储存目录搬迁的「目标与源重叠」安全用例。
 *
 * 背景：搬迁是「遍历源目录 → 拷到目标目录」。目标若落在源目录内部（把缓存搬进自己的子目录），
 * 边拷边遍历会把刚写出的副本再枚举出来、再拷一层，一层套一层无限嵌套，直到磁盘写满。
 * 这些用例锁住修复后的行为：重叠路径只搬一次、目标在源外层时照常搬。
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
}
