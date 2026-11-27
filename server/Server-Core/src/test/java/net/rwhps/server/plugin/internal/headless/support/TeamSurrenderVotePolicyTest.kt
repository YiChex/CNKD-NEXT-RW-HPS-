package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamSurrenderVotePolicyTest {
    @Test
    fun singleRequiredVoterUsesOriginalSurrenderPath() {
        assertEquals(false, TeamSurrenderVotePolicy.shouldUseTeamVote(0))
        assertEquals(false, TeamSurrenderVotePolicy.shouldUseTeamVote(1))
        assertEquals(true, TeamSurrenderVotePolicy.shouldUseTeamVote(2))
    }

    @Test
    fun `original surrender action is intercepted only for team voting`() {
        assertTrue(
            TeamSurrenderVotePolicy.shouldInterceptOriginalSurrenderAction(
                isSystemAction = true,
                systemAction = 100,
                individualSurrenderEnabled = false
            )
        )
        assertFalse(
            TeamSurrenderVotePolicy.shouldInterceptOriginalSurrenderAction(
                isSystemAction = true,
                systemAction = 100,
                individualSurrenderEnabled = true
            )
        )
        assertFalse(
            TeamSurrenderVotePolicy.shouldInterceptOriginalSurrenderAction(
                isSystemAction = true,
                systemAction = 5,
                individualSurrenderEnabled = false
            )
        )
        assertFalse(
            TeamSurrenderVotePolicy.shouldInterceptOriginalSurrenderAction(
                isSystemAction = false,
                systemAction = 100,
                individualSurrenderEnabled = false
            )
        )
    }

    @Test
    fun `eliminated and disconnected teammates do not block surrender`() {
        val progress = TeamSurrenderVotePolicy.calculate(
            requiredVoterKeys = setOf("alive-a", "alive-b"),
            recordedVoteKeys = setOf("alive-a"),
            eliminatedExemptCount = 1,
            disconnectedExemptCount = 1
        )

        assertEquals(1, progress.votedCount)
        assertEquals(2, progress.requiredCount)
        assertEquals(1, progress.remainingCount)
        assertEquals(1, progress.eliminatedExemptCount)
        assertEquals(1, progress.disconnectedExemptCount)
        assertFalse(progress.complete)
    }

    @Test
    fun `stale votes are excluded from current denominator`() {
        val progress = TeamSurrenderVotePolicy.calculate(
            requiredVoterKeys = setOf("alive-a"),
            recordedVoteKeys = setOf("alive-a", "eliminated-old-vote", "disconnected-old-vote"),
            eliminatedExemptCount = 1,
            disconnectedExemptCount = 1
        )

        assertEquals(1, progress.votedCount)
        assertEquals(1, progress.requiredCount)
        assertEquals(0, progress.remainingCount)
        assertTrue(progress.complete)
    }

    @Test
    fun `empty eligible team is not treated as a new surrender decision`() {
        val progress = TeamSurrenderVotePolicy.calculate(
            requiredVoterKeys = emptySet(),
            recordedVoteKeys = setOf("eliminated-player"),
            eliminatedExemptCount = 1,
            disconnectedExemptCount = 0
        )

        assertEquals(0, progress.votedCount)
        assertEquals(0, progress.requiredCount)
        assertEquals(0, progress.remainingCount)
        assertFalse(progress.complete)
    }

    @Test
    fun `vote completes when an unvoted teammate is eliminated`() {
        val progress = TeamSurrenderVotePolicy.calculate(
            requiredVoterKeys = setOf("alive-a", "alive-b"),
            recordedVoteKeys = setOf("alive-a", "alive-b"),
            eliminatedExemptCount = 1,
            disconnectedExemptCount = 0
        )

        assertEquals(2, progress.votedCount)
        assertEquals(2, progress.requiredCount)
        assertTrue(progress.complete)
    }

    @Test
    fun `partial submission retry targets only players not already enqueued`() {
        val pending = TeamSurrenderVotePolicy.pendingSubmissionKeys(
            requiredVoterKeys = setOf("alive-a", "alive-b", "alive-c"),
            submittedVoterKeys = setOf("alive-a", "alive-c", "stale-player")
        )

        assertEquals(setOf("alive-b"), pending)
    }

    @Test
    fun `eligibility shrink can finish a previous partial submission without duplicate enqueue`() {
        val pending = TeamSurrenderVotePolicy.pendingSubmissionKeys(
            requiredVoterKeys = setOf("alive-a"),
            submittedVoterKeys = setOf("alive-a", "now-disconnected-b")
        )

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `reconnect grace prevents transient disconnect from completing vote`() {
        val progress = TeamSurrenderVotePolicy.calculate(
            requiredVoterKeys = setOf("alive-a", "reconnecting-b"),
            recordedVoteKeys = setOf("alive-a", "reconnecting-b"),
            eliminatedExemptCount = 0,
            disconnectedExemptCount = 0,
            reconnectPendingCount = 1
        )

        assertEquals(0, progress.remainingCount)
        assertEquals(1, progress.reconnectPendingCount)
        assertFalse(progress.complete)
    }
}
