/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

/**
 * Keeps requests to one master server from arriving in a burst when several
 * public-list entries are maintained by the same process.
 *
 * The clock and sleeper are injectable so the timing policy can be checked
 * without starting a server or waiting in a test.
 */
internal class UpListRequestThrottle(
    private val minimumIntervalMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) }
) {
    private var nextAllowedAtMillis = 0L

    init {
        require(minimumIntervalMillis >= 0L) { "minimumIntervalMillis must not be negative" }
    }

    @Synchronized
    fun awaitPermit() {
        val now = nowMillis()
        val waitMillis = nextAllowedAtMillis - now
        if (waitMillis > 0L) {
            try {
                sleepMillis(waitMillis)
            } catch (_: InterruptedException) {
                // Do not turn an interrupted maintenance request into a failed server task.
                Thread.currentThread().interrupt()
            }
        }
        nextAllowedAtMillis = nowMillis() + minimumIntervalMillis
    }

    @Synchronized
    fun reset() {
        nextAllowedAtMillis = 0L
    }
}
