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
 * RELAY Join
 * @author Dr (dr@der.kim)
 */

/**
 * Let the client think this is the RELAY server
 *  Initialization with RELAY
 * @return Packet       : Generate a send package
 * @throws IOException  : Unknown
 *
 * @author Dr (dr@der.kim)
 */
@Throws(IOException::class)
internal fun relayServerInitInfoInternalPacket(): Packet {
    val o = GameOutputStream()
    o.writeByte(0)
    // RELAY Version
    o.writeInt(151)
    // ?
    o.writeInt(1)
    // ?
    o.writeBoolean(false)
    return o.createPacket(PacketType.RELAY_VERSION_INFO)
}