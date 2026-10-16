package io.github.rayvader.kirocrew

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.rayvader.kirocrew.core.Release
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The on-screen part of updating: check, offer, download with progress, install. */
class UpdateFlow(private val activity: AppCompatActivity) {

    private val prefs = Prefs(activity)
    private var checking = false
    private var downloading = false
    /** Waiting for the user to allow "install unknown apps" in Android settings. */
    private var waitingForPermission: Release? = null

    /** Quiet check when the app opens: at most every few hours, silent when up to date. */
    fun autoCheck() {
        if (!prefs.updateCheck) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheckAt < AUTO_INTERVAL_MS) return
        startCheck(manual = false)
    }

    /** "Check now" in settings, or a tapped update notification. */
    fun checkNow() = startCheck(manual = true)

    /** Call from the activity's onResume. */
    fun onResume() {
        val release = waitingForPermission ?: return
        if (!canInstall()) return
        waitingForPermission = null
        download(release)
    }

    private fun startCheck(manual: Boolean) {
        if (checking || downloading) return
        checking = true
        if (manual) toast(activity.getString(R.string.update_checking))
        thread(name = "kirocrew-update-check", isDaemon = true) {
            val result = UpdateChecker.check()
            activity.runOnUiThread {
                checking = false
                prefs.lastUpdateCheckAt = System.currentTimeMillis()
                if (!alive()) return@runOnUiThread
                when (result) {
                    is UpdateChecker.Check.Newer ->
                        if (manual || !prefs.isUpdateSnoozed(result.release.version)) offer(result.release)
                    UpdateChecker.Check.UpToDate ->
                        if (manual) toast(activity.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                    is UpdateChecker.Check.Failed ->
                        if (manual) toast(activity.getString(R.string.update_check_failed, result.reason))
                }
            }
        }
    }

    private fun offer(release: Release) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(activity.getString(R.string.update_body, BuildConfig.VERSION_NAME, release.version))
            .setPositiveButton(R.string.update_now) { _, _ -> start(release) }
            .setNegativeButton(R.string.later) { _, _ -> prefs.snoozeUpdate(release.version) }
            .setNeutralButton(R.string.release_notes) { _, _ -> openPage(release.pageUrl) }
            .show()
    }

    private fun canInstall(): Boolean = activity.packageManager.canRequestPackageInstalls()

    private fun start(release: Release) {
        if (canInstall()) {
            download(release)
            return
        }
        waitingForPermission = release
        toast(activity.getString(R.string.update_allow_installs))
        (activity.application as KiroCrewApp).expectingExternalReturn = true
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: ActivityNotFoundException) {
            waitingForPermission = null
            openPage(release.pageUrl)
        }
    }

    private fun download(release: Release) {
        if (downloading) return
        downloading = true
        val cancelled = AtomicBoolean(false)
        val bar = LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading, release.version))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()
        val app = activity.applicationContext
        thread(name = "kirocrew-update-download", isDaemon = true) {
            try {
                val apk = ApkInstaller.download(app, release, { percent ->
                    activity.runOnUiThread {
                        bar.isIndeterminate = false
                        bar.setProgressCompat(percent, true)
                    }
                }, { cancelled.get() })
                activity.runOnUiThread { dismiss(dialog) }
                if (!cancelled.get()) {
                    (app as KiroCrewApp).expectingExternalReturn = true
                    ApkInstaller.install(app, apk)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dismiss(dialog)
                    if (!cancelled.get()) toast(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                activity.runOnUiThread { downloading = false }
            }
        }
    }

    private fun dismiss(dialog: AlertDialog) {
        if (alive() && dialog.isShowing) dialog.dismiss()
    }

    private fun openPage(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(activity.getString(R.string.no_app))
        }
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
