/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BattleRoomTransitionPolicyTest {
    @Test
    fun countdownAndWatchdogDelayAreBounded() {
        assertEquals(0, BattleRoomTransitionPolicy.normalizeCountdownSeconds(-1))
        assertEquals(5, BattleRoomTransitionPolicy.normalizeCountdownSeconds(5))
        assertEquals(300, BattleRoomTransitionPolicy.normalizeCountdownSeconds(999))
        assertEquals(15, BattleRoomTransitionPolicy.watchdogDelaySeconds(5))
        assertEquals(310, BattleRoomTransitionPolicy.watchdogDelaySeconds(999))
    }

    @Test
    fun schedulingRequiresStartedGameAndIdleReturnFlags() {
        assertEquals(true, BattleRoomTransitionPolicy.canSchedule(true, false, false))
        assertEquals(false, BattleRoomTransitionPolicy.canSchedule(false, false, false))
        assertEquals(false, BattleRoomTransitionPolicy.canSchedule(true, true, false))
        assertEquals(false, BattleRoomTransitionPolicy.canSchedule(true, false, true))
        assertEquals(false, BattleRoomTransitionPolicy.canSchedule(true, true, true))
    }

    @Test
    fun engineReturnRequiresGameAndBothPendingFlagsToClear() {
        assertEquals(
            false,
            BattleRoomTransitionPolicy.engineReturnComplete(
                engineGameStarted = true,
                engineCountdownPending = true,
                engineCommitPending = true
            )
        )
        assertEquals(
            true,
            BattleRoomTransitionPolicy.engineReturnComplete(
                engineGameStarted = false,
                engineCountdownPending = false,
                engineCommitPending = false
            )
        )
        assertEquals(
            false,
            BattleRoomTransitionPolicy.engineReturnComplete(
                engineGameStarted = false,
                engineCountdownPending = true,
                engineCommitPending = false
            )
        )
        assertEquals(
            false,
            BattleRoomTransitionPolicy.engineReturnComplete(
                engineGameStarted = false,
                engineCountdownPending = false,
                engineCommitPending = true
            )
        )
        assertEquals(
            false,
            BattleRoomTransitionPolicy.engineReturnComplete(
                engineGameStarted = true,
                engineCountdownPending = false,
                engineCommitPending = false
            )
        )
    }

    @Test
    fun outboundReturnPacketIsAuthoritativeWhenEngineFlagsClearLate() {
        assertEquals(
            true,
            BattleRoomTransitionPolicy.hasAuthoritativeReturnEvidence(
                engineReturnComplete = false,
                returnPacketObserved = true
            )
        )
        assertEquals(
            true,
            BattleRoomTransitionPolicy.hasAuthoritativeReturnEvidence(
                engineReturnComplete = true,
                returnPacketObserved = false
            )
        )
        assertEquals(
            false,
            BattleRoomTransitionPolicy.hasAuthoritativeReturnEvidence(
                engineReturnComplete = false,
                returnPacketObserved = false
            )
        )
    }

    @Test
    fun protectiveResetIsForbiddenAfterLobbyCommit() {
        assertEquals(true, BattleRoomTransitionPolicy.shouldProtectivelyResetAfterFailure(true))
        assertEquals(false, BattleRoomTransitionPolicy.shouldProtectivelyResetAfterFailure(false))
    }

    @Test
    fun engineCommitSchedulingIsSuppressedWhileQueuedOrRunning() {
        assertEquals(true, BattleRoomTransitionPolicy.shouldScheduleCommit(false, false))
        assertEquals(false, BattleRoomTransitionPolicy.shouldScheduleCommit(true, false))
        assertEquals(false, BattleRoomTransitionPolicy.shouldScheduleCommit(false, true))
        assertEquals(false, BattleRoomTransitionPolicy.shouldScheduleCommit(true, true))
    }

    @Test
    fun lobbyPlayerSyncWaitsUntilBattleRoomTransitionAndCooldownFinish() {
        assertEquals(false, BattleRoomTransitionPolicy.canSyncLobbyPlayers(true, false, 0, 2_000))
        assertEquals(false, BattleRoomTransitionPolicy.canSyncLobbyPlayers(false, true, 0, 2_000))
        assertEquals(false, BattleRoomTransitionPolicy.canSyncLobbyPlayers(false, false, 3_000, 2_999))
        assertEquals(true, BattleRoomTransitionPolicy.canSyncLobbyPlayers(false, false, 3_000, 3_000))
    }

    @Test
    fun lobbyJoinGraceDoesNotReopenForConnectionsFromThePreviousLobbyGeneration() {
        assertEquals(true, BattleRoomTransitionPolicy.canUseLobbyJoinGrace(4, 4))
        assertEquals(false, BattleRoomTransitionPolicy.canUseLobbyJoinGrace(4, 5))
    }

}
