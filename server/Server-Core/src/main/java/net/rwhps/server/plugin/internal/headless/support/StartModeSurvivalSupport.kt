/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import com.corrodinggames.rts.game.n
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.event.game.ServerGameOverEvent.GameOverData
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.Time
import net.rwhps.server.util.log.Log
import java.util.concurrent.atomic.AtomicLong

internal object StartModeSurvivalSupport {
    private const val DEFAULT_FIRST_WAVE_DELAY_FRAMES = 1_200f
    private const val DEFAULT_NEXT_WAVE_DELAY_FRAMES = 1_800f

    private val suppressedStartupVerdicts = AtomicLong(0L)
    private val suppressedHumanWinVerdicts = AtomicLong(0L)
    private val humanDefeatVerdicts = AtomicLong(0L)
    private val survivalAiFlagRepairs = AtomicLong(0L)
    private val engineLocalPlayerRepairs = AtomicLong(0L)
    private val missionDefeatStateRepairs = AtomicLong(0L)
    private val firstWaveTimerRepairs = AtomicLong(0L)
    private val runtimeRepairFailures = AtomicLong(0L)

    @Volatile
    private var lastGameStartSecond = 0

    @Volatile
    private var lastEnemyAliveSecond = 0

    @Volatile
    private var enemyUnitsObserved = false

    @Volatile
    private var lastRuntimeSnapshot = StartModeSurvivalRuntimeSnapshot()

    fun enabled(): Boolean {
        return Data.configStartMode?.enableSurvivalMapMode ?: false
    }

    fun prepareLobbySurvivalAi(player: PlayerHess? = null, room: ServerRoom? = null) {
        if (!enabled()) {
            return
        }

        runEngineMutation("prepare_lobby_survival_ai") {
            val layout = StartModeSurvivalMapSupport.currentLayout(room)
            if (player != null) {
                val slot = layout.aiSlots.firstOrNull { it.playerIndex == player.index }
                if (slot == null) {
                    return@runEngineMutation
                }
                if (player.team != slot.allyGroup) {
                    player.team = slot.allyGroup
                }
            }

            markSurvivalAiFlag(layout)
        }
    }

    fun onGameStart(room: ServerRoom) {
        if (!enabled()) {
            return
        }

        runEngineMutation("survival_game_start") {
            val now = Time.concurrentSecond()
            lastGameStartSecond = now
            lastEnemyAliveSecond = 0
            enemyUnitsObserved = false
            room.flagData.ai = true
            room.flagData.aiWarn = false
            val layout = StartModeSurvivalMapSupport.currentLayout(room)
            val teamProvision = StartModeSurvivalTeamSupport.provisionCoreTeams(room, layout)
            prepareLobbySurvivalAi(room = room)
            val localPlayer = repairEngineLocalPlayer(layout, room)
            scheduleRuntimeSurvivalGuard(room, now)
            if (!layout.profile.survivalLike) {
                Log.warn("[StartMode] Survival mode is enabled, but the selected map does not expose survival metadata yet. Fallback layout will be used.")
            }
            logSurvivalRepair("[StartMode] Survival game-over protection enabled: grace=${startupGraceSeconds()}s, clearConfirm=${enemyClearConfirmSeconds()}s, localPlayer=${localPlayer.toDiagnosticString()}, ${teamProvision.toDiagnosticString()}")
        }
    }

    fun prepareCoreTeamsBeforeStart(room: ServerRoom) {
        if (!enabled()) {
            return
        }
        runEngineMutation("prepare_survival_core_teams") {
            StartModeSurvivalTeamSupport.provisionCoreTeams(room, StartModeSurvivalMapSupport.currentLayout(room)).also { report ->
                if (report.created.isNotEmpty() || report.replaced.isNotEmpty() || report.skipped.isNotEmpty()) {
                    logSurvivalRepair("[StartMode] Survival core teams prepared before start: ${report.toDiagnosticString()}")
                }
            }
        }
    }

    fun onBattleRoomReturn(room: ServerRoom) {
        runEngineMutation("clear_survival_placeholder_teams") {
            StartModeSurvivalTeamSupport.clearRuntimePlaceholderTeams(room)
        }
    }

    fun shouldUseSurvivalPlayerCheck(room: ServerRoom): Boolean {
        return enabled() && room.isStartGame
    }

