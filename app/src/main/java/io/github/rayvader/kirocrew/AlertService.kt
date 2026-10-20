package io.github.rayvader.kirocrew

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import io.github.rayvader.kirocrew.core.Profile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * "Instant" background alerts: keeps each server's live event stream open, the
 * same one the dashboard page listens to, so a note shows up within seconds.
 * Android requires a small ongoing notification while it runs.
 */
class AlertService : Service() {

    private val streams = ConcurrentHashMap<String, ProfileStream>()
    @Volatile private var running = true
    private var statusText: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        goForeground(getString(R.string.instant_connecting))
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Reconnect right away when the network (or Tailscale) comes back.
            override fun onAvailable(network: Network) = streams.values.forEach { it.wake() }
        }
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // Without it the streams still retry on their own timer.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must be answered with startForeground, even one that is about to stop.
        goForeground(statusText ?: getString(R.string.instant_connecting))
        if (!AlertScheduler.wantsService(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val profiles = Prefs(this).profiles
        val wanted = profiles.associateBy { it.id + "|" + it.baseUrl }
        streams.keys.filter { it !in wanted }.forEach { streams.remove(it)?.stop() }
        wanted.forEach { (key, profile) ->
            streams.getOrPut(key) { ProfileStream(profile, profiles.size > 1).also { it.start() } }
        }
        refreshStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        // Under the same lock as refreshStatus, so no stream thread can post the notice after this.
        synchronized(this) { running = false }
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (e: RuntimeException) {
                // Already gone.
            }
        }
        streams.values.forEach { it.stop() }
        streams.clear()
        // A status update can re-post the notice as a plain notification; remove it too.
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(Notifier.ID_SERVICE)
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val n = Notifier.serviceNotification(this, text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_SERVICE, n)
            }
        } catch (e: RuntimeException) {
            // Not allowed right now (started from the background). The periodic check covers it.
            stopSelf()
        }
    }

    @Synchronized
    private fun refreshStatus() {
        // Stream threads report their last state while the service stops. Posting
        // then would leave an "Instant alerts are on" notice with no service behind it.
        if (!running) return
        val all = streams.values.toList()
        val text = when {
            all.isEmpty() -> getString(R.string.instant_connecting)
            all.all { it.connected } -> if (all.size == 1) {
                getString(R.string.instant_connected_one, all[0].profile.name)
            } else {
                getString(R.string.instant_connected_many, all.size)
            }
            all.any { it.signedOut } -> getString(R.string.instant_signed_out)
            else -> getString(R.string.instant_reconnecting)
        }
        if (text == statusText) return
        statusText = text
        Notifier.updateService(this, text)
    }

    private inner class ProfileStream(val profile: Profile, private val showServer: Boolean) {
        @Volatile var connected = false
        @Volatile var signedOut = false
        @Volatile private var alive = true
        @Volatile private var sleeping = false
        private val client = AlertsClient(this@AlertService)
        private var worker: Thread? = null

        fun start() {
            worker = thread(name = "kirocrew-alerts", isDaemon = true) { loop() }
        }

        fun stop() {
            alive = false
            client.cancel()
            worker?.interrupt()
        }

        /** Cut a retry wait short (the network just came back). */
        fun wake() {
            if (sleeping) worker?.interrupt()
        }

        private fun keepGoing() = alive && running

        private fun loop() {
            var backoff = MIN_BACKOFF_MS
            while (keepGoing()) {
                // Catch up on anything missed while disconnected, and renew the login if needed.
                when (AlertSync.syncProfile(this@AlertService, client, profile, showServer)) {
                    is AlertsClient.Feed.Ok -> signedOut = false
                    AlertsClient.Feed.SignedOut -> {
                        signedOut = true
                        markConnected(false)
                        pause(SIGNED_OUT_WAIT_MS)
                        continue
                    }
                    is AlertsClient.Feed.Failed -> {
                        markConnected(false)
                        pause(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                        continue
                    }
                }
                val openedAt = SystemClock.elapsedRealtime()
                try {
                    client.stream(profile, ::keepGoing, onConnected = { markConnected(true) }) { note ->
                        AlertSync.onLiveNote(this@AlertService, profile, showServer, note)
                    }
                } catch (e: IOException) {
                    // Dropped: reconnect below.
                } catch (e: RuntimeException) {
                    // Same.
                }
                markConnected(false)
                if (SystemClock.elapsedRealtime() - openedAt > STABLE_MS) backoff = MIN_BACKOFF_MS
                pause(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        private fun markConnected(value: Boolean) {
            if (connected == value) return
            connected = value
            if (value) signedOut = false
            refreshStatus()
        }

        private fun pause(ms: Long) {
            if (!keepGoing()) return
            sleeping = true
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                // Woken early.
            } finally {
                sleeping = false
            }
        }
    }

    companion object {
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val STABLE_MS = 60_000L
        private const val SIGNED_OUT_WAIT_MS = 15 * 60_000L
    }
}
