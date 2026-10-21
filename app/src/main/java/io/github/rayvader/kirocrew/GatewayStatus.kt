package io.github.rayvader.kirocrew

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.rayvader.kirocrew.core.Connection
import io.github.rayvader.kirocrew.core.Profile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Remembers whether each saved gateway can be reached, for the status dots on
 * the Servers icon, the edge tab and the Servers list. Only the app's screens
 * ask: nothing runs in the background.
 */
object GatewayStatus {
    private const val TIMEOUT_MS = 5_000

    private val states = ConcurrentHashMap<String, Connection>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val probing = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())

    fun state(profileId: String?): Connection = profileId?.let { states[it] } ?: Connection.UNKNOWN

    fun set(profileId: String, state: Connection) {
        if (states.put(profileId, state) == state) return
        main.post { listeners.forEach { it() } }
    }

    /** Called on the main thread whenever a dot changes colour. */
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Asks [profile]'s gateway in the background. One probe per server at a time. */
    fun probe(profile: Profile) {
        if (!probing.add(profile.id)) return
        thread(name = "kirocrew-health", isDaemon = true) {
            try {
                set(profile.id, Connection.fromHttpCode(httpCode(Connection.healthUrl(profile.baseUrl))))
            } finally {
                probing.remove(profile.id)
            }
        }
    }

    private fun httpCode(url: String): Int? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "KiroCrewAndroid/${BuildConfig.VERSION_NAME}")
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    fun colorRes(state: Connection): Int = when (state) {
        Connection.UP -> R.color.status_up
        Connection.DOWN -> R.color.status_down
        Connection.UNKNOWN -> R.color.status_unknown
    }

    fun label(ctx: Context, state: Connection): String = ctx.getString(
        when (state) {
            Connection.UP -> R.string.status_up
            Connection.DOWN -> R.string.status_down
            Connection.UNKNOWN -> R.string.status_unknown
        }
    )

    /** A round dot with a thin ring in [ringColor], so it stands out on any background. */
    fun dot(ctx: Context, state: Connection, ringColor: Int, sizePx: Int): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(ctx, colorRes(state)))
            setStroke((sizePx / 6).coerceAtLeast(1), ringColor)
            setSize(sizePx, sizePx)
        }
}
