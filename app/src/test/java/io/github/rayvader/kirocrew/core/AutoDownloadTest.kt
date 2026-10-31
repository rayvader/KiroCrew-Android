package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadTest {

    private val sha = "ab".repeat(32)

    private fun release(size: Long = 1000, digest: String? = sha) = Release(
        version = "2.0.200",
        pageUrl = "https://github.com/example/app/releases/tag/v2.0.200",
        apkUrl = "https://github.com/example/app/releases/download/v2.0.200/KiroCrew-2.0.200.apk",
        apkName = "KiroCrew-2.0.200.apk",
        apkSize = size,
        sha256 = digest,
    )

    @Test
    fun noNewerVersionDoesNothingOnAnyNetwork() {
        for (metered in listOf(true, false)) {
            for (apk in ApkState.values()) {
                assertEquals(UpdateStep.NOTHING, AutoDownload.decide(newer = false, metered = metered, apk = apk))
            }
        }
    }

    @Test
    fun onWifiANewVersionDownloadsByItself() {
        assertEquals(UpdateStep.START_DOWNLOAD, AutoDownload.decide(newer = true, metered = false, apk = ApkState.NONE))
    }

    @Test
    fun onMobileDataANewVersionWaitsForATap() {
        assertEquals(UpdateStep.OFFER, AutoDownload.decide(newer = true, metered = true, apk = ApkState.NONE))
    }

    @Test
    fun aFinishedDownloadIsReadyToInstallOnAnyNetwork() {
        assertEquals(UpdateStep.INSTALL, AutoDownload.decide(newer = true, metered = false, apk = ApkState.READY))
        assertEquals(UpdateStep.INSTALL, AutoDownload.decide(newer = true, metered = true, apk = ApkState.READY))
    }

    @Test
    fun aRunningDownloadOnWifiIsNotStartedAgain() {
        assertEquals(UpdateStep.DOWNLOADING, AutoDownload.decide(newer = true, metered = false, apk = ApkState.RUNNING))
    }

    @Test
    fun aDownloadWaitingForWifiCanBeTappedOnMobileData() {
        assertEquals(UpdateStep.OFFER, AutoDownload.decide(newer = true, metered = true, apk = ApkState.RUNNING))
    }

    @Test
    fun aFailedDownloadIsNotRetriedByItself() {
        assertEquals(UpdateStep.OFFER, AutoDownload.decide(newer = true, metered = false, apk = ApkState.FAILED))
        assertEquals(UpdateStep.OFFER, AutoDownload.decide(newer = true, metered = true, apk = ApkState.FAILED))
    }

    @Test
    fun aDownloadOfAnotherVersionDoesNotCount() {
        assertEquals(ApkState.RUNNING, AutoDownload.stateFor("2.0.200", "2.0.200", ApkState.RUNNING))
        assertEquals(ApkState.NONE, AutoDownload.stateFor("2.0.199", "2.0.200", ApkState.READY))
        assertEquals(ApkState.NONE, AutoDownload.stateFor(null, "2.0.200", ApkState.FAILED))
    }

    @Test
    fun aSavedFileMustMatchSizeAndChecksum() {
        assertTrue(AutoDownload.matches(release(), 1000, sha))
        assertTrue(AutoDownload.matches(release(), 1000, sha.uppercase()))
        assertFalse(AutoDownload.matches(release(), 999, sha))
        assertFalse(AutoDownload.matches(release(), 1000, "cd".repeat(32)))
        assertFalse(AutoDownload.matches(release(), 1000, null))
        assertFalse(AutoDownload.matches(release(), 0, sha))
    }

    @Test
    fun aReleaseWithOnlyOneCheckUsesThatOne() {
        assertTrue(AutoDownload.matches(release(digest = null), 1000, null))
        assertFalse(AutoDownload.matches(release(digest = null), 1001, null))
        assertTrue(AutoDownload.matches(release(size = -1), 1234, sha))
    }

    @Test
    fun aReleaseWithNoChecksIsNeverReused() {
        assertFalse(AutoDownload.matches(release(size = -1, digest = null), 1000, null))
    }
}
