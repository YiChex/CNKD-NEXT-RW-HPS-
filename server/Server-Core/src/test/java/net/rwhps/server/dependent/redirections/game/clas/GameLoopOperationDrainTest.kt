package net.rwhps.server.dependent.redirections.game.clas

import net.rwhps.server.dependent.redirections.game.FPSSleepRedirections
import net.rwhps.server.plugin.internal.headless.support.GameLoopLivenessPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

internal class GameLoopOperationDrainTest {
    @Test
    fun drainRunsQueuedOperationsInOrderIncludingOperationsAddedDuringDrain() {
        val queue = ConcurrentLinkedQueue<Any>()
        val events = mutableListOf<String>()
        queue.add(Runnable {
            events += "first"
            queue.add(Runnable { events += "third" })
        })
        queue.add(Runnable { events += "second" })

        GameLoopOperationDrain.drain(queue)

        assertEquals(listOf("first", "second", "third"), events)
        assertEquals(0, queue.size)
    }

    @Test
    fun drainContinuesAfterANonFatalQueuedOperationFailure() {
        GameLoopOperationDrain.resetHealth()
        val queue = ConcurrentLinkedQueue<Any>()
        val events = mutableListOf<String>()
        queue.add(Runnable { throw IllegalStateException("synthetic_queue_failure") })
        queue.add(Runnable { events += "after-failure" })

        GameLoopOperationDrain.drain(queue)

        assertEquals(listOf("after-failure"), events)
        assertEquals(1L, GameLoopOperationDrain.healthSnapshot().drainFailureCount)
        assertEquals("synthetic_queue_failure", GameLoopOperationDrain.healthSnapshot().lastDrainError)
    }

    @Test
    fun fpsCallbackKeepsTheNoMapQueueDrainWired() {
        val resourceName = FPSSleepRedirections::class.java.name.replace('.', '/') + ".class"
        val classBytes = FPSSleepRedirections::class.java.classLoader
            .getResourceAsStream(resourceName)
            ?.readBytes()
            ?: error("FPSSleepRedirections class bytes are unavailable")
        val constantPoolText = classBytes.toString(Charsets.ISO_8859_1)

        assertTrue(constantPoolText.contains("GameLoopOperationDrain"))
        assertTrue(constantPoolText.contains("drainCurrentEngineQueue"))
    }

    @Test
    fun livenessPolicyDoesNotTreatAnUnobservedLoopAsHealthy() {        assertEquals(
            GameLoopLivenessPolicy.State.NOT_OBSERVED,
            GameLoopLivenessPolicy.evaluate(0L, 100_000L, 30_000L)
        )
        assertEquals(
            GameLoopLivenessPolicy.State.HEALTHY,
            GameLoopLivenessPolicy.evaluate(80_000L, 100_000L, 30_000L)
        )
        assertEquals(
            GameLoopLivenessPolicy.State.STALE,
            GameLoopLivenessPolicy.evaluate(69_999L, 100_000L, 30_000L)
        )
        assertTrue(
            GameLoopLivenessPolicy.shouldRestart(
                GameLoopLivenessPolicy.State.STALE,
                staleSinceMillis = 100_000L,
                nowMillis = 115_000L,
                recoveryGraceMillis = 15_000L
            )
        )
        assertTrue(
            !GameLoopLivenessPolicy.shouldRestart(
                GameLoopLivenessPolicy.State.HEALTHY,
                staleSinceMillis = 100_000L,
                nowMillis = 115_000L,
                recoveryGraceMillis = 15_000L
            )
        )
    }

