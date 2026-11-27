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
import net.rwhps.server.dependent.redirections.game.clas.GameLoopOperationDrain
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.util.log.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Sends lightweight heartbeat packets independently of the heavy lobby/server-info fallback.
 *
 * TEAM_LIST refreshes use the native lobby update path. They must never call
 * the full game-save SYNC path while the room is a lobby.
 */
internal object StartModeLatencyHeartbeatSupport {
    private const val DEFAULT_INTERVAL_MILLIS = 1_000
    private const val LEGACY_DEFAULT_INTERVAL_MILLIS = 10_000
    private const val TASK_START_DELAY_SECONDS = 1
    private const val MAX_DISPLAY_PING_MILLIS = 9_999
    private const val DISPLAY_RISE_LIMIT_MILLIS = 120
    private const val DISPLAY_DROP_LIMIT_MILLIS = 180
    private const val DISPLAY_STALE_MIN_MILLIS = 5_000L
    private const val DISPLAY_HIGH_AVG_MILLIS = 180L
    private const val DISPLAY_HIGH_RAW_MILLIS = 250
    private const val DISPLAY_JITTER_MILLIS = 180

    private val sentHeartbeats = AtomicLong(0)
    private val failedHeartbeats = AtomicLong(0)
    private val latencyListRefreshes = AtomicLong(0)
    private val failedLatencyListRefreshes = AtomicLong(0)
    private val deferredLatencyListRefreshes = AtomicLong(0)
    private val lastLatencyListRefreshResult = AtomicReference("none")
    private val latencyListRefreshDurationNanos = AtomicLong(0)
    private val latencyListRefreshDurationSamples = AtomicLong(0)
    private val latencyListRefreshDurationMaxNanos = AtomicLong(0)
    private val latencyListRefreshDurationLastNanos = AtomicLong(0)
    private val latencyRefreshLock = Any()
    private val playerLatencyStates = ConcurrentHashMap<String, PlayerLatencyState>()

    fun onPlayerJoin(player: PlayerHess) {
        sendHeartbeat(player)
        ensureStarted()
    }

    @Synchronized fun ensureStarted() {
        if (Threads.containsTimeTask(CallTimeTask.LatencyHeartbeatTask)) {
            return
        }

        val intervalSeconds = (latencyHeartbeatMillis() / 1000L).coerceAtLeast(1L).toInt()
        Threads.newTimedTask(
            CallTimeTask.LatencyHeartbeatTask,
            TASK_START_DELAY_SECONDS,
            intervalSeconds,
            TimeUnit.SECONDS
        ) {
            tick()
        }
    }

    fun latencyHeartbeatMillis(): Long {
        val configuredMillis = Data.configStartMode?.latencyHeartbeatMillis ?: DEFAULT_INTERVAL_MILLIS
        return if (configuredMillis == LEGACY_DEFAULT_INTERVAL_MILLIS) {
            DEFAULT_INTERVAL_MILLIS
        } else {
            configuredMillis.coerceIn(1_000, 60_000)
        }.toLong()
    }

    fun buildDiagnostics(): List<String> {
        val assessment = currentDisplayAssessment()
        return listOf(
            "latency.heartbeatMillis=${latencyHeartbeatMillis()}",
            "latency.heartbeatsSent=${sentHeartbeats.get()}",
            "latency.heartbeatsFailed=${failedHeartbeats.get()}",
            "latency.teamListRefreshes=${latencyListRefreshes.get()}",
            "latency.teamListRefreshesFailed=${failedLatencyListRefreshes.get()}",
            "latency.teamListRefreshesDeferred=${deferredLatencyListRefreshes.get()}",
            "latency.teamListRefreshLastResult=${lastLatencyListRefreshResult.get()}",
            "latency.teamListRefreshPolicy=timer_only",
            "latency.teamListRefreshDurationAvgMs=${averageDurationMillis()}",
            "latency.teamListRefreshDurationMaxMs=${nanosToMillis(latencyListRefreshDurationMaxNanos.get())}",
            "latency.teamListRefreshDurationLastMs=${nanosToMillis(latencyListRefreshDurationLastNanos.get())}",
            "latency.responseTriggeredRefreshes=0",
            "latency.responseRefreshSkipped=0",
            "latency.trackedPlayers=${playerLatencyStates.size}",
            "latency.sampledPlayers=${assessment.sampledPlayers}",
            "latency.stalePlayers=${assessment.stalePlayers}",
            "latency.sampleStaleAfterMs=${assessment.staleAfterMillis}",
            "latency.displayAvgMs=${assessment.displayAvgMillis}",
            "latency.rawLastMaxMs=${assessment.rawLastMaxMillis}",
            "latency.jitterMaxMs=${assessment.jitterMaxMillis}",
            "latency.displayState=${assessment.state}",
            "latency.displayAdvice=${assessment.advice}",
            "latency.taskActive=${Threads.containsTimeTask(CallTimeTask.LatencyHeartbeatTask)}"
        ) + loopHealthDiagnostics()
    }

