package io.github.rayvader.kirocrew.core

import java.net.URLDecoder
import java.net.URLEncoder

/** One Kiro Crew dashboard the app can connect to. */
data class Profile(val id: String, val name: String, val baseUrl: String)

/** Stores profiles as one line each, fields URL-encoded and split by '|'. */
object ProfileCodec {
    private const val SEP = "|"

    fun encode(profiles: List<Profile>): String =
        profiles.joinToString("\n") { p -> listOf(p.id, p.name, p.baseUrl).joinToString(SEP) { enc(it) } }

    fun decode(raw: String?): List<Profile> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size != 3) return@mapNotNull null
            val id = dec(parts[0]) ?: return@mapNotNull null
            val name = dec(parts[1]) ?: return@mapNotNull null
            val url = dec(parts[2]) ?: return@mapNotNull null
            if (id.isBlank() || url.isBlank()) null else Profile(id, name.ifBlank { url }, url)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
