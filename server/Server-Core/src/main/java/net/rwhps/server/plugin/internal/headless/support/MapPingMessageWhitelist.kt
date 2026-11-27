package net.rwhps.server.plugin.internal.headless.support

/** Recognizes the bounded text envelope used by map-ping compatible clients. */
internal object MapPingMessageWhitelist {
    private const val MAX_ENCODED_LENGTH = 512
    private val mapPingAction = Regex(
        "(?:^|[^A-Za-z0-9_])c_6_[A-Za-z][A-Za-z0-9_]{0,63}(?=$|[^A-Za-z0-9_])",
        RegexOption.IGNORE_CASE
    )

    fun isWhitelisted(message: String): Boolean {
        val normalized = message.trim()
        return normalized.length <= MAX_ENCODED_LENGTH && mapPingAction.containsMatchIn(normalized)
    }
}
