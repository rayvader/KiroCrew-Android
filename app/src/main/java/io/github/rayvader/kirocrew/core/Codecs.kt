package io.github.rayvader.kirocrew.core

import java.net.URLDecoder
import java.util.Base64

/** A decoded `data:` URL, used to save blob/data downloads from the dashboard. */
class DataUrl(val mimeType: String, val bytes: ByteArray) {
    companion object {
        fun parse(s: String): DataUrl? {
            if (!s.startsWith("data:", ignoreCase = true)) return null
            val comma = s.indexOf(',')
            if (comma < 0) return null
            val meta = s.substring(5, comma).split(';')
            val mime = meta.first().trim().ifBlank { "text/plain" }
            val isBase64 = meta.drop(1).any { it.trim().equals("base64", ignoreCase = true) }
            val payload = s.substring(comma + 1)
            val bytes = try {
                if (isBase64) {
                    Base64.getMimeDecoder().decode(payload)
                } else {
                    // '+' is a literal in data URLs, not a space.
                    URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
                }
            } catch (e: IllegalArgumentException) {
                return null
            }
            return DataUrl(mime, bytes)
        }
    }
}

object FileNames {
    private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun sanitize(name: String?): String {
        val cleaned = (name ?: "").replace(BAD, "_").trim().trim('.')
        if (cleaned.isEmpty()) return "download"
        return if (cleaned.length <= 120) cleaned else cleaned.take(120)
    }
}

object JsQuote {
    /** Quotes [s] as a JavaScript string literal. */
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
