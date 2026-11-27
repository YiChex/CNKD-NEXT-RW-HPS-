/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import java.util.Locale

internal object StartModeSurvivalDiagnosticsSupport {
    fun build(
        enabled: Boolean,
        startupGraceSeconds: Int,
        enemyClearConfirmSeconds: Int,
        elapsedSeconds: Int,
        enemyObserved: Boolean,
        lastEnemyAliveAgoSeconds: Int,
        suppressedStartupVerdicts: Long,
        suppressedHumanWinVerdicts: Long,
        humanDefeatVerdicts: Long,
        runtime: StartModeSurvivalRuntimeSnapshot,
        survivalAiFlagRepairs: Long,
        engineLocalPlayerRepairs: Long,
        missionDefeatStateRepairs: Long,
        firstWaveTimerRepairs: Long,
        repairFailures: Long
    ): List<String> {
        return listOf(
            "survival.protection.enabled=$enabled",
            "survival.protection.startupGraceSeconds=$startupGraceSeconds",
            "survival.protection.enemyClearConfirmSeconds=$enemyClearConfirmSeconds",
            "survival.protection.elapsedSeconds=${String.format(Locale.US, "%d", elapsedSeconds)}",
            "survival.protection.enemyObserved=$enemyObserved",
            "survival.protection.lastEnemyAliveAgoSeconds=${String.format(Locale.US, "%d", lastEnemyAliveAgoSeconds)}",
            "survival.protection.suppressedStartupVerdicts=$suppressedStartupVerdicts",
            "survival.protection.suppressedHumanWinVerdicts=$suppressedHumanWinVerdicts",
            "survival.protection.humanDefeatVerdicts=$humanDefeatVerdicts",
            "survival.runtime.engineReady=${runtime.engineReady}",
            "survival.runtime.missionReady=${runtime.missionReady}",
            "survival.runtime.missionSurvival=${runtime.missionSurvival}",
            "survival.runtime.customWaves=${runtime.customWaves}",
            "survival.runtime.ended=${runtime.ended}",
            "survival.runtime.waveIndex=${runtime.waveIndex}",
            "survival.runtime.waveCountdownFrames=${runtime.waveCountdownFrames}",
            "survival.runtime.attackPoints=${runtime.attackPoints}",
            "survival.runtime.localPlayerIndex=${runtime.localPlayerIndex ?: "null"}",
            "survival.runtime.localPlayerAlly=${runtime.localPlayerAllyGroup ?: "null"}",
            "survival.runtime.localPlayerBeforeRepair=${runtime.localPlayerBeforeRepair ?: "null"}",
            "survival.runtime.controlledMainBuildings=${runtime.controlledMainBuildings}",
            "survival.runtime.humanMainBuildings=${runtime.humanMainBuildings}",
            "survival.runtime.primaryHumanMainBuildings=${runtime.primaryHumanMainBuildings}",
            "survival.runtime.survivalAiPresent=${runtime.survivalAiPresent}",
            "survival.runtime.survivalAiFlag=${runtime.survivalAiFlag}",
            "survival.runtime.survivalAiTeam=${runtime.survivalAiTeam}",
            "survival.runtime.survivalAiStates=${runtime.survivalAiStates.ifBlank { "<none>" }}",
            "survival.runtime.aiFlagRepairs=$survivalAiFlagRepairs",
            "survival.runtime.engineLocalPlayerRepairs=$engineLocalPlayerRepairs",
            "survival.runtime.missionDefeatStateRepairs=$missionDefeatStateRepairs",
            "survival.runtime.firstWaveTimerRepairs=$firstWaveTimerRepairs",
            "survival.runtime.repairFailures=$repairFailures",
            "survival.runtime.lastError=${runtime.error.ifBlank { "<none>" }}"
        ) + StartModeSurvivalMapSupport.buildDiagnostics()
    }
}