    fun shouldReturnBecauseHumansDefeated(room: ServerRoom): Boolean {
        if (!shouldUseSurvivalPlayerCheck(room)) {
            return false
        }

        val runtime = repairRuntimeSurvivalState(room).also {
            recordRuntimeSnapshot(it)
        }
        if (runtime.humanMainBuildings > 0 && runtime.controlledMainBuildings == 0) {
            return false
        }

        val humans = StartModeSurvivalRuntimeQuerySupport.snapshotPlayers(room).filter { !it.isAi }
        if (humans.isEmpty()) {
            if (StartModeSurvivalRuntimeQuerySupport.hasHumanInsideReconnectGrace(room)) {
                return false
            }
            humanDefeatVerdicts.incrementAndGet()
            return true
        }

        if (elapsedSeconds(room) < startupGraceSeconds()) {
            return false
        }

        val anyHumanAlive = humans.any { player ->
            runCatching { player.survive }.getOrDefault(true)
        }
        if (!anyHumanAlive) {
            humanDefeatVerdicts.incrementAndGet()
            return true
        }
        return false
    }

    fun shouldSkipGenericVerdictProbe(room: ServerRoom): Boolean {
        if (!shouldUseSurvivalPlayerCheck(room)) {
            return false
        }

        observeEnemyUnits()
        val runtime = repairRuntimeSurvivalState(room).also {
            recordRuntimeSnapshot(it)
        }
        if (shouldKeepSurvivalMissionRunning(runtime)) {
            suppressedStartupVerdicts.incrementAndGet()
            return true
        }
        if (elapsedSeconds(room) < startupGraceSeconds()) {
            suppressedStartupVerdicts.incrementAndGet()
            return true
        }
        return false
    }

    fun shouldSuppressGenericVerdict(room: ServerRoom, gameOverData: GameOverData): Boolean {
        if (!shouldUseSurvivalPlayerCheck(room)) {
            return false
        }

        observeEnemyUnits()
        if (!isHumanSideVerdict(room, gameOverData)) {
            return false
        }

        val now = Time.concurrentSecond()
        val recentlyHadEnemies = enemyUnitsObserved &&
            lastEnemyAliveSecond > 0 &&
            now - lastEnemyAliveSecond < enemyClearConfirmSeconds()
        if (recentlyHadEnemies) {
            suppressedHumanWinVerdicts.incrementAndGet()
            return true
        }
        return false
    }

    fun buildDiagnostics(): List<String> {
        val runtime = runtimeSnapshot()
        return StartModeSurvivalDiagnosticsSupport.build(
            enabled = enabled(),
            startupGraceSeconds = startupGraceSeconds(),
            enemyClearConfirmSeconds = enemyClearConfirmSeconds(),
            elapsedSeconds = currentElapsedSeconds(),
            enemyObserved = enemyUnitsObserved,
            lastEnemyAliveAgoSeconds = lastEnemyAliveAgoSeconds(),
            suppressedStartupVerdicts = suppressedStartupVerdicts.get(),
            suppressedHumanWinVerdicts = suppressedHumanWinVerdicts.get(),
            humanDefeatVerdicts = humanDefeatVerdicts.get(),
            runtime = runtime,
            survivalAiFlagRepairs = survivalAiFlagRepairs.get(),
            engineLocalPlayerRepairs = engineLocalPlayerRepairs.get(),
            missionDefeatStateRepairs = missionDefeatStateRepairs.get(),
            firstWaveTimerRepairs = firstWaveTimerRepairs.get(),
            repairFailures = totalRuntimeRepairFailures()
        )
    }

    private fun totalRuntimeRepairFailures(): Long {
        return runtimeRepairFailures.get() + StartModeSurvivalTeamSupport.repairFailureCount()
    }

    private fun scheduleRuntimeSurvivalGuard(room: ServerRoom, generation: Int) {
        StartModeSurvivalRuntimeGuardSupport.schedule(
            room = room,
            generation = generation,
            repairEngineLocalPlayerFast = ::repairEngineLocalPlayerFast,
            repairRuntimeSurvivalState = ::repairRuntimeSurvivalState,
            recordRuntimeSnapshot = ::recordRuntimeSnapshot,
            logSurvivalRepair = ::logSurvivalRepair
        )
    }

    private fun runEngineMutation(action: String, mutation: () -> Unit) {
        if (!HeadlessModuleManage.initHPS()) {
            return
        }
        val operationSucceeded = HeadlessModuleManage.hps.gameFunction.suspendMainThreadOperations {
            mutation()
        }
        if (!operationSucceeded) {
            Log.warn("[StartMode] Failed to run survival engine mutation on the game thread: $action")
        }
    }

