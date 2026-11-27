/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal data class StartModeSurvivalRuntimeSnapshot(
    val engineReady: Boolean = false,
    val missionReady: Boolean = false,
    val missionSurvival: Boolean = false,
    val customWaves: Boolean = false,
    val ended: Boolean = false,
    val waveIndex: Int = 0,
    val waveCountdownFrames: Float = 0f,
    val attackPoints: Int = 0,
    val localPlayerIndex: Int? = null,
    val localPlayerAllyGroup: Int? = null,
    val localPlayerBeforeRepair: Int? = null,
    val controlledMainBuildings: Int = 0,
    val humanMainBuildings: Int = 0,
    val primaryHumanMainBuildings: Int = 0,
    val survivalAiPresent: Boolean = false,
    val survivalAiFlag: Boolean? = null,
    val survivalAiTeam: Int? = null,
    val survivalAiStates: String = "",
    val error: String = ""
)

internal data class StartModeSurvivalControlledPlayerState(
    val beforePlayerIndex: Int? = null,
    val controlledPlayerIndex: Int? = null,
    val controlledAllyGroup: Int? = null,
    val controlledMainBuildings: Int = 0,
    val humanMainBuildings: Int = 0,
    val error: String = ""
) {
    fun toDiagnosticString(): String {
        if (error.isNotBlank()) {
            return "error=$error"
        }
        return "before=${beforePlayerIndex ?: "null"}/current=${controlledPlayerIndex ?: "null"}/ally=${controlledAllyGroup ?: "null"}/controlledMain=$controlledMainBuildings/humanMain=$humanMainBuildings"
    }
}

internal data class StartModeSurvivalAiRuntimeState(
    val playerIndex: Int,
    val present: Boolean,
    val survivalFlag: Boolean?,
    val allyGroup: Int?
) {
    fun toDiagnosticString(): String {
        return "slot=${playerIndex + 1}/present=$present/U=${survivalFlag ?: "null"}/ally=${allyGroup ?: "null"}"
    }
}
