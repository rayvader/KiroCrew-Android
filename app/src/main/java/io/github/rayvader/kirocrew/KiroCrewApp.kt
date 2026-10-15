package io.github.rayvader.kirocrew

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.preference.PreferenceManager

class KiroCrewApp : Application(), Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0
    private var backgroundedAt = 0L

    /** App lock state: locked on a cold start, and again after the timeout in the background. */
    var locked = true
    var autoPrompt = true
    var authInProgress = false

    /** Set before opening a picker or scanner so coming back does not lock the app. */
    var expectingExternalReturn = false

    override fun onCreate() {
        super.onCreate()
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        Theming.applyNightMode(Prefs(this).theme)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0) {
            if (expectingExternalReturn || authInProgress) {
                expectingExternalReturn = false
            } else if (backgroundedAt > 0) {
                val prefs = Prefs(this)
                if (prefs.appLock && System.currentTimeMillis() - backgroundedAt >= prefs.lockTimeoutMs) {
                    locked = true
                    autoPrompt = true
                }
            }
        }
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !activity.isChangingConfigurations && !authInProgress && !expectingExternalReturn) {
            backgroundedAt = System.currentTimeMillis()
        }
    }

    fun unlocked() {
        locked = false
        authInProgress = false
        autoPrompt = true
        backgroundedAt = 0L
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
