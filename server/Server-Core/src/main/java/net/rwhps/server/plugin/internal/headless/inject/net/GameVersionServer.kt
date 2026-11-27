/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.net

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.game.units.am
import com.corrodinggames.rts.gameFramework.w
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.bean.BeanServerConfigFile
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.totalizer.TimeAndNumber
import net.rwhps.server.func.Control
import net.rwhps.server.game.enums.GameCommandActions
import net.rwhps.server.game.enums.GamePingActions
import net.rwhps.server.game.event.game.PlayerChatEvent
import net.rwhps.server.game.event.game.PlayerLeaveEvent
import net.rwhps.server.game.event.game.PlayerOperationFactoryBuildUnitEvent
import net.rwhps.server.game.event.game.PlayerOperationUnitEvent
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.packet.GameCommandOnePacket
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.ServerInfoPacket
import net.rwhps.server.io.packet.type.AbstractPacketType
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.core.DataPermissionStatus.ServerStatus
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.core.server.AbstractNetConnectData
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.net.netconnectprotocol.internal.relay.relayServerTypeInternal
import net.rwhps.server.net.netconnectprotocol.internal.relay.relayServerTypeReplyInternalPacket
import net.rwhps.server.net.netconnectprotocol.internal.server.playerExitInternalPacket
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.inject.lib.PlayerConnectX
import net.rwhps.server.plugin.internal.headless.support.StartModeSurrenderSupport
import net.rwhps.server.plugin.internal.headless.support.OriginalSurrenderPacketProbe
import net.rwhps.server.plugin.internal.headless.support.MapPingMessageWhitelist
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModePlayerExperienceSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAdvertisingRedirectSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeConsoleOutputSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionAction
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSpectatorSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeTeamChatSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeVisualMapSelectionSupport
import net.rwhps.server.util.annotations.MainProtocolImplementation
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.game.command.CommandResponseFormatter
import net.rwhps.server.util.game.SystemMessageTextMode
import net.rwhps.server.util.game.SystemMessageTextSupport
import net.rwhps.server.util.inline.coverConnect
import net.rwhps.server.util.log.ColorCodes
import net.rwhps.server.util.log.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A2-P3/A10: upper bound for player-operation events awaited on the network thread.
 *
 * These run per game command and the caller is a Netty worker, of which production runs exactly
 * one (nettyWorkerThreadsOverride=1). Generous enough for any legitimate listener, short enough
 * that a hung one cannot stall every player's packet handling.
 */
private const val PLAYER_OPERATION_EVENT_TIMEOUT_MILLIS = 5_000L

/**
 * FIX-LOG-RATE-LIMIT: 玩家操作事件超时告警的最小间隔。
 *
 * 这两条 WARN 位于 `handleGameCommand` 的**每指令**路径上，而生产环境
 * `nettyWorkerThreadsOverride=1`（单 Netty worker 处理所有玩家指令）。
 * 某个监听器一旦持续变慢，**每个玩家的每条单位指令**都会打一条 WARN，
 * 无上限且客户端可控 ⇒ 可淹没控制台、拖慢单 worker。
 *
 * 实测（2026-10-21，R1 全量检索）：本次无客户端连接，未实际触发，
 * 属潜在放大路径。加 60 秒窗口限频后，慢监听器仍会被记录（首条必打），
 * 但不会刷屏。
 */
private const val PLAYER_OPERATION_EVENT_LOG_INTERVAL_MILLIS = 60_000L

/** 上一次打印 PlayerOperationUnitEvent 超时告警的时间戳（0 = 从未）。 */
private val lastPlayerOperationUnitTimeoutLogAt = java.util.concurrent.atomic.AtomicLong(0L)

/** 上一次打印 PlayerOperationFactoryBuildUnitEvent 超时告警的时间戳（0 = 从未）。 */
private val lastPlayerOperationBuildUnitTimeoutLogAt = java.util.concurrent.atomic.AtomicLong(0L)

/**
 * 限频判定：距上次打印是否已超过 [PLAYER_OPERATION_EVENT_LOG_INTERVAL_MILLIS]。
 * 首条总是打印（时间戳为 0）。CAS 保证多 worker 下也只有一条通过。
 */
private fun shouldLogPlayerOperationTimeout(lastLogAt: java.util.concurrent.atomic.AtomicLong): Boolean {
    val now = System.currentTimeMillis()
    val previous = lastLogAt.get()
    if (previous != 0L && now - previous < PLAYER_OPERATION_EVENT_LOG_INTERVAL_MILLIS) {
        return false
    }
    return lastLogAt.compareAndSet(previous, now)
}

/**
 * Common server implementation
 *
 * @property supportedversionBeta   Server is Beta
 * @property supportedversionGame   Server Support Version String
 * @property supportedVersionInt    Server Support Version Int
 * @property player                 Player
 * @property permissionStatus       ServerStatus
 * @property version                Protocol version
 * @constructor
 *
 * @date 2020/9/5 17:02:33
 * @author Dr (dr@der.kim)
 */
@MainProtocolImplementation
open class GameVersionServer(val playerConnectX: PlayerConnectX): AbstractNetConnect(playerConnectX.connectionAgreement), AbstractNetConnectData, AbstractNetConnectServer {
    init {
        playerConnectX.serverConnect = this
    }

