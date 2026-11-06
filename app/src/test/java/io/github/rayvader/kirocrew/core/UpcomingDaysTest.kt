package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class UpcomingDaysTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val uk = Locale.UK

    /** Friday 6 Nov 2026, 09:00. */
    private val now = LocalDateTime.of(2026, 11, 6, 9, 0)

    private fun item(title: String, due: String?, kind: String = "booking"): UpcomingItem {
        val (at, hasTime) = if (due == null) null to false else Upcoming.parseDue(due, zone)
        return UpcomingItem(title, kind, at, hasTime)
    }

    private fun labels(rows: List<DayRow>) = rows.map {
        when (it) {
            is DayRow.Header -> "# ${it.label}"
            is DayRow.Card -> it.item.title
        }
    }

    @Test
    fun groupsByDayInOrderWithLateUnderTodayAndNoDateLast() {
        val items = listOf(
            item("Later dinner", "2026-11-08 19:30"),
            item("No date form", null, "deadline"),
            item("Next year trip", "2027-01-03", "flight"),
            item("Today timed", "2026-11-06 14:10"),
            item("Tomorrow bill", "2026-11-07", "bill"),
            item("Late parcel", "2026-11-04", "delivery"),
            item("Today all day", "2026-11-06"),
            item("Late earlier", "2026-11-02 10:00"),
        )
        assertEquals(
            listOf(
                "# Today · Fri 6 Nov",
                "Late earlier", "Late parcel", // late first, oldest first
                "Today all day", // all day before timed
                "Today timed",
                "# Tomorrow · Sat 7 Nov",
                "Tomorrow bill",
                "# Sun 8 Nov",
                "Later dinner",
                "# Sun 3 Jan 2027", // the year only when it is not this year
                "Next year trip",
                "# No date",
                "No date form",
            ),
            labels(UpcomingDays.rows(items, now, uk)),
        )
    }

    @Test
    fun onlyTodaysHeaderIsMarkedToday() {
        val rows = UpcomingDays.rows(
            listOf(item("A", "2026-11-06"), item("B", "2026-11-07"), item("C", null)),
            now,
            uk,
        )
        val headers = rows.filterIsInstance<DayRow.Header>()
        assertEquals(listOf(true, false, false), headers.map { it.today })
    }

    @Test
    fun lateWithNothingDueTodayStillMakesATodayHeader() {
        val rows = UpcomingDays.rows(listOf(item("Late", "2026-11-04")), now, uk)
        assertEquals(listOf("# Today · Fri 6 Nov", "Late"), labels(rows))
    }

    @Test
    fun allDayComesBeforeTimedOnTheSameDay() {
        val rows = UpcomingDays.rows(
            listOf(item("At 08:00", "2026-11-09 08:00"), item("All day", "2026-11-09"), item("At 07:00", "2026-11-09 07:00")),
            now,
            uk,
        )
        assertEquals(listOf("# Mon 9 Nov", "All day", "At 07:00", "At 08:00"), labels(rows))
    }

    @Test
    fun timeLines() {
        val cards = UpcomingDays.rows(
            listOf(
                item("Timed", "2026-11-06 14:10"),
                item("All day", "2026-11-07"),
                item("Late day", "2026-11-04"),
                item("Late timed", "2026-11-06 08:30"),
                item("Late last year", "2025-12-30"),
                item("Undated", null),
            ),
            now,
            uk,
        ).filterIsInstance<DayRow.Card>().associate { it.item.title to it.timeLine }
        assertEquals("14:10", cards["Timed"])
        assertEquals("all day", cards["All day"])
        assertEquals("late · was Wed 4 Nov", cards["Late day"])
        assertEquals("late · was Fri 6 Nov 08:30", cards["Late timed"])
        assertEquals("late · was Tue 30 Dec 2025", cards["Late last year"])
        assertEquals("", cards["Undated"])
    }

    @Test
    fun aDateOnlyItemDueTodayIsNotLate() {
        val card = UpcomingDays.rows(listOf(item("Today", "2026-11-06")), now, uk)[1] as DayRow.Card
        assertFalse(card.late)
        assertEquals(Tone.EVENT, card.tone)
    }

    @Test
    fun colorsByType() {
        val soon = "2026-11-10"
        assertEquals(Tone.EVENT, UpcomingDays.tone(item("x", soon, "booking"), now))
        assertEquals(Tone.EVENT, UpcomingDays.tone(item("x", soon, "dinner"), now))
        assertEquals(Tone.EVENT, UpcomingDays.tone(item("x", soon, "event"), now))
        assertEquals(Tone.FLIGHT, UpcomingDays.tone(item("x", soon, "flight"), now))
        assertEquals(Tone.DEADLINE, UpcomingDays.tone(item("x", soon, "deadline"), now))
        assertEquals(Tone.BILL, UpcomingDays.tone(item("x", soon, "bill"), now))
        assertEquals(Tone.BILL, UpcomingDays.tone(item("x", soon, "payment"), now))
        assertEquals(Tone.OTHER, UpcomingDays.tone(item("x", soon, "delivery"), now))
        assertEquals(Tone.OTHER, UpcomingDays.tone(item("x", soon, "something-new"), now))
        // No date: teal, whatever the kind.
        assertEquals(Tone.OTHER, UpcomingDays.tone(item("x", null, "flight"), now))
        // Late wins over the kind.
        assertEquals(Tone.LATE, UpcomingDays.tone(item("x", "2026-11-01", "flight"), now))
    }

    @Test
    fun theSpecColorsPassOrAreDarkenedToPass() {
        assertEquals(0xFFD93025.toInt(), Tone.LATE.light)
        assertEquals(0xFF1A73E8.toInt(), Tone.EVENT.light)
        assertEquals(0xFF8E24AA.toInt(), Tone.FLIGHT.light)
        assertEquals(0xFF188038.toInt(), Tone.BILL.light)
        // These two fail with white text as given, so they are darker.
        assertTrue(UpcomingDays.contrast(0xFFE37400.toInt(), UpcomingDays.WHITE) < 4.5)
        assertTrue(UpcomingDays.contrast(0xFF00897B.toInt(), UpcomingDays.WHITE) < 4.5)
    }

    @Test
    fun everyCardColorHasWhiteTextAt45OrBetterInBothThemes() {
        for (tone in Tone.values()) {
            val light = UpcomingDays.contrast(tone.light, UpcomingDays.WHITE)
            val dark = UpcomingDays.contrast(tone.dark, UpcomingDays.WHITE)
            assertTrue("$tone light $light", light >= 4.5)
            assertTrue("$tone dark $dark", dark >= 4.5)
        }
    }

    @Test
    fun contrastMath() {
        assertEquals(21.0, UpcomingDays.contrast(0xFF000000.toInt(), UpcomingDays.WHITE), 0.01)
        assertEquals(1.0, UpcomingDays.contrast(UpcomingDays.WHITE, UpcomingDays.WHITE), 0.001)
    }

    /** The widget's color resources must hold the same colors this test checks. */
    @Test
    fun theColorResourcesMatchTheTones() {
        fun colors(path: String): Map<String, Int> {
            val f = listOf(File(path), File("app/$path")).first { it.exists() }
            return Regex("""<color name="widget_tone_(\w+)">#([0-9A-Fa-f]{6})</color>""")
                .findAll(f.readText())
                .associate { it.groupValues[1] to (0xFF000000.toInt() or it.groupValues[2].toInt(16)) }
        }
        val light = colors("src/main/res/values/widget_tones.xml")
        val dark = colors("src/main/res/values-night/widget_tones.xml")
        for (tone in Tone.values()) {
            val key = tone.name.lowercase(Locale.ROOT)
            assertEquals("light $key", tone.light, light[key])
            assertEquals("dark $key", tone.dark, dark[key])
        }
    }

    @Test
    fun aNarrowWidgetPutsTheCountUnderTheTitle() {
        // Phone 4x2 (~348 dp) at 115 % text (348/1.15 = 302 >= 290): one line.
        assertFalse(UpcomingDays.stackHeader(348f, 1.15f))
        assertFalse(UpcomingDays.stackHeader(362f, 1f))
        // Unfolded, the launcher gives the widget few dp, or the app's folded 110 % piles on: stacked.
        assertTrue(UpcomingDays.stackHeader(362f, 1.1f * 1.15f)) // 362/1.265 = 286 < 290
        assertTrue(UpcomingDays.stackHeader(180f, 1.15f))
        assertTrue(UpcomingDays.stackHeader(250f, 1f))
        // Bigger text needs more width for one line.
        assertTrue(UpcomingDays.stackHeader(320f, 1.3f))
        assertFalse(UpcomingDays.stackHeader(0f, 1f))
    }

    @Test
    fun aLongTitleIsKeptWhole() {
        val long = "E2E made-up title that is sixty characters long, to wrap ok."
        assertEquals(60, long.length)
        val card = UpcomingDays.rows(listOf(item(long, "2026-11-07")), now, uk)[1] as DayRow.Card
        assertEquals(long, card.item.title)
    }
}
