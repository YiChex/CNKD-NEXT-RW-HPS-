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

internal class StartModeDefeatedPlayerKickSupportTest {
    @Test
    fun tracksOnlyConnectedDefeatedHumanPlayersInAnActiveMatch() {
        assertTrue(
            StartModeDefeatedPlayerKickSupport.shouldObservePlayer(
                matchStarted = true,
                isAi = false,
                isConnectedHuman = true,
                isSpectator = false,
                originalGameReportsAlive = false
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldObservePlayer(
                matchStarted = true,
                isAi = true,
                isConnectedHuman = true,
                isSpectator = false,
                originalGameReportsAlive = false
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldObservePlayer(
                matchStarted = true,
                isAi = false,
                isConnectedHuman = true,
                isSpectator = true,
                originalGameReportsAlive = false
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldObservePlayer(
                matchStarted = true,
                isAi = false,
                isConnectedHuman = false,
                isSpectator = false,
                originalGameReportsAlive = false
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldObservePlayer(
                matchStarted = true,
                isAi = false,
                isConnectedHuman = true,
                isSpectator = false,
                originalGameReportsAlive = true
            )
        )
    }

    @Test
    fun requiresThreeConsecutiveSecondsOfOriginalDefeatState() {
        assertFalse(StartModeDefeatedPlayerKickSupport.isDefeatConfirmed(100, 102))
        assertTrue(StartModeDefeatedPlayerKickSupport.isDefeatConfirmed(100, 103))
    }

    @Test
    fun rejectsReconnectOnlyForAnActiveEnabledMatchAfterTheAutomaticKickWasSent() {
        assertTrue(
            StartModeDefeatedPlayerKickSupport.shouldRejectAutoKickedReconnect(
                featureEnabled = true,
                matchStarted = true,
                kickAlreadySent = true
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldRejectAutoKickedReconnect(
                featureEnabled = false,
                matchStarted = true,
                kickAlreadySent = true
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldRejectAutoKickedReconnect(
                featureEnabled = true,
                matchStarted = false,
                kickAlreadySent = true
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldRejectAutoKickedReconnect(
                featureEnabled = true,
                matchStarted = true,
                kickAlreadySent = false
            )
        )
    }

    @Test
    fun preservesAnAutomaticKickRecordUntilTheMatchEnds() {
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldDiscardObservation(
                playerIsStillActive = false,
                kickAlreadySent = true
            )
        )
        assertTrue(
            StartModeDefeatedPlayerKickSupport.shouldDiscardObservation(
                playerIsStillActive = false,
                kickAlreadySent = false
            )
        )
        assertFalse(
            StartModeDefeatedPlayerKickSupport.shouldDiscardObservation(
                playerIsStillActive = true,
                kickAlreadySent = false
            )
        )
    }
}
