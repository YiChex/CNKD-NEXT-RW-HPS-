/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

/**
 * Timing policy for rebuilding the native lobby after a battle-room return.
 *
 * The engine return packet only proves that the match has ended. It does not
 * prove that the restored player state has been serialized for connected
 * clients, so start admission remains closed until this policy reaches ready.
 */
internal object BattleRoomLobbyRecoveryPolicy {
    enum class Phase(val diagnosticValue: String) {
        PENDING_INITIAL_REFRESH("pending_initial_lobby_refresh"),
        CLIENT_APPLY_GRACE("client_apply_grace"),
        PENDING_FINAL_REFRESH("pending_final_lobby_refresh"),
        FINAL_CLIENT_APPLY_GRACE("final_client_apply_grace")
    }

    const val INITIAL_SYNC_DELAY_MILLIS = BattleRoomTransitionPolicy.LOBBY_SYNC_COOLDOWN_MILLIS
    const val RETRY_DELAY_MILLIS = 500L
    const val CLIENT_APPLY_GRACE_MILLIS = 3_000L
    const val FINAL_CLIENT_APPLY_GRACE_MILLIS = 500L
    const val MAX_SYNC_ATTEMPTS = 30

    fun retryDelayMillis(attempt: Int): Long {
        return if (attempt <= 0) INITIAL_SYNC_DELAY_MILLIS else RETRY_DELAY_MILLIS
    }

    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_SYNC_ATTEMPTS

    fun isRefreshPhase(phase: Phase): Boolean {
        return phase == Phase.PENDING_INITIAL_REFRESH || phase == Phase.PENDING_FINAL_REFRESH
    }

    fun nextPhaseAfterGrace(phase: Phase): Phase? {
        return when (phase) {
            Phase.CLIENT_APPLY_GRACE -> Phase.PENDING_FINAL_REFRESH
            Phase.FINAL_CLIENT_APPLY_GRACE -> null
            else -> error("phase_does_not_complete_a_client_grace:$phase")
        }
    }

    fun clientGraceMillis(phase: Phase): Long {
        return when (phase) {
            Phase.CLIENT_APPLY_GRACE -> CLIENT_APPLY_GRACE_MILLIS
            Phase.FINAL_CLIENT_APPLY_GRACE -> FINAL_CLIENT_APPLY_GRACE_MILLIS
            else -> error("phase_does_not_hold_a_client_grace:$phase")
        }
    }

    fun remainingGraceMillis(nowMillis: Long, graceUntilMillis: Long): Long {
        return (graceUntilMillis - nowMillis).coerceAtLeast(0L)
    }

    fun requiresEnginePlayer(isAi: Boolean, connectedHuman: Boolean): Boolean {
        return isAi || connectedHuman
    }
}
