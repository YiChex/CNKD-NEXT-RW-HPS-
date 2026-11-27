/*
 *
 *  * Copyright 2020-2024 RW-HPS Team and contributors.
 *  *
 *  * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *  *
 *  * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 *  
 */

package net.rwhps.server.game.room

import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.core.thread.Threads.closeTimeTask
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.temp.ServerCacheFlag
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.event.game.ServerGameOverEvent
import net.rwhps.server.game.event.game.ServerGameOverEvent.GameOverData
import net.rwhps.server.game.headless.core.link.BattleRoomLobbyRecoveryLink
import net.rwhps.server.game.headless.core.link.LobbyPlayerSyncResult
import net.rwhps.server.game.headless.core.link.LobbyTeamRepairResult
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.manage.CallManage
import net.rwhps.server.game.manage.PlayerManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.internal.headless.support.BattleRoomLobbyRecoveryPolicy
import net.rwhps.server.plugin.internal.headless.support.BattleRoomTransitionPolicy
import net.rwhps.server.plugin.internal.headless.support.GameOverSettlePolicy
import net.rwhps.server.plugin.internal.headless.support.StartModeConsoleOutputSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeDefeatedPlayerKickSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurvivalSupport
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.Time
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.log.Log
import java.io.EOFException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * @author Dr (dr@der.kim)
 */
