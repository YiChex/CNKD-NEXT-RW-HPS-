package net.rwhps.server.util.game

/** Formatting policy for server generated system notices. */
enum class SystemMessageTextMode {
    DEFAULT,
    HELP,
    WINNER_RESULT,
    CONFIG_NOTICE
}

/** Normalizes server generated system notices without changing player chat. */
internal object SystemMessageTextSupport {
    private val whitespace = Regex("\\s+")

    fun normalize(text: String, mode: SystemMessageTextMode = SystemMessageTextMode.DEFAULT): String {
        return when (mode) {
            // Keep server-generated punctuation such as surrender progress fractions and separators.
            // Collapse only incidental whitespace so notices remain single-line and readable.
            SystemMessageTextMode.DEFAULT -> whitespace.replace(text, " ").trim()
            // Help is a command reference. Keep prefixes, parameters, punctuation and line breaks intact.
            SystemMessageTextMode.HELP -> text.trim()
            // Winner names and grouping markers are client-visible content and retain punctuation.
            SystemMessageTextMode.WINNER_RESULT -> whitespace.replace(text, " ").trim()
            // Administrator configured notices are player-facing copy. Preserve their punctuation and layout.
            SystemMessageTextMode.CONFIG_NOTICE -> text.trim()
        }
    }
}
