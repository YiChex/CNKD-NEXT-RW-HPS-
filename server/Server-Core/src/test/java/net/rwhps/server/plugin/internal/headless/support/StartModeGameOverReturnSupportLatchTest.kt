/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * P0-4 regression tests: the exhausted reset-retry latch must not wedge the room in SETTLING
 * forever, while still rate-limiting recovery so that it cannot become a reset storm.
 *
 * These drive the pure decision helpers directly. The end-to-end glue in
 * [StartModeGameOverReturnSupport.recoverStaleLifecycleIfNeeded] is:
 *
 *   if (shouldBlockStaleSettlingRecovery(...)) return true          // still latched -> defer
 *   if (settleAge >= staleSettlingRecoveryMillis) {
 *       if (staleLifecycleRecoveryInFlight) return true
 *       val released = tryConsumeResetRetryExhaustedRecovery()      // one release per window
 *       if (activeResetRetryExhausted && !released) return true     // refused -> defer
 *       ... room.gr() ...                                           // released -> recycle
 *   }
 */
internal class StartModeGameOverReturnSupportLatchTest {
    private val grace = StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryGraceMillis()

    @BeforeEach
    fun resetState() {
        StartModeGameOverReturnSupport.resetResetRetryStateForTests()
    }

    // ---------------------------------------------------------------- pure decision

    @Test
    fun unarmedLatchNeverBlocksRecovery() {
        // Normal path: latch not armed => never blocks, and this is the pre-fix behaviour too.
        assertFalse(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = false,
                resetRetryExhaustedAtMillis = 0L,
                nowMillis = 1_000_000L,
                graceMillis = grace
            )
        )
    }

    @Test
    fun armedLatchBlocksWithinGraceWindow() {
        // Pre-fix behaviour that must be preserved: right after exhaustion we do NOT retry.
        val armedAt = 5_000_000L
        assertTrue(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = true,
                resetRetryExhaustedAtMillis = armedAt,
                nowMillis = armedAt, // just armed
                graceMillis = grace
            )
        )
        assertTrue(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = true,
                resetRetryExhaustedAtMillis = armedAt,
                nowMillis = armedAt + grace - 1,
                graceMillis = grace
            )
        )
    }

    @Test
    fun armedLatchReleasesAtAndAfterGraceWindow() {
        // The P0-4 fix: the latch is no longer permanent. This is the exact assertion that
        // fails on the old `if (activeResetRetryExhausted) return true`.
        val armedAt = 5_000_000L
        assertFalse(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = true,
                resetRetryExhaustedAtMillis = armedAt,
                nowMillis = armedAt + grace, // boundary
                graceMillis = grace
            )
        )
        assertFalse(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = true,
                resetRetryExhaustedAtMillis = armedAt,
                nowMillis = armedAt + grace * 10,
                graceMillis = grace
            )
        )
    }

    @Test
    fun armedLatchWithMissingTimestampDoesNotWedgeForever() {
        // Defensive: an armed latch with no stamp must expire, never block permanently.
        assertFalse(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = true,
                resetRetryExhaustedAtMillis = 0L,
                nowMillis = 1_000_000L,
                graceMillis = grace
            )
        )
    }

    // ---------------------------------------------------------------- one-release-per-window

    @Test
    fun unarmedLatchConsumesNothing() {
        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery())
        assertEquals(0L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
        assertFalse(StartModeGameOverReturnSupport.isResetRetryExhausted())
    }

    @Test
    fun withinGraceWindowNoReleaseIsGranted() {
        val now = 7_000_000L
        StartModeGameOverReturnSupport.armResetRetryExhaustedForTests(now)

        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(now + 1))
        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(now + grace - 1))
        assertEquals(0L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
        assertTrue(StartModeGameOverReturnSupport.isResetRetryExhausted())
    }

    @Test
    fun pastGraceWindowExactlyOneReleaseIsGrantedThenReArmed() {
        val now = 7_000_000L
        StartModeGameOverReturnSupport.armResetRetryExhaustedForTests(now)
        val releaseAt = now + grace

        // First call past the window: released.
        assertTrue(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(releaseAt))
        assertEquals(1L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())

        // Latch is re-armed, so an immediate retry is refused -> no reset storm.
        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(releaseAt + 1))
        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(releaseAt + grace - 1))
        assertEquals(1L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
        assertTrue(StartModeGameOverReturnSupport.isResetRetryExhausted())

        // Only after another full window may the next single release happen.
        assertTrue(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(releaseAt + grace))
        assertEquals(2L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(releaseAt + grace + 1))
        assertEquals(2L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
    }

    @Test
    fun releasesAreRateLimitedAcrossManyWindowsNotUnbounded() {
        // 100 windows must yield exactly 100 releases (1 per window), never more.
        val start = 1_000_000L
        StartModeGameOverReturnSupport.armResetRetryExhaustedForTests(start)
        var releases = 0
        for (window in 1..100) {
            val at = start + grace * window
            // Hammer several times inside the window; only the first may pass.
            repeat(5) { attempt ->
                if (StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(at + attempt)) {
                    releases++
                }
            }
        }
        assertEquals(100L, releases.toLong())
        assertEquals(100L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
    }

    // ---------------------------------------------------------------- normal path invariance

    @Test
    fun successfulResetClearsLatchAndStopsFurtherReleases() {
        // onRoomResetComplete() clears the latch; afterwards recovery must be entirely
        // unconditional (the pre-existing, non-exhausted behaviour).
        val now = 3_000_000L
        StartModeGameOverReturnSupport.armResetRetryExhaustedForTests(now)
        StartModeGameOverReturnSupport.clearResetRetryExhaustedForTests()

        assertFalse(StartModeGameOverReturnSupport.isResetRetryExhausted())
        assertFalse(StartModeGameOverReturnSupport.tryConsumeResetRetryExhaustedRecovery(now + grace * 5))
        assertEquals(0L, StartModeGameOverReturnSupport.resetRetryExhaustedRecoveryCount())
        assertFalse(
            StartModeGameOverReturnSupport.shouldBlockStaleSettlingRecovery(
                resetRetryExhausted = StartModeGameOverReturnSupport.isResetRetryExhausted(),
                resetRetryExhaustedAtMillis = now,
                nowMillis = now,
                graceMillis = grace
            )
        )
    }

    @Test
    fun graceWindowMatchesDocumentedConstant() {
        // 60s per the P0-4 design; guards against an accidental constant change.
        assertEquals(60_000L, grace)
    }
}
