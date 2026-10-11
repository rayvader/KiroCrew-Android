package io.github.rayvader.kirocrew.core

/** Dashboard pages the bottom bar, start page and launcher shortcuts can point at. */
enum class Shortcut(val key: String, val path: String, val matchPrefix: String) {
    CHAT("chat", "/chat", "/chat"),
    SCHEDULE("schedule", "/schedule", "/schedule"),
    ARTIFACTS("artifacts", "/artifacts", "/artifacts"),
    NOTIFICATIONS("notifications", "/notifications", "/notifications"),
    CUSTOMIZE("customize", "/capabilities", "/capabilities"),
    APPS("apps", "/apps", "/apps"),
    SETTINGS("settings", "/settings/overview", "/settings"),
    LOGS("logs", "/logs", "/logs"),
    DEVELOPER("developer", "/developer", "/developer");

    companion object {
        val DEFAULT_BAR = listOf(CHAT, SCHEDULE, ARTIFACTS, NOTIFICATIONS, SETTINGS)

        fun fromKey(key: String?): Shortcut? = entries.firstOrNull { it.key == key }

        /** The shortcut whose page contains [path], longest prefix first. */
        fun matching(path: String, among: List<Shortcut>): Shortcut? =
            among.filter { path == it.matchPrefix || path.startsWith(it.matchPrefix + "/") || path.startsWith(it.matchPrefix + "?") }
                .maxByOrNull { it.matchPrefix.length }
    }
}
