/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.log.Log
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal object StartModeGameOverReturnSupport {
    const val SETTLE_SECONDS = GameOverSettlePolicy.DEFAULT_COUNTDOWN_SECONDS
    private const val STALE_SETTLING_RECOVERY_GRACE_SECONDS = 20
    private const val RESET_RETRY_DELAY_SECONDS = 5
    private const val MAX_AUTOMATIC_RESET_RETRIES = 1

    /**
     * P0-4: the reset-retry budget must not turn into a permanent room latch.
     *
     * `activeResetRetryExhausted` exists to stop *unbounded* reset retries, but it used to
     * short-circuit `recoverStaleLifecycleIfNeeded()` forever, which is the only self-healing
     * entry point for a room stuck in SETTLING. That left the room dead until an admin ran
     * `gameover` or the process restarted.
     *
     * After this grace period the latch releases exactly one stale-settling recycle and
     * re-arms itself, so recovery is rate-limited rather than either unbounded (storm) or
     * never (deadlock). See [shouldBlockStaleSettlingRecovery] for the pure decision.
     */
    private const val RESET_EXHAUSTED_RECOVERY_GRACE_SECONDS = 60

    private val lifecycleLock = Any()
    private val lifecycleGeneration = AtomicLong(0L)
    private val settlingCount = AtomicLong(0L)
    private val resetCount = AtomicLong(0L)
    private val battleRoomTransitionFailureCount = AtomicLong(0L)
    private val battleRoomFallbackResetCount = AtomicLong(0L)
    private val battleRoomPreservedLobbyFailureCount = AtomicLong(0L)
    private val settleTimerScheduleFailureCount = AtomicLong(0L)
    private val resetFailureCount = AtomicLong(0L)
    private val resetRetryScheduledCount = AtomicLong(0L)
    private val resetRetryExhaustedCount = AtomicLong(0L)
    private val noOnlineHumanGraceStartedCount = AtomicLong(0L)
    private val noOnlineHumanGraceClearedCount = AtomicLong(0L)
    private val noOnlineHumanGraceExpiredCount = AtomicLong(0L)
    private val noOnlineHumanDeferredCount = AtomicLong(0L)
    private val staleLifecycleRecoveryCount = AtomicLong(0L)
    private val resetRetryExhaustedRecoveryCount = AtomicLong(0L)

    @Volatile private var lastSettleReason = "none"
    @Volatile private var lastSettleAtMillis = 0L
    @Volatile private var lastSettleSeconds = SETTLE_SECONDS
    @Volatile private var lastSettleMode = "none"
    @Volatile private var lastResetAtMillis = 0L
    @Volatile private var lastBattleRoomTransitionStatus = "none"
    @Volatile private var lastBattleRoomTransitionError = "none"
    @Volatile private var lastResetFailureReason = "none"
    @Volatile private var lastResetFailureError = "none"
    @Volatile private var lastResetFailureAtMillis = 0L
    @Volatile private var activeSettleForcesServerReset = false
    @Volatile private var activeResetRetryAttempts = 0
    @Volatile private var activeResetRetryExhausted = false
    /** P0-4: when [activeResetRetryExhausted] was armed; 0 means "not armed / unknown". */
    @Volatile private var resetRetryExhaustedAtMillis = 0L
    @Volatile private var lastStaleLifecycleRecoveryReason = "none"
    @Volatile private var lastStaleLifecycleRecoveryAtMillis = 0L
    @Volatile private var staleLifecycleRecoveryInFlight = false
    @Volatile private var noOnlineHumanGraceActive = false
    @Volatile private var noOnlineHumanGraceSeconds = 0
    @Volatile private var lastNoOnlineHumanReason = "none"
    @Volatile private var lastNoOnlineHumanAtMillis = 0L

    /**
     * Simple game-end state machine.
     *
     *   LOBBY -> PLAYING -> SETTLING -> LOBBY
     */
    enum class GameState { LOBBY, PLAYING, SETTLING }

    @Volatile var currentState: GameState = GameState.LOBBY
        private set

    fun onGameStart() {
        synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            currentState = GameState.PLAYING
            activeSettleForcesServerReset = false
            activeResetRetryAttempts = 0
            activeResetRetryExhausted = false
            resetRetryExhaustedAtMillis = 0L
            staleLifecycleRecoveryInFlight = false
        }
        closeResetRetryTask("game_start")
        clearNoOnlineHumanGrace("game_start")
    }

    fun onSettle(module: AbstractGameModule, label: String) {
        val settlePolicy = gameOverSettlePolicy()
        val settleSeconds = settlePolicy.countdownSeconds
        val settleGenerationId = synchronized(lifecycleLock) {
            if (currentState != GameState.PLAYING) return

            currentState = GameState.SETTLING
            lastSettleReason = label
            lastSettleSeconds = settleSeconds
            lastSettleMode = settlePolicy.mode.diagnosticValue
            lastSettleAtMillis = System.currentTimeMillis()
            lastBattleRoomTransitionStatus = if (settlePolicy.mode.usesBattleRoomTransition) "pending" else "not_requested"
            lastBattleRoomTransitionError = "none"
            activeSettleForcesServerReset = !settlePolicy.mode.usesBattleRoomTransition
            activeResetRetryAttempts = 0
            activeResetRetryExhausted = false
            resetRetryExhaustedAtMillis = 0L
            staleLifecycleRecoveryInFlight = false
            lifecycleGeneration.incrementAndGet()
        }
        closeResetRetryTask("settle:$label")
        module.gameFunction.suspendMainThreadOperations {
            net.rwhps.server.analytics.StatisticsBridge.finish(module, label)
        }
        closeSettleTask("settle:$label")
        clearNoOnlineHumanGrace("settle:$label")

        val room = module.room
        settlingCount.incrementAndGet()

        if (room.isBattleRoomTransitionInProgress()) {
            synchronized(lifecycleLock) {
                if (isActiveSettle(settleGenerationId)) {
                    lastSettleMode = "manual_battle_room_return_in_progress"
                    lastBattleRoomTransitionStatus = "manual_pending"
                    activeSettleForcesServerReset = false
                }
            }
            Log.clog(
                "[${room.roomID}] Game over detected while a manual battle-room return is already in progress; " +
                        "the existing transition keeps ownership"
            )
            return
        }

        runCatching {
            room.call.sendSystemMessageLocal(settlePolicy.mode.messageKey, settleSeconds)
        }.onFailure { error ->
            Log.warn("[${room.roomID}] Failed to send game-over settle message key=${settlePolicy.mode.messageKey}", error)
        }

        if (settlePolicy.mode.usesBattleRoomTransition) {
            Log.clog(
                "[${room.roomID}] Game over - automatic battle-room return in ${settleSeconds}s: reason=$label"
            )

            // Battle-room transition starts the client-side countdown animation.
            var transitionCallError: Throwable? = null
            val transitionCallSucceeded = runCatching {
                module.gameLinkFunction.battleRoom(
                        settleSeconds,
                        allowDuringSettlement = true,
                        onCompleted = completed@{
                            if (!isActiveSettle(settleGenerationId)) return@completed
                            lastBattleRoomTransitionStatus = "completed"
                            onRoomResetComplete("battle_room_engine_commit")
                            Log.clog("[${room.roomID}] Automatic battle-room return completed")
                        },
                        onFailed = { error ->
                            handleBattleRoomTransitionFailure(settleGenerationId, label, room, error)
                        }
                    )
            }.getOrElse { error ->
                transitionCallError = error
                false
            }
            if (transitionCallSucceeded && isActiveSettle(settleGenerationId)) {
                lastBattleRoomTransitionStatus = "scheduled"
            } else if (isActiveSettle(settleGenerationId)) {
                handleBattleRoomTransitionFailure(
                    settleGenerationId,
                    label,
                    room,
                    transitionCallError
                        ?: IllegalStateException("main_thread_operation_or_battle_room_transition_setup_failed")
                )
            }
            return
        }

        Log.clog(
            "[${room.roomID}] Game over - forced server reset in ${settleSeconds}s; " +
                    "automatic battle-room return disabled: reason=$label"
        )
        runCatching {
            Threads.newCountdown(CallTimeTask.GameOverTask, settleSeconds, TimeUnit.SECONDS) {
                if (!isActiveSettle(settleGenerationId)) return@newCountdown
                room.gr()
                Log.clog("[${room.roomID}] Forced server reset requested after settle timer")
            }
        }.onFailure { error ->
            settleTimerScheduleFailureCount.incrementAndGet()
            Log.error("[${room.roomID}] Failed to schedule game-over settle timer; forcing immediate reset", error)
            if (isActiveSettle(settleGenerationId)) {
                room.gr()
            }
        }
    }

    fun isSettling(): Boolean = currentState == GameState.SETTLING
    fun isPlaying(): Boolean = currentState == GameState.PLAYING
    fun configuredSettleSeconds(): Int = gameOverSettlePolicy().countdownSeconds
    fun postGameSettleSeconds(): Int = if (isSettling()) lastSettleSeconds else configuredSettleSeconds()
    fun recommendedSettleRecheckSeconds(): Int = postGameSettleSeconds() + STALE_SETTLING_RECOVERY_GRACE_SECONDS
    fun shouldSkipPostGameAutoStart(): Boolean = isSettling() && activeSettleForcesServerReset

    /**
     * Returns true when the caller should stop the current action because room
     * recovery or battle-room commit still owns the lifecycle.
     */
    fun recoverStaleLifecycleIfNeeded(room: ServerRoom, reason: String): Boolean {
        val state = currentState
        if (room.isBattleRoomTransitionCommitBlocking()) {
            return true
        }
        if (state == GameState.PLAYING && !room.isStartGame && !room.isGameOverResetInProgress()) {
            recordStaleLifecycleRecovery("playing_without_started_room:$reason")
            StartModeConsoleOutputSupport.printThrottled(
                StartModeConsoleOutputSupport.Category.LIFECYCLE_RECOVERY,
                "stale_playing_without_started_room",
                "[StartMode] Recovered stale PLAYING lifecycle without started room: reason=$reason",
                minIntervalMillis = 60_000L
            )
            onRoomResetComplete("stale_playing_without_started_room:$reason")
            return false
        }

        if (state == GameState.SETTLING && !room.isGameOverResetInProgress()) {
            if (shouldBlockStaleSettlingRecovery(
                    resetRetryExhausted = activeResetRetryExhausted,
                    resetRetryExhaustedAtMillis = resetRetryExhaustedAtMillis,
                    nowMillis = System.currentTimeMillis(),
                    graceMillis = resetRetryExhaustedRecoveryGraceMillis()
                )
            ) {
                return true
            }
            val staleSettlingRecoveryMs = staleSettlingRecoveryMillis()
            val settleAge = ageMillis(lastSettleAtMillis)
            if (settleAge >= staleSettlingRecoveryMs) {
                if (staleLifecycleRecoveryInFlight) {
                    return true
                }
                // P0-4: the exhausted latch no longer blocks forever. Past its grace window we
                // release exactly one recycle and immediately re-arm, so recovery is
                // rate-limited rather than unbounded. A non-exhausted latch never reaches the
                // consume call, keeping the normal path unchanged.
                val exhaustedRelease = tryConsumeResetRetryExhaustedRecovery()
                if (activeResetRetryExhausted && !exhaustedRelease) {
                    return true
                }
                staleLifecycleRecoveryInFlight = true
                recordStaleLifecycleRecovery("stale_settling:$reason")
                StartModeConsoleOutputSupport.printThrottled(
                    StartModeConsoleOutputSupport.Category.LIFECYCLE_RECOVERY,
                    "stale_settling_recycle",
                    "[StartMode] Stale game-over settling detected; requesting room recycle: " +
                            "reason=$reason, settleAgeMs=$settleAge, thresholdMs=$staleSettlingRecoveryMs" +
                            ", resetRetryExhausted=$exhaustedRelease",
                    minIntervalMillis = 60_000L
                )
                room.gr()
                return true
            }
        }

        return false
    }

    fun onRoomResetComplete(reason: String) {
        synchronized(lifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            currentState = GameState.LOBBY
            if (reason == "server_room_battle_room") {
                lastBattleRoomTransitionStatus = "manual_completed"
                lastBattleRoomTransitionError = "none"
            }
            staleLifecycleRecoveryInFlight = false
            activeSettleForcesServerReset = false
            activeResetRetryAttempts = 0
            activeResetRetryExhausted = false
            resetRetryExhaustedAtMillis = 0L
        }
        closeSettleTask("reset_complete:$reason")
        closeResetRetryTask("reset_complete:$reason")
        clearNoOnlineHumanGrace("reset:$reason")
        resetCount.incrementAndGet()
        lastResetAtMillis = System.currentTimeMillis()
    }

    fun onRoomResetFailed(room: ServerRoom, reason: String, error: Throwable) {
        closeSettleTask("reset_failed:$reason")
        closeResetRetryTask("reset_failed:$reason")
        val resetGenerationId: Long
        val shouldScheduleRetry: Boolean
        synchronized(lifecycleLock) {
            if (currentState != GameState.SETTLING) {
                currentState = GameState.SETTLING
                lastSettleAtMillis = System.currentTimeMillis()
                lastSettleSeconds = RESET_RETRY_DELAY_SECONDS
                lastSettleReason = "reset_failure:$reason"
                resetGenerationId = lifecycleGeneration.incrementAndGet()
            } else {
                resetGenerationId = lifecycleGeneration.get()
            }
            lastSettleMode = "forced_server_reset_failed"
            activeSettleForcesServerReset = true
            staleLifecycleRecoveryInFlight = false
            shouldScheduleRetry = GameOverSettlePolicy.shouldRetryReset(
                activeResetRetryAttempts,
                MAX_AUTOMATIC_RESET_RETRIES
            )
            if (shouldScheduleRetry) {
                activeResetRetryAttempts++
                activeResetRetryExhausted = false
                resetRetryExhaustedAtMillis = 0L
            } else {
                activeResetRetryExhausted = true
                // P0-4: stamp the latch so it expires into a single rate-limited recycle
                // instead of wedging the room in SETTLING forever.
                resetRetryExhaustedAtMillis = System.currentTimeMillis()
            }
        }

        resetFailureCount.incrementAndGet()
        lastResetFailureReason = reason.take(120).ifBlank { "unknown" }
        lastResetFailureError = summarizeError(error)
        lastResetFailureAtMillis = System.currentTimeMillis()
        Log.error(
            "[${room.roomID}] Game-over reset failed: reason=$reason, " +
                    "automaticRetry=$shouldScheduleRetry, error=$lastResetFailureError",
            error
        )

        if (!shouldScheduleRetry) {
            resetRetryExhaustedCount.incrementAndGet()
            runCatching { room.call.sendSystemMessageLocal("gameOver.resetRetryExhausted") }
            return
        }

        resetRetryScheduledCount.incrementAndGet()
        runCatching {
            Threads.newCountdown(
                CallTimeTask.GameOverResetRetryTask,
                RESET_RETRY_DELAY_SECONDS,
                TimeUnit.SECONDS
            ) {
                if (!isActiveSettle(resetGenerationId) || room.isGameOverResetInProgress()) {
                    return@newCountdown
                }
                runCatching { room.call.sendSystemMessageLocal("gameOver.resetRetry") }
                room.gr()
            }
        }.onFailure { scheduleError ->
            synchronized(lifecycleLock) {
                if (isActiveSettle(resetGenerationId)) {
                    activeResetRetryExhausted = true
                    resetRetryExhaustedAtMillis = System.currentTimeMillis()
                }
            }
            resetRetryExhaustedCount.incrementAndGet()
            lastResetFailureError = "retry_schedule_failed:${summarizeError(scheduleError)}"
            Log.error("[${room.roomID}] Failed to schedule game-over reset retry", scheduleError)
            runCatching { room.call.sendSystemMessageLocal("gameOver.resetRetryExhausted") }
        }
    }

    fun recordNoOnlineHumanGraceStarted(reason: String, graceSeconds: Int) {
        if (!noOnlineHumanGraceActive) {
            noOnlineHumanGraceStartedCount.incrementAndGet()
        }
        noOnlineHumanGraceActive = true
        noOnlineHumanGraceSeconds = graceSeconds
        lastNoOnlineHumanReason = reason
        lastNoOnlineHumanAtMillis = System.currentTimeMillis()
    }

    fun recordNoOnlineHumanGraceExpired(reason: String) {
        noOnlineHumanGraceExpiredCount.incrementAndGet()
        noOnlineHumanGraceActive = false
        lastNoOnlineHumanReason = reason
        lastNoOnlineHumanAtMillis = System.currentTimeMillis()
    }

    fun recordNoOnlineHumanDeferred(reason: String, graceSeconds: Int) {
        noOnlineHumanDeferredCount.incrementAndGet()
        recordNoOnlineHumanGraceStarted(reason, graceSeconds)
    }

    fun clearNoOnlineHumanGrace(reason: String) {
        if (noOnlineHumanGraceActive) {
            noOnlineHumanGraceClearedCount.incrementAndGet()
            lastNoOnlineHumanReason = reason
            lastNoOnlineHumanAtMillis = System.currentTimeMillis()
        }
        noOnlineHumanGraceActive = false
    }

    fun buildDiagnostics(): List<String> {
        val settlePolicy = gameOverSettlePolicy()
        return listOf(
            "gameOver.state=${currentState.name}",
            "gameOver.settleSeconds=${settlePolicy.countdownSeconds}",
            "gameOver.defaultSettleSeconds=$SETTLE_SECONDS",
            "gameOver.lifecycleGeneration=${lifecycleGeneration.get()}",
            "gameOver.configReturnEnabled=${settlePolicy.mode.usesBattleRoomTransition}",
            "gameOver.configReturnCountdownSeconds=${settlePolicy.countdownSeconds}",
            "gameOver.configSettleMode=${settlePolicy.mode.diagnosticValue}",
            "gameOver.configSettleMessageKey=${settlePolicy.mode.messageKey}",
            "gameOver.settlingCount=${settlingCount.get()}",
            "gameOver.resetCount=${resetCount.get()}",
            "gameOver.lastSettleReason=$lastSettleReason",
            "gameOver.lastSettleSeconds=$lastSettleSeconds",
            "gameOver.lastSettleMode=$lastSettleMode",
            "gameOver.lastBattleRoomTransitionStatus=$lastBattleRoomTransitionStatus",
            "gameOver.lastBattleRoomTransitionError=$lastBattleRoomTransitionError",
            "gameOver.battleRoomTransitionFailureCount=${battleRoomTransitionFailureCount.get()}",
            "gameOver.battleRoomFallbackResetCount=${battleRoomFallbackResetCount.get()}",
            "gameOver.battleRoomPreservedLobbyFailureCount=${battleRoomPreservedLobbyFailureCount.get()}",
            "gameOver.settleTimerScheduleFailureCount=${settleTimerScheduleFailureCount.get()}",
            "gameOver.resetFailureCount=${resetFailureCount.get()}",
            "gameOver.resetRetryScheduledCount=${resetRetryScheduledCount.get()}",
            "gameOver.resetRetryExhaustedCount=${resetRetryExhaustedCount.get()}",
            "gameOver.activeResetRetryAttempts=$activeResetRetryAttempts",
            "gameOver.activeResetRetryExhausted=$activeResetRetryExhausted",
            "gameOver.resetRetryExhaustedAgeMs=${ageMillis(resetRetryExhaustedAtMillis)}",
            "gameOver.resetRetryExhaustedRecoveryGraceMs=${resetRetryExhaustedRecoveryGraceMillis()}",
            "gameOver.resetRetryExhaustedRecoveryCount=${resetRetryExhaustedRecoveryCount.get()}",
            "gameOver.lastResetFailureReason=$lastResetFailureReason",
            "gameOver.lastResetFailureError=$lastResetFailureError",
            "gameOver.lastResetFailureAgeMs=${ageMillis(lastResetFailureAtMillis)}",
            "gameOver.lastSettleAgeMs=${ageMillis(lastSettleAtMillis)}",
            "gameOver.lastResetAgeMs=${ageMillis(lastResetAtMillis)}",
            "gameOver.staleSettlingRecoveryMs=${staleSettlingRecoveryMillis()}",
            "gameOver.staleLifecycleRecoveryCount=${staleLifecycleRecoveryCount.get()}",
            "gameOver.staleLifecycleRecoveryInFlight=$staleLifecycleRecoveryInFlight",
            "gameOver.lastStaleLifecycleRecoveryReason=$lastStaleLifecycleRecoveryReason",
            "gameOver.lastStaleLifecycleRecoveryAgeMs=${ageMillis(lastStaleLifecycleRecoveryAtMillis)}",
            "flow.noOnlineHumanGraceActive=$noOnlineHumanGraceActive",
            "flow.noOnlineHumanGraceSeconds=$noOnlineHumanGraceSeconds",
            "flow.noOnlineHumanGraceStarted=${noOnlineHumanGraceStartedCount.get()}",
            "flow.noOnlineHumanGraceCleared=${noOnlineHumanGraceClearedCount.get()}",
            "flow.noOnlineHumanGraceExpired=${noOnlineHumanGraceExpiredCount.get()}",
            "flow.noOnlineHumanDeferred=${noOnlineHumanDeferredCount.get()}",
            "flow.lastNoOnlineHumanReason=$lastNoOnlineHumanReason",
            "flow.lastNoOnlineHumanAgeMs=${ageMillis(lastNoOnlineHumanAtMillis)}"
        )
    }

    private fun recordStaleLifecycleRecovery(reason: String) {
        staleLifecycleRecoveryCount.incrementAndGet()
        lastStaleLifecycleRecoveryReason = reason.take(120).ifBlank { "unknown" }
        lastStaleLifecycleRecoveryAtMillis = System.currentTimeMillis()
    }

    private fun closeResetRetryTask(reason: String) {
        runCatching { Threads.closeTimeTask(CallTimeTask.GameOverResetRetryTask) }
            .onFailure { error ->
                Log.warn("[StartMode] Failed to close game-over reset retry task: reason=$reason", error)
            }
    }

    private fun closeSettleTask(reason: String) {
        runCatching { Threads.closeTimeTask(CallTimeTask.GameOverTask) }
            .onFailure { error ->
                Log.warn("[StartMode] Failed to close game-over settle task: reason=$reason", error)
            }
    }

    private fun recordBattleRoomTransitionFailure(generation: Long, label: String, error: Throwable) {
        if (!isActiveSettle(generation)) return
        battleRoomTransitionFailureCount.incrementAndGet()
        lastBattleRoomTransitionStatus = "failed"
        lastBattleRoomTransitionError = summarizeError(error)
        Log.error(
            "[StartMode] Automatic battle-room transition failed: reason=$label, " +
                    "generation=$generation, error=$lastBattleRoomTransitionError",
            error
        )
    }

    private fun handleBattleRoomTransitionFailure(
        generation: Long,
        label: String,
        room: ServerRoom,
        error: Throwable
    ) {
        if (!isActiveSettle(generation)) return
        recordBattleRoomTransitionFailure(generation, label, error)
        if (!BattleRoomTransitionPolicy.shouldProtectivelyResetAfterFailure(room.isStartGame)) {
            synchronized(lifecycleLock) {
                if (!isActiveSettle(generation)) return
                lastSettleMode = "battle_room_return_degraded_lobby_preserved"
                lastBattleRoomTransitionStatus = "completed_with_sync_warning"
                activeSettleForcesServerReset = false
                battleRoomPreservedLobbyFailureCount.incrementAndGet()
            }
            onRoomResetComplete("battle_room_lobby_preserved_after_failure")
            Log.warn(
                "[${room.roomID}] Automatic battle-room return reached the lobby, but lobby refresh did not fully complete; " +
                        "connected players are preserved and no protective reset will run."
            )
            return
        }
        if (!markBattleRoomFallbackReset(generation)) return
        runCatching {
            room.call.sendSystemMessageLocal("gameOver.returnFailedForceServerReset")
        }.onFailure { messageError ->
            Log.warn("[${room.roomID}] Failed to send battle-room fallback reset message", messageError)
        }
        if (!isActiveSettle(generation)) return
        room.gr()
    }

    private fun markBattleRoomFallbackReset(generation: Long): Boolean {
        synchronized(lifecycleLock) {
            if (!isActiveSettle(generation)) return false
            lastSettleMode = "battle_room_return_fallback_reset"
            lastBattleRoomTransitionStatus = "fallback_reset"
            activeSettleForcesServerReset = true
            battleRoomFallbackResetCount.incrementAndGet()
            return true
        }
    }

    private fun isActiveSettle(generation: Long): Boolean {
        return currentState == GameState.SETTLING && lifecycleGeneration.get() == generation
    }

    private fun gameOverSettlePolicy(): GameOverSettlePolicy.Decision {
        val config = runCatching { Data.configStartMode }.getOrNull()
        return GameOverSettlePolicy.decide(
            enableBattleRoomReturn = config?.enableGameOverBattleRoomReturn,
            countdownSeconds = config?.gameOverReturnCountdownSeconds
        )
    }

    private fun staleSettlingRecoveryMillis(): Long {
        return (lastSettleSeconds + STALE_SETTLING_RECOVERY_GRACE_SECONDS).toLong() * 1000L
    }

    /** P0-4: how long the exhausted latch blocks stale-settling recovery before one release. */
    internal fun resetRetryExhaustedRecoveryGraceMillis(): Long =
        RESET_EXHAUSTED_RECOVERY_GRACE_SECONDS * 1000L

    /**
     * P0-4 pure decision: should the exhausted latch keep blocking stale-settling recovery?
     *
     * Returns `true` while the latch is armed and still inside its grace window, and `false`
     * once the window has elapsed (the caller may then release exactly one recycle) or when
     * the latch is not armed at all.
     *
     * An armed latch with a non-positive timestamp is treated as *expired* rather than
     * blocking forever: the whole point of P0-4 is that no state may wedge the room
     * permanently. Every arming path stamps the timestamp, so this branch is defensive only,
     * and even if it were reached the release re-arms with a real timestamp, so it cannot
     * produce more than one release.
     */
    internal fun shouldBlockStaleSettlingRecovery(
        resetRetryExhausted: Boolean,
        resetRetryExhaustedAtMillis: Long,
        nowMillis: Long,
        graceMillis: Long
    ): Boolean {
        if (!resetRetryExhausted) {
            return false
        }
        if (resetRetryExhaustedAtMillis <= 0L) {
            return false
        }
        return nowMillis - resetRetryExhaustedAtMillis < graceMillis
    }

    /** P0-4: arms the latch and stamps its age. Re-entrant w.r.t. [lifecycleLock]. */
    private fun armResetRetryExhaustedLocked(nowMillis: Long) {
        activeResetRetryExhausted = true
        resetRetryExhaustedAtMillis = nowMillis
    }

    internal fun isResetRetryExhausted(): Boolean = activeResetRetryExhausted

    internal fun resetRetryExhaustedRecoveryCount(): Long = resetRetryExhaustedRecoveryCount.get()

    /** Test seam: restores the latch fields to their process-start defaults. */
    internal fun resetResetRetryStateForTests() {
        synchronized(lifecycleLock) {
            activeResetRetryExhausted = false
            resetRetryExhaustedAtMillis = 0L
            activeResetRetryAttempts = 0
        }
        resetRetryExhaustedRecoveryCount.set(0L)
    }

    /** Test seam: arms the latch at a controlled timestamp (mirrors the exhausted branch). */
    internal fun armResetRetryExhaustedForTests(nowMillis: Long) {
        synchronized(lifecycleLock) {
            armResetRetryExhaustedLocked(nowMillis)
        }
    }

    /** Test seam: clears the latch (mirrors `onRoomResetComplete`). */
    internal fun clearResetRetryExhaustedForTests() {
        synchronized(lifecycleLock) {
            activeResetRetryExhausted = false
            resetRetryExhaustedAtMillis = 0L
        }
    }

    /**
     * P0-4: atomically claims the single post-grace stale-settling recycle.
     *
     * Returns `true` only for the caller that consumed the release. It **re-arms** the latch
     * with the current timestamp before returning, so the next release is a full grace window
     * away. That is what makes this rate-limited instead of a reset storm:
     *  - success  -> `onRoomResetComplete()` clears the latch and moves to LOBBY;
     *  - failure  -> `onRoomResetFailed()` re-arms the latch with a fresh timestamp;
     *  - either way the next release is >= [resetRetryExhaustedRecoveryGraceMillis] later.
     *
     * When the latch is not armed this returns `false` without touching state, so the normal
     * (non-exhausted) path is byte-for-byte the previous behaviour.
     */
    internal fun tryConsumeResetRetryExhaustedRecovery(nowMillis: Long = System.currentTimeMillis()): Boolean {
        synchronized(lifecycleLock) {
            if (!activeResetRetryExhausted) {
                return false
            }
            if (shouldBlockStaleSettlingRecovery(
                    resetRetryExhausted = true,
                    resetRetryExhaustedAtMillis = resetRetryExhaustedAtMillis,
                    nowMillis = nowMillis,
                    graceMillis = resetRetryExhaustedRecoveryGraceMillis()
                )
            ) {
                return false
            }
            // Re-arm before releasing: one recycle per grace window.
            armResetRetryExhaustedLocked(nowMillis)
            resetRetryExhaustedRecoveryCount.incrementAndGet()
            return true
        }
    }

    private fun ageMillis(eventAtMillis: Long): Long {
        if (eventAtMillis <= 0L) {
            return -1L
        }
        return (System.currentTimeMillis() - eventAtMillis).coerceAtLeast(0L)
    }

    private fun summarizeError(error: Throwable): String {
        val type = error::class.java.simpleName.ifBlank { "Throwable" }
        val message = error.message?.replace(Regex("\\s+"), " ")?.take(160).orEmpty()
        return if (message.isBlank()) type else "$type:$message"
    }
}
