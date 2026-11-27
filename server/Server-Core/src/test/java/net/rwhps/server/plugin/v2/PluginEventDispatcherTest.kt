package net.rwhps.server.plugin.v2

import net.rwhps.server.plugin.PluginRuntimeMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

class PluginEventDispatcherTest {
    @Test
    fun `full plugin queue drops work without caller runs`() {
        val metrics = PluginRuntimeMetrics()
        val dispatcher = PluginEventDispatcher("slow.plugin", metrics, queueCapacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val rejectedTaskRan = AtomicBoolean(false)

        try {
            assertTrue(dispatcher.dispatch {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            })
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertTrue(dispatcher.dispatch { secondCompleted.countDown() })
            assertFalse(dispatcher.dispatch { rejectedTaskRan.set(true) })
            assertFalse(rejectedTaskRan.get())

            releaseFirst.countDown()
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(2, metrics.eventNotificationCount())
            assertEquals(1, metrics.droppedEventNotificationCount())
            assertEquals(1, metrics.eventQueueCapacity())
            assertTrue(metrics.eventQueueMaxDepth() >= 1)
            assertEquals("DEGRADED", metrics.healthSnapshot().level)
            assertTrue(metrics.healthSnapshot().reasons.contains("event_dropped"))
        } finally {
            releaseFirst.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `slow plugin does not block a separate plugin dispatcher`() {
        val slow = PluginEventDispatcher("slow.plugin", PluginRuntimeMetrics(), queueCapacity = 2)
        val fast = PluginEventDispatcher("fast.plugin", PluginRuntimeMetrics(), queueCapacity = 2)
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val fastCompleted = CountDownLatch(1)

        try {
            assertTrue(slow.dispatch {
                slowStarted.countDown()
                releaseSlow.await(5, TimeUnit.SECONDS)
            })
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
            assertTrue(fast.dispatch { fastCompleted.countDown() })
            assertTrue(fastCompleted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseSlow.countDown()
            slow.close()
            fast.close()
        }
    }

    @Test
    fun `one plugin preserves submission order on a daemon worker`() {
        val dispatcher = PluginEventDispatcher("ordered.plugin", PluginRuntimeMetrics(), queueCapacity = 32)
        val completed = CountDownLatch(20)
        val order = CopyOnWriteArrayList<Int>()
        val daemonWorker = AtomicBoolean(false)

        try {
            repeat(20) { value ->
                assertTrue(dispatcher.dispatch {
                    daemonWorker.set(Thread.currentThread().isDaemon)
                    order.add(value)
                    completed.countDown()
                })
            }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals((0 until 20).toList(), order.toList())
            assertTrue(daemonWorker.get())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `slow callback produces warning health and failure degrades health`() {
        val metrics = PluginRuntimeMetrics()

        assertTrue(metrics.recordEventCallbackSuccess(101L * 1_000_000L))
        assertEquals("WARN", metrics.healthSnapshot().level)
        assertTrue(metrics.healthSnapshot().reasons.contains("event_callback_slow"))
        assertEquals(101_000L, metrics.lastEventCallbackDurationMicros())

        metrics.recordEventCallbackFailure(1_000L)
        assertEquals("DEGRADED", metrics.healthSnapshot().level)
        assertTrue(metrics.healthSnapshot().reasons.contains("event_callback_failed"))
    }
}
