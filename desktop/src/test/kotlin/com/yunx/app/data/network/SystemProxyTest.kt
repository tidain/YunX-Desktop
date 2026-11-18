package com.yunx.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemProxyTest {
    @Test
    fun `macOS reads HTTP proxy and ignores bypass list entries`() {
        val output = """
            <dictionary> {
              ExceptionsList : <array> {
                0 : localhost
                1 : *.local
              }
              HTTPEnable : 1
              HTTPProxy : 127.0.0.1
              HTTPPort : 7897
            }
        """.trimIndent()
        assertEquals(SystemProxy.Inspect.Proxy("127.0.0.1", 7897), SystemProxy.parseMac(output))
    }

    @Test
    fun `macOS prefers HTTPS proxy when both protocols are enabled`() {
        val output = """
            HTTPEnable : 1
            HTTPProxy : http.proxy
            HTTPPort : 8080
            HTTPSEnable : 1
            HTTPSProxy : https.proxy
            HTTPSPort : 8443
        """.trimIndent()
        assertEquals(SystemProxy.Inspect.Proxy("https.proxy", 8443), SystemProxy.parseMac(output))
    }

    @Test
    fun `macOS reports unsupported PAC and auto discovery instead of fixed proxy`() {
        for (key in listOf("ProxyAutoConfigEnable", "ProxyAutoDiscoveryEnable")) {
            val output = "$key : 1\nHTTPEnable : 1\nHTTPProxy : localhost\nHTTPPort : 8080"
            assertEquals(SystemProxy.Inspect.PacUnsupported, SystemProxy.parseMac(output))
        }
    }

    @Test
    fun `macOS ignores disabled or malformed proxy settings`() {
        for (output in listOf(
            "",
            "HTTPEnable : 0\nHTTPProxy : localhost\nHTTPPort : 8080",
            "HTTPEnable : 1\nHTTPPort : 8080",
            "HTTPEnable : 1\nHTTPProxy : localhost\nHTTPPort : invalid",
            "HTTPEnable : 1\nHTTPProxy : localhost\nHTTPPort : 65536"
        )) {
            assertEquals(output, SystemProxy.Inspect.Direct, SystemProxy.parseMac(output))
        }
    }

    @Test
    fun `Windows proxy parsing retains protocol and PAC behavior`() {
        assertEquals(SystemProxy.Inspect.Proxy("localhost", 8080),
            SystemProxy.parse(1, "http=localhost:8080;https=other:8443", null))
        assertEquals(SystemProxy.Inspect.Direct, SystemProxy.parse(0, "localhost:8080", null))
        assertEquals(SystemProxy.Inspect.PacUnsupported,
            SystemProxy.parse(1, "localhost:8080", "https://example.com/proxy.pac"))
    }
}
