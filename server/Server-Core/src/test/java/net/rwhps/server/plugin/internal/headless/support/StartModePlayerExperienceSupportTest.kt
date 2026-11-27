/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class StartModePlayerExperienceSupportTest {
    @Test
    fun delayedInGameDisconnectIsKeptWhileTheMatchIsStillRunning() {
        assertFalse(
            StartModePlayerExperienceSupport.shouldRemovePlayerAllAfterDisconnect(
                wasStartGame = true,
                roomStillStarted = true
            )
        )
    }

    @Test
    fun delayedInGameDisconnectIsRemovedAfterTheRoomReturnsToLobby() {
        assertTrue(
            StartModePlayerExperienceSupport.shouldRemovePlayerAllAfterDisconnect(
                wasStartGame = true,
                roomStillStarted = false
            )
        )
    }

    @Test
    fun lobbyDisconnectIsRemovedImmediately() {
        assertTrue(
            StartModePlayerExperienceSupport.shouldRemovePlayerAllAfterDisconnect(
                wasStartGame = false,
                roomStillStarted = false
            )
        )
    }

    @Test
    fun staleDisconnectedConnectionDoesNotSuppressTheLeaveEvent() {
        assertFalse(
            StartModePlayerExperienceSupport.shouldSuppressLeaveEventForReconnect(
                connectionPresent = true,
                connectionDisconnected = true
            )
        )
        assertTrue(
            StartModePlayerExperienceSupport.shouldSuppressLeaveEventForReconnect(
                connectionPresent = true,
                connectionDisconnected = false
            )
        )
        assertFalse(
            StartModePlayerExperienceSupport.shouldSuppressLeaveEventForReconnect(
                connectionPresent = false,
                connectionDisconnected = false
            )
        )
    }
}
