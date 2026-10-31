package io.github.rayvader.kirocrew

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.rayvader.kirocrew.core.Alert
import io.github.rayvader.kirocrew.core.GatewayUpdateParser
import io.github.rayvader.kirocrew.core.LiveTags
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.Shortcut

/** Every Android notification the app posts. */
object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_CHAT = "chat"
    const val CH_APP = "app"
    const val CH_CONNECTION = "connection"
    const val ID_SERVICE = 4201

    private const val ID_ALERT = 1
    private const val ID_LIVE = 2
    private const val ID_UPDATE = 3
    private const val ID_SIGN_IN = 4
    private const val ID_INSTALL = 5
    private const val ID_SUMMARY = 6
    private const val ID_GATEWAY_UPDATE = 7
    private const val GROUP_ALERTS = "io.github.rayvader.kirocrew.ALERTS"
    private const val GROUP_GATEWAY_UPDATES = "io.github.rayvader.kirocrew.GATEWAY_UPDATES"
    private const val MAX_TITLE = 200
    private const val MAX_BODY = 1000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ALERTS, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = ctx.getString(R.string.channel_alerts_desc) },
                NotificationChannel(CH_CHAT, ctx.getString(R.string.channel_chat), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = ctx.getString(R.string.channel_chat_desc) },
                NotificationChannel(CH_APP, ctx.getString(R.string.channel_app), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = ctx.getString(R.string.channel_app_desc) },
                NotificationChannel(CH_CONNECTION, ctx.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = ctx.getString(R.string.channel_connection_desc)
                        setShowBadge(false)
                    },
            )
        )
    }

    /** Android lets the app post notifications (runtime permission on 13+, and not switched off). */
    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /** A note from the dashboard's notification feed. */
    fun postAlert(ctx: Context, profile: Profile, showServer: Boolean, alert: Alert) {
        val key = "alert:${profile.id}:${alert.ts}"
        val n = base(ctx, CH_ALERTS)
            .setContentTitle(alert.title.take(MAX_TITLE))
            .setContentText(alert.body.take(MAX_BODY).ifBlank { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body.take(MAX_BODY)))
            .setSubText(if (showServer) profile.name else null)
            .setWhen(alert.epochMillis ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup(GROUP_ALERTS)
            .setContentIntent(open(ctx, key, profile.id, alert.path ?: Shortcut.NOTIFICATIONS.path))
            .build()
        post(ctx, key, ID_ALERT, n)
        postSummary(ctx, profile)
    }

    /** "3 more alerts" when one check finds more than it shows. */
    fun postMore(ctx: Context, profile: Profile, count: Int) {
        val key = "more:${profile.id}"
        val text = ctx.resources.getQuantityString(R.plurals.more_alerts, count, count)
        val n = base(ctx, CH_ALERTS)
            .setContentTitle(text)
            .setSubText(profile.name)
            .setGroup(GROUP_ALERTS)
            .setContentIntent(open(ctx, key, profile.id, Shortcut.NOTIFICATIONS.path))
            .build()
        post(ctx, key, ID_ALERT, n)
    }

    private fun postSummary(ctx: Context, profile: Profile) {
        val n = base(ctx, CH_ALERTS)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(open(ctx, "summary", profile.id, Shortcut.NOTIFICATIONS.path))
            .build()
        post(ctx, "summary", ID_SUMMARY, n)
    }

    /** A toast raised by the dashboard page itself. The same tag replaces the last one, like a browser. */
    fun postLive(ctx: Context, profile: Profile?, showServer: Boolean, title: String, body: String, tag: String) {
        val key = liveKey(tag.ifBlank { title })
        val channel = if (LiveTags.isSessionEvent(tag)) CH_CHAT else CH_ALERTS
        val n = base(ctx, channel)
            .setContentTitle(title.take(MAX_TITLE))
            .setContentText(body.take(MAX_BODY).ifBlank { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(MAX_BODY)))
            .setSubText(if (showServer) profile?.name else null)
            .setContentIntent(open(ctx, key, profile?.id, null))
            .build()
        post(ctx, key, ID_LIVE, n)
    }

    fun cancelLive(ctx: Context, tag: String) {
        if (tag.isBlank()) return
        NotificationManagerCompat.from(ctx).cancel(liveKey(tag), ID_LIVE)
    }

    private fun liveKey(tag: String) = "live:$tag"

    fun postUpdate(ctx: Context, version: String) {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
        val n = base(ctx, CH_APP)
            .setContentTitle(ctx.getString(R.string.update_ready_title, version))
            .setContentText(ctx.getString(R.string.update_ready_body))
            .setContentIntent(PendingIntent.getActivity(ctx, "update".hashCode(), intent, PI_FLAGS))
            .build()
        post(ctx, "update", ID_UPDATE, n)
    }

    /** The APK downloaded by itself on Wi-Fi. Replaces the "is ready" notice; tapping opens the install prompt. */
    fun postUpdateDownloaded(ctx: Context, version: String) {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
        val n = base(ctx, CH_APP)
            .setContentTitle(ctx.getString(R.string.update_downloaded_title, version))
            .setContentText(ctx.getString(R.string.update_install_tap))
            .setContentIntent(PendingIntent.getActivity(ctx, "update".hashCode(), intent, PI_FLAGS))
            .build()
        post(ctx, "update", ID_UPDATE, n)
    }

    /** Kiro Crew itself has an update on this server. Tapping opens the dashboard's About page. */
    fun postGatewayUpdate(ctx: Context, profile: Profile, version: String) {
        val key = "gateway-update:${profile.id}"
        val body = ctx.getString(R.string.gateway_update_body, profile.name)
        val n = base(ctx, CH_APP)
            .setContentTitle(ctx.getString(R.string.gateway_update_title, version))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open(ctx, key, profile.id, GatewayUpdateParser.ABOUT_PATH))
            // Its own group: otherwise Android bundles it with the app's other
            // notifications, and a tap on the bundle never reaches this one.
            .setGroup(GROUP_GATEWAY_UPDATES)
            .build()
        post(ctx, key, ID_GATEWAY_UPDATE, n)
    }

    /** Shown when Android blocked the app from opening the install screen by itself. */
    fun postInstallReady(ctx: Context, confirm: Intent) {
        val n = base(ctx, CH_APP)
            .setContentTitle(ctx.getString(R.string.update_install_ready))
            .setContentText(ctx.getString(R.string.update_install_tap))
            .setContentIntent(PendingIntent.getActivity(ctx, "install".hashCode(), confirm, PI_FLAGS))
            .build()
        post(ctx, "install", ID_INSTALL, n)
    }

    fun postSignIn(ctx: Context, profile: Profile) {
        val key = "sign-in:${profile.id}"
        val n = base(ctx, CH_APP)
            .setContentTitle(ctx.getString(R.string.sign_in_needed_title))
            .setContentText(ctx.getString(R.string.sign_in_needed_body, profile.name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.sign_in_needed_body, profile.name)))
            .setContentIntent(open(ctx, key, profile.id, null))
            .build()
        post(ctx, key, ID_SIGN_IN, n)
    }

    fun cancelSignIn(ctx: Context, profile: Profile) {
        NotificationManagerCompat.from(ctx).cancel("sign-in:${profile.id}", ID_SIGN_IN)
    }

    /** The small ongoing notification Android requires while instant alerts stay connected. */
    fun serviceNotification(ctx: Context, text: String): Notification {
        val settings = Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return base(ctx, CH_CONNECTION)
            .setContentTitle(ctx.getString(R.string.instant_alerts_on))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open(ctx, "service", null, null))
            .addAction(0, ctx.getString(R.string.settings), PendingIntent.getActivity(ctx, "settings".hashCode(), settings, PI_FLAGS))
            .build()
    }

    fun updateService(ctx: Context, text: String) {
        post(ctx, null, ID_SERVICE, serviceNotification(ctx, text))
    }

    /** Opens this app's page in Android's notification settings (sounds, vibration, channels). */
    fun systemSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    private fun base(ctx: Context, channel: String) = NotificationCompat.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_stat_ghost)
        .setColor(ContextCompat.getColor(ctx, R.color.ic_launcher_background))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun open(ctx: Context, key: String, profileId: String?, path: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        profileId?.let { intent.putExtra(MainActivity.EXTRA_PROFILE, it) }
        path?.let { intent.putExtra(MainActivity.EXTRA_PATH, it) }
        return PendingIntent.getActivity(ctx, key.hashCode(), intent, PI_FLAGS)
    }

    private fun post(ctx: Context, tag: String?, id: Int, n: Notification) {
        if (!canPost(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(tag, id, n)
        } catch (e: SecurityException) {
            // Permission was taken away between the check and the post.
        }
    }

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
