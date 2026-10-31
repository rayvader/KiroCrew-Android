package io.github.rayvader.kirocrew

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import io.github.rayvader.kirocrew.core.ApkState
import io.github.rayvader.kirocrew.core.AutoDownload
import io.github.rayvader.kirocrew.core.Release
import io.github.rayvader.kirocrew.core.UpdateStep
import java.io.File
import java.security.MessageDigest

/**
 * Update APKs on the phone. On Wi-Fi (an unmetered network) a newer release's APK is fetched by itself
 * through Android's downloader, which never uses mobile data for it. The decision is [AutoDownload].
 * Everything here touches Android, so call it at run time only, off the main thread where noted.
 */
object ApkDownloads {
    private const val DIR = "updates"

    /** True on mobile data, or when Android cannot tell: then the user decides. */
    fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return true) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Where update APKs are saved, both by Android's downloader and by a tapped download. */
    fun dir(ctx: Context): File = ctx.getExternalFilesDir(DIR) ?: File(ctx.cacheDir, DIR)

    fun fileFor(ctx: Context, release: Release): File = File(dir(ctx), release.apkName)

    /** True when the saved APK for [release] is complete and matches it. Reads the whole file: not on the main thread. */
    fun isVerified(ctx: Context, release: Release): Boolean {
        val file = fileFor(ctx, release)
        if (!file.isFile) return false
        return AutoDownload.matches(release, file.length(), sha256(file))
    }

    /** Where the APK for [release] stands. Not on the main thread. */
    fun state(ctx: Context, release: Release): ApkState {
        val prefs = Prefs(ctx)
        val ours = prefs.apkDownloadVersion == release.version
        val status = if (ours) status(ctx, prefs.apkDownloadId) else null
        if (status in RUNNING) return ApkState.RUNNING
        if (isVerified(ctx, release)) return ApkState.READY
        if (ours && status != null) {
            // Finished but failed, or saved a file that does not match: drop it and wait for a tap.
            forget(ctx, removeFile = true)
            prefs.apkDownloadFailedVersion = release.version
        }
        return AutoDownload.stateFor(prefs.apkDownloadFailedVersion, release.version, ApkState.FAILED)
    }

    /** Checks the network and the saved file, and starts the download by itself when that is the step. Not on the main thread. */
    fun prepare(ctx: Context, release: Release): UpdateStep {
        val step = AutoDownload.decide(newer = true, metered = isMetered(ctx), apk = state(ctx, release))
        if (step != UpdateStep.START_DOWNLOAD) return step
        return if (start(ctx, release)) UpdateStep.DOWNLOADING else UpdateStep.OFFER
    }

    /** Hands the APK to Android's downloader, Wi-Fi only. False when it could not start. */
    private fun start(ctx: Context, release: Release): Boolean {
        val prefs = Prefs(ctx)
        forget(ctx, removeFile = true)
        // Only one APK is kept: an older one is no longer needed.
        dir(ctx).listFiles()?.forEach { it.delete() }
        return try {
            val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle(ctx.getString(R.string.update_downloading, release.version))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(ctx, DIR, release.apkName)
                .addRequestHeader("User-Agent", "KiroCrewAndroid/${BuildConfig.VERSION_NAME}")
            val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
            prefs.apkDownloadId = dm.enqueue(request)
            prefs.apkDownloadVersion = release.version
            prefs.apkDownloadFailedVersion = null
            true
        } catch (e: RuntimeException) {
            // No shared storage, or the downloader is off: the user can still tap Update.
            false
        }
    }

    /**
     * Stops tracking the downloader job before a tapped download. A running or failed job is removed;
     * a finished file is kept, because removing a finished job deletes its file.
     */
    fun handOver(ctx: Context) {
        val prefs = Prefs(ctx)
        val status = status(ctx, prefs.apkDownloadId)
        forget(ctx, removeFile = status != DownloadManager.STATUS_SUCCESSFUL)
        prefs.apkDownloadFailedVersion = null
    }

    /** No newer version any more (it is installed): delete saved APKs. */
    fun clear(ctx: Context) {
        forget(ctx, removeFile = true)
        Prefs(ctx).apkDownloadFailedVersion = null
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** The downloader job finished: tell the user, who taps to install. */
    fun onComplete(ctx: Context, id: Long) {
        val prefs = Prefs(ctx)
        if (id < 0 || id != prefs.apkDownloadId) return
        val version = prefs.apkDownloadVersion ?: return
        // The file is checked against the release when the prompt opens.
        if (status(ctx, id) == DownloadManager.STATUS_SUCCESSFUL) Notifier.postUpdateDownloaded(ctx, version)
    }

    private fun forget(ctx: Context, removeFile: Boolean) {
        val prefs = Prefs(ctx)
        val id = prefs.apkDownloadId
        if (id >= 0 && removeFile) {
            try {
                ctx.getSystemService(DownloadManager::class.java)?.remove(id)
            } catch (e: RuntimeException) {
                // Already gone.
            }
        }
        prefs.apkDownloadId = -1L
        prefs.apkDownloadVersion = null
    }

    private val RUNNING = setOf(
        DownloadManager.STATUS_PENDING,
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED,
    )

    private fun status(ctx: Context, id: Long): Int? {
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return null
        return try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Android's downloader says a job ended. Only the job this app started counts. */
class ApkDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        ApkDownloads.onComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
    }
}
