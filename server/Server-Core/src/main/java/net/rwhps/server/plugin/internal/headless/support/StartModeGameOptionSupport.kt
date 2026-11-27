/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.headless.core.link.FullGameSyncLink
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.log.Log
import java.util.Locale

internal object StartModeGameOptionSupport {
    private const val START_PLAYER_DEFAULT_INITIAL_DELAY_MILLIS = 1_000L
    private const val START_PLAYER_DEFAULT_SYNC_TIMEOUT_MILLIS = 30_000L
    private const val START_PLAYER_DEFAULT_RETRY_INTERVAL_MILLIS = 1_000L
    private const val MAP_ENGINE_NOT_READY = "map_engine_not_ready"

    @Volatile
    private var playerSelectedStartingCreditsAmount: Int? = null

    private val noNukesSelectionState = StartModeNoNukesSelectionState()

    @Volatile
    private var lastStartNoNukesDecision: StartModeNoNukesDecision? = null

    private enum class FogMode(val configValue: String, val engineValue: Int) {
        OFF("off", 0),
        BASIC("basic", 1),
        LINE_OF_SIGHT("lineOfSight", 2)
    }

    private data class CreditPreset(
        val engineValue: Int,
        val amount: Int
    )

    private val creditPresets = listOf(
        CreditPreset(1, 0),
        CreditPreset(2, 1000),
        CreditPreset(3, 2000),
        CreditPreset(0, 4000),
        CreditPreset(4, 5000),
        CreditPreset(5, 10000),
        CreditPreset(6, 50000),
        CreditPreset(7, 100000),
        CreditPreset(8, 200000)
    )

    internal data class LobbyOptionStateSnapshot(
        val selectedStartingCreditsAmount: Int?,
        val hostNoNukesSelection: StartModeNoNukesHostSelection?
    )

    internal fun captureLobbyOptionState(): LobbyOptionStateSnapshot {
        return LobbyOptionStateSnapshot(
            selectedStartingCreditsAmount = playerSelectedStartingCreditsAmount,
            hostNoNukesSelection = noNukesSelectionState.recordedHostSelection()
        )
    }

    internal fun restoreLobbyOptionState(snapshot: LobbyOptionStateSnapshot) {
        playerSelectedStartingCreditsAmount = snapshot.selectedStartingCreditsAmount
        noNukesSelectionState.clearForNewLobby()
        snapshot.hostNoNukesSelection?.let {
            noNukesSelectionState.recordHostSelection(it.noNukes, it.hostIdentity)
        }
    }

    internal data class EngineLobbyOptions(val credits: Int, val nukes: Boolean, val fog: Int, val income: Float, val maxUnit: Int)

    internal fun captureEngineLobbyOptions(): EngineLobbyOptions? {
        if (!HeadlessModuleManage.initHPS()) return null
        val module = HeadlessModuleManage.hps
        var result: EngineLobbyOptions? = null
        check(module.gameFunction.suspendMainThreadOperations {
            val data = module.gameLinkServerData
            result = EngineLobbyOptions(data.credits, data.nukes, data.fog, data.income, data.maxUnit)
        }) { "Cannot snapshot current engine lobby settings" }
        return result
    }

    internal fun restoreEngineLobbyOptions(options: EngineLobbyOptions?): Boolean {
        if (options == null) return true
        val module = HeadlessModuleManage.hps
        return module.gameFunction.suspendMainThreadOperations {
            val data = module.gameLinkServerData
            data.credits = options.credits; data.nukes = options.nukes; data.fog = options.fog
            data.income = options.income; data.maxUnit = options.maxUnit
            module.gameLinkFunction.refreshLobbyServerInfo()
        }
    }

    fun applyConfiguredLobbyDefaults(module: AbstractGameModule) {
        val data = module.gameLinkServerData
        playerSelectedStartingCreditsAmount = null
        noNukesSelectionState.clearForNewLobby()
        data.credits = configuredCreditPreset().engineValue
        data.nukes = Data.configServer.disableNukes
        data.fog = configuredFogValue()
    }

