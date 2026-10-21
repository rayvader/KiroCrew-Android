package io.github.rayvader.kirocrew.core

import org.json.JSONException
import org.json.JSONObject

/** A Kiro Crew update the gateway itself reports through GET /api/update/check. */
data class GatewayUpdate(val latest: String, val current: String)

object GatewayUpdateParser {
    const val PATH = "/api/update/check"

    /** Where the dashboard shows the update and its Update button. */
    const val ABOUT_PATH = "/settings/about"

    private val SAFE_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,39}")

    /**
     * An update only when the gateway's check SUCCEEDED and says one is available.
     * A failed, deferred or unchecked comparison is not an answer, so it is null
     * here too, the same as "up to date". The version must look like a version,
     * because it goes into a notification.
     */
    fun parse(json: String): GatewayUpdate? = try {
        val o = JSONObject(json)
        if (o.optString("check_status") != "succeeded") {
            null
        } else if (!o.optBoolean("update_available", false) || o.isNull("update_available")) {
            null
        } else {
            val latest = version(o, "latest_version_display") ?: version(o, "latest_version")
            latest?.let { GatewayUpdate(it, version(o, "current_version").orEmpty()) }
        }
    } catch (e: JSONException) {
        null
    }

    private fun version(o: JSONObject, key: String): String? =
        o.text(key)?.trim()?.removePrefix("v")?.takeIf { SAFE_VERSION.matches(it) }
}
