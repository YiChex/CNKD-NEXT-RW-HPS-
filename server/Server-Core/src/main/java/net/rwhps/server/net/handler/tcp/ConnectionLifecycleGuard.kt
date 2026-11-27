/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler.tcp

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import net.rwhps.server.io.packet.type.AbstractPacketType
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.util.log.Log
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal object ConnectionLifecycleGuard {
    private const val DEFAULT_HANDSHAKE_IDLE_SECONDS = 15L
    private const val DEFAULT_MAX_GLOBAL_PENDING_CONNECTIONS = 512
    private const val DEFAULT_MAX_PER_IP_ACTIVE_CONNECTIONS = 64
    private const val DEFAULT_MAX_PER_IP_CONNECTS_PER_WINDOW = 128
    private const val DEFAULT_CONNECT_WINDOW_MILLIS = 10_000L
    private const val DEFAULT_TEMP_BLOCK_MILLIS = 30_000L
    private const val DEFAULT_PRE_HANDSHAKE_MAX_PACKET_BYTES = 1_048_576
    /**
     * P0-3: 8 MiB. Previously 50_000_000, which made
     * `maxPerIpActiveConnections x establishedMaxPacketBytes` =
     * 24 x 50_000_000 = 1_200_000_000 B exceed the `-Xmx1024m` (1_073_741_824 B)
     * used by the shipped start scripts. See [maxSafeEstablishedPacketBytes].
     */
    private const val DEFAULT_ESTABLISHED_MAX_PACKET_BYTES = 8 * 1024 * 1024
    /**
     * Upper bound accepted for `cnkd.netguard.establishedMaxPacketBytes`. Kept at the historical
     * hard cap so operators can still opt into larger packets; the default is now [DEFAULT_ESTABLISHED_MAX_PACKET_BYTES].
     */
    private const val HARD_MAX_PACKET_BYTES = 50_000_000
    /** Share of the JVM max heap that network payload buffers may occupy. */
    private const val NETWORK_BUDGET_HEAP_DIVISOR = 4L
    private const val MIN_NETWORK_PAYLOAD_BUDGET_BYTES = 64L * 1024 * 1024
    private const val MAX_NETWORK_PAYLOAD_BUDGET_BYTES = 1024L * 1024 * 1024
    private const val DEFAULT_PACKET_ASSEMBLY_TIMEOUT_MILLIS = 8_000L
    private const val DEFAULT_ESTABLISHED_PACKET_ASSEMBLY_TIMEOUT_MILLIS = 60_000L
    private const val LOG_INTERVAL_MILLIS = 30_000L

    private val handshakeIdleSeconds = longProperty("cnkd.netguard.handshakeIdleSeconds", DEFAULT_HANDSHAKE_IDLE_SECONDS, 5L, 60L)
    private val maxGlobalPendingConnections = intProperty("cnkd.netguard.maxGlobalPending", DEFAULT_MAX_GLOBAL_PENDING_CONNECTIONS, 64, 4096)
    private val maxPerIpActiveConnections = intProperty("cnkd.netguard.maxPerIpActive", DEFAULT_MAX_PER_IP_ACTIVE_CONNECTIONS, 4, 256)
    private val maxPerIpConnectsPerWindow = intProperty("cnkd.netguard.maxPerIpConnectsPerWindow", DEFAULT_MAX_PER_IP_CONNECTS_PER_WINDOW, 8, 512)
    private val connectWindowMillis = longProperty("cnkd.netguard.connectWindowMillis", DEFAULT_CONNECT_WINDOW_MILLIS, 1_000L, 60_000L)
    private val tempBlockMillis = longProperty("cnkd.netguard.tempBlockMillis", DEFAULT_TEMP_BLOCK_MILLIS, 1_000L, 300_000L)
    private val preHandshakeMaxPacketBytes = intProperty(
        listOf("cnkd.netguard.preHandshakeMaxPacketBytes", "cnkd.netguard.preAuthMaxPacketBytes"),
        DEFAULT_PRE_HANDSHAKE_MAX_PACKET_BYTES,
        4_096,
        10 * 1024 * 1024
    )
    private val packetAssemblyTimeoutMillis = longProperty(
        "cnkd.netguard.packetAssemblyTimeoutMillis",
        DEFAULT_PACKET_ASSEMBLY_TIMEOUT_MILLIS,
        1_000L,
        60_000L
    )
    private val establishedPacketAssemblyTimeoutMillis = longProperty(
        "cnkd.netguard.establishedPacketAssemblyTimeoutMillis",
        DEFAULT_ESTABLISHED_PACKET_ASSEMBLY_TIMEOUT_MILLIS,
        packetAssemblyTimeoutMillis,
        300_000L
    )
    private val establishedMaxPacketBytes = intProperty(
        "cnkd.netguard.establishedMaxPacketBytes",
        DEFAULT_ESTABLISHED_MAX_PACKET_BYTES,
        preHandshakeMaxPacketBytes,
        HARD_MAX_PACKET_BYTES
    )
    /**
     * P0-3: aggregate cap on payload bytes simultaneously reserved by the packet decoders.
     *
     * A per-connection cap alone cannot bound memory: `maxGlobalPendingConnections` connections
     * could each declare a maximum-size packet. This budget bounds the sum, so raising
     * `establishedMaxPacketBytes` for a legitimate large-packet workload can no longer exceed the heap.
     */
    private val maxInFlightPayloadBytes = longProperty(
        "cnkd.netguard.maxInFlightPayloadBytes",
        defaultNetworkPayloadBudgetBytes(),
        MIN_NETWORK_PAYLOAD_BUDGET_BYTES,
        MAX_NETWORK_PAYLOAD_BUDGET_BYTES
    )
    private val inFlightPayloadBytes = AtomicLong(0L)

    private fun defaultNetworkPayloadBudgetBytes(): Long {
        val maxHeap = Runtime.getRuntime().maxMemory()
        val budget = if (maxHeap <= 0L || maxHeap == Long.MAX_VALUE) {
            MIN_NETWORK_PAYLOAD_BUDGET_BYTES
        } else {
            maxHeap / NETWORK_BUDGET_HEAP_DIVISOR
        }
        return budget.coerceIn(MIN_NETWORK_PAYLOAD_BUDGET_BYTES, MAX_NETWORK_PAYLOAD_BUDGET_BYTES)
    }

    private val remoteIpKey = AttributeKey.valueOf<String>("cnkd.remoteIp")
    private val registeredKey = AttributeKey.valueOf<Boolean>("cnkd.lifecycleRegistered")
    private val protocolEstablishedKey = AttributeKey.valueOf<Boolean>("cnkd.protocolEstablished")
    private val globalActive = AtomicInteger()
    private val ipStates = ConcurrentHashMap<String, IpState>()

    fun onChannelRegistered(channel: Channel): Admission {
        val ip = remoteIp(channel)
        channel.attr(remoteIpKey).set(ip)

        val now = System.currentTimeMillis()
        val state = ipStates.computeIfAbsent(ip) { IpState() }
        synchronized(state) {
            if (state.blockUntilMillis > now) {
                logBlocked(ip, state, "temp_block")
                return Admission.reject("temp_block")
            }
            if (state.windowStartMillis <= 0L || now - state.windowStartMillis > connectWindowMillis) {
                state.windowStartMillis = now
                state.windowCount = 0
            }
            state.windowCount += 1
            if (state.windowCount > maxPerIpConnectsPerWindow) {
                state.blockUntilMillis = now + tempBlockMillis
                logBlocked(ip, state, "connect_rate")
                return Admission.reject("connect_rate")
            }
            if (state.activeCount >= maxPerIpActiveConnections) {
                state.blockUntilMillis = now + tempBlockMillis
                logBlocked(ip, state, "ip_active_limit")
                return Admission.reject("ip_active_limit")
            }
            val currentGlobal = globalActive.incrementAndGet()
            if (currentGlobal > maxGlobalPendingConnections) {
                globalActive.decrementAndGet()
                state.blockUntilMillis = now + tempBlockMillis
                logBlocked(ip, state, "global_active_limit")
                return Admission.reject("global_active_limit")
            }
            state.activeCount += 1
            channel.attr(registeredKey).set(true)
            return Admission.accept(ip)
        }
    }

    fun onChannelUnregistered(channel: Channel): Boolean {
        channel.attr(protocolEstablishedKey).set(false)
        if (channel.attr(registeredKey).getAndSet(false) != true) {
            return false
        }
        globalActive.updateAndGet { (it - 1).coerceAtLeast(0) }
        val ip = channel.attr(remoteIpKey).get() ?: remoteIp(channel)
        val state = ipStates[ip] ?: return true
        synchronized(state) {
            state.activeCount = (state.activeCount - 1).coerceAtLeast(0)
            if (state.activeCount == 0 && state.blockUntilMillis < System.currentTimeMillis()) {
                ipStates.remove(ip, state)
            }
        }
        return true
    }

    fun activeConnectionCount(): Int = globalActive.get()

    fun handshakeIdleSeconds(): Long = handshakeIdleSeconds

    fun preHandshakeMaxPacketBytes(): Int = preHandshakeMaxPacketBytes

    fun establishedMaxPacketBytes(): Int = establishedMaxPacketBytes

    fun packetAssemblyTimeoutMillis(channel: Channel): Long {
        return if (isProtocolEstablished(channel)) {
            establishedPacketAssemblyTimeoutMillis
        } else {
            packetAssemblyTimeoutMillis
        }
    }

    fun markProtocolEstablished(channel: Channel) {
        channel.attr(protocolEstablishedKey).set(true)
    }

    /**
     * P0-3: gate for [markProtocolEstablished].
     *
     * The promotion previously happened on the very first decodable packet, and the header alone
     * decides that (`length=0,type=0` is a well-formed 8-byte frame), so a bare 8-byte frame used to
     * raise the per-connection limit from [preHandshakeMaxPacketBytes] to [establishedMaxPacketBytes].
     * Require a non-degenerate, resolved game packet instead.
     *
     * This blocks the trivial frame only. It is deliberately *not* treated as a security boundary:
     * a peer can forge a non-empty packet, which is why the authoritative bound is the aggregate
     * [maxInFlightPayloadBytes] budget rather than this predicate.
     */
    fun isHandshakeEstablishedPacket(packetType: AbstractPacketType, payloadBytes: Int): Boolean {
        if (payloadBytes <= 0) {
            return false
        }
        return packetType != PacketType.EMPTYP_ACKAGE && packetType != PacketType.NOT_RESOLVED
    }

    fun isProtocolEstablished(channel: Channel): Boolean {
        return channel.attr(protocolEstablishedKey).get() == true
    }

    /**
     * P0-3: reserve [bytes] against the aggregate payload budget.
     *
     * Only a reservation that fits is counted, so [currentInFlightPayloadBytes] never exceeds
     * [maxInFlightPayloadBytes]. Callers decide what a failed reservation means: the decoder only
     * rejects *large* packets, so draining the budget cannot deny service to ordinary small traffic.
     *
     * @return `true` when the reservation was made, `false` when the budget is exhausted.
     */
    fun tryReserveInFlightPayloadBytes(bytes: Int): Boolean {
        if (bytes <= 0) {
            return true
        }
        while (true) {
            val current = inFlightPayloadBytes.get()
            val next = current + bytes
            if (next > maxInFlightPayloadBytes) {
                return false
            }
            if (inFlightPayloadBytes.compareAndSet(current, next)) {
                return true
            }
        }
    }

    fun releaseInFlightPayloadBytes(bytes: Long) {
        if (bytes > 0L) {
            inFlightPayloadBytes.addAndGet(-bytes)
        }
    }

    fun inFlightPayloadBudgetBytes(): Long = maxInFlightPayloadBytes

    fun currentInFlightPayloadBytes(): Long = inFlightPayloadBytes.get()

    private fun remoteIp(channel: Channel): String {
        val address = channel.remoteAddress()
        return when (address) {
            is InetSocketAddress -> address.address?.hostAddress ?: address.hostString
            else -> address?.toString()?.substringBeforeLast(':')?.trim('/') ?: "unknown"
        }.removePrefix("::ffff:")
    }

    private fun logBlocked(ip: String, state: IpState, reason: String) {
        val now = System.currentTimeMillis()
        if (now - state.lastLogAtMillis < LOG_INTERVAL_MILLIS) {
            return
        }
        state.lastLogAtMillis = now
        Log.warn(
            "[NetGuard] Rejected connection: reason=$reason, ip=$ip, ipActive=${state.activeCount}, " +
                "ipWindow=${state.windowCount}, globalActive=${globalActive.get()}"
        )
    }

    private fun intProperty(name: String, defaultValue: Int, min: Int, max: Int): Int {
        return System.getProperty(name)
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(min, max)
            ?: defaultValue.coerceIn(min, max)
    }

    private fun intProperty(names: List<String>, defaultValue: Int, min: Int, max: Int): Int {
        return names.asSequence()
            .mapNotNull { System.getProperty(it)?.trim()?.toIntOrNull() }
            .firstOrNull()
            ?.coerceIn(min, max)
            ?: defaultValue.coerceIn(min, max)
    }

    private fun longProperty(name: String, defaultValue: Long, min: Long, max: Long): Long {
        return System.getProperty(name)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(min, max)
            ?: defaultValue.coerceIn(min, max)
    }

    data class Admission(
        val accepted: Boolean,
        val ip: String,
        val reason: String
    ) {
        companion object {
            fun accept(ip: String) = Admission(true, ip, "")
            fun reject(reason: String) = Admission(false, "", reason)
        }
    }

    private class IpState {
        var activeCount: Int = 0
        var windowStartMillis: Long = 0L
        var windowCount: Int = 0
        var blockUntilMillis: Long = 0L
        var lastLogAtMillis: Long = 0L
    }
}