class ServerRoom(
    private val gameModule: AbstractGameModule
) {
    lateinit var roomID: String

    /**
     * 服务器客户端的命令系统
     *
     * 为多端的准备
     */
    val clientHandler = CommandHandler("/")

    val playerManage = PlayerManage(gameModule)
    val call = CallManage(gameModule)

    @Volatile var isStartGame = false

    // Start Time
    var startTime = 0
        private set
    /** End Time */
    var endTime = 0
        private set

    val isAfk get() = Data.configServer.isAfk
    val muteAll get() = Data.configServer.muteAll

    var isMixPlayer = false

    val maps = GameMaps()
    var replayFileName = ""

    // FLAG
    var flagData = ServerCacheFlag()
    @Volatile private var forcedReturn = false
    @Volatile private var forcedReturnSinceMillis = 0L
    private var battleRoomTransitionGeneration = 0L
    private var lobbyLifecycleGeneration = 0L
    private var battleRoomTransition: BattleRoomTransitionState? = null
    private var battleRoomLobbyRecovery: BattleRoomLobbyRecoveryState? = null
    @Volatile private var battleRoomTransitionCommitBlocking = false
    @Volatile private var lobbyPlayerSyncNotBeforeMillis = 0L
    private var lastBattleRoomLobbyRecoveryState = "idle"
    private var lastBattleRoomLobbyRecoveryError = "none"
    private var lastBattleRoomLobbyRecoveryAttempts = 0
    private var lastBattleRoomLobbyRecoveryPacketEpoch = 0L
    private var lastBattleRoomLobbyRecoveryCompletedAtMillis = 0L
    internal var gameOverData: GameOverData? = null
    internal var playerData: Seq<Map<String, Any>>? = null
    private var preGameLobbyTeams: Map<String, Int> = emptyMap()
    private var battleRoomRestoredLobbyTeams: Map<String, Int> = emptyMap()

    private data class BattleRoomTransitionState(
        val generation: Long,
        val startedAtMillis: Long,
        val allowDuringSettlement: Boolean,
        val completionProbe: () -> Boolean,
        val onCompleted: () -> Unit,
        val onFailed: (Throwable) -> Unit,
        var returnPacketObserved: Boolean = false,
        var commitScheduled: Boolean = false,
        var commitInProgress: Boolean = false
    )

    private data class BattleRoomLobbyRecoveryState(
        val transitionGeneration: Long,
        val lobbyGeneration: Long,
        val allowDuringSettlement: Boolean,
        val startedAtMillis: Long,
        val restoredTeams: Map<String, Int>,
        val onCompleted: () -> Unit,
        val onFailed: (Throwable) -> Unit,
        var phase: BattleRoomLobbyRecoveryPolicy.Phase = BattleRoomLobbyRecoveryPolicy.Phase.PENDING_INITIAL_REFRESH,
        var attempts: Int = 0,
        var broadcastEpoch: Long = 0L,
        var engineTeamsRepaired: Int = 0,
        var engineTeamsMissing: Int = 0,
        var refreshInProgress: Boolean = false,
        var clientGraceUntilMillis: Long = 0L,
        var lastError: String = "none"
    )

    internal fun roomStartGame(): Boolean {
        if (StartModeGameOverReturnSupport.recoverStaleLifecycleIfNeeded(this, "room_start")) {
            return false
        }
        var startError: Throwable? = null
        val started = synchronized(this) {
            if (isStartGame || forcedReturn || battleRoomTransition != null || battleRoomLobbyRecovery != null ||
                StartModeGameOverReturnSupport.isPlaying() || StartModeGameOverReturnSupport.isSettling()
            ) {
                return@synchronized false
            }

            try {
                if (preGameLobbyTeams.isEmpty()) {
                    capturePreGameLobbyState()
                }
                forcedReturn = false
                forcedReturnSinceMillis = 0L
                closeBattleRoomTransitionTasks()
                flagData = ServerCacheFlag()
                closeTimeTask(CallTimeTask.GameOverTask)
                isStartGame = true
                StartModeGameOverReturnSupport.onGameStart()
                StartModeDefeatedPlayerKickSupport.onGameStart()
                startTime = Time.concurrentSecond()
                endTime = Time.concurrentSecond() + Data.configServer.maxGameIngTime

                Threads.pause(CallTimeTask.CallTeamTask)

                playerManage.playerAll.eachAll {
                    it.updateDate()
                }

                net.rwhps.server.analytics.StatisticsBridge.prepare(gameModule)

                StartModeSurvivalSupport.onGameStart(this)

                call.startCheckThread()
                true
            } catch (error: Throwable) {
                startError = error
                false
            }
        }
        startError?.let { error ->
            Log.error("[$roomID] Room start initialization failed; requesting protective reset", error)
            gr()
        }
        return started
    }

    /** Capture the lobby teams before the engine can mark defeated players as spectators. */
    internal fun capturePreGameLobbyState() {
        synchronized(this) {
            val snapshot = LinkedHashMap<String, Int>()
            playerManage.playerAll.eachAll { player ->
                snapshot[playerStateKey(player)] = player.team
            }
            preGameLobbyTeams = snapshot
        }
    }
    

    internal fun battleRoom(
        countdownSeconds: Int,
        allowDuringSettlement: Boolean = false,
        transitionAction: () -> Unit,
        completionProbe: () -> Boolean,
        onCompleted: () -> Unit = {},
        onFailed: (Throwable) -> Unit = {}
    ): Boolean {
        val normalizedCountdown = BattleRoomTransitionPolicy.normalizeCountdownSeconds(countdownSeconds)
        var setupError: Throwable? = null
        val accepted = synchronized(this) {
            if (!GameOverSettlePolicy.canEnterBattleRoom(
                    isSettling = StartModeGameOverReturnSupport.isSettling(),
                    resetInProgress = forcedReturn,
                    allowDuringSettlement = allowDuringSettlement
                )
            ) {
                StartModeConsoleOutputSupport.printThrottled(
                    StartModeConsoleOutputSupport.Category.LIFECYCLE_RECOVERY,
                    "battle_room_return_during_settlement",
                    "[$roomID] Battle-room return ignored because post-game settlement is in progress " +
                            "resetInProgress=$forcedReturn, resetAgeMs=${gameOverResetAgeMillis()}",
                    minIntervalMillis = 60_000L
                )
                return@synchronized false
            }
            if (!isStartGame || battleRoomTransition != null) {
                return@synchronized false
            }

            val generation = ++battleRoomTransitionGeneration
            val transition = BattleRoomTransitionState(
                generation = generation,
                startedAtMillis = System.currentTimeMillis(),
                allowDuringSettlement = allowDuringSettlement,
                completionProbe = completionProbe,
                onCompleted = onCompleted,
                onFailed = onFailed
            )
            battleRoomTransitionCommitBlocking = false
            battleRoomTransition = transition
            try {
                closeBattleRoomTransitionTasks()
                Threads.newCountdown(
                    CallTimeTask.BattleRoomTransitionWatchdogTask,
                    BattleRoomTransitionPolicy.watchdogDelaySeconds(normalizedCountdown),
                    TimeUnit.SECONDS
                ) {
                    completeBattleRoomTransition(
                        generation,
                        failIfEnginePending = true
                    )
                }
                transitionAction()
                net.rwhps.server.analytics.StatisticsBridge.finish(gameModule, "MANUAL_BATTLEROOM_RETURN")
                true
            } catch (error: Throwable) {
                if (battleRoomTransition?.generation == generation) {
                    battleRoomTransition = null
                }
                closeBattleRoomTransitionTasks()
                setupError = error
                false
            }
        }
        setupError?.let { error ->
            Log.error("[$roomID] Battle-room transition setup failed", error)
        }
        return accepted
    }

    internal fun onEngineBattleRoomReturnPacket() {
        val transition = synchronized(this) {
            val active = battleRoomTransition ?: return
            active.returnPacketObserved = true
            // The engine has committed to leaving the match. Block old game
            // traffic immediately instead of waiting for the delayed cleanup.
            battleRoomTransitionCommitBlocking = true
            if (!BattleRoomTransitionPolicy.shouldScheduleCommit(
                    commitScheduled = active.commitScheduled,
                    commitInProgress = active.commitInProgress
                )
            ) return
            active.commitScheduled = true
            active
        }

        val commitScheduleResult = runCatching {
            Threads.newCountdown(
                CallTimeTask.BattleRoomTransitionCommitTask,
                BattleRoomTransitionPolicy.ENGINE_COMMIT_DELAY_MILLIS,
                TimeUnit.MILLISECONDS
            ) {
                completeBattleRoomTransition(
                    transition.generation,
                    failIfEnginePending = false
                )
            }
        }
        commitScheduleResult.onFailure { error ->
            synchronized(this) {
                battleRoomTransition
                    ?.takeIf { it.generation == transition.generation }
                    ?.commitScheduled = false
            }
            Log.warn("[$roomID] Failed to schedule battle-room packet commit; watchdog remains active", error)
        }
    }

    internal fun isBattleRoomTransitionInProgress(): Boolean = synchronized(this) {
        battleRoomTransition != null
    }

    internal fun battleRoomTransitionAgeMillis(): Long = synchronized(this) {
        val startedAt = battleRoomTransition?.startedAtMillis ?: return@synchronized -1L
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    }

    internal fun isBattleRoomTransitionCommitBlocking(): Boolean = battleRoomTransitionCommitBlocking

    /** Keep engine TEAM_LIST serialization closed during and immediately after a return. */
    internal fun isLobbyPlayerSyncBlocked(): Boolean = synchronized(this) {
        !BattleRoomTransitionPolicy.canSyncLobbyPlayers(
            transitionInProgress = battleRoomTransition != null,
            commitBlocking = battleRoomTransitionCommitBlocking,
            syncNotBeforeMillis = lobbyPlayerSyncNotBeforeMillis,
            nowMillis = System.currentTimeMillis()
        ) || battleRoomLobbyRecovery != null
    }

    /** The recovery worker bypasses only its own barrier, never an active engine transition. */
    internal fun isLobbyPlayerSyncBlockedForBattleRoomRecovery(): Boolean = synchronized(this) {
        battleRoomTransition != null || battleRoomTransitionCommitBlocking
    }

    internal fun lobbyPlayerSyncCooldownRemainingMillis(): Long = synchronized(this) {
        (lobbyPlayerSyncNotBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    internal fun isBattleRoomLobbyRecoveryInProgress(): Boolean = synchronized(this) {
        battleRoomLobbyRecovery != null
    }

    /** Changes for every recovery-owned native lobby broadcast. */
    internal fun battleRoomLobbyRecoveryPacketEpoch(): Long = synchronized(this) {
        battleRoomLobbyRecovery?.broadcastEpoch ?: lastBattleRoomLobbyRecoveryPacketEpoch
    }

    internal fun buildBattleRoomLobbyRecoveryDiagnostics(): List<String> = synchronized(this) {
        val recovery = battleRoomLobbyRecovery
        val now = System.currentTimeMillis()
        listOf(
            "battleRoom.recovery.state=${recovery?.phase?.diagnosticValue ?: lastBattleRoomLobbyRecoveryState}",
            "battleRoom.recovery.transitionGeneration=${recovery?.transitionGeneration ?: -1}",
            "battleRoom.recovery.lobbyGeneration=${recovery?.lobbyGeneration ?: lobbyLifecycleGeneration}",
            "battleRoom.recovery.attempts=${recovery?.attempts ?: lastBattleRoomLobbyRecoveryAttempts}",
            "battleRoom.recovery.packetEpoch=${recovery?.broadcastEpoch ?: lastBattleRoomLobbyRecoveryPacketEpoch}",
            "battleRoom.recovery.restoredTeams=${recovery?.restoredTeams?.size ?: 0}",
            "battleRoom.recovery.engineTeamsRepaired=${recovery?.engineTeamsRepaired ?: 0}",
            "battleRoom.recovery.engineTeamsMissing=${recovery?.engineTeamsMissing ?: 0}",
            "battleRoom.recovery.refreshInProgress=${recovery?.refreshInProgress ?: false}",
            "battleRoom.recovery.clientGraceRemainingMs=${recovery?.let { BattleRoomLobbyRecoveryPolicy.remainingGraceMillis(now, it.clientGraceUntilMillis) } ?: 0L}",
            "battleRoom.recovery.lastError=${recovery?.lastError ?: lastBattleRoomLobbyRecoveryError}",
            "battleRoom.recovery.lastCompletedAgeMs=${elapsedMillis(lastBattleRoomLobbyRecoveryCompletedAtMillis, now)}"
        )
    }

    /** Changes whenever a completed game returns to a fresh lobby lifecycle. */
    internal fun lobbyLifecycleGeneration(): Long = synchronized(this) { lobbyLifecycleGeneration }

    internal fun battleRoomTransitionPhase(): String = synchronized(this) {
        val transition = battleRoomTransition ?: return@synchronized "idle"
        when {
            transition.commitInProgress -> "committing"
            transition.commitScheduled -> "commit_scheduled"
            transition.returnPacketObserved -> "packet_observed_watchdog_pending"
            else -> "countdown"
        }
    }

    private fun completeBattleRoomTransition(
        generation: Long,
        failIfEnginePending: Boolean
    ) {
        var completedTransition: BattleRoomTransitionState? = null
        var failedTransition: BattleRoomTransitionState? = null
        var completionError: Throwable? = null
        var operationEntered = false
        var transitionMatched = false
        var transitionTerminal = false
        var transitionToCommit: BattleRoomTransitionState? = null

        val mainThreadOperationSucceeded = gameModule.gameFunction.suspendMainThreadOperations {
            operationEntered = true
            synchronized(this) {
                val transition = battleRoomTransition
                    ?.takeIf { it.generation == generation }
                    ?: return@synchronized
                transitionMatched = true
                val probeResult = runCatching { transition.completionProbe() }
                val engineCompleted = probeResult.getOrDefault(false)
                val returnCommitted = BattleRoomTransitionPolicy.hasAuthoritativeReturnEvidence(
                    engineReturnComplete = engineCompleted,
                    returnPacketObserved = transition.returnPacketObserved
                )
                if (!returnCommitted) {
                    val probeError = probeResult.exceptionOrNull()
                    if (probeError == null && !failIfEnginePending) {
                        transition.commitScheduled = false
                        return@synchronized
                    }
                    transitionTerminal = true
                    failedTransition = transition
                    completionError = probeError
                        ?: IllegalStateException("engine_battle_room_return_not_completed")
                    battleRoomTransitionCommitBlocking = false
                    lobbyPlayerSyncNotBeforeMillis = 0L
                    battleRoomTransition = null
                    return@synchronized
                }

                if (transition.commitInProgress) return@synchronized
                transition.commitInProgress = true
                battleRoomTransitionCommitBlocking = true
                transitionToCommit = transition
            }

            val claimedTransition = transitionToCommit ?: return@suspendMainThreadOperations
            val cleanupError = runCatching {
                StartModeSurvivalSupport.onBattleRoomReturn(this)
                    cleanData(restoreLobbyTeams = true)
                Threads.unPause(CallTimeTask.CallTeamTask)
            }.exceptionOrNull()

            synchronized(this) {
                val active = battleRoomTransition
                    ?.takeIf { it === claimedTransition && it.generation == generation }
                    ?: return@synchronized
                transitionTerminal = true
                active.commitInProgress = false
                battleRoomTransitionCommitBlocking = false
                if (cleanupError != null) {
                    battleRoomTransition = null
                    failedTransition = active
                    completionError = cleanupError
                    return@synchronized
                }

                try {
                    lobbyPlayerSyncNotBeforeMillis =
                        System.currentTimeMillis() + BattleRoomLobbyRecoveryPolicy.INITIAL_SYNC_DELAY_MILLIS
                    battleRoomTransition = null
                    completedTransition = active
                } catch (error: Throwable) {
                    battleRoomTransition = null
                    failedTransition = active
                    completionError = error
                }
            }
        }

        if (!operationEntered && !mainThreadOperationSucceeded) {
            synchronized(this) {
                val transition = battleRoomTransition
                    ?.takeIf { it.generation == generation }
                    ?: return@synchronized
                transitionMatched = true
                transitionTerminal = true
                battleRoomTransitionCommitBlocking = false
                lobbyPlayerSyncNotBeforeMillis = 0L
                battleRoomTransition = null
                failedTransition = transition
                completionError = IllegalStateException("battle_room_main_thread_operation_failed")
            }
        } else if (completedTransition != null && !mainThreadOperationSucceeded) {
            val transition = completedTransition
            completedTransition = null
            failedTransition = transition
            completionError = IllegalStateException("battle_room_game_loop_resume_failed")
            Log.error("[$roomID] Battle-room room state committed but game-loop resume failed")
        }

        if (!transitionMatched || !transitionTerminal) return
        closeBattleRoomTransitionTasks()
        completedTransition?.let { transition ->
            beginBattleRoomLobbyRecovery(transition)
        }
        failedTransition?.let { transition ->
            val error = completionError ?: IllegalStateException("battle_room_transition_failed")
            Log.error("[$roomID] Battle-room transition did not complete", error)
            runCatching { transition.onFailed(error) }.onFailure { callbackError ->
                Log.error("[$roomID] Battle-room failure callback failed", callbackError)
            }
        }
    }

    internal fun cancelBattleRoomTransition(reason: String) {
        val cancelled = synchronized(this) {
            val active = battleRoomTransition ?: return@synchronized false
            battleRoomTransitionCommitBlocking = false
            lobbyPlayerSyncNotBeforeMillis = 0L
            battleRoomTransition = null
            battleRoomTransitionGeneration++
            Log.clog("[$roomID] Cancelled pending battle-room transition: reason=$reason, generation=${active.generation}")
            true
        }
        if (cancelled) {
            closeBattleRoomTransitionTasks()
        }
    }

    private fun beginBattleRoomLobbyRecovery(transition: BattleRoomTransitionState) {
        val recovery = synchronized(this) {
            if (battleRoomLobbyRecovery != null) {
                return
            }
            BattleRoomLobbyRecoveryState(
                transitionGeneration = transition.generation,
                lobbyGeneration = lobbyLifecycleGeneration,
                allowDuringSettlement = transition.allowDuringSettlement,
                startedAtMillis = System.currentTimeMillis(),
                restoredTeams = battleRoomRestoredLobbyTeams,
                onCompleted = transition.onCompleted,
                onFailed = transition.onFailed
            ).also {
                battleRoomLobbyRecovery = it
                lastBattleRoomLobbyRecoveryState = it.phase.diagnosticValue
                lastBattleRoomLobbyRecoveryError = "none"
                lastBattleRoomLobbyRecoveryAttempts = 0
            }
        }
        Log.clog(
            "[$roomID] Battle-room engine return committed; rebuilding native lobby state " +
                    "generation=${recovery.transitionGeneration}, lobbyGeneration=${recovery.lobbyGeneration}"
        )
        if (!scheduleBattleRoomLobbyRecovery(
                recovery.transitionGeneration,
                BattleRoomLobbyRecoveryPolicy.retryDelayMillis(0)
            )
        ) {
            failBattleRoomLobbyRecovery(
                recovery.transitionGeneration,
                IllegalStateException("battle_room_lobby_recovery_schedule_failed")
            )
        }
    }

    private fun scheduleBattleRoomLobbyRecovery(generation: Long, delayMillis: Long): Boolean {
        return runCatching {
            Threads.closeTimeTask(CallTimeTask.BattleRoomLobbyRecoveryTask)
            Threads.newCountdown(
                CallTimeTask.BattleRoomLobbyRecoveryTask,
                delayMillis.coerceAtLeast(0L).toInt(),
                TimeUnit.MILLISECONDS
            ) {
                runBattleRoomLobbyRecovery(generation)
            }
            true
        }.onFailure { error ->
            Log.warn("[$roomID] Failed to schedule battle-room lobby recovery", error)
        }.getOrDefault(false)
    }

    private fun runBattleRoomLobbyRecovery(generation: Long) {
        var remainingClientGraceMillis: Long? = null
        val readyRecovery = synchronized(this) {
            val active = battleRoomLobbyRecovery
                ?.takeIf { it.transitionGeneration == generation }
                ?: return
            if (active.phase != BattleRoomLobbyRecoveryPolicy.Phase.CLIENT_APPLY_GRACE &&
                active.phase != BattleRoomLobbyRecoveryPolicy.Phase.FINAL_CLIENT_APPLY_GRACE
            ) {
                null
            } else {
                val remaining = BattleRoomLobbyRecoveryPolicy.remainingGraceMillis(
                    System.currentTimeMillis(),
                    active.clientGraceUntilMillis
                )
                if (remaining > 0L) {
                    remainingClientGraceMillis = remaining
                    return@synchronized null
                }
                if (active.phase == BattleRoomLobbyRecoveryPolicy.Phase.CLIENT_APPLY_GRACE) {
                    active.phase = BattleRoomLobbyRecoveryPolicy.nextPhaseAfterGrace(active.phase)
                        ?: error("battle_room_lobby_recovery_missing_final_sync")
                    lastBattleRoomLobbyRecoveryState = active.phase.diagnosticValue
                    null
                } else {
                    battleRoomLobbyRecovery = null
                    lastBattleRoomLobbyRecoveryState = "ready"
                    lastBattleRoomLobbyRecoveryError = "none"
                    lastBattleRoomLobbyRecoveryAttempts = active.attempts
                    lastBattleRoomLobbyRecoveryPacketEpoch = active.broadcastEpoch
                    lastBattleRoomLobbyRecoveryCompletedAtMillis = System.currentTimeMillis()
                    active
                }
            }
        }
        if (readyRecovery != null) {
            completeBattleRoomLobbyRecovery(readyRecovery)
            return
        }
        remainingClientGraceMillis?.let { delayMillis ->
            if (!scheduleBattleRoomLobbyRecovery(generation, delayMillis)) {
                failBattleRoomLobbyRecovery(
                    generation,
                    IllegalStateException("battle_room_lobby_recovery_grace_reschedule_failed")
                )
            }
            return
        }

        val recovery = synchronized(this) {
            val active = battleRoomLobbyRecovery
                ?.takeIf { it.transitionGeneration == generation }
                ?: return
            if (!BattleRoomLobbyRecoveryPolicy.isRefreshPhase(active.phase) || active.refreshInProgress
            ) {
                return
            }
            active.refreshInProgress = true
            active.attempts++
            active.broadcastEpoch++
            lastBattleRoomLobbyRecoveryState = active.phase.diagnosticValue
            lastBattleRoomLobbyRecoveryAttempts = active.attempts
            active
        }

        val recoveryLink = gameModule.gameLinkFunction as? BattleRoomLobbyRecoveryLink
        val engineTeamRepair = if (recovery.restoredTeams.isEmpty()) {
            LobbyTeamRepairResult(success = true)
        } else {
            runCatching {
                recoveryLink?.repairLobbyTeamsAfterBattleRoomReturn(recovery.restoredTeams)
                    ?: LobbyTeamRepairResult(
                        success = false,
                        error = IllegalStateException("battle_room_recovery_link_unavailable")
                    )
            }.getOrElse { error ->
                LobbyTeamRepairResult(success = false, error = error)
            }
        }
        val lobbyRefresh = if (!engineTeamRepair.success) {
            LobbyPlayerSyncResult(false, engineTeamRepair.error)
        } else {
            runCatching {
                recoveryLink?.refreshLobbyStateAfterBattleRoomReturn()
                    ?: LobbyPlayerSyncResult(false, IllegalStateException("battle_room_recovery_link_unavailable"))
            }.getOrElse { error ->
                LobbyPlayerSyncResult(false, error)
            }
        }

        val refreshSucceeded = lobbyRefresh.success
        val refreshError = lobbyRefresh.error
        var retryDelayMillis: Long? = null
        var failedError: Throwable? = null
        synchronized(this) {
            val active = battleRoomLobbyRecovery
                ?.takeIf { it === recovery && it.transitionGeneration == generation }
                ?: return
            active.refreshInProgress = false
            active.engineTeamsRepaired += engineTeamRepair.repaired
            active.engineTeamsMissing = engineTeamRepair.missing
            if (refreshSucceeded) {
                val isFinalRefresh = active.phase == BattleRoomLobbyRecoveryPolicy.Phase.PENDING_FINAL_REFRESH
                active.phase = if (isFinalRefresh) {
                    BattleRoomLobbyRecoveryPolicy.Phase.FINAL_CLIENT_APPLY_GRACE
                } else {
                    BattleRoomLobbyRecoveryPolicy.Phase.CLIENT_APPLY_GRACE
                }
                active.clientGraceUntilMillis = System.currentTimeMillis() +
                        BattleRoomLobbyRecoveryPolicy.clientGraceMillis(active.phase)
                active.lastError = "none"
                lastBattleRoomLobbyRecoveryState = active.phase.diagnosticValue
                lastBattleRoomLobbyRecoveryError = "none"
                retryDelayMillis = BattleRoomLobbyRecoveryPolicy.clientGraceMillis(active.phase)
            } else {
                val error = refreshError ?: IllegalStateException("lobby_state_refresh_failed")
                active.lastError = summarizeBattleRoomLobbyRecoveryError(error)
                lastBattleRoomLobbyRecoveryError = active.lastError
                val canRetry = isTransientBattleRoomLobbyRecoveryError(error) &&
                        BattleRoomLobbyRecoveryPolicy.shouldRetry(active.attempts)
                if (canRetry) {
                    retryDelayMillis = BattleRoomLobbyRecoveryPolicy.retryDelayMillis(active.attempts)
                } else {
                    lastBattleRoomLobbyRecoveryState = "failed"
                    lastBattleRoomLobbyRecoveryAttempts = active.attempts
                    failedError = error
                }
            }
        }

        failedError?.let { error ->
            failBattleRoomLobbyRecovery(generation, error)
            return
        }
        retryDelayMillis?.let { delayMillis ->
            if (!scheduleBattleRoomLobbyRecovery(generation, delayMillis)) {
                failBattleRoomLobbyRecovery(
                    generation,
                    IllegalStateException("battle_room_lobby_recovery_reschedule_failed")
                )
            }
        }
    }

    private fun completeBattleRoomLobbyRecovery(recovery: BattleRoomLobbyRecoveryState) {
        closeBattleRoomLobbyRecoveryTask()
        if (!recovery.allowDuringSettlement) {
            StartModeGameOverReturnSupport.onRoomResetComplete("server_room_battle_room")
        }
        runCatching { recovery.onCompleted() }.onFailure { callbackError ->
            Log.error("[$roomID] Battle-room recovery completion callback failed", callbackError)
        }
        StartModeAutoStartSupport.onBattleRoomLobbyRecoveryCompleted(this)
        Log.clog(
            "[$roomID] Battle-room lobby recovery ready: " +
                    "generation=${recovery.transitionGeneration}, attempts=${recovery.attempts}"
        )
    }

    private fun failBattleRoomLobbyRecovery(generation: Long, error: Throwable) {
        val failedRecovery = synchronized(this) {
            val active = battleRoomLobbyRecovery
                ?.takeIf { it.transitionGeneration == generation }
                ?: return
            battleRoomLobbyRecovery = null
            active.refreshInProgress = false
            active.lastError = summarizeBattleRoomLobbyRecoveryError(error)
            lastBattleRoomLobbyRecoveryState = "failed"
            lastBattleRoomLobbyRecoveryError = active.lastError
            lastBattleRoomLobbyRecoveryAttempts = active.attempts
            lastBattleRoomLobbyRecoveryPacketEpoch = active.broadcastEpoch
            active
        }
        closeBattleRoomLobbyRecoveryTask()
        Log.error(
            "[$roomID] Battle-room lobby recovery failed; delegating post-return recovery policy: " +
                    "generation=${failedRecovery.transitionGeneration}, attempts=${failedRecovery.attempts}",
            error
        )
        runCatching { failedRecovery.onFailed(error) }.onFailure { callbackError ->
            Log.error("[$roomID] Battle-room recovery failure callback failed", callbackError)
        }
    }

    private fun cancelBattleRoomLobbyRecovery(reason: String) {
        val cancelled = synchronized(this) {
            val active = battleRoomLobbyRecovery ?: return@synchronized false
            battleRoomLobbyRecovery = null
            lastBattleRoomLobbyRecoveryState = "cancelled"
            lastBattleRoomLobbyRecoveryError = reason
            lastBattleRoomLobbyRecoveryAttempts = active.attempts
            lastBattleRoomLobbyRecoveryPacketEpoch = active.broadcastEpoch
            true
        }
        if (cancelled) {
            closeBattleRoomLobbyRecoveryTask()
            Log.clog("[$roomID] Cancelled battle-room lobby recovery: reason=$reason")
        }
    }

    private fun closeBattleRoomLobbyRecoveryTask() {
        runCatching { Threads.closeTimeTask(CallTimeTask.BattleRoomLobbyRecoveryTask) }
            .onFailure { error ->
                Log.warn("[$roomID] Failed to close battle-room lobby recovery task", error)
            }
    }

    private fun isTransientBattleRoomLobbyRecoveryError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is EOFException) {
                return true
            }
            if (current is NullPointerException && current.message.orEmpty().contains("bL")) {
                return true
            }
            if (current is IllegalStateException && current.message in setOf(
                    "map_engine_not_ready",
                    "lobby_net_engine_not_ready",
                    "lobby_player_sync_transient",
                    "lobby_player_sync_busy",
                    "battle_room_lobby_sync_blocked"
                )
            ) {
                return true
            }
            if (current is IllegalStateException && current.message.orEmpty().startsWith("battle_room_engine_player_missing:")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun summarizeBattleRoomLobbyRecoveryError(error: Throwable): String {
        val type = error::class.java.simpleName.ifBlank { "Throwable" }
        val message = error.message?.replace(Regex("\\s+"), " ")?.take(160).orEmpty()
        return if (message.isBlank()) type else "$type:$message"
    }

    private fun elapsedMillis(atMillis: Long, nowMillis: Long): Long {
        if (atMillis <= 0L) {
            return -1L
        }
        return (nowMillis - atMillis).coerceAtLeast(0L)
    }

    private fun closeBattleRoomTransitionTasks() {
        runCatching { Threads.closeTimeTask(CallTimeTask.BattleRoomTransitionCommitTask) }
            .onFailure { error ->
                Log.warn("[$roomID] Failed to close battle-room commit task", error)
            }
        runCatching { Threads.closeTimeTask(CallTimeTask.BattleRoomTransitionWatchdogTask) }
            .onFailure { error ->
                Log.warn("[$roomID] Failed to close battle-room watchdog task", error)
            }
    }

    internal fun isGameOverResetInProgress(): Boolean {
        return forcedReturn
    }

    internal fun gameOverResetAgeMillis(): Long {
        if (!forcedReturn || forcedReturnSinceMillis <= 0L) {
            return -1L
        }
        return (System.currentTimeMillis() - forcedReturnSinceMillis).coerceAtLeast(0L)
    }

    internal fun gr() {
        if (!beginGameOverReset()) {
            StartModeConsoleOutputSupport.printThrottled(
                StartModeConsoleOutputSupport.Category.LIFECYCLE_RECOVERY,
                "gameover_reset_already_in_progress",
                "[$roomID] Gameover ignored because reset is already in progress ageMs=${gameOverResetAgeMillis()}",
                minIntervalMillis = 60_000L
            )
            return
        }

        var resetAttempted = false
        var resetSucceeded = false
        var resetError: Throwable? = null
        try {
            cancelBattleRoomLobbyRecovery("server_room_gr")
            cancelBattleRoomTransition("server_room_gr")
            val mainThreadOperationSucceeded = gameModule.gameFunction.suspendMainThreadOperations {
                resetAttempted = true
                try {
                    cleanData(resetForcedReturn = false)
                    closeTimeTask(CallTimeTask.CallTeamTask)

                    Log.clog("[$roomID] Gameover")

                    val rebootSucceeded = gameModule.gameLinkNet.reBootServer {
                        playerManage.cleanPlayerAllData()
                        check(playerManage.verifyResetPlayerCleanupComplete("server_room_gr_reboot_cleanup")) {
                            "reset_player_cleanup_incomplete"
                        }
                    }
                    check(rebootSucceeded) { "headless_server_reboot_failed" }
                    check(playerManage.verifyResetPlayerCleanupComplete("server_room_gr_finally_cleanup")) {
                        "reset_player_final_cleanup_incomplete"
                    }
                    resetSucceeded = true
                } catch (error: Throwable) {
                    resetError = error
                }
            }

            if (!resetAttempted) {
                if (!mainThreadOperationSucceeded) {
                    StartModeGameOverReturnSupport.onRoomResetFailed(
                        this,
                        "server_room_gr_main_thread_guard",
                        IllegalStateException("main_thread_operation_failed_before_reset")
                    )
                }
                return
            }

            if (resetSucceeded && mainThreadOperationSucceeded) {
                StartModeGameOverReturnSupport.onRoomResetComplete("server_room_gr")
                return
            }

            StartModeGameOverReturnSupport.onRoomResetFailed(
                this,
                "server_room_gr",
                resetError ?: IllegalStateException("main_thread_operation_or_reboot_failed")
            )
        } finally {
            finishGameOverReset()
        }
    }

    @Synchronized
    private fun beginGameOverReset(): Boolean {
        if (forcedReturn) {
            return false
        }
        forcedReturn = true
        forcedReturnSinceMillis = System.currentTimeMillis()
        return true
    }

    @Synchronized
    private fun finishGameOverReset() {
        forcedReturn = false
        forcedReturnSinceMillis = 0L
    }

    private fun cleanThread() {
        if (Data.vote != null) {
            Data.vote!!.stopVote()
        }
        closeTimeTask(CallTimeTask.GameOverTask)
        closeTimeTask(CallTimeTask.AutoCheckTask)
    }

    private fun cleanData(
        resetForcedReturn: Boolean = true,
        restoreLobbyTeams: Boolean = false
    ) {
        net.rwhps.server.analytics.StatisticsBridge.finish(gameModule, "ROOM_RESET")
        isStartGame = false
        synchronized(this) {
            lobbyLifecycleGeneration++
        }
        cleanThread()
        Data.vote?.stopVote()
        playerManage.playerAll.eachAll { it.removeData("Ping") }
        StartModeDefeatedPlayerKickSupport.onGameEnd()
        val prunedDisconnectedPlayers = playerManage.pruneDisconnectedPlayersForLobbyTransition("clean_data")
        if (prunedDisconnectedPlayers > 0) {
            Log.clog("[$roomID] Removed $prunedDisconnectedPlayers disconnected player(s) before lobby recovery")
        }

        if (restoreLobbyTeams) {
            restorePreGameLobbyState()
        } else {
            synchronized(this) {
                preGameLobbyTeams = emptyMap()
                battleRoomRestoredLobbyTeams = emptyMap()
            }
        }

        flagData = ServerCacheFlag()

        startTime = 0
        endTime = 0

        if (resetForcedReturn) {
            forcedReturn = false
            forcedReturnSinceMillis = 0L
        }

        // Keep engine-owned lobby defaults deterministic, but do not make the
        // game loop wait for replay I/O or third-party event listeners.
        StartModeGameOptionSupport.applyConfiguredLobbyDefaults(gameModule)
        StartModeAutoStartSupport.onGameOver(this)
        gameModule.eventManage.fire(ServerGameOverEvent(gameModule, gameOverData))
        gameOverData = null

        maps.mapData?.clean()
    }

    private fun restorePreGameLobbyState() {
        val snapshot = synchronized(this) {
            preGameLobbyTeams.also {
                preGameLobbyTeams = emptyMap()
                battleRoomRestoredLobbyTeams = it
            }
        }
        if (snapshot.isEmpty()) {
            return
        }

        var restored = 0
        playerManage.playerAll.eachAll { player ->
            val desiredTeam = snapshot[playerStateKey(player)] ?: return@eachAll
            if (player.team != desiredTeam) {
                player.team = desiredTeam
                restored++
            }
        }
        if (restored > 0) {
            Log.clog("[$roomID] Restored $restored player team(s) from the pre-game lobby state")
        }
    }

    private fun playerStateKey(player: PlayerHess): String {
        val connectId = runCatching { player.connectHexID.trim().uppercase(Locale.ROOT) }.getOrDefault("")
        return if (connectId.isNotBlank()) connectId else "slot:${player.index}"
    }
}
