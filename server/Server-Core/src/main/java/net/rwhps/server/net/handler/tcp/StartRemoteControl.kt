/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler.tcp

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.util.AttributeKey
import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
import net.rwhps.server.io.packet.RconPacket
import net.rwhps.server.net.code.tcp.RemoteControlPacketDecoder
import net.rwhps.server.net.code.tcp.RemoteControlPacketEncoder
import net.rwhps.server.net.core.AbstractNet
import net.rwhps.server.net.core.INetServerHandler
import net.rwhps.server.net.core.IRwHps

/**
 * @author Dr (dr@der.kim)
 */
@Sharable
class StartRemoteControl(rwHps: IRwHps): AbstractNet(rwHps) {

    val rChannelData = AttributeKey.valueOf<Boolean>("R-CON Authenticated")!!

    init {
        init(object: INetServerHandler(this) {
            override fun channelRead0(ctx: ChannelHandlerContext, msg: Any?) {
                val rconRequest = msg as RconPacket
                ctx.channel().attr(rChannelData).set(false)
                ctx.writeAndFlush(
                        RconPacket(
                                rconRequest.id,
                                RconPacket.Companion.RconConstant.SERVERDATA_AUTH_RESPONSE,
                                "Remote control has been disabled for security reasons."
                        )
                )
                ctx.close()
            }

            @Throws(Exception::class)
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                println("An error happened while handling Source RCON packet!$cause")
            }
        })
    }

    override fun addPacketDecoderAndEncoder(channelPipeline: ChannelPipeline) {
        channelPipeline.addLast(RemoteControlPacketDecoder())
        channelPipeline.addLast(RemoteControlPacketEncoder())
    }

    override fun initHandler(channelPipeline: ChannelPipeline) {
        addPacketDecoderAndEncoder(channelPipeline)
        addNewServerHandler(channelPipeline)
    }
}
