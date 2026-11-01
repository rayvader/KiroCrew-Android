package io.github.rayvader.kirocrew.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One open item of the crewmate Froyo, as its dashboard lists it. */
data class UpcomingItem(
    val title: String,
    val kind: String,
    /** Local date and time it is due, or null when it has no date. */
    val due: LocalDateTime?,
    /** False when the due value was a date only. */
    val hasTime: Boolean,
)

/** How soon an item is due. */
enum class DueState { NONE, LATER, SOON, LATE }

/**
 * How the widget lays out at one size. Every item is in the scrolling list; [rows] is how
 * many whole rows the list shows at rest, and [listHeightDp] is its height, snapped to whole
 * rows so none is cut at rest. [header] is false when only one row fits with no room for the
 * header (4x1); the count then goes on the first row so it is not lost.
 */
data class WidgetFit(val rows: Int, val header: Boolean, val listHeightDp: Float)

/** Froyo's pinned chat: the slot to send to and the member name the dashboard read needs. */
data class FroyoThread(val slotKey: String, val member: String)

/**
 * The "Froyo · Upcoming" widget's logic: reading Froyo's open items out of its dashboard,
 * sorting them, the due text, and how many rows fit. Plain Kotlin, no Android calls.
 */
object Upcoming {
    /** Froyo's member slug on the gateway. */
    const val SLUG = "froyo-personal-butler"
    const val THREAD_PATH = "/api/members/$SLUG/thread"
    const val CHAT_PATH = "/api/chat"

    /** The dashboard field Froyo writes its open items to. */
    const val FIELD = "open_items"
    private const val MAX_TITLE = 200
    private const val DATA_ID = "kirocrew-dashboard-data"

    fun dashboardPath(member: String): String =
        "/api/members/$SLUG/dashboard?member=" + URLEncoder.encode(member, "UTF-8")

    /** Where a tap on a row opens the app: Froyo's chat when its slot is known. */
    fun chatPagePath(slotKey: String?): String =
        if (slotKey.isNullOrBlank()) "/chat" else "/chat?sid=" + URLEncoder.encode(slotKey, "UTF-8")

    /** The chat text that marks an item done. Froyo's own ack rules read "done". */
    fun doneMessage(title: String): String = "Done: ${oneLine(title)}"

    fun undoMessage(title: String): String = "Undo done: ${oneLine(title)}"

    /** The body of POST /api/chat, the dashboard's own send. */
    fun chatBody(slotKey: String, message: String): String =
        JSONObject().put("message", message).put("slot", slotKey).toString()

    /** POST /api/members/{slug}/thread answers {"slot_key", "slug", "member"}. */
    fun parseThread(body: String): FroyoThread? = try {
        val o = JSONObject(body)
        val slot = o.optString("slot_key").trim()
        val member = o.optString("member").trim()
        if (slot.isEmpty() || member.isEmpty()) null else FroyoThread(slot, member)
    } catch (e: JSONException) {
        null
    }

    /** True when POST /api/chat took the message: a stream, or a JSON receipt that is not a refusal. */
    fun chatAccepted(code: Int, contentType: String?, body: String): Boolean {
        if (code != 200) return false
        if (contentType?.startsWith("text/event-stream") == true) return true
        return try {
            val o = JSONObject(body)
            o.optBoolean("ok", true) && !o.has("error")
        } catch (e: JSONException) {
            false
        }
    }

    /**
     * The open items array out of GET /api/members/{slug}/dashboard. The page carries its
     * values in a JSON data island; this returns that island's [FIELD] as JSON text, or
     * null when the answer has no such field (no page yet, or Froyo never wrote it).
     */
    fun extractItems(body: String): String? {
        return try {
            val html = JSONObject(body).optString("rendered_html")
            val island = island(html) ?: return null
            val fields = JSONObject(island).optJSONObject("fields") ?: return null
            fields.optJSONArray(FIELD)?.toString()
        } catch (e: JSONException) {
            null
        }
    }

    private fun island(html: String): String? {
        val tag = html.indexOf("id=\"$DATA_ID\"")
        if (tag < 0) return null
        val start = html.indexOf('>', tag)
        if (start < 0) return null
        val end = html.indexOf("</script>", start)
        if (end < 0) return null
        return html.substring(start + 1, end)
    }