    private fun repairRuntimeSurvivalState(room: ServerRoom? = null): StartModeSurvivalRuntimeSnapshot {
        if (!HeadlessModuleManage.initHPS()) {
            return StartModeSurvivalRuntimeSnapshot(error = "headless_module_not_ready")
        }
        var snapshot = StartModeSurvivalRuntimeSnapshot(error = "game_thread_operation_failed")
        val operationSucceeded = HeadlessModuleManage.hps.gameFunction.suspendMainThreadOperations {
            snapshot = repairRuntimeSurvivalStateOnGameThread(room)
        }
        return if (operationSucceeded) {
            snapshot
        } else {
            runtimeRepairFailures.incrementAndGet()
            StartModeSurvivalRuntimeSnapshot(error = "game_thread_operation_failed")
        }
    }

    private fun repairRuntimeSurvivalStateOnGameThread(room: ServerRoom? = null): StartModeSurvivalRuntimeSnapshot {
        return runCatching {
            val layout = StartModeSurvivalMapSupport.currentLayout(room)
            markSurvivalAiFlag(layout)
            val localPlayer = repairEngineLocalPlayer(layout, room)

            val engine = StartModeSurvivalMissionAccessSupport.gameEngineOrNull()
                ?: return@runCatching StartModeSurvivalRuntimeSnapshot(error = "game_engine_not_ready")
            val mission = engine.ce ?: return@runCatching StartModeSurvivalRuntimeSnapshot(engineReady = true)
            val missionSurvival = StartModeSurvivalMissionAccessSupport.getBooleanField(mission, "k")
            val customWaves = StartModeSurvivalMissionAccessSupport.getBooleanField(mission, "N")
            var ended = StartModeSurvivalMissionAccessSupport.getBooleanField(mission, "m")
            val waveIndex = StartModeSurvivalMissionAccessSupport.getIntField(mission, "r")
            var waveCountdownFrames = StartModeSurvivalMissionAccessSupport.getFloatField(mission, "z")
            val attackPoints = StartModeSurvivalMissionAccessSupport.getListSize(mission, "D")
            val aiStates = layout.aiSlots.map { slot ->
                val ai = n.k(slot.playerIndex)
                StartModeSurvivalAiRuntimeState(slot.playerIndex, ai != null, ai?.U, ai?.r)
            }
            val primaryAi = aiStates.firstOrNull()
            val elapsed = currentElapsedSeconds()
            val humanMainBuildings = localPlayer.humanMainBuildings
            val primaryHumanMainBuildings = StartModeSurvivalRuntimeQuerySupport.countMainBuildingsForPlayerIndex(layout.primaryHumanSlot.playerIndex)

            if (missionSurvival && ended && shouldKeepSurvivalMissionRunning(humanMainBuildings, layout.profile)) {
                StartModeSurvivalMissionAccessSupport.setBooleanField(mission, "m", false)
                ended = false
                missionDefeatStateRepairs.incrementAndGet()
                if (!customWaves && waveCountdownFrames <= 0f) {
                    waveCountdownFrames = if (waveIndex <= 0) DEFAULT_FIRST_WAVE_DELAY_FRAMES else DEFAULT_NEXT_WAVE_DELAY_FRAMES
                    StartModeSurvivalMissionAccessSupport.setFloatField(mission, "z", waveCountdownFrames)
                    firstWaveTimerRepairs.incrementAndGet()
                }
                Log.warn("[StartMode] Survival defeat state was cleared because this survival map is still protected. localPlayer=${localPlayer.toDiagnosticString()}, winCondition=${layout.profile.winCondition.ifBlank { "default" }}")
            }

            if (
                missionSurvival &&
                !customWaves &&
                !ended &&
                waveIndex <= 0 &&
                attackPoints > 0 &&
                waveCountdownFrames <= 0f &&
                lastGameStartSecond > 0 &&
                elapsed in 0..StartModeSurvivalRuntimeGuardSupport.STARTUP_RUNTIME_REPAIR_SECONDS
            ) {
                StartModeSurvivalMissionAccessSupport.setFloatField(mission, "z", DEFAULT_FIRST_WAVE_DELAY_FRAMES)
                firstWaveTimerRepairs.incrementAndGet()
                return@runCatching StartModeSurvivalRuntimeSnapshot(
                    engineReady = true,
                    missionReady = true,
                    missionSurvival = true,
                    customWaves = false,
                    ended = false,
                    waveIndex = waveIndex,
                    waveCountdownFrames = DEFAULT_FIRST_WAVE_DELAY_FRAMES,
                    attackPoints = attackPoints,
                    localPlayerIndex = localPlayer.controlledPlayerIndex,
                    localPlayerAllyGroup = localPlayer.controlledAllyGroup,
                    localPlayerBeforeRepair = localPlayer.beforePlayerIndex,
                    controlledMainBuildings = localPlayer.controlledMainBuildings,
                    humanMainBuildings = humanMainBuildings,
                    primaryHumanMainBuildings = primaryHumanMainBuildings,
                    survivalAiPresent = aiStates.all { it.present },
                    survivalAiFlag = primaryAi?.survivalFlag,
                    survivalAiTeam = primaryAi?.allyGroup,
                    survivalAiStates = aiStates.joinToString(",") { it.toDiagnosticString() }
                )
            }

            StartModeSurvivalRuntimeSnapshot(
                engineReady = true,
                missionReady = true,
                missionSurvival = missionSurvival,
                customWaves = customWaves,
                ended = ended,
                waveIndex = waveIndex,
                waveCountdownFrames = waveCountdownFrames,
                attackPoints = attackPoints,
                localPlayerIndex = localPlayer.controlledPlayerIndex,
                localPlayerAllyGroup = localPlayer.controlledAllyGroup,
                localPlayerBeforeRepair = localPlayer.beforePlayerIndex,
                controlledMainBuildings = localPlayer.controlledMainBuildings,
                humanMainBuildings = humanMainBuildings,
                primaryHumanMainBuildings = primaryHumanMainBuildings,
                survivalAiPresent = aiStates.all { it.present },
                survivalAiFlag = primaryAi?.survivalFlag,
                survivalAiTeam = primaryAi?.allyGroup,
                survivalAiStates = aiStates.joinToString(",") { it.toDiagnosticString() }
            )
        }.getOrElse { error ->
            runtimeRepairFailures.incrementAndGet()
            StartModeSurvivalRuntimeSnapshot(error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun runtimeSnapshot(): StartModeSurvivalRuntimeSnapshot {
        if (!enabled()) {
            return StartModeSurvivalRuntimeSnapshot()
        }
        return repairRuntimeSurvivalState().also {
            recordRuntimeSnapshot(it)
        }
    }

    private fun recordRuntimeSnapshot(snapshot: StartModeSurvivalRuntimeSnapshot) {
        lastRuntimeSnapshot = snapshot
    }

    private fun markSurvivalAiFlag(layout: StartModeSurvivalMapSupport.SurvivalLayout = StartModeSurvivalMapSupport.currentLayout()) {
        runCatching {
            layout.aiSlots.forEach { slot ->
                val ai = n.k(slot.playerIndex) ?: return@forEach
                if (ai.r != slot.allyGroup) {
                    ai.r = slot.allyGroup
                }
                if (!ai.U) {
                    ai.U = true
                    survivalAiFlagRepairs.incrementAndGet()
                }
            }
        }.onFailure {
            runtimeRepairFailures.incrementAndGet()
        }
    }

    private fun repairEngineLocalPlayer(
        layout: StartModeSurvivalMapSupport.SurvivalLayout,
        room: ServerRoom? = null
    ): StartModeSurvivalControlledPlayerState {
        return runCatching {
            val engine = StartModeSurvivalMissionAccessSupport.gameEngineOrNull()
                ?: return@runCatching StartModeSurvivalControlledPlayerState(error = "game_engine_not_ready")
            val before = engine.bs
            val candidates = layout.humanSlots
                .mapNotNull { slot -> n.k(slot.playerIndex) }
                .filter { candidate -> candidate.k !in layout.reservedPlayerIndexes }
            val onlineHumanIndexes = room?.playerManage?.playerGroup
                ?.filter { !it.isAi }
                ?.map { it.index }
                ?.toSet()
                .orEmpty()
            val preferred = candidates.firstOrNull { StartModeSurvivalRuntimeQuerySupport.countMainBuildingsForPlayer(it) > 0 }
                ?: candidates.firstOrNull { it.k in onlineHumanIndexes }
                ?: candidates.firstOrNull()
            if (preferred != null && engine.bs !== preferred) {
                engine.bs = preferred
                engineLocalPlayerRepairs.incrementAndGet()
                logSurvivalRepair("[StartMode] Survival local player repaired: ${before?.k ?: "null"} -> ${preferred.k}")
            }

            val controlled = engine.bs
            StartModeSurvivalControlledPlayerState(
                beforePlayerIndex = before?.k,
                controlledPlayerIndex = controlled?.k,
                controlledAllyGroup = controlled?.r,
                controlledMainBuildings = StartModeSurvivalRuntimeQuerySupport.countMainBuildingsForPlayer(controlled),
                humanMainBuildings = StartModeSurvivalRuntimeQuerySupport.countHumanMainBuildings(layout, primaryOnly = false)
            )
        }.getOrElse { error ->
            runtimeRepairFailures.incrementAndGet()
            StartModeSurvivalControlledPlayerState(error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun isHumanSideVerdict(room: ServerRoom, gameOverData: GameOverData): Boolean {
        val humanNames = StartModeSurvivalRuntimeQuerySupport.snapshotPlayers(room)
            .filter { !it.isAi }
            .map { it.name }
            .toSet()
        if (humanNames.isEmpty()) {
            return false
        }
        return gameOverData.winPlayerList.any { it in humanNames }
    }

    private fun observeEnemyUnits() {
        val humanGroup = StartModeSurvivalLayoutSupport.humanAllyGroup()
        val hasEnemy = StartModeSurvivalRuntimeQuerySupport.hasEnemyUnitOutsideHumanGroup(humanGroup)

        if (hasEnemy) {
            enemyUnitsObserved = true
            lastEnemyAliveSecond = Time.concurrentSecond()
        }
    }

    private fun repairEngineLocalPlayerFast(layout: StartModeSurvivalMapSupport.SurvivalLayout) {
        runEngineMutation("repair_survival_local_player") {
            repairEngineLocalPlayerFastOnGameThread(layout)
        }
    }

    private fun repairEngineLocalPlayerFastOnGameThread(layout: StartModeSurvivalMapSupport.SurvivalLayout) {
        runCatching {
            val preferred = n.k(layout.primaryHumanSlot.playerIndex)
                ?: layout.humanSlots.asSequence().mapNotNull { n.k(it.playerIndex) }.firstOrNull()
                ?: return
            val engine = StartModeSurvivalMissionAccessSupport.gameEngineOrNull() ?: return@runCatching
            if (engine.bs !== preferred) {
                val before = engine.bs
                engine.bs = preferred
                engineLocalPlayerRepairs.incrementAndGet()
                logSurvivalRepair("[StartMode] Survival local player fast-guard repaired: ${before?.k ?: "null"} -> ${preferred.k}")
            }
        }.onFailure {
            runtimeRepairFailures.incrementAndGet()
        }
    }

    private fun logSurvivalRepair(message: String) {
        StartModeConsoleOutputSupport.print(
            StartModeConsoleOutputSupport.Category.SURVIVAL_REPAIR,
            message
        )
    }

    private fun shouldKeepSurvivalMissionRunning(runtime: StartModeSurvivalRuntimeSnapshot): Boolean {
        if (!runtime.missionSurvival || !runtime.ended) {
            return false
        }
        val profile = StartModeSurvivalMapSupport.currentProfile()
        return shouldKeepSurvivalMissionRunning(runtime.humanMainBuildings, profile)
    }

    private fun shouldKeepSurvivalMissionRunning(
        humanMainBuildings: Int,
        profile: StartModeSurvivalMapSupport.Profile
    ): Boolean {
        return humanMainBuildings > 0 || profile.winConditionNone
    }

    private fun startupGraceSeconds(): Int {
        return runCatching { Data.configStartMode.survivalGameOverGraceSeconds }
            .getOrDefault(180)
            .coerceIn(0, 900)
    }

    private fun enemyClearConfirmSeconds(): Int {
        return runCatching { Data.configStartMode.survivalEnemyClearConfirmSeconds }
            .getOrDefault(45)
            .coerceIn(0, 300)
    }

    private fun elapsedSeconds(room: ServerRoom): Int {
        val start = if (room.startTime > 0) room.startTime else lastGameStartSecond
        if (start <= 0) {
            return 0
        }
        return (Time.concurrentSecond() - start).coerceAtLeast(0)
    }

    private fun currentElapsedSeconds(): Int {
        if (lastGameStartSecond <= 0) {
            return 0
        }
        return (Time.concurrentSecond() - lastGameStartSecond).coerceAtLeast(0)
    }

    private fun lastEnemyAliveAgoSeconds(): Int {
        if (lastEnemyAliveSecond <= 0) {
            return -1
        }
        return (Time.concurrentSecond() - lastEnemyAliveSecond).coerceAtLeast(0)
    }

}
