/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

/** Keeps lobby packets and full game snapshots on separate lifecycle paths. */
internal object GameSyncSafetyPolicy {
    fun canAttemptFullGameSync(
        roomStarted: Boolean,
        lifecyclePlaying: Boolean,
        resetInProgress: Boolean,
        battleRoomTransitionInProgress: Boolean,
        lobbyRecoveryInProgress: Boolean
    ): Boolean {
        return roomStarted && lifecyclePlaying && !resetInProgress &&
            !battleRoomTransitionInProgress && !lobbyRecoveryInProgress
    }

    fun canBroadcastFullGameSync(
        roomStarted: Boolean,
        lifecyclePlaying: Boolean,
        resetInProgress: Boolean,
        battleRoomTransitionInProgress: Boolean,
        lobbyRecoveryInProgress: Boolean,
        mapEngineReady: Boolean
    ): Boolean {
        return canAttemptFullGameSync(
            roomStarted,
            lifecyclePlaying,
            resetInProgress,
            battleRoomTransitionInProgress,
            lobbyRecoveryInProgress
        ) && mapEngineReady
    }

    fun canRefreshLobbyState(
        roomStarted: Boolean,
        lobbySyncBlocked: Boolean,
        netEngineReady: Boolean
    ): Boolean {
        return !roomStarted && !lobbySyncBlocked && netEngineReady
    }
}
