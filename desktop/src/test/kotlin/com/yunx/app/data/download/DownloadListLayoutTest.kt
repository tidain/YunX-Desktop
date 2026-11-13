package com.yunx.app.data.download

import com.yunx.app.data.db.DownloadTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 下载页列表扁平化/懒加载的纯数据测试：
 * - 文件夹子任务必须逐条输出为独立行（交给 LazyColumn 按可视区域组合），不能截断；
 * - 收起时只保留表头；
 * - 根任务顺序、失败置顶、不同保存根目录的分组行为保持不变。
 */
class DownloadListLayoutTest {

    @Test
    fun expandedFolderEmitsEveryChildAsSeparateLazyRow() {
        val tasks = (1..1000).map { i ->
            task(id = i.toLong(), fileName = "大文件夹/sub/${"f%04d".format(i)}.bin")
        }

        val rows = DownloadListLayout.rows(tasks, globalRoot(), emptySet())

        assertEquals(1001, rows.size)
        val header = rows.first() as DownloadListRow.FolderHeader
        assertEquals("大文件夹", header.folder)
        assertTrue(header.expanded)
        assertEquals(1000, header.tasks.size)

        val children = rows.drop(1).map { it as DownloadListRow.FolderChild }
        assertEquals(1000, children.size)
        assertEquals(1000, children.map { it.task.id }.toSet().size)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(children.first().first)
        assertFalse(children.first().last)
        assertTrue(children.last().last)
        assertFalse(children.last().first)
        children.forEachIndexed { index, child ->
            assertEquals((index + 1).toLong(), child.task.id)
        }
    }

    @Test
    fun collapsedFolderEmitsOnlyHeader() {
        val tasks = (1..1000).map { i ->
            task(id = i.toLong(), fileName = "大文件夹/${"f%04d".format(i)}.bin")
        }
        val groupId = DownloadListLayout.groupId(
            DownloadGrouping.groupKey(tasks.first(), globalRoot()).first,
            "大文件夹"
        )

        val rows = DownloadListLayout.rows(tasks, globalRoot(), setOf(groupId))

        assertEquals(1, rows.size)
        val header = rows.single() as DownloadListRow.FolderHeader
        assertFalse(header.expanded)
        assertEquals(1000, header.tasks.size)
    }

    @Test
    fun rootTasksStayFirstAndFailedRowsStayOnTop() {
        val tasks = listOf(
            task(id = 1, fileName = "a.bin", status = DownloadTaskEntity.STATUS_COMPLETED),
            task(id = 2, fileName = "folder/x.bin", status = DownloadTaskEntity.STATUS_DOWNLOADING),
            task(id = 3, fileName = "b.bin", status = DownloadTaskEntity.STATUS_FAILED),
            task(id = 4, fileName = "folder/y.bin", status = DownloadTaskEntity.STATUS_FAILED)
        )

        val rows = DownloadListLayout.rows(tasks, globalRoot(), emptySet())

        val rootIds = rows.filterIsInstance<DownloadListRow.RootTask>().map { it.task.id }
        assertEquals(listOf(3L, 1L), rootIds)
        val children = rows.filterIsInstance<DownloadListRow.FolderChild>().map { it.task.id }
        assertEquals(listOf(4L, 2L), children)
        assertTrue(rows.first() is DownloadListRow.RootTask)
    }

    @Test
    fun sameFolderAtDifferentSaveRootsKeepsSeparateGroups() {
        val base = File("build/download-list-layout-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val saveA = File(base, "a").apply { mkdirs() }
        val saveB = File(base, "b").apply { mkdirs() }
        val tasks = listOf(
            task(id = 1, fileName = "folder/1.bin", saveDir = saveA.absolutePath),
            task(id = 2, fileName = "folder/2.bin", saveDir = saveB.absolutePath)
        )

        val rows = DownloadListLayout.rows(tasks, base.absolutePath, emptySet())
        val headers = rows.filterIsInstance<DownloadListRow.FolderHeader>()

        assertEquals(2, headers.size)
        assertEquals(
            setOf(DownloadGrouping.normalizeRoot(saveA.absolutePath), DownloadGrouping.normalizeRoot(saveB.absolutePath)),
            headers.map { it.groupId.substringBefore('\u0000') }.toSet()
        )
        assertEquals(2, rows.filterIsInstance<DownloadListRow.FolderChild>().size)
    }

    @Test
    fun rowsAreStableAndKeepEveryTaskWhenRebuilt() {
        val tasks = (1..1000).map { i ->
            task(id = i.toLong(), fileName = if (i % 2 == 0) "A/$i.bin" else "B/$i.bin")
        }

        val first = DownloadListLayout.rows(tasks, globalRoot(), emptySet())
        val second = DownloadListLayout.rows(tasks, globalRoot(), emptySet())

        assertEquals(first.map { it.key }, second.map { it.key })
        assertEquals(
            tasks.map { it.id }.toSet(),
            first.filterIsInstance<DownloadListRow.FolderChild>().map { it.task.id }.toSet()
        )
    }

    private fun globalRoot(): String =
        File("build/download-list-layout-test/global").apply { mkdirs() }.absolutePath

    private fun task(
        id: Long,
        fileName: String,
        status: Int = DownloadTaskEntity.STATUS_DOWNLOADING,
        saveDir: String = "",
        savePath: String = ""
    ) = DownloadTaskEntity(
        id = id,
        url = "http://example.com/file",
        fileName = fileName,
        saveDir = saveDir,
        savePath = savePath,
        status = status
    )
}
