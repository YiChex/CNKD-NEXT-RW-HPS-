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

import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import java.io.IOException

/**
 * RELAY Jump Server
 * @author Dr (dr@der.kim)
 */

/**
 * Jump to a new server using the official protocol
 *  Client actively connects
 * @param ip String     : IP
 * @return Packet       : Generate a send package
 * @throws IOException  : Unknown
 *
 * @author Dr (dr@der.kim)
 */
@Throws(IOException::class)
internal fun fromRelayJumpsToAnotherServerInternalPacket(ip: String): Packet {
    val o = GameOutputStream()
    // The message contained in the package
    o.writeByte(0)
    // Protocol version? (I don't know)
    o.writeInt(3)
    // Debug
    o.writeBoolean(false)
    // For
    o.writeInt(1)
    o.writeString(ip)

    return o.createPacket(PacketType.PACKET_RECONNECT_TO)
}