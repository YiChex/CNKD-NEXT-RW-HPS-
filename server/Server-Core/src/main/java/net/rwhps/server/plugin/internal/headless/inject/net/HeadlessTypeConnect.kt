/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.net

import net.rwhps.server.data.global.Data
import net.rwhps.server.func.Control
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.plugin.internal.headless.inject.command.ClientCommands
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine.netEngine
import net.rwhps.server.plugin.internal.headless.inject.lib.PlayerConnectX
import net.rwhps.server.plugin.internal.headless.support.StartModeLatencyHeartbeatSupport
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameCommandCompatibilitySupport
import net.rwhps.server.util.log.Log

/**
 * Parse the [net.rwhps.server.net.core.IRwHps.NetType.ServerProtocol] protocol
 * @property con                GameVersionRelay
 * @property conClass           Initialize
 * @property abstractNetConnect AbstractNetConnect
 * @property version            Parser version
 * @author Dr (dr@der.kim)
 */
open class HeadlessTypeConnect: TypeConnect {
    val con: GameVersionServer
    var conClass: Class<out GameVersionServer>? = null

    override val abstractNetConnect: AbstractNetConnect
        get() = con

    constructor(con: GameVersionServer) {
        this.con = con
    }

    constructor(con: Class<out GameVersionServer>) {
        // will not be used ; just override the initial value to avoid refusing to compile
        this.con = GameVersionServer(PlayerConnectX(netEngine, ConnectionAgreement()))

        // use for instantiation
        conClass = con
    }

    override fun getTypeConnect(connectionAgreement: ConnectionAgreement): TypeConnect {
        val netEngine = con.playerConnectX.netEngine

        val playerConnect = PlayerConnectX(netEngine, connectionAgreement)
        playerConnect.h = false // UDP
        playerConnect.i = false // UDP
        playerConnect.d()

        netEngine.aM.add(playerConnect)

        return HeadlessTypeConnect(GameVersionServer(playerConnect))
    }