    /**
     * A1-05 (independent audit): the loop-guard counters had no production reader, so "we swallowed
     * an exception and kept the loop alive" was invisible. Surface what CAN be read correctly.
     *
     * ⚠️ Classloader note, and the reason this does not report the guard counters: this class is
     * app-loader, and so is FPSSleepRedirections (the copy that calls recordLoopTick and therefore
     * owns the tick counters). GameLoopOperationDrain exists in BOTH loaders, so the numbers below
     * describe the APP copy -- which is the one that sees real ticks, so it is the right copy for
     * "is the loop actually running".
     *
     * GameLoopExceptionGuard and RenderGateGuard, by contrast, are referenced only by wrapped game
     * classes and therefore only exist meaningfully in the GAME loader. Reading them from here would
     * return a different, never-used instance whose counters stay 0 forever -- which is precisely
     * the misleading-zero trap this audit flagged. They are deliberately omitted rather than
     * reported as a permanent zero; their real signal is the rate-limited log lines they emit.
     */
    private fun loopHealthDiagnostics(): List<String> {
        return runCatching {
            val health = GameLoopOperationDrain.healthSnapshot()
            listOf(
                "loop.state=${health.state}",
                "loop.ticks=${health.tickCount}",
                "loop.lastTickAgeMs=${health.ageMillis(System.currentTimeMillis()) ?: -1L}",
                "loop.thread=${health.threadName.ifBlank { "none" }}",
                "loop.drainFailures=${health.drainFailureCount}",
                "loop.lastDrainError=${health.lastDrainError ?: "none"}",
                "loop.classloaderNote=app-loader copy (FPSSleepRedirections side); " +
                    "GameLoopExceptionGuard/RenderGateGuard are game-loader-only and are " +
                    "intentionally not reported here to avoid misleading zeros"
            )
        }.getOrElse { error ->
            listOf("loop.diagnosticsUnavailable=${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun onHeartbeatResponse(player: PlayerHess, rawPingMillis: Int) {
        recordLatencySample(player, rawPingMillis)
        // Keep latency sampling per heartbeat, but let the single timer-owned
        // lightweight lobby refresh publish the latest TEAM_LIST values once
        // per interval instead of refreshing on every player's response.
    }

    fun displayPingMillis(player: PlayerHess?): Int {
        if (player == null) {
            return 0
        }
        return playerLatencyStates[playerStateKey(player)]?.displayPingMillis ?: 0
    }

    fun forgetPlayer(player: PlayerHess) {
        playerLatencyStates.remove(playerStateKey(player))
    }

    fun resetRuntimeState() {
        playerLatencyStates.clear()
    }

    fun currentDisplayAssessment(): LatencyDisplayAssessment {
        return buildDisplayAssessment()
    }

    private fun tick() {
        // P0-STALL: this task runs on Quartz, i.e. independently of the game loop, which makes it
        // the only place that can notice the game loop being dead. A dead loop produces no ticks
        // and no log line of its own, and on the live instance (b013) it left the server accepting
        // connections while every game-thread operation timed out. Report it loudly, rate-limited.
        // Deliberately no auto-exit: mcsmanager runs these instances with autoRestart=false, so
        // exiting would make a degraded instance permanently dead.
        GameLoopOperationDrain.reportDeadLoopIfConfirmed()

        if (!HeadlessModuleManage.initHPS()) {
            Threads.closeTimeTask(CallTimeTask.LatencyHeartbeatTask)
            return
        }

        val module = HeadlessModuleManage.hps
        val players = module.room.playerManage.playerGroup
            .filter { !it.isAi && it.con != null }
        if (players.isEmpty()) {
            playerLatencyStates.clear()
            Threads.closeTimeTask(CallTimeTask.LatencyHeartbeatTask)
            return
        }

        pruneMissingPlayerStates(players)
        players.forEach(::sendHeartbeat)
        if (!module.room.isStartGame) {
            refreshLatencyDisplay()
        }
    }

    private fun sendHeartbeat(player: PlayerHess) {
        val con = player.con ?: return
        runCatching {
            con.sendPing()
            sentHeartbeats.incrementAndGet()
        }.onFailure { error ->
            failedHeartbeats.incrementAndGet()
            Log.debug("[StartModeLatency] Failed to send heartbeat to ${player.name}: ${error.message.orEmpty()}")
        }
    }

    private fun refreshLatencyDisplay() {
        val startedAtNanos = System.nanoTime()
        try {
            synchronized(latencyRefreshLock) {
                if (!HeadlessModuleManage.initHPS()) {
                    deferredLatencyListRefreshes.incrementAndGet()
                    lastLatencyListRefreshResult.set("deferred_module_unavailable")
                    return
                }

                val module = HeadlessModuleManage.hps
                if (module.room.isStartGame) {
                    deferredLatencyListRefreshes.incrementAndGet()
                    lastLatencyListRefreshResult.set("deferred_game_in_progress")
                    return
                }
                if (module.room.isLobbyPlayerSyncBlocked()) {
                    deferredLatencyListRefreshes.incrementAndGet()
                    lastLatencyListRefreshResult.set("deferred_battle_room_transition")
                    return
                }
                if (StartModeAutoStartSupport.isStartOperationInProgress()) {
                    deferredLatencyListRefreshes.incrementAndGet()
                    lastLatencyListRefreshResult.set("deferred_start_confirmation")
                    return
                }

                // This emits only native lobby state (TEAM_LIST/SERVER_INFO).
                // Full game SYNC packets are invalid after a battle-room return.
                val gameLinkFunction = module.gameLinkFunction
                val result = gameLinkFunction.syncLobbyPlayers()
                val error = result.error ?: if (!result.success) {
                    IllegalStateException("lobby_player_sync_failed")
                } else null
                if (error == null) {
                    latencyListRefreshes.incrementAndGet()
                    lastLatencyListRefreshResult.set("ok")
                    return
                }

                failedLatencyListRefreshes.incrementAndGet()
                lastLatencyListRefreshResult.set(refreshFailureCode(error))
            }
        } finally {
            recordRefreshDuration(System.nanoTime() - startedAtNanos)
        }
    }

    private fun refreshFailureCode(error: Throwable): String {
        if (error is IllegalStateException) {
            return when (error.message) {
                "map_engine_not_ready" -> "map_engine_not_ready"
                "lobby_net_engine_not_ready" -> "lobby_net_engine_not_ready"
                "battle_room_lobby_sync_blocked" -> "battle_room_transition"
                "lobby_player_sync_busy" -> "lobby_player_sync_busy"
                else -> error.javaClass.simpleName.ifBlank { "unknown" }
            }
        }
        return error.javaClass.simpleName.ifBlank { "unknown" }
    }

    private fun recordRefreshDuration(durationNanos: Long) {
        val bounded = durationNanos.coerceAtLeast(0L)
        latencyListRefreshDurationNanos.addAndGet(bounded)
        latencyListRefreshDurationSamples.incrementAndGet()
        latencyListRefreshDurationLastNanos.set(bounded)
        latencyListRefreshDurationMaxNanos.updateAndGet { current -> maxOf(current, bounded) }
    }

    private fun averageDurationMillis(): Long {
        val samples = latencyListRefreshDurationSamples.get()
        return if (samples <= 0L) 0L else nanosToMillis(latencyListRefreshDurationNanos.get() / samples)
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private fun recordLatencySample(player: PlayerHess, rawPingMillis: Int) {
        val normalizedRaw = rawPingMillis.coerceIn(0, MAX_DISPLAY_PING_MILLIS)
        val state = playerLatencyStates.computeIfAbsent(playerStateKey(player)) { PlayerLatencyState() }
        synchronized(state) {
            val previousDisplay = state.displayPingMillis
            val previousRaw = state.rawLastPingMillis
            val nextDisplay = if (state.samples == 0L) {
                normalizedRaw
            } else {
                val limitedRaw = if (normalizedRaw > previousDisplay &&
                    abs(normalizedRaw - previousDisplay) > DISPLAY_RISE_LIMIT_MILLIS
                ) {
                    previousDisplay + DISPLAY_RISE_LIMIT_MILLIS
                } else if (normalizedRaw < previousDisplay &&
                    abs(normalizedRaw - previousDisplay) > DISPLAY_DROP_LIMIT_MILLIS
                ) {
                    (previousDisplay - DISPLAY_DROP_LIMIT_MILLIS).coerceAtLeast(0)
                } else {
                    normalizedRaw
                }

                if (limitedRaw >= previousDisplay) {
                    ((previousDisplay * 3) + limitedRaw + 2) / 4
                } else {
                    // Let improved network conditions show up faster while still dampening one-sample drops.
                    (previousDisplay + limitedRaw + 1) / 2
                }
            }

            state.samples++
            state.rawLastPingMillis = normalizedRaw
            state.displayPingMillis = nextDisplay.coerceIn(0, MAX_DISPLAY_PING_MILLIS)
            state.jitterMillis = if (state.samples <= 1L) {
                0
            } else {
                abs(normalizedRaw - previousRaw)
            }
            state.lastSampleAtMillis = System.currentTimeMillis()
        }
    }

    private fun pruneMissingPlayerStates(players: List<PlayerHess>) {
        val activeKeys = players.map(::playerStateKey).toHashSet()
        playerLatencyStates.keys.forEach { key ->
            if (key !in activeKeys) {
                playerLatencyStates.remove(key)
            }
        }
    }

    private fun buildDisplayAssessment(now: Long = System.currentTimeMillis()): LatencyDisplayAssessment {
        val snapshots = playerLatencyStates.values.map { state ->
            synchronized(state) {
                PlayerLatencySnapshot(
                    samples = state.samples,
                    rawLastPingMillis = state.rawLastPingMillis,
                    displayPingMillis = state.displayPingMillis,
                    jitterMillis = state.jitterMillis,
                    lastSampleAtMillis = state.lastSampleAtMillis
                )
            }
        }
        val sampled = snapshots.filter { it.samples > 0L && it.lastSampleAtMillis > 0L }
        val staleAfterMillis = staleSampleMillis()
        val stalePlayers = sampled.count { now - it.lastSampleAtMillis > staleAfterMillis }
        val displayAvgMillis = if (sampled.isEmpty()) {
            0L
        } else {
            sampled.sumOf { it.displayPingMillis.toLong() } / sampled.size
        }
        val rawLastMaxMillis = sampled.maxOfOrNull { it.rawLastPingMillis } ?: 0
        val jitterMaxMillis = sampled.maxOfOrNull { it.jitterMillis } ?: 0
        val state = when {
            sampled.isEmpty() -> "unknown"
            stalePlayers == sampled.size -> "stale"
            displayAvgMillis >= DISPLAY_HIGH_AVG_MILLIS || rawLastMaxMillis >= DISPLAY_HIGH_RAW_MILLIS -> "high"
            jitterMaxMillis >= DISPLAY_JITTER_MILLIS || stalePlayers > 0 -> "jitter"
            else -> "stable"
        }
        val advice = when (state) {
            "stable" -> "keep_current_profile"
            "jitter" -> "observe_network_jitter"
            "high" -> "collect_route_and_client_ping"
            "stale" -> "check_player_connection_or_heartbeat"
            else -> "wait_for_heartbeat_samples"
        }

        return LatencyDisplayAssessment(
            sampledPlayers = sampled.size,
            stalePlayers = stalePlayers,
            staleAfterMillis = staleAfterMillis,
            displayAvgMillis = displayAvgMillis,
            rawLastMaxMillis = rawLastMaxMillis,
            jitterMaxMillis = jitterMaxMillis,
            state = state,
            advice = advice
        )
    }

    private fun staleSampleMillis(): Long {
        return (latencyHeartbeatMillis() * 3L).coerceAtLeast(DISPLAY_STALE_MIN_MILLIS)
    }

    private fun playerStateKey(player: PlayerHess): String {
        return player.connectHexID.ifBlank { player.name.ifBlank { "slot:${player.index}" } }
    }

    private class PlayerLatencyState {
        var samples: Long = 0L
        var rawLastPingMillis: Int = 0
        var displayPingMillis: Int = 0
        var jitterMillis: Int = 0
        var lastSampleAtMillis: Long = 0L
    }

    private data class PlayerLatencySnapshot(
        val samples: Long,
        val rawLastPingMillis: Int,
        val displayPingMillis: Int,
        val jitterMillis: Int,
        val lastSampleAtMillis: Long
    )

    data class LatencyDisplayAssessment(
        val sampledPlayers: Int,
        val stalePlayers: Int,
        val staleAfterMillis: Long,
        val displayAvgMillis: Long,
        val rawLastMaxMillis: Int,
        val jitterMaxMillis: Int,
        val state: String,
        val advice: String
    )
}
