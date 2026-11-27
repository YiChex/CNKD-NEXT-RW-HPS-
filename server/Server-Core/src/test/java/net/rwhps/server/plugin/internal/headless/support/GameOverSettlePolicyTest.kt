/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

internal class GameOverSettlePolicyTest {
    @Test
    fun missingOrDisabledSettingUsesForcedServerReset() {
        val missing = GameOverSettlePolicy.decide(null, null)
        val disabled = GameOverSettlePolicy.decide(false, 30)

        assertEquals(GameOverSettlePolicy.Mode.FORCED_SERVER_RESET, missing.mode)
        assertEquals("forced_server_reset", missing.mode.diagnosticValue)
        assertEquals("gameOver.forceServerReset", missing.mode.messageKey)
        assertEquals(false, missing.mode.usesBattleRoomTransition)
        assertEquals(30, missing.countdownSeconds)
        assertEquals(GameOverSettlePolicy.Mode.FORCED_SERVER_RESET, disabled.mode)
    }

    @Test
    fun explicitEnabledSettingUsesBattleRoomReturn() {
        val decision = GameOverSettlePolicy.decide(true, 30)

        assertEquals(GameOverSettlePolicy.Mode.BATTLE_ROOM_RETURN, decision.mode)
        assertEquals("battle_room_return", decision.mode.diagnosticValue)
        assertEquals("gameOver.returnBattleRoom", decision.mode.messageKey)
        assertEquals(true, decision.mode.usesBattleRoomTransition)
        assertNotEquals(
            GameOverSettlePolicy.Mode.FORCED_SERVER_RESET.messageKey,
            decision.mode.messageKey
        )
    }

    @Test
    fun countdownIsSharedAndClampedForBothModes() {
        val forcedReset = GameOverSettlePolicy.decide(false, 1)
        val battleRoomReturn = GameOverSettlePolicy.decide(true, 999)

        assertEquals(5, forcedReset.countdownSeconds)
        assertEquals(300, battleRoomReturn.countdownSeconds)
    }

    @Test
    fun failedResetRetriesOnlyWithinConfiguredBudget() {
        assertEquals(true, GameOverSettlePolicy.shouldRetryReset(0, 1))
        assertEquals(false, GameOverSettlePolicy.shouldRetryReset(1, 1))
        assertEquals(false, GameOverSettlePolicy.shouldRetryReset(0, 0))
    }

    @Test
    fun startIsBlockedThroughoutSettlementResetAndBattleRoomTransition() {
        assertEquals(false, GameOverSettlePolicy.shouldBlockStart(false, false, false))
        assertEquals(true, GameOverSettlePolicy.shouldBlockStart(true, false, false))
        assertEquals(true, GameOverSettlePolicy.shouldBlockStart(false, true, false))
        assertEquals(true, GameOverSettlePolicy.shouldBlockStart(true, true, false))
        assertEquals(true, GameOverSettlePolicy.shouldBlockStart(false, false, true))
    }

    @Test
    fun battleRoomEntryRequiresSettlementAuthorizationAndNoResetOwner() {
        assertEquals(true, GameOverSettlePolicy.canEnterBattleRoom(false, false, false))
        assertEquals(false, GameOverSettlePolicy.canEnterBattleRoom(true, false, false))
        assertEquals(true, GameOverSettlePolicy.canEnterBattleRoom(true, false, true))
        assertEquals(false, GameOverSettlePolicy.canEnterBattleRoom(true, true, true))
    }
}
