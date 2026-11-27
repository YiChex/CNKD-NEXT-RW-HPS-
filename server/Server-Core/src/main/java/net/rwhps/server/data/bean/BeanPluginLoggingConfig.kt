/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

/**
 * Observability controls for V2 plugin logs. These settings are intentionally
 * independent from the global core log threshold.
 */
data class BeanPluginLoggingConfig(
    val consoleLevel: String = LEVEL_INFO,
    val fileLevel: String = LEVEL_INFO,
    val infoLinesPerMinutePerPlugin: Int = DEFAULT_INFO_LINES_PER_MINUTE,
    val maxMessageLength: Int = DEFAULT_MAX_MESSAGE_LENGTH,
    val maxFileSizeMb: Int = DEFAULT_MAX_FILE_SIZE_MB,
    val retainDays: Int = DEFAULT_RETAIN_DAYS
) {
    fun normalized(): BeanPluginLoggingConfig {
        return copy(
            consoleLevel = normalizeLevel(consoleLevel),
            fileLevel = normalizeLevel(fileLevel),
            infoLinesPerMinutePerPlugin = infoLinesPerMinutePerPlugin.coerceIn(MIN_INFO_LINES_PER_MINUTE, MAX_INFO_LINES_PER_MINUTE),
            maxMessageLength = maxMessageLength.coerceIn(MIN_MESSAGE_LENGTH, MAX_MESSAGE_LENGTH),
            maxFileSizeMb = maxFileSizeMb.coerceIn(MIN_FILE_SIZE_MB, MAX_FILE_SIZE_MB),
            retainDays = retainDays.coerceIn(MIN_RETAIN_DAYS, MAX_RETAIN_DAYS)
        )
    }

    companion object {
        const val LEVEL_OFF = "OFF"
        const val LEVEL_ERROR = "ERROR"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_INFO = "INFO"

        const val DEFAULT_INFO_LINES_PER_MINUTE = 120
        const val DEFAULT_MAX_MESSAGE_LENGTH = 2_048
        const val DEFAULT_MAX_FILE_SIZE_MB = 8
        const val DEFAULT_RETAIN_DAYS = 7

        const val MIN_INFO_LINES_PER_MINUTE = 10
        const val MAX_INFO_LINES_PER_MINUTE = 600
        const val MIN_MESSAGE_LENGTH = 256
        const val MAX_MESSAGE_LENGTH = 8_192
        const val MIN_FILE_SIZE_MB = 1
        const val MAX_FILE_SIZE_MB = 64
        const val MIN_RETAIN_DAYS = 1
        const val MAX_RETAIN_DAYS = 30

        private val LEVELS = setOf(LEVEL_OFF, LEVEL_ERROR, LEVEL_WARN, LEVEL_INFO)

        fun normalizeLevel(rawValue: String?): String {
            val normalized = rawValue.orEmpty().trim().uppercase()
            return if (normalized in LEVELS) normalized else LEVEL_INFO
        }
    }
}
