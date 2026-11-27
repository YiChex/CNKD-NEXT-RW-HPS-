/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core.link

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.gameFramework.j.c
import net.rwhps.server.game.headless.core.link.AbstractLinkGameFunction
import net.rwhps.server.game.headless.core.link.BattleRoomLobbyRecoveryLink
import net.rwhps.server.game.headless.core.link.FullGameSyncLink
import net.rwhps.server.game.headless.core.link.FullGameSyncResult
import net.rwhps.server.game.headless.core.link.LobbyPlayerSyncResult
import net.rwhps.server.game.headless.core.link.LobbyTeamRepairResult
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.BattleRoomLobbyRecoveryPolicy
import net.rwhps.server.plugin.internal.headless.support.BattleRoomTransitionPolicy
import net.rwhps.server.plugin.internal.headless.support.GameSyncSafetyPolicy
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.util.file.FileName.getFileNameNoSuffix
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.log.Log
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author Dr (dr@der.kim)
 */
internal class LinkGameFunction: net.rwhps.server.game.headless.core.link.CheckedGameControlLink, AbstractLinkGameFunction, BattleRoomLobbyRecoveryLink, FullGameSyncLink {
    private val engineSyncInProgress = AtomicBoolean(false)

    companion object {
        private const val MAP_ENGINE_NOT_READY = "map_engine_not_ready"
        private const val LOBBY_SYNC_BLOCKED = "battle_room_lobby_sync_blocked"
        private const val LOBBY_SYNC_BUSY = "lobby_player_sync_busy"
        private const val LOBBY_REFRESH_REQUIRES_LOBBY = "lobby_refresh_requires_lobby"
        private const val LOBBY_NET_ENGINE_NOT_READY = "lobby_net_engine_not_ready"
        private const val FULL_GAME_SYNC_REQUIRES_STARTED_ROOM = "full_game_sync_requires_started_room"
        private const val FULL_GAME_SYNC_BUSY = "full_game_sync_busy"

        private fun isLobbyPlayerSyncReady(allowDuringBattleRoomRecovery: Boolean = false): Boolean {
            val room = GameEngine.data.room
            val syncBlocked = if (allowDuringBattleRoomRecovery) {
                room.isLobbyPlayerSyncBlockedForBattleRoomRecovery()
            } else {
                room.isLobbyPlayerSyncBlocked()
            }
            val netEngineReady = runCatching {
                GameEngine.netEngine.ay != null
            }.getOrDefault(false)
            return GameSyncSafetyPolicy.canRefreshLobbyState(
                roomStarted = room.isStartGame,
                lobbySyncBlocked = syncBlocked,
                netEngineReady = netEngineReady
            )
        }

        private fun isFullGameSyncReady(): Boolean {
            val room = GameEngine.data.room
            val roomStarted = room.isStartGame
            val lifecyclePlaying = StartModeGameOverReturnSupport.isPlaying()
            val resetInProgress = room.isGameOverResetInProgress()
            val transitionInProgress = room.isBattleRoomTransitionInProgress()
            val recoveryInProgress = room.isBattleRoomLobbyRecoveryInProgress()
            if (!GameSyncSafetyPolicy.canAttemptFullGameSync(
                    roomStarted,
                    lifecyclePlaying,
                    resetInProgress,
                    transitionInProgress,
                    recoveryInProgress
                )
            ) {
                return false
            }
            val mapEngineReady = runCatching {
                val engine = GameEngine.gameEngine
                synchronized(engine) {
                    val mapEngine = engine.bL
                    mapEngine != null && mapEngine.W && engine.bX != null && engine.bX.ay != null
                }
            }.getOrDefault(false)
            return GameSyncSafetyPolicy.canBroadcastFullGameSync(
                roomStarted,
                lifecyclePlaying,
                resetInProgress,
                transitionInProgress,
                recoveryInProgress,
                mapEngineReady
            )
        }

        private fun mapEngineNotReadyError(cause: Throwable? = null): IllegalStateException {
            return IllegalStateException(MAP_ENGINE_NOT_READY, cause)
        }

        private fun isTransientMapEngineNotReady(error: Throwable): Boolean {
            if (error is IllegalStateException && error.message == MAP_ENGINE_NOT_READY) {
                return true
            }
            if (error !is NullPointerException) {
                return false
            }
            val message = error.message.orEmpty()
            return message.contains(".bL") || message.contains(" bL ")
        }

        private fun isTransientFullGameSerializationFailure(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is EOFException) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }

