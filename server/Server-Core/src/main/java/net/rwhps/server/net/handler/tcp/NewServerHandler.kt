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
import net.rwhps.server.game.event.global.NetConnectNewEvent
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.net.core.AbstractNet
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.INetServerHandler
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.debug
import net.rwhps.server.util.log.Log.error
import net.rwhps.server.util.log.exp.ExceptionX
import java.util.concurrent.TimeUnit

/**
 *
 * @author Dr (dr@der.kim)
 */
@Sharable
internal class NewServerHandler(abstractNet: AbstractNet): INetServerHandler(abstractNet) {

    companion object {
        /**
         * Upper bound for the new-connection event on the network thread. Generous enough for any
         * legitimate listener, short enough that a hung one cannot stall every player.
         */
        private const val CONNECT_EVENT_TIMEOUT_MILLIS = 5_000L
    }

    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any?) {
        if (msg == null) {
            return
        }

        try {
            if (msg is Packet) {
                val attr = abstractNet.getTypeConnect(ctx.channel())
                var type = attr.get()

                if (type == null) {
                    val connectionAgreement = ConnectionAgreement(ctx, abstractNet.nettyChannelData, abstractNet.rwHps)
                    type = abstractNet.rwHps.typeConnect.getTypeConnect(connectionAgreement)
                    attr.set(type)

                    val newConnectEvent = NetConnectNewEvent(connectionAgreement)
                    /*
                     * A2-P3/B3 (independent verification): this used to be an UNBOUNDED
                     * `await()`, which is `runBlocking { future.await() }` on the CALLING thread.
                     * The caller here is a Netty worker, and production runs
                     * nettyWorkerThreadsOverride=1 -- a single worker for every connection. So one
                     * slow (or hung) NetConnectNewEvent listener would stall EVERY player's packet
                     * handling, which matches the reported "still have to wait for others to join".
                     *
                     * Bounded now: if listeners do not finish in time we proceed to serve the
                     * connection rather than freezing the whole server on one listener. The event
                     * result is only consulted to decide whether to reject the connection, and
                     * timing out means "no listener objected", which is the safe default.
                     */
                    val eventCompleted = PluginManage.runGlobalEventManage(newConnectEvent)
                        .await(CONNECT_EVENT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    if (!eventCompleted) {
                        Log.warn(
                            "[NetLifecycle] NetConnectNewEvent did not complete within " +
                                "${CONNECT_EVENT_TIMEOUT_MILLIS}ms; continuing to serve the connection " +
                                "so one slow listener cannot stall the network thread"
                        )
                    }
                    if (newConnectEvent.result) {
                        closeAndDisconnect(ctx)
                        return
                    }
                }
                // P0-3: promote the per-connection packet limit only for a non-degenerate resolved
                // packet. A bare `length=0,type=0` frame is a well-formed 8-byte frame but carries no
                // handshake evidence. This is evaluated for every packet rather than only the first
                // one, so a degenerate opening frame cannot strand a legitimate connection at the
                // pre-handshake limit.
                if (!ConnectionLifecycleGuard.isProtocolEstablished(ctx.channel()) &&
                    ConnectionLifecycleGuard.isHandshakeEstablishedPacket(msg.type, msg.bytes.size)
                ) {
                    ConnectionLifecycleGuard.markProtocolEstablished(ctx.channel())
                }
                try {
                    type.processConnect(msg)
                } catch (e: Exception) {
                    debug(e)
                }
            }
        } catch (ss: Exception) {
            error(ss)
            closeAndDisconnect(ctx)
        }
    }


    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        cause?.let {
            error(ExceptionX.resolveTrace(it))
        }
        closeAndDisconnect(ctx)
    }

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        try {
            super.channelInactive(ctx)
        } finally {
            closeAndDisconnect(ctx)
        }
    }

    private fun closeAndDisconnect(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        val attr = abstractNet.getTypeConnect(channel)
        val type = attr.get()
        attr.set(null)
        runCatching {
            if (channel.isOpen) {
                channel.close()
            }
            ctx.close()
        }.onFailure {
            debug(it)
        }
        runCatching {
            type?.abstractNetConnect?.disconnect()
        }.onFailure {
            debug(it)
        }
    }
}
