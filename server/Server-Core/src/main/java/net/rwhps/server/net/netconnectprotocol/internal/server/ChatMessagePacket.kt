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

@file:JvmName("ServerPacket")
@file:JvmMultifileClass

package net.rwhps.server.net.netconnectprotocol.internal.server

import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import java.io.IOException

/**
 * Chat
 * @author Dr (dr@der.kim)
 */

/**
 * Send chat messages to players
 * @param msg String    : Message
 * @param sendBy String : Sender Name
 * @param team Int      : Team (distinguishing color)
 * @return Packet       : Generate a send package
 * @throws IOException  : Unknown
 */
@Throws(IOException::class)
internal fun chatMessagePacketInternal(msg: String, sendBy: String, team: Int): Packet {
    val o = GameOutputStream()
    // The message contained in the package
    o.writeString(msg)
    // Protocol version (in older versions here 0 1 2 3 respectively)
    o.writeByte(3)
    // Whether the sender name is empty and write data
    o.writeIsString(sendBy)
    // Team
    o.writeInt(team)
    // Team
    o.writeInt(team)
    return o.createPacket(PacketType.CHAT)
}

/**
 * Sim player message
 * @param msg String    : Message
 * @return Packet       : Generate a send package
 * @throws IOException  : Unknown
 */
@Throws(IOException::class)
internal fun chatUserMessagePacketInternal(msg: String): Packet {
    val o = GameOutputStream()
    // The message contained in the package
    o.writeString(msg)
    o.writeByte(0)
    return o.createPacket(PacketType.CHAT_RECEIVE)
}