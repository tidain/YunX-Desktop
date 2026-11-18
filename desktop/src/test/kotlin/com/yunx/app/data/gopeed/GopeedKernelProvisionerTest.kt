package com.yunx.app.data.gopeed

import org.junit.Assert.assertEquals
import org.junit.Test

class GopeedKernelProvisionerTest {
    @Test
    fun `Apple Silicon selects the official macos arm64 archive for both JVM architecture names`() {
        for (arch in listOf("aarch64", "arm64")) {
            assertEquals("gopeed-web-v1.9.3-macos-arm64.zip",
                GopeedKernelProvisioner.assetNameFor("v1.9.3", "Mac OS X", arch))
        }
    }

    @Test
    fun `Windows retains official amd64 and arm64 archive names`() {
        assertEquals("gopeed-web-v1.9.3-windows-amd64.zip",
            GopeedKernelProvisioner.assetNameFor("v1.9.3", "Windows 11", "amd64"))
        assertEquals("gopeed-web-v1.9.3-windows-arm64.zip",
            GopeedKernelProvisioner.assetNameFor("v1.9.3", "Windows 11", "aarch64"))
    }
}
