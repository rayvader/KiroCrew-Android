package io.github.rayvader.kirocrew.core

/**
 * URL helpers that run on the plain JVM so they can be unit-tested.
 * A lenient regex is used instead of java.net.URI because dashboard links can
 * carry characters URI rejects, and a rejected same-server link must not be
 * mistaken for an external one.
 */
object UrlTools {

    data class Parts(
        val scheme: String,
        val host: String,
        val port: Int?,
        val path: String,
        val query: String,
        val fragment: String,
    )

    data class QrTarget(val baseUrl: String, val openUrl: String?)

    private val URL_RE = Regex(
        "([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^@/?#]*@)?(\\[[^\\]/]+]|[^/?#:@]+)(?::(\\d{1,5}))?([^?#]*)(\\?[^#]*)?(#.*)?"
    )

    fun parse(url: String): Parts? {
        val m = URL_RE.matchEntire(url.trim()) ?: return null
        val portText = m.groupValues[3]
        val port = if (portText.isEmpty()) null else portText.toIntOrNull()
        if (port != null && port !in 1..65535) return null
        val path = m.groupValues[4]
        if (path.isNotEmpty() && !path.startsWith("/")) return null
        return Parts(
            scheme = m.groupValues[1].lowercase(),
            host = m.groupValues[2].lowercase(),
            port = port,
            path = path,
            query = m.groupValues[5],
            fragment = m.groupValues[6],
        )
    }

    /** Turns what a person types ("host:5476", "kiro.example.com/") into a base URL, or null. */
    fun normalizeServerUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) {
            s = (if (prefersHttp(s)) "http://" else "https://") + s
        }
        val p = parse(s) ?: return null
        if (p.scheme != "http" && p.scheme != "https") return null
        if (p.host.isEmpty()) return null
        val port = p.port?.let { ":$it" } ?: ""
        return "${p.scheme}://${p.host}$port${p.path.trimEnd('/')}"
    }

    /** No scheme typed: an explicit port, localhost, an IP or .local means plain HTTP (e.g. over Tailscale). */
    fun prefersHttp(noScheme: String): Boolean {
        val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
        val host = authority.substringBefore(':').lowercase()
        return authority.contains(':') || host == "localhost" || isIpv4(host) || host.endsWith(".local")
    }

    fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
        }
    }

    fun origin(url: String): String? {
        val p = parse(url) ?: return null
        return "${p.scheme}://${p.host}${p.port?.let { ":$it" } ?: ""}"
    }

    private fun effectivePort(p: Parts): Int = p.port ?: when (p.scheme) {
        "https", "wss" -> 443
        "http", "ws" -> 80
        else -> -1
    }

    /** True when [url] points at the same host and port as the server [base]. */
    fun isSameServer(base: String, url: String): Boolean {
        val a = parse(base) ?: return false
        val b = parse(url) ?: return false
        if (b.scheme != "http" && b.scheme != "https") return false
        return a.host == b.host && effectivePort(a) == effectivePort(b)
    }

    fun buildUrl(base: String, path: String?): String {
        val p = path?.trim().orEmpty()
        if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return p
        val b = base.trimEnd('/')
        if (p.isEmpty() || p == "/") return "$b/"
        return if (p.startsWith("/")) b + p else "$b/$p"
    }

    fun pathOf(url: String): String = parse(url)?.path?.ifEmpty { "/" } ?: "/"

    /** Plain HTTP to a host that is not loopback, Tailscale or a private network. */
    fun isUnencryptedPublic(url: String): Boolean {
        val p = parse(url) ?: return false
        if (p.scheme != "http") return false
        val h = p.host
        if (h == "localhost" || h.endsWith(".ts.net") || h.endsWith(".local") ||
            h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")
        ) return false
        if (isIpv4(h)) {
            val o = h.split('.').map { it.toInt() }
            val private = o[0] == 10 || o[0] == 127 ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 100 && o[1] in 64..127) ||
                (o[0] == 169 && o[1] == 254)
            return !private
        }
        if (h.startsWith("[")) return false
        return true
    }

    /**
     * Reads a scanned QR code. A full link (for example a sign-in link from the
     * dashboard's "Connect your phone") keeps its origin as the server and is
     * opened once so the login cookie gets set.
     */
    fun parseQr(text: String): QrTarget? {
        val t = text.trim()
        if (t.contains("://")) {
            val p = parse(t) ?: return null
            if (p.scheme != "http" && p.scheme != "https") return null
            val base = origin(t) ?: return null
            val hasMore = p.path.trimEnd('/').isNotEmpty() || p.query.isNotEmpty() || p.fragment.isNotEmpty()
            return QrTarget(base, if (hasMore) t else null)
        }
        val base = normalizeServerUrl(t) ?: return null
        return QrTarget(base, null)
    }
}
