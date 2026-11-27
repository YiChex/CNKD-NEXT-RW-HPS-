/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core.server

import net.rwhps.server.data.global.Data
import net.rwhps.server.data.temp.ConnectReceiveData
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.GroupNet
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.util.Time
import net.rwhps.server.util.annotations.core.DependsClassLoader
import net.rwhps.server.util.log.Log
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Realize basic network information packaging
 * The game protocol compulsory inheritance of this class
 * @author Dr (dr@der.kim)
 * @date 2021/12/16 08:55:26
 */
@DependsClassLoader
abstract class AbstractNetConnect(protected val connectionAgreement: ConnectionAgreement) {
    val rwHps = connectionAgreement.rwHps
    /**
     * Get connection IP
     * @return IP
     */
    val ip: String
        get() = connectionAgreement.ip

    val ipLong24: String
        get() = connectionAgreement.ipLong24

    /**
     * Get connection IP Country
     * @return IP
     */
    val ipCountry: String
        get() = connectionAgreement.ipCountry

    val ipCountryAll: String
        get() = connectionAgreement.ipCountryAll

    /**
     * Get the local port used
     * @return Port
     */
    val port: Int
        get() = connectionAgreement.localPort

    /**
     * Stable identity of this *transport connection*.
     *
     * `ConnectionAgreement.id` is a fresh `UUID.randomUUID()` per accepted connection
     * (`ConnectionAgreement.kt:51`), so unlike `GameVersionServer.hashCode()` — which delegates to
     * `PlayerHess.hashCode()` = `Objects.hash(connectHexID)` — this value changes on every
     * reconnect. Callers that need per-connection (rather than per-player) bookkeeping must key on
     * this value.
     *
     * `connectionAgreement` itself is `protected`, so this accessor is the only way for code
     * holding an `AbstractNetConnect` (e.g. obtained via `coverConnect()`) to read it.
     */
    val connectionId: String
        get() = connectionAgreement.id

    /** 尝试数 */
    var numberOfRetries = 0


    /**
     * Set up try
     * To Get try status
     * @return Boolean
     */
    var tryBoolean: Boolean = false

    var isDis: Boolean = false

    val connectReceiveData: ConnectReceiveData = ConnectReceiveData()

    /**
     * last time to Received Packet
     * @return Time
     */
    @Volatile
    var lastReceivedTime: Long = Time.concurrentMillis()
        private set

    fun lastReceivedTime() {
        lastReceivedTime = Time.concurrentMillis()
        connectReceiveData.receiveBigPacket = false
    }

    /**
     * Get connection agreement
     * @return Protocol
     */
    val useConnectionAgreement: String
        get() = connectionAgreement.useAgreement

    /**
     * Protocol version
     * @return version number
     */
    abstract val version: String

    /**
     * Disconnect
     */
    abstract fun disconnect()

    /**
     * Send package
     * @param packet Data
     */
    fun sendPacket(packet: Packet) {
        sendPacketChecked(packet)
    }

    /**
     * Send package and report whether the transport actually accepted it.
     *
     * [sendPacket] keeps its original `Unit` signature and behaviour for the ~24 existing call
     * sites, but it cannot report failure: it swallows the exception (after calling [disconnect]).
     * Callers that must distinguish "handed to the transport" from "failed" — such as the
     * advertising redirect path — have to use this variant instead of wrapping [sendPacket] in
     * `runCatching`, whose `onFailure` branch is unreachable.
     *
     * @return `true` when the packet was written to the transport, `false` when it failed (in which
     *         case the connection has already been disconnected, exactly as before).
     */
    fun sendPacketChecked(packet: Packet): Boolean {
        return try {
            connectionAgreement.send(packet)
            true
        } catch (e: Exception) {
            disconnect()
            if (connectionAgreement.useAgreement == "UDP") {
                Log.error("[${connectionAgreement.useAgreement}] SendError - 本消息单独出现无妨 连续多次出现请debug", e)
            }
            false
        }
    }

    /**
     * Receive package
     * 选择向下传递
     *
     * @param packet Data
     */
    open fun receivePacket(packet: Packet) {
        //
    }

    /**
     * Debug Special development not open temporarily
     * @param packet Packet
     */
    fun debug(packet: Packet) {
        try {
            GameInputStream(packet).use { stream ->
                Data.LOG_COMMAND.handleMessage(
                        URLDecoder.decode(
                                stream.readString(), StandardCharsets.UTF_8.toString()
                        ), this
                )
            }
        } catch (_: IOException) {
            sendDebug("Error")
        }
    }

    /**
     * Debug Special development not open temporarily
     * @param str String
     */
    fun sendDebug(str: String) {
        try {
            val o = GameOutputStream()
            o.writeString(str)
            sendPacket(o.createPacket(PacketType.SERVER_DEBUG))
        } catch (_: Exception) {
            // Ignore
        }
    }

    /**
     * @param packet Packet
     */
    fun exCommand(packet: Packet): Boolean {
        val read = GameInputStream(packet)
        when (packet.type) {
            PacketType.SERVER_DEBUG_RECEIVE -> debug(packet)
            PacketType.GET_SERVER_INFO_RECEIVE -> Data.PING_COMMAND.handleMessage(read.readString(), this)
            else -> return false
        }
        return true
    }

    protected fun close(groupNet: GroupNet?) {
        try {
            connectionAgreement.close(groupNet)
        } catch (e: Exception) {
            Log.error("Close Connect", e)
        }
    }
}