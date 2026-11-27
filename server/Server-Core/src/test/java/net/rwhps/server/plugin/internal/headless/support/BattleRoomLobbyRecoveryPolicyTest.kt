/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class BattleRoomLobbyRecoveryPolicyTest {
    @Test
    fun recoveryStartsAfterTheNativeReturnCooldownThenUsesBoundedRetries() {
        assertEquals(1_000L, BattleRoomLobbyRecoveryPolicy.retryDelayMillis(0))
        assertEquals(500L, BattleRoomLobbyRecoveryPolicy.retryDelayMillis(1))
        assertTrue(BattleRoomLobbyRecoveryPolicy.shouldRetry(29))
        assertFalse(BattleRoomLobbyRecoveryPolicy.shouldRetry(30))
    }

    @Test
    fun clientGraceOnlyEndsWhenItsDeadlineIsReached() {
        assertEquals(500L, BattleRoomLobbyRecoveryPolicy.FINAL_CLIENT_APPLY_GRACE_MILLIS)
        assertEquals(3_000L, BattleRoomLobbyRecoveryPolicy.remainingGraceMillis(1_000L, 4_000L))
        assertEquals(1L, BattleRoomLobbyRecoveryPolicy.remainingGraceMillis(3_999L, 4_000L))
        assertEquals(0L, BattleRoomLobbyRecoveryPolicy.remainingGraceMillis(4_000L, 4_000L))
        assertEquals(0L, BattleRoomLobbyRecoveryPolicy.remainingGraceMillis(5_000L, 4_000L))
    }

    @Test
    fun recoveryRequiresTwoLightweightLobbyRefreshPhasesBeforeStartCanBeReleased() {
        assertTrue(BattleRoomLobbyRecoveryPolicy.isRefreshPhase(BattleRoomLobbyRecoveryPolicy.Phase.PENDING_INITIAL_REFRESH))
        assertTrue(BattleRoomLobbyRecoveryPolicy.isRefreshPhase(BattleRoomLobbyRecoveryPolicy.Phase.PENDING_FINAL_REFRESH))
        assertFalse(BattleRoomLobbyRecoveryPolicy.isRefreshPhase(BattleRoomLobbyRecoveryPolicy.Phase.CLIENT_APPLY_GRACE))
        assertEquals(
            BattleRoomLobbyRecoveryPolicy.Phase.PENDING_FINAL_REFRESH,
            BattleRoomLobbyRecoveryPolicy.nextPhaseAfterGrace(BattleRoomLobbyRecoveryPolicy.Phase.CLIENT_APPLY_GRACE)
        )
        assertEquals(
            null,
            BattleRoomLobbyRecoveryPolicy.nextPhaseAfterGrace(BattleRoomLobbyRecoveryPolicy.Phase.FINAL_CLIENT_APPLY_GRACE)
        )
        assertEquals(3_000L, BattleRoomLobbyRecoveryPolicy.clientGraceMillis(BattleRoomLobbyRecoveryPolicy.Phase.CLIENT_APPLY_GRACE))
        assertEquals(500L, BattleRoomLobbyRecoveryPolicy.clientGraceMillis(BattleRoomLobbyRecoveryPolicy.Phase.FINAL_CLIENT_APPLY_GRACE))
    }

    @Test
    fun disconnectedHumansDoNotMakeLobbyRecoveryFailForAMissingEngineSlot() {
        assertTrue(BattleRoomLobbyRecoveryPolicy.requiresEnginePlayer(isAi = true, connectedHuman = false))
        assertTrue(BattleRoomLobbyRecoveryPolicy.requiresEnginePlayer(isAi = false, connectedHuman = true))
        assertFalse(BattleRoomLobbyRecoveryPolicy.requiresEnginePlayer(isAi = false, connectedHuman = false))
    }
}
