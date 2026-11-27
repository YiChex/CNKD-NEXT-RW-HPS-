package net.rwhps.server.plugin.internal.headless.support

internal object StartCountdownPolicy {
    fun remainingSeconds(deadlineNanos: Long, nowNanos: Long): Int {
        val remaining = deadlineNanos - nowNanos
        return if (remaining <= 0L) 0 else ((remaining + 999_999_999L) / 1_000_000_000L).toInt()
    }
}
