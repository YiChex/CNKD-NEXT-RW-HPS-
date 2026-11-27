/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.netconnectprotocol

import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType.*
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.DataPermissionStatus.RelayStatus.*
import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.netconnectprotocol.internal.relay.fromRelayJumpsToAnotherServerInternalPacket
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.game.command.CommandHandler

/**
 * Parse the [net.rwhps.server.net.core.IRwHps.NetType.RelayProtocol] protocol
 * @property con                GameVersionRelay
 * @property conClass           Initialize
 * @property abstractNetConnect AbstractNetConnect
 * @property version            Parser version
 * @author Dr (dr@der.kim)
 */
@PrivateMark
open class TypeRelay: TypeConnect {
    val con: GameVersionRelay
    var conClass: Class<out GameVersionRelay>? = null

    override val abstractNetConnect: AbstractNetConnect
        get() = con

    constructor(con: GameVersionRelay) {
        this.con = con
    }

    constructor(con: Class<out GameVersionRelay>) {
        // will not be used ; just override the initial value to avoid refusing to compile
        this.con = ReflectionUtils.accessibleConstructor(con, ConnectionAgreement::class.java).newInstance(ConnectionAgreement())

        // use for instantiation
        conClass = con
    }

    override fun getTypeConnect(connectionAgreement: ConnectionAgreement): TypeConnect {
        return TypeRelay(
                ReflectionUtils.accessibleConstructor(conClass!!, ConnectionAgreement::class.java).newInstance(connectionAgreement)
        )
    }

    @Throws(Exception::class)
    override fun processConnect(packet: Packet) {
        if (relayCheck(packet)) {
            return
        }

        packetProcessing(packet)
    }

    @Throws(Exception::class)
    protected open fun hostProcessing(packet: Packet) {
        when (packet.type) {
            PACKET_FORWARD_CLIENT_TO -> {
                con.addRelaySend(packet)
                return
            }

            SERVER_INFO, START_GAME, RETURN_TO_BATTLEROOM -> {
                con.sendPacketExtractInformation(packet, con)
                return
            }

            HEART_BEAT -> {
                con.getPingData(packet)
            }

            CHAT -> {
                GameInputStream(packet).use {
                    val message = it.readString()
                    it.skip(1)
                    if (it.readIsString() == con.name && message.startsWith(".")) {
                        val response = Data.RELAY_COMMAND.handleMessage(message, con)
                        if (response == null || response.type == CommandHandler.ResponseType.noCommand) {
                            // Ignore
                        } else if (response.type != CommandHandler.ResponseType.valid) {
                            val text: String = when (response.type) {
                                CommandHandler.ResponseType.manyArguments -> "Too many arguments. Usage: " + response.command.text + " " + response.command.paramText
                                CommandHandler.ResponseType.fewArguments -> "Too few arguments. Usage: " + response.command.text + " " + response.command.paramText
                                else -> return@use
                            }
                            con.sendPacket(con.rwHps.abstractNetPacket.getSystemMessagePacket(text))
                        }
                    }
                }
                return
            }

            DISCONNECT -> con.disconnect()

            else -> {
                // Relay HOST should no longer send any more packets for the server to process
                // Ignore
            }
        }
    }

    private fun packetProcessing(packet: Packet) {
        if (con.permissionStatus == HostPermission) {
            hostProcessing(packet)
        } else {
            normalProcessing(packet)
        }
    }

    @Throws(Exception::class)
    private fun normalProcessing(packet: Packet) {
        when (packet.type) {
            // USERS (NON-HOST) SHOULD NOT SEND RELAY PACKETS
            RELAY_117, RELAY_118_117_RETURN, RELAY_POW, RELAY_POW_RECEIVE, RELAY_VERSION_INFO, RELAY_BECOME_SERVER, FORWARD_CLIENT_ADD, FORWARD_CLIENT_REMOVE, PACKET_FORWARD_CLIENT_FROM, PACKET_FORWARD_CLIENT_TO, PACKET_FORWARD_CLIENT_TO_REPEATED,
                // 防止假冒
            TICK, SYNC, SERVER_INFO, HEART_BEAT, START_GAME, RETURN_TO_BATTLEROOM, CHAT, KICK, PACKET_RECONNECT_TO,
                // 每个玩家不一样, 不需要处理/缓存
            PASSWD_ERROR, TEAM_LIST, PREREGISTER_INFO,
                // Refusal to Process
            EMPTYP_ACKAGE, NOT_RESOLVED -> {
                // Ignore
            }
            GAMECOMMAND_RECEIVE -> {
                con.receiveCommand(packet)
            }

            REGISTER_PLAYER -> con.relayRegisterConnection(packet)
            CHAT_RECEIVE -> con.receiveChat(packet)
            ACCEPT_START_GAME -> {
                con.room!!.isStartGame = true
                con.sendPackageToHOST(packet)
            }

            DISCONNECT -> {
                con.sendPackageToHOST(packet)
                con.disconnect()
            }

            else -> con.sendPackageToHOST(packet)
        }
    }

    @Throws(Exception::class)
    private fun relayCheck(packet: Packet): Boolean {
        con.lastReceivedTime()

        val permissionStatus = con.permissionStatus

        if (permissionStatus.ordinal >= PlayerPermission.ordinal) {
            return false
        }

        when (permissionStatus) {
            // Initial Connection
            InitialConnection -> {
                if (packet.type == PREREGISTER_INFO_RECEIVE) {
                    con.permissionStatus = GetPlayerInfo
                    con.setCachePacket(packet)
                    val registerServer = GameOutputStream()
                    registerServer.writeString(Data.SERVER_ID_RELAY_GET)
                    registerServer.writeInt(1)
                    registerServer.writeInt(0)
                    registerServer.writeInt(0)
                    registerServer.writeString("com.corrodinggames.rts.server")
                    registerServer.writeString(Data.SERVER_RELAY_UUID)
                    registerServer.writeInt("Dr @ 2022".hashCode())
                    con.sendPacket(registerServer.createPacket(PREREGISTER_INFO))
                } else {
                    con.exCommand(packet)
                }
                return true
            }

            GetPlayerInfo -> {
                if (packet.type == REGISTER_PLAYER) {
                    con.relayRegisterConnection(packet)
                    // Wait Certified
                    con.permissionStatus = WaitCertified
                    con.sendRelayServerInfo()
                    con.sendVerifyClientValidity()
                }
                return true
            }

            WaitCertified -> {
                if (packet.type == RELAY_POW_RECEIVE) {
                    if (con.receiveVerifyClientValidity(packet)) {
                        // Certified End
                        con.permissionStatus = CertifiedEnd
                        con.relayDirectInspection()
                    } else {
                        con.sendVerifyClientValidity()
                    }
                } else {
                    con.disconnect()
                }
                return true
            }

            CertifiedEnd -> {
                if (packet.type == RELAY_118_117_RETURN) {
                    con.sendRelayServerTypeReply(packet)
                }
                return true
            }
            // 你肯定没验证
            else -> {
                con.disconnect()
                return true
            }
        }
    }

    override val version: String
        get() = "RELAY 1.1.0"
}
