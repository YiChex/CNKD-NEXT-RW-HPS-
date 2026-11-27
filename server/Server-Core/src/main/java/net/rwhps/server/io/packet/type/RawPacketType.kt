/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.packet.type

import net.rwhps.server.io.GameOutputStream

/**
 * Preserves game-core packet ids that are not known by RW-HPS yet.
 *
 * Some modded clients or future game builds can send packets outside the static
 * PacketType enum. Keeping the original id allows pure forwarding paths to stay
 * compatible instead of disconnecting the player or rewriting the packet as -1.
 */
class RawPacketType private constructor(
    override val typeInt: Int
) : AbstractPacketType {
    override val typeIntBytes: ByteArray = GameOutputStream.intToBytes(typeInt)
    override val nameEnum: String = "RAW_PACKET_$typeInt"

    override fun from(type: Int): AbstractPacketType {
        return if (type == typeInt) this else of(type)
    }

    override fun equals(other: Any?): Boolean {
        return other is AbstractPacketType && other.typeInt == typeInt
    }

    override fun hashCode(): Int = typeInt

    override fun toString(): String = nameEnum

    companion object {
        fun of(type: Int): AbstractPacketType {
            return if (type >= 0) RawPacketType(type) else PacketType.NOT_RESOLVED
        }
    }
}
