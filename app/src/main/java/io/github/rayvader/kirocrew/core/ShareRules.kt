package io.github.rayvader.kirocrew.core

/** Rules for what other apps share into the chat box. No Android types, so it can be unit tested. */
object ShareRules {
    const val MAX_FILES = 10
    const val MAX_BYTES = 25L * 1024 * 1024
    const val MAX_TEXT = 20_000

    /** One draft from a shared subject and text. The subject goes first, unless the text already has it. */
    fun draft(subject: String?, text: String?): String {
        val s = subject?.trim().orEmpty()
        val t = text?.trim().orEmpty()
        val joined = when {
            s.isEmpty() -> t
            t.isEmpty() -> s
            t.contains(s) -> t
            else -> "$s\n$t"
        }
        return joined.take(MAX_TEXT)
    }

    /**
     * Only content:// links from other apps. A file:// link, or one to this app's own
     * providers, could make the app read its own private files (logins) into the chat.
     */
    fun allowedUri(scheme: String?, authority: String?, ownPackage: String): Boolean =
        scheme == "content" &&
            !authority.isNullOrBlank() &&
            authority != ownPackage &&
            !authority.startsWith("$ownPackage.")

    /** A plain file name: no folders, no control characters, never empty. */
    fun fileName(raw: String?, fallback: String): String {
        val base = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' }
            .trim()
        return base.ifEmpty { fallback }.take(120)
    }
}
