/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

//关闭傻逼格式化
//@formatter:off

@file:JvmName("RelayPacket")
@file:JvmMultifileClass

package net.rwhps.server.net.netconnectprotocol.internal.relay

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import java.io.IOException

/**
 * RELAY verification
 * @author Dr (dr@der.kim)
 */

/**
 * Make the player client pop up an input window
 *   The input data is returned in plaintext
 * @param msg String    : Prompt Information
 * @return Packet       : Generate a send package
 * @throws IOException  : Unknown
 */
@Throws(IOException::class)
fun relayServerTypeInternal(msg: String): Packet {
    val o = GameOutputStream()
    // Theoretically random numbers?
    o.writeByte(1)
    o.writeInt(5) //可能和-AX一样
    // Msg
    o.writeString(msg)
    return o.createPacket(PacketType.RELAY_117) /// -> 118
}

/**
 * Parse the data returned by [relayServerTypeInternal]
 * @param packet Packet : Returned Package
 * @return String       : Parsed data (plaintext entered by the player)
 * @throws IOException  : Unknown
 */
@Throws(IOException::class)
fun relayServerTypeReplyInternalPacket(packet: Packet): String {
    GameInputStream(packet).use { inStream ->
        // Skip the previously useless data
        inStream.skip(5)
        // Read data and remove leading and trailing spaces
        return inStream.readString().trim { it <= ' ' }
    }
}