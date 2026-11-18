package com.yunx.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePlatformTest {
    private val windows = listOf(
        UpdateChecker.Asset("YunX-Desktop-setup.exe", "https://example.com/setup.exe"),
        UpdateChecker.Asset("YunX-Desktop-portable.zip", "https://example.com/portable.zip")
    )
    private val mac = UpdateChecker.Asset("YunX-Desktop-macOS-arm64.dmg", "https://example.com/app.dmg")

    @Test
    fun `macOS never offers a Windows only release`() {
        assertTrue(UpdateChecker.assetsForPlatform(windows, "Mac OS X").isEmpty())
    }

    @Test
    fun `macOS selects only DMG from a release with multiple platforms`() {
        assertEquals(listOf(mac), UpdateChecker.assetsForPlatform(windows + mac, "Mac OS X"))
    }

    @Test
    fun `Windows assets remain unchanged`() {
        assertEquals(windows, UpdateChecker.assetsForPlatform(windows, "Windows 11"))
    }
}