    /**
     * P0-STALL: the live instance b013 had its game-loop thread killed by an uncaught
     * `NullPointerException: this.bL is null` 36s after startup and then never ticked again.
     * Because recordLoopTick() only runs on the loop, that looked identical to "still starting",
     * so nothing ever reported it. These tests pin the distinction.
     */
    @Test
    fun loopIsNotCalledDeadWhileItIsMerelyStartingUp() {
        GameLoopOperationDrain.resetHealth()
        val expectedSince = System.currentTimeMillis()

        // No grace period elapsed yet -> the loop may legitimately still be starting.
        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 1_000L, graceMillis = 30_000L))
        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 29_999L, graceMillis = 30_000L))
    }

    @Test
    fun loopIsCalledDeadOnceTheGracePeriodElapsesWithZeroTicks() {
        GameLoopOperationDrain.resetHealth()
        val expectedSince = System.currentTimeMillis()

        assertTrue(GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 30_000L, graceMillis = 30_000L))
        assertTrue(GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 600_000L, graceMillis = 30_000L))
    }

    /**
     * P2 (found by independent verification): a loop that RAN and then died must also be reported.
     * The original version of this test asserted the opposite and therefore encoded the bug --
     * "ran then died" returned false forever, so the only symptom was silent 30s timeouts with no
     * dead-loop alert. b013 only matched because it died 33s after startup.
     */
    @Test
    fun loopIsCalledDeadAfterItStoppedTicking() {
        GameLoopOperationDrain.resetHealth()
        GameLoopOperationDrain.recordLoopTick()
        val tickedAt = System.currentTimeMillis()

        // Recently ticked -> still fine.
        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(tickedAt + 1_000L, graceMillis = 30_000L))
        // Silent for longer than the threshold -> dead, even though it DID tick earlier.
        assertTrue(GameLoopOperationDrain.isLoopConfirmedDead(tickedAt + 30_000L, graceMillis = 30_000L))
        assertTrue(GameLoopOperationDrain.isLoopConfirmedDead(tickedAt + 3_600_000L, graceMillis = 30_000L))
    }

    @Test
    fun aFreshlyTickedLoopIsNotReportedAsDead() {
        GameLoopOperationDrain.resetHealth()
        // recordLoopTick() stamps real time, so this asserts against the real clock rather than a
        // synthetic one: immediately after a tick the loop must look alive.
        GameLoopOperationDrain.recordLoopTick()

        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(graceMillis = 30_000L))
        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(
            System.currentTimeMillis() + 1_000L,
            graceMillis = 30_000L
        ))
    }

    @Test
    fun deadLoopReportingIsRateLimited() {
        GameLoopOperationDrain.resetHealth()
        val expectedSince = System.currentTimeMillis()
        val afterGrace = expectedSince + 31_000L

        // First call after the grace period reports; immediate repeats are suppressed but still
        // answer "yes, it is dead" so callers can react on every tick.
        assertTrue(GameLoopOperationDrain.reportDeadLoopIfConfirmed(afterGrace))
        assertTrue(GameLoopOperationDrain.reportDeadLoopIfConfirmed(afterGrace + 1_000L))
        assertTrue(GameLoopOperationDrain.reportDeadLoopIfConfirmed(afterGrace + 59_000L))
        // Before the grace period it must not claim death at all.
        GameLoopOperationDrain.resetHealth()
        val fresh = System.currentTimeMillis()
        assertTrue(!GameLoopOperationDrain.reportDeadLoopIfConfirmed(fresh + 1_000L))
    }

    /**
     * P0-STALL regression guard (raised by the independent verifier): an earlier revision shortened
     * the wait for EVERY not-yet-ticked loop, which would have failed a legitimately slow startup.
     * Startup really does call into the game thread before the first tick -- e.g.
     * StartModeMapLockSupport.applyLockedMapIfNeeded() runs right after startHeadlessServer and
     * reads a map file. A merely-slow start must therefore NOT be treated as dead.
     */
    @Test
    fun aSlowButLiveStartupIsNotTreatedAsADeadLoop() {
        GameLoopOperationDrain.resetHealth()
        val expectedSince = System.currentTimeMillis()

        // Within the grace period: not dead, so callers keep the full wait window and may succeed.
        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 5_000L))
        assertTrue(!GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 20_000L))
        // Only once the grace period has fully elapsed is waiting pointless.
        assertTrue(GameLoopOperationDrain.isLoopConfirmedDead(expectedSince + 30_000L))
    }
}
