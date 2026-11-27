/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.code.tcp

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.NetService
import net.rwhps.server.net.NetService.Companion.headerSize
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.handler.tcp.ConnectionLifecycleGuard
import net.rwhps.server.util.log.Log.warn

/**
 * Parse game packets.
 *
 * Packet format:
 * - Int payload length
 * - Int packet type
 * - payload bytes
 */
internal class GamePacketDecoder(
    private val rwHps: IRwHps
): ByteToMessageDecoder() {

    private var readPacketLengthCache = headerSize
    private var readPacketTypeCache = -1
    private var stopRead = false
    private var packetAssemblyStartedAtMillis = 0L
    private var lastUnknownProtocolWarnAt = 0L
    /**
     * P0-3: bytes currently reserved from the aggregate in-flight payload budget for this decoder.
     * Zero whenever no packet payload is being assembled.
     */
    private var reservedInFlightPayloadBytes = 0

    @Throws(Exception::class)
    override fun decode(ctx: ChannelHandlerContext, bufferIn: ByteBuf, out: MutableList<Any>) {
        if (stopRead) {
            return
        }

        fun stopReadAndClose(reason: String) {
            val assemblyElapsedMillis = if (packetAssemblyStartedAtMillis > 0L) {
                System.currentTimeMillis() - packetAssemblyStartedAtMillis
            } else {
                0L
            }
            stopRead = true
            packetAssemblyStartedAtMillis = 0L
            releaseReservedPayloadBytes()
            /*
             * FIX-PROTOCOL-LOG-READABILITY: 追加前 8 字节的 ASCII 预览。
             *
             * 线上常见一条 WARN：
             *   reason=packet_too_large, expectedPayloadBytes=1195725856,
             *   packetType=790644820
             * 两个数字看起来像"游戏包头离谱"，实际是 **HTTP 扫描器**：
             *   1195725856 = 0x47455420 = "GET "
             *    790644820 = 0x2F204854 = "/ HT"
             * 合起来 TCP 前 8 字节 = 47 45 54 20 2F 20 48 54 = "GET / HT"
             * 即 `GET / HTTP/1.1` 被当成 [int32 length][int32 type] 读取。
             *
             * 实测（2026-10-21，R2）：本机发 HTTP 请求可 1:1 复现这两个数值；
             * 合法客户端首包与 TICK 小包 0 误伤。
             *
             * 加上预览后，运维一眼能看出是外部扫描而非游戏协议 bug。
             * 只在关闭连接时算一次，不影响热路径。
             */
            warn(
                "[GamePacketDecoder] Closing connection: reason=$reason, " +
                    "protocolEstablished=${ConnectionLifecycleGuard.isProtocolEstablished(ctx.channel())}, " +
                    "bufferedBytes=${bufferIn.readableBytes()}, expectedPayloadBytes=$readPacketLengthCache, " +
                    "packetType=$readPacketTypeCache, assemblyElapsedMillis=$assemblyElapsedMillis, " +
                    "headPreview=${describeHeadBytes(bufferIn)}"
            )
            bufferIn.clear()
            ctx.close()
        }

        fun markPacketAssemblyStartedIfNeeded() {
            if (packetAssemblyStartedAtMillis <= 0L) {
                packetAssemblyStartedAtMillis = System.currentTimeMillis()
            }
        }

        fun isPacketAssemblyTimedOut(): Boolean {
            val startedAt = packetAssemblyStartedAtMillis
            return startedAt > 0L &&
                System.currentTimeMillis() - startedAt > ConnectionLifecycleGuard.packetAssemblyTimeoutMillis(ctx.channel())
        }

        if (bufferIn.readableBytes() > 0) {
            markPacketAssemblyStartedIfNeeded()
        }

        if (bufferIn.readableBytes() < readPacketLengthCache) {
            if (isPacketAssemblyTimedOut()) {
                stopReadAndClose("packet_assembly_timeout")
            }
            return
        }

        if (readPacketLengthCache == headerSize && readPacketTypeCache == -1) {
            readPacketLengthCache = bufferIn.readInt()
            readPacketTypeCache = bufferIn.readInt()

            if (readPacketLengthCache < 0 || readPacketTypeCache < 0) {
                stopReadAndClose("invalid_header")
                return
            }

            if (readPacketLengthCache > NetService.maxPacketSizt) {
                stopReadAndClose("packet_too_large")
                return
            }

            val allowedPacketBytes = if (ConnectionLifecycleGuard.isProtocolEstablished(ctx.channel())) {
                ConnectionLifecycleGuard.establishedMaxPacketBytes()
            } else {
                ConnectionLifecycleGuard.preHandshakeMaxPacketBytes()
            }
            if (readPacketLengthCache > allowedPacketBytes) {
                stopReadAndClose("packet_too_large_for_connection_stage")
                return
            }

            // P0-3: bound the sum of payloads declared across all connections, not just each one.
            // Reserved before waiting for the rest of the payload and held until the packet is read
            // or the connection dies, so a slow sender cannot park the budget forever.
            // Only *large* packets are rejected when the budget is exhausted: ordinary small game
            // traffic is already bounded by the per-connection cap, so draining the budget must not
            // let an attacker deny service to normal players.
            if (reservedInFlightPayloadBytes == 0) {
                if (ConnectionLifecycleGuard.tryReserveInFlightPayloadBytes(readPacketLengthCache)) {
                    reservedInFlightPayloadBytes = readPacketLengthCache
                } else if (readPacketLengthCache > ConnectionLifecycleGuard.preHandshakeMaxPacketBytes()) {
                    // Only large packets are refused when the budget is exhausted; small ones proceed
                    // uncounted, so draining the budget cannot deny service to normal players.
                    stopReadAndClose("in_flight_payload_budget_exhausted")
                    return
                }
            }

            if (bufferIn.readableBytes() < readPacketLengthCache) {
                if (isPacketAssemblyTimedOut()) {
                    stopReadAndClose("packet_assembly_timeout")
                }
                return
            }
        }

        try {
            val packetType = IRwHps.packetType.from(readPacketTypeCache)
            val b = ByteArray(readPacketLengthCache)
            bufferIn.readBytes(b)
            if (packetType == PacketType.NOT_RESOLVED) {
                val now = System.currentTimeMillis()
                if (now - lastUnknownProtocolWarnAt >= UNKNOWN_PROTOCOL_WARN_INTERVAL_MILLIS) {
                    lastUnknownProtocolWarnAt = now
                    warn("[GamePacketDecoder] Unknown protocol preserved for forwarding", "Type : $readPacketTypeCache")
                }
                out.add(Packet(readPacketTypeCache, b))
            } else {
                out.add(Packet(packetType, b))
            }
        } finally {
            readPacketLengthCache = headerSize
            readPacketTypeCache = -1
            packetAssemblyStartedAtMillis = 0L
            releaseReservedPayloadBytes()
        }
    }

    /**
     * P0-3: return this decoder's reservation to the aggregate payload budget.
     * Idempotent, so it is safe to call from both the success path and every close path.
     */
    private fun releaseReservedPayloadBytes() {
        val reserved = reservedInFlightPayloadBytes
        if (reserved > 0) {
            reservedInFlightPayloadBytes = 0
            ConnectionLifecycleGuard.releaseInFlightPayloadBytes(reserved.toLong())
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        releaseReservedPayloadBytes()
        super.channelInactive(ctx)
    }

    companion object {
        private const val UNKNOWN_PROTOCOL_WARN_INTERVAL_MILLIS = 30_000L

        /**
         * FIX-PROTOCOL-LOG-READABILITY: 把缓冲区前 8 字节渲染成可读形式。
         *
         * 目的：让 `packet_too_large` 这类 WARN 一眼能看出对端发的是什么协议，
         * 而不是只看到两个"离谱的游戏包头数值"。
         *
         * 输出形如 `GET / HT | 47 45 54 20 2F 20 48 54`：
         *   - 可打印 ASCII（0x20..0x7E）原样显示
         *   - 其余显示 `.`
         * 非破坏性：只读不消费缓冲区，且不移动 readerIndex。
         */
        private fun describeHeadBytes(buffer: ByteBuf, maxBytes: Int = 8): String {
            return try {
                val readable = buffer.readableBytes()
                if (readable <= 0) {
                    "(empty)"
                } else {
                    val count = minOf(readable, maxBytes)
                    val ascii = StringBuilder(count)
                    val hex = StringBuilder(count * 3)
                    for (i in 0 until count) {
                        val b = buffer.getByte(buffer.readerIndex() + i).toInt() and 0xFF
                        ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
                        if (i > 0) hex.append(' ')
                        hex.append(String.format("%02X", b))
                    }
                    "$ascii | $hex"
                }
            } catch (_: Throwable) {
                "(unavailable)"
            }
        }
    }
}
