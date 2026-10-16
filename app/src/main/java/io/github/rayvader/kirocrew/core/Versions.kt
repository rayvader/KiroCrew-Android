package io.github.rayvader.kirocrew.core

import org.json.JSONException
import org.json.JSONObject

object Versions {
    /** "v1.0.12" or "1.0.12" -> [1, 0, 12]. Null when it is not a dotted number. */
    fun parse(version: String): List<Int>? {
        val s = version.trim().removePrefix("v").removePrefix("V")
        if (s.isEmpty()) return null
        return s.split('.').map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    /** True only when both parse and [remote] is strictly higher than [local]. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}

/** A published app release with its APK. */
data class Release(
    val version: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 from GitHub's asset digest, when GitHub gives one. */
    val sha256: String?,
)

/** Reads a GitHub "latest release" API response. */
object ReleaseParser {
    private val APK_NAME = Regex("KiroCrew-[0-9][0-9.]*\\.apk")
    private val HEX_64 = Regex("[0-9a-f]{64}")
    private const val GITHUB = "https://github.com/"

    fun parse(json: String): Release? = try {
        parse(JSONObject(json))
    } catch (e: JSONException) {
        null
    }

    private fun parse(o: JSONObject): Release? {
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val version = o.text("tag_name")?.trim()?.removePrefix("v") ?: return null
        Versions.parse(version) ?: return null
        val page = o.text("html_url")?.takeIf { it.startsWith(GITHUB) } ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.text("name") ?: continue
            if (!APK_NAME.matches(name)) continue
            // Only ever download from GitHub itself, over HTTPS.
            val url = a.text("browser_download_url")?.takeIf { it.startsWith(GITHUB) } ?: continue
            val digest = a.text("digest")?.lowercase()?.removePrefix("sha256:")?.takeIf { HEX_64.matches(it) }
            return Release(version, page, url, name, a.optLong("size", -1L), digest)
        }
        return null
    }
}
