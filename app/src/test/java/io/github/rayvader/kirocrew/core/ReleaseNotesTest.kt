package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    private val body = """
        ## What's new

        - Add Auto text size: text scales with screen width (#7)
        - Slim edge tab for the menu when the top bar is off (#6)

        **Full changelog:** https://github.com/o/r/compare/v1.0.39...v1.0.42

        ---
        Built from main (abc). Download the APK on your phone and open it to install.
    """.trimIndent()

    @Test
    fun readsTheBulletsUnderWhatsNew() {
        assertEquals(
            listOf(
                "Add Auto text size: text scales with screen width (#7)",
                "Slim edge tab for the menu when the top bar is off (#6)",
            ),
            ReleaseNotes.whatsNew(body),
        )
    }

    @Test
    fun stopsAtTheChangelogLinkAndTheFooter() {
        assertTrue(ReleaseNotes.whatsNew(body).none { it.contains("Built from") || it.contains("compare") })
    }

    @Test
    fun oldStyleBodyHasNoList() {
        assertTrue(ReleaseNotes.whatsNew("Built from main (abc). Download the APK.").isEmpty())
        assertTrue(ReleaseNotes.whatsNew("").isEmpty())
        assertTrue(ReleaseNotes.whatsNew(null).isEmpty())
    }

    @Test
    fun windowsLineEndingsWork() {
        assertEquals(2, ReleaseNotes.whatsNew(body.replace("\n", "\r\n")).size)
    }

    @Test
    fun markdownBecomesPlainText() {
        val items = ReleaseNotes.whatsNew("## What's new\n- Fix **crash** in `Settings` ([#9](https://x.example/9))")
        assertEquals(listOf("Fix crash in Settings (#9)"), items)
    }

    @Test
    fun longLinesAreCut() {
        val item = ReleaseNotes.whatsNew("## What's new\n- " + "a".repeat(500)).single()
        assertTrue(item.length <= 160)
        assertTrue(item.endsWith("…"))
    }

    @Test
    fun otherSectionsAreIgnored() {
        assertTrue(ReleaseNotes.whatsNew("## Known issues\n- one\n- two").isEmpty())
        assertEquals(listOf("one"), ReleaseNotes.whatsNew("## Known issues\n- x\n## What's new\n- one\n## Other\n- two"))
    }

    @Test
    fun parserFillsTheNotes() {
        val json = """{"tag_name":"v1.0.42","draft":false,"prerelease":false,
            "html_url":"https://github.com/o/r/releases/tag/v1.0.42",
            "body":${org.json.JSONObject.quote(body)},
            "assets":[{"name":"KiroCrew-1.0.42.apk","browser_download_url":"https://github.com/o/r/KiroCrew-1.0.42.apk","size":5}]}"""
        assertEquals(2, ReleaseParser.parse(json)!!.notes.size)
    }
}
