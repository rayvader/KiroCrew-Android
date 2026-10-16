package io.github.rayvader.kirocrew.core

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** One note from the dashboard's notification feed (GET /api/notifications or the /api/stream events). */
data class Alert(
    val ts: String,
    val title: String,
    val body: String,
    val url: String?,
    val priority: String,
    val silenced: Boolean,
    val acked: Boolean,
) {
    /** Worth a phone notification: unread, not muted and not passive (the same rule as the bell badge). */
    val loud: Boolean get() = !acked && !silenced && priority != "passive"

    /** The dashboard page to open on tap, only when it is a path on the same server. */
    val path: String? get() = url?.takeIf { it.startsWith("/") && !it.startsWith("//") }

    val epochMillis: Long? get() = AlertFeed.instant(ts)?.toEpochMilli()
}

object AlertFeed {
    const val MAX_PER_SYNC = 5

    /** Reads `{"notifications": [...]}`. Null when the text is not that JSON. */
    fun parseFeed(json: String): List<Alert>? = try {
        val arr = JSONObject(json).optJSONArray("notifications")
        if (arr == null) {
            null
        } else {
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseNote) }
        }
    } catch (e: JSONException) {
        null
    }

    /** One note from an SSE `notification` event. Null for other broadcast frames. */
    fun parseNoteJson(json: String): Alert? = try {
        parseNote(JSONObject(json))
    } catch (e: JSONException) {
        null
    }

    fun parseNote(o: JSONObject): Alert? {
        // Internal broadcast frames (slots, chat rows, ...) carry a `_type`; real notes never do.
        if (o.has("_type")) return null
        val ts = o.text("ts")?.takeIf { it.isNotBlank() } ?: return null
        val title = o.text("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        return Alert(
            ts = ts,
            title = title,
            body = Markdown.plain(o.text("body").orEmpty()),
            url = o.text("url")?.takeIf { it.isNotBlank() },
            priority = o.text("priority") ?: "normal",
            silenced = o.optBoolean("silenced", false),
            acked = o.optBoolean("acked", false),
        )
    }

    /** What to post now, how many new notes were left out, and the marker to store. */
    data class Sync(val toPost: List<Alert>, val skipped: Int, val marker: String)

    /**
     * Picks the notes that are new since [marker] (the newest `ts` seen last time).
     * The first sync (no marker yet) only records where the feed is, so old
     * notes do not flood the phone.
     */
    fun sync(all: List<Alert>, marker: String?, max: Int = MAX_PER_SYNC): Sync {
        val newest = all.maxWithOrNull(TS_ORDER)?.ts
        if (marker == null) return Sync(emptyList(), 0, newest ?: "")
        val fresh = all.filter { it.loud && isAfter(it.ts, marker) }.sortedWith(TS_ORDER)
        val next = if (newest != null && isAfter(newest, marker)) newest else marker
        val toPost = fresh.takeLast(max)
        return Sync(toPost, fresh.size - toPost.size, next)
    }

    /** True when [ts] is later than [marker]. An empty marker means "nothing seen yet". */
    fun isAfter(ts: String, marker: String): Boolean = marker.isEmpty() || compareTs(ts, marker) > 0

    fun compareTs(a: String, b: String): Int {
        val ia = instant(a)
        val ib = instant(b)
        return if (ia != null && ib != null) ia.compareTo(ib) else a.compareTo(b)
    }

    fun instant(ts: String): Instant? = try {
        OffsetDateTime.parse(ts).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    val TS_ORDER: Comparator<Alert> = Comparator { x, y -> compareTs(x.ts, y.ts) }
}

/** Tags the dashboard puts on its own browser toasts. */
object LiveTags {
    /**
     * "Chat finished" and "waiting for you" toasts. They come from the chat
     * stream, not the notification feed, so only the page itself can raise them.
     */
    fun isSessionEvent(tag: String): Boolean =
        tag.startsWith("kirocrew-chat-done:") || tag.startsWith("kirocrew-approval:")
}

/** Turns note bodies (markdown) into plain text for a phone notification. */
object Markdown {
    private val IMAGE_OR_LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val BOLD = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val ITALIC_STAR = Regex("(?<![\\w*])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![\\w*])")
    private val ITALIC_UNDERSCORE = Regex("(?<![\\w_])_(?=\\S)(.+?)(?<=\\S)_(?![\\w_])")
    private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    private val CODE_FENCE = Regex("(?m)^\\s*```.*$\\n?")
    private val CODE = Regex("`([^`]*)`")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val QUOTE = Regex("(?m)^\\s{0,3}>\\s?")
    private val BULLET = Regex("(?m)^(\\s*)[-*+]\\s+")
    private val BLANK_LINES = Regex("\\n{3,}")

    fun plain(md: String): String {
        var s = md.replace("\r\n", "\n")
        s = CODE_FENCE.replace(s, "")
        s = CODE.replace(s, "$1")
        s = IMAGE_OR_LINK.replace(s, "$1")
        s = BOLD.replace(s, "$2")
        s = ITALIC_STAR.replace(s, "$1")
        s = ITALIC_UNDERSCORE.replace(s, "$1")
        s = STRIKE.replace(s, "$1")
        s = HEADING.replace(s, "")
        s = QUOTE.replace(s, "")
        s = BULLET.replace(s, "$1• ")
        s = BLANK_LINES.replace(s, "\n\n")
        return s.trim()
    }
}

/** Minimal `text/event-stream` reader: feed it lines, get (event, data) back at each blank line. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): Pair<String, String>? {
        if (line.isEmpty()) {
            if (!hasData) {
                event = null
                return null
            }
            val out = (event ?: "message") to data.toString()
            event = null
            data.setLength(0)
            hasData = false
            return out
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

/** A string field, or null when it is missing or JSON null (org.json would return "null"). */
internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key)
