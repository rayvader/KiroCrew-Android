package io.github.rayvader.kirocrew

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rayvader.kirocrew.core.Shortcut

class SettingsActivity : AppCompatActivity() {
    lateinit var updates: UpdateFlow
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyAccent(this, Prefs(this))
        super.onCreate(savedInstanceState)
        updates = UpdateFlow(this)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (!granted) Toast.makeText(ctx, R.string.notify_denied_toast, Toast.LENGTH_LONG).show()
        AlertScheduler.apply(ctx)
        updateNotifySummary()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val ctx = requireContext()

        findPreference<SwitchPreferenceCompat>("notify_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) askNotifyPermissionIfNeeded()
            true
        }

        findPreference<ListPreference>("alert_mode")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue != AlertScheduler.MODE_OFF) askNotifyPermissionIfNeeded()
            true
        }

        findPreference<Preference>("notify_system")?.setOnPreferenceClickListener {
            try {
                startActivity(Notifier.systemSettingsIntent(ctx))
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
            true
        }

        findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.updates?.checkNow()
            GatewayUpdates.checkNow(ctx)
            true
        }

        findPreference<Preference>("servers")?.setOnPreferenceClickListener {
            startActivity(Intent(ctx, ProfilesActivity::class.java))
            true
        }

        findPreference<Preference>("froyo_widget")?.setOnPreferenceClickListener {
            if (!FroyoWidgets.requestPin(ctx)) {
                Toast.makeText(ctx, R.string.froyo_add_widget_unsupported, Toast.LENGTH_LONG).show()
            }
            true
        }

        findPreference<MultiSelectListPreference>("bottom_shortcuts")?.apply {
            summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { pref ->
                val chosen = pref.values.mapNotNull { Shortcut.fromKey(it) }.sortedBy { it.ordinal }
                if (chosen.isEmpty()) getString(R.string.none) else chosen.joinToString(", ") { getString(ShortcutUi.label(it)) }
            }
            setOnPreferenceChangeListener { _, newValue ->
                val size = (newValue as? Set<*>)?.size ?: 0
                if (size > Prefs.MAX_BAR_ITEMS) {
                    Toast.makeText(ctx, getString(R.string.max_shortcuts, Prefs.MAX_BAR_ITEMS), Toast.LENGTH_SHORT).show()
                    false
                } else {
                    true
                }
            }
        }

        findPreference<EditTextPreference>("custom_css")?.apply {
            setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.isSingleLine = false
                edit.minLines = 4
                edit.maxLines = 12
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val css = pref.text.orEmpty().trim()
                if (css.isEmpty()) getString(R.string.custom_css_summary) else css.lineSequence().first().take(60)
            }
        }

        findPreference<EditTextPreference>("custom_start_path")?.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            edit.isSingleLine = true
        }

        findPreference<SwitchPreferenceCompat>("app_lock")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true && !AppLock.canUse(ctx)) {
                Toast.makeText(ctx, R.string.lock_unavailable, Toast.LENGTH_LONG).show()
                false
            } else {
                if (newValue == true) (requireActivity().application as KiroCrewApp).unlocked()
                true
            }
        }

        findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
            Prefs(ctx).pendingClearCache = true
            Toast.makeText(ctx, R.string.cache_clear_on_return, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("sign_out")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.sign_out)
                .setMessage(R.string.sign_out_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sign_out) { _, _ ->
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    Prefs(ctx).pendingReload = true
                    // The widget's saved list is the signed-in person's: drop it too.
                    FroyoWidgets.clear(ctx)
                    Toast.makeText(ctx, R.string.signed_out, Toast.LENGTH_SHORT).show()
                }
                .show()
            true
        }

        findPreference<Preference>("about")?.apply {
            summary = getString(R.string.about_version, BuildConfig.VERSION_NAME)
            setOnPreferenceClickListener {
                startActivity(Intent(ctx, CreditsActivity::class.java))
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        findPreference<Preference>("servers")?.summary =
            Prefs(requireContext()).activeProfile?.let { getString(R.string.servers_active, it.name) }
                ?: getString(R.string.servers_summary)
        updateNotifySummary()
        updateTextSizeSummary()
    }

    private fun askNotifyPermissionIfNeeded() {
        val ctx = requireContext()
        if (Notifier.canPost(ctx)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Prefs(ctx).askedNotifyPermission = true
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Switched off in Android settings: only the user can turn it back on there.
            Toast.makeText(ctx, R.string.notify_denied_toast, Toast.LENGTH_LONG).show()
        }
    }

    /** With Auto text size on, the slider is an adjustment on top of the automatic size. */
    private fun updateTextSizeSummary() {
        val ctx = context ?: return
        findPreference<Preference>("text_zoom")?.summary =
            if (Prefs(ctx).autoTextSize) getString(R.string.text_zoom_auto_summary) else null
    }

    private fun updateNotifySummary() {
        val ctx = context ?: return
        findPreference<SwitchPreferenceCompat>("notify_enabled")?.summary = getString(
            if (Prefs(ctx).notifyEnabled && !Notifier.canPost(ctx)) R.string.notify_blocked else R.string.notify_enabled_summary
        )
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            "theme" -> Theming.applyNightMode(Prefs(requireContext()).theme)
            "accent" -> requireActivity().recreate()
            "notify_enabled", "alert_mode" -> {
                AlertScheduler.apply(requireContext())
                updateNotifySummary()
            }
            "update_check", "gateway_update_check" -> UpdateScheduler.apply(requireContext())
            "auto_text_size" -> updateTextSizeSummary()
        }
    }
}
