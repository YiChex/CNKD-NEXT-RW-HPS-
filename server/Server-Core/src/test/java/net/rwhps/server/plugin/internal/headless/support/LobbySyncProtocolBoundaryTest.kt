/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.game.room.ServerRoom
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LobbySyncProtocolBoundaryTest {
    @Test
    fun battleRoomRecoveryAndLatencyDisplayDoNotCallFullGameSync() {
        val roomClass = classText(ServerRoom::class.java)
        val latencyClass = classText(StartModeLatencyHeartbeatSupport::class.java)

        assertTrue(roomClass.contains("refreshLobbyStateAfterBattleRoomReturn"))
        assertFalse(roomClass.contains("syncFullGameState"))
        assertFalse(roomClass.contains("allPlayerSync"))

        assertTrue(latencyClass.contains("syncLobbyPlayers"))
        assertFalse(latencyClass.contains("syncFullGameState"))
        assertFalse(latencyClass.contains("allPlayerSync"))
    }

    private fun classText(type: Class<*>): String {
        val resourceName = type.name.replace('.', '/') + ".class"
        return type.classLoader
            .getResourceAsStream(resourceName)
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("Class bytes are unavailable: $resourceName")
    }
}
