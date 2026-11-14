package com.yunx.app.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.yunx.app.data.upload.UploadCandidate

class UploadUiStateTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun CoroutineScope.finishSelection() = coroutineContext[Job]!!.children.toList().joinAll()
    private fun state(scope: CoroutineScope, callback: (List<UploadCandidate>) -> Unit = {}) = UploadUiState(scope).apply {
        enabled = true
        currentDestination = "测试网盘 / 目标目录"
        onUpload = callback
    }

    @Test fun droppingFilesWaitsForExplicitConfirmation() = runBlocking {
        var calls = 0
        val state = state(this) { calls++ }
        state.stage(listOf(temp.newFile()))
        finishSelection()
        assertTrue(state.visible)
        assertEquals(1, state.files.size)
        assertEquals("测试网盘 / 目标目录", state.destination)
        assertEquals(0, calls)
        state.start()
        state.start()
        assertEquals(1, calls)
        assertFalse(state.visible)
        assertEquals(1, state.submitted.size)
    }

    @Test fun filesCanBeAddedAndRemovedBeforeStarting() = runBlocking {
        val one = temp.newFile("one")
        val two = temp.newFile("two")
        var uploaded = emptyList<UploadCandidate>()
        val state = state(this) { uploaded = it }
        state.stage(listOf(one))
        finishSelection()
        state.stage(listOf(one, two))
        finishSelection()
        state.remove(state.files.first())
        state.start()
        assertEquals(listOf(two.canonicalFile), uploaded.map { it.file })
    }

    @Test fun dismissCancelsSelectionAndDoesNotUpload() = runBlocking {
        val state = state(this) { fail("Dismiss must not upload") }
        state.stage(listOf(temp.newFile()))
        state.dismiss()
        finishSelection()
        assertFalse(state.visible)
        assertFalse(state.checking)
        assertTrue(state.files.isEmpty())
        state.start()
    }

    @Test fun loadingOrBusyPageCannotAcceptOrStartUploads() = runBlocking {
        val state = state(this) { fail("Disabled page must not upload") }
        state.enabled = false
        state.open()
        state.stage(listOf(temp.newFile()))
        assertFalse(state.visible)
        state.enabled = true
        state.stage(listOf(temp.newFile()))
        finishSelection()
        state.enabled = false
        state.start()
        assertTrue(state.visible)
        state.dismiss()
    }

    @Test fun folderOnlyDropWaitsForConfirmationAndPreservesEmptyFolder() = runBlocking {
        var uploaded = emptyList<UploadCandidate>()
        val state = state(this) { uploaded = it }
        state.stage(listOf(temp.newFolder()))
        finishSelection()
        assertEquals(1, state.files.size)
        assertTrue(state.files.single().isDirectory)
        assertTrue(uploaded.isEmpty())
        state.start()
        assertFalse(state.visible)
        assertTrue(uploaded.single().isDirectory)
    }
}
