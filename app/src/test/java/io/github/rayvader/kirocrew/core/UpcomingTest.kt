package io.github.rayvader.kirocrew.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class UpcomingTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 4, 9, 0) // a Wednesday

    private fun dashboard(items: String, extra: String = ""): String {
        val island = """{"fields":{"open_items":$items,"items_as_of":"09:00"$extra},"agentic":["open_items"]}"""
            .replace("<", "\\u003c")
        val html = "<!doctype html><html><body>" +
            "<script type=\"application/json\" id=\"kirocrew-dashboard-data\">$island</script>" +
            "<script>var x = 1;</script></body></html>"
        return JSONObject().put("state", "live").put("rendered_html", html).toString()
    }

    private fun item(title: String, due: String?, kind: String = "booking", hasTime: Boolean = false) =
        UpcomingItem(title, kind, due?.let { Upcoming.parseDue(it, zone).first }, hasTime)

    @Test
    fun readsTheOpenItemsFieldOutOfTheDashboardPage() {
        val body = dashboard("""[{"n":1,"title":"Test parcel <A>","due":"2026-11-05","kind":"delivery","flag":null}]""")
        val items = Upcoming.parseItems(Upcoming.extractItems(body)!!, zone)
        assertEquals(1, items.size)
        assertEquals("Test parcel <A>", items[0].title)
        assertEquals("delivery", items[0].kind)
        assertEquals(LocalDateTime.of(2026, 11, 5, 0, 0), items[0].due)
        assertFalse(items[0].hasTime)
    }

    @Test
    fun aPageWithoutTheFieldReadsAsNothing() {
        assertNull(Upcoming.extractItems(JSONObject().put("state", "empty").toString()))
        assertNull(Upcoming.extractItems(JSONObject().put("rendered_html", "<html></html>").toString()))
        assertNull(Upcoming.extractItems("not json"))
        assertEquals("[]", Upcoming.extractItems(dashboard("[]")))
    }

    @Test
    fun soonestFirstUndatedLastAckedDropped() {
        val json = """[
            {"title":"Later","due":"2026-11-20","kind":"booking"},
            {"title":"No date","due":null,"kind":"deadline"},
            {"title":"Timed","due":"2026-11-05 14:10","kind":"flight"},
            {"title":"Acked","due":"2026-11-04","acked_at":"2026-11-03T10:00:00Z"},
            {"title":"Overdue","due":"2026-11-01","kind":"bill"},
            {"title":"","due":"2026-11-02"},
            "junk"
        ]"""
        assertEquals(listOf("Overdue", "Timed", "Later", "No date"), Upcoming.parseItems(json, zone).map { it.title })
        assertTrue(Upcoming.parseItems("not json", zone).isEmpty())
    }

    @Test
    fun dueValuesWithAndWithoutTime() {
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 10) to true, Upcoming.parseDue("2026-11-05 14:10", zone))
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 10) to true, Upcoming.parseDue("2026-11-05T14:10:00", zone))
        // An offset is turned into the phone's own time.
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 10) to true, Upcoming.parseDue("2026-11-05T06:10:00Z", zone))
        assertEquals(LocalDateTime.of(2026, 11, 5, 0, 0) to false, Upcoming.parseDue("2026-11-05", zone))
        assertEquals(null to false, Upcoming.parseDue("soon", zone))
        assertEquals(null to false, Upcoming.parseDue("", zone))
    }

    @Test
    fun lateSoonAndLater() {
        assertEquals(DueState.LATE, Upcoming.state(item("a", "2026-11-03"), now))
        // A date with no time is not late until that day ends.
        assertEquals(DueState.SOON, Upcoming.state(item("a", "2026-11-04"), now))
        assertEquals(DueState.LATER, Upcoming.state(item("a", "2026-11-06"), now))
        assertEquals(DueState.LATE, Upcoming.state(item("a", "2026-11-04 08:59", hasTime = true), now))
        assertEquals(DueState.SOON, Upcoming.state(item("a", "2026-11-05 09:00", hasTime = true), now))
        assertEquals(DueState.LATER, Upcoming.state(item("a", "2026-11-05 09:01", hasTime = true), now))
        assertEquals(DueState.NONE, Upcoming.state(item("a", null), now))
    }

    @Test
    fun dueTextIs24HourAndSaysLate() {
        val uk = Locale.UK
        assertEquals("Thu 5 14:10", Upcoming.dueText(item("a", "2026-11-05 14:10", hasTime = true), now, uk))
        assertEquals("Thu 5 21:05", Upcoming.dueText(item("a", "2026-11-05 21:05", hasTime = true), now, uk))
        assertEquals("Thu 5", Upcoming.dueText(item("a", "2026-11-05"), now, uk))
        assertEquals("late Sun 1", Upcoming.dueText(item("a", "2026-11-01"), now, uk))
        // Far away: the month too.
        assertEquals("Sat 28 Nov", Upcoming.dueText(item("a", "2026-11-28"), now, uk))
        assertEquals("", Upcoming.dueText(item("a", null), now, uk))
    }

    @Test
    fun kindIcons() {
        assertEquals("📅", Upcoming.icon("booking"))
        assertEquals("✈", Upcoming.icon("flight"))
        assertEquals("📦", Upcoming.icon("delivery"))
        assertEquals("⏰", Upcoming.icon("deadline"))
        assertEquals("•", Upcoming.icon("something-new"))
    }

    @Test
    fun aGoodReadSaysUpdatedInLocal24HourTime() {
        val at = java.time.ZonedDateTime.of(2026, 11, 5, 14, 7, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(SyncLine(SyncNote.UPDATED, "14:07"), Upcoming.syncLine(false, false, true, at, zone))
        // Another zone, same instant: local time of that zone.
        assertEquals(SyncLine(SyncNote.UPDATED, "06:07"), Upcoming.syncLine(false, false, true, at, ZoneId.of("UTC")))
        val night = java.time.ZonedDateTime.of(2026, 11, 5, 23, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("23:05", Upcoming.syncLine(false, false, true, night, zone).time)
    }

    @Test
    fun aFailedReadKeepsTheListAndSaysAsOf() {
        val at = java.time.ZonedDateTime.of(2026, 11, 5, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(SyncLine(SyncNote.AS_OF, "09:30"), Upcoming.syncLine(false, true, true, at, zone))
    }

    @Test
    fun nothingReadYetSaysNothing() {
        assertEquals(SyncNote.NONE, Upcoming.syncLine(false, false, false, 0L, zone).note)
        assertEquals(SyncNote.NONE, Upcoming.syncLine(false, true, false, 0L, zone).note)
        assertEquals(SyncNote.NONE, Upcoming.syncLine(false, false, true, 0L, zone).note)
    }

    @Test
    fun aRunningRefreshSaysUpdating() {
        assertEquals(SyncLine(SyncNote.UPDATING, ""), Upcoming.syncLine(true, false, true, 1_000L, zone))
        assertEquals(SyncNote.UPDATING, Upcoming.syncLine(true, true, false, 0L, zone).note)
    }

    @Test
    fun refreshSpinnerStopsWhenDoneOrStale() {
        val t = 10_000_000L
        assertTrue(Upcoming.isRefreshing(t, t))
        assertTrue(Upcoming.isRefreshing(t, t + 30_000))
        // A run that never reported back stops spinning after two minutes.
        assertFalse(Upcoming.isRefreshing(t, t + Upcoming.REFRESH_SPIN_MS))
        // Cleared (0) or a clock that went back: not refreshing.
        assertFalse(Upcoming.isRefreshing(0L, t))
        assertFalse(Upcoming.isRefreshing(t, t - 1))
    }

    @Test
    fun theSyncNoteSitsInTheHeaderSoNoRowIsLost() {
        // Rows per height are unchanged by the note: it shares the header line.
        assertEquals(3, Upcoming.fit(139f, 1f).rows)
        assertEquals(3, Upcoming.fit(139f, 1.15f).rows)
        assertEquals(2, Upcoming.fit(110f, 1.15f).rows)
    }

    @Test
    fun listShowsWholeRowsAndScrollsTheRest() {
        // Raymond's Wide on Smart Launcher is about 139 dp; the emulator's 4x2 is about 223 dp.
        assertEquals(WidgetFit(3, true, 84f), Upcoming.fit(139f, 1f))
        assertEquals(3, Upcoming.fit(139f, 1.15f).rows)
        assertEquals(2, Upcoming.fit(110f, 1f).rows)
        assertEquals(2, Upcoming.fit(110f, 1.15f).rows)
        assertEquals(6, Upcoming.fit(223f, 1f).rows)
        assertEquals(5, Upcoming.fit(223f, 1.15f).rows)
        assertEquals(9, Upcoming.fit(300f, 1f).rows)
        // 4x1: one row, no header.
        assertEquals(WidgetFit(1, false, 28f), Upcoming.fit(50f, 1f))
        // The list is snapped to whole rows, so no row is cut at rest.
        assertEquals(Upcoming.fit(139f, 1.15f).rows * Upcoming.rowHeight(1.15f), Upcoming.fit(139f, 1.15f).listHeightDp, 0.001f)
    }

    @Test
    fun listNeverSticksOut() {
        val heights = (110..150).map { it.toFloat() } + listOf(139f, 223f, 300f, 600f)
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f)) {
            for (h in heights) {
                val fit = Upcoming.fit(h, scale)
                assertTrue("h=$h scale=$scale fit=$fit", Upcoming.used(fit, scale) <= h)
                assertTrue("h=$h scale=$scale", fit.rows >= 1 && fit.header)
            }
        }
    }

    @Test
    fun noMoreLineEveryItemIsInTheList() {
        // The list holds every open item; nothing is counted away as "+N more".
        val items = (1..8).map { item("Item $it", "2026-11-%02d".format(4 + it)) }
        val (shown, _) = Upcoming.applyHidden(items, emptyMap(), 0L)
        assertEquals(8, shown.size)
        assertEquals("Item 8", shown.last().title)
    }

    @Test
    fun markedDoneStaysHiddenUntilFroyoDropsIt() {
        val items = listOf(item("A", "2026-11-05"), item("B", "2026-11-06"))
        val t = 1_000_000L
        val (shown, keep) = Upcoming.applyHidden(items, mapOf("A" to t, "Gone" to t), t + 1000)
        assertEquals(listOf("B"), shown.map { it.title })
        // "Gone" left Froyo's list, so it is no longer kept.
        assertEquals(mapOf("A" to t), keep)
        // Never dropped by Froyo: it comes back after a while.
        val (late, _) = Upcoming.applyHidden(items, mapOf("A" to t), t + Upcoming.HIDE_MS)
        assertEquals(listOf("A", "B"), late.map { it.title })
    }

    @Test
    fun hiddenMapRoundTrips() {
        val m = mapOf("Test \"one\"" to 5L, "two" to 7L)
        assertEquals(m, Upcoming.decodeHidden(Upcoming.encodeHidden(m)))
        assertTrue(Upcoming.decodeHidden("junk").isEmpty())
        assertTrue(Upcoming.decodeHidden(null).isEmpty())
    }

    @Test
    fun messagesAndPaths() {
        assertEquals("Done: Test parcel", Upcoming.doneMessage(" Test\nparcel "))
        assertEquals("Undo done: Test parcel", Upcoming.undoMessage("Test parcel"))
        val body = JSONObject(Upcoming.chatBody("member-x", "Done: A"))
        assertEquals("Done: A", body.getString("message"))
        assertEquals("member-x", body.getString("slot"))
        assertEquals("/chat?sid=member%3Ax", Upcoming.chatPagePath("member:x"))
        assertEquals("/chat", Upcoming.chatPagePath(null))
        assertEquals("/api/members/froyo-personal-butler/dashboard?member=Test+Butler", Upcoming.dashboardPath("Test Butler"))
    }

    @Test
    fun threadAndSendAnswers() {
        assertEquals(
            FroyoThread("member-froyo", "Test Butler"),
            Upcoming.parseThread("""{"slot_key":"member-froyo","slug":"froyo-personal-butler","member":"Test Butler"}"""),
        )
        assertNull(Upcoming.parseThread("""{"error":"no crew member for this slug"}"""))
        assertNull(Upcoming.parseThread("junk"))
        assertTrue(Upcoming.chatAccepted(200, "text/event-stream", ""))
        assertTrue(Upcoming.chatAccepted(200, "application/json", """{"queued":true,"queue_id":"q1"}"""))
        assertFalse(Upcoming.chatAccepted(200, "application/json", """{"ok":false,"error":"busy"}"""))
        assertFalse(Upcoming.chatAccepted(403, "text/plain", "CSRF check failed"))
        assertFalse(Upcoming.chatAccepted(200, "text/html", "<html>"))
    }
}