    override fun allPlayerSync() {
        val result = syncFullGameState()
        result.error?.let { error ->
            if (error is IllegalStateException && error.message == FULL_GAME_SYNC_REQUIRES_STARTED_ROOM) {
                Log.debug("[Headless] Full game SYNC rejected outside a ready active match")
            } else {
                Log.warn("[Headless] Full game SYNC was not completed", error)
            }
        }
    }

    override fun canSyncFullGameState(): Boolean = isFullGameSyncReady()

    override fun syncFullGameState(): FullGameSyncResult = syncFullGameStateForGeneration(GameEngine.data.room.lobbyLifecycleGeneration())

    override fun syncFullGameStateForGeneration(expectedGeneration: Long): FullGameSyncResult {
        val generation = expectedGeneration
        if (GameEngine.data.room.lobbyLifecycleGeneration() != generation) {
            return FullGameSyncResult(false, IllegalStateException("full_game_sync_match_changed"))
        }
        if (!isFullGameSyncReady()) {
            return FullGameSyncResult(false, IllegalStateException(FULL_GAME_SYNC_REQUIRES_STARTED_ROOM))
        }
        if (!engineSyncInProgress.compareAndSet(false, true)) {
            return FullGameSyncResult(false, IllegalStateException(FULL_GAME_SYNC_BUSY))
        }
        var syncError: Throwable? = null
        val operationSucceeded: Boolean
        try {
            operationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
                syncError = if (GameEngine.data.room.lobbyLifecycleGeneration() != generation) {
                    IllegalStateException("full_game_sync_match_changed")
                } else fullGameSyncOnGameThread()
            }
        } finally {
            engineSyncInProgress.set(false)
        }
        return if (operationSucceeded && syncError == null && GameEngine.data.room.lobbyLifecycleGeneration() == generation) {
            FullGameSyncResult(success = true)
        } else {
            FullGameSyncResult(
                success = false,
                error = syncError ?: IllegalStateException("game_thread_operation_failed")
            )
        }
    }

    override fun refreshLobbyServerInfo(): Boolean {
        val room = GameEngine.data.room
        if (room.isStartGame || room.isLobbyPlayerSyncBlocked()) {
            return false
        }
        var refreshed = false
        val operationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
            if (!room.isStartGame && !room.isLobbyPlayerSyncBlocked()) {
                GameEngine.netEngine.L()
                refreshed = true
            }
        }
        return operationSucceeded && refreshed
    }

    override fun canSyncLobbyPlayers(): Boolean {
        return isLobbyPlayerSyncReady()
    }

    override fun syncLobbyPlayers(): LobbyPlayerSyncResult {
        return syncLobbyPlayers(allowDuringBattleRoomRecovery = false)
    }

    override fun refreshLobbyStateAfterBattleRoomReturn(): LobbyPlayerSyncResult {
        return syncLobbyPlayers(allowDuringBattleRoomRecovery = true)
    }

    override fun repairLobbyTeamsAfterBattleRoomReturn(restoredTeams: Map<String, Int>): LobbyTeamRepairResult {
        if (restoredTeams.isEmpty()) {
            return LobbyTeamRepairResult(success = true)
        }
        if (!engineSyncInProgress.compareAndSet(false, true)) {
            return LobbyTeamRepairResult(
                success = false,
                error = IllegalStateException(LOBBY_SYNC_BUSY)
            )
        }

        var repaired = 0
        val missingSlots = mutableListOf<Int>()
        var syncError: Throwable? = null
        val operationSucceeded: Boolean
        try {
            operationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
                val room = GameEngine.data.room
                if (room.isStartGame) {
                    syncError = IllegalStateException(LOBBY_REFRESH_REQUIRES_LOBBY)
                    return@suspendMainThreadOperations
                }
                if (room.isLobbyPlayerSyncBlockedForBattleRoomRecovery()) {
                    syncError = IllegalStateException(LOBBY_SYNC_BLOCKED)
                    return@suspendMainThreadOperations
                }
                synchronized(GameEngine.data.gameLinkServerData.teamOperationsSyncObject) {
                    room.playerManage.playerAll.eachAll { player ->
                        val connectId = runCatching { player.connectHexID.trim().uppercase() }.getOrDefault("")
                        val playerKey = connectId.takeIf { it.isNotBlank() } ?: "slot:${player.index}"
                        val desiredTeam = restoredTeams[playerKey] ?: return@eachAll
                        val connectedHuman = room.playerManage.isConnectedHumanPlayer(player)
                        if (!BattleRoomLobbyRecoveryPolicy.requiresEnginePlayer(player.isAi, connectedHuman)) {
                            return@eachAll
                        }
                        if (player.team != desiredTeam) {
                            player.team = desiredTeam
                        }
                        val enginePlayer = n.k(player.index)
                        if (enginePlayer == null) {
                            missingSlots += player.index
                            return@eachAll
                        }
                        if (enginePlayer.r != desiredTeam) {
                            enginePlayer.r = desiredTeam
                            repaired++
                        }
                    }
                }
            }
        } finally {
            engineSyncInProgress.set(false)
        }

        if (!operationSucceeded) {
            return LobbyTeamRepairResult(
                success = false,
                repaired = repaired,
                missing = missingSlots.size,
                error = syncError ?: IllegalStateException("game_thread_operation_failed")
            )
        }
        if (syncError != null) {
            return LobbyTeamRepairResult(
                success = false,
                repaired = repaired,
                missing = missingSlots.size,
                error = syncError
            )
        }
        if (missingSlots.isNotEmpty()) {
            return LobbyTeamRepairResult(
                success = false,
                repaired = repaired,
                missing = missingSlots.size,
                error = IllegalStateException(
                    "battle_room_engine_player_missing:${missingSlots.take(8).joinToString(",")}"
                )
            )
        }
        return LobbyTeamRepairResult(success = true, repaired = repaired)
    }

    private fun syncLobbyPlayers(allowDuringBattleRoomRecovery: Boolean): LobbyPlayerSyncResult {
        if (!engineSyncInProgress.compareAndSet(false, true)) {
            return LobbyPlayerSyncResult(false, IllegalStateException(LOBBY_SYNC_BUSY))
        }
        var syncError: Throwable? = null
        val operationSucceeded: Boolean
        try {
            operationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
                syncError = refreshLobbyStateOnGameThread(allowDuringBattleRoomRecovery)
            }
        } finally {
            engineSyncInProgress.set(false)
        }
        return if (operationSucceeded && syncError == null) {
            LobbyPlayerSyncResult(success = true)
        } else {
            LobbyPlayerSyncResult(
                success = false,
                error = syncError ?: IllegalStateException("game_thread_operation_failed")
            )
        }
    }

    private fun refreshLobbyStateOnGameThread(allowDuringBattleRoomRecovery: Boolean): Throwable? {
        val room = GameEngine.data.room
        if (room.isStartGame) {
            return IllegalStateException(LOBBY_REFRESH_REQUIRES_LOBBY)
        }
        val syncBlocked = if (allowDuringBattleRoomRecovery) {
            room.isLobbyPlayerSyncBlockedForBattleRoomRecovery()
        } else {
            room.isLobbyPlayerSyncBlocked()
        }
        if (syncBlocked) {
            return IllegalStateException(LOBBY_SYNC_BLOCKED)
        }
        if (!isLobbyPlayerSyncReady(allowDuringBattleRoomRecovery)) {
            return IllegalStateException(LOBBY_NET_ENGINE_NOT_READY)
        }
        return try {
            GameEngine.netEngine.e(null as c?)
            GameEngine.netEngine.L()
            null
        } catch (e: Exception) {
            Log.error("[Headless] Lightweight lobby refresh failed", e)
            e
        }
    }

    private fun fullGameSyncOnGameThread(): Throwable? {
        if (!isFullGameSyncReady()) {
            return IllegalStateException(FULL_GAME_SYNC_REQUIRES_STARTED_ROOM)
        }
        return try {
            val engine = GameEngine.gameEngine
            synchronized(engine) {
                val mapEngine = engine.bL
                if (mapEngine == null || !mapEngine.W || engine.bX == null || engine.bX.ay == null) {
                    mapEngineNotReadyError()
                } else {
                    engine.bX.a(false, false, true)
                    null
                }
            }
        } catch (e: Exception) {
            if (isTransientMapEngineNotReady(e)) {
                return mapEngineNotReadyError(e)
            }
            if (isTransientFullGameSerializationFailure(e)) {
                return IllegalStateException("full_game_sync_transient", e)
            }
            Log.error("[Headless] Full game SYNC failed", e)
            e
        }
    }

    override fun pauseGame(pause: Boolean) { tryPauseGame(pause) }

    override fun tryPauseGame(pause: Boolean): Boolean {
        val generation = GameEngine.data.room.lobbyLifecycleGeneration()
        var applied = false
        val dispatched = GameEngine.data.gameFunction.suspendMainThreadOperations {
            if (GameEngine.data.room.isStartGame && GameEngine.data.room.lobbyLifecycleGeneration() == generation) {
                GameEngine.netEngine.e(pause)
                applied = true
            }
        }
        return dispatched && applied
    }

    override fun saveGame() { trySaveGame() }

    override fun trySaveGame(): Boolean {
        var applied = false
        val generation = GameEngine.data.room.lobbyLifecycleGeneration()
        val operationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
            if (!GameEngine.data.room.isStartGame || GameEngine.data.room.lobbyLifecycleGeneration() != generation) return@suspendMainThreadOperations
            val name = getFileNameNoSuffix(GameEngine.data.room.replayFileName)
            val target = GameEngine.gameEngine.ca.a("$name.rwsave", false)
            val previousModified = target.lastModified()
            val previousLength = target.length()
            GameEngine.gameEngine.ca.b(name, false)
            // Native save returns void and handles some I/O errors internally.
            applied = target.isFile && target.length() > 0L &&
                (target.lastModified() != previousModified || target.length() != previousLength)
        }
        if (!operationSucceeded) {
            Log.warn("[Headless] Failed to save the game on the game thread")
            return false
        }
        if (applied) Log.clog("Save: ${getFileNameNoSuffix(GameEngine.data.room.replayFileName)}.rwsave")
        return applied
    }

    override fun battleRoom(
        time: Int,
        allowDuringSettlement: Boolean,
        onCompleted: () -> Unit,
        onFailed: (Throwable) -> Unit
    ): Boolean {
        val countdownSeconds = BattleRoomTransitionPolicy.normalizeCountdownSeconds(time)
        var transitionScheduled = false
        val mainThreadOperationSucceeded = GameEngine.data.gameFunction.suspendMainThreadOperations {
            transitionScheduled = GameEngine.data.room.battleRoom(
                countdownSeconds = countdownSeconds,
                allowDuringSettlement = allowDuringSettlement,
                transitionAction = {
                    val engineState = readEngineBattleRoomState()
                    check(BattleRoomTransitionPolicy.canSchedule(
                        engineGameStarted = engineState.gameStarted,
                        engineCountdownPending = engineState.countdownPending,
                        engineCommitPending = engineState.commitPending
                    )) { "engine_battle_room_return_already_pending" }
                    GameEngine.netEngine.d(countdownSeconds.toFloat())
                },
                completionProbe = ::engineBattleRoomReturnComplete,
                onCompleted = onCompleted,
                onFailed = onFailed
            )
        }
        if (!mainThreadOperationSucceeded && transitionScheduled) {
            GameEngine.data.room.cancelBattleRoomTransition("battle_room_schedule_game_loop_resume_failed")
            throw IllegalStateException("battle_room_schedule_game_loop_resume_failed")
        }
        return mainThreadOperationSucceeded && transitionScheduled
    }

    private fun engineBattleRoomReturnComplete(): Boolean {
        val engineState = readEngineBattleRoomState()
        return BattleRoomTransitionPolicy.engineReturnComplete(
            engineGameStarted = engineState.gameStarted,
            engineCountdownPending = engineState.countdownPending,
            engineCommitPending = engineState.commitPending
        )
    }

    private fun readEngineBattleRoomState(): EngineBattleRoomState {
        val netEngine = GameEngine.netEngine
        val netEngineClass = netEngine.javaClass
        val gameStarted = netEngineClass.findField("aW", Boolean::class.javaPrimitiveType)
            ?.getBoolean(netEngine)
            ?: error("engine_game_started_field_unavailable")
        val countdownPending = netEngineClass.findField("aZ", Boolean::class.javaPrimitiveType)
            ?.getBoolean(netEngine)
            ?: error("engine_battle_room_countdown_field_unavailable")
        val commitPending = netEngineClass.findField("aY", Boolean::class.javaPrimitiveType)
            ?.getBoolean(netEngine)
            ?: error("engine_battle_room_commit_field_unavailable")
        return EngineBattleRoomState(
            gameStarted = gameStarted,
            countdownPending = countdownPending,
            commitPending = commitPending
        )
    }

    private data class EngineBattleRoomState(
        val gameStarted: Boolean,
        val countdownPending: Boolean,
        val commitPending: Boolean
    )


    override fun clean() {
        val rwHps = GameEngine.iRwHps ?: return
        if (rwHps.netType != IRwHps.NetType.ServerProtocol) {
            return
        }
        GameEngine.gameEngine.bX.b("exited")
        GameEngine.root.showMainMenu()
        Log.error("[D-29] Interrupted while cleaning game state")
    }
}
