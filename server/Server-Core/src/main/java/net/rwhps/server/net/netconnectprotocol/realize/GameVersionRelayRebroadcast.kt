/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.netconnectprotocol.realize

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.util.annotations.mark.PrivateMark
import java.io.IOException
import java.util.*

/**
 * Many thanks to them for providing cloud computing for the project
 * This is essential to complete the RW-HPS Relay test
 * @Thanks : [SimpFun Cloud](https://vps.tiexiu.xyz)
 * @Thanks : [Github 1dNDN](https://github.com/1dNDN)
 *
 * This test was done on :
 * Relay-CN (V. 6.1.0)
 * 2022.7.22 10:00
 */

/**
 * Relay protocol implementation
 * Can realize multicast and save bandwidth
 *
 * @property lastSentPacket Last packet sent
 * @property version        Protocol version
 * @constructor
 *
 * @author Dr (dr@der.kim)
 */
@PrivateMark
class GameVersionRelayRebroadcast(connectionAgreement: ConnectionAgreement): GameVersionRelay(connectionAgreement) {
    // last Unwrapped Or Normal Packet From Server
    @Volatile
    private lateinit var lastSentPacket: Packet

    override val version: String
        get() = "1.15 RELAY Rebroadcast"

    override fun setlastSentPacket(packet: Packet) {
        lastSentPacket = packet
    }

    override fun sendRelayServerId(multicast: Boolean) {
        super.sendRelayServerId(true)
    }

    override fun addRelaySend(packet: Packet) {
        try {
            GameInputStream(packet).use { inStream ->
                val target = inStream.readInt()

                val type = inStream.readInt()
                val bytes = inStream.readStreamBytes()

                val abstractNetConnect = room!!.getAbstractNetConnect(target)

                Packet(type, bytes).let { sendPacketData ->
                    lastSentPacket = sendPacketData
                    abstractNetConnect?.sendPacket(sendPacketData)
                    sendPacketExtractInformation(sendPacketData, abstractNetConnect)
                }
            }
        } catch (_: IOException) {/* 忽略 */
        } catch (_: NullPointerException) {/* 忽略 */
        }
    }

    @Throws(IOException::class)
    override fun multicastAnalysis(packet: Packet) {
        try {
            GameInputStream(packet).use { stream ->
                val target = stream.readInt()
                room!!.getAbstractNetConnect(target)?.sendPacket(lastSentPacket)
            }
        } catch (_: IOException) {/* 忽略 */
        }
    }
}
