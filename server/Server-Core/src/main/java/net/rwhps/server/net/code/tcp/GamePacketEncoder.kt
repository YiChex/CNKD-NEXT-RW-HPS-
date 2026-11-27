/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.code.tcp

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import net.rwhps.server.io.packet.Packet

/**
 * Format data
 * @author Dr (dr@der.kim)
 */
/**
 *    1 2 3 4  5  6  7  8  ...
 *   +-+-+-+-+-+-+-+-+---------------+
 *   |0 |0 |0 |0 |0|0|0|0| Data|
 *   +-+-+-+-+-+-+-+-+---------------+
 *   |Data length|  Type | Data
 *   |    Packet Head    | Data
 *   +---------------+---------------+
 */
@ChannelHandler.Sharable
internal class GamePacketEncoder: MessageToByteEncoder<Packet>() {
    @Throws(Exception::class)
    override fun encode(p1: ChannelHandlerContext, msg: Packet, out: ByteBuf) {
        out.writeInt(msg.bytes.size)
        out.writeBytes(msg.type.typeIntBytes)
        out.writeBytes(msg.bytes)
    }
}