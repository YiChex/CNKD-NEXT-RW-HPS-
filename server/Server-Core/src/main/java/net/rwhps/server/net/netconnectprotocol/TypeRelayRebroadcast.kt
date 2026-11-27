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
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType.*
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelayRebroadcast
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.game.command.CommandHandler

/**
 * Parse the [net.rwhps.server.net.core.IRwHps.NetType.RelayMulticastProtocol] protocol
 * @property con                GameVersionRelay
 * @property conClass           Initialize
 * @property abstractNetConnect AbstractNetConnect
 * @property version            Parser version
 * @author Dr (dr@der.kim)
 */
@PrivateMark
class TypeRelayRebroadcast: TypeRelay {
    constructor(con: GameVersionRelay): super(con)

    constructor(con: Class<out GameVersionRelayRebroadcast>): super(con)

    override fun getTypeConnect(connectionAgreement: ConnectionAgreement): TypeConnect {
        return TypeRelayRebroadcast(
                ReflectionUtils.accessibleConstructor(conClass!!, ConnectionAgreement::class.java).newInstance(connectionAgreement)
        )
    }

    @Throws(Exception::class)
    override fun hostProcessing(packet: Packet) {
        when (packet.type) {
            // 每个玩家不一样, 不需要处理/缓存
            TEAM_LIST,
                // Nobody dealt with it
            PACKET_DOWNLOAD_PENDING,
                // Refusal to Process
            EMPTYP_ACKAGE, NOT_RESOLVED -> {
                // Ignore
            }
            PACKET_FORWARD_CLIENT_TO -> {
                con.addRelaySend(packet)
                return
            }
            PACKET_FORWARD_CLIENT_TO_REPEATED -> {
                con.multicastAnalysis(packet)
                return
            }
            HEART_BEAT -> {
                con.setlastSentPacket(packet)
                con.getPingData(packet)
            }
            SERVER_INFO, RETURN_TO_BATTLEROOM, START_GAME -> {
                con.setlastSentPacket(packet)
                con.sendPacketExtractInformation(packet, con)
            }
            CHAT -> {
                con.setlastSentPacket(packet)
                GameInputStream(packet).use {
                    val message = it.readString()
                    it.skip(1)
                    if (it.readIsString() == con.name) {
                        if (message.startsWith(".")) {
                            val response = Data.RELAY_COMMAND.handleMessage(message, con)
                            if (response == null || response.type == CommandHandler.ResponseType.noCommand) {
                            } else if (response.type != CommandHandler.ResponseType.valid) {
                                val text: String = when (response.type) {
                                    CommandHandler.ResponseType.manyArguments -> "Too many arguments. Usage: " + response.command.text + " " + response.command.paramText
                                    CommandHandler.ResponseType.fewArguments -> "Too few arguments. Usage: " + response.command.text + " " + response.command.paramText
                                    else -> return@use
                                }
                                con.sendPacket(con.rwHps.abstractNetPacket.getSystemMessagePacket(text))
                            }
                            return
                        }
                    }
                }
            }

            DISCONNECT -> con.disconnect()
            else -> {
                con.setlastSentPacket(packet)
            }
        }
    }

    override val version: String
        get() = "${Data.SERVER_CORE_VERSION}: 2.2.0"
}
