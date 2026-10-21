package io.github.rayvader.kirocrew

import android.content.Context
import kotlin.concurrent.thread

/**
 * "Check for Kiro Crew updates": asks each saved gateway whether Kiro Crew itself
 * has a new version, and posts one notification per server and version. Tapping
 * it opens that dashboard's Settings > About, where the Update button lives.
 * The app never installs a gateway update itself.
 */
object GatewayUpdates {
    private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000
    private val lock = Any()

    /** When the app opens: at most every few hours, in the background. */
    fun autoCheck(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.gatewayUpdateCheck || prefs.profiles.isEmpty()) return
        if (System.currentTimeMillis() - prefs.lastGatewayUpdateCheckAt < AUTO_INTERVAL_MS) return
        prefs.lastGatewayUpdateCheckAt = System.currentTimeMillis()
        thread(name = "kirocrew-gateway-update", isDaemon = true) { checkAll(ctx.applicationContext) }
    }

    /** "Check now" in Settings. */
    fun checkNow(ctx: Context) {
        if (!Prefs(ctx).gatewayUpdateCheck) return
        thread(name = "kirocrew-gateway-update", isDaemon = true) { checkAll(ctx.applicationContext) }
    }

    /** Blocking. Run off the main thread (the daily worker or the threads above). */
    fun checkAll(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.gatewayUpdateCheck) return
        val profiles = prefs.profiles
        val client = AlertsClient(ctx)
        synchronized(lock) {
            prefs.lastGatewayUpdateCheckAt = System.currentTimeMillis()
            for (profile in profiles) {
                val update = client.gatewayUpdate(profile) ?: continue
                if (prefs.notifiedGatewayUpdate(profile.id) == update.latest) continue
                prefs.setNotifiedGatewayUpdate(profile.id, update.latest)
                Notifier.postGatewayUpdate(ctx, profile, update.latest)
            }
        }
    }
}
