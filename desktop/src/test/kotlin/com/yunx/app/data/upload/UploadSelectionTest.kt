package com.yunx.app.data.upload

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File

class UploadSelectionTest {
    @get:Rule val temp = TemporaryFolder()

    private fun transfer(value: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor) = value
    }

    @Test fun nativeFileListPreservesUnicodeAndSpecialNames() {
        val files = listOf(temp.newFile("中文 空格+#.txt"), temp.newFile("第二个.txt"))
        assertEquals(files, UploadSelection.readDrop(transfer(files)))
    }

    @Test fun textAndUrlsAreNotAcceptedAsFiles() {
        assertThrows(IllegalArgumentException::class.java) { UploadSelection.readDrop(StringSelection("https://example.com/file")) }
    }

    @Test fun malformedNativeListsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UploadSelection.readDrop(transfer(listOf("C:/fake.txt"))) }
        assertThrows(IllegalArgumentException::class.java) { UploadSelection.readDrop(transfer("fake")) }
    }

    @Test fun duplicatePathsAreNotUploadedTwice() {
        val file = temp.newFile("one.txt").apply { writeText("abc") }
        val selection = UploadSelection.merge(emptyList(), listOf(file, File(file.parentFile, "./one.txt")))
        assertEquals(1, selection.files.size)
        assertEquals(3L, selection.files.single().size)
        assertTrue(selection.notice!!.contains("重复"))
    }

    @Test fun emptyFoldersAreKeptAndMissingFilesAreSkippedWithNotice() {
        val good = temp.newFile("good.txt")
        val folder = temp.newFolder("folder")
        val result = UploadSelection.merge(emptyList(), listOf(folder, File(temp.root, "missing"), good))
        assertEquals(listOf(folder.canonicalFile, good.canonicalFile), result.files.map { it.file })
        assertTrue(result.files.first().isDirectory)
        assertEquals(0L, result.files.first().size)
        assertTrue(result.notice!!.contains("不可读取"))
    }

    @Test fun addingFilesPreservesOrderAndDistinctSameNames() {
        val first = temp.newFile("same.txt")
        val second = File(temp.newFolder(), "same.txt").apply { writeText("two") }
        val initial = UploadSelection.merge(emptyList(), listOf(first))
        val result = UploadSelection.merge(initial.files, listOf(first, second))
        assertEquals(listOf(first.canonicalFile, second.canonicalFile), result.files.map { it.file })
    }

    @Test fun selectionNeverDeletesOrChangesLocalFiles() {
        val source = temp.newFile().apply { writeText("source stays local") }
        UploadSelection.merge(emptyList(), UploadSelection.readDrop(transfer(listOf(source))))
        assertEquals("source stays local", source.readText())
    }
}
