package com.yunx.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主页输入框分流（视频 / P2P / 网盘）的纯逻辑单测。
 */
class LinkInputRouterTest {

    private val magnet =
        "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Example"

    @Test
    fun `磁力_ed2k_本地种子文件交给下载页`() {
        assertEquals(LinkInputRouter.Target.P2P, LinkInputRouter.targetOf(magnet))
        assertEquals(
            LinkInputRouter.Target.P2P,
            LinkInputRouter.targetOf("ed2k://|file|Example.avi|734003200|0123456789ABCDEF0123456789ABCDEF|/")
        )
        assertEquals(LinkInputRouter.Target.P2P, LinkInputRouter.targetOf("D:/Downloads/Example.torrent"))
    }

    @Test
    fun `磁力链接 dn 里的视频站地址不改变 P2P 分流`() {
        val withVideoName = magnet + "&dn=https://www.bilibili.com/video/BV1xx411c7mD"
        assertEquals(LinkInputRouter.Target.P2P, LinkInputRouter.targetOf(withVideoName))
    }

    @Test
    fun `视频链接与网盘链接维持原分流`() {
        assertEquals(
            LinkInputRouter.Target.VIDEO,
            LinkInputRouter.targetOf("https://www.bilibili.com/video/BV1xx411c7mD")
        )
        assertEquals(
            LinkInputRouter.Target.RESOLVE,
            LinkInputRouter.targetOf("https://pan.quark.cn/s/81c7b58ff633")
        )
        assertEquals(
            LinkInputRouter.Target.RESOLVE,
            LinkInputRouter.targetOf("绝对有效 咐置松君杂货铺叩苓")
        )
    }
}
