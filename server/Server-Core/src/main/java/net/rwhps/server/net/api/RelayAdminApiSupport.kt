/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.plugin.internal.headless.support.StartModeAdvertisingRedirectSupport
import net.rwhps.server.util.IpUtils

internal object RelayAdminApiSupport {
    fun buildStatus(): Map<String, Any> {
        return mapOf(
            "bannedIps" to Data.core.admin.bannedIPs.toList(),
            "bannedIp24" to Data.core.admin.bannedIP24.toList(),
            "relayServerModeEnabled" to RelayCustomRoomSupport.isRelayServerModeEnabled(),
            "relayServerManagedRooms" to RelayCustomRoomSupport.relayServerManagedProfiles().map { RelayDeploymentSupport.roomDisplayId(it.roomId) },
            "statusApiPath" to Data.configRelayApi.statusApiPath,
            "roomsApiPath" to Data.configRelayApi.roomsApiPath,
            "adminApiPath" to Data.configRelayApi.adminApiPath
        )
    }

    fun buildRooms(
        includeClosed: Boolean = Data.configRelayApi.roomsApiIncludeClosed,
        limit: Int = Data.configRelayApi.roomsApiLimit
    ): Map<String, Any> {
        val safeLimit = limit.coerceIn(1, 10000)
        val rooms = RelayRoom.getAllRoomSnapshots(safeLimit, includeClosed).map { room ->
            val normalizedHostIp = room.hostIp.trim()
            mapOf(
                "roomId" to room.roomId,
                "displayId" to RelayDeploymentSupport.roomDisplayId(room.roomId),
                "lookupId" to RelayDeploymentSupport.roomLookupId(room.roomId),
                "playerSize" to room.playerSize,
                "activeConnectionSize" to room.activeConnectionSize,
                "status" to when {
                    room.closeRoom -> "closed"
                    room.started -> "ingame"
                    else -> "battleroom"
                },
                "hostName" to room.hostName,
                "hostIp" to normalizedHostIp,
                "hostIp24" to normalizedHostIp.takeIf { it.isNotBlank() }?.let { IpUtils.ipToLong24(it, false) }.orEmpty(),
                "mapName" to room.mapName,
                "customRoom" to room.customRoom,
                "publicPriority" to room.publicPriority,
                "publicRoom" to room.publicRoom,
                "closeRoom" to room.closeRoom,
                "isMod" to room.isMod,
                "roomCreateTime" to room.roomCreateTime,
                "publicTopTime" to room.publicTopTime,
                "lastActivityTime" to room.lastActivityTime,
                "joinLink" to RelayDeploymentSupport.roomJoinLink(room.roomId)
            )
        }

        return mapOf(
            "nodeName" to RelayDeploymentSupport.displayName(),
            "role" to RelayDeploymentSupport.roleName(),
            "localNodeCode" to RelayDeploymentSupport.localNodeCode(),
            "roomCount" to RelayRoom.roomAllSize,
            "roomNoStartSize" to RelayRoom.roomNoStartSize,
            "roomPublicSize" to RelayRoom.roomPublicSize,
            "roomPrivateSize" to (RelayRoom.roomNoStartSize - RelayRoom.roomPublicSize),
            "rooms" to rooms
        )
    }

    fun advertisingStatus(): Map<String, Any> {
        return mapOf(
            "runtime" to StartModeAdvertisingRedirectSupport.runtimeSnapshot(),
            "diagnostics" to StartModeAdvertisingRedirectSupport.buildDiagnostics()
        )
    }

    fun advertisingCheck(): Map<String, Any> {
        val check = StartModeAdvertisingRedirectSupport.checkTargetNow()
        return mapOf(
            "check" to check,
            "runtime" to StartModeAdvertisingRedirectSupport.runtimeSnapshot(),
            "diagnostics" to StartModeAdvertisingRedirectSupport.buildDiagnostics()
        )
    }

    fun banIp(ip: String, disconnectNow: Boolean = true): Map<String, Any> {
        val normalizedIp = ip.trim()
        require(normalizedIp.isNotEmpty()) { "ip is blank" }

        Data.core.admin.banIp(normalizedIp)
        val ip24 = IpUtils.ipToLong24(normalizedIp, false)
        if (disconnectNow) {
            disconnectByIp(normalizedIp)
        }

        return mapOf(
            "ip" to normalizedIp,
            "ip24" to ip24,
            "disconnectNow" to disconnectNow,
            "bannedIps" to Data.core.admin.bannedIPs.toList(),
            "bannedIp24" to Data.core.admin.bannedIP24.toList()
        )
    }

    fun unbanIp(ip: String): Map<String, Any> {
        val normalizedIp = ip.trim()
        require(normalizedIp.isNotEmpty()) { "ip is blank" }

        Data.core.admin.unbanIp(normalizedIp)
        return mapOf(
            "ip" to normalizedIp,
            "bannedIps" to Data.core.admin.bannedIPs.toList()
        )
    }

    fun banIp24(ip24: String): Map<String, Any> {
        val normalizedIp24 = ip24.trim()
        require(normalizedIp24.isNotEmpty()) { "ip24 is blank" }

        Data.core.admin.banIp24(normalizedIp24)
        return mapOf(
            "ip24" to normalizedIp24,
            "bannedIp24" to Data.core.admin.bannedIP24.toList()
        )
    }

    fun unbanIp24(ip24: String): Map<String, Any> {
        val normalizedIp24 = ip24.trim()
        require(normalizedIp24.isNotEmpty()) { "ip24 is blank" }

        Data.core.admin.unbanIp24(normalizedIp24)
        return mapOf(
            "ip24" to normalizedIp24,
            "bannedIp24" to Data.core.admin.bannedIP24.toList()
        )
    }

    private fun disconnectByIp(ip: String) {
        RelayRoom.forEachActiveRoom { room ->
            room.admin?.takeIf { it.ip == ip }?.safeKickForAdminApi()
            room.abstractNetConnectIntMap.values.forEach { player ->
                if (player.ip == ip) {
                    player.safeKickForAdminApi()
                }
            }
        }
    }

    private fun GameVersionRelay.safeKickForAdminApi() {
        try {
            kick("源IP已被管理员封禁")
        } catch (_: Exception) {
            disconnect()
        }
    }
}
