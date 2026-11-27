/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class UpListRequestThrottleTest {
    @Test
    fun firstRequestIsImmediateAndLaterRequestsObserveMinimumInterval() {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val throttle = UpListRequestThrottle(
            minimumIntervalMillis = 350L,
            nowMillis = { now },
            sleepMillis = { wait ->
                waits += wait
                now += wait
            }
        )

        throttle.awaitPermit()
        throttle.awaitPermit()
        throttle.awaitPermit()

        assertEquals(listOf(350L, 350L), waits)
    }

    @Test
    fun resetAllowsImmediateRequestAfterAReconfiguration() {
        var now = 2_000L
        val waits = mutableListOf<Long>()
        val throttle = UpListRequestThrottle(
            minimumIntervalMillis = 500L,
            nowMillis = { now },
            sleepMillis = { wait ->
                waits += wait
                now += wait
            }
        )

        throttle.awaitPermit()
        throttle.reset()
        throttle.awaitPermit()

        assertTrue(waits.isEmpty())
    }
}
