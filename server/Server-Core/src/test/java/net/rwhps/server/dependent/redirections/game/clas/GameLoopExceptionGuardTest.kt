package net.rwhps.server.dependent.redirections.game.clas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FIX-LOOP-GUARD: the game loop runs at up to 120 FPS. A persistent per-frame failure must not
 * produce 120 log lines per second -- especially because LogCore now persists WARN+ to disk, so
 * flooding would rotate away genuine ERRORs.
 *
 * These tests deliberately do NOT capture Log output (that would couple them to the logging
 * backend, which the guard is explicitly designed to tolerate failing). They pin the contract that
 * matters and can be asserted without the backend: the guard always swallows non-fatal throwables,
 * always rethrows fatal ones, and counts every swallow.
 */
internal class GameLoopExceptionGuardTest {

    @Test
    fun countsEverySwallowedException() {
        GameLoopExceptionGuard.resetForTest()
        repeat(5) {
            GameLoopExceptionGuard.invoke(null, "Lcom/x;m()V", IllegalStateException("synthetic_$it"))
        }
        assertEquals(5L, GameLoopExceptionGuard.swallowed())
    }

    @Test
    fun swallowsNonFatalThrowablesInsteadOfPropagating() {
        GameLoopExceptionGuard.resetForTest()
        // Must not throw: this is the last line of defence for the game-loop thread.
        GameLoopExceptionGuard.invoke(null, "Lcom/x;m()V", NullPointerException("this.bL is null"))
        assertEquals(1L, GameLoopExceptionGuard.swallowed())
    }

    @Test
    fun rethrowsVirtualMachineErrorSoTheProcessCanStillDie() {
        GameLoopExceptionGuard.resetForTest()
        var rethrown: Throwable? = null
        try {
            GameLoopExceptionGuard.invoke(null, "Lcom/x;m()V", OutOfMemoryError("synthetic_oom"))
        } catch (error: Throwable) {
            rethrown = error
        }
        assertTrue(rethrown is OutOfMemoryError, "VirtualMachineError must not be swallowed")
        // A fatal error must not be counted as a swallowed frame.
        assertEquals(0L, GameLoopExceptionGuard.swallowed())
    }

    @Test
    fun rethrowsThreadDeath() {
        GameLoopExceptionGuard.resetForTest()
        var rethrown: Throwable? = null
        try {
            GameLoopExceptionGuard.invoke(null, "Lcom/x;m()V", ThreadDeath())
        } catch (error: Throwable) {
            rethrown = error
        }
        assertTrue(rethrown is ThreadDeath, "ThreadDeath must not be swallowed")
        assertEquals(0L, GameLoopExceptionGuard.swallowed())
    }

    /**
     * P2-1 (raised by independent verification): swallowing keeps the thread alive, but a body that
     * throws on EVERY frame means no frame ever completes -- and because recordLoopTick() runs at
     * the HEAD of updateAndRender, the loop still looks HEALTHY. Nothing else can see this, so the
     * guard tracks the failure streak itself.
     */
    @Test
    fun aShortBurstOfFailuresIsNotAStall() {
        GameLoopExceptionGuard.resetForTest()
        repeat(10) {
            GameLoopExceptionGuard.invoke(null, "Lcom/x;m()V", IllegalStateException("burst"))
        }
        // A brief burst is just bad frames, not a stalled loop.
        assertTrue(GameLoopExceptionGuard.currentFailureStreakMillis() < 30_000L)
        assertTrue(!GameLoopExceptionGuard.shouldReportNoProgress(5_000L, 5_000L))
    }

    @Test
    fun aLongUninterruptedFailureStreakIsReportedAsNoProgress() {
        GameLoopExceptionGuard.resetForTest()
        val start = 1_000_000L
        // Realistic spacing: a body that throws on every frame fails ~8ms apart at 120 FPS.
        // (Synthesised time, because a real streak needs 30s of wall clock.)
        var lastStreak = 0L
        var t = start
        while (t <= start + 40_000L) {
            lastStreak = GameLoopExceptionGuard.recordFailureForTest(t)
            t += 8L
        }

        assertTrue(lastStreak >= 30_000L, "a 40s uninterrupted streak must be visible, got $lastStreak")
        assertTrue(
            GameLoopExceptionGuard.shouldReportNoProgress(lastStreak, start + 40_000L),
            "a >=30s uninterrupted streak must be classified as no-progress"
        )
    }

    /**
     * A gap between failures means frames DID complete, so the streak must restart. This is the
     * property that stops a few scattered bad frames from ever being reported as a stall.
     */
    @Test
    fun aGapBetweenFailuresRestartsTheStreak() {
        GameLoopExceptionGuard.resetForTest()
        val start = 1_000_000L
        GameLoopExceptionGuard.recordFailureForTest(start)

        // 60s of silence means the loop was completing frames.
        assertEquals(
            0L,
            GameLoopExceptionGuard.currentFailureStreakMillis(start + 60_000L),
            "a >10s gap means the loop made progress, so the streak must be over"
        )
        // A failure after that gap starts a fresh streak rather than resuming the old one.
        assertEquals(0L, GameLoopExceptionGuard.recordFailureForTest(start + 60_000L))
    }

    /**
     * A2-P4: `isNotProgressing()` is what lets `GameFunction` fail fast instead of burning the full
     * 30s timeout on an operation that will never run. It must only become true for a SUSTAINED
     * streak, never for a few bad frames.
     */
    @Test
    fun isNotProgressingOnlyAfterASustainedStreak() {
        GameLoopExceptionGuard.resetForTest()
        val start = 1_000_000L

        // A brief burst of failures is not "not progressing".
        repeat(5) { GameLoopExceptionGuard.recordFailureForTest(start + it * 8L) }
        assertTrue(!GameLoopExceptionGuard.isNotProgressing(start + 40L))

        // After a sustained streak it must report not-progressing, so callers can reject.
        var t = start
        while (t <= start + 40_000L) {
            GameLoopExceptionGuard.recordFailureForTest(t)
            t += 8L
        }
        assertTrue(GameLoopExceptionGuard.isNotProgressing(start + 40_000L))
    }

    /** Once frames complete again (a gap), the state must clear so calls are not rejected forever. */
    @Test
    fun isNotProgressingClearsAfterAFrameCompletes() {
        GameLoopExceptionGuard.resetForTest()
        val start = 1_000_000L
        var t = start
        while (t <= start + 40_000L) {
            GameLoopExceptionGuard.recordFailureForTest(t)
            t += 8L
        }
        assertTrue(GameLoopExceptionGuard.isNotProgressing(start + 40_000L))

        assertTrue(
            !GameLoopExceptionGuard.isNotProgressing(start + 100_000L),
            "a >10s gap means frames completed, so the loop must not stay marked as stalled"
        )
    }
}
