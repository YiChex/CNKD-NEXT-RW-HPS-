/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GameSyncSafetyPolicyTest {
    @Test
    fun fullGameSyncIsAllowedOnlyForAReadyActiveMatch() {
        assertTrue(
            GameSyncSafetyPolicy.canBroadcastFullGameSync(
                roomStarted = true,
                lifecyclePlaying = true,
                resetInProgress = false,
                battleRoomTransitionInProgress = false,
                lobbyRecoveryInProgress = false,
                mapEngineReady = true
            )
        )
        assertFalse(fullGameSync(roomStarted = false))
        assertFalse(fullGameSync(lifecyclePlaying = false))
        assertFalse(fullGameSync(resetInProgress = true))
        assertFalse(fullGameSync(battleRoomTransitionInProgress = true))
        assertFalse(fullGameSync(lobbyRecoveryInProgress = true))
        assertFalse(fullGameSync(mapEngineReady = false))
    }

    @Test
    fun lobbyRefreshIsRejectedDuringMatchesAndTransitions() {
        assertTrue(GameSyncSafetyPolicy.canRefreshLobbyState(false, false, true))
        assertFalse(GameSyncSafetyPolicy.canRefreshLobbyState(true, false, true))
        assertFalse(GameSyncSafetyPolicy.canRefreshLobbyState(false, true, true))
        assertFalse(GameSyncSafetyPolicy.canRefreshLobbyState(false, false, false))
    }

    private fun fullGameSync(
        roomStarted: Boolean = true,
        lifecyclePlaying: Boolean = true,
        resetInProgress: Boolean = false,
        battleRoomTransitionInProgress: Boolean = false,
        lobbyRecoveryInProgress: Boolean = false,
        mapEngineReady: Boolean = true
    ): Boolean {
        return GameSyncSafetyPolicy.canBroadcastFullGameSync(
            roomStarted,
            lifecyclePlaying,
            resetInProgress,
            battleRoomTransitionInProgress,
            lobbyRecoveryInProgress,
            mapEngineReady
        )
    }
}
