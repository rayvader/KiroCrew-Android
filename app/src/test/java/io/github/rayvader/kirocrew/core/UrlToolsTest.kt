package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlToolsTest {

    @Test
    fun normalize_hostWithPortUsesHttp() {
        assertEquals("http://ip-1.tailnet.ts.net:5476", UrlTools.normalizeServerUrl("ip-1.tailnet.ts.net:5476"))
        assertEquals("http://100.101.102.103", UrlTools.normalizeServerUrl(" 100.101.102.103 "))
        assertEquals("http://localhost:5476", UrlTools.normalizeServerUrl("localhost:5476/"))
    }

    @Test
    fun normalize_plainHostUsesHttps() {
        assertEquals("https://kiro.example.com", UrlTools.normalizeServerUrl("kiro.example.com/"))
    }

    @Test
    fun normalize_keepsSchemePortAndPath() {
        assertEquals("https://kiro.example.com:8443/base", UrlTools.normalizeServerUrl("HTTPS://Kiro.Example.com:8443/base/"))
    }

    @Test
    fun normalize_rejectsBadInput() {
        assertNull(UrlTools.normalizeServerUrl(""))
        assertNull(UrlTools.normalizeServerUrl("ftp://host"))
        assertNull(UrlTools.normalizeServerUrl("http://"))
        assertNull(UrlTools.normalizeServerUrl("a b"))
        assertNull(UrlTools.normalizeServerUrl("http://host:99999"))
    }

    @Test
    fun sameServer() {
        assertTrue(UrlTools.isSameServer("http://h:5476", "http://h:5476/chat?x=1"))
        assertTrue(UrlTools.isSameServer("https://h", "https://h:443/a"))
        assertTrue(UrlTools.isSameServer("http://h:5476", "http://H:5476/api/x?q=a|b c"))
        assertFalse(UrlTools.isSameServer("http://h:5476", "http://other:5476"))
        assertFalse(UrlTools.isSameServer("http://h:5476", "http://h/"))
        assertFalse(UrlTools.isSameServer("http://h", "mailto:a@b.c"))
    }

    @Test
    fun buildUrl() {
        assertEquals("http://h:5476/chat", UrlTools.buildUrl("http://h:5476", "/chat"))
        assertEquals("http://h:5476/chat", UrlTools.buildUrl("http://h:5476/", "chat"))
        assertEquals("http://h/", UrlTools.buildUrl("http://h", ""))
        assertEquals("https://x.y/z", UrlTools.buildUrl("http://h", "https://x.y/z"))
    }

    @Test
    fun pathOf() {
        assertEquals("/settings/overview", UrlTools.pathOf("http://h:5476/settings/overview?tab=1#x"))
        assertEquals("/", UrlTools.pathOf("http://h:5476"))
    }

    @Test
    fun unencryptedPublic() {
        assertTrue(UrlTools.isUnencryptedPublic("http://example.com"))
        assertTrue(UrlTools.isUnencryptedPublic("http://8.8.8.8:5476"))
        assertFalse(UrlTools.isUnencryptedPublic("https://example.com"))
        assertFalse(UrlTools.isUnencryptedPublic("http://host.tailnet.ts.net:5476"))
        assertFalse(UrlTools.isUnencryptedPublic("http://100.70.1.2:5476"))
        assertFalse(UrlTools.isUnencryptedPublic("http://192.168.1.20:5476"))
    }

    @Test
    fun parseQr() {
        val login = UrlTools.parseQr("http://h.ts.net:5476/api/auth/qr?t=abc")!!
        assertEquals("http://h.ts.net:5476", login.baseUrl)
        assertEquals("http://h.ts.net:5476/api/auth/qr?t=abc", login.openUrl)

        val plain = UrlTools.parseQr("http://h:5476/")!!
        assertEquals("http://h:5476", plain.baseUrl)
        assertNull(plain.openUrl)

        assertEquals("http://h:5476", UrlTools.parseQr("h:5476")!!.baseUrl)
        assertNull(UrlTools.parseQr("hello world"))
        assertNull(UrlTools.parseQr("WIFI:S:net;T:WPA;P:x;;"))
    }
}
