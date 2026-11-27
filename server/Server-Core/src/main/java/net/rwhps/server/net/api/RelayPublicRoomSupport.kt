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
import kotlin.concurrent.thread

internal data class RelayPublicRoomConflict(
    val localRoom: RelayRoom? = null,
    val remoteNodeCode: String? = null
)

internal object RelayPublicRoomSupport {
    fun findConflict(ip: String, excludeRoomId: String = ""): RelayPublicRoomConflict? {
        if (!Data.configRelay.decision.enableSinglePublicRoomPerIpLimit) {
            return null
        }

        val normalizedIp = ip.trim()
        if (normalizedIp.isEmpty()) {
            return null
        }

        RelayRoom.findOtherPublicRoomByAdminIp(normalizedIp, excludeRoomId)?.let {
            return RelayPublicRoomConflict(localRoom = it)
        }

        RelayNodeRoutingSupport.findNodeCodeWithObservedPublicRoomByHostIp(
            normalizedIp,
            Data.configRelay.localNodeCode
        )?.let {
            return RelayPublicRoomConflict(remoteNodeCode = it)
        }

        return null
    }

    fun buildConflictMessage(conflict: RelayPublicRoomConflict): String {
        conflict.localRoom?.let {
            return "同一IP当前只能公开一个房间，请先关闭已公开房间 ${RelayDeploymentSupport.roomDisplayId(it.id)} 后再试"
        }

        conflict.remoteNodeCode?.let { code ->
            val node = RelayNodeRoutingSupport.findNode(code)
            val nodeName = node?.displayName?.ifBlank { node.name.ifBlank { code } } ?: code
            return "同一IP当前只能公开一个房间，请先关闭你在 $nodeName 上的公开房后再试"
        }

        return "同一IP当前只能公开一个房间，请先关闭你已公开的其他房间后再试"
    }

    fun scheduleSuccessNotice(room: RelayRoom) {
        val delaySeconds = Data.configRelay.decision.publicRoomSuccessCheckSeconds.coerceIn(1, 30)
        thread(start = true, isDaemon = true, name = "RelayPublicRoomCheck-${room.id}") {
            try {
                Thread.sleep(delaySeconds * 1000L)
            } catch (_: InterruptedException) {
                return@thread
            }

            if (!room.closeRoom && room.publicRoom && room.admin != null) {
                room.sendMsg("公开房已复查开放成功，RCN与CNKD祝您游戏愉快", RelayDeploymentSupport.chatSender(room.id), 5)
            }
        }
    }
}
