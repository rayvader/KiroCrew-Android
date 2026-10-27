package io.github.rayvader.kirocrew

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.rayvader.kirocrew.core.PastRelease
import io.github.rayvader.kirocrew.core.Release
import io.github.rayvader.kirocrew.core.ReleaseHistory
import io.github.rayvader.kirocrew.core.ReleaseParser
import io.github.rayvader.kirocrew.core.Versions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Asks GitHub for the newest release of this app. */
object UpdateChecker {
    sealed interface Check {
        data class Newer(val release: Release) : Check
        data object UpToDate : Check
        data class Failed(val reason: String) : Check
    }

    private val userAgent = "KiroCrewAndroid/${BuildConfig.VERSION_NAME}"

    fun check(): Check = try {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", userAgent)
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val release = ReleaseParser.parse(body)
                    when {
                        release == null -> Check.Failed("no APK in the latest release")
                        Versions.isNewer(release.version, BuildConfig.VERSION_NAME) -> Check.Newer(release)
                        else -> Check.UpToDate
                    }
                }
                404 -> Check.UpToDate // No release published yet.
                else -> Check.Failed("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        Check.Failed(e.javaClass.simpleName)
    }
}

/** Asks GitHub for the past releases of this app, for Settings > About > Past changes. */
object ReleaseHistoryFetcher {
    sealed interface Result {
        data class Ok(val releases: List<PastRelease>) : Result
        data class Failed(val reason: String) : Result
    }

    fun fetch(): Result = try {
        // Branch builds are pre-releases too, so ask for many to find the real ones.
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=100")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "KiroCrewAndroid/${BuildConfig.VERSION_NAME}")
            when (val code = conn.responseCode) {
                200 -> Result.Ok(ReleaseHistory.parse(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }))
                else -> Result.Failed("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        Result.Failed(e.javaClass.simpleName)
    }
}

/** A daily background check that posts one notification per new version. */
object UpdateScheduler {
    private const val WORK = "update-check"

    fun apply(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        val prefs = Prefs(ctx)
        // One daily job covers both: app updates from GitHub and Kiro Crew updates from each gateway.
        if (!prefs.updateCheck && !prefs.gatewayUpdateCheck) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.updateCheck) {
            val check = UpdateChecker.check()
            if (check is UpdateChecker.Check.Newer && prefs.notifiedUpdateVersion != check.release.version) {
                prefs.notifiedUpdateVersion = check.release.version
                Notifier.postUpdate(applicationContext, check.release.version)
            }
        }
        GatewayUpdates.checkAll(applicationContext)
        return Result.success()
    }
}

/** Downloads a release APK, checks it, and hands it to Android's installer. */
object ApkInstaller {
    private const val ACTION_STATUS = "io.github.rayvader.kirocrew.INSTALL_STATUS"

    /** Saves the APK in the app cache and checks its size and SHA-256. Throws IOException on any problem. */
    fun download(ctx: Context, release: Release, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): File {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, release.apkName)
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true // GitHub redirects to its download host, HTTPS to HTTPS.
            conn.setRequestProperty("User-Agent", "KiroCrewAndroid/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            if (conn.url.protocol != "https") throw IOException("not HTTPS")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw IOException("size does not match")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hex != release.sha256) throw IOException("checksum does not match")
            return out
        } catch (e: IOException) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** Starts an install session. Android then asks the user to confirm; it refuses an APK signed with another key. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val status = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, sessionId, status, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Gets the install result from Android and shows the confirm screen. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val app = context.applicationContext as? KiroCrewApp
                if (app?.inForeground == true) {
                    app.expectingExternalReturn = true
                    try {
                        context.startActivity(confirm)
                        return
                    } catch (e: RuntimeException) {
                        // Fall back to a notification below.
                    }
                }
                Notifier.postInstallReady(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android replaces the running app.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // The user said no.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()
                Toast.makeText(context, context.getString(R.string.update_failed, message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
