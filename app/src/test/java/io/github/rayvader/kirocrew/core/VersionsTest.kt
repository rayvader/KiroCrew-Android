package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun parse() {
        assertEquals(listOf(1, 0, 12), Versions.parse("v1.0.12"))
        assertEquals(listOf(2), Versions.parse("2"))
        assertNull(Versions.parse("1.0.x"))
        assertNull(Versions.parse("1.0.12-beta"))
        assertNull(Versions.parse(""))
    }

    @Test
    fun isNewer() {
        assertTrue(Versions.isNewer("1.0.12", "1.0.11"))
        assertTrue(Versions.isNewer("v1.0.100", "1.0.99"))
        assertTrue(Versions.isNewer("1.1", "1.0.50"))
        assertFalse(Versions.isNewer("1.0.11", "1.0.11"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.9", "1.0.11"))
        assertFalse(Versions.isNewer("junk", "1.0.1"))
        assertFalse(Versions.isNewer("1.0.2", "junk"))
    }

    private val sha = "a".repeat(64)

    private fun release(
        tag: String = "v1.0.12",
        prerelease: Boolean = false,
        page: String = "https://github.com/rayvader/KiroCrew-Android/releases/tag/v1.0.12",
        assets: String = """[
            {"name":"notes.txt","browser_download_url":"https://github.com/r/a/notes.txt","size":10},
            {"name":"KiroCrew-1.0.12.apk","browser_download_url":"https://github.com/rayvader/KiroCrew-Android/releases/download/v1.0.12/KiroCrew-1.0.12.apk","size":7340032,"digest":"sha256:${sha.uppercase()}"}
        ]""",
    ) = """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"html_url":"$page","body":"x","assets":$assets}"""

    @Test
    fun release_parsesApkAsset() {
        val r = ReleaseParser.parse(release())!!
        assertEquals("1.0.12", r.version)
        assertEquals("KiroCrew-1.0.12.apk", r.apkName)
        assertEquals(7340032L, r.apkSize)
        assertEquals(sha, r.sha256)
        assertTrue(r.apkUrl.startsWith("https://github.com/"))
    }

    @Test
    fun release_withoutDigest() {
        val r = ReleaseParser.parse(
            release(assets = """[{"name":"KiroCrew-1.0.12.apk","browser_download_url":"https://github.com/a/b/KiroCrew-1.0.12.apk","size":5,"digest":null}]""")
        )!!
        assertNull(r.sha256)
    }

    @Test
    fun release_refusesUnsafeOrUnusable() {
        assertNull(ReleaseParser.parse(release(prerelease = true)))
        assertNull(ReleaseParser.parse(release(tag = "nightly")))
        assertNull(ReleaseParser.parse(release(page = "https://evil.example/r")))
        assertNull(ReleaseParser.parse(release(assets = "[]")))
        assertNull(
            ReleaseParser.parse(
                release(assets = """[{"name":"KiroCrew-1.0.12.apk","browser_download_url":"http://github.com/a/b/KiroCrew-1.0.12.apk"}]""")
            )
        )
        assertNull(
            ReleaseParser.parse(
                release(assets = """[{"name":"KiroCrew-1.0.12.apk","browser_download_url":"https://evil.example/KiroCrew-1.0.12.apk"}]""")
            )
        )
        assertNull(ReleaseParser.parse("not json"))
    }
}
