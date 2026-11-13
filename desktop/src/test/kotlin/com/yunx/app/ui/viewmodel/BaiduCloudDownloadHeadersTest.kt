package com.yunx.app.ui.viewmodel

import com.yunx.app.data.network.BaiduConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class BaiduCloudDownloadHeadersTest {
    @Test
    fun downloadHeadersUseP2spUa() {
        val headers = baiduCloudDownloadHeaders("BDUSS=test")

        // 取链（locatedownload）与下载必须同用 P2SP 客户端 UA，否则 CDN 直链 403 sign error
        assertEquals(BaiduConstants.UA_P2SP, headers["User-Agent"])
        assertEquals("BDUSS=test", headers["Cookie"])
    }
}
