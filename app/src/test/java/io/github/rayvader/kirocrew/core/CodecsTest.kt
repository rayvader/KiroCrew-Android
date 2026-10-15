package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {

    @Test
    fun profiles_roundTrip() {
        val list = listOf(
            Profile("a", "Home | Tailscale", "http://h.ts.net:5476"),
            Profile("b", "Line\nbreak ✓", "https://kiro.example.com/base"),
        )
        assertEquals(list, ProfileCodec.decode(ProfileCodec.encode(list)))
    }

    @Test
    fun profiles_skipsBrokenLines() {
        assertEquals(emptyList<Profile>(), ProfileCodec.decode(null))
        assertEquals(emptyList<Profile>(), ProfileCodec.decode("garbage\nalso|bad"))
        val decoded = ProfileCodec.decode("id1||http%3A%2F%2Fh%3A5476")
        assertEquals(listOf(Profile("id1", "http://h:5476", "http://h:5476")), decoded)
    }

    @Test
    fun dataUrl_base64() {
        val d = DataUrl.parse("data:text/csv;base64,YSxiCjEsMg==")!!
        assertEquals("text/csv", d.mimeType)
        assertEquals("a,b\n1,2", String(d.bytes))
    }

    @Test
    fun dataUrl_plain() {
        val d = DataUrl.parse("data:,a+b%20c")!!
        assertEquals("text/plain", d.mimeType)
        assertEquals("a+b c", String(d.bytes))
        assertNull(DataUrl.parse("nope"))
        assertNull(DataUrl.parse("data:text/plain;base64"))
    }

    @Test
    fun fileNames() {
        assertEquals("a_b_c_.txt", FileNames.sanitize("a/b:c?.txt"))
        assertEquals("download", FileNames.sanitize("   "))
        assertEquals("download", FileNames.sanitize(null))
        assertEquals(120, FileNames.sanitize("x".repeat(300)).length)
    }

    @Test
    fun jsQuote() {
        assertEquals("\"a\\\"b\\\\c\\n\"", JsQuote.str("a\"b\\c\n"))
        assertEquals("\"\\u2028\\u0001\"", JsQuote.str("\u2028\u0001"))
    }

    @Test
    fun scripts_embedQuotedValues() {
        val css = Scripts.customCss("body{color:red}\n/* \"x\" */")
        assertTrue(css.contains("\"body{color:red}\\n/* \\\"x\\\" */\""))
        assertTrue(Scripts.spaNavigate("http://h/chat").contains("\"http://h/chat\""))
        assertTrue(Scripts.DOWNLOAD_HOOKS.contains("KiroCrewAndroid.saveDataUrl"))
    }

    @Test
    fun shortcuts() {
        assertEquals(Shortcut.SCHEDULE, Shortcut.fromKey("schedule"))
        assertNull(Shortcut.fromKey("nope"))
        val bar = Shortcut.DEFAULT_BAR
        assertEquals(Shortcut.SETTINGS, Shortcut.matching("/settings/chat", bar))
        assertEquals(Shortcut.CHAT, Shortcut.matching("/chat", bar))
        assertNull(Shortcut.matching("/chatroom", bar))
        assertNull(Shortcut.matching("/logs", bar))
    }
}