    @Throws(Exception::class)
    override fun processConnect(packet: Packet) {
        try {
            var packetToProcess = packet
            when (packet.type) {
                PacketType.CHAT_RECEIVE -> {
                    con.receiveChat(packet)
                }
                PacketType.GAMECOMMAND_RECEIVE -> {
                    val lowLatencyConfig = SingleRoomLowLatencySupport.runtimeConfig()
                    val metricsEnabled = lowLatencyConfig.metricsEnabled
                    if (metricsEnabled) {
                        SingleRoomLowLatencySupport.onGameCommandReceived(packet, lowLatencyConfig)
                    }
                    con.receiveCommand(packet, lowLatencyConfig)
                    if (metricsEnabled) {
                        SingleRoomLowLatencySupport.onGameCommandReceiveCommandFinished(
                            packet,
                            packet.status == Control.EventNext.CONTINUE,
                            lowLatencyConfig
                        )
                    }
                    if (packet.status == Control.EventNext.CONTINUE && shouldDropGameCommandDuringBattleRoomCommit()) {
                        SingleRoomLowLatencySupport.recordGameCommandFastPathRejected(
                            "battle_room_transition_committing_recheck",
                            lowLatencyConfig
                        )
                        packet.status = Control.EventNext.STOPPED
                        return
                    }
                    if (packet.status == Control.EventNext.CONTINUE &&
                        SingleRoomLowLatencySupport.shouldForwardGameCommandDirectToEngine(
                            roomStarted = con.playerConnectX.room.isStartGame,
                            resetInProgress = con.playerConnectX.room.isGameOverResetInProgress(),
                            runtimeConfig = lowLatencyConfig
                        )
                    ) {
                        packetToProcess = StartModeGameCommandCompatibilitySupport.rewriteSharedControlCheck(packet)
                        SingleRoomLowLatencySupport.transferPendingGameCommandMetric(packet, packetToProcess, lowLatencyConfig)
                        val directForwarded = try {
                            if (shouldDropGameCommandDuringBattleRoomCommit()) {
                                SingleRoomLowLatencySupport.recordGameCommandFastPathRejected(
                                    "battle_room_transition_committing_direct_recheck",
                                    lowLatencyConfig
                                )
                                packetToProcess.status = Control.EventNext.STOPPED
                                return
                            }
                            con.playerConnectX.netEnginePackaging.addPacket(packetToProcess)
                            true
                        } catch (error: ThreadDeath) {
                            throw error
                        } catch (error: VirtualMachineError) {
                            throw error
                        } catch (error: Throwable) {
                            SingleRoomLowLatencySupport.recordDirectEngineForwardFailed(error, lowLatencyConfig)
                            Log.warn(
                                "[SingleRoomLowLatency] Direct engine forward failed; falling back to original command path.",
                                error
                            )
                            false
                        }
                        if (directForwarded) {
                            return
                        }
                    }
                    if (packet.status == Control.EventNext.CONTINUE) {
                        packetToProcess = StartModeGameCommandCompatibilitySupport.rewriteSharedControlCheck(packet)
                        SingleRoomLowLatencySupport.transferPendingGameCommandMetric(packet, packetToProcess, lowLatencyConfig)
                    }
                }
                PacketType.START_GAME -> {
                    packet.status = Control.EventNext.STOPPED
                    val player = con.playerConnectX.player ?: return
                    /*
                     * START-FREEZE: dispatch off the Netty worker. requestStart() blocks the caller
                     * inside suspendMainThreadOperations{ multiplayerStart() } (up to 30s, +30s more
                     * if already begun), and production runs nettyWorkerThreadsOverride=1, so doing
                     * this inline freezes EVERY player's packet handling until the map finishes
                     * loading -- the reported "latency stops, chat unavailable, can't join".
                     */
                    ClientCommands.requestStartOffNetworkThread(player, "client_start_packet")
                }
                PacketType.RETURN_TO_BATTLEROOM -> {
                    packet.status = Control.EventNext.STOPPED
                    val player = con.playerConnectX.player ?: return
                    ClientCommands.requestBattleRoom(player, "client_return_to_battleroom_packet")
                }
                PacketType.RELAY_118_117_RETURN -> {
                    packet.status = Control.EventNext.STOPPED
                    con.sendRelayServerTypeReply(packet)
                }
                PacketType.DISCONNECT -> {
                    con.disconnect()
                    packet.status = Control.EventNext.STOPPED
                }
                PacketType.HEART_BEAT_RESPONSE -> {
                    con.lastReceivedTime()
                    reportHeartbeatLatency(packet)
                }
                else -> {}
            }
            if (packetToProcess.status == Control.EventNext.CONTINUE) {
                if (packetToProcess.type == PacketType.GAMECOMMAND_RECEIVE &&
                    shouldDropGameCommandDuringBattleRoomCommit()
                ) {
                    packetToProcess.status = Control.EventNext.STOPPED
                    return
                }
                con.receivePacket(packetToProcess)
            }
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    private fun shouldDropGameCommandDuringBattleRoomCommit(): Boolean {
        return con.playerConnectX.room.isBattleRoomTransitionCommitBlocking()
    }

    override val version: String
        get() = "${Data.SERVER_CORE_VERSION}: 1.0.0"

    private fun reportHeartbeatLatency(packet: Packet) {
        runCatching {
            val player = con.playerConnectX.player ?: return
            val now = System.currentTimeMillis()
            val sentAtMillis = heartbeatSentAtMillis(packet, now) ?: player.timeTemp
            if (sentAtMillis <= 0L) {
                return
            }
            val pingMs = (now - sentAtMillis)
                .coerceIn(0L, 60_000L)
                .toInt()
            StartModeLatencyHeartbeatSupport.onHeartbeatResponse(player, pingMs)
        }.onFailure {
            Log.debug("[StartModeLatency] Failed to report heartbeat latency: ${it.message.orEmpty()}")
        }
    }

    private fun heartbeatSentAtMillis(packet: Packet, now: Long): Long? {
        return runCatching {
            GameInputStream(packet).use { stream ->
                stream.readLong()
            }
        }.getOrNull()
            ?.takeIf { it > 0L && it <= now + 5_000L }
    }
}