    fun applyConfiguredLobbyDefaultsToCurrentHeadless(): Boolean {
        if (!HeadlessModuleManage.initHPS()) return true
        val module = HeadlessModuleManage.hps
        var applied = false
        val operationSucceeded = module.gameFunction.suspendMainThreadOperations {
            if (module.room.isStartGame || module.room.isLobbyPlayerSyncBlocked() || StartModeAutoStartSupport.isStartOperationInProgress()) {
                return@suspendMainThreadOperations
            }
            val currentEngineNoNukes = module.gameLinkServerData.nukes
            val effectiveNoNukes = noNukesSelectionState.resolve(
                currentHostIdentity = currentVisibleHostIdentity(module.room),
                engineNoNukes = currentEngineNoNukes,
                configuredNoNukes = Data.configServer.disableNukes
            )
            playerSelectedStartingCreditsAmount = null
            module.gameLinkServerData.credits = configuredCreditPreset().engineValue
            module.gameLinkServerData.nukes = effectiveNoNukes.noNukes
            module.gameLinkServerData.fog = configuredFogValue()
            module.gameLinkServerData.income = Data.configServer.defIncome
            module.gameLinkServerData.maxUnit = Data.configServer.maxUnit
            applied = module.gameLinkFunction.refreshLobbyServerInfo()
        }
        if (!operationSucceeded) {
            Log.warn("[StartMode] Failed to apply configured lobby defaults on the game thread")
        }
        return operationSucceeded && applied
    }

    fun applyEffectiveNoNukesBeforeStart(module: AbstractGameModule) {
        val engineNoNukes = module.gameLinkServerData.nukes
        val currentHostIdentity = currentVisibleHostIdentity(module.room)
        val decision = noNukesSelectionState.resolve(
            currentHostIdentity = currentHostIdentity,
            engineNoNukes = engineNoNukes,
            configuredNoNukes = Data.configServer.disableNukes
        )
        module.gameLinkServerData.nukes = decision.noNukes
        lastStartNoNukesDecision = decision
        Log.clog(
            "[StartMode] Pre-start no-nukes resolved: effective=${decision.noNukes}, " +
                    "source=${decision.source.diagnosticValue}, configured=${Data.configServer.disableNukes}, " +
                    "hostSelection=${renderNullableBoolean(noNukesSelectionState.hostSelection(currentHostIdentity))}, " +
                    "hostSelectionOwnerCurrent=${renderNullableBoolean(isRecordedSelectionOwnedBy(currentHostIdentity))}, " +
                    "engineBefore=$engineNoNukes"
        )
    }

    fun applyConfiguredStartPlayerDefaults(module: AbstractGameModule) {
        val targetCredits = currentDisplayedCreditsAmount()
        if (!requiresExactStartCreditSync(targetCredits)) {
            return
        }
        Threads.newThreadOther(Runnable {
            Thread.sleep(START_PLAYER_DEFAULT_INITIAL_DELAY_MILLIS)
            var lastFailure: Throwable? = null
            var attempt = 0
            val deadline = System.currentTimeMillis() + START_PLAYER_DEFAULT_SYNC_TIMEOUT_MILLIS
            while (module.room.isStartGame && System.currentTimeMillis() <= deadline) {
                attempt += 1
                val failure = applyConfiguredStartPlayerDefaultsOnce(module, targetCredits)
                if (failure == null) {
                    if (attempt > 1) {
                        Log.clog("[StartMode] Start player defaults synced after map engine became ready.")
                    }
                    return@Runnable
                }
                lastFailure = failure

                if (isTransientSyncNotReady(failure)) {
                    if (attempt == 1) {
                        Log.clog("[StartMode] Waiting for the map engine to finish initializing before syncing start player defaults.")
                    }
                } else {
                    Log.warn("[StartMode] Start player defaults sync failed on attempt $attempt. Retrying.", failure)
                    if (attempt == 1) {
                        module.room.call.sendSystemMessage("CNKD: Start player defaults sync is delayed, retrying automatically.")
                    }
                }

                val remainingMillis = deadline - System.currentTimeMillis()
                if (remainingMillis > 0L) {
                    Thread.sleep(START_PLAYER_DEFAULT_RETRY_INTERVAL_MILLIS.coerceAtMost(remainingMillis))
                }
            }

            if (module.room.isStartGame && lastFailure != null) {
                if (isTransientSyncNotReady(lastFailure)) {
                    Log.warn("[StartMode] Start player defaults sync skipped because the map engine was not ready within ${START_PLAYER_DEFAULT_SYNC_TIMEOUT_MILLIS / 1000}s. Game continues normally.")
                } else {
                    Log.warn("[StartMode] Start player defaults sync still failed after retries.", lastFailure)
                    module.room.call.sendSystemMessage("CNKD: Start player defaults sync failed. Please check map readiness and room state.")
                }
            }
        })
    }

