/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.manage.ModCompatibilitySupport
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.ServerInfoPacket
import net.rwhps.server.util.log.Log

/**
 * Bridges the official client map picker to server-side mod-pack maps.
 *
 * Clients can send map names such as MOD|hash//maps/foo or NEW_PATH|maps2/foo
 * from their visual UI. The headless server must map those names to a prepared
 * runtime file under data/maps before the game core can load them.
 */
internal object StartModeVisualMapSelectionSupport {
    fun rewriteIncomingServerInfoPacket(packet: Packet, notify: ((String) -> Unit)? = null): Packet {
        if (HeadlessModuleManage.initHPS() && HeadlessModuleManage.hps.room.isBattleRoomLobbyRecoveryInProgress()) {
            return packet
        }
        val incomingMapName = runCatching { ServerInfoPacket(packet.bytes).mapName.trim() }.getOrDefault("")
        StartModeSurvivalMapSupport.recordSelectedMapName(incomingMapName)

        if (StartModeMapLockSupport.isEnabled()) {
            return packet
        }

        if (incomingMapName.isBlank() || incomingMapName.startsWith("maps/skirmish/", ignoreCase = true)) {
            return packet
        }

        val target = MapManage.resolveCustomMap(incomingMapName) ?: return packet
        val prepared = runCatching {
            target.mapData.readMap(cleanOnRelease = false)
            true
        }.getOrElse { error ->
            Log.warn("[ModMap] Failed to prepare visual UI selected map: ${target.mapName}", error)
            notify?.invoke("Map file is not ready on the server: ${target.mapName}")
            false
        }
        if (!prepared) {
            return packet
        }

        val runtimeMapPath = "/SD/rusted_warfare_maps/${target.mapData.runtimeFileName}"
        if (incomingMapName.equals(runtimeMapPath, ignoreCase = true)) {
            return packet
        }

        ModCompatibilitySupport.recordVisualUiMapRewrite(incomingMapName, target.mapName)
        notify?.invoke("Switched to server-side mod map: ${target.mapName}")
        return Packet(packet.type, ServerInfoPacket.rewriteMapName(packet.bytes, runtimeMapPath))
    }

    fun commitAcceptedServerInfoPacket(packet: Packet) {
        if (!HeadlessModuleManage.initHPS()) {
            return
        }
        val mapName = runCatching { ServerInfoPacket(packet.bytes).mapName.trim() }.getOrDefault("")
        if (mapName.isBlank() || mapName.startsWith("maps/skirmish/", ignoreCase = true)) {
            return
        }
        val target = MapManage.resolveCustomMap(mapName) ?: return
        applyRoomState(target)
    }

    private fun applyRoomState(target: MapManage.ResolvedMapTarget) {
        if (!HeadlessModuleManage.initHPS()) {
            return
        }
        val room = HeadlessModuleManage.hps.room
        if (room.isBattleRoomLobbyRecoveryInProgress()) {
            return
        }
        room.maps.mapData = target.mapData
        room.maps.mapType = target.mapData.mapType
        room.maps.mapName = target.mapName
        room.maps.mapPlayer = ""
    }
}