    /** Open items from the field's JSON text, soonest first, undated last. Acked ones are dropped. */
    fun parseItems(json: String, zone: ZoneId): List<UpcomingItem> {
        val arr = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = ArrayList<UpcomingItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (isAcked(o)) continue
            val title = oneLine(o.optString("title")).take(MAX_TITLE)
            if (title.isEmpty()) continue
            val kind = o.optString("kind").trim().lowercase(Locale.ROOT)
            val raw = if (o.isNull("due")) "" else o.optString("due")
            val (due, hasTime) = parseDue(raw, zone)
            out.add(UpcomingItem(title, kind, due, hasTime))
        }
        return sort(out)
    }

    private fun isAcked(o: JSONObject): Boolean {
        if (o.optBoolean("acked", false)) return true
        val at = if (o.isNull("acked_at")) "" else o.optString("acked_at")
        return at.isNotBlank()
    }

    fun sort(items: List<UpcomingItem>): List<UpcomingItem> =
        items.sortedWith(compareBy<UpcomingItem>({ it.due == null }, { it.due }, { it.title.lowercase(Locale.ROOT) }))

    /** "2026-11-01" (date only), "2026-11-01 14:10", "2026-11-01T14:10[:ss]" or with an offset. */
    fun parseDue(raw: String, zone: ZoneId): Pair<LocalDateTime?, Boolean> {
        val s = raw.trim()
        if (s.isEmpty()) return null to false
        if (s.length == 10) {
            return try {
                LocalDate.parse(s).atStartOfDay() to false
            } catch (e: DateTimeParseException) {
                null to false
            }
        }
        val t = s.replace(' ', 'T')
        try {
            return OffsetDateTime.parse(t).atZoneSameInstant(zone).toLocalDateTime() to true
        } catch (e: DateTimeParseException) {
            // No offset: local time.
        }
        if (t.endsWith("Z")) return null to false
        return try {
            LocalDateTime.parse(t) to true
        } catch (e: DateTimeParseException) {
            null to false
        }
    }

    /** Late once it is past; soon within 24 h. A date with no time counts until that day ends. */
    fun state(item: UpcomingItem, now: LocalDateTime): DueState {
        val due = item.due ?: return DueState.NONE
        val deadline = if (item.hasTime) due else due.toLocalDate().plusDays(1).atStartOfDay()
        return when {
            !deadline.isAfter(now) -> DueState.LATE
            ChronoUnit.MINUTES.between(now, deadline) <= 24 * 60 -> DueState.SOON
            else -> DueState.LATER
        }
    }

    /**
     * "Sat 1 14:10" (24 h), or "Sat 1" when there is no time. More than 6 days away the
     * month is added ("Sat 28 Nov"), so a far date cannot pass for one this week. Late
     * items say so first ("late Fri 30"). Empty when it has no date.
     */
    fun dueText(item: UpcomingItem, now: LocalDateTime, locale: Locale): String {
        val due = item.due ?: return ""
        val far = ChronoUnit.DAYS.between(now.toLocalDate(), due.toLocalDate()) > 6
        val pattern = buildString {
            append("EEE d")
            if (far) append(" MMM")
            if (item.hasTime) append(" HH:mm")
        }
        val text = due.format(DateTimeFormatter.ofPattern(pattern, locale))
        return if (state(item, now) == DueState.LATE) "late $text" else text
    }

    fun icon(kind: String): String = when (kind) {
        "booking" -> "📅"
        "flight" -> "✈"
        "delivery" -> "📦"
        "deadline" -> "⏰"
        "bill" -> "💳"
        "security" -> "🔐"
        else -> "•"
    }

    // Fixed sizes from widget_froyo.xml and widget_froyo_item.xml. The header and each row
    // have a fixed height in sp, so they grow with the font scale exactly; the padding is dp.
    // Change both together.
    private const val PAD_DP = 12f
    const val HEADER_SP = 22f
    const val ROW_SP = 28f

    fun headerHeight(fontScale: Float): Float = HEADER_SP * fontScale

    fun rowHeight(fontScale: Float): Float = ROW_SP * fontScale

    /**
     * The layout at [heightDp] and [fontScale]: the header, then a list of as many whole rows
     * as fit. The list scrolls through every item, so none is left out. Too short for the
     * header and a row: one row, no header.
     */
    fun fit(heightDp: Float, fontScale: Float): WidgetFit {
        val scale = if (fontScale > 0f) fontScale else 1f
        val withHeader = ((heightDp - PAD_DP - headerHeight(scale)) / rowHeight(scale)).toInt()
        if (withHeader >= 1) return WidgetFit(withHeader, header = true, listHeightDp = withHeader * rowHeight(scale))
        return WidgetFit(1, header = false, listHeightDp = rowHeight(scale))
    }

    /** Height the header and the list take, for checks that nothing sticks out. */
    fun used(fit: WidgetFit, fontScale: Float): Float =
        PAD_DP + (if (fit.header) headerHeight(fontScale) else 0f) + fit.listHeightDp

    /**
     * Items marked done on the phone stay hidden until Froyo's list drops them, or for
     * [HIDE_MS] if it never does (then the row comes back, which shows the done did not land).
     * [hidden] maps title to when it was marked. Returns the items to show and the hidden map to keep.
     */
    fun applyHidden(items: List<UpcomingItem>, hidden: Map<String, Long>, nowMs: Long): Pair<List<UpcomingItem>, Map<String, Long>> {
        val titles = items.map { it.title }.toSet()
        val keep = hidden.filter { (title, at) -> title in titles && nowMs - at < HIDE_MS }
        return items.filter { it.title !in keep } to keep
    }

    const val HIDE_MS = 2L * 60 * 60 * 1000

    fun encodeHidden(hidden: Map<String, Long>): String {
        val o = JSONObject()
        hidden.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun decodeHidden(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optLong(it) }
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
