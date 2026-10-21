package io.github.rayvader.kirocrew

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import io.github.rayvader.kirocrew.core.Alert
import io.github.rayvader.kirocrew.core.AlertFeed
import io.github.rayvader.kirocrew.core.GatewayUpdate
import io.github.rayvader.kirocrew.core.GatewayUpdateParser
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.SseParser
import io.github.rayvader.kirocrew.core.UrlTools
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to a dashboard outside the WebView, with the WebView's own login
 * cookies, so alerts arrive while the app is closed.
 */
class AlertsClient(private val ctx: Context) {

    sealed interface Feed {
        data class Ok(val alerts: List<Alert>) : Feed
        data object SignedOut : Feed
        data class Failed(val reason: String) : Feed
    }

    @Volatile private var streamConn: HttpURLConnection? = null

    private val userAgent: String by lazy {
        val base = try {
            WebSettings.getDefaultUserAgent(ctx)
        } catch (e: RuntimeException) {
            "Mozilla/5.0 (Linux; Android)"
        }
        "$base KiroCrewAndroid/${BuildConfig.VERSION_NAME}"
    }

    /** Reads the notification feed. Renews an expired login once, like the dashboard page does. */
    fun fetch(profile: Profile): Feed = try {
        var res = request(profile)
        if (res.authRequired && refresh(profile)) res = request(profile)
        when {
            res.authRequired -> Feed.SignedOut
            res.code != 200 -> Feed.Failed("HTTP ${res.code}")
            else -> AlertFeed.parseFeed(res.body)?.let { Feed.Ok(it) } ?: Feed.Failed("not a notification feed")
        }
    } catch (e: IOException) {
        Feed.Failed(e.javaClass.simpleName)
    } catch (e: RuntimeException) {
        // CookieManager throws while the WebView package is being updated.
        Feed.Failed(e.javaClass.simpleName)
    }

    /**
     * Holds the dashboard's live event stream open and calls [onNote] for each
     * notification. Returns false when the login is needed, true when the stream
     * ended. Throws IOException on network errors. [cancel] stops it from another thread.
     */
    fun stream(profile: Profile, keepGoing: () -> Boolean, onConnected: () -> Unit, onNote: (Alert) -> Unit): Boolean {
        val conn = open(profile, "GET", STREAM_PATH)
        conn.readTimeout = STREAM_READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        streamConn = conn
        try {
            val code = conn.responseCode
            if (isAuthRequired(conn, code)) return false
            if (code != 200) throw IOException("HTTP $code")
            onConnected()
            val parser = SseParser()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (keepGoing()) {
                    val line = reader.readLine() ?: break
                    val event = parser.feed(line) ?: continue
                    if (event.first == "notification") AlertFeed.parseNoteJson(event.second)?.let(onNote)
                }
            }
            return true
        } finally {
            streamConn = null
            conn.disconnect()
        }
    }

    fun cancel() {
        streamConn?.disconnect()
    }

    private class Response(val code: Int, val body: String, val authRequired: Boolean)

    /**
     * Asks the gateway whether Kiro Crew itself has an update. Null when there is
     * none, when the gateway could not tell, or when it cannot be reached.
     */
    fun gatewayUpdate(profile: Profile): GatewayUpdate? = try {
        var res = request(profile, GatewayUpdateParser.PATH)
        if (res.authRequired && refresh(profile)) res = request(profile, GatewayUpdateParser.PATH)
        if (res.code == 200) GatewayUpdateParser.parse(res.body) else null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun request(profile: Profile, path: String = FEED_PATH): Response {
        val conn = open(profile, "GET", path)
        try {
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it) }.orEmpty()
            return Response(code, body, isAuthRequired(conn, code))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST /api/auth/refresh with the refresh cookie, then store the new
     * cookies where the WebView reads them too, so both stay signed in.
     */
    private fun refresh(profile: Profile): Boolean {
        val url = UrlTools.buildUrl(profile.baseUrl, REFRESH_PATH)
        val conn = open(profile, "POST", REFRESH_PATH)
        try {
            UrlTools.origin(profile.baseUrl)?.let { conn.setRequestProperty("Origin", it) }
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
            if (conn.responseCode != 200) return false
            val cookies = CookieManager.getInstance()
            conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .forEach { cookies.setCookie(url, it) }
            cookies.flush()
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun open(profile: Profile, method: String, path: String): HttpURLConnection {
        val url = UrlTools.buildUrl(profile.baseUrl, path)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    /** The dashboard answers 401, or 403 with X-Auth-Required, when the login cookie is missing or old. */
    private fun isAuthRequired(conn: HttpURLConnection, code: Int): Boolean =
        code == 401 || (code == 403 && conn.getHeaderField("X-Auth-Required").equals("true", ignoreCase = true))

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY_BYTES) throw IOException("response too large")
        }
        return out.toString("UTF-8")
    }

    companion object {
        const val FEED_PATH = "/api/notifications"
        const val STREAM_PATH = "/api/stream"
        const val REFRESH_PATH = "/api/auth/refresh"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        /** The dashboard sends a status event every 5 s, so a minute of silence means the link is dead. */
        private const val STREAM_READ_TIMEOUT_MS = 60_000
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}
