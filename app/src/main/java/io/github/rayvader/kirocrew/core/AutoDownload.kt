package io.github.rayvader.kirocrew.core

/** Where the APK for the offered version stands on the phone. */
enum class ApkState {
    /** Nothing saved for this version. */
    NONE,

    /** Android's downloader is still fetching it, or waiting for Wi-Fi to come back. */
    RUNNING,

    /** Fully saved, and its size and SHA-256 match the release. */
    READY,

    /** A download of this version failed or did not match the release. It is not retried by itself. */
    FAILED,
}

/** What the app does once it knows about the newest release. */
enum class UpdateStep {
    /** No newer version. */
    NOTHING,

    /** Show the update prompt; the APK downloads only when the user taps Update. */
    OFFER,

    /** Start the APK download by itself (Wi-Fi or another unmetered network). */
    START_DOWNLOAD,

    /** A download is already running: leave it alone. */
    DOWNLOADING,

    /** The APK is saved and checked: show the prompt as ready to install. */
    INSTALL,
}

/**
 * Decides whether a newer release's APK downloads by itself. On an unmetered network (Wi-Fi) it does;
 * on mobile data it waits for a tap. A version is never downloaded twice: a finished file is reused and
 * a running download is left to finish. Installing always needs the user's tap; Android requires it.
 */
object AutoDownload {

    fun decide(newer: Boolean, metered: Boolean, apk: ApkState): UpdateStep = when {
        !newer -> UpdateStep.NOTHING
        apk == ApkState.READY -> UpdateStep.INSTALL
        // A failed try is not repeated by itself: the prompt lets the user try again.
        apk == ApkState.FAILED -> UpdateStep.OFFER
        // On mobile data a download started on Wi-Fi waits for Wi-Fi; a tap fetches it now instead.
        metered -> UpdateStep.OFFER
        apk == ApkState.RUNNING -> UpdateStep.DOWNLOADING
        else -> UpdateStep.START_DOWNLOAD
    }

    /** A saved download counts only for the version on offer: one for another version is ignored. */
    fun stateFor(savedVersion: String?, offered: String, saved: ApkState): ApkState =
        if (savedVersion != null && savedVersion == offered) saved else ApkState.NONE

    /**
     * True when a saved file of [size] bytes with SHA-256 [sha256] is the release's APK. Checks what
     * the release publishes; a file is never trusted on its name alone.
     */
    fun matches(release: Release, size: Long, sha256: String?): Boolean {
        if (size <= 0) return false
        if (release.apkSize <= 0 && release.sha256 == null) return false
        if (release.apkSize > 0 && size != release.apkSize) return false
        if (release.sha256 != null && !release.sha256.equals(sha256, ignoreCase = true)) return false
        return true
    }
}
