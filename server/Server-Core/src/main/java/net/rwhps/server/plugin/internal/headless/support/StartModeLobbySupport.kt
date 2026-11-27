/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanStartModeConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.findMethod
import net.rwhps.server.util.inline.toClass
import net.rwhps.server.util.log.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object StartModeLobbySupport {
    private const val SURVIVAL_AI_MARKER = "StartMode.SurvivalAI"
    const val SPECTATOR_TEAM = -3

    private val lockedTeams = ConcurrentHashMap<String, Int>()
    private val lockedPositions = ConcurrentHashMap<String, Int>()
    private val lobbyRefreshForwardedCount = AtomicLong(0L)
    private val lobbyRefreshSkippedCount = AtomicLong(0L)
    private val lobbyPacketForwardedCount = AtomicLong(0L)
    private val lobbyPacketDroppedCount = AtomicLong(0L)

    @Volatile
    private var survivalWarningsPrinted = false

    fun onServerStarted() {
        if (!HeadlessModuleManage.initHPS()) {
            return
        }

        if (survivalModeEnabled()) {
            printSurvivalConflictWarnings()
            enforceLobbyRules()
        } else {
            survivalWarningsPrinted = false
        }
    }

    fun onPlayerJoin(player: PlayerHess) {
        if (!HeadlessModuleManage.initHPS()) {
            return
        }

        val key = playerStateKey(player)
        if (Data.configStartMode.disablePositionChange) {
            lockedPositions.putIfAbsent(key, player.index)
        }

        if (survivalModeEnabled()) {
            lockedTeams[key] = StartModeSurvivalLayoutSupport.humanAllyGroup(HeadlessModuleManage.hps.room)
        } else if (Data.configStartMode.enableFreeForAllMode) {
            lockedTeams[key] = player.index
        } else if (Data.configStartMode.disableTeamChange || Data.configStartMode.enableAlternateTeamsOnJoin) {
            lockedTeams[key] = desiredJoinTeam(player)
        }

        enforceLobbyRules()
    }

    fun updateLockedTeam(player: PlayerHess, newTeam: Int) {
        val key = playerStateKey(player)
        if (isSpectatorTeam(newTeam)) {
            // Fix: When player moves to spectator, remove team lock so enforceLobbyRules
            // won't pull them back to their previous team
            lockedTeams.remove(key)
        } else {
            lockedTeams[key] = newTeam
        }
    }

    fun onPlayerLeave(player: PlayerHess) {
        forgetPlayer(player)
        if (!HeadlessModuleManage.initHPS()) {
            return
        }
    }

    fun forgetPlayer(player: PlayerHess) {
        val key = playerStateKey(player)
        lockedTeams.remove(key)
        lockedPositions.remove(key)
        StartModeLatencyHeartbeatSupport.forgetPlayer(player)
    }

    fun resetRuntimeState() {
        lockedTeams.clear()
        lockedPositions.clear()
        StartModeLatencyHeartbeatSupport.resetRuntimeState()
    }

    fun canChangeTeam(): Boolean {
        return !survivalModeEnabled() &&
            !Data.configStartMode.enableFreeForAllMode &&
            !Data.configStartMode.disableTeamChange &&
            !alternateTeamModeEnabled()
    }

    fun canChangeTeam(player: PlayerHess): Boolean {
        if (survivalModeEnabled()) {
            return false
        }
        if (Data.configStartMode.enableFreeForAllMode) {
            return false
        }
        if (
            Data.configStartMode.adminCanOverrideTeamRules &&
            StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)
        ) {
            return true
        }
        if (alternateTeamModeEnabled()) {
            return false
        }
        return canChangeTeam()
    }

    fun canChangeToSpectator(player: PlayerHess): Boolean {
        return canChangeToSpectator(Data.configStartMode, StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player))
    }

    fun forcedTeamForPosition(site: Int): Int? {
        return when {
            survivalModeEnabled() -> StartModeSurvivalLayoutSupport.humanAllyGroup(HeadlessModuleManage.hps.room)
            Data.configStartMode.enableFreeForAllMode -> site
            alternateTeamModeEnabled() -> standardTeamForPosition(site)
            else -> null
        }
    }

    internal fun canChangeToSpectator(config: BeanStartModeConfig, effectiveAdmin: Boolean): Boolean {
        // FFA, alternate teams and survival assign combat teams; they do not prohibit observing.
        // Map lock is deliberately independent of both placement locks.
        val positionAllowed = !config.disablePositionChange || (effectiveAdmin && config.adminCanOverridePositionRules)
        val teamAllowed = !config.disableTeamChange || (effectiveAdmin && config.adminCanOverrideTeamRules)
        return positionAllowed && teamAllowed
    }

    fun isSpectatorTeam(team: Int): Boolean {
        return team == SPECTATOR_TEAM
    }

    fun canChangePosition(): Boolean {
        return !Data.configStartMode.disablePositionChange
    }

    fun canChangePosition(player: PlayerHess): Boolean {
        return (
            Data.configStartMode.adminCanOverridePositionRules &&
                StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)
            ) || canChangePosition()
    }

    fun updateLockedPosition(player: PlayerHess, newPosition: Int) {
        if (Data.configStartMode.disablePositionChange) {
            lockedPositions[playerStateKey(player)] = newPosition
        }
    }

    fun teamChangeBlockedReason(): String {
        return when {
            survivalModeEnabled() -> "Survival mode is enabled. Human players stay on the configured human ally group while the map survival AI uses ${StartModeSurvivalLayoutSupport.reservedAiDescription()}."
            Data.configStartMode.enableFreeForAllMode -> "FFA mode is enabled. Each player must stay on a unique team."
            alternateTeamModeEnabled() -> "Standard team mode is enabled. Odd slots stay on team A and even slots stay on team B."
            Data.configStartMode.disableTeamChange -> "Team changes are disabled by StartModeConfig."
            else -> ""
        }
    }

    fun positionChangeBlockedReason(): String {
        return if (Data.configStartMode.disablePositionChange) {
            "Position changes are disabled by StartModeConfig."
        } else {
            ""
        }
    }

    fun buildDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== LOBBY =========="
        lines += "headlessReady=${HeadlessModuleManage.initHPS()}"
        lines += "survivalMode=${survivalModeEnabled()}"
        lines += "freeForAllMode=${Data.configStartMode.enableFreeForAllMode}"
        lines += "alternateTeamsOnJoin=${Data.configStartMode.enableAlternateTeamsOnJoin}"
        lines += "disableTeamChange=${Data.configStartMode.disableTeamChange}"
        lines += "disablePositionChange=${Data.configStartMode.disablePositionChange}"
        lines += "adminCanOverrideTeamRules=${Data.configStartMode.adminCanOverrideTeamRules}"
        lines += "adminCanOverridePositionRules=${Data.configStartMode.adminCanOverridePositionRules}"
        lines += "canChangeTeam=${canChangeTeam()}"
        lines += "canChangePosition=${canChangePosition()}"
        lines += "lockedTeams=${lockedTeams.size}"
        lines += "lockedPositions=${lockedPositions.size}"
        lines += "syncOptimization=${lobbySyncOptimizationEnabled()}"
        lines += "sync.refreshHeartbeatMillis=${lobbyRefreshHeartbeatMillis()}"
        lines += "sync.packetHeartbeatMillis=${lobbyPacketHeartbeatMillis()}"
        lines += "sync.gamePacketFilter=${lobbyGameSyncPacketFilterEnabled()}"
        lines += "sync.gamePacketHeartbeatMillis=${lobbyGameSyncPacketHeartbeatMillis()}"
        lines += "sync.gamePacketJoinGraceMillis=${lobbyGameSyncPacketJoinGraceMillis()}"
        lines += StartModeLatencyHeartbeatSupport.buildDiagnostics()
        lines += "sync.refreshForwarded=${lobbyRefreshForwardedCount.get()}"
        lines += "sync.refreshSkipped=${lobbyRefreshSkippedCount.get()}"
        lines += "sync.packetForwarded=${lobbyPacketForwardedCount.get()}"
        lines += "sync.packetDropped=${lobbyPacketDroppedCount.get()}"
        lines += StartModePlayerExperienceSupport.buildDiagnostics()
        lines += StartModeGameOverReturnSupport.buildDiagnostics()
        lines += StartModeSurvivalSupport.buildDiagnostics()
        if (HeadlessModuleManage.initHPS()) {
            val room = HeadlessModuleManage.hps.room
            val presence = room.playerManage.buildPlayerPresenceSnapshot()
            lines += "roomStarted=${room.isStartGame}"
            lines += "mixPlayer=${room.isMixPlayer}"
            lines += "players.connected=${presence.connectedOccupants}"
            lines += "players.reserved=${presence.reservedOccupants}"
            lines += "players.graceProtectedHumans=${presence.graceProtectedHumanCount}"
            lines += "players.reconnectGraceHumans=${presence.reconnectGraceHumanCount}"
        }
        return lines
    }

    fun enforceLobbyRules(forceSync: Boolean = true): Boolean {
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }

        val module = HeadlessModuleManage.hps
        if (module.room.isBattleRoomLobbyRecoveryInProgress() ||
            StartModeAutoStartSupport.isStartOperationInProgress()
        ) {
            return false
        }
        var changed = false
        val operationSucceeded = module.gameFunction.suspendMainThreadOperations {
            changed = enforceLobbyRulesOnGameThread(module, forceSync)
        }
        if (!operationSucceeded) {
            Log.warn("[StartModeLobby] Failed to enforce lobby rules on the game thread")
        }
        return operationSucceeded && changed
    }

    private fun enforceLobbyRulesOnGameThread(module: AbstractGameModule, forceSync: Boolean): Boolean {
        val room = module.room
        if (room.isStartGame || room.isBattleRoomLobbyRecoveryInProgress() ||
            StartModeAutoStartSupport.isStartOperationInProgress()
        ) {
            return false
        }

        var changed = false
        val players = snapshotPlayers(room)
        synchronized(module.gameLinkServerData.teamOperationsSyncObject) {
            if (survivalModeEnabled()) {
                changed = enforceSurvivalMode(module, players) || changed
            } else if (Data.configStartMode.disablePositionChange) {
                changed = enforceLockedPositions(module, players) || changed
            }

            changed = if (survivalModeEnabled()) {
                changed
            } else if (Data.configStartMode.enableFreeForAllMode) {
                enforceFfaTeams(players) || changed
            } else if (alternateTeamModeEnabled()) {
                enforceAlternateTeamsByPosition(players) || changed
            } else if (Data.configStartMode.disableTeamChange) {
                enforceLockedTeams(players) || changed
            } else {
                changed
            }
        }

        if (changed && forceSync) {
            refreshLobbyState(module)
        }
        return changed
    }

    fun lobbySyncOptimizationEnabled(): Boolean {
        return Data.configStartMode?.enableLobbySyncOptimization ?: true
    }

    fun lobbyRefreshHeartbeatMillis(): Long {
        return (Data.configStartMode?.lobbyRefreshHeartbeatMillis ?: 30_000).coerceIn(30_000, 120_000).toLong()
    }

    fun lobbyPacketHeartbeatMillis(): Long {
        return (Data.configStartMode?.lobbyPacketHeartbeatMillis ?: 30_000).coerceIn(30_000, 120_000).toLong()
    }

    fun lobbyGameSyncPacketFilterEnabled(): Boolean {
        return Data.configStartMode?.enableLobbyGameSyncPacketFilter ?: true
    }

    fun lobbyGameSyncPacketHeartbeatMillis(): Long {
        return (Data.configStartMode?.lobbyGameSyncPacketHeartbeatMillis ?: 3_000).coerceIn(500, 120_000).toLong()
    }

    fun lobbyGameSyncPacketJoinGraceMillis(): Long {
        return (Data.configStartMode?.lobbyGameSyncPacketJoinGraceMillis ?: 180_000).coerceIn(0, 600_000).toLong()
    }

    fun recordLobbyRefresh(forwarded: Boolean) {
        if (forwarded) {
            lobbyRefreshForwardedCount.incrementAndGet()
        } else {
            lobbyRefreshSkippedCount.incrementAndGet()
        }
    }

    fun recordLobbyPacket(forwarded: Boolean) {
        if (forwarded) {
            lobbyPacketForwardedCount.incrementAndGet()
        } else {
            lobbyPacketDroppedCount.incrementAndGet()
        }
    }

    private fun enforceLockedPositions(module: AbstractGameModule, players: Iterable<PlayerHess>): Boolean {
        var changed = false
        for (player in players) {
            val desiredPosition = lockedPositions.getOrPut(playerStateKey(player)) { player.index }
            if (player.index == desiredPosition) {
                continue
            }
            if (movePlayerToPositionInternal(module, player.index, desiredPosition)) {
                changed = true
            }
        }
        return changed
    }

    private fun enforceSurvivalMode(module: AbstractGameModule, players: Iterable<PlayerHess>): Boolean {
        var changed = false
        val room = module.room
        val layout = StartModeSurvivalLayoutSupport.layout(room)
        val reservedIndexes = layout.reservedPlayerIndexes
        val humanIndexes = layout.humanPlayerIndexes
        val humanAllyGroup = layout.humanAllyGroup
        room.isMixPlayer = false

        reservedIndexes.forEach { reservedIndex ->
            val reservedOccupant = room.playerManage.getPlayer(reservedIndex)
            if (reservedOccupant != null && !reservedOccupant.isAi) {
                val newPosition = StartModeSurvivalLayoutSupport.findAvailableHumanPosition(
                    room.playerManage.playerGroup,
                    reservedOccupant.index,
                    room
                )
                if (newPosition != null) {
                    changed = movePlayerToPositionInternal(module, reservedOccupant.index, newPosition) || changed
                } else {
                    Log.warn("[StartMode] Survival layout could not immediately move ${reservedOccupant.name} out of reserved AI slot ${reservedIndex + 1}; retrying after AI layout repair.")
                }
            }
        }

        val survivalAis = ensureSurvivalAis(module, layout)
        survivalAis.forEach { (desiredIndex, survivalAi) ->
            val desiredAllyGroup = StartModeSurvivalLayoutSupport.aiAllyGroupForIndex(desiredIndex, room) ?: layout.primaryAiSlot.allyGroup
            if (survivalAi.index != desiredIndex) {
                changed = movePlayerToPositionInternal(module, survivalAi.index, desiredIndex) || changed
            }
            if (survivalAi.team != desiredAllyGroup) {
                survivalAi.team = desiredAllyGroup
                changed = true
            }
            StartModeSurvivalSupport.prepareLobbySurvivalAi(survivalAi, room)
        }

        snapshotPlayers(room).forEach { player ->
            if (player.isAi) {
                if (player.index in reservedIndexes) {
                    StartModeSurvivalSupport.prepareLobbySurvivalAi(player, room)
                    return@forEach
                }
                if (player.index in humanIndexes) {
                    val newPosition = StartModeSurvivalLayoutSupport.findAvailableNonReservedPosition(
                        room.playerManage.playerGroup,
                        player.index,
                        room,
                        avoidHumanSlots = true
                    )
                    if (newPosition != null) {
                        changed = movePlayerToPositionInternal(module, player.index, newPosition) || changed
                    } else {
                        player.kickPlayer("Survival mode keeps map human start slots reserved for real players.")
                        changed = true
                    }
                }
                if (player.team == humanAllyGroup) {
                    player.team = layout.primaryAiSlot.allyGroup
                    changed = true
                }
                return@forEach
            }
        }

        assignHumansToSurvivalSlots(module, players, layout).also {
            changed = it || changed
        }
        return changed
    }

    private fun assignHumansToSurvivalSlots(
        module: AbstractGameModule,
        players: Iterable<PlayerHess>,
        layout: StartModeSurvivalMapSupport.SurvivalLayout
    ): Boolean {
        var changed = false
        val room = module.room
        val reservedIndexes = layout.reservedPlayerIndexes
        val humanIndexes = layout.humanPlayerIndexes
        val humanAllyGroup = layout.humanAllyGroup

        players.toList().filter { !it.isAi }.forEach { player ->
            if (isSpectatorTeam(player.team)) {
                return@forEach
            }

            lockedTeams[playerStateKey(player)] = humanAllyGroup
            if (player.team != humanAllyGroup) {
                player.team = humanAllyGroup
                changed = true
            }

            if (player.index in reservedIndexes) {
                val newPosition = StartModeSurvivalLayoutSupport.findAvailableHumanPosition(room.playerManage.playerGroup, player.index, room)
                if (newPosition != null) {
                    changed = movePlayerToPositionInternal(module, player.index, newPosition) || changed
                } else {
                    player.kickPlayer("Survival mode reserves position ${player.index + 1} for the map survival AI.")
                    changed = true
                }
            } else if (layout.hasDeclaredHumanSlots && player.index !in humanIndexes) {
                val newPosition = StartModeSurvivalLayoutSupport.findAvailableDeclaredHumanPosition(
                    room.playerManage.playerGroup,
                    player.index,
                    room
                )
                if (newPosition != null) {
                    changed = movePlayerToPositionInternal(module, player.index, newPosition) || changed
                }
            }
        }
        return changed
    }

    private fun enforceLockedTeams(players: Iterable<PlayerHess>): Boolean {
        var changed = false
        for (player in players) {
            val desiredTeam = lockedTeams.getOrPut(playerStateKey(player)) { player.team }
            if (isSpectatorTeam(player.team)) {
                continue
            }
            if (player.team != desiredTeam) {
                player.team = desiredTeam
                changed = true
            }
        }
        return changed
    }

    private fun enforceFfaTeams(players: Iterable<PlayerHess>): Boolean {
        var changed = false
        HeadlessModuleManage.hps.room.isMixPlayer = true
        for (player in players) {
            if (isSpectatorTeam(player.team)) {
                continue
            }
            val desiredTeam = player.index
            lockedTeams[playerStateKey(player)] = desiredTeam
            if (player.team != desiredTeam) {
                player.team = desiredTeam
                changed = true
            }
        }
        return changed
    }

    private fun enforceAlternateTeamsByPosition(players: Iterable<PlayerHess>): Boolean {
        var changed = false
        HeadlessModuleManage.hps.room.isMixPlayer = false
        for (player in players) {
            if (isSpectatorTeam(player.team)) {
                continue
            }
            // An administrator can intentionally apply a three-side
            // layout while alternate join assignment remains enabled. Keep
            // that explicit per-player result; new players still receive the
            // normal position-based assignment in onPlayerJoin().
            val key = playerStateKey(player)
            val desiredTeam = lockedTeams[key] ?: standardTeamForPosition(player.index)
            lockedTeams[playerStateKey(player)] = desiredTeam
            if (player.team != desiredTeam) {
                player.team = desiredTeam
                changed = true
            }
        }
        return changed
    }

    private fun desiredJoinTeam(joiningPlayer: PlayerHess): Int {
        if (!alternateTeamModeEnabled()) {
            return joiningPlayer.team
        }
        return standardTeamForPosition(joiningPlayer.index)
    }

    private fun ensureSurvivalAis(
        module: AbstractGameModule,
        layout: StartModeSurvivalMapSupport.SurvivalLayout
    ): Map<Int, PlayerHess> {
        val room = module.room
        val result = LinkedHashMap<Int, PlayerHess>()
        val usedPlayers = HashSet<PlayerHess>()
        val reservedIndexes = layout.reservedPlayerIndexes

        layout.aiSlots.forEach { slot ->
            if (slot.playerIndex !in 0 until Data.configServer.maxPlayer) {
                Log.warn("[StartMode] Survival AI slot ${slot.playerIndex + 1} exceeds maxPlayer=${Data.configServer.maxPlayer}; this map may not start correctly.")
                return@forEach
            }

            val candidate = room.playerManage.getPlayer(slot.playerIndex)
                ?.takeIf { it.isAi }
                ?: room.playerManage.playerGroup.firstOrNull {
                    it.isAi && it !in usedPlayers && it.getData(SURVIVAL_AI_MARKER, false)
                }
                ?: room.playerManage.playerGroup.firstOrNull {
                    it.isAi && it !in usedPlayers && it.index !in reservedIndexes
                }
                ?: room.playerManage.addAI()

            candidate?.let {
                it.addData(SURVIVAL_AI_MARKER, true)
                usedPlayers += it
                result[slot.playerIndex] = it
            }
        }

        return result
    }

    private fun survivalModeEnabled(): Boolean = Data.configStartMode.enableSurvivalMapMode

    private fun alternateTeamModeEnabled(): Boolean {
        return Data.configStartMode.enableAlternateTeamsOnJoin &&
            !Data.configStartMode.enableFreeForAllMode &&
            !survivalModeEnabled()
    }

    private fun standardTeamForPosition(index: Int): Int {
        return if (((index + 1) % 2) == 1) 0 else 1
    }

    private fun snapshotPlayers(room: net.rwhps.server.game.room.ServerRoom): List<PlayerHess> {
        val result = ArrayList<PlayerHess>()
        room.playerManage.playerGroup.eachAll { player ->
            result += player
        }
        return result
    }

    private fun playerStateKey(player: PlayerHess): String {
        val connectId = runCatching { player.connectHexID.trim() }.getOrDefault("")
        if (connectId.isNotBlank()) {
            return connectId
        }

        val name = runCatching { player.name.trim() }.getOrDefault("")
        return if (name.isNotBlank()) {
            "name:$name"
        } else {
            "slot:${player.index}"
        }
    }

    private fun printSurvivalConflictWarnings() {
        if (survivalWarningsPrinted) {
            return
        }
        survivalWarningsPrinted = true

        val layout = StartModeSurvivalLayoutSupport.layout(HeadlessModuleManage.hps.room)
        logSurvivalRepair("[StartMode] Survival map mode enabled: human layout ${StartModeSurvivalLayoutSupport.humanSlotsDescription()}, reserved AI layout ${StartModeSurvivalLayoutSupport.reservedAiDescription()}, human ally group=${layout.humanAllyGroup}.")
        if (Data.configStartMode.enableFreeForAllMode) {
            Log.warn("[StartMode] Survival map mode overrides FFA mode. enableFreeForAllMode will be ignored.")
        }
        if (Data.configStartMode.enableAlternateTeamsOnJoin) {
            Log.warn("[StartMode] Survival map mode overrides alternate team assignment. enableAlternateTeamsOnJoin will be ignored.")
        }
        if (!Data.configServer.enableAI) {
            Log.warn("[StartMode] Survival map mode still needs the map survival AI slot even though enableAI is false.")
        }
        val requiredPlayerSlots = StartModeSurvivalLayoutSupport.maxRequiredPlayerIndex(HeadlessModuleManage.hps.room) + 1
        if (Data.configServer.maxPlayer < requiredPlayerSlots) {
            Log.warn("[StartMode] Survival map mode requires maxPlayer >= $requiredPlayerSlots because this map reserves AI slots: ${StartModeSurvivalLayoutSupport.reservedAiDescription()}.")
        }
    }

    private fun logSurvivalRepair(message: String) {
        StartModeConsoleOutputSupport.print(
            StartModeConsoleOutputSupport.Category.SURVIVAL_REPAIR,
            message
        )
    }

    // Cached reflection handles for hot-path lobby operations
    private var cachedEngineMethodB: java.lang.reflect.Method? = null
    private var cachedEngineFieldBx: java.lang.reflect.Field? = null
    private var cachedNetEngineMethodE: java.lang.reflect.Method? = null
    private var cachedNetEngineMethodL: java.lang.reflect.Method? = null
    private var cachedPlayerClass: Class<*>? = null
    private var cachedPlayerMethodK: java.lang.reflect.Method? = null
    private var cachedNetEngineMethodA: java.lang.reflect.Method? = null
    private var cachedLoaderId: String? = null

    private fun resolveEngineReflection(loader: ClassLoader): Boolean {
        val loaderId = loader.toString()
        if (loaderId == cachedLoaderId && cachedEngineMethodB != null) {
            return true
        }
        return try {
            val engineClass = Class.forName("com.corrodinggames.rts.gameFramework.l", false, loader)
            val netEngineClass = Class.forName("com.corrodinggames.rts.gameFramework.j.ad", false, loader)
            val connClass = Class.forName("com.corrodinggames.rts.gameFramework.j.c", false, loader)
            cachedEngineMethodB = engineClass.findMethod("B")
            cachedEngineFieldBx = engineClass.findField("bX")
            cachedNetEngineMethodE = netEngineClass.findMethod("e", connClass)
            cachedNetEngineMethodL = netEngineClass.findMethod("L")
            cachedPlayerClass = Class.forName("com.corrodinggames.rts.game.n", false, loader)
            val primInt = Int::class.javaPrimitiveType ?: Int::class.java
            cachedPlayerMethodK = cachedPlayerClass?.findMethod("k", primInt)
            cachedNetEngineMethodA = cachedPlayerClass?.let { pc -> netEngineClass.findMethod("a", pc, primInt) }
            cachedLoaderId = loaderId
            true
        } catch (e: Exception) {
            Log.error("[StartModeLobby] Failed to resolve engine reflection", e)
            false
        }
    }

    private fun refreshLobbyState(module: AbstractGameModule) {
        try {
            val loader = module.useClassLoader
            if (!resolveEngineReflection(loader)) return
            val engine = cachedEngineMethodB?.invoke(null) ?: return
            val netEngine = cachedEngineFieldBx?.get(engine) ?: return
            cachedNetEngineMethodE?.invoke(netEngine, null)
            cachedNetEngineMethodL?.invoke(netEngine)
        } catch (e: Exception) {
            Log.error("[StartModeLobby] Failed to refresh lobby state", e)
        }
    }

    fun movePlayerToPosition(player: PlayerHess, desiredPosition: Int, forceSync: Boolean = true): Boolean {
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }
        val module = HeadlessModuleManage.hps
        val room = module.room
        if (room.isStartGame || room.isBattleRoomLobbyRecoveryInProgress() ||
            desiredPosition !in 0 until Data.configServer.maxPlayer
        ) {
            return false
        }
        if (player.index == desiredPosition) {
            return true
        }

        var moved = false
        val operationSucceeded = module.gameFunction.suspendMainThreadOperations {
            if (module.room.isStartGame || module.room.isBattleRoomLobbyRecoveryInProgress()) {
                return@suspendMainThreadOperations
            }
            moved = synchronized(module.gameLinkServerData.teamOperationsSyncObject) {
                movePlayerToPositionInternal(module, player.index, desiredPosition)
            }
            if (moved && forceSync) {
                refreshLobbyState(module)
            }
        }
        if (!operationSucceeded) {
            Log.warn("[StartModeLobby] Failed to move player position on the game thread")
        }
        return operationSucceeded && moved
    }

    private fun movePlayerToPositionInternal(module: AbstractGameModule, currentPosition: Int, desiredPosition: Int): Boolean {
        return try {
            val loader = module.useClassLoader
            if (!resolveEngineReflection(loader)) return false
            val engine = cachedEngineMethodB?.invoke(null) ?: return false
            val netEngine = cachedEngineFieldBx?.get(engine) ?: return false
            val playerData = cachedPlayerMethodK?.invoke(null, currentPosition) ?: return false
            cachedNetEngineMethodA?.invoke(netEngine, playerData, desiredPosition)
            true
        } catch (e: Exception) {
            Log.error("[StartModeLobby] Failed to move player position", e)
            false
        }
    }
}
