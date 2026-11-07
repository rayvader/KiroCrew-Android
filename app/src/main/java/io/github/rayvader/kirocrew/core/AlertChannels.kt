package io.github.rayvader.kirocrew.core

import java.util.Locale

/** The Bot a feed note came from: the feed's `member` object. */
data class AlertMember(val slug: String, val name: String)

/** Which Android notification channel group a channel sits in. */
enum class ChannelGroup { BOTS, KIRO_CREW }

/**
 * Where one feed note goes. [id] is the Android channel id. [botName] is set only for a Bot's
 * own channel (`bot_<slug>`), whose name comes from the feed; the fixed channels take their
 * names from the app's strings.
 */
data class AlertRoute(val id: String, val group: ChannelGroup, val botName: String? = null)

/**
 * Picks the notification channel for each feed note, so sound and mute can be set per Bot and
 * per kind in Android settings. Plain Kotlin.
 *
 *   member present          -> bot_<slug>, named after the Bot     (Bots group)
 *   system.agent            -> news: News and agents               (Kiro Crew group)
 *   system.monitor          -> watches: Watches
 *   chat.* / session.*      -> chat: Chat updates
 *   anything else, or none  -> alerts: Dashboard alerts (the old channel)
 */
object AlertChannels {
    /** The old channel every note used. Kept as the fallback so its settings are kept too. */
    const val ALERTS = "alerts"
    const val CHAT = "chat"
    const val NEWS = "news"
    const val WATCHES = "watches"
    const val BOT_PREFIX = "bot_"

    private const val MAX_SLUG = 60
    private const val MAX_NAME = 40

    fun route(channel: String?, member: AlertMember?): AlertRoute {
        val slug = member?.slug?.let(::cleanSlug)
        if (member != null && !slug.isNullOrEmpty()) {
            return AlertRoute(BOT_PREFIX + slug, ChannelGroup.BOTS, displayName(member.name, member.slug))
        }
        val c = channel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = when {
            c == "system.agent" -> NEWS
            c == "system.monitor" -> WATCHES
            c.startsWith("chat.") || c.startsWith("session.") -> CHAT
            else -> ALERTS
        }
        return AlertRoute(id, ChannelGroup.KIRO_CREW)
    }

    /** A slug made safe for a channel id: lower case, letters, digits, `-` and `_` only. */
    fun cleanSlug(slug: String): String =
        slug.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_', '-')
            .take(MAX_SLUG)

    /**
     * The channel's name. The feed's `member.name` as given ("Test Butler", "Trip Helper"). The feed
     * often carries the slug there too ("trip-helper"): a name with no spaces and no capitals
     * that is made of `-` or `_` words is turned into words ("Trip Helper"). Empty: from the slug.
     */
    fun displayName(name: String?, slug: String): String {
        val n = name?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        val source = n.ifEmpty { slug.trim() }
        val looksLikeSlug = source.isNotEmpty() && source == source.lowercase(Locale.ROOT) &&
            !source.contains(' ') && Regex("[a-z0-9]+([-_][a-z0-9]+)*").matches(source)
        val out = if (looksLikeSlug) {
            source.split('-', '_').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        } else {
            source
        }
        return out.take(MAX_NAME).ifEmpty { "Bot" }
    }
}
