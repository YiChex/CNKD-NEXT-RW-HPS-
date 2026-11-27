/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core.thread

import net.rwhps.timetask.task.TimeTaskManage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class TimeTaskCountdownRescheduleTest {
    @Test
    fun countdownCanReplaceItselfWithTheSameJobKey() {
        val scheduler = TimeTaskManage()
        val callbacks = AtomicInteger(0)
        val completed = CountDownLatch(2)
        lateinit var callback: Runnable

        callback = Runnable {
            val invocation = callbacks.incrementAndGet()
            completed.countDown()
            if (invocation == 1) {
                scheduler.remove(TASK_NAME, TASK_GROUP)
                scheduler.addCountdown(
                    TASK_NAME,
                    TASK_GROUP,
                    "replacement",
                    System.currentTimeMillis() + 50L,
                    callback
                )
            }
        }

        try {
            scheduler.addCountdown(
                TASK_NAME,
                TASK_GROUP,
                "initial",
                System.currentTimeMillis() + 50L,
                callback
            )

            assertTrue(completed.await(3L, TimeUnit.SECONDS))
            assertEquals(2, callbacks.get())
            awaitTaskRemoval(scheduler)
            assertFalse(scheduler.contains(TASK_NAME, TASK_GROUP))
        } finally {
            scheduler.shutdownNow()
        }
    }

    private fun awaitTaskRemoval(scheduler: TimeTaskManage) {
        val deadline = System.currentTimeMillis() + 1_000L
        while (scheduler.contains(TASK_NAME, TASK_GROUP) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
    }

    companion object {
        private const val TASK_NAME = "countdown-reschedule-test"
        private const val TASK_GROUP = "test"
    }
}
