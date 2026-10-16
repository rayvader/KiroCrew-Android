package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {

    private fun note(ts: String, title: String = "Note $ts", extra: String = "") =
        """{"ts":"$ts","title":"$title","body":"**Cron** finished","kind":"cron"$extra}"""

    private fun feed(vararg notes: String) = """{"notifications":[${notes.joinToString(",")}],"unread":${notes.size}}"""

    @Test
    fun parseFeed_readsNotesAndSkipsJunk() {
        val alerts = AlertFeed.parseFeed(
            feed(
                note("2026-10-16T10:00:00.000001+00:00", extra = ""","url":"/schedule?job=1""""),
                """{"title":"no ts"}""",
                """{"ts":"2026-10-16T10:00:01+00:00","title":"  "}""",
                """{"_type":"slots","ts":"2026-10-16T10:00:02+00:00","title":"frame"}""",
                note("2026-10-16T10:00:03+00:00", extra = ""","url":null,"priority":"passive","silenced":true"""),
            )
        )!!
        assertEquals(2, alerts.size)
        assertEquals("Cron finished", alerts[0].body)
        assertEquals("/schedule?job=1", alerts[0].path)
        assertTrue(alerts[0].loud)
        assertNull(alerts[1].url)
        assertFalse(alerts[1].loud)
    }

    @Test
    fun parseFeed_rejectsWhatIsNotAFeed() {
        assertNull(AlertFeed.parseFeed("<html>login</html>"))
        assertNull(AlertFeed.parseFeed("""{"error":"forbidden"}"""))
        assertEquals(emptyList<Alert>(), AlertFeed.parseFeed("""{"notifications":[]}"""))
    }

    @Test
    fun path_onlySameServerPaths() {
        fun withUrl(url: String?) = Alert("t", "x", "", url, "normal", silenced = false, acked = false)
        assertEquals("/chat", withUrl("/chat").path)
        assertNull(withUrl("//evil.example/x").path)
        assertNull(withUrl("https://evil.example/").path)
        assertNull(withUrl(null).path)
    }

    @Test
    fun sync_firstRunOnlySetsTheMarker() {
        val alerts = AlertFeed.parseFeed(feed(note("2026-10-16T10:00:00+00:00"), note("2026-10-16T11:00:00+00:00")))!!
        val s = AlertFeed.sync(alerts, marker = null)
        assertEquals(emptyList<Alert>(), s.toPost)
        assertEquals("2026-10-16T11:00:00+00:00", s.marker)
        assertEquals("", AlertFeed.sync(emptyList(), null).marker)
    }

    @Test
    fun sync_postsOnlyNewLoudNotes_oldestFirst() {
        val alerts = AlertFeed.parseFeed(
            feed(
                note("2026-10-16T12:00:00.5+00:00", "C"),
                note("2026-10-16T10:00:00+00:00", "old"),
                note("2026-10-16T11:30:00+00:00", "B"),
                note("2026-10-16T11:45:00+00:00", "read", extra = ""","acked":true"""),
            )
        )!!
        val s = AlertFeed.sync(alerts, marker = "2026-10-16T11:00:00+00:00")
        assertEquals(listOf("B", "C"), s.toPost.map { it.title })
        assertEquals(0, s.skipped)
        assertEquals("2026-10-16T12:00:00.5+00:00", s.marker)
    }

    @Test
    fun sync_emptyMarkerMeansEverythingIsNew() {
        val alerts = AlertFeed.parseFeed(feed(note("2026-10-16T10:00:00+00:00", "A")))!!
        assertEquals(listOf("A"), AlertFeed.sync(alerts, marker = "").toPost.map { it.title })
    }

    @Test
    fun sync_capsHowManyItShows() {
        val notes = (10..17).map { note("2026-10-16T$it:00:00+00:00", "n$it") }.toTypedArray()
        val s = AlertFeed.sync(AlertFeed.parseFeed(feed(*notes))!!, marker = "", max = 3)
        assertEquals(listOf("n15", "n16", "n17"), s.toPost.map { it.title })
        assertEquals(5, s.skipped)
    }

    @Test
    fun sync_markerNeverGoesBack() {
        val alerts = AlertFeed.parseFeed(feed(note("2026-10-16T10:00:00+00:00")))!!
        assertEquals("2026-10-16T12:00:00+00:00", AlertFeed.sync(alerts, "2026-10-16T12:00:00+00:00").marker)
    }

    @Test
    fun compareTs_usesRealTimeAcrossOffsets() {
        assertTrue(AlertFeed.compareTs("2026-10-16T12:00:00+02:00", "2026-10-16T11:00:00+00:00") < 0)
        assertTrue(AlertFeed.isAfter("2026-10-16T11:00:00.000001+00:00", "2026-10-16T11:00:00+00:00"))
    }

    @Test
    fun liveNote_fromStreamEvent() {
        val a = AlertFeed.parseNoteJson(note("2026-10-16T10:00:00+00:00", "Approval needed"))!!
        assertEquals("Approval needed", a.title)
        assertNull(AlertFeed.parseNoteJson("""{"_type":"refresh","kinds":["crons"]}"""))
        assertNull(AlertFeed.parseNoteJson("not json"))
    }

    @Test
    fun liveTags() {
        assertTrue(LiveTags.isSessionEvent("kirocrew-chat-done:dashboard:abc"))
        assertTrue(LiveTags.isSessionEvent("kirocrew-approval:slot:42"))
        assertFalse(LiveTags.isSessionEvent("kirocrew-notif"))
        assertFalse(LiveTags.isSessionEvent("job-123"))
    }

    @Test
    fun markdown_plain() {
        assertEquals("name -- description", Markdown.plain("**name** -- description"))
        assertEquals("See PR #1 now", Markdown.plain("See [PR #1](https://github.com/x/y/pull/1) now"))
        assertEquals("keep snake_case_name and 2*3*4", Markdown.plain("keep snake_case_name and 2*3*4"))
        assertEquals("Title\nemph and code", Markdown.plain("## Title\n_emph_ and `code`"))
        assertEquals("• one\n• two", Markdown.plain("- one\n* two"))
        assertEquals("quoted", Markdown.plain("> quoted"))
        assertEquals("a\n\nb", Markdown.plain("a\n\n\n\nb"))
    }

    @Test
    fun sse_parsesEvents() {
        val p = SseParser()
        val lines = listOf(
            ": comment",
            "event: dashboard",
            "data: {\"a\":1}",
            "",
            "event: notification",
            "data: line1",
            "data:line2",
            "",
            "",
            "data: plain",
            "",
        )
        val events = lines.mapNotNull { p.feed(it) }
        assertEquals(
            listOf("dashboard" to "{\"a\":1}", "notification" to "line1\nline2", "message" to "plain"),
            events,
        )
    }
}
