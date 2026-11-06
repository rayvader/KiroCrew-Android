package io.github.rayvader.kirocrew.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The card color of a Froyo item. [light] and [dark] are the card fills for the light and the
 * dark theme (ARGB); both carry white text at 4.5:1 or better. The widget's color resources
 * (`widget_tone_*` in values/ and values-night/) hold the same values.
 */
enum class Tone(val light: Int, val dark: Int) {
    LATE(0xFFD93025.toInt(), 0xFFC03027.toInt()),
    EVENT(0xFF1A73E8.toInt(), 0xFF1D69CC.toInt()),
    FLIGHT(0xFF8E24AA.toInt(), 0xFF802698.toInt()),
    // Google's amber #E37400 is only 3.1:1 with white text: darkened to pass.
    DEADLINE(0xFFB65D00.toInt(), 0xFFA25607.toInt()),
    BILL(0xFF188038.toInt(), 0xFF1C7437.toInt()),
    // Teal #00897B is 4.3:1 with white text: darkened a little to pass.
    OTHER(0xFF008476.toInt(), 0xFF07776C.toInt()),
}

/** One line of the widget's list: a day header, or an item's card under it. */
sealed interface DayRow {
    /** "Today · Fri 6 Nov", "Tomorrow · Sat 7 Nov", "Sun 8 Nov", or "No date". */
    data class Header(val label: String, val today: Boolean) : DayRow

    /** [timeLine] is "14:10", "all day", the late line, or "" for an item with no date. */
    data class Card(val item: UpcomingItem, val tone: Tone, val timeLine: String, val late: Boolean) : DayRow
}

/**
 * The widget's list in the Google Calendar style: a header per day, then that day's items.
 * Late items go under Today. Items with no date go under "No date", last. Plain Kotlin.
 */
object UpcomingDays {
    const val TODAY = "Today"
    const val TOMORROW = "Tomorrow"
    const val NO_DATE = "No date"
    const val ALL_DAY = "all day"

    /** The card color: late wins; else by kind; an item with no date is teal. */
    fun tone(item: UpcomingItem, now: LocalDateTime): Tone {
        if (Upcoming.state(item, now) == DueState.LATE) return Tone.LATE
        if (item.due == null) return Tone.OTHER
        return when (item.kind) {
            "booking", "dinner", "event", "meeting", "reservation" -> Tone.EVENT
            "flight" -> Tone.FLIGHT
            "deadline" -> Tone.DEADLINE
            "bill", "payment" -> Tone.BILL
            else -> Tone.OTHER
        }
    }

    /** "Fri 6 Nov", with the year only when it is not [now]'s year. */
    fun dayText(day: LocalDate, now: LocalDateTime, locale: Locale): String {
        val pattern = if (day.year == now.year) "EEE d MMM" else "EEE d MMM yyyy"
        return day.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    fun headerLabel(day: LocalDate, now: LocalDateTime, locale: Locale): String {
        val text = dayText(day, now, locale)
        return when (day) {
            now.toLocalDate() -> "$TODAY · $text"
            now.toLocalDate().plusDays(1) -> "$TOMORROW · $text"
            else -> text
        }
    }

    /** "late · was Wed 4 Nov", plus the time when the item had one. */
    fun lateLine(item: UpcomingItem, now: LocalDateTime, locale: Locale): String {
        val due = item.due ?: return ""
        val day = dayText(due.toLocalDate(), now, locale)
        val time = if (item.hasTime) " " + due.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)) else ""
        return "late · was $day$time"
    }

    fun timeLine(item: UpcomingItem, now: LocalDateTime, locale: Locale): String {
        val due = item.due ?: return ""
        if (Upcoming.state(item, now) == DueState.LATE) return lateLine(item, now, locale)
        return if (item.hasTime) due.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)) else ALL_DAY
    }

    /**
     * Headers and cards, in order: Today (late items first, oldest first; then all-day; then by
     * time), then each later day (all-day before timed), then "No date". Within a slot, by title.
     */
    fun rows(items: List<UpcomingItem>, now: LocalDateTime, locale: Locale): List<DayRow> {
        val today = now.toLocalDate()
        val dated = items.filter { it.due != null }
        val undated = items.filter { it.due == null }
        // The day each item is shown under: late ones move to today.
        fun shownDay(i: UpcomingItem): LocalDate {
            val late = Upcoming.state(i, now) == DueState.LATE
            return if (late) today else i.due!!.toLocalDate()
        }
        val byDay = dated.groupBy(::shownDay).toSortedMap()
        val out = ArrayList<DayRow>()
        for ((day, list) in byDay) {
            out.add(DayRow.Header(headerLabel(day, now, locale), today = day == today))
            val sorted = list.sortedWith(
                compareBy<UpcomingItem>(
                    { if (Upcoming.state(it, now) == DueState.LATE) 0 else if (!it.hasTime) 1 else 2 },
                    { it.due },
                    { it.title.lowercase(Locale.ROOT) },
                ),
            )
            sorted.forEach { out.add(card(it, now, locale)) }
        }
        if (undated.isNotEmpty()) {
            out.add(DayRow.Header(NO_DATE, today = false))
            undated.sortedBy { it.title.lowercase(Locale.ROOT) }.forEach { out.add(card(it, now, locale)) }
        }
        return out
    }

    private fun card(item: UpcomingItem, now: LocalDateTime, locale: Locale): DayRow.Card =
        DayRow.Card(
            item = item,
            tone = tone(item, now),
            timeLine = timeLine(item, now, locale),
            late = Upcoming.state(item, now) == DueState.LATE,
        )

    /** WCAG contrast ratio of two opaque ARGB colors, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    const val WHITE = 0xFFFFFFFF.toInt()
}
