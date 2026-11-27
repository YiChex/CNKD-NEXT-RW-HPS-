package net.rwhps.server.plugin.v2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PluginCommandDispatcherTest {
    @Test
    fun blockedPluginCommandsDoNotRunOnCallerAndQueueIsBounded() {
        val dispatcher = PluginCommandDispatcher("bounded-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        try {
            val first = dispatcher.submit { thread.set(Thread.currentThread()); entered.countDown(); release.await() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNotSame(Thread.currentThread(), thread.get())
            repeat(32) { assertNotNull(dispatcher.submit {}) }
            assertNull(dispatcher.submit { fail("must not execute rejected command on caller") })
            release.countDown()
            first!!.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); dispatcher.close() }
        assertNull(dispatcher.submit {})
    }
}
