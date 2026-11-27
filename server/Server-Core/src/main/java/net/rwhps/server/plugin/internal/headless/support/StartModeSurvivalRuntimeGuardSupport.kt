/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.Time
import net.rwhps.server.util.log.Log

internal object StartModeSurvivalRuntimeGuardSupport {
    const val STARTUP_RUNTIME_REPAIR_SECONDS = 45

    private const val LOCAL_PLAYER_GUARD_INTERVAL_MILLIS = 250L
    private const val FULL_RUNTIME_REPAIR_INTERVAL_MILLIS = 1_000L

    @Volatile
    private var runtimeRepairGeneration = 0

    fun schedule(
        room: ServerRoom,
        generation: Int,
        repairEngineLocalPlayerFast: (StartModeSurvivalMapSupport.SurvivalLayout) -> Unit,
        repairRuntimeSurvivalState: (ServerRoom?) -> StartModeSurvivalRuntimeSnapshot,
        recordRuntimeSnapshot: (StartModeSurvivalRuntimeSnapshot) -> Unit,
        logSurvivalRepair: (String) -> Unit
    ) {
        runtimeRepairGeneration = generation
        Threads.newThreadOther(Runnable {
            val deadline = Time.concurrentSecond() + STARTUP_RUNTIME_REPAIR_SECONDS
            var nextFullRepairAt = 0L
            var loggedReady = false
            var loggedNoAttackPoints = false
            while (room.isStartGame && runtimeRepairGeneration == generation) {
                val layout = StartModeSurvivalMapSupport.currentLayout(room)
                repairEngineLocalPlayerFast(layout)
                val nowMillis = Time.concurrentMillis()
                val inStartupWindow = Time.concurrentSecond() <= deadline
                if (nowMillis >= nextFullRepairAt || inStartupWindow) {
                    val snapshot = repairRuntimeSurvivalState(room)
                    recordRuntimeSnapshot(snapshot)
                    nextFullRepairAt = nowMillis + FULL_RUNTIME_REPAIR_INTERVAL_MILLIS

                    if (!loggedReady && snapshot.missionSurvival && snapshot.attackPoints > 0 && snapshot.survivalAiFlag == true) {
                        loggedReady = true
                        logSurvivalRepair(
                            "[StartMode] Survival runtime ready: wave=${snapshot.waveIndex}, countdownFrames=${snapshot.waveCountdownFrames}, attackPoints=${snapshot.attackPoints}"
                        )
                    }

                    if (
                        !loggedNoAttackPoints &&
                        snapshot.missionReady &&
                        snapshot.missionSurvival &&
                        snapshot.attackPoints <= 0 &&
                        snapshot.error.isBlank()
                    ) {
                        loggedNoAttackPoints = true
                        Log.warn("[StartMode] Survival map has no attack_point triggers. Enemy waves cannot spawn on this map.")
                    }
                }

                Thread.sleep(LOCAL_PLAYER_GUARD_INTERVAL_MILLIS)
            }
        })
    }
}
