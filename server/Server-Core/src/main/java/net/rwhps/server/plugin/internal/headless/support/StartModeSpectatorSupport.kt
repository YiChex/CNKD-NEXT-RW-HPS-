/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.findMethod
import net.rwhps.server.util.inline.toClass
import net.rwhps.server.util.log.Log
import java.util.Locale

internal object StartModeSpectatorSupport {
    private const val OFFICIAL_SPECTATOR_MOVE_TARGET = -2
    private val COMMAND_PREFIXES = charArrayOf('/', '-', '.', '_')
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** When true, lobby position changes are blocked for non-host, non-superAdmin players. */
    @Volatile
    var teamLockEnabled: Boolean = false

    fun parseTeamArgument(raw: String, site: Int = 0): Int? {
        return when {
            isSpectatorTeamToken(raw) -> StartModeLobbySupport.SPECTATOR_TEAM
            raw.trim() == "-1" -> site % 2
            else -> raw.trim().toIntOrNull()?.takeIf { it in 1..100 }?.let { it - 1 }
        }
    }

    fun parseMoveTeamArgument(raw: String, site: Int): Int? {
        return when {
            isSpectatorTeamToken(raw) -> StartModeLobbySupport.SPECTATOR_TEAM
            raw.trim() == "-1" -> site % 2
            else -> raw.trim().toIntOrNull()?.takeIf { it in 0..99 }
        }
    }

