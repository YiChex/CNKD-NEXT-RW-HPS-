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
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import net.rwhps.server.io.packet.RconPacket
import java.nio.charset.Charset

/**
 * Decodes the raw rcon packet and wrap into a RconRequest class
 *
 * 客户端实现 nl.vv32.rcon
 *
 * @see RconPacket
 * @author Dr (dr@der.kim)
 */
class RemoteControlPacketDecoder: ByteToMessageDecoder() {
    @Throws(Exception::class)
    override fun decode(ctx: ChannelHandlerContext, bufferIn: ByteBuf, out: MutableList<Any>) {
        if (bufferIn.readableBytes() < 8) {
            return
        }

        val readerIndex = bufferIn.readerIndex()
        val packetLength = bufferIn.readIntLE()

        if (packetLength < 4 || packetLength > 4096) {
            return
        }

        if (bufferIn.readableBytes() < packetLength) {
            bufferIn.readerIndex(readerIndex)
            return
        }

        val id = bufferIn.readIntLE()
        val type = bufferIn.readIntLE()

        val body = bufferIn.readBytes(bufferIn.readableBytes() - 2).toString(Charset.forName("UTF-8"))
        bufferIn.readBytes(2)

        out.add(RconPacket(id, type, body))
    }
}
