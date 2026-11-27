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
 * Player Exit Server
 * @author Dr (dr@der.kim)
 */

/**
 * Player Exit Server Packet
 * @return Packet
 * @throws IOException
 */
@Throws(IOException::class)
internal fun playerExitInternalPacket(cause: String = "exited"): Packet {
    val o = GameOutputStream()
    o.writeString(cause)
    return o.createPacket(PacketType.DISCONNECT)
}
