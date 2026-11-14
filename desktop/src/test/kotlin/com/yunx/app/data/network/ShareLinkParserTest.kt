package com.yunx.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkParserTest {

    @Test
    fun parsesAllSupportedPlatforms() {
        val cases = listOf(
            "https://pan.quark.cn/s/Abc123?pwd=a1B2" to (SharePlatform.QUARK to "Abc123"),
            "https://drive.uc.cn/s/Abc123" to (SharePlatform.UC to "Abc123"),
            "https://pan.xunlei.com/s/Abc_123-xy" to (SharePlatform.XUNLEI to "Abc_123-xy"),
            "https://pan.baidu.com/s/1Abc_123-xy?pwd=9xYz" to (SharePlatform.BAIDU to "Abc_123-xy"),
            "https://yun.139.com/shareweb/#/w/i/Abc_123" to (SharePlatform.C139 to "Abc_123"),
            "https://www.123pan.com/s/2785Vv-T4Ded" to (SharePlatform.PAN123 to "2785Vv-T4Ded")
        )

        cases.forEach { (text, expected) ->
            val parsed = ShareLinkParser.parse(text)!!
            assertEquals(expected.first, parsed.platform)
            assertEquals(expected.second, parsed.shareId)
        }
    }

    @Test
    fun explicitTextPasswordIsExtracted() {
        val parsed = ShareLinkParser.parse("链接 https://drive.uc.cn/s/Abc123 提取码：a1B2")!!
        assertEquals("a1B2", parsed.pwd)
    }

    @Test
    fun rejectsUnrelatedUrl() {
        assertNull(ShareLinkParser.parse("https://example.com/s/Abc123"))
    }

    @Test
    fun parseAllFindsEveryLinkInOrder() {
        val text = """
            看这个 https://pan.quark.cn/s/Quark123 提取码：aa11
            还有 https://drive.uc.cn/s/Uc456 密码: bb22
            以及 https://example.com/s/ignored
        """.trimIndent()

        val links = ShareLinkParser.parseAll(text)

        assertEquals(2, links.size)
        assertEquals(SharePlatform.QUARK, links[0].share.platform)
        assertEquals("Quark123", links[0].share.shareId)
        assertEquals("aa11", links[0].share.pwd)
        assertEquals(SharePlatform.UC, links[1].share.platform)
        assertEquals("Uc456", links[1].share.shareId)
        assertEquals("bb22", links[1].share.pwd)
    }

    @Test
    fun parseSkipsUnsupportedLeadingUrl() {
        val parsed = ShareLinkParser.parse(
            "https://example.com/s/nothing https://pan.baidu.com/s/1Real123?pwd=abcd"
        )!!

        assertEquals(SharePlatform.BAIDU, parsed.platform)
        assertEquals("Real123", parsed.shareId)
        assertEquals("abcd", parsed.pwd)
    }

    @Test
    fun parseAllDeduplicatesSameUrl() {
        val links = ShareLinkParser.parseAll(
            "https://pan.quark.cn/s/Same123 提取码：aa11 https://pan.quark.cn/s/Same123"
        )

        assertEquals(1, links.size)
        assertEquals("aa11", links[0].share.pwd)
    }
}
