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
    fun rowsFillTheHeight() {
        // Raymond's Wide on Smart Launcher is about 139 dp; the emulator's 4x2 is about 223 dp.
        assertEquals(WidgetFit(4, true), Upcoming.fit(139f, 1f, 4))
        assertEquals(WidgetFit(3, true, more = 2, moreLine = true), Upcoming.fit(139f, 1f, 5))
        assertEquals(WidgetFit(2, true, more = 3, moreLine = true), Upcoming.fit(139f, 1.15f, 5))
        assertEquals(WidgetFit(1, true, more = 4, moreLine = true), Upcoming.fit(110f, 1.15f, 5))
        assertEquals(WidgetFit(1, true), Upcoming.fit(110f, 1.15f, 1))
        // Tall enough: every item shows, no "+N more".
        assertEquals(WidgetFit(5, true), Upcoming.fit(223f, 1f, 5))
        assertEquals(WidgetFit(5, true), Upcoming.fit(223f, 1.15f, 5))
        assertEquals(WidgetFit(5, true), Upcoming.fit(300f, 1f, 5))
        assertEquals(WidgetFit(5, true), Upcoming.fit(300f, 1.15f, 5))
        assertEquals(WidgetFit(9, true), Upcoming.fit(300f, 1f, 9))
        // Not more rows than the layout has.
        assertEquals(WidgetFit(15, true, more = 5, moreLine = true), Upcoming.fit(600f, 1f, 20))
        assertEquals(WidgetFit(0, true), Upcoming.fit(139f, 1f, 0))
        // Room for the header and one row only: "+N more" beside the count.
        assertEquals(WidgetFit(1, true, more = 4), Upcoming.fit(60f, 1f, 5))
        // 4x1: one row and no header; "+N more" at the row's end.
        assertEquals(WidgetFit(1, false, more = 4), Upcoming.fit(50f, 1f, 5))
        assertEquals("+4 more", Upcoming.moreText(4))
    }

    @Test
    fun rowsNeverOverflowTheHeight() {
        val heights = (110..150).map { it.toFloat() } + listOf(223f, 300f, 600f)
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f)) {
            for (h in heights) {
                for (count in 0..20) {
                    val fit = Upcoming.fit(h, scale, count)
                    assertTrue("h=$h scale=$scale n=$count fit=$fit", Upcoming.used(fit, scale) <= h)
                    // Every item is either a row or counted in "+N more".
                    assertEquals(count, fit.rows + fit.more)
                }
            }
        }
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
