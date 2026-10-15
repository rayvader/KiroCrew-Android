package io.github.rayvader.kirocrew

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import io.github.rayvader.kirocrew.core.Shortcut

object Theming {
    fun applyNightMode(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun accentStyle(accent: String): Int = when (accent) {
        "blue" -> R.style.Accent_Blue
        "green" -> R.style.Accent_Green
        "orange" -> R.style.Accent_Orange
        "rose" -> R.style.Accent_Rose
        "graphite" -> R.style.Accent_Graphite
        else -> R.style.Accent_Purple
    }

    /** Call before super.onCreate so every view picks up the accent color. */
    fun applyAccent(activity: Activity, prefs: Prefs) {
        activity.theme.applyStyle(accentStyle(prefs.accent), true)
    }

    /** Changes when a setting needs the activity rebuilt to show. */
    fun styleKey(prefs: Prefs): String = prefs.accent
}

object ShortcutUi {
    @StringRes
    fun label(s: Shortcut): Int = when (s) {
        Shortcut.CHAT -> R.string.nav_chat
        Shortcut.SCHEDULE -> R.string.nav_schedule
        Shortcut.ARTIFACTS -> R.string.nav_artifacts
        Shortcut.NOTIFICATIONS -> R.string.nav_notifications
        Shortcut.CUSTOMIZE -> R.string.nav_customize
        Shortcut.APPS -> R.string.nav_apps
        Shortcut.SETTINGS -> R.string.nav_settings
        Shortcut.LOGS -> R.string.nav_logs
        Shortcut.DEVELOPER -> R.string.nav_developer
    }

    @DrawableRes
    fun icon(s: Shortcut): Int = when (s) {
        Shortcut.CHAT -> R.drawable.ic_chat
        Shortcut.SCHEDULE -> R.drawable.ic_schedule
        Shortcut.ARTIFACTS -> R.drawable.ic_folder
        Shortcut.NOTIFICATIONS -> R.drawable.ic_notifications
        Shortcut.CUSTOMIZE -> R.drawable.ic_tune
        Shortcut.APPS -> R.drawable.ic_apps
        Shortcut.SETTINGS -> R.drawable.ic_settings
        Shortcut.LOGS -> R.drawable.ic_list
        Shortcut.DEVELOPER -> R.drawable.ic_code
    }
}
