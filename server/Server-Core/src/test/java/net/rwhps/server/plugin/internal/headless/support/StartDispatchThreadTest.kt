package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.LogCore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Behavioural coverage for the START-FREEZE fix.
 *
 * A1 audit finding: the round's most important fix had ZERO tests -- the existing wiring tests only
 * assert constant-pool strings, so deleting the real dispatch would still leave them green.
 *
 * These tests were validated by MUTATION: replacing the dedicated-thread dispatch with an inline
 * `task.run()` (i.e. reintroducing the original bug) must make them fail. An earlier version of this
 * file passed under that mutation and was therefore worthless; that is why each test below now
 * asserts an observable consequence of the dispatch rather than a mere absence of blocking.
 *
 * The bug: `/start` was handled on a Netty worker and requestStart blocks the calling thread inside
 * suspendMainThreadOperations{ multiplayerStart() } for up to 30s (+30s). Production runs
 * nettyWorkerThreadsOverride=1, so blocking that single worker froze every player's packet handling.
 */
internal class StartDispatchThreadTest {

    private val dispatchThreadName = "StartMode-Start-Request"

    /**
     * Hold the real start worker at its warning write until assertions finish. Without this,
     * a healthy no-module request can exit before thread enumeration sees it, and the 24
     * callers can legitimately win successive dispatches rather than compete for one in flight.
     * Initialize Data before Log to keep this test's worker from bootstrapping global state.
     */
    private fun withPendingDispatch(assertions: () -> Unit) {
        Data.core
        val log = Log
        LogCore::class.java.getDeclaredField("errorLogLock").apply { isAccessible = true }.get(log).let { lock ->
            try {
                synchronized(lock) { assertions() }
            } finally {
                dispatchThreads().forEach { it.join(5_000L) }
            }
        }
    }

    private fun dispatchThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == dispatchThreadName && it.isAlive }

    /** Wait until a dispatch thread has existed, then report whether one is still alive. */
    private fun awaitDispatchThread(timeoutMillis: Long = 2_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (dispatchThreads().isNotEmpty()) {
                return true
            }
            Thread.sleep(2)
        }
        return false
    }

    /**
     * The decisive property: dispatching must actually CREATE a dedicated thread.
     *
     * This is what the inline mutation breaks -- `task.run()` still returns true and still completes
     * quickly, so a test that only measured elapsed time (or only checked the return value) would
     * pass while the network thread was being blocked.
     */
    @Test
    fun dispatchCreatesADedicatedThreadInsteadOfRunningInline() = withPendingDispatch {
        StartModeAutoStartSupport.resetDispatchForTest()
        val dispatched = StartModeAutoStartSupport.requestStartOffNetworkThread(null, "test")
        assertTrue(dispatched, "a single start request must be accepted for dispatch")
        assertTrue(
            awaitDispatchThread(),
            "dispatch must run on the dedicated '$dispatchThreadName' thread; " +
                "no such thread appeared, which means the work ran inline on the caller"
        )
    }

    /**
     * A1-01: the dispatch CAS must bound concurrency. Without it, N concurrent requests each spawn a
     * thread (the earlier bug produced 200 threads for 200 requests).
     *
     * HONEST NOTE ON WHAT THIS DOES AND DOES NOT PROVE (mutation-tested):
     * this test catches a MISSING or broken dispatch CAS, but it does NOT catch the inline-dispatch
     * regression -- replacing the dedicated thread with `task.run()` leaves it green, because inline
     * execution still accepts exactly one caller and rejects the rest. The test above
     * (dispatchCreatesADedicatedThreadInsteadOfRunningInline) is the one that fails under that
     * mutation. Both properties matter and they are different; asserting the concurrency bound here
     * is not a substitute for the thread-identity check, and vice versa.
     *
     * Asserted via the RETURN VALUE rather than live-thread counting: exactly one caller may win the
     * CAS, the rest are rejected. Live-thread counting is racy because dispatched threads exit
     * quickly, and it was exactly that raciness that made an earlier version of this test pass under
     * mutation.
     */
    @Test
    fun concurrentDispatchesAllowOnlyOneInFlight() = withPendingDispatch {
        // Both flags are process-level singletons, so a thread dispatched by another test can still
        // hold the slot. Reset first; otherwise every caller here is rejected (measured
        // accepted=0 rejected=24) and the assertion would be testing test-ordering, not the CAS.
        StartModeAutoStartSupport.resetDispatchForTest()
        val attempts = 24
        val ready = CountDownLatch(attempts)
        val go = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val rejected = java.util.concurrent.atomic.AtomicInteger(0)

        val callers = (0 until attempts).map {
            Thread {
                ready.countDown()
                go.await()
                if (StartModeAutoStartSupport.requestStartOffNetworkThread(null, "test")) {
                    accepted.incrementAndGet()
                } else {
                    rejected.incrementAndGet()
                }
            }.apply { isDaemon = true; start() }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "callers failed to start")
        go.countDown()
        callers.forEach { it.join(3_000L) }

        assertEquals(
            1,
            accepted.get(),
            "exactly one concurrent start request may be dispatched; " +
                "accepted=${accepted.get()} rejected=${rejected.get()} " +
                "(more than one means the dispatch CAS is missing and every request spawns a thread)"
        )
        assertEquals(attempts - 1, rejected.get(), "every loser must be rejected, not silently dispatched")
    }
}