    private var relaySelect: ((String) -> Unit)? = null

    override val supportedversionBeta = false
    override val supportedversionGame = "1.15"
    override val supportedVersionInt = 176

    override val room: ServerRoom = playerConnectX.room

    override val name: String get() = player.name
    override val registerPlayerId: String? get() = player.connectHexID

    override val betaGameVersion: Boolean get() = supportedversionBeta
    override val clientVersion: Int get() = supportedVersionInt

    /** 玩家  */
    override lateinit var player: PlayerHess
    override var permissionStatus: ServerStatus = ServerStatus.InitialConnection
        internal set

    override val version: String
        get() = "1.15 RW-HPS"

    val update = TimeAndNumber(2, 1)

    override fun sendSystemMessage(msg: String, mode: SystemMessageTextMode) {
        try {
            val normalized = SystemMessageTextSupport.normalize(msg, mode)
            if (normalized.isNotBlank()) {
                sendPacket(rwHps.abstractNetPacket.getChatMessagePacket(normalized, systemMessageSenderName(), 5))
            }
        } catch (e: IOException) {
            Log.error("[Player] Send System Chat Error", e)
        }
    }

    private fun systemMessageSenderName(): String {
        return BeanServerConfigFile.normalizeChatSenderName(runCatching { Data.config.chatSenderName }.getOrNull())
    }

    override fun sendChatMessage(msg: String, sendBy: String, team: Int) {
        try {
            // 排位剥离：原 chatSenderColorOverride(sendBy, team) 在非托管对局（本构建全部情况）下返回 team。
            val displayColor = team
            sendPacket(rwHps.abstractNetPacket.getChatMessagePacket(msg, sendBy, displayColor))
        } catch (e: IOException) {
            Log.error("[Player] Send Player Chat Error", e)
        }
    }

    @Throws(IOException::class)
    override fun sendServerInfo(utilData: Boolean) {
    }

    override fun sendTeamData(gzip: CompressOutputStream) {
    }

    override fun sendSurrender() {
        try {
            val out = GameOutputStream()

            // Player Site
            out.writeByte(player.index)

            // Is Unit Action
            out.writeBoolean(false)

            // unknown booleans
            out.writeBoolean(false)
            // Is Cancel the operation
            out.writeBoolean(false)

            // unknown
            out.writeInt(-1)
            out.writeInt(-1)
            out.writeBoolean(false)
            out.writeBoolean(false)

            // Unit count
            out.writeInt(0)

            out.writeBoolean(false)
            out.writeBoolean(false)

            out.writeLong(-1)
            // build Unit
            out.writeString("-1")

            out.writeBoolean(false)
            // multi person control check code
            out.writeShort(0)

            // System action
            out.writeBoolean(true)
            out.writeByte(0)
            out.writeFloat(0)
            out.writeFloat(0)
            // action type
            out.writeInt(100)

            // Unit Count
            out.writeInt(0)
            // unknown
            out.writeBoolean(false)

            GameEngine.data.gameData.commandPacketList.add(out.getByteArray())
            //Call.sendSystemMessage(Data.i18NBundle.getinput("player.surrender", player.name))
        } catch (e: Exception) {
            Log.error("[Surrender] Failed to send surrender packet for player ${player.name}", e)
            throw IllegalStateException("Failed to enqueue surrender action for player ${player.index}", e)
        }
    }

    override fun sendKick(reason: String) {
        val o = GameOutputStream()
        o.writeString(reason)
        sendPacket(o.createPacket(PacketType.KICK))
        val dis = GameOutputStream()
        dis.writeString("kicked")
        sendPacket(dis.createPacket(PacketType.DISCONNECT))
    }

    override fun sendPing() {
        if (!this::player.isInitialized) {
            return
        }
        try {
            sendPacket(rwHps.abstractNetPacket.getPingPacket(player))
        } catch (e: IOException) {
            Log.debug("[StartModeLatency] Send ping failed", e)
        }
    }

    @Throws(IOException::class)
    override fun sendStartGame() {
    }

    /**
     * Replace the engine's custom-map path with the protocol form that embeds
     * the TMX bytes. Built-in maps remain byte-for-byte untouched.
     */
    fun rewriteStartGamePacket(packetBytes: ByteArray): ByteArray? {
        if (room.maps.mapType == net.rwhps.server.game.GameMaps.MapType.DefaultMap) {
            return packetBytes
        }

        return runCatching {
            rwHps.abstractNetPacket.getCustomStartGamePacket(room.maps).bytes
        }.onFailure { error ->
            Log.error("[MapSync] Failed to build custom START_GAME for ${room.maps.mapName}; dropping unsafe engine packet", error)
        }.getOrNull()
    }