    /**
     * The stock client concatenates the player slot to the command name when
     * it emits lobby placement commands, for example `-move1 2` and
     * `-self_team2`.  Normalize that wire-compatible form before it reaches
     * the generic command handler, while leaving unrelated commands untouched.
     */
    fun normalizeOfficialLobbyCommandBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }

        val parts = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return trimmed
        }

        val rawName = parts[0].lowercase(Locale.ROOT)
        val prefixes = listOf("self_move", "self_team", "teamlayout", "teamdivide", "move", "team")
        val prefix = prefixes.firstOrNull {
            rawName.startsWith(it) && rawName.length > it.length
        } ?: return trimmed
        val suffix = rawName.substring(prefix.length)
        if (suffix.toIntOrNull() == null) {
            return trimmed
        }

        return buildList {
            add(prefix)
            add(suffix)
            addAll(parts.drop(1))
        }.joinToString(" ")
    }

    /** One command path for stock UI, chat prefixes and quick-command wrappers. */
    fun normalizeLobbyCommand(message: String): String? {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed[0] !in COMMAND_PREFIXES) return null
        var body = trimmed.substring(1).trim()
        val name = body.takeWhile { !it.isWhitespace() }
        if (name.equals("qc", ignoreCase = true)) {
            body = body.substring(name.length).trimStart()
            if (body.firstOrNull()?.let { it in COMMAND_PREFIXES } == true) body = body.substring(1).trimStart()
        }
        return "/${normalizeOfficialLobbyCommandBody(body)}"
    }

    fun isTeamLockPositionChangeCommand(message: String): Boolean {
        val command = normalizeLobbyCommand(message)?.substring(1)?.takeWhile { !it.isWhitespace() } ?: return false
        return command.lowercase(Locale.ROOT) in setOf("self_move", "self_team", "move", "team")
    }

    /** A numbered slot must return a spectator to a playing team, even without an optional team argument. */
    fun teamAfterPositionChange(rawTeam: String?, site: Int, currentTeam: Int, forcedTeam: Int? = null): Int? {
        if (forcedTeam != null && (rawTeam == null || rawTeam.trim() == "-1")) return forcedTeam
        return if (rawTeam != null) parseMoveTeamArgument(rawTeam, site)
        else if (StartModeLobbySupport.isSpectatorTeam(currentTeam)) site % 2
        else currentTeam
    }

    fun isSpectatorMoveTarget(raw: String): Boolean {
        val value = normalized(raw)
        return isSpectatorAlias(value) ||
            value == OFFICIAL_SPECTATOR_MOVE_TARGET.toString() ||
            value == StartModeLobbySupport.SPECTATOR_TEAM.toString() ||
            value == "-4"
    }

    fun isSpectatorTeamToken(raw: String): Boolean {
        val value = normalized(raw)
        return isSpectatorAlias(value) ||
            value == OFFICIAL_SPECTATOR_MOVE_TARGET.toString() ||
            value == StartModeLobbySupport.SPECTATOR_TEAM.toString() ||
            value == "-4"
    }

    fun moveToSpectator(player: PlayerHess): Boolean {
        val module = HeadlessModuleManage.hps
        var blockedBeforeDispatch = false
        var moved = false
        val operationSucceeded = module.gameFunction.suspendMainThreadOperations {
            if (module.room.isStartGame || module.room.isLobbyPlayerSyncBlocked() ||
                StartModeAutoStartSupport.isStartOperationInProgress()
            ) {
                blockedBeforeDispatch = true
                return@suspendMainThreadOperations
            }
            synchronized(module.gameLinkServerData.teamOperationsSyncObject) {
                // Keep the engine player table and the HPS lobby state on the original update thread.
                moved = StartModeLobbySupport.isSpectatorTeam(player.team) || moveToSpectatorViaHeadlessLoader(player)
                if (moved) {
                    StartModeLobbySupport.updateLockedTeam(player, StartModeLobbySupport.SPECTATOR_TEAM)
                    StartModeLobbySupport.updateLockedPosition(player, player.index)
                }
            }
        }
        if (!operationSucceeded) {
            Log.warn("[StartModeSpectator] Failed to move player to spectator on the game thread")
        }
        return operationSucceeded && !blockedBeforeDispatch && moved
    }

    /** Shared by player and console commands. Native classes stay in their owning loader. */
    fun changePlayingTeam(player: PlayerHess, newTeam: Int): Boolean {
        if (newTeam !in 0..99 || !HeadlessModuleManage.initHPS()) return false
        val module = HeadlessModuleManage.hps
        var changed = false
        val dispatched = module.gameFunction.suspendMainThreadOperations {
            if (module.room.isStartGame || module.room.isLobbyPlayerSyncBlocked() ||
                StartModeAutoStartSupport.isStartOperationInProgress()
            ) return@suspendMainThreadOperations
            synchronized(module.gameLinkServerData.teamOperationsSyncObject) {
                if (module.room.playerManage.getPlayer(player.index) !== player) return@synchronized
                val loader = module.useClassLoader
                val playerClass = "com.corrodinggames.rts.game.n".toClass(loader) ?: return@synchronized
                val lookup = playerClass.findMethod("k", Int::class.javaPrimitiveType!!) ?: return@synchronized
                val nativePlayer = lookup.invoke(null, player.index) ?: return@synchronized
                val teamField = playerClass.findField("r") ?: return@synchronized
                if (StartModeLobbySupport.isSpectatorTeam(teamField.getInt(nativePlayer))) {
                    val site = (0 until Data.configServer.maxPlayer).firstOrNull { index ->
                        val allowed = !Data.configStartMode.enableSurvivalMapMode ||
                            (!StartModeSurvivalLayoutSupport.isReservedAiIndex(index, module.room) &&
                                (!StartModeSurvivalLayoutSupport.hasDeclaredHumanSlots(module.room) ||
                                    StartModeSurvivalLayoutSupport.isHumanSlotIndex(index, module.room)))
                        allowed && (lookup.invoke(null, index) == null || lookup.invoke(null, index) === nativePlayer)
                    } ?: return@synchronized
                    val engineClass = "com.corrodinggames.rts.gameFramework.l".toClass(loader) ?: return@synchronized
                    val engine = engineClass.findMethod("B")?.invoke(null) ?: return@synchronized
                    val net = engineClass.findField("bX")?.get(engine) ?: return@synchronized
                    val move = net.javaClass.findMethod("a", playerClass, Int::class.javaPrimitiveType!!) ?: return@synchronized
                    move.invoke(net, nativePlayer, site)
                    if (player.index != site) return@synchronized
                    StartModeLobbySupport.updateLockedPosition(player, site)
                }
                teamField.setInt(nativePlayer, newTeam)
                StartModeLobbySupport.updateLockedTeam(player, newTeam)
                refreshLobbyStateViaHeadlessLoader()
                changed = true
            }
        }
        return dispatched && changed
    }

    private fun moveToSpectatorViaHeadlessLoader(player: PlayerHess): Boolean {
        return try {
            val module = HeadlessModuleManage.hps
            val loader = module.useClassLoader
            val engineClass = "com.corrodinggames.rts.gameFramework.l".toClass(loader) ?: return false
            val engine = engineClass.findMethod("B")?.invoke(null) ?: return false
            val netEngine = engineClass.findField("bX")?.get(engine) ?: return false
            val netEngineClass = netEngine.javaClass
            val playerClass = "com.corrodinggames.rts.game.n".toClass(loader) ?: return false
            val getPlayerMethod = playerClass.findMethod("k", Int::class.javaPrimitiveType ?: Int::class.java) ?: return false
            val playerData = getPlayerMethod.invoke(null, player.index) ?: return false

            if (!applyNativeSpectatorMove(netEngine, playerData, playerClass)) return false
            refreshLobbyStateViaHeadlessLoader()
            true
        } catch (e: Exception) {
            Log.error("[StartModeSpectator] Failed to move player to spectator", e)
            false
        }
    }

    internal fun applyNativeSpectatorMove(netEngine: Any, playerData: Any, playerClass: Class<*>): Boolean {
        val move = netEngine.javaClass.findMethod("a", playerClass, Int::class.javaPrimitiveType ?: Int::class.java)
            ?: return false
        move.invoke(netEngine, playerData, StartModeLobbySupport.SPECTATOR_TEAM)
        // The native method returns void even when there is no free spectator slot.
        // Never manufacture success by setting just the team field after a failed move.
        val team = playerClass.findField("r")?.getInt(playerData) ?: return false
        return StartModeLobbySupport.isSpectatorTeam(team)
    }

    private fun refreshLobbyStateViaHeadlessLoader() {
        try {
            val module = HeadlessModuleManage.hps
            val loader = module.useClassLoader
            val engineClass = "com.corrodinggames.rts.gameFramework.l".toClass(loader) ?: return
            val engine = engineClass.findMethod("B")?.invoke(null) ?: return
            val netEngine = engineClass.findField("bX")?.get(engine) ?: return
            val netEngineClass = netEngine.javaClass
            val connectionClass = "com.corrodinggames.rts.gameFramework.j.c".toClass(loader)
            netEngineClass.findMethod("e", connectionClass ?: Any::class.java)?.invoke(netEngine, null)
            netEngineClass.findMethod("L")?.invoke(netEngine)
        } catch (e: Exception) {
            Log.error("[StartModeSpectator] Failed to refresh lobby state", e)
        }
    }

    private fun isSpectatorAlias(raw: String): Boolean {
        return when (normalized(raw)) {
            "s", "spec", "spectator", "observe", "observer", "watch", "guan zhan", "guanzhan", "\u89c2\u6218" -> true
            else -> false
        }
    }

    private fun normalized(raw: String): String {
        return raw.trim().trim('"', '\'').lowercase(Locale.ROOT)
    }

}
