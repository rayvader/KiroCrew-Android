package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRulesTest {

    @Test
    fun draftPutsTheSubjectFirst() {
        assertEquals("A page\nhttps://example.com/a", ShareRules.draft("A page", "https://example.com/a"))
    }

    @Test
    fun draftSkipsASubjectTheTextAlreadyHas() {
        assertEquals("A page https://example.com/a", ShareRules.draft("A page", " A page https://example.com/a "))
    }

    @Test
    fun draftWorksWithOnlyOnePart() {
        assertEquals("hello", ShareRules.draft(null, "hello"))
        assertEquals("subject", ShareRules.draft("subject", ""))
        assertEquals("", ShareRules.draft(null, null))
    }

    @Test
    fun draftIsCapped() {
        assertEquals(ShareRules.MAX_TEXT, ShareRules.draft(null, "x".repeat(ShareRules.MAX_TEXT + 50)).length)
    }

    @Test
    fun onlyOtherAppsContentLinksAreRead() {
        val own = "io.github.rayvader.kirocrew"
        assertTrue(ShareRules.allowedUri("content", "media", own))
        assertTrue(ShareRules.allowedUri("content", "com.google.android.apps.photos.contentprovider", own))
        assertFalse(ShareRules.allowedUri("file", null, own))
        assertFalse(ShareRules.allowedUri("file", "", own))
        assertFalse(ShareRules.allowedUri("content", own, own))
        assertFalse(ShareRules.allowedUri("content", "$own.androidx-startup", own))
        assertFalse(ShareRules.allowedUri("content", "", own))
        assertFalse(ShareRules.allowedUri("http", "example.com", own))
    }

    @Test
    fun sharedTextIsQuotedIntoTheScript() {
        val js = Scripts.shareIntoChat("tok", "a \"quote\" </script>\nline 2", 2)
        assertTrue(js.contains("T=\"tok\""))
        assertTrue(js.contains("N=2"))
        assertFalse(js.contains("a \"quote\""))
        assertFalse(js.contains("\nline 2\""))
        assertTrue(js.contains("mc:prefill-composer"))
    }

    @Test
    fun fileNamesLoseFoldersAndControlCharacters() {
        assertEquals("photo.jpg", ShareRules.fileName("../../photo.jpg", "x"))
        assertEquals("a.txt", ShareRules.fileName("C:\\dir\\a.txt", "x"))
        assertEquals("ab.txt", ShareRules.fileName("a\u0000b.txt", "x"))
        assertEquals("shared-1", ShareRules.fileName("  ", "shared-1"))
        assertEquals("shared-1", ShareRules.fileName(null, "shared-1"))
        assertEquals(120, ShareRules.fileName("n".repeat(300), "x").length)
    }
}
