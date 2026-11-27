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

internal class SingleRoomLowLatencyReadinessPolicyTest {
    @Test
    fun hotPathRequiresAStartedPlayingRoomAndReadyMap() {
        assertTrue(
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                roomStarted = true,
                resetInProgress = false,
                playing = true,
                mapEngineReady = true
            )
        )

        assertFalse(
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                roomStarted = true,
                resetInProgress = false,
                playing = true,
                mapEngineReady = false
            )
        )
    }

    @Test
    fun hotPathStaysBlockedForLifecycleTransitionsEvenWithAReadyMap() {
        assertFalse(
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                roomStarted = false,
                resetInProgress = false,
                playing = true,
                mapEngineReady = true
            )
        )
        assertFalse(
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                roomStarted = true,
                resetInProgress = true,
                playing = true,
                mapEngineReady = true
            )
        )
        assertFalse(
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                roomStarted = true,
                resetInProgress = false,
                playing = false,
                mapEngineReady = true
            )
        )
        assertFalse(
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                roomStarted = true,
                resetInProgress = false,
                playing = true,
                mapEngineReady = true,
                postReturnTrafficBlocked = true
            )
        )
    }
}
