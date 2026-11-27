/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler.tcp

import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import net.rwhps.server.net.core.web.WebSocket
import java.util.function.Consumer

/**
 * @author Dr (dr@der.kim)
 */
@ChannelHandler.Sharable
class StartWebSocket(private val webSocket: WebSocket): SimpleChannelInboundHandler<TextWebSocketFrame>() {
    /** 已经连接这个Ws的通道 */
    private val connected: MutableSet<Channel> = HashSet(4)


    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: TextWebSocketFrame) {
        connected.add(ctx.channel())
        webSocket.ws(this, ctx.channel(), msg.text())
    }

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        connected.remove(ctx.channel())
        webSocket.closeWs(this, ctx.channel())
        super.channelInactive(ctx)
    }

    /**
     * 给已经连接这个Ws所有人群发消息
     * @param msg String?
     */
    fun broadCast(msg: String?) {
        connected.forEach(Consumer { x: Channel ->
            x.writeAndFlush(TextWebSocketFrame(msg))
        })
    }

    /**
     * 检查某个连接是否在群发内
     * @param channel Channel
     * @return Boolean
     */
    fun hasChannel(channel: Channel): Boolean {
        return connected.contains(channel)
    }
}