package io.github.rayvader.kirocrew

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.rayvader.kirocrew.core.GhostPlacement
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.ProfileCodec
import io.github.rayvader.kirocrew.core.Versions
import io.github.rayvader.kirocrew.core.Shortcut

/** Every user setting in one place. Keys match res/xml/preferences.xml. */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var profiles: List<Profile>
        get() = ProfileCodec.decode(sp.getString(K_PROFILES, null))
        set(value) = sp.edit { putString(K_PROFILES, ProfileCodec.encode(value)) }

    var activeProfileId: String?
        get() = sp.getString(K_ACTIVE, null)
        set(value) = sp.edit { putString(K_ACTIVE, value) }

    val activeProfile: Profile?
        get() {
            val list = profiles
            return list.firstOrNull { it.id == activeProfileId } ?: list.firstOrNull()
        }

    /** A one-time URL to open after scanning a sign-in QR code. */
    var pendingOpenUrl: String?
        get() = sp.getString(K_PENDING_OPEN, null)
        set(value) = sp.edit { putString(K_PENDING_OPEN, value) }

    var pendingClearCache: Boolean
        get() = sp.getBoolean(K_PENDING_CLEAR, false)
        set(value) = sp.edit { putBoolean(K_PENDING_CLEAR, value) }

    var pendingReload: Boolean
        get() = sp.getBoolean(K_PENDING_RELOAD, false)
        set(value) = sp.edit { putBoolean(K_PENDING_RELOAD, value) }

    val theme: String get() = sp.getString("theme", "system") ?: "system"
    val accent: String get() = sp.getString("accent", "purple") ?: "purple"
    val forceDarkPages: Boolean get() = sp.getBoolean("force_dark_pages", false)
    val textZoom: Int get() = sp.getInt("text_zoom", 100).coerceIn(50, 200)
    val autoTextSize: Boolean get() = sp.getBoolean("auto_text_size", false)
    val showToolbar: Boolean get() = sp.getBoolean("show_toolbar", true)

    /** Which edge the menu ghost rests on when the top bar is off. */
    var ghostOnLeft: Boolean
        get() = sp.getBoolean("ghost_left", false)
        set(value) = sp.edit { putBoolean("ghost_left", value) }

    /** The ghost's height as a share of the free height; [GhostPlacement.UNSET] until it is moved. */
    var ghostY: Float
        get() = sp.getFloat("ghost_y", GhostPlacement.UNSET)
        set(value) = sp.edit { putFloat("ghost_y", value) }
    val showBottomBar: Boolean get() = sp.getBoolean("show_bottom_bar", true)
    val fullscreen: Boolean get() = sp.getBoolean("fullscreen", false)
    val pullToRefresh: Boolean get() = sp.getBoolean("pull_to_refresh", false)
    val keepScreenOn: Boolean get() = sp.getBoolean("keep_screen_on", false)
    val desktopMode: Boolean get() = sp.getBoolean("desktop_mode", false)
    val fastNav: Boolean get() = sp.getBoolean("fast_nav", true)
    val externalInBrowser: Boolean get() = sp.getBoolean("external_in_browser", true)
    val doubleBackExit: Boolean get() = sp.getBoolean("double_back_exit", true)
    val customCss: String get() = sp.getString("custom_css", "") ?: ""
    val uaSuffix: String get() = sp.getString("ua_suffix", "") ?: ""
    val appLock: Boolean get() = sp.getBoolean("app_lock", false)
    val lockTimeoutMs: Long get() = ((sp.getString("lock_timeout", "60") ?: "60").toLongOrNull() ?: 60L) * 1000L
    val privacyScreen: Boolean get() = sp.getBoolean("privacy_screen", false)
    val startPage: String get() = sp.getString("start_page", "chat") ?: "chat"
    val customStartPath: String get() = sp.getString("custom_start_path", "/") ?: "/"

    // Notifications
    val notifyEnabled: Boolean get() = sp.getBoolean("notify_enabled", true)
    /** "off", a number of minutes ("15", "30", "60"), or "instant". */
    val alertMode: String get() = sp.getString("alert_mode", "15") ?: "15"

    var askedNotifyPermission: Boolean
        get() = sp.getBoolean(K_ASKED_NOTIFY, false)
        set(value) = sp.edit { putBoolean(K_ASKED_NOTIFY, value) }

    /** Newest notification time seen for a server; null before the first check. */
    fun alertMarker(profileId: String): String? = sp.getString(K_MARKER + profileId, null)
    fun setAlertMarker(profileId: String, marker: String) = sp.edit { putString(K_MARKER + profileId, marker) }

    fun signInNeeded(profileId: String): Boolean = sp.getBoolean(K_SIGN_IN + profileId, false)
    fun setSignInNeeded(profileId: String, needed: Boolean) = sp.edit { putBoolean(K_SIGN_IN + profileId, needed) }

    // Updates
    val updateCheck: Boolean get() = sp.getBoolean("update_check", true)

    /** Ask each saved gateway whether Kiro Crew itself has an update. */
    val gatewayUpdateCheck: Boolean get() = sp.getBoolean("gateway_update_check", true)

    var lastGatewayUpdateCheckAt: Long
        get() = sp.getLong(K_LAST_GATEWAY_CHECK, 0L)
        set(value) = sp.edit { putLong(K_LAST_GATEWAY_CHECK, value) }

    /** The gateway version we already sent a notification about for this server. */
    fun notifiedGatewayUpdate(profileId: String): String? = sp.getString(K_NOTIFIED_GATEWAY + profileId, null)
    fun setNotifiedGatewayUpdate(profileId: String, version: String) =
        sp.edit { putString(K_NOTIFIED_GATEWAY + profileId, version) }

    var lastUpdateCheckAt: Long
        get() = sp.getLong(K_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit { putLong(K_LAST_UPDATE_CHECK, value) }

    /** The release we already sent a notification about, so it is sent once. */
    var notifiedUpdateVersion: String?
        get() = sp.getString(K_NOTIFIED_UPDATE, null)
        set(value) = sp.edit { putString(K_NOTIFIED_UPDATE, value) }

    fun snoozeUpdate(version: String) = sp.edit {
        putString(K_SNOOZED_UPDATE, version)
        putLong(K_SNOOZED_AT, System.currentTimeMillis())
    }

    /** "Later" hides the prompt for that version for a day. */
    fun isUpdateSnoozed(version: String): Boolean =
        sp.getString(K_SNOOZED_UPDATE, null) == version &&
            System.currentTimeMillis() - sp.getLong(K_SNOOZED_AT, 0L) < SNOOZE_MS

    /** Remember the "What's new" lines of the release being installed, to show once it is running. */
    fun savePendingChangelog(version: String, notes: List<String>) = sp.edit {
        putString(K_CHANGELOG_VERSION, version)
        putString(K_CHANGELOG_NOTES, notes.joinToString("\n"))
    }

    /** The saved notes if this app is that release, else null. Either way a done or stale note is dropped. */
    fun takePendingChangelog(installed: String): List<String>? {
        val version = sp.getString(K_CHANGELOG_VERSION, null) ?: return null
        val notes = sp.getString(K_CHANGELOG_NOTES, "").orEmpty().lines().filter { it.isNotBlank() }
        val installedNow = Versions.parse(installed)
        val target = Versions.parse(version)
        if (installedNow == null || target == null) return null
        // Not there yet (the install may still be waiting): keep the note.
        if (Versions.isNewer(version, installed)) return null
        sp.edit {
            remove(K_CHANGELOG_VERSION)
            remove(K_CHANGELOG_NOTES)
        }
        return notes.takeIf { installedNow == target && it.isNotEmpty() }
    }

    val bottomShortcuts: List<Shortcut>
        get() {
            val keys = sp.getStringSet("bottom_shortcuts", null) ?: return Shortcut.DEFAULT_BAR
            return keys.mapNotNull { Shortcut.fromKey(it) }.sortedBy { it.ordinal }.take(MAX_BAR_ITEMS)
        }

    fun startPath(): String = when (startPage) {
        "custom" -> customStartPath.ifBlank { "/" }
        "root" -> "/"
        else -> Shortcut.fromKey(startPage)?.path ?: Shortcut.CHAT.path
    }

    companion object {
        const val MAX_BAR_ITEMS = 5
        private const val K_PROFILES = "profiles_v1"
        private const val K_ACTIVE = "active_profile"
        private const val K_PENDING_OPEN = "pending_open_url"
        private const val K_PENDING_CLEAR = "pending_clear_cache"
        private const val K_PENDING_RELOAD = "pending_reload"
        private const val K_ASKED_NOTIFY = "asked_notify_permission"
        private const val K_MARKER = "alert_marker_"
        private const val K_SIGN_IN = "alert_sign_in_"
        private const val K_LAST_UPDATE_CHECK = "last_update_check"
        private const val K_LAST_GATEWAY_CHECK = "last_gateway_update_check"
        private const val K_NOTIFIED_GATEWAY = "notified_gateway_update_"
        private const val K_NOTIFIED_UPDATE = "notified_update"
        private const val K_SNOOZED_UPDATE = "snoozed_update"
        private const val K_CHANGELOG_VERSION = "changelog_version"
        private const val K_CHANGELOG_NOTES = "changelog_notes"
        private const val K_SNOOZED_AT = "snoozed_update_at"
        private const val SNOOZE_MS = 24L * 60 * 60 * 1000
    }
}
