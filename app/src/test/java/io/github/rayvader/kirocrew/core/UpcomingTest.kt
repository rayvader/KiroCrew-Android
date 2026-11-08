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

    /** Like Froyo's dashboard from page v4: open_items_live (sync job, object) beside open_items. */
    private fun v4Dashboard(live: String?, items: String): String {
        val liveField = live?.let { ",\"open_items_live\":$it" } ?: ""
        val island = """{"fields":{"cleared_items":[],"coming_up":[],"items_as_of":"Thu 5 Nov, 17:01 SGT",""" +
            """"milestones":[],"open_items":$items$liveField,"waiting_count":0,"waiting_items":[]},""" +
            """"agentic":["cleared_items","coming_up","items_as_of","open_items"],"seq":42,"missing":[],""" +
            """"written_at":{"open_items":"2026-11-05T09:00:37Z"},"locale":"en","subject":"crewmate","last_user_at":""}"""
        val html = "<!doctype html><html><head><style>.x{}</style></head><body>" +
            "<script type=\"application/json\" id=\"kirocrew-dashboard-data\">$island</script>" +
            "<script>/* header time drawn by script */ document.getElementById('kirocrew-dashboard-data');</script>" +
            "</body></html>"
        return JSONObject()
            .put("instance_version", 4)
            .put("template", JSONObject().put("id", "froyo-dashboard").put("version", 4))
            .put("dashboard", "froyo-dashboard")
            .put("state", "live")
            .put("fields", org.json.JSONArray(listOf("open_items", "open_items_live")))
            .put("scripts", true)
            .put("rendered_html", html)
            .toString()
    }

    @Test
    fun aV4PageIsReadAndPrefersTheLiveList() {
        val old = """[{"n":1,"id":"a","title":"Old item","due":"2026-11-04","kind":"booking","flag":null}]"""
        val live = """{"items":[{"n":1,"id":"b","title":"Test dinner 18:30","due":"2026-11-05","kind":"booking"},""" +
            """{"n":2,"id":"c","title":"Test flight","due":"2026-11-09","kind":"flight"}],"as_of":"Thu 5 Nov, 17:01 SGT · 2 open"}"""
        val items = Upcoming.parseItems(Upcoming.extractItems(v4Dashboard(live, old))!!, zone)
        assertEquals(listOf("Test dinner 18:30", "Test flight"), items.map { it.title })
    }

    /** The real shape: the gateway's custom fold row wraps the job's record in `data`. */
    @Test
    fun theLiveListIsReadFromTheFoldRowsData() {
        val old = """[{"n":1,"id":"a","title":"Old item","due":"2026-11-04","kind":"booking","flag":null}]"""
        val live = """{"data":{"items":[{"n":1,"id":"b","title":"Test dinner 18:30","due":"2026-11-05","kind":"booking"},""" +
            """{"n":2,"id":"c","title":"Test snorkel 14:30","due":"2026-11-06","kind":"booking"}],""" +
            """"as_of":"Thu 5 Nov, 21:28 SGT · 2 open"},"at":"2026-11-05T13:28:55Z",""" +
            """"writer":"4cbe3e0f","stale_after":"2026-11-05T14:28:55Z"}"""
        val items = Upcoming.parseItems(Upcoming.extractItems(v4Dashboard(live, old))!!, zone)
        assertEquals(listOf("Test dinner 18:30", "Test snorkel 14:30"), items.map { it.title })
    }

    @Test
    fun aFoldRowWithNoListInItsDataFallsBackToOpenItems() {
        val old = """[{"n":1,"title":"Only item","due":null,"kind":"bill","flag":"x"}]"""
        val live = """{"data":{"as_of":"x"},"at":"2026-11-05T13:28:55Z"}"""
        assertEquals(listOf("Only item"), Upcoming.parseItems(Upcoming.extractItems(v4Dashboard(live, old))!!, zone).map { it.title })
        // An empty list under data is a real answer: nothing open.
        assertEquals("[]", Upcoming.extractItems(v4Dashboard("""{"data":{"items":[]}}""", old)))
    }

    @Test
    fun aV4PageWithoutTheLiveListFallsBackToOpenItems() {
        val old = """[{"n":1,"title":"Only item","due":null,"kind":"bill","flag":"x"}]"""
        assertEquals(listOf("Only item"), Upcoming.parseItems(Upcoming.extractItems(v4Dashboard(null, old))!!, zone).map { it.title })
        // A live field that is not the expected object falls back too.
        assertEquals(listOf("Only item"), Upcoming.parseItems(Upcoming.extractItems(v4Dashboard("\"soon\"", old))!!, zone).map { it.title })
        assertEquals(listOf("Only item"), Upcoming.parseItems(Upcoming.extractItems(v4Dashboard("{\"as_of\":\"x\"}", old))!!, zone).map { it.title })
        // An empty live list is a real answer: nothing open.
        assertEquals("[]", Upcoming.extractItems(v4Dashboard("{\"items\":[]}", old)))
    }

    @Test
    fun failureReasons() {
        assertEquals(null, Upcoming.failReason(200, false, true))
        assertEquals(FailReason.FORMAT, Upcoming.failReason(200, false, false))
        assertEquals(FailReason.SIGN_IN, Upcoming.failReason(401, true, false))
        assertEquals(FailReason.SIGN_IN, Upcoming.failReason(403, true, false))
        assertEquals(FailReason.SERVER, Upcoming.failReason(403, false, false))
        assertEquals(FailReason.SERVER, Upcoming.failReason(500, false, false))
        assertEquals(FailReason.SERVER, Upcoming.failReason(409, false, false))
        assertEquals(FailReason.NETWORK, Upcoming.failReason(502, false, false))
        assertEquals(FailReason.TIMEOUT, Upcoming.failReason(504, false, false))
        assertEquals("sign-in", FailReason.SIGN_IN.word)
        assertEquals("network", FailReason.NETWORK.word)
        assertEquals("format", FailReason.FORMAT.word)
    }

    @Test
    fun aWebPageInsteadOfJsonIsAFormatProblemNotSignIn() {
        // A server address that kept a page path (/chat/<slug>) gets the page's HTML back.
        assertEquals(FailReason.FORMAT, Upcoming.failReason(200, false, false, "text/html; charset=utf-8"))
        assertEquals(FailReason.FORMAT, Upcoming.failReason(403, true, false, "text/html"))
        assertEquals(FailReason.FORMAT, Upcoming.failReason(403, false, false, "Text/HTML"))
        assertEquals(FailReason.SIGN_IN, Upcoming.failReason(401, true, false, "application/json"))
        assertEquals(FailReason.SERVER, Upcoming.failReason(403, false, false, "application/json"))
        assertTrue(Upcoming.isHtml("text/html"))
        assertFalse(Upcoming.isHtml("application/json"))
        assertFalse(Upcoming.isHtml(null))
        // The page's HTML has no dashboard data island: no list.
        assertNull(Upcoming.extractItems("<!doctype html><html><body><div id=\"root\"></div></body></html>"))
    }

    @Test
    fun aStaleThreadIsAskedForAgain() {
        assertTrue(Upcoming.threadMayBeStale(400))
        assertTrue(Upcoming.threadMayBeStale(404))
        assertTrue(Upcoming.threadMayBeStale(409))
        assertFalse(Upcoming.threadMayBeStale(200))
        assertFalse(Upcoming.threadMayBeStale(401))
        assertFalse(Upcoming.threadMayBeStale(500))
    }

    @Test
    fun aFailedReadSaysWhyNextToAsOf() {
        val at = java.time.ZonedDateTime.of(2026, 11, 5, 5, 48, 0, 0, ZoneId.of("Indian/Maldives")).toInstant().toEpochMilli()
        val line = Upcoming.syncLine(false, true, true, at, ZoneId.of("Indian/Maldives"), FailReason.NETWORK)
        assertEquals(SyncLine(SyncNote.AS_OF, "05:48", FailReason.NETWORK), line)
        // A good read carries no reason, even if an old one was saved.
        assertEquals(null, Upcoming.syncLine(false, false, true, at, zone, FailReason.NETWORK).reason)
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

    private fun sgt(h: Int, m: Int, day: Int = 8) =
        java.time.ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun header(line: SyncLine): String {
        val head = when (line.note) {
            SyncNote.UPDATED -> "5 open · Updated ${line.time}"
            SyncNote.AS_OF -> "5 open · as of ${line.time}" + (line.reason?.let { " · ${it.word}" } ?: "")
            SyncNote.UPDATING -> "5 open · Updating…"
            SyncNote.NONE -> "5 open"
        }
        return head + Upcoming.syncTail(line)
    }

    @Test
    fun headerAFreshGoodReadSaysOnlyUpdated() {
        // The read started at 20:39:30 and saved at 20:40: tried is not newer, so not shown.
        val at = sgt(20, 40)
        val line = Upcoming.syncLine(false, false, true, at, zone, triedMs = at - 30_000, nowMs = sgt(20, 45))
        assertEquals(SyncLine(SyncNote.UPDATED, "20:40"), line)
        assertEquals("5 open · Updated 20:40", header(line))
    }

    @Test
    fun headerAReadTriedAfterTheLastGoodOneSaysTried() {
        val line = Upcoming.syncLine(false, false, true, sgt(5, 48), zone, triedMs = sgt(20, 40), nowMs = sgt(20, 41))
        assertEquals("5 open · Updated 05:48 · tried 20:40", header(line))
        assertFalse(line.stuck)
        // A failed read keeps its reason, then says when it was tried.
        val failed = Upcoming.syncLine(false, true, true, sgt(5, 48), zone, FailReason.NETWORK, sgt(20, 40), sgt(20, 41))
        assertEquals("5 open · as of 05:48 · network · tried 20:40", header(failed))
    }

    @Test
    fun headerNoReadTriedFor2HoursSaysNotUpdating() {
        // Last good read 05:48, nothing tried since: the job is stuck.
        val never = Upcoming.syncLine(false, false, true, sgt(5, 48), zone, triedMs = 0L, nowMs = sgt(20, 40))
        assertEquals("5 open · Updated 05:48 · not updating", header(never))
        // Tried at 06:10, but nothing for 2 h since: both show.
        val old = Upcoming.syncLine(false, true, true, sgt(5, 48), zone, FailReason.FORMAT, sgt(6, 10), sgt(20, 40))
        assertEquals("5 open · as of 05:48 · format · tried 06:10 · not updating", header(old))
        // A read tried within 2 h: not stuck, even though the good one is old.
        val recent = Upcoming.syncLine(false, true, true, sgt(5, 48), zone, FailReason.NETWORK, sgt(19, 0), sgt(20, 40))
        assertFalse(recent.stuck)
        // The good read itself within 2 h: not stuck, with or without a tried time.
        assertFalse(Upcoming.syncLine(false, false, true, sgt(19, 0), zone, nowMs = sgt(20, 40)).stuck)
        // Exactly 2 h is not yet stuck; no clock given never says stuck.
        assertFalse(Upcoming.syncLine(false, false, true, sgt(18, 40), zone, nowMs = sgt(20, 40)).stuck)
        assertFalse(Upcoming.syncLine(false, false, true, sgt(5, 48), zone).stuck)
        // While a refresh runs, only "Updating…".
        assertEquals("5 open · Updating…", header(Upcoming.syncLine(true, false, true, sgt(5, 48), zone, nowMs = sgt(20, 40))))
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