    @Throws(IOException::class)
    override fun receiveChat(packet: Packet) {
        GameInputStream(packet).use { stream ->
            val message: String = stream.readString()
            var response: CommandHandler.CommandResponse? = null

            // Afk Stop
            if (
                StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player) &&
                Threads.containsTimeTask(CallTimeTask.PlayerAfkTask)
            ) {
                Threads.closeTimeTask(CallTimeTask.PlayerAfkTask)
            }


            if (StartModeTeamChatSupport.tryHandleTeamChat(message, player, room)) {
                packet.status = Control.EventNext.STOPPED
                return
            }


            if (StartModeSurrenderSupport.tryHandleSurrenderChat(message, player)) {
                packet.status = Control.EventNext.STOPPED
                Log.clog("[&by {0} &fr]: &y{1}", player.name, ColorCodes.formatColors(message, true))
                return
            }

            Data.vote?.toVote(player, message)

            // Check if message is a command prefix candidate
            val isPrimaryCommand = message.startsWith("/")
            val isAltPrefix = message.startsWith(".") || message.startsWith("-") || message.startsWith("_")
            val isCommandMessage = isPrimaryCommand || isAltPrefix

            if (StartModeSpectatorSupport.teamLockEnabled && !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                if (StartModeSpectatorSupport.isTeamLockPositionChangeCommand(message)) {
                    player.sendSystemMessage("队伍位置已锁定 仅房主或管理员可移动")
                    packet.status = Control.EventNext.STOPPED
                    Log.clog("[&by {0} &fr]: &y{1}", player.name, ColorCodes.formatColors(message, true))
                    return
                }
            }

            // Placement commands share parsing and permission checks, including S-team requests.
            if (isCommandMessage) {
                val normalizedCommand = StartModeSpectatorSupport.normalizeLobbyCommand(message).orEmpty()
                response = if (normalizedCommand.isBlank() || normalizedCommand == "/") {
                    CommandHandler.CommandResponse(CommandHandler.ResponseType.noCommand, null, null)
                } else {
                    GameEngine.data.room.clientHandler.handleMessage(normalizedCommand, player)
                }
            }

            if (response == null || response.type == CommandHandler.ResponseType.noCommand) {
                // Not a command - process as chat
                val mapPingMessage = MapPingMessageWhitelist.isWhitelisted(message)
                if (message.length > Data.configServer.maxMessageLen && !mapPingMessage) {
                    sendSystemMessage(Data.i18NBundle.getinput("message.maxLen"))
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                if (room.muteAll) {
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                val messageOut = Data.core.admin.filterMessage(player, message) ?: run {
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                GameEngine.data.eventManage.fire(PlayerChatEvent(GameEngine.data, player, messageOut))
            } else if (response.type == CommandHandler.ResponseType.unknownCommand && isAltPrefix) {
                // Fix: Unknown alt-prefix commands (e.g. -i desync info) should pass through as chat,
                // not produce an error. Only primary /-prefix unknown commands show an error.
                val mapPingMessage = MapPingMessageWhitelist.isWhitelisted(message)
                if (message.length > Data.configServer.maxMessageLen && !mapPingMessage) {
                    sendSystemMessage(Data.i18NBundle.getinput("message.maxLen"))
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                if (room.muteAll) {
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                val messageOut = Data.core.admin.filterMessage(player, message) ?: run {
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                GameEngine.data.eventManage.fire(PlayerChatEvent(GameEngine.data, player, messageOut))
            } else if (response.type != CommandHandler.ResponseType.valid) {
                if (CommandResponseFormatter.appendFormattedError(response) { player.sendSystemMessage(it) }) {
                    packet.status = Control.EventNext.STOPPED
                }
            } else {
                packet.status = Control.EventNext.STOPPED
            }

            logPlayerInput(message, shouldAlwaysPrint = shouldAlwaysPrintPlayerInput(response, isCommandMessage, isAltPrefix))
        }
    }

    private fun shouldAlwaysPrintPlayerInput(
        response: CommandHandler.CommandResponse?,
        isCommandMessage: Boolean,
        isAltPrefix: Boolean
    ): Boolean {
        if (!isCommandMessage) {
            return false
        }
        return !(response?.type == CommandHandler.ResponseType.unknownCommand && isAltPrefix)
    }

    private fun logPlayerInput(message: String, shouldAlwaysPrint: Boolean = false) {
        if (!shouldAlwaysPrint && !StartModeConsoleOutputSupport.playerChatEnabled()) {
            return
        }
        Log.clog("[&by {0} &fr]: &y{1}", player.name, ColorCodes.formatColors(message, true))
    }

    @Throws(IOException::class)
    override fun receiveCommand(packet: Packet) {
        receiveCommand(packet, SingleRoomLowLatencySupport.runtimeConfig())
    }

    @Throws(IOException::class)
    internal fun receiveCommand(packet: Packet, lowLatencyConfig: SingleRoomLowLatencySupport.RuntimeConfig) {
        try {
            if (!this::player.isInitialized) {
                if (lowLatencyConfig.enabled && lowLatencyConfig.fastGameCommand) {
                    SingleRoomLowLatencySupport.recordGameCommandFastPathRejected(
                        "player_not_initialized",
                        lowLatencyConfig
                    )
                }
                packet.status = Control.EventNext.STOPPED
                return
            }

            val lifecycleRecoveryDeferred = StartModeGameOverReturnSupport.recoverStaleLifecycleIfNeeded(
                room,
                "game_command:${player.name}"
            )
            val lifecycleBlockReason = when {
                room.isBattleRoomTransitionCommitBlocking() -> "battle_room_transition_committing"
                lifecycleRecoveryDeferred -> "lifecycle_recovery_deferred"
                room.isGameOverResetInProgress() -> "room_resetting"
                StartModeGameOverReturnSupport.isSettling() -> "game_settling"
                else -> null
            }
            if (lifecycleBlockReason != null) {
                SingleRoomLowLatencySupport.recordGameCommandFastPathRejected(lifecycleBlockReason, lowLatencyConfig)
                packet.status = Control.EventNext.STOPPED
                return
            }

            if (player.con !== this) {
                SingleRoomLowLatencySupport.recordGameCommandFastPathRejected("stale_player_connection", lowLatencyConfig)
                packet.status = Control.EventNext.STOPPED
                return
            }

            val originalSurrenderCommand = if (
                !Data.configStartMode.enableIndividualSurrender &&
                hasOriginalSurrenderPacketSuffix(packet)
            ) {
                try {
                    GameCommandOnePacket(GameInputStream(packet).getDecodeBytes())
                } catch (error: Exception) {
                    packet.status = Control.EventNext.STOPPED
                    player.sendSystemMessage("Surrender request could not be parsed; reconnect and retry.")
                    Log.warn("[Surrender] Rejected malformed surrender action candidate from ${player.name}", error)
                    return
                }
            } else {
                null
            }
            if (originalSurrenderCommand != null && StartModeSurrenderSupport.isOriginalSurrenderAction(
                    isSystemAction = originalSurrenderCommand.N_Boolean_4,
                    systemAction = originalSurrenderCommand.systemAction
                )
            ) {
                if (originalSurrenderCommand.sendBy?.index != player.index) {
                    player.sendSystemMessage("Surrender request identity check failed; reconnect and retry.")
                    Log.warn(
                        "[Surrender] Rejected slot mismatch: connection=${player.index}, " +
                                "command=${originalSurrenderCommand.sendBy?.index ?: -1}, player=${player.name}"
                    )
                    packet.status = Control.EventNext.STOPPED
                    return
                }
                if (StartModeSurrenderSupport.tryHandleOriginalSurrenderAction(
                        isSystemAction = true,
                        systemAction = originalSurrenderCommand.systemAction,
                        player = player
                    )
                ) {
                    recordGameCommandClassification(originalSurrenderCommand, lowLatencyConfig)
                    packet.status = Control.EventNext.STOPPED
                    return
                }
            }

            val pendingMapAction = player.getData<Any>("Ping") != null
            if (!pendingMapAction && SingleRoomLowLatencySupport.shouldUseGameCommandFastPath(
                    playerReady = true,
                    runtimeConfig = lowLatencyConfig
                )
            ) {
                gameCommandFastPathGuard()?.let { guard ->
                    if (guard.hardReject) {
                        SingleRoomLowLatencySupport.recordGameCommandFastPathRejected(guard.reason, lowLatencyConfig)
                        packet.status = Control.EventNext.STOPPED
                        return
                    }
                    SingleRoomLowLatencySupport.recordGameCommandFastPathFallback(guard.reason, lowLatencyConfig)
                    // Start/game-state mismatches can happen during the first frames of a real start.
                    // Fall back to the normal path instead of swallowing player commands.
                } ?: run {
                    packet.status = Control.EventNext.CONTINUE
                    if (lowLatencyConfig.metricsEnabled) {
                        if (originalSurrenderCommand != null) {
                            recordGameCommandClassification(originalSurrenderCommand, lowLatencyConfig)
                        } else {
                            recordGameCommandClassification(packet, lowLatencyConfig)
                        }
                        SingleRoomLowLatencySupport.recordGameCommandFastPath(lowLatencyConfig)
                    }
                    return
                }
            }
            if (lowLatencyConfig.metricsEnabled) {
                SingleRoomLowLatencySupport.recordGameCommandNormalPath(lowLatencyConfig)
            }


            (originalSurrenderCommand ?: GameCommandOnePacket(GameInputStream(packet).getDecodeBytes())).let { command ->
                recordGameCommandClassification(command, lowLatencyConfig)
                val lowLatencyRawGameCommand = lowLatencyConfig.enabled && lowLatencyConfig.fastGameCommand
                val skipPlayerOperationEvents = lowLatencyRawGameCommand && lowLatencyConfig.skipPlayerOperationEvents
                val skipPingActionIntercept = !pendingMapAction && lowLatencyRawGameCommand && lowLatencyConfig.skipPingActionIntercept
                val disableTurnStoneIntoGold = lowLatencyRawGameCommand && lowLatencyConfig.disableTurnStoneIntoGoldOnFastPath

                // Original client system actions belong on the raw GAMECOMMAND path.
                if (isOriginalSystemActionCommand(command)) {
                    packet.status = Control.EventNext.CONTINUE
                    return
                }

                command.operationUnit?.let { opUnit ->
                    if (!disableTurnStoneIntoGold &&
                        Data.configServer.turnStoneIntoGold &&
                        opUnit.gameCommandActions == GameCommandActions.BUILD
                    ) {
                        val unitName = opUnit.unitName
                        if (unitName != null) {
                            gameSummon(unitName, opUnit.x, opUnit.y)
                        }
                        packet.status = Control.EventNext.STOPPED
                        return
                    } else if (!skipPlayerOperationEvents) {
                        PlayerOperationUnitEvent(GameEngine.data, player, opUnit.gameCommandActions, opUnit.unitName, opUnit.x, opUnit.y).let { event ->
                            /*
                             * A2-P3/A10 (independent verification): this is a PER-COMMAND path on
                             * the Netty worker, and production runs nettyWorkerThreadsOverride=1.
                             * An unbounded await() here means one slow PlayerOperationUnitEvent
                             * listener stalls every player's packet handling on every unit command.
                             *
                             * Bounded now. On timeout we proceed: `resultStatus` defaults to true,
                             * so "no listener objected in time" means allow the command -- the same
                             * default the event itself ships with, and strictly better than freezing
                             * the network thread.
                             */
                            val completed = GameEngine.data.eventManage.fire(event)
                                .await(PLAYER_OPERATION_EVENT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            if (!completed) {
                                // FIX-LOG-RATE-LIMIT: 每指令路径，60 秒窗口限频（首条必打）。
                                if (shouldLogPlayerOperationTimeout(lastPlayerOperationUnitTimeoutLogAt)) {
                                    Log.warn(
                                        "[Headless] PlayerOperationUnitEvent did not complete within " +
                                            "${PLAYER_OPERATION_EVENT_TIMEOUT_MILLIS}ms; allowing the " +
                                            "command so one slow listener cannot stall the network thread"
                                    )
                                }
                            }
                            if (!event.resultStatus) {
                                packet.status = Control.EventNext.STOPPED
                                return
                            }
                        }
                    }
                }
                if (command.mapPoint != null && command.actionIdData.actionId.startsWith("c_6_")) {
                    val action = GamePingActions.from(command.actionIdData.actionId.removePrefix("c_6_"))
                    if (!skipPingActionIntercept && action != null) {
                        val pending = synchronized(player) {
                            player.getData<(AbstractNetConnectServer, GamePingActions, Float, Float) -> Unit>("Ping")
                                ?.also { player.removeData("Ping") }
                        }
                        pending?.let { lambda ->
                            val mapPoint = command.mapPoint
                            if (mapPoint != null && mapPoint.size >= 2 && mapPoint.all { it.isFinite() }) {
                                lambda(this, action, mapPoint[0], mapPoint[1])
                            }
                            packet.status = Control.EventNext.STOPPED
                            return
                        }
                    }
                    packet.status = Control.EventNext.CONTINUE
                    return
                }

                if (!skipPlayerOperationEvents && !command.actionIdData.actionId.startsWith("_")) {
                    PlayerOperationFactoryBuildUnitEvent(GameEngine.data, player, command.actionIdData).let { event->
                        // Same reasoning as PlayerOperationUnitEvent above: this is a per-command
                        // path on the single Netty worker. Bounded so a slow listener cannot stall
                        // every player; resultStatus defaults to true, so a timeout allows.
                        val completed = GameEngine.data.eventManage.fire(event)
                            .await(PLAYER_OPERATION_EVENT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        if (!completed) {
                            // FIX-LOG-RATE-LIMIT: 每指令路径，60 秒窗口限频（首条必打）。
                            if (shouldLogPlayerOperationTimeout(lastPlayerOperationBuildUnitTimeoutLogAt)) {
                                Log.warn(
                                    "[Headless] PlayerOperationFactoryBuildUnitEvent did not complete " +
                                        "within ${PLAYER_OPERATION_EVENT_TIMEOUT_MILLIS}ms; allowing " +
                                        "the command so one slow listener cannot stall the network thread"
                                )
                            }
                        }
                        if (!event.resultStatus) {
                            packet.status = Control.EventNext.STOPPED
                            return
                        }
                    }

                }
            }
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    private fun recordGameCommandClassification(
        packet: Packet,
        lowLatencyConfig: SingleRoomLowLatencySupport.RuntimeConfig
    ) {
        if (!lowLatencyConfig.metricsEnabled) {
            return
        }

        runCatching {
            GameCommandOnePacket(GameInputStream(packet).getDecodeBytes())
        }.onSuccess { command ->
            recordGameCommandClassification(command, lowLatencyConfig)
        }.onFailure { error ->
            SingleRoomLowLatencySupport.recordGameCommandClassificationFailed(
                error.javaClass.simpleName.ifBlank { "parse_failed" },
                lowLatencyConfig
            )
        }
    }

    private fun recordGameCommandClassification(
        command: GameCommandOnePacket,
        lowLatencyConfig: SingleRoomLowLatencySupport.RuntimeConfig
    ) {
        SingleRoomLowLatencySupport.recordGameCommandClassification(
            command = command,
            classification = classifyGameCommand(command),
            runtimeConfig = lowLatencyConfig
        )
    }

    private fun classifyGameCommand(command: GameCommandOnePacket): SingleRoomLowLatencySupport.GameCommandClassification {
        if (command.N_Boolean_4) {
            return SingleRoomLowLatencySupport.GameCommandClassification.SYSTEM_ACTION
        }
        if (command.operationUnit != null) {
            return SingleRoomLowLatencySupport.GameCommandClassification.UNIT_OPERATION
        }
        val actionId = command.actionIdData.actionId
        if (command.mapPoint != null && actionId.startsWith("c_6_")) {
            return SingleRoomLowLatencySupport.GameCommandClassification.MAP_PING
        }
        if (actionId.startsWith("_")) {
            return SingleRoomLowLatencySupport.GameCommandClassification.INTERNAL_ACTION
        }
        if (actionId.isNotBlank()) {
            return SingleRoomLowLatencySupport.GameCommandClassification.FACTORY_ACTION
        }
        return SingleRoomLowLatencySupport.GameCommandClassification.UNKNOWN
    }

    private data class GameCommandFastPathGuard(
        val reason: String,
        val hardReject: Boolean
    )

    private fun gameCommandFastPathGuard(): GameCommandFastPathGuard? {
        if (room.isGameOverResetInProgress()) {
            return GameCommandFastPathGuard("room_resetting", hardReject = true)
        }
        if (StartModeGameOverReturnSupport.isSettling()) {
            return GameCommandFastPathGuard("game_settling", hardReject = true)
        }
        if (!room.isStartGame) {
            return GameCommandFastPathGuard("room_not_started", hardReject = false)
        }
        if (!StartModeGameOverReturnSupport.isPlaying()) {
            return GameCommandFastPathGuard("game_not_playing", hardReject = false)
        }
        if (!SingleRoomLowLatencySupport.isMapEngineReady()) {
            return GameCommandFastPathGuard("map_engine_not_ready", hardReject = false)
        }
        if (player.con !== this) {
            return GameCommandFastPathGuard("stale_player_connection", hardReject = true)
        }
        if (player.isAi) {
            return GameCommandFastPathGuard("player_is_ai", hardReject = true)
        }
        if (room.playerManage.isStaleHumanPlayer(player)) {
            return GameCommandFastPathGuard("stale_player", hardReject = true)
        }
        return null
    }

    private fun isOriginalSystemActionCommand(command: GameCommandOnePacket): Boolean {
        return command.N_Boolean_4
    }

    private fun hasOriginalSurrenderPacketSuffix(packet: Packet): Boolean {
        return OriginalSurrenderPacketProbe.matchesEnvelopeSuffix(packet.bytes)
    }

    @Throws(IOException::class)
    override fun receiveCheckPacket(packet: Packet) {
    }

    @Throws(IOException::class)
    override fun getGameSaveData(packet: Packet): ByteArray {
        return PacketType.nullPacket.bytes
    }

    //@Throws(IOException::class)
    override fun getPlayerInfo(packet: Packet): Boolean {
        return true
    }

    @Throws(IOException::class)
    override fun registerConnection(packet: Packet) {
    }

    override fun gameSummon(unit: String, x: Float, y: Float) {
        try {
//            val commandPacket = GameEngine.gameEngine.cf.b()
//
//            val out = GameOutputStream()
//            out.flushEncodeData(CompressOutputStream.getGzipOutputStream("c", false).apply {
//                writeBytes(rwHps.abstractNetPacket.gameSummonPacket(player.index, unit, x, y).bytes)
//            })
//
//            commandPacket.a(GameNetInputStream(playerConnectX.netEnginePackaging.transformHessPacket(out.createPacket(PacketType.TICK))))
//
//            commandPacket.c = GameEngine.data.gameHessData.tickNetHess + 10
//            GameEngine.gameEngine.cf.b.add(commandPacket)
            GameEngine.data.gameData.commandPacketList.add(rwHps.abstractNetPacket.gameSummonPacket(player.index, unit, x, y).bytes)
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    override fun sendRelayServerType(msg: String, run: ((String) -> Unit)?) {
        try {
            sendPacket(relayServerTypeInternal(msg))

            relaySelect = run

            connectReceiveData.inputPassword = true
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    override fun sendRelayServerTypeReply(packet: Packet) {
        try {
            connectReceiveData.inputPassword = false

            val id = relayServerTypeReplyInternalPacket(packet)
            relaySelect?.let { callback ->
                callback(id)
                relaySelect = null
            }
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    override fun sendErrorPasswd() {
    }

    override fun disconnect() {
        if (super.isDis) {
            return
        }
        super.isDis = true
        if (this::player.isInitialized) {
            val playerManage = playerConnectX.room.playerManage
            var wasStartGame = false
            val shouldHandleDisconnect = synchronized(playerManage) {
                synchronized(player) {
                    if (player.con !== this) {
                        false
                    } else {
                        wasStartGame = playerConnectX.room.isStartGame
                        StartModePlayerExperienceSupport.markDisconnected(player, wasStartGame)
                        player.clear()
                        // D1: release the advertising-redirect attempt budget of this connection now
                        // that it is definitively unbound (`player.con === this` was verified above,
                        // so this can never clear a newer connection's budget).
                        StartModeAdvertisingRedirectSupport.onPlayerConnectionClosed(player, connectionId)
                        playerManage.playerGroup.remove(player)
                        true
                    }
                }
            }
            if (!shouldHandleDisconnect) {
                super.close(null)
                return
            }

            if (Data.neverEnd && player.never) {
                synchronized(am.bE) {
                    GameEngine.data.gameFunction.suspendMainThreadOperations {
                        val enginePlayer = n.k(player.index) ?: return@suspendMainThreadOperations
                        val it: Iterator<*> = am.bE.iterator()
                        while (it.hasNext()) {
                            val amVar = it.next() as am
                            if (amVar.bX.k == player.index && amVar is  com.corrodinggames.rts.game.units.y) {
                                w.er.remove(amVar)
                            }
                        }
                        enginePlayer.I()
                        GameEngine.data.gameLinkFunction.allPlayerSync()
                    }
                }
            }

            addPacketToEngineSafely(playerExitInternalPacket(), "player_exit")

            schedulePlayerLeaveEvent(player, wasStartGame)
        }
        super.close(null)
    }

    private fun schedulePlayerLeaveEvent(player: PlayerHess, wasStartGame: Boolean) {
        val delayMillis = StartModePlayerExperienceSupport.leaveDebounceMillis(wasStartGame)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val taskName = "PlayerLeave-${System.identityHashCode(player)}-${System.nanoTime()}"
        Threads.newCountdown(
            taskName,
            "StartModePlayerLeave",
            "Debounce player disconnect before firing leave event",
            delayMillis,
            java.util.concurrent.TimeUnit.MILLISECONDS
        ) {
            Threads.newThreadOther {
                firePlayerLeaveEventIfStillDisconnected(player, wasStartGame)
            }
        }
    }

    private fun firePlayerLeaveEventIfStillDisconnected(player: PlayerHess, wasStartGame: Boolean) {
        if (StartModePlayerExperienceSupport.consumeDisconnectedPlayerPrunedAfterGameOver(player)) {
            return
        }
        val playerManage = playerConnectX.room.playerManage
        val shouldFireLeaveEvent = synchronized(playerManage) {
            synchronized(player) {
                val connection = player.con
                val connectionDisconnected = connection == null || runCatching {
                    connection.coverConnect().isDis
                }.getOrDefault(true)
                if (StartModePlayerExperienceSupport.shouldSuppressLeaveEventForReconnect(
                        connectionPresent = connection != null,
                        connectionDisconnected = connectionDisconnected
                    )
                ) {
                    StartModePlayerExperienceSupport.recordLeaveEventSuppressedByReconnect()
                    false
                } else {
                    if (StartModePlayerExperienceSupport.shouldRemovePlayerAllAfterDisconnect(
                            wasStartGame = wasStartGame,
                            roomStillStarted = playerConnectX.room.isStartGame
                        )
                    ) {
                        playerManage.playerAll.remove(player)
                    }
                    true
                }
            }
        }
        if (!shouldFireLeaveEvent) {
            return
        }
        StartModePlayerExperienceSupport.recordLeaveEventFired()
        GameEngine.data.eventManage.fire(PlayerLeaveEvent(GameEngine.data, player)).await()
    }

    override fun sendGameSave(packet: Packet) {
    }

    override fun receivePacket(packet: Packet) {
        if (!this::player.isInitialized &&
            (packet.type == PacketType.KICK ||
                packet.type == PacketType.SERVER_INFO ||
                packet.type == PacketType.TEAM_LIST)
        ) {
            return
        }
        if (room.isBattleRoomLobbyRecoveryInProgress() &&
            (packet.type == PacketType.SERVER_INFO || packet.type == PacketType.TEAM_LIST)
        ) {
            Log.debug("[StartMode] Dropped client lobby state packet during battle-room recovery: type=${packet.type}, player=${player.name}")
            return
        }
        if (StartModeAutoStartSupport.isStartOperationInProgress() &&
            (packet.type == PacketType.KICK ||
                packet.type == PacketType.SERVER_INFO ||
                packet.type == PacketType.TEAM_LIST)
        ) {
            Log.debug("[StartMode] Dropped lobby mutation while game start confirmation is pending: type=${packet.type}")
            return
        }
        var acceptedHostNoNukes: Boolean? = null
        val packetToForward = when (packet.type) {
            PacketType.KICK -> {
                if (room.isStartGame) {
                    sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                    return
                }
                if (!requireLobbyControlAction(StartModeRoomPermissionAction.KICK_PLAYERS)) {
                    return
                }
                packet
            }
            PacketType.SERVER_INFO -> {
                val lockedPacket = runCatching {
                    StartModeMapLockSupport.rewriteIncomingServerInfoPacket(packet) { message ->
                        if (this::player.isInitialized) {
                            sendSystemMessage(message)
                        }
                    }
                }.getOrElse { error ->
                    Log.warn("[StartMode] Dropping malformed SERVER_INFO packet during map-lock rewrite from ${player.name}", error)
                    return
                }
                val serverInfo = runCatching { ServerInfoPacket(lockedPacket.bytes) }.getOrElse { error ->
                    Log.warn("[StartMode] Dropping malformed SERVER_INFO packet from ${player.name}", error)
                    return
                }
                if (!serverInfo.structurallyComplete) {
                    Log.warn(
                        "[StartMode] Dropping incomplete SERVER_INFO options from ${player.name}: " +
                                "version=${serverInfo.optionsVersion}, version2Complete=${serverInfo.version2OptionsComplete}"
                    )
                    return
                }
                if (room.isStartGame) {
                    return
                }
                if (!allowLobbyServerInfoUpdate(serverInfo)) {
                    return
                }
                if (serverInfo.optionsVersion >= 2 && StartModeRoomPermissionSupport.isVisibleHost(player, room)) {
                    acceptedHostNoNukes = serverInfo.nukes
                }
                runCatching {
                    StartModeVisualMapSelectionSupport.rewriteIncomingServerInfoPacket(lockedPacket) { message ->
                        if (this::player.isInitialized) {
                            sendSystemMessage(message)
                        }
                    }
                }.getOrElse { error ->
                    Log.warn("[StartMode] Dropping malformed SERVER_INFO packet during visual-map rewrite from ${player.name}", error)
                    return
                }
            }
            else -> packet
        }
        if (isInboundEngineRelayControlPacket(packetToForward.type)) {
            SingleRoomLowLatencySupport.recordInboundEngineRelayControlRejected(packetToForward.type)
            return
        }
        if (addPacketToEngineSafely(packetToForward, "receive_packet")) {
            if (packetToForward.type == PacketType.SERVER_INFO) {
                StartModeVisualMapSelectionSupport.commitAcceptedServerInfoPacket(packetToForward)
            }
            acceptedHostNoNukes?.let { noNukes ->
                StartModeGameOptionSupport.recordHostNoNukesChanged(player, room, noNukes)
            }
        }
    }

    /**
     * Defense-in-depth for the engine enqueue boundary (P2b).
     *
     * `ad.c(au)V` case 170 (`RELAY_BECOME_SERVER`) is the only writer of `j.c.q = true`, and that
     * flag unlocks case 178 (`PACKET_RECONNECT_TO`), whose handler runs `ad.a(Socket)Z` — flipping
     * the session into "join a server" mode (`ad.C = 0`) and dialling an attacker-chosen endpoint.
     * Both packets are > 100, so [net.rwhps.server...NetEnginePackaging.addPacket] dispatches them
     * straight into the engine via `ad.c(au)V`, bypassing the frame queue.
     *
     * CNKD only ever *sends* these packets (advertising redirect: `PACKET_RECONNECT_TO`;
     * relay: `RELAY_BECOME_SERVER`) and never legitimately receives them from a game client.
     * Relay traffic is handled by `TypeRelay`/`GameVersionRelay`, which do not route through this
     * enqueue boundary — so filtering here cannot break relay mode.
     */
    private fun isInboundEngineRelayControlPacket(type: AbstractPacketType): Boolean {
        return type == PacketType.RELAY_BECOME_SERVER ||
            type == PacketType.PACKET_RECONNECT_TO
    }

    private fun addPacketToEngineSafely(packet: Packet, context: String): Boolean {
        return try {
            playerConnectX.netEnginePackaging.addPacket(packet)
            true
        } catch (error: ThreadDeath) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            val lowLatencyConfig = SingleRoomLowLatencySupport.runtimeConfig()
            SingleRoomLowLatencySupport.recordDirectEngineForwardFailed(error, lowLatencyConfig)
            Log.warn(
                "[Headless] Failed to forward packet to NetEngine: context=$context, type=${packet.type}",
                error
            )
            false
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }

        if (player != (other as GameVersionServer).player) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        return player.hashCode()
    }

    private fun allowLobbyServerInfoUpdate(serverInfo: ServerInfoPacket): Boolean {
        val actions = linkedSetOf<StartModeRoomPermissionAction>()
        val current = GameEngine.data.gameLinkServerData
        if (serverInfo.mapName.isNotBlank() && !sameLobbyMapName(serverInfo.mapName, room.maps.mapName)) {
            actions += StartModeRoomPermissionAction.CHANGE_MAP
        }
        if (serverInfo.credits != current.credits) {
            actions += StartModeRoomPermissionAction.SET_CREDITS
        }
        if (serverInfo.fog != current.fog) {
            actions += StartModeRoomPermissionAction.SET_FOG
        }
        if (serverInfo.revealedMap != true) {
            actions += StartModeRoomPermissionAction.SET_FOG
        }
        if (serverInfo.aiDifficuly != current.aiDifficuld) {
            actions += StartModeRoomPermissionAction.SET_AI_DIFFICULTY
        }
        if (serverInfo.optionsVersion >= 2) {
            if (serverInfo.initUnit != current.startingunits) {
                actions += StartModeRoomPermissionAction.SET_STARTING_UNITS
            }
            if (java.lang.Float.compare(serverInfo.income, current.income) != 0) {
                actions += StartModeRoomPermissionAction.SET_INCOME
            }
            if (serverInfo.nukes != current.nukes) {
                actions += StartModeRoomPermissionAction.TOGGLE_NUKES
            }
        }

        for (action in actions) {
            if (!requireLobbyControlAction(action)) {
                return false
            }
        }
        return true
    }

    private fun sameLobbyMapName(left: String, right: String): Boolean {
        val normalizedLeft = normalizeLobbyMapName(left)
        val normalizedRight = normalizeLobbyMapName(right)
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return false
        }
        return normalizedLeft.equals(normalizedRight, ignoreCase = true) ||
            normalizedLeft.substringAfterLast('/').equals(normalizedRight.substringAfterLast('/'), ignoreCase = true)
    }

    private fun normalizeLobbyMapName(value: String): String {
        return value.trim()
            .trimStart('\u0001')
            .replace('\\', '/')
            .trim()
    }

    private fun requireLobbyControlAction(action: StartModeRoomPermissionAction): Boolean {
        return if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
            StartModeRoomPermissionSupport.require(player, action)
        } else {
            StartModeRoomPermissionSupport.requireDelegatedPlayerAction(player, action)
        }
    }
}