    fun currentDisplayedCreditsAmount(): Int {
        playerSelectedStartingCreditsAmount?.let {
            return it
        }
        if (!HeadlessModuleManage.initHPS()) {
            return configuredStartingCreditsAmount()
        }

        val currentEngineValue = HeadlessModuleManage.hps.gameLinkServerData.credits
        val configuredPreset = configuredCreditPreset()
        return if (currentEngineValue == configuredPreset.engineValue) {
            configuredStartingCreditsAmount()
        } else {
            creditPresets.firstOrNull { it.engineValue == currentEngineValue }?.amount ?: configuredStartingCreditsAmount()
        }
    }

    fun recordLobbyCreditsChanged(amount: Int) {
        playerSelectedStartingCreditsAmount = amount
    }

    fun recordHostNoNukesChanged(player: PlayerHess, room: ServerRoom, noNukes: Boolean): Boolean {
        val visibleHost = StartModeRoomPermissionSupport.visibleHostPlayer(room) ?: return false
        val sameHost = visibleHost === player || hostIdentity(visibleHost) == hostIdentity(player)
        if (!sameHost) {
            return false
        }
        noNukesSelectionState.recordHostSelection(noNukes, hostIdentity(visibleHost))
        return true
    }

    fun currentNoNukes(): Boolean {
        val module = if (HeadlessModuleManage.initHPS()) {
            HeadlessModuleManage.hps
        } else {
            null
        }
        return noNukesSelectionState.resolve(
            currentHostIdentity = module?.room?.let(::currentVisibleHostIdentity),
            engineNoNukes = module?.gameLinkServerData?.nukes,
            configuredNoNukes = Data.configServer.disableNukes
        ).noNukes
    }

    fun currentFogValue(): Int {
        return if (HeadlessModuleManage.initHPS()) {
            HeadlessModuleManage.hps.gameLinkServerData.fog
        } else {
            configuredFogMode().engineValue
        }
    }

    private fun configuredStartingCreditsAmount(): Int = Data.configServer.defaultStartingCreditsAmount.coerceAtLeast(0)

    private fun configuredFogValue(): Int = configuredFogMode().engineValue

    private fun configuredFogMode(): FogMode {
        return when (Data.configServer.defaultFogMode.trim().lowercase()) {
            FogMode.OFF.configValue.lowercase(), "0", "false" -> FogMode.OFF
            FogMode.BASIC.configValue.lowercase(), "1" -> FogMode.BASIC
            FogMode.LINE_OF_SIGHT.configValue.lowercase(), "line_of_sight", "line-of-sight", "lineofsight", "2", "true" -> FogMode.LINE_OF_SIGHT
            else -> FogMode.OFF
        }
    }

    private fun configuredCreditPreset(): CreditPreset {
        val desiredAmount = configuredStartingCreditsAmount()
        return creditPresets.lastOrNull { it.amount <= desiredAmount } ?: creditPresets.first()
    }

    private fun requiresExactStartCreditSync(amount: Int): Boolean {
        return creditPresets.none { it.amount == amount }
    }

