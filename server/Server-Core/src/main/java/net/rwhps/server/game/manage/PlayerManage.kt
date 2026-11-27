/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.manage

import com.corrodinggames.rts.gameFramework.j.NetEnginePackaging
import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.headless.core.link.AbstractLinkPlayerData
import net.rwhps.server.game.player.AiPlayer
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.net.netconnectprotocol.internal.server.playerExitInternalPacket
import net.rwhps.server.plugin.internal.headless.support.StartModePlayerExperienceSupport
import net.rwhps.server.plugin.internal.headless.support.StartModePersistentAccessSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.file.load.I18NBundle
import net.rwhps.server.util.inline.coverConnect
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.log.Log

/**
 * Hess 下的玩家管理器
 *
 * 不需要用户手动创建, 在每个 Hess Server自带.
 *
 * RW-HPS 单例下使用(java) :
 * HeadlessModuleManage.INSTANCE.getHps().getRoom().getPlayerManage()
 *
 * @author Dr (dr@der.kim)
 */
class PlayerManage(
    private val gameModule: AbstractGameModule
) {
    /** Online players   */
    @JvmField
    val playerGroup = Seq<PlayerHess>(16, true)

    /** ALL players  */
    @JvmField
    val playerAll = Seq<PlayerHess>(16, true)

    @Synchronized
    internal fun addAI(): AiPlayer? {
        gameModule.gameScriptMultiPlayer.addAi()
        for (position in 0 until Data.configServer.maxPlayer) {
            if (getPlayer(position) == null && gameModule.gameHessData.existPlayer(position)) {
                return AiPlayer(gameModule.gameHessData.getHeadlessAIServer(), gameModule.gameLinkServerData.getPlayerAIData(position)).also {
                    playerGroup.add(it)
                    playerAll.add(it)
                }
            }
        }
        return null
    }

    @Synchronized
    fun addAbstractPlayer(
        con: AbstractNetConnectServer, playerData: AbstractLinkPlayerData, i18NBundle: I18NBundle = Data.i18NBundle
    ): PlayerHess {
        var player: PlayerHess? = null
        var resultFlag = true

        playerAll.eachAllFind({ i: PlayerHess -> i.connectHexID == playerData.connectHexID }) { e: PlayerHess ->
            synchronized(e) {
                val wasDisconnected = e.con == null
                e.con = con
                e.playerPrivateData = playerData
                if (wasDisconnected) {
                    StartModePlayerExperienceSupport.markReconnectIfProtected(e)
                }
            }
            resultFlag = false
            player = e
        }

        if (resultFlag) {
            player = PlayerHess(con, i18NBundle, playerData)
            if (Data.configServer.oneAdmin && StartModeRoomPermissionSupport.allowAutoAdminAssignment()) {
                var hasAutoAdmin = false
                playerGroup.eachFind({
                    it.con != null && StartModeRoomPermissionSupport.hasEffectiveHostAuthority(it)
                }) { hasAutoAdmin = true }

                if (!hasAutoAdmin) {
                    player!!.isAdmin = true
                    player!!.autoAdmin = true
                }
            }

            if (Data.core.admin.isAdmin(player!!)) {
                val adminData = Data.core.admin.playerAdminData[player.connectHexID]
                if (adminData != null) {
                    player.isAdmin = adminData.admin
                    player.superAdmin = adminData.superAdmin
                }
            }
            StartModePersistentAccessSupport.applyPlayerAccess(player!!)

            playerAll.add(player!!)
        }

        StartModePersistentAccessSupport.applyPlayerAccess(player!!)

        if (!playerGroup.contains(player!!)) {
            playerGroup.add(player!!)
        }
        return player!!
    }

    fun findPlayer(writeConsole: StrCons, findIn: String): PlayerHess? {
        var conTg: PlayerHess? = null

        var findNameIn: String? = null
        var findPositionIn: Int? = null

        if (IsUtils.isNumeric(findIn)) {
            findPositionIn = findIn.toInt() - 1
        } else {
            findNameIn = findIn
        }

        findNameIn?.let { findName ->
            var count = 0
            playerGroup.eachAll {
                if (it.name.contains(findName, ignoreCase = true)) {
                    conTg = it
                    count++
                }
            }
            if (count > 1) {
                writeConsole("目标不止一个, 请不要输入太短的玩家名")
                return@let
            }
            if (conTg == null) {
                writeConsole("找不到玩家")
                return@let
            }
        }

        findPositionIn?.let { findPosition ->
            playerGroup.eachAll {
                if (it.index == findPosition) {
                    conTg = it
                }
            }
            if (conTg == null) {
                writeConsole("找不到玩家")
                return@let
            }
        }

        return conTg
    }

    fun runPlayerArrayDataRunnable(run: (PlayerHess) -> Unit) {
        playerAll.eachAll { player ->
            run(player)
        }
    }

    fun getPlayersNameOnTheSameTeam(team: Int): Seq<String> {
        val result = Seq<String>()
        playerAll.eachAll { player ->
            if (player.team == team) {
                result.add(player.name)
            }
        }
        return result
    }

    fun getPlayer(index: Int): PlayerHess? {
        var result: PlayerHess? = null
        playerAll.eachAll { player ->
            if (result == null && player.index == index) {
                result = player
            }
        }
        return result
    }

    fun isStaleHumanPlayer(player: PlayerHess): Boolean {
        if (player.isAi) {
            return false
        }

        if (isNaPlaceholder(player)) {
            return true
        }

        if (StartModePlayerExperienceSupport.isWithinReconnectGrace(player)) {
            return false
        }

        val connection = player.con
        if (connection == null || runCatching { connection.coverConnect().isDis }.getOrDefault(false)) {
            return true
        }

        return false
    }

    fun isConnectedHumanPlayer(player: PlayerHess): Boolean {
        if (player.isAi || isNaPlaceholder(player)) {
            return false
        }

        val connection = player.con ?: return false
        return !runCatching { connection.coverConnect().isDis }.getOrDefault(true)
    }

    fun countConnectedHumanPlayers(): Int {
        return buildPlayerPresenceSnapshot().connectedHumanCount
    }

    fun countConnectedOccupants(): Int {
        return buildPlayerPresenceSnapshot().connectedOccupants
    }

    fun countGraceProtectedHumanPlayers(): Int {
        return buildPlayerPresenceSnapshot().graceProtectedHumanCount
    }

    fun countReconnectGraceHumanPlayers(): Int {
        return buildPlayerPresenceSnapshot().reconnectGraceHumanCount
    }

    /** Start thresholds count active combatants; spectators remain visible in online totals. */
    fun countConnectedParticipants(): Int = playerGroup.count {
        it.team != -3 && (it.isAi || isConnectedHumanPlayer(it))
    }

    fun countReservedParticipants(): Int = playerAll.count { it.team != -3 }

    fun buildPlayerPresenceSnapshot(): PlayerPresenceSnapshot {
        var connectedOccupants = 0
        var reservedOccupants = 0
        var connectedHumanCount = 0
        var graceProtectedHumanCount = 0
        var reconnectGraceHumanCount = 0
        var staleHumanCount = 0
        var aiCount = 0

        playerGroup.eachAll { player ->
            if (player.isAi || isConnectedHumanPlayer(player)) {
                connectedOccupants++
            }
        }

        playerAll.eachAll { player ->
            reservedOccupants++
            if (player.isAi) {
                aiCount++
            } else {
                val placeholder = isNaPlaceholder(player)
                if (placeholder) {
                    staleHumanCount++
                } else {
                    val connection = player.con
                    val connected = connection != null && !runCatching { connection.coverConnect().isDis }.getOrDefault(true)
                    val reconnectGrace = connection == null && StartModePlayerExperienceSupport.isWithinReconnectGrace(player)
                    if (connected) {
                        connectedHumanCount++
                    }
                    if (reconnectGrace) {
                        reconnectGraceHumanCount++
                    }
                    if (connected || reconnectGrace) {
                        graceProtectedHumanCount++
                    } else {
                        staleHumanCount++
                    }
                }
            }
        }

        return PlayerPresenceSnapshot(
            connectedOccupants = connectedOccupants,
            reservedOccupants = reservedOccupants,
            connectedHumanCount = connectedHumanCount,
            graceProtectedHumanCount = graceProtectedHumanCount,
            reconnectGraceHumanCount = reconnectGraceHumanCount,
            staleHumanCount = staleHumanCount,
            aiCount = aiCount
        )
    }

    fun pruneStalePlayer(player: PlayerHess, reason: String, syncAfterPrune: Boolean = true): Boolean {
        var pruned = false
        val operationSucceeded = gameModule.gameFunction.suspendMainThreadOperations {
            pruned = pruneStalePlayerOnGameThread(player, reason)
        }
        if (!operationSucceeded) {
            Log.warn("[StartMode] Failed to prune stale player on the game thread: reason=$reason")
            return false
        }

        if (pruned && syncAfterPrune) {
            runCatching { gameModule.room.call.syncAllPlayer() }
                .onFailure { Log.warn("[StartMode] Failed to sync lobby after pruning stale player slot", it) }
        }
        return pruned
    }

    @Synchronized
    private fun pruneStalePlayerOnGameThread(player: PlayerHess, reason: String): Boolean {
        synchronized(player) {
            if (!isStaleHumanPlayer(player)) {
                return false
            }

            val name = runCatching { player.name }.getOrDefault("N/A").ifBlank { "N/A" }
            val position = runCatching { player.position }.getOrDefault(-1)
            val connectId = runCatching { player.connectHexID }.getOrDefault("N/A").ifBlank { "N/A" }

            playerGroup.remove(player)
            playerAll.remove(player)
            StartModeLobbySupport.forgetPlayer(player)
            StartModePlayerExperienceSupport.markDisconnectedPlayerPrunedAfterGameOver(player)
            if (runCatching { gameModule.gameHessData.existPlayer(player.index) }.getOrDefault(false)) {
                runCatching { player.playerPrivateData.removePlayer() }
                    .onFailure { Log.warn("[StartMode] Failed to remove stale player private data: $name", it) }
            }
            player.clear()

            Log.clog(
                "[StartMode] Pruned stale player slot: reason=$reason, name=$name, position=$position, connectId=$connectId."
            )
        }
        return true
    }

    /**
     * Remove disconnected human slots before a completed match becomes a lobby.
     * This runs on the game thread, so engine player removal cannot race render.
     */
    @Synchronized
    fun pruneDisconnectedPlayersForLobbyTransition(reason: String): Int {
        if (!Data.configStartMode.pruneDisconnectedPlayersOnGameOver) {
            return 0
        }

        val candidates = ArrayList<PlayerHess>()
        playerAll.eachAll { player ->
            if (!player.isAi && !isConnectedHumanPlayer(player)) {
                candidates += player
            }
        }

        var pruned = 0
        candidates.forEach { player ->
            synchronized(player) {
                if (player.isAi || isConnectedHumanPlayer(player)) {
                    return@forEach
                }

                val name = runCatching { player.name }.getOrDefault("N/A").ifBlank { "N/A" }
                val position = runCatching { player.position }.getOrDefault(-1)
                playerGroup.remove(player)
                playerAll.remove(player)
                StartModeLobbySupport.forgetPlayer(player)
                StartModePlayerExperienceSupport.markDisconnectedPlayerPrunedAfterGameOver(player)
                if (runCatching { gameModule.gameHessData.existPlayer(player.index) }.getOrDefault(false)) {
                    runCatching { player.playerPrivateData.removePlayer() }
                        .onFailure {
                            Log.warn("[StartMode] Failed to remove disconnected engine player during lobby transition: $name", it)
                        }
                }
                player.clear()
                pruned++
                Log.clog(
                    "[StartMode] Pruned disconnected player for lobby transition: " +
                        "reason=$reason, name=$name, position=$position."
                )
            }
        }
        return pruned
    }

    private fun isNaPlaceholder(player: PlayerHess): Boolean {
        val name = runCatching { player.name.trim() }.getOrDefault("")
        val connectId = runCatching { player.connectHexID.trim() }.getOrDefault("")
        return isPlaceholderText(name) && isPlaceholderText(connectId)
    }

    private fun isPlaceholderText(value: String): Boolean {
        return value.isBlank() || value.equals("N/A", ignoreCase = true)
    }

    @Synchronized
    fun cleanPlayerAllData() {
        val players = ArrayList<PlayerHess>()
        playerAll.eachAll { player ->
            players += player
        }

        var onlineHumans = 0
        var offlineHumans = 0
        var aiPlayers = 0
        var privateDataRemoved = 0

        players.forEach { player ->
            if (player.isAi) {
                aiPlayers++
            } else if (isStaleHumanPlayer(player)) {
                offlineHumans++
            } else {
                onlineHumans++
            }
        }

        runCatching { gameModule.room.call.killAllPlayer("CleanAllPlayer") }
            .onFailure { Log.warn("[StartMode] Failed to kick players before reset cleanup; stale cleanup will continue.", it) }

        players.forEach { player ->
            synchronized(player) {
                runCatching { player.playerPrivateData.removePlayer() }
                    .onSuccess { privateDataRemoved++ }
                    .onFailure { Log.warn("[StartMode] Failed to remove player private data during reset cleanup: ${player.name}", it) }
                if (!player.isAi) {
                    StartModeLobbySupport.forgetPlayer(player)
                    StartModePlayerExperienceSupport.markDisconnectedPlayerPrunedAfterGameOver(player)
                }
                player.clear()
            }
        }

        StartModeLobbySupport.resetRuntimeState()
        playerAll.clear()
        playerGroup.clear()
        Log.clog(
            "[StartMode] Reset player cleanup completed: onlineHumans=$onlineHumans, offlineHumans=$offlineHumans, " +
                    "ai=$aiPlayers, privateDataRemoved=$privateDataRemoved."
        )
    }

    @Synchronized
    fun verifyResetPlayerCleanupComplete(reason: String): Boolean {
        val residualPlayers = ArrayList<PlayerHess>()
        val residualAllCount = playerAll.size
        val residualGroupCount = playerGroup.size

        playerAll.eachAll { player ->
            if (residualPlayers.none { it === player }) {
                residualPlayers += player
            }
        }
        playerGroup.eachAll { player ->
            if (residualPlayers.none { it === player }) {
                residualPlayers += player
            }
        }

        if (residualAllCount == 0 && residualGroupCount == 0) {
            Log.clog("[StartMode] Reset player cleanup verified: reason=$reason, playerAll=0, playerGroup=0.")
            return true
        }

        Log.warn(
            "[StartMode] reset_player_cleanup_incomplete: reason=$reason, " +
                    "playerAll=$residualAllCount, playerGroup=$residualGroupCount. Running second-stage cleanup."
        )

        var privateDataRemoved = 0
        residualPlayers.forEach { player ->
            synchronized(player) {
                runCatching { player.playerPrivateData.removePlayer() }
                    .onSuccess { privateDataRemoved++ }
                    .onFailure { Log.warn("[StartMode] Failed to remove residual player private data: ${player.name}", it) }
                if (!player.isAi) {
                    StartModeLobbySupport.forgetPlayer(player)
                    StartModePlayerExperienceSupport.markDisconnectedPlayerPrunedAfterGameOver(player)
                }
                player.clear()
            }
        }

        StartModeLobbySupport.resetRuntimeState()
        playerAll.clear()
        playerGroup.clear()
        Log.clog(
            "[StartMode] Reset player second-stage cleanup completed: reason=$reason, " +
                    "residualPlayers=${residualPlayers.size}, privateDataRemoved=$privateDataRemoved."
        )
        return playerAll.size == 0 && playerGroup.size == 0
    }

    data class PlayerPresenceSnapshot(
        val connectedOccupants: Int,
        val reservedOccupants: Int,
        val connectedHumanCount: Int,
        val graceProtectedHumanCount: Int,
        val reconnectGraceHumanCount: Int,
        val staleHumanCount: Int,
        val aiCount: Int
    )
}
