/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal object GameOverSettlePolicy {
    const val DEFAULT_COUNTDOWN_SECONDS = 30
    private const val MIN_COUNTDOWN_SECONDS = 5
    private const val MAX_COUNTDOWN_SECONDS = 300

    enum class Mode(
        val diagnosticValue: String,
        val messageKey: String,
        val usesBattleRoomTransition: Boolean
    ) {
        BATTLE_ROOM_RETURN(
            diagnosticValue = "battle_room_return",
            messageKey = "gameOver.returnBattleRoom",
            usesBattleRoomTransition = true
        ),
        FORCED_SERVER_RESET(
            diagnosticValue = "forced_server_reset",
            messageKey = "gameOver.forceServerReset",
            usesBattleRoomTransition = false
        )
    }

    data class Decision(
        val mode: Mode,
        val countdownSeconds: Int
    )

    fun decide(
        enableBattleRoomReturn: Boolean?,
        countdownSeconds: Int?
    ): Decision {
        val mode = if (enableBattleRoomReturn == true) {
            Mode.BATTLE_ROOM_RETURN
        } else {
            Mode.FORCED_SERVER_RESET
        }
        return Decision(
            mode = mode,
            countdownSeconds = (countdownSeconds ?: DEFAULT_COUNTDOWN_SECONDS)
                .coerceIn(MIN_COUNTDOWN_SECONDS, MAX_COUNTDOWN_SECONDS)
        )
    }

    fun shouldRetryReset(completedAutomaticRetries: Int, maxAutomaticRetries: Int): Boolean {
        return completedAutomaticRetries.coerceAtLeast(0) < maxAutomaticRetries.coerceAtLeast(0)
    }

    fun shouldBlockStart(
        isSettling: Boolean,
        resetInProgress: Boolean,
        battleRoomTransitionInProgress: Boolean
    ): Boolean {
        return isSettling || resetInProgress || battleRoomTransitionInProgress
    }

    fun canEnterBattleRoom(
        isSettling: Boolean,
        resetInProgress: Boolean,
        allowDuringSettlement: Boolean
    ): Boolean {
        return !resetInProgress && (!isSettling || allowDuringSettlement)
    }
}
