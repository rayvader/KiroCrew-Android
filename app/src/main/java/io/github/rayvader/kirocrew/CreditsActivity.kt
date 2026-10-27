package io.github.rayvader.kirocrew

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rayvader.kirocrew.core.PastRelease

/** Settings > About: the app version on a moving kaleidoscope, and what changed in past versions. */
class CreditsActivity : AppCompatActivity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyAccent(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credits)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<TextView>(R.id.credits_app).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
        findViewById<MaterialButton>(R.id.past_changes).setOnClickListener { showPastChanges() }
    }

    /** Asks GitHub only when tapped, so opening About sends nothing. */
    private fun showPastChanges() {
        if (dialog?.isShowing == true) return
        val shown = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.past_changes_title)
            .setMessage(R.string.past_changes_loading)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog = shown
        Thread({
            val result = ReleaseHistoryFetcher.fetch()
            runOnUiThread {
                if (isFinishing || isDestroyed || !shown.isShowing) return@runOnUiThread
                shown.setMessage(
                    when (result) {
                        is ReleaseHistoryFetcher.Result.Ok -> text(result.releases)
                        is ReleaseHistoryFetcher.Result.Failed -> getString(R.string.past_changes_failed, result.reason)
                    }
                )
            }
        }, "kirocrew-past-changes").start()
    }

    private fun text(releases: List<PastRelease>): String {
        if (releases.isEmpty()) return getString(R.string.past_changes_none)
        return releases.joinToString("\n\n") { r ->
            r.version + r.notes.joinToString("") { "\n• $it" }
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
