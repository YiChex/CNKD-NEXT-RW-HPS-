/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.lib

import com.corrodinggames.rts.gameFramework.j.NetEnginePackaging
import com.corrodinggames.rts.gameFramework.j.ad
import com.corrodinggames.rts.gameFramework.j.au
import com.corrodinggames.rts.gameFramework.j.c
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.event.game.PlayerJoinEvent
import net.rwhps.server.game.event.game.ServerGameStartEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.inject.core.link.PrivateClassLinkPlayer
import net.rwhps.server.plugin.internal.headless.inject.net.GameVersionServer
import net.rwhps.server.plugin.internal.headless.inject.net.socket.HessSocket
import net.rwhps.server.plugin.internal.headless.support.BattleRoomTransitionPolicy
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeDefeatedPlayerKickSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLatencyHeartbeatSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.util.log.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import com.corrodinggames.rts.gameFramework.j.c as PlayerConnect

/**
 * @author Dr (dr@der.kim)
 */
class PlayerConnectX(
    val netEngine: ad, val connectionAgreement: ConnectionAgreement
): PlayerConnect(netEngine, HessSocket(connectionAgreement)) {

    val netEnginePackaging: NetEnginePackaging = NetEnginePackaging(netEngine, this)
    var room: ServerRoom = HeadlessModuleManage.hessLoaderMap[this.javaClass.classLoader.toString()]!!.room
    var player: PlayerHess? = null
    lateinit var serverConnect: GameVersionServer
    private val lobbyPacketStates = ConcurrentHashMap<Int, LobbyPacketState>()
    private val connectedAtMillis = System.currentTimeMillis()
    private val connectedLobbyGeneration = room.lobbyLifecycleGeneration()

    @Synchronized
    override fun a(p0: Boolean, p1: Boolean, p2: String?) {
        super.a(p0, p1, p2)
        serverConnect.disconnect()
    }

    override fun d() {
        // Register BIO
    }

    override fun a(packetHess: au) {
        if (player == null) {
            if (this.e() != "<null>") {
                val joiningPlayerData = PrivateClassLinkPlayer(z)
                if (StartModeDefeatedPlayerKickSupport.shouldRejectReconnect(room, joiningPlayerData.connectHexID)) {
                    runCatching {
                        serverConnect.sendKick(Data.configStartMode.autoKickPlayerWhenAllUnitsDestroyedMessage)
                    }.onFailure { error ->
                        Log.warn("[StartMode] Failed to send defeated-player reconnect denial", error)
                    }
                    serverConnect.disconnect()
                    return
                }
                player = room.playerManage.addAbstractPlayer(serverConnect, joiningPlayerData)

                serverConnect.player = player!!

                GameEngine.data.eventManage.fire(PlayerJoinEvent(GameEngine.data, player!!))

                if (!Threads.containsTimeTask(CallTimeTask.CallTeamTask)) {
                    Threads.newTimedTask(CallTimeTask.CallTeamTask, 0, 1, TimeUnit.SECONDS) {
                        if (room.isBattleRoomLobbyRecoveryInProgress()) {
                            return@newTimedTask
                        }
                        val lobbyRulesChanged = StartModeLobbySupport.enforceLobbyRules(forceSync = false)
                        if (shouldRefreshLobbyState(room, lobbyRulesChanged)) {
                            val operationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
                                if (!room.isStartGame) {
                                    GameEngine.netEngine.e(null as c?)
                                    GameEngine.netEngine.L()
                                }
                            }
                            if (!operationSucceeded) {
                                net.rwhps.server.util.log.Log.warn("[Headless] Failed to refresh lobby state after player join")
                            }
                        }
                    }
                }
            }
        }

        if (player != null) {
            if (shouldDropGameTrafficAfterBattleRoomReturn(packetHess.b)) {
                return
            }
            if (shouldFastSendStartedGamePacket(packetHess.b)) {
                sendTransformedPacket(packetHess)
                return
            }

            // 在这里过滤走官方的包, 加入 RW-HPS 的一些修改
            run {
                when (packetHess.b) {
                    PacketType.PREREGISTER_INFO.typeInt -> {
                        GameInputStream(packetHess.c).use {
                            val o = GameOutputStream()
                            o.writeString(it.readString())
                            o.transferToFixedLength(it, 12)
                            o.writeString(Data.SERVER_ID)
                            it.skip(it.readShort().toLong())
                            o.transferTo(it)
                            packetHess.c = o.getByteArray()
                        }
                    }
                    // 修改, 使 客户端 显示 AdminUI
                    PacketType.SERVER_INFO.typeInt -> {
                        GameInputStream(packetHess.c).use {
                            val o = GameOutputStream()
                            it.skip(it.readShort().toLong())
                            o.writeString(Data.SERVER_ID)

                            o.transferToFixedLength(it, 8)

                            val length = it.readShort()
                            o.writeShort(length)
                            o.transferToFixedLength(it, length.toInt())

                            o.transferToFixedLength(it, 15)

                            /* Admin Ui */
                            it.skip(1)
                            o.writeBoolean(StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player!!))
                            o.transferTo(it)
                            packetHess.c = o.getByteArray()
                        }
                    }
                    // 修改, 使 客户端 显示 HOST
                    PacketType.TEAM_LIST.typeInt -> {
                        val originalTeamList = packetHess.c
                        runCatching {
                            GameInputStream(originalTeamList).use {
                            val o = GameOutputStream()
                            val site = it.readInt()
                            o.writeInt(site)
                            val isGameStatus = it.readBoolean()
                            o.writeBoolean(isGameStatus)
                            if (!isGameStatus) {
                                val playerConut = it.readInt()
                                o.writeInt(playerConut)

                                room.flagData.ai = false
                                CompressOutputStream.getGzipOutputStream("teams", true).also { teamIn ->
                                    it.getDecodeStream(true).use { team ->
                                        for (position in 0 until playerConut) {
                                            val hasPlayer = team.readBoolean()
                                            teamIn.writeBoolean(hasPlayer)
                                            if (hasPlayer) {
                                                teamIn.transferToFixedLength(team, 13)
                                                val name = team.readIsString()
                                                teamIn.writeIsString(name)

                                                //teamIn.writeBoolean(team.readBoolean())
//                                                team.skip(4)
//                                                teamIn.writeInt(99999)

                                                val rowPlayer = room.playerManage.getPlayer(position)
                                                writeLatencyDisplayFields(teamIn, team, rowPlayer)

                                                // 可能存在 Hess 还没刷新的, 所以多来一次判断
                                                if (rowPlayer == null) {
                                                    teamIn.transferToFixedLength(team, 4)
                                                    // 过滤掉 AI
                                                    if (name.contains("AI", ignoreCase = true)) {
                                                        room.flagData.ai = true
                                                    }
                                                } else {
                                                    team.skip(4)
                                                    teamIn.writeInt(if (StartModeRoomPermissionSupport.isVisibleHost(rowPlayer, room)) 1 else 0)
                                                }


                                                teamIn.writeIsInt(team)
                                                teamIn.writeIsInt(team)
                                                teamIn.writeIsInt(team)
                                                teamIn.writeIsInt(team)
                                                teamIn.writeInt(team.readInt())
                                            }
                                        }
                                    }
                                    o.flushEncodeData(teamIn)
                                }
                                o.transferTo(it)
                                packetHess.c = o.getByteArray()
                            }
                        }
                        }.onFailure { error ->
                            packetHess.c = originalTeamList
                            Log.warn("[Headless] TEAM_LIST rewrite failed; forwarding the original engine packet", error)
                        }
                    }
                    //
                    PacketType.START_GAME.typeInt -> {
                        val rewrittenPacket = serverConnect.rewriteStartGamePacket(packetHess.c)
                        if (rewrittenPacket == null) {
                            StartModeAutoStartSupport.onEngineStartPacketRejected(room, "custom_start_packet_build_failed")
                            return
                        }
                        val roomInitialized = room.roomStartGame()
                        if (!roomInitialized && !room.isStartGame) {
                            StartModeAutoStartSupport.onEngineStartPacketRejected(room, "room_start_state_rejected")
                            return
                        }
                        if (roomInitialized) {
                            GameEngine.data.eventManage.fire(ServerGameStartEvent(GameEngine.data))
                        }
                        packetHess.c = rewrittenPacket
                    }
                    PacketType.RETURN_TO_BATTLEROOM.typeInt -> {
                        // The engine emits this outbound packet only after its return countdown finishes.
                        // Record that commit signal; ServerRoom performs the service-side cleanup once.
                        room.onEngineBattleRoomReturnPacket()
                    }
                }
            }
        }
        run {
            when (packetHess.b) {
                PacketType.KICK.typeInt -> {
                    GameInputStream(packetHess.c).use {
                        val o = GameOutputStream()
                        val msg = it.readString()
                        if (Data.configServer.maxPlayerJoinAd.isNotBlank() && msg.contains("free")) {
                            o.writeString(Data.configServer.maxPlayerJoinAd)
                        } else if (Data.configServer.startPlayerJoinAd.isNotBlank() && msg.contains("started")) {
                            o.writeString(Data.configServer.startPlayerJoinAd)
                        } else {
                            return@run
                        }
                        packetHess.c = o.getByteArray()
                    }
                }
            }
        }

        if (packetHess.b == PacketType.SERVER_INFO.typeInt) {
            packetHess.c = StartModeMapLockSupport
                .rewriteIncomingServerInfoPacket(Packet(packetHess.b, packetHess.c))
                .bytes
        }

        if (shouldRunRedundantLobbyPacketFilter(packetHess.b) &&
            shouldDropRedundantLobbyPacket(packetHess.b, packetHess.c)
        ) {
            return
        }

        sendTransformedPacket(packetHess)
    }

    private fun writeLatencyDisplayFields(out: GameOutputStream, input: GameInputStream, rowPlayer: PlayerHess?) {
        // game.n.b(as): U flag, displayed ping, lastPingTimeReceivedAt, then fixed lobby flags before Q.
        out.writeBoolean(input.readBoolean())
        val originalPingMillis = input.readInt()
        val displayPingMillis = StartModeLatencyHeartbeatSupport.displayPingMillis(rowPlayer)
        out.writeInt(if (displayPingMillis > 0) displayPingMillis else originalPingMillis)
        out.writeLong(input.readLong())
        out.transferToFixedLength(input, TEAM_LIST_AFTER_LATENCY_FIXED_BYTES)
    }

    private fun sendTransformedPacket(packetHess: au) {
        val outgoingPacket = netEnginePackaging.transformPacket(packetHess)
        SingleRoomLowLatencySupport.recordOutboundPacket(outgoingPacket)
        serverConnect.sendPacket(outgoingPacket)
    }

    private fun shouldFastSendStartedGamePacket(packetType: Int): Boolean {
        if (!room.isStartGame || room.isBattleRoomTransitionCommitBlocking()) {
            return false
        }
        return packetType == PacketType.TICK.typeInt ||
            packetType == PacketType.SYNC.typeInt ||
            packetType == PacketType.SYNC_CHECK.typeInt ||
            packetType == PacketType.SYNCCHECKSUM_STATUS.typeInt
    }

    private fun shouldRunRedundantLobbyPacketFilter(packetType: Int): Boolean {
        if (room.isStartGame) {
            return false
        }
        if (packetType == PacketType.TEAM_LIST.typeInt) {
            return true
        }
        if (packetType != PacketType.SYNC.typeInt &&
            packetType != PacketType.SYNC_CHECK.typeInt &&
            packetType != PacketType.SYNCCHECKSUM_STATUS.typeInt
        ) {
            return false
        }
        return StartModeLobbySupport.lobbyGameSyncPacketFilterEnabled()
    }

    private fun shouldDropRedundantLobbyPacket(packetType: Int, packetBytes: ByteArray): Boolean {
        if (isPreGameGameSyncPacket(packetType) &&
            (!BattleRoomTransitionPolicy.canUseLobbyJoinGrace(
                connectedGeneration = connectedLobbyGeneration,
                currentGeneration = room.lobbyLifecycleGeneration()
            ) || room.isBattleRoomLobbyRecoveryInProgress())
        ) {
            StartModeLobbySupport.recordLobbyPacket(forwarded = false)
            return true
        }
        if (!StartModeLobbySupport.lobbySyncOptimizationEnabled()) {
            StartModeLobbySupport.recordLobbyPacket(forwarded = true)
            return false
        }

        val now = System.currentTimeMillis()
        if (isPreGameGameSyncPacket(packetType) && isWithinGameSyncJoinGrace(now)) {
            StartModeLobbySupport.recordLobbyPacket(forwarded = true)
            return false
        }

        // Fix: Use per-player, per-packet-type state to avoid cross-player desync
        val stateKey = java.util.Objects.hash(packetType, player?.connectHexID ?: "unknown")
        val fingerprint = buildOutgoingLobbyPacketFingerprint(packetType, packetBytes)
        val state = lobbyPacketStates.computeIfAbsent(stateKey) { LobbyPacketState() }
        synchronized(state) {
            val duplicate = state.fingerprint == fingerprint
            val heartbeatDue = now - state.forwardedAtMillis >= lobbyPacketHeartbeatMillis(packetType)
            if (duplicate && !heartbeatDue) {
                StartModeLobbySupport.recordLobbyPacket(forwarded = false)
                return true
            }

            state.fingerprint = fingerprint
            state.forwardedAtMillis = now
            StartModeLobbySupport.recordLobbyPacket(forwarded = true)
            return false
        }
    }

    private fun isPreGameLobbyPacketCandidate(packetType: Int): Boolean {
        return packetType == PacketType.TEAM_LIST.typeInt ||
            isPreGameGameSyncPacket(packetType)
    }

    private fun isPreGameGameSyncPacket(packetType: Int): Boolean {
        return StartModeLobbySupport.lobbyGameSyncPacketFilterEnabled() &&
            (packetType == PacketType.SYNC.typeInt ||
                packetType == PacketType.SYNC_CHECK.typeInt ||
                packetType == PacketType.SYNCCHECKSUM_STATUS.typeInt)
    }

    private fun lobbyPacketHeartbeatMillis(packetType: Int): Long {
        if (packetType == PacketType.TEAM_LIST.typeInt) {
            return StartModeLatencyHeartbeatSupport.latencyHeartbeatMillis()
        }
        if (!isPreGameGameSyncPacket(packetType)) {
            return StartModeLobbySupport.lobbyPacketHeartbeatMillis()
        }
        return min(
            StartModeLobbySupport.lobbyPacketHeartbeatMillis(),
            StartModeLobbySupport.lobbyGameSyncPacketHeartbeatMillis()
        )
    }

    private fun isWithinGameSyncJoinGrace(now: Long): Boolean {
        val graceMillis = StartModeLobbySupport.lobbyGameSyncPacketJoinGraceMillis()
        if (graceMillis <= 0) {
            return false
        }
        if (!BattleRoomTransitionPolicy.canUseLobbyJoinGrace(
                connectedGeneration = connectedLobbyGeneration,
                currentGeneration = room.lobbyLifecycleGeneration()
            )
        ) {
            return false
        }
        return BattleRoomTransitionPolicy.isWithinLobbySyncGrace(
            nowMillis = now,
            connectedAtMillis = connectedAtMillis,
            graceMillis = graceMillis,
            graceUntilMillis = 0L
        )
    }

    private fun shouldDropGameTrafficAfterBattleRoomReturn(packetType: Int): Boolean {
        if (!room.isBattleRoomTransitionCommitBlocking()) {
            return false
        }
        return packetType == PacketType.TICK.typeInt ||
            packetType == PacketType.SYNC.typeInt ||
            packetType == PacketType.SYNC_CHECK.typeInt ||
            packetType == PacketType.SYNCCHECKSUM_STATUS.typeInt
    }

    private fun buildOutgoingLobbyPacketFingerprint(packetType: Int, packetBytes: ByteArray): String {
        // Fix: Include per-player identity in fingerprint to prevent cross-player desync
        val playerId = player?.connectHexID ?: "unknown"
        val playerAdmin = player?.let { StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(it) } ?: false
        // Recovery-owned broadcasts must not be collapsed into a previous lobby packet.
        val recoveryPacketEpoch = room.battleRoomLobbyRecoveryPacketEpoch()

        if (isPreGameGameSyncPacket(packetType)) {
            return listOf(
                packetType,
                playerId,
                packetBytes.size,
                packetBytes.contentHashCode(),
                room.lobbyLifecycleGeneration(),
                recoveryPacketEpoch,
                buildLobbyRefreshSignature(room)
            ).joinToString("\u0002")
        }

        if (packetType == PacketType.TEAM_LIST.typeInt) {
            return listOf(
                packetType,
                playerId,
                playerAdmin,
                room.lobbyLifecycleGeneration(),
                recoveryPacketEpoch,
                StartModeLatencyHeartbeatSupport.displayPingMillis(player),
                buildLatencyDisplaySignature(room),
                StartModeMapLockSupport.lockedMapName().orEmpty(),
                buildLobbyRefreshSignature(room)
            ).joinToString("\u0002")
        }

        return listOf(
            packetType,
            playerId,
            playerAdmin,
            room.lobbyLifecycleGeneration(),
            recoveryPacketEpoch,
            StartModeMapLockSupport.lockedMapName().orEmpty(),
            buildLobbyRefreshSignature(room)
        ).joinToString("\u0002")
    }

    companion object {
        private const val MAX_LOBBY_REFRESH_STATES = 256
        private const val TEAM_LIST_AFTER_LATENCY_FIXED_BYTES = 19
        private val lobbyRefreshStates = ConcurrentHashMap<String, LobbyRefreshState>()

        private fun shouldRefreshLobbyState(room: ServerRoom, force: Boolean): Boolean {
            if (!StartModeLobbySupport.lobbySyncOptimizationEnabled()) {
                StartModeLobbySupport.recordLobbyRefresh(forwarded = true)
                return true
            }

            val now = System.currentTimeMillis()
            val signature = buildLobbyRefreshSignature(room)
            val state = refreshState(room)
            synchronized(state) {
                val signatureChanged = signature != state.signature
                val heartbeatDue = now - state.refreshAtMillis >= StartModeLobbySupport.lobbyRefreshHeartbeatMillis()
                if (!force && !signatureChanged && !heartbeatDue) {
                    StartModeLobbySupport.recordLobbyRefresh(forwarded = false)
                    return false
                }

                state.signature = signature
                state.refreshAtMillis = now
                StartModeLobbySupport.recordLobbyRefresh(forwarded = true)
                return true
            }
        }

        private fun roomRefreshKey(room: ServerRoom): String {
            val configuredRoomId = runCatching { room.roomID }.getOrDefault("")
            return "${System.identityHashCode(room)}:$configuredRoomId"
        }

        private fun refreshState(room: ServerRoom): LobbyRefreshState {
            if (lobbyRefreshStates.size > MAX_LOBBY_REFRESH_STATES) {
                lobbyRefreshStates.clear()
            }
            return lobbyRefreshStates.computeIfAbsent(roomRefreshKey(room)) { LobbyRefreshState() }
        }

        private fun buildLobbyRefreshSignature(room: ServerRoom): String {
            val players = snapshotPlayerGroup(room)
                .map { buildPlayerLobbySignature(room, it) }
                .sorted()
                .joinToString("|")

            return listOf(
                room.lobbyLifecycleGeneration(),
                room.isStartGame,
                room.isMixPlayer,
                room.maps.mapName,
                room.maps.mapType.name,
                room.maps.mapPlayer,
                buildLobbyGameOptionSignature(),
                players
            ).joinToString("\u0001")
        }

        private fun snapshotPlayerGroup(room: ServerRoom): List<PlayerHess> {
            val players = ArrayList<PlayerHess>()
            room.playerManage.playerGroup.eachAll { player ->
                players += player
            }
            return players
        }

        private fun buildPlayerLobbySignature(room: ServerRoom, player: PlayerHess): String {
            return listOf(
                safeLobbyInt { player.index },
                safeLobbyInt { player.team },
                safeLobbyText { player.name },
                safeLobbyText { player.connectHexID },
                safeLobbyBoolean { player.isAdmin },
                safeLobbyBoolean { player.autoAdmin },
                safeLobbyBoolean { player.superAdmin },
                safeLobbyBoolean { StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player) },
                safeLobbyBoolean { StartModeRoomPermissionSupport.isVisibleHost(player, room) },
                safeLobbyBoolean { player.isAi },
                safeLobbyInt { player.aiDifficulty },
                safeLobbyInt { player.startUnit },
                safeLobbyInt { player.color },
                safeLobbyInt { player.credits }
            ).joinToString(":")
        }

        private fun buildLatencyDisplaySignature(room: ServerRoom): String {
            return snapshotPlayerGroup(room)
                .map { "${safeLobbyInt { it.index }}:${StartModeLatencyHeartbeatSupport.displayPingMillis(it)}" }
                .sorted()
                .joinToString("|")
        }

        private inline fun safeLobbyInt(read: () -> Int): Int {
            return runCatching(read).getOrDefault(0)
        }

        private inline fun safeLobbyBoolean(read: () -> Boolean): Boolean {
            return runCatching(read).getOrDefault(false)
        }

        private inline fun safeLobbyText(read: () -> String): String {
            return runCatching(read).getOrDefault("")
        }

        private fun buildLobbyGameOptionSignature(): String {
            return runCatching {
                val data = HeadlessModuleManage.hps.gameLinkServerData
                listOf(
                    data.maxUnit,
                    data.sharedcontrol,
                    data.fog,
                    data.nukes,
                    data.credits,
                    data.aiDifficuld,
                    data.income,
                    data.startingunits
                ).joinToString(":")
            }.getOrDefault("")
        }

        private data class LobbyRefreshState(
            var signature: String = "",
            var refreshAtMillis: Long = 0L
        )

        private data class LobbyPacketState(
            var fingerprint: String = "",
            var forwardedAtMillis: Long = 0L
        )
    }

    override fun f(): String {
        return connectionAgreement.ip
    }

    override fun g(): String {
        return connectionAgreement.ip
    }
}
