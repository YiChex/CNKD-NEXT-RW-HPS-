/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core

import net.rwhps.server.game.headless.core.AbstractGameFast
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.plugin.internal.headless.inject.lib.PlayerConnectX
import com.corrodinggames.rts.gameFramework.j.au as Packet

/**
 * @author Dr (dr@der.kim)
 */
class GameFast: AbstractGameFast {
    override fun filteredPacket(packet: Any): Boolean {
        if (packet is Packet) {
            val playerConnect = packet.a as PlayerConnectX?
            val type = packet.b

            // D-24: magic numbers replaced by named PacketType ids.
            if (type == PacketType.CHAT_RECEIVE.typeInt && playerConnect != null) {
                return true
            }

            /*
             * D-24: 105 is the game-lib "connection/handshake" packet id carried by
             * `com.corrodinggames.rts.gameFramework.j.au`; it has no server-side
             * PacketType counterpart and is deliberately passed through. Keep it as a
             * named constant so the whitelist below stays readable and auditable.
             */
            if (GameEngine.netEngine.C && playerConnect != null && !playerConnect.p &&
                type != GAME_LIB_CONNECTION_PACKET_ID &&
                type != PacketType.REGISTER_PLAYER.typeInt &&
                type != PacketType.DISCONNECT.typeInt &&
                type != PacketType.HEART_BEAT.typeInt &&
                type != PacketType.PREREGISTER_INFO_RECEIVE.typeInt
            ) {
                return true
            }
            return false
        }
        return false
    }

    companion object {
        /**
         * Game-lib packet id that must not be filtered (see [filteredPacket]).
         * Not present in [PacketType] because it never reaches the server protocol layer.
         */
        private const val GAME_LIB_CONNECTION_PACKET_ID = 105
    }
}