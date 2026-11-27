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
 * 数据包接受状态
 *
 * @date 2023/7/30 17:08
 * @author Dr (dr@der.kim)
 */

/**
 * Send chat messages to players
 * @param receiveSize Int : 已经收到的包
 * @param allSize Int     : 包的总大小
 * @return Packet         : Generate a send package
 * @throws IOException    : Unknown
 */
@Throws(IOException::class)
internal fun receivingStatusInternalPacket(receiveSize: Int, allSize: Int): Packet {
    val o = GameOutputStream()
    o.writeByte(0)
    o.writeInt(receiveSize)
    o.writeInt(allSize)
    return o.createPacket(PacketType.PACKET_DOWNLOAD_PENDING)
}

