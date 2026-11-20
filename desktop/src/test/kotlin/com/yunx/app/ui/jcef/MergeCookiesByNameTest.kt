package com.yunx.app.ui.jcef

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #94：collectCookies 把全局 Cookie 管理器里同名多份的 Cookie
 * （如 STOKEN×4）原样拼进同一个 Cookie 头，导致百度鉴权 errno=-6。
 */
class MergeCookiesByNameTest {

    @Test
    fun keepsLastValuePerNameAndFirstAppearanceOrder() {
        val merged = mergeCookiesByName(
            listOf(
                "BAIDUID" to "a",
                "STOKEN" to "old",
                "XFI" to "x1",
                "STOKEN" to "new1",
                "PTOKEN" to "p",
                "STOKEN" to "new2",
                "XFI" to "x2",
            )
        )
        // 保留每个名称最后一次出现的值；名称按首次出现顺序排列
        assertEquals(
            listOf(
                "BAIDUID" to "a",
                "STOKEN" to "new2",
                "XFI" to "x2",
                "PTOKEN" to "p",
            ),
            merged
        )
    }

    @Test
    fun singleOccurrenceIsUnchanged() {
        val cookies = listOf("BDUSS" to "b", "PANPSC" to "c")
        assertEquals(cookies, mergeCookiesByName(cookies))
    }

    @Test
    fun emptyInputYieldsEmptyOutput() {
        assertEquals(emptyList<Pair<String, String>>(), mergeCookiesByName(emptyList()))
    }

    @Test
    fun duplicateNamesDoNotReachTheCookieHeader() {
        // 与 #94 实测同构：4 份 STOKEN 去重后只剩 1 份且取最后一次出现的值
        val raw = buildList {
            repeat(4) { i -> add("STOKEN" to "v$i") }
            add("BDUSS" to "b")
        }
        val header = mergeCookiesByName(raw).joinToString("; ") { "${it.first}=${it.second}" }
        assertEquals("STOKEN=v3; BDUSS=b", header)
        assertEquals(1, header.split("STOKEN=").size - 1)

        // 基线行为对照：去重前的原样拼接正是 #94 的缺陷形态——
        // 4 份同名 STOKEN 同时进入 Cookie 头，百度据此判定鉴权失败（errno=-6）。
        val legacyHeader = raw.joinToString("; ") { "${it.first}=${it.second}" }
        assertEquals(4, legacyHeader.split("STOKEN=").size - 1)
    }
}
