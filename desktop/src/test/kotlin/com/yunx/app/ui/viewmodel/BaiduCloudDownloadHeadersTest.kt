package com.yunx.app.ui.viewmodel

import com.yunx.app.data.network.BaiduConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class BaiduCloudDownloadHeadersTest {
    @Test
    fun downloadHeadersUseNetdiskUa() {
        val headers = baiduCloudDownloadHeaders("BDUSS=test")

        assertEquals(BaiduConstants.UA_NETDISK, headers["User-Agent"])
        assertEquals("BDUSS=test", headers["Cookie"])
    }
}
