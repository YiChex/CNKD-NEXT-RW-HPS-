/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core.server

import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.net.core.DataPermissionStatus
import net.rwhps.server.util.annotations.mark.PrivateMark
import java.io.IOException

/**
 * Only provide interface but not implement
 * As the interface of game CoreNet, it provides various version support for GameRelay
 *
 * @date 2021/7/31/ 14:14
 * @author Dr (dr@der.kim)
 */
@PrivateMark
interface AbstractNetConnectRelay {
    /** Safety Certificate */
    val permissionStatus: DataPermissionStatus.RelayStatus

    /**
     * Get the instance of Relay, null if none
     * @return Relay
     */
    val room: RelayRoom?

    fun setCachePacket(packet: Packet)

    /**
     * Cache the last sent packet
     * @param packet Packet
     */
    fun setlastSentPacket(packet: Packet)

    /**
     * Server handshake
     */
    fun sendRelayServerInfo()

    /**
     * Inspection ID Direct join Server
     * @param relayRoom Relay
     * @throws IOException Error
     */
    @Throws(IOException::class)
    fun relayDirectInspection(relayRoom: RelayRoom? = null)

    fun relayRegisterConnection(packet: Packet)

    /**
     * Check if Relay's checksum is correct - send
     */
    fun sendVerifyClientValidity()

    /**
     * Check whether the check of Relay is correct - check
     */
    fun receiveVerifyClientValidity(packet: Packet): Boolean

    /**
     * Server type
     * @param msg RelayID
     */
    fun sendRelayServerType(msg: String, run: ((String) -> Unit)? = null)

    /**
     * Type Reply
     */
    fun sendRelayServerTypeReply(packet: Packet)

    /**
     * Set up RELAY HOST
     */
    fun sendRelayServerId(multicast: Boolean = false)

    /**
     * Accept language pack
     * @param p Packet
     * @throws IOException Error
     */
    @Throws(IOException::class)
    fun receiveChat(packet: Packet)

    fun receiveCommand(packet: Packet)

    /**
     * Send a message
     */
    fun getRelayT4(msg: String)

    /**
     * Return Ping Packet
     * @param packet packet
     */
    fun getPingData(packet: Packet)

    /**
     * Group Package
     * @param packet Packet
     */
    fun addGroup(packet: Packet)

    /**
     * Group Ping Package
     * @param packet Packet
     */
    fun addGroupPing(packet: Packet)

    /**
     * Connect to Relay server
     */
    fun addRelayConnect()

    /**
     * Reconnect to Relay server
     */
    fun addReRelayConnect()

    /**
     * Parse the package
     * @param packet Packet
     */
    fun addRelaySend(packet: Packet)

    fun sendPackageToHOST(packet: Packet)

    /**
     * Send RELAY to HOST Player disconnected
     */
    fun relayPlayerDisconnect()

    /**
     * Multicast Multiplexing
     * @param packet Packet
     */
    fun multicastAnalysis(packet: Packet)
}
