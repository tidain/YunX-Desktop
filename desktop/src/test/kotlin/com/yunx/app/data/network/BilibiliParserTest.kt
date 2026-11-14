package com.yunx.app.data.network

import com.yunx.app.data.network.video.BilibiliParser
import com.yunx.app.data.network.video.DetectedVideoLink
import com.yunx.app.data.network.video.VideoPlatform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliParserTest {

    private fun link(url: String) = DetectedVideoLink(
        platform = VideoPlatform.BILIBILI,
        kind = "video",
        id = "BV1xx411c7mD",
        url = url
    )

    private fun pages(vararg entries: Pair<Int, Long>): JSONObject = JSONObject().put(
        "pages",
        JSONArray().apply {
            entries.forEach { (page, cid) ->
                put(JSONObject().put("page", page).put("cid", cid).put("part", "P$page"))
            }
        }
    )

    @Test
    fun extractsPageNumberFromBilibiliUrl() {
        assertEquals(
            2,
            BilibiliParser.requestedPage(
                link("https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999&p=2")
            )
        )
        assertEquals(3, BilibiliParser.requestedPage(link("https://www.bilibili.com/video/BV1xx411c7mD?p=3")))
    }

    @Test
    fun defaultsToFirstPageWhenPageIsMissingOrInvalid() {
        val video = link("https://www.bilibili.com/video/BV1xx411c7mD")
        assertEquals(1, BilibiliParser.requestedPage(video))
        assertEquals(1, BilibiliParser.requestedPage(link("https://www.bilibili.com/video/BV1xx411c7mD?p=abc")))
        assertEquals(1, BilibiliParser.requestedPage(link("https://www.bilibili.com/video/BV1xx411c7mD?p=0")))
    }

    @Test
    fun selectsRequestedPageByPageNumber() {
        val data = pages(1 to 111L, 2 to 222L, 3 to 333L)
        assertEquals(222L, BilibiliParser.resolvePage(data, 2)?.optLong("cid"))
        assertEquals(333L, BilibiliParser.resolvePage(data, 3)?.optLong("cid"))
    }

    @Test
    fun fallsBackToFirstPageWhenRequestedPageIsOutOfRange() {
        val data = pages(1 to 111L, 2 to 222L)
        assertEquals(1, BilibiliParser.resolvePage(data, 9)?.optInt("page"))
        assertNull(BilibiliParser.resolvePage(JSONObject(), 2))
    }

    @Test
    fun usesIndexWhenPagesHaveNoPageNumber() {
        val legacy = JSONObject().put(
            "pages",
            JSONArray().apply {
                put(JSONObject().put("cid", 111L))
                put(JSONObject().put("cid", 222L))
            }
        )
        assertEquals(222L, BilibiliParser.resolvePage(legacy, 2)?.optLong("cid"))
    }
}
