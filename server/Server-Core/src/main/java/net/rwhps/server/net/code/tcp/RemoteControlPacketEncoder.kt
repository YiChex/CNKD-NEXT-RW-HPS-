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
import net.rwhps.server.io.packet.RconPacket

/**
 * Encodes a RconResponse into a raw rcon packet
 *
 * @see RconPacket
 * @author Dr (dr@der.kim)
 */
@ChannelHandler.Sharable
internal class RemoteControlPacketEncoder: MessageToByteEncoder<RconPacket>() {
    @Throws(Exception::class)
    override fun encode(ctx: ChannelHandlerContext, msg: RconPacket, out: ByteBuf) {
        out.writeIntLE(msg.body.length + 12)
        out.writeIntLE(msg.id)
        out.writeIntLE(msg.type)
        out.writeBytes(msg.body.toByteArray())
        out.writeIntLE(0)
    }
}
