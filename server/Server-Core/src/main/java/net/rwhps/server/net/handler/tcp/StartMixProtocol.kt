/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler.tcp

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.*
import net.rwhps.server.data.global.Data
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.struct.map.ObjectMap


/**
 * 混合转发器, 检查数据是不是游戏数据
 *
 * @author Dr (dr@der.kim)
 */

@ChannelHandler.Sharable
@Suppress("UNUSED")
class StartMixProtocol(mixData: ObjectMap<IRwHps.NetType, IRwHps>): StartHttp(mixData[IRwHps.NetType.HttpProtocol]!!) {
    // 我真棒, 写了坨大的
    private val gameServer = StartGameNetTcp(mixData[IRwHps.NetType.GameProtocol]!!)

    override fun initChannel(socketChannel: SocketChannel) {
        val sup = this
        socketChannel.pipeline().addLast(object: ChannelInboundHandlerAdapter() {
            @Throws(Exception::class)
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                val firstData: ByteBuf = msg as ByteBuf
                run {
                    // Game
                    if(read(firstData) {
                        val readPacketLengthCache = it.readInt()
                        val readPacketTypeCache = it.readInt()
                        if (readPacketLengthCache > 0 && readPacketTypeCache > 0 &&
                            (IRwHps.packetType.from(readPacketTypeCache).typeInt > 1000 ||
                             IRwHps.packetType.from(readPacketTypeCache) == PacketType.PREREGISTER_INFO_RECEIVE)
                        ) {
                            gameServer.initHandler(socketChannel.pipeline())
                            return@read true
                        }
                        return@read false
                    }) return@run

                    // Web
                    if(Data.config.webMixEnable && read(firstData) {
                        sup.initChannel(socketChannel, !isHttpReq(it.toString(Data.UTF_8)))
                        return@read true
                    }) return@run
                }

                ctx.pipeline().remove(this)
                super.channelRead(ctx, msg)
            }
        })
    }

    private fun read(firstData: ByteBuf, run: (ByteBuf)->Boolean): Boolean {
        val readerIndex = firstData.readerIndex()
        return try {
            run(firstData)
        } catch (_: Exception) {
            // 读取错误 协议有误
            false
        } finally {
            firstData.readerIndex(readerIndex)
        }
    }
}
