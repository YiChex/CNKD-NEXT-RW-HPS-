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
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ChannelInputShutdownEvent
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.channel.unix.Errors
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import net.rwhps.server.net.core.AbstractNet
import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.handler.TimeoutDetection
import net.rwhps.server.util.log.Log
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Dr (dr@der.kim)
 */
@Sharable
open class AcceptorIdleStateTrigger(
    private val abstractNet: AbstractNet
): ChannelInboundHandlerAdapter() {
    val connectNum: AtomicInteger = AtomicInteger()

    @Throws(Exception::class)
    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.fireChannelActive()
    }

    @Throws(java.lang.Exception::class)
    override fun channelRegistered(ctx: ChannelHandlerContext?) {
        if (ctx == null) {
            super.channelRegistered(ctx)
            return
        }
        val admission = ConnectionLifecycleGuard.onChannelRegistered(ctx.channel())
        if (!admission.accepted) {
            ctx.close()
            return
        }
        try {
            super.channelRegistered(ctx)
            scheduleHandshakeDeadline(ctx)
        } finally {
            connectNum.set(ConnectionLifecycleGuard.activeConnectionCount())
        }
    }

    @Throws(java.lang.Exception::class)
    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        try {
            super.channelUnregistered(ctx)
        } finally {
            if (ctx != null) {
                ConnectionLifecycleGuard.onChannelUnregistered(ctx.channel())
            }
            connectNum.set(ConnectionLifecycleGuard.activeConnectionCount())
        }
    }

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        try {
            super.channelInactive(ctx)
        } finally {
            clear(ctx)
        }
    }

    @Throws(Exception::class)
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is IdleStateEvent -> if (evt.state() == IdleState.READER_IDLE) {
                val con: TypeConnect? = abstractNet.getTypeConnect(ctx.channel()).get()
                if (TimeoutDetection.checkTimeoutDetection(con)) {
                    clear(ctx)
                }
            }
            is ChannelInputShutdownEvent,
            is ChannelInputShutdownReadComplete -> {
                clear(ctx)
            }
            else -> super.userEventTriggered(ctx, evt)
        }
    }

    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        // The remote host forcibly closed an existing connection
        when (cause) {
            is SocketException -> {
                clear(ctx)
            }
            is Errors.NativeIoException -> {
                clear(ctx)
            }
            else -> {
                cause?.let {
                    Log.error(it)
                }
                clear(ctx)
            }
        }
    }

    /**
     * Clean up connections and release resources
     * @param ctx ChannelHandlerContext
     */
    internal fun clear(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        val attr = abstractNet.getTypeConnect(channel)
        val con = attr.get()
        attr.set(null)
        if (channel.isOpen) {
            channel.close()
        }
        ctx.close()
        ConnectionLifecycleGuard.onChannelUnregistered(channel)
        connectNum.set(ConnectionLifecycleGuard.activeConnectionCount())
        if (con != null) {
            try {
                con.abstractNetConnect.disconnect()
            } catch (e: Exception) {
                Log.warn("[NetLifecycle] Connection disconnect hook failed; forcing channel close", e)
            }
        }
    }

    private fun scheduleHandshakeDeadline(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        val timeoutSeconds = ConnectionLifecycleGuard.handshakeIdleSeconds()
        channel.eventLoop().schedule({
            if (channel.isOpen && !ConnectionLifecycleGuard.isProtocolEstablished(channel)) {
                clear(ctx)
            }
        }, timeoutSeconds, TimeUnit.SECONDS)
    }
}
