package io.github.rayvader.kirocrew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.rayvader.kirocrew.core.Alert
import io.github.rayvader.kirocrew.core.AlertFeed
import io.github.rayvader.kirocrew.core.Profile
import java.util.concurrent.TimeUnit

/** Checks the feed, posts what is new and remembers where it got to. Used by the worker and the service. */
object AlertSync {
    private val lock = Any()

    fun syncAll(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.notifyEnabled) return
        val profiles = prefs.profiles
        val client = AlertsClient(ctx)
        profiles.forEach { syncProfile(ctx, client, it, profiles.size > 1) }
    }

    fun syncProfile(ctx: Context, client: AlertsClient, profile: Profile, showServer: Boolean): AlertsClient.Feed {
        val result = client.fetch(profile)
        val prefs = Prefs(ctx)
        when (result) {
            is AlertsClient.Feed.Ok -> synchronized(lock) {
                val sync = AlertFeed.sync(result.alerts, prefs.alertMarker(profile.id))
                prefs.setAlertMarker(profile.id, sync.marker)
                if (prefs.signInNeeded(profile.id)) {
                    prefs.setSignInNeeded(profile.id, false)
                    Notifier.cancelSignIn(ctx, profile)
                }
                if (!isOnScreen(ctx, profile)) {
                    sync.toPost.forEach { Notifier.postAlert(ctx, profile, showServer, it) }
                    if (sync.skipped > 0) Notifier.postMore(ctx, profile, sync.skipped)
                }
            }
            AlertsClient.Feed.SignedOut -> if (!prefs.signInNeeded(profile.id)) {
                prefs.setSignInNeeded(profile.id, true)
                Notifier.postSignIn(ctx, profile)
            }
            is AlertsClient.Feed.Failed -> Unit // Try again next time.
        }
        return result
    }

    /** A note pushed live by the event stream. */
    fun onLiveNote(ctx: Context, profile: Profile, showServer: Boolean, alert: Alert) {
        synchronized(lock) {
            val prefs = Prefs(ctx)
            val marker = prefs.alertMarker(profile.id) ?: ""
            if (!AlertFeed.isAfter(alert.ts, marker)) return
            prefs.setAlertMarker(profile.id, alert.ts)
            if (alert.loud && !isOnScreen(ctx, profile)) Notifier.postAlert(ctx, profile, showServer, alert)
        }
    }

    private fun isOnScreen(ctx: Context, profile: Profile): Boolean =
        (ctx.applicationContext as? KiroCrewApp)?.visibleProfileId == profile.id
}

/** Turns the "Background alerts" setting into a periodic check or the instant-alerts service. */
object AlertScheduler {
    const val MODE_OFF = "off"
    const val MODE_INSTANT = "instant"
    private const val WORK = "alerts-check"

    fun mode(prefs: Prefs): String = if (!prefs.notifyEnabled) MODE_OFF else prefs.alertMode

    fun wantsService(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        return mode(prefs) == MODE_INSTANT && prefs.profiles.isNotEmpty() && Notifier.canPost(ctx)
    }

    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val mode = mode(prefs)
        val wm = WorkManager.getInstance(app)
        // Instant mode keeps a 15-minute check too, as a safety net if Android stops the service.
        val minutes = when (mode) {
            MODE_OFF -> 0L
            MODE_INSTANT -> 15L
            else -> (mode.toLongOrNull() ?: 15L).coerceAtLeast(15L)
        }
        if (minutes == 0L || prefs.profiles.isEmpty() || !Notifier.canPost(app)) {
            wm.cancelUniqueWork(WORK)
        } else {
            val request = PeriodicWorkRequestBuilder<AlertsWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        val service = Intent(app, AlertService::class.java)
        if (wantsService(app)) {
            try {
                ContextCompat.startForegroundService(app, service)
            } catch (e: IllegalStateException) {
                // Android refuses to start it from the background right now. The
                // periodic check still runs, and the app starts it when opened.
            }
        } else {
            app.stopService(service)
        }
    }
}

class AlertsWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AlertSync.syncAll(applicationContext)
        return Result.success()
    }
}

/** Restarts instant alerts and the update check after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlertScheduler.apply(context)
                UpdateScheduler.apply(context)
            }
        }
    }
}