    private fun applyConfiguredStartPlayerDefaultsOnce(module: AbstractGameModule, targetCredits: Int): Throwable? {
        val fullGameSync = module.gameLinkFunction as? FullGameSyncLink
            ?: return IllegalStateException(MAP_ENGINE_NOT_READY)
        if (!fullGameSync.canSyncFullGameState()) {
            return IllegalStateException(MAP_ENGINE_NOT_READY)
        }

        val operationSucceeded = module.gameFunction.suspendMainThreadOperations {
            module.room.playerManage.playerAll.eachAll {
                it.credits = targetCredits
            }
        }
        if (!operationSucceeded) {
            return IllegalStateException("game_thread_operation_failed")
        }

        val syncResult = fullGameSync.syncFullGameState()
        val syncFailure = syncResult.error ?: if (!syncResult.success) {
            IllegalStateException(MAP_ENGINE_NOT_READY)
        } else null
        syncFailure?.takeIf { !isTransientSyncNotReady(it) }?.let {
            Log.warn("[StartMode] Failed to sync start player defaults", it)
        }
        return syncFailure
    }

    private fun isTransientSyncNotReady(error: Throwable): Boolean {
        return error.message in setOf(
            MAP_ENGINE_NOT_READY,
            "full_game_sync_requires_started_room",
            "full_game_sync_busy"
        )
    }

    fun buildDiagnostics(): List<String> {
        val module = if (HeadlessModuleManage.initHPS()) {
            HeadlessModuleManage.hps
        } else {
            null
        }
        val currentHostIdentity = module?.room?.let(::currentVisibleHostIdentity)
        val recordedHostSelection = noNukesSelectionState.recordedHostSelection()
        val effective = noNukesSelectionState.resolve(
            currentHostIdentity = currentHostIdentity,
            engineNoNukes = module?.gameLinkServerData?.nukes,
            configuredNoNukes = Data.configServer.disableNukes
        )
        val lastStart = lastStartNoNukesDecision
        return listOf(
            "========== GAME OPTIONS ==========",
            "noNukes.configDefault=${Data.configServer.disableNukes}",
            "noNukes.hostSelection=${renderNullableBoolean(noNukesSelectionState.hostSelection(currentHostIdentity))}",
            "noNukes.hostSelectionRecorded=${renderNullableBoolean(recordedHostSelection?.noNukes)}",
            "noNukes.hostSelectionOwnerCurrent=${renderNullableBoolean(recordedHostSelection?.let { isRecordedSelectionOwnedBy(currentHostIdentity) })}",
            "noNukes.engineLobby=${renderNullableBoolean(module?.gameLinkServerData?.nukes)}",
            "noNukes.effectiveStart=${effective.noNukes}",
            "noNukes.effectiveSource=${effective.source.diagnosticValue}",
            "noNukes.lastStart=${renderNullableBoolean(lastStart?.noNukes)}",
            "noNukes.lastStartSource=${lastStart?.source?.diagnosticValue ?: "<none>"}"
        )
    }

    private fun renderNullableBoolean(value: Boolean?): String = value?.toString() ?: "<unset>"

    private fun currentVisibleHostIdentity(room: ServerRoom): String? {
        return StartModeRoomPermissionSupport.visibleHostPlayer(room)?.let(::hostIdentity)
    }

    private fun hostIdentity(player: PlayerHess): String {
        val connectId = runCatching { player.connectHexID.trim() }.getOrDefault("")
        if (connectId.isNotBlank()) {
            return "hex:${connectId.lowercase(Locale.ROOT)}"
        }
        return "session:${player.index}:${System.identityHashCode(player)}"
    }

    private fun isRecordedSelectionOwnedBy(currentHostIdentity: String?): Boolean? {
        val recorded = noNukesSelectionState.recordedHostSelection() ?: return null
        return currentHostIdentity != null && recorded.hostIdentity == currentHostIdentity
    }

    fun logConfiguredDefaults() {
        Log.clog(
            "[StartMode] Lobby defaults applied: credits=${configuredStartingCreditsAmount()}, configuredNoNukes=${Data.configServer.disableNukes}, fog=${configuredFogMode().configValue}"
        )
    }
}
