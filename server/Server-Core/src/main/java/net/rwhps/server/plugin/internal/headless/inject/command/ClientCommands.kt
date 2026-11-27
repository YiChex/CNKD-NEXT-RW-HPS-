/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.command

import android.graphics.PointF
import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.gameFramework.j.ai
import com.corrodinggames.rts.gameFramework.j.am
import net.rwhps.server.command.ex.Vote
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.enums.GamePingActions
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.StartModeSurrenderSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMoneyCommandSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionAction
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSpectatorSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurvivalLayoutSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurvivalMapSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurvivalSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeTeamLayoutSupport
import net.rwhps.server.struct.map.BaseMap.Companion.toSeq
import net.rwhps.server.util.IsUtils.isNumeric
import net.rwhps.server.util.IsUtils.notIsNumeric
import net.rwhps.server.util.algorithms.HexUtils
import net.rwhps.server.util.console.tab.TabDefaultEnum.PlayerPosition
import net.rwhps.server.util.console.tab.TabDefaultEnum.PlayerPositionAI
import net.rwhps.server.util.file.json.Json
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.game.SystemMessageTextMode
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.error
import java.io.IOException
import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.corrodinggames.rts.game.units.h as SupBuild

/**
 * @author Dr (dr@der.kim)
 */
internal class ClientCommands(handler: CommandHandler) {
    private val localeUtil = Data.i18NBundle
    private val mapListPageSize = 10

    private fun sendInvalidChoice(player: PlayerHess, label: String, validValues: String) {
        player.sendSystemMessage("$label 取值无效，可选值: $validValues")
    }

    private fun parseBooleanArgument(raw: String, player: PlayerHess, label: String): Boolean? {
        return when (raw.trim().lowercase()) {
            "on", "true", "1", "yes", "y", "enable", "enabled", "open" -> true
            "off", "false", "0", "no", "n", "disable", "disabled", "close" -> false
            else -> {
                sendInvalidChoice(player, label, "on/off/true/false/1/0")
                null
            }
        }
    }

    private fun parseIntInRangeArgument(raw: String, player: PlayerHess, label: String, min: Int, max: Int): Int? {
        val value = raw.trim().toIntOrNull()
        if (value == null) {
            player.sendSystemMessage(localeUtil.getinput("err.noNumber"))
            return null
        }
        if (value !in min..max) {
            player.sendSystemMessage("$label 必须介于 $min 和 $max 之间")
            return null
        }
        return value
    }

    private fun parseFloatInRangeArgument(raw: String, player: PlayerHess, label: String, min: Float, max: Float): Float? {
        val value = raw.trim().toFloatOrNull()
        if (value == null || !java.lang.Float.isFinite(value)) {
            player.sendSystemMessage(localeUtil.getinput("err.noNumber"))
            return null
        }
        if (value < min || value > max) {
            player.sendSystemMessage("$label 必须介于 $min 和 $max 之间")
            return null
        }
        return value
    }

    private fun creditsPresetEngineValue(amount: Int): Int? {
        return when (amount) {
            0 -> 1
            1000 -> 2
            2000 -> 3
            4000 -> 0
            5000 -> 4
            10000 -> 5
            50000 -> 6
            100000 -> 7
            200000 -> 8
            else -> null
        }
    }

    private fun parseUnitNameArgument(raw: String, player: PlayerHess): String? {
        val unit = raw.trim()
        if (unit.isEmpty() || unit.length > 128 || unit.any { Character.isISOControl(it) }) {
            player.sendSystemMessage("单位名称长度必须在 1-128 字符之间，且不能包含控制字符")
            return null
        }
        return unit
    }

    private fun registerOneShotMapPointAction(
        player: PlayerHess,
        prompt: String,
        permission: StartModeRoomPermissionAction,
        action: (AbstractNetConnectServer, GamePingActions, Float, Float) -> Unit
    ) {
        val generation = room.lobbyLifecycleGeneration()
        val createdAt = System.nanoTime()
        val armedConnection = player.con
        val guardedAction: (AbstractNetConnectServer, GamePingActions, Float, Float) -> Unit = { server, pingAction, x, y ->
            if (!room.isStartGame) {
                runCatching { player.sendSystemMessage("Current game has ended. This pending action was cancelled.") }
            } else if (room.lobbyLifecycleGeneration() != generation ||
                System.nanoTime() - createdAt > TimeUnit.SECONDS.toNanos(60) ||
                player.con == null || player.con !== armedConnection || player.con !== server
            ) {
                player.sendSystemMessage("待执行的地图操作已过期，请重新输入命令。")
            } else if (requireAdminAction(player, permission)) {
                runCatching { action(server, pingAction, x, y) }.onFailure {
                    error("[ClientCommand] Deferred map-point action failed", it)
                    runCatching { player.sendSystemMessage("Map point action failed. Please try again later.") }
                }
            }
        }
        player.removeData("Ping")
        player.sendSystemMessage(prompt)
        player.addData("Ping", guardedAction)
    }

    private fun sendCustomMapPage(player: PlayerHess, rawPage: String?) {
        val mapKeys = MapManage.mapsData.keys.toSeq()
        if (mapKeys.size == 0) {
            player.sendSystemMessage(localeUtil.getinput("map.no"))
            return
        }

        val maxPage = ((mapKeys.size - 1) / mapListPageSize) + 1
        val page = rawPage
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
            ?: 1
        if (page !in 1..maxPage) {
            player.sendSystemMessage("页码无效，请输入 1-$maxPage")
            return
        }

        val fromIndex = (page - 1) * mapListPageSize
        val toIndexExclusive = kotlin.math.min(fromIndex + mapListPageSize, mapKeys.size)
        player.sendSystemMessage("Map list page $page/$maxPage, total ${mapKeys.size} map(s)")
        for (index in fromIndex until toIndexExclusive) {
            player.sendSystemMessage(
                localeUtil.getinput("maps.info", index, MapManage.displayNameForKey(mapKeys[index]))
            )
        }
        if (page < maxPage) {
            player.sendSystemMessage("Use /maps ${page + 1} to view next page.")
        }
    }

    private fun requireAdminAction(player: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
            return StartModeRoomPermissionSupport.require(player, action)
        }
        return StartModeRoomPermissionSupport.requireDelegatedPlayerAction(player, action)
    }

    private fun requirePlayerAction(player: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        return StartModeRoomPermissionSupport.require(player, action)
    }

    private fun requireSelfLobbyAction(player: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        return if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
            StartModeRoomPermissionSupport.require(player, action)
        } else {
            true
        }
    }

    private fun checkPositionNumb(int: String, player: PlayerHess): Boolean {
        val position = int.trim().toIntOrNull()
        if (position == null) {
            player.sendSystemMessage(player.i18NBundle.getinput("err.noNumber"))
            return false
        }
        if (position <= 0) {
            player.sendSystemMessage(player.i18NBundle.getinput("err.noInt"))
            return false
        }
        if (position > Data.configServer.maxPlayer) {
            player.sendSystemMessage(player.i18NBundle.getinput("err.maxPlayer"))
            return false
        }
        return true
    }

    private fun playerIndexFromPositionArgument(raw: String, player: PlayerHess): Int? {
        val position = raw.trim().toIntOrNull()
        if (position == null || position <= 0) {
            player.sendSystemMessage(player.i18NBundle.getinput("err.noInt"))
            return null
        }
        // Native spectator slots are above maxPlayer; targets may refer to those slots.
        // Destinations still pass checkPositionNumb and remain bounded by maxPlayer.
        return position - 1
    }

    private fun playerFromPositionArgument(raw: String, player: PlayerHess): PlayerHess? {
        val index = playerIndexFromPositionArgument(raw, player) ?: return null
        return room.playerManage.getPlayer(index) ?: run {
            player.sendSystemMessage(player.i18NBundle.getinput("err.player.no.site", raw.trim()))
            null
        }
    }

    private fun requireLobbyTargetAction(actor: PlayerHess, target: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        return if (actor === target) requireSelfLobbyAction(actor, action) else requireAdminAction(actor, action)
    }

    private fun changeToSpectator(actor: PlayerHess, target: PlayerHess) {
        if (!requireLobbyTargetAction(actor, target, StartModeRoomPermissionAction.MOVE_PLAYERS) ||
            !requireLobbyTargetAction(actor, target, StartModeRoomPermissionAction.CHANGE_TEAM)
        ) return
        if (!StartModeLobbySupport.canChangeToSpectator(actor)) {
            actor.sendSystemMessage(if (!StartModeLobbySupport.canChangePosition(actor))
                StartModeLobbySupport.positionChangeBlockedReason() else StartModeLobbySupport.teamChangeBlockedReason())
            return
        }
        if (!StartModeSpectatorSupport.moveToSpectator(target)) {
            actor.sendSystemMessage("暂时无法进入 S 队：观战位置不可用，或房间正在开局/返回大厅，请稍后重试。")
        }
    }

    private fun changePlayingTeam(actor: PlayerHess, target: PlayerHess, newTeam: Int) {
        if (StartModeLobbySupport.isSpectatorTeam(target.team) && !StartModeLobbySupport.canChangePosition(actor)) {
            actor.sendSystemMessage(StartModeLobbySupport.positionChangeBlockedReason())
            return
        }
        if (!StartModeSpectatorSupport.changePlayingTeam(target, newTeam)) {
            actor.sendSystemMessage("没有可用的参战位置，或房间状态已变化，请稍后重试。")
        }
    }

    private fun mutateLobbyOnGameThread(action: String, mutation: () -> Unit): Boolean {
        var roomStartedBeforeDispatch = false
        var battleRoomRecoveryBeforeDispatch = false
        var startOperationBeforeDispatch = false
        val operationSucceeded = gameModule.gameFunction.suspendMainThreadOperations {
            if (room.isStartGame) {
                roomStartedBeforeDispatch = true
                return@suspendMainThreadOperations
            }
            if (room.isBattleRoomLobbyRecoveryInProgress()) {
                battleRoomRecoveryBeforeDispatch = true
                return@suspendMainThreadOperations
            }
            if (StartModeAutoStartSupport.isStartOperationInProgress()) {
                startOperationBeforeDispatch = true
                return@suspendMainThreadOperations
            }
            mutation()
            GameEngine.netEngine.L()
        }
        if (!operationSucceeded) {
            Log.warn("[Headless] Failed to apply lobby mutation on the game thread: $action")
        } else if (battleRoomRecoveryBeforeDispatch) {
            Log.debug("[Headless] Deferred lobby mutation during battle-room recovery: $action")
        } else if (startOperationBeforeDispatch) {
            Log.debug("[Headless] Deferred lobby mutation while start confirmation is pending: $action")
        } else if (roomStartedBeforeDispatch) {
            Log.warn("[Headless] Ignored lobby mutation after the game started: $action")
        }
        return operationSucceeded && !roomStartedBeforeDispatch && !battleRoomRecoveryBeforeDispatch && !startOperationBeforeDispatch
    }

    private fun snapshotLayoutEnginePlayers(includeSpectators: Boolean): List<n> {
        val players = ArrayList<n>()
        val engineSlotCount = minOf(Data.configServer.maxPlayer, n.c)
        for (site in 0 until engineSlotCount) {
            val enginePlayer = n.k(site) ?: continue
            // Native layout helpers iterate the engine table directly. Do not
            // feed an orphan engine slot back into that path: it has no HPS
            // player wrapper and can make the next lobby/render pass crash.
            if (room.playerManage.getPlayer(site) == null) {
                continue
            }
            if (!includeSpectators && StartModeLobbySupport.isSpectatorTeam(enginePlayer.r)) {
                continue
            }
            players += enginePlayer
        }
        return players
    }

    private fun findUnboundEngineSlot(): Int? {
        val engineSlotCount = minOf(Data.configServer.maxPlayer, n.c)
        for (site in 0 until engineSlotCount) {
            if (n.k(site) != null && room.playerManage.getPlayer(site) == null) {
                return site
            }
        }
        return null
    }

    private fun syncTeamLayoutLocks() {
        room.playerManage.playerGroup.eachAll { current ->
            StartModeLobbySupport.updateLockedPosition(current, current.index)
            StartModeLobbySupport.updateLockedTeam(current, current.team)
        }
    }

    private fun applyTeamLayout(layout: StartModeTeamLayoutSupport.Layout): Boolean {
        val enginePlayers = snapshotLayoutEnginePlayers(includeSpectators = true)
        val activePlayers = enginePlayers.filterNot { StartModeLobbySupport.isSpectatorTeam(it.r) }
        val spectatorSlots = enginePlayers
            .filter { StartModeLobbySupport.isSpectatorTeam(it.r) }
            .map { it.k }
            .toSet()
        val hasEligiblePlayers = if (layout == StartModeTeamLayoutSupport.Layout.SPECTATORS) {
            enginePlayers.isNotEmpty()
        } else {
            activePlayers.isNotEmpty()
        }

        when (layout) {
            StartModeTeamLayoutSupport.Layout.TWO_SIDES -> {
                applyFilteredTeamLayout(layout, activePlayers, spectatorSlots)
                gameModule.room.isMixPlayer = false
            }
            StartModeTeamLayoutSupport.Layout.THREE_SIDES -> {
                applyFilteredTeamLayout(layout, activePlayers, spectatorSlots)
                gameModule.room.isMixPlayer = false
            }
            StartModeTeamLayoutSupport.Layout.FFA -> {
                applyFilteredTeamLayout(layout, activePlayers, spectatorSlots)
                gameModule.room.isMixPlayer = true
            }
            StartModeTeamLayoutSupport.Layout.SPECTATORS -> {
                GameEngine.netEngine.a(am.d)
                gameModule.room.isMixPlayer = false
            }
        }

        syncTeamLayoutLocks()
        return hasEligiblePlayers
    }

    private fun applyFilteredTeamLayout(
        layout: StartModeTeamLayoutSupport.Layout,
        activePlayers: List<n>,
        spectatorSlots: Set<Int>
    ) {
        val assignments = StartModeTeamLayoutSupport.planAssignments(
            activePlayerSlots = activePlayers.map { it.k },
            spectatorSlots = spectatorSlots,
            maxSlots = minOf(Data.configServer.maxPlayer, n.c),
            layout = layout
        )
        check(assignments.size == activePlayers.size) {
            "team_layout_no_available_positions layout=${layout.wireName} active=${activePlayers.size} spectators=${spectatorSlots.size}"
        }

        val playersByOriginalSlot = HashMap<Int, com.corrodinggames.rts.game.n>()
        activePlayers.forEach { candidate ->
            playersByOriginalSlot[candidate.k] = candidate
        }
        // Move by target position so each completed slot is not displaced by a
        // later player in the same permutation. The native move API swaps the
        // occupants, so processing targets in order resolves cycles safely.
        assignments.sortedBy { it.targetPosition }.forEach { assignment ->
            val enginePlayer: com.corrodinggames.rts.game.n = playersByOriginalSlot[assignment.playerSlot]
                ?: throw IllegalStateException("team_layout_player_slot_changed slot=${assignment.playerSlot}")
            if (enginePlayer.k != assignment.targetPosition) {
                GameEngine.netEngine.a(enginePlayer, assignment.targetPosition)
            }
            enginePlayer.r = assignment.team
        }
    }

    private fun teamLayoutBlockedReason(
        layout: StartModeTeamLayoutSupport.Layout,
        player: PlayerHess
    ): String? {
        if (Data.configStartMode.enableSurvivalMapMode) {
            return "生存地图模式下不能使用队伍布局调整"
        }

        if (Data.configStartMode.enableFreeForAllMode && layout != StartModeTeamLayoutSupport.Layout.FFA) {
            return "服务器已开启混战模式 只能使用混战布局"
        }

        if (Data.configStartMode.enableAlternateTeamsOnJoin &&
            layout != StartModeTeamLayoutSupport.Layout.TWO_SIDES &&
            layout != StartModeTeamLayoutSupport.Layout.SPECTATORS &&
            !StartModeLobbySupport.canChangeTeam(player)
        ) {
            return "服务器已开启按位置分队 只能使用两队布局"
        }

        if (!StartModeLobbySupport.canChangePosition(player)) {
            return StartModeLobbySupport.positionChangeBlockedReason()
        }

        if (layout != StartModeTeamLayoutSupport.Layout.SPECTATORS &&
            !StartModeLobbySupport.canChangeTeam(player) &&
            !(Data.configStartMode.enableFreeForAllMode && layout == StartModeTeamLayoutSupport.Layout.FFA) &&
            !(Data.configStartMode.enableAlternateTeamsOnJoin && layout == StartModeTeamLayoutSupport.Layout.TWO_SIDES)
        ) {
            return StartModeLobbySupport.teamChangeBlockedReason()
        }
        return null
    }

    private fun handleTeamLayout(player: PlayerHess, rawLayout: String) {
        if (room.isStartGame) {
            player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
            return
        }
        if (StartModeSpectatorSupport.teamLockEnabled &&
            !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)
        ) {
            player.sendSystemMessage("队伍位置已锁定 仅房主或管理员可调整")
            return
        }

        val layout = StartModeTeamLayoutSupport.parseLayout(rawLayout)
        if (layout == null) {
            player.sendSystemMessage("队伍布局无效 可选 2 3 ffa spectators")
            return
        }
        if (!requireAdminAction(player, StartModeRoomPermissionAction.MOVE_PLAYERS) ||
            !requireAdminAction(player, StartModeRoomPermissionAction.CHANGE_TEAM)
        ) {
            return
        }
        if (layout == StartModeTeamLayoutSupport.Layout.FFA &&
            !requireAdminAction(player, StartModeRoomPermissionAction.TOGGLE_MIXED_TEAM)
        ) {
            return
        }

        teamLayoutBlockedReason(layout, player)?.let {
            player.sendSystemMessage(it)
            return
        }

        var hasEligiblePlayers = false
        var unboundEngineSlot: Int? = null
        if (!mutateLobbyOnGameThread("team_layout_${layout.wireName}") {
                synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                    unboundEngineSlot = findUnboundEngineSlot()
                    if (unboundEngineSlot == null) {
                        hasEligiblePlayers = applyTeamLayout(layout)
                    }
                }
            }) {
            sendLobbyMutationUnavailable(player)
            return
        }
        if (unboundEngineSlot != null) {
            player.sendSystemMessage(
                "战役室仍有未绑定的引擎槽位 ${unboundEngineSlot!! + 1} 请先刷新战役室或重置房间后再调整队伍"
            )
            return
        }
        if (!hasEligiblePlayers) {
            player.sendSystemMessage("当前没有可调整的玩家")
            return
        }

        val label = when (layout) {
            StartModeTeamLayoutSupport.Layout.TWO_SIDES -> "两队"
            StartModeTeamLayoutSupport.Layout.THREE_SIDES -> "三队"
            StartModeTeamLayoutSupport.Layout.FFA -> "混战"
            StartModeTeamLayoutSupport.Layout.SPECTATORS -> "全体观战"
        }
        player.sendSystemMessage("已应用${label}布局 玩家位置和队伍已同步")
        room.call.sendSystemMessage("管理员已应用${label}布局")
    }

    private fun sendLobbyMutationUnavailable(player: PlayerHess) {
        if (room.isBattleRoomLobbyRecoveryInProgress()) {
            player.sendSystemMessage("战役室正在恢复大厅状态 请稍后再修改")
            return
        }
        player.sendSystemMessage("Lobby state changed before this command could be applied. Please try again.")
    }



    private fun registerGameCoreCommand(handler: CommandHandler) {
        handler.register("surrender", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }
        handler.register("ff", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }
        handler.register("forfeit", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }
        handler.register("giveup", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }
        handler.register("resign", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }
        handler.register("投降", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }
        handler.register("放弃", "HIDE") { _: Array<String>?, player: PlayerHess ->
            StartModeSurrenderSupport.requestPlayerSurrender(player)
        }

        /* QC */
        handler.register("credits", "<money>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.SET_CREDITS)) {
                val amount = args[0].toIntOrNull()
                if (amount == null) {
                    player.sendSystemMessage(localeUtil.getinput("err.noNumber"))
                    return@register
                }
                val preset = creditsPresetEngineValue(amount)
                if (preset == null) {
                    player.sendSystemMessage("Invalid credits value. Use a preset such as 1000, 2000, 4000, 5000, 10000, 50000, 100000 or 200000.")
                    return@register
                }
                if (!mutateLobbyOnGameThread("set_credits") {
                        GameEngine.data.gameLinkServerData.credits = preset
                        StartModeGameOptionSupport.recordLobbyCreditsChanged(amount)
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                player.sendSystemMessage("已设置初始资金为: $amount")
            }
        }
        handler.register("nukes", "<boolean>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.TOGGLE_NUKES)) {
                val allowNukes = parseBooleanArgument(args[0], player, "nukes") ?: return@register
                val noNukes = !allowNukes
                if (!mutateLobbyOnGameThread("set_nukes") {
                        GameEngine.data.gameLinkServerData.nukes = noNukes
                        if (StartModeRoomPermissionSupport.isVisibleHost(player, room)) {
                            StartModeGameOptionSupport.recordHostNoNukesChanged(player, room, noNukes)
                        }
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                player.sendSystemMessage(if (allowNukes) "核弹已允许" else "核弹已禁用")
            }
        }
        handler.register("addai", "HIDE") { _: Array<String>?, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.ADD_AI)) {
                if (GameEngine.data.room.isGameOverResetInProgress()) {
                    player.sendSystemMessage("房间正在重置 请稍后再添加 AI")
                    return@register
                }
                if (Data.configStartMode.enableSurvivalMapMode) {
                    player.sendSystemMessage("生存地图模式下无法添加 AI")
                    return@register
                }
                if (Data.configServer.enableAI) {
                    var aiPlayer: PlayerHess? = null
                    if (!mutateLobbyOnGameThread("add_ai") {
                            aiPlayer = GameEngine.data.room.playerManage.addAI()
                        }) {
                        sendLobbyMutationUnavailable(player)
                        return@register
                    }
                    if (aiPlayer == null) {
                        player.sendSystemMessage("当前无法添加 AI，请稍后重试")
                    } else {
                        player.sendSystemMessage("已添加 AI: ${aiPlayer?.name}")
                    }
                } else {
                    player.sendSystemMessage("当前服务器未开启添加 AI")
                }
            }
        }
        handler.register("fog", "<type>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.SET_FOG)) {
                val fog = when (args[0].lowercase()) {
                    "off", "0", "false" -> 0
                    "basic", "1" -> 1
                    "lineofsight", "line_of_sight", "line-of-sight", "2", "true" -> 2
                    else -> {
                        player.sendSystemMessage("Fog must be one of: off/basic/lineOfSight")
                        return@register
                    }
                }
                if (!mutateLobbyOnGameThread("set_fog") {
                        GameEngine.data.gameLinkServerData.fog = fog
                    }) {
                    sendLobbyMutationUnavailable(player)
                }
            }
        }
        handler.register("sharedcontrol", "<boolean>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.TOGGLE_SHARED_CONTROL)) {
                val enabled = parseBooleanArgument(args[0], player, "共享控制") ?: return@register
                if (!mutateLobbyOnGameThread("set_shared_control") {
                        GameEngine.data.gameLinkServerData.sharedcontrol = enabled
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                player.sendSystemMessage(if (enabled) "共享控制已开启" else "共享控制已关闭")
            }
        }
        handler.register("startingunits", "<type>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.SET_STARTING_UNITS)) {
                val raw = args[0].trim().toIntOrNull()
                if (raw == null || raw < 0) {
                    player.sendSystemMessage(localeUtil.getinput("err.noNumber"))
                    return@register
                }
                if (!mutateLobbyOnGameThread("set_starting_units") {
                        GameEngine.data.gameLinkServerData.startingunits = raw
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                player.sendSystemMessage("已设置起始单位数量为: $raw")
            }
        }
        handler.register("income", "<income>", "clientCommands.income") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.SET_INCOME)) {
                if (room.isStartGame) {
                    player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                    return@register
                }
                val income = parseFloatInRangeArgument(args[0], player, "收入倍率", 0f, 100f) ?: return@register
                if (!mutateLobbyOnGameThread("set_income") {
                        GameEngine.data.gameLinkServerData.income = income
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                player.sendSystemMessage("已设置收入倍率为: $income")
            }
        }
        handler.register("ai", "<difficuld> [$PlayerPositionAI]", "clientCommands.income") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.SET_AI_DIFFICULTY)) {
                if (room.isStartGame) {
                    player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                    return@register
                }

                val difficulty = parseIntInRangeArgument(args[0], player, "AI 难度", -2, 3) ?: return@register

                val selectedAiIndex = if (args.size > 1) {
                    playerIndexFromPositionArgument(args[1], player) ?: return@register
                } else {
                    null
                }
                var resultMessage: String? = null
                if (!mutateLobbyOnGameThread("set_ai_difficulty") {
                        synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                            if (selectedAiIndex != null) {
                                val playerAi = gameModule.room.playerManage.getPlayer(selectedAiIndex)
                                resultMessage = when {
                                    playerAi == null -> player.i18NBundle.getinput("no.findPlayer")
                                    !playerAi.isAi -> "Selected player is not an AI."
                                    else -> {
                                        playerAi.aiDifficulty = difficulty
                                        "AI difficulty updated for ${playerAi.name}: $difficulty"
                                    }
                                }
                            } else {
                                GameEngine.data.gameLinkServerData.aiDifficuld = difficulty
                                gameModule.room.playerManage.playerGroup.eachAllFind({ it.isAi }) {
                                    it.aiDifficulty = difficulty
                                }
                                resultMessage = "AI difficulty updated for all AI players: $difficulty"
                            }
                        }
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                resultMessage?.let(player::sendSystemMessage)
            }
        }
        handler.register("startcancel", "#取消手动开局倒计时") { _: Array<String>?, player: PlayerHess ->
            if (!StartModeAutoStartSupport.cancelManualStart(player)) player.sendSystemMessage("没有可取消的手动开局倒计时，或你没有开局权限。")
        }
        handler.register("start", "clientCommands.start") { _: Array<String>?, player: PlayerHess? ->
            StartModeAutoStartSupport.requestStartOffNetworkThread(player, "manual")
        }
        handler.register("give", "<$PlayerPosition>", "#给予房主") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (StartModeAutoStartSupport.isStartOperationInProgress() || room.isBattleRoomLobbyRecoveryInProgress()) {
                player.sendSystemMessage("房间状态正在同步 请稍后再转移房主")
                return@register
            }
            if (!requireAdminAction(player, StartModeRoomPermissionAction.TRANSFER_HOST)) {
                return@register
            }
            val targetPlayer = playerFromPositionArgument(args[0], player) ?: return@register
            if (!StartModeRoomPermissionSupport.transferHost(room, targetPlayer)) {
                player.sendSystemMessage("无法转移房主：目标无效或大厅正在同步。")
                return@register
            }
            room.call.sendSystemMessageLocal("give.ok", targetPlayer.name)
            StartModeRoomPermissionSupport.refreshOnlineAuthorityView("client_give_host")
        }
        handler.register("kick", "<$PlayerPosition>", "clientCommands.kick") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.KICK_PLAYERS)) {
                val position = args[0].trim().toIntOrNull()
                if (position == null || position <= 0) {
                    player.sendSystemMessage(player.i18NBundle.getinput("err.noInt"))
                    return@register
                }
                val targetIndex = position - 1
                var targetWasBound = false
                var targetLabel = args[0]
                if (!mutateLobbyOnGameThread("kick_player") {
                        synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                            // Never remove an engine slot without its matching PlayerHess binding.
                            val kickPlayer = room.playerManage.getPlayer(targetIndex)
                            if (kickPlayer == null) {
                                Log.warn("[Kick] Refused to nuke unbound engine slot $targetIndex by admin ${player.name}")
                            } else {
                                targetWasBound = true
                                targetLabel = runCatching { "${kickPlayer.name}#${kickPlayer.position}" }.getOrDefault(args[0])
                                kickPlayer.kickPlayer(localeUtil.getinput("kick.you"), 60)
                                Log.clog("[Kick] Sent kick/disconnect to player: $targetLabel by admin ${player.name}")
                                synchronized(kickPlayer) {
                                    kickPlayer.con = null
                                    room.playerManage.playerGroup.remove(kickPlayer)
                                    room.playerManage.playerAll.remove(kickPlayer)
                                    StartModeLobbySupport.forgetPlayer(kickPlayer)
                                    runCatching { kickPlayer.playerPrivateData.removePlayer() }
                                        .onFailure { Log.warn("[Kick] Failed to remove player private data for $targetLabel", it) }
                                    kickPlayer.clear()
                                }
                                Log.clog("[Kick] Removed player: $targetLabel by admin ${player.name}")
                                runCatching { gameModule.room.call.syncAllPlayer() }
                                    .onFailure { Log.warn("[Kick] Failed to sync the lobby after removing $targetLabel", it) }
                            }
                        }
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                if (!targetWasBound) {
                    player.sendSystemMessage("That position has no server-side player binding. Wait for a lobby refresh or reset the room.")
                    return@register
                }
                player.sendSystemMessage("Kicked player $targetLabel.")
            }
        }
        handler.register("self_move", "<ToSerialNumber> [Team]", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (StartModeSpectatorSupport.teamLockEnabled && !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                player.sendSystemMessage("队伍位置已锁定，仅房主或管理员可调整。")
                return@register
            }
            if (!requireSelfLobbyAction(player, StartModeRoomPermissionAction.MOVE_PLAYERS)) return@register
            if (StartModeSpectatorSupport.isSpectatorMoveTarget(args[0]) ||
                args.getOrNull(1)?.let(StartModeSpectatorSupport::isSpectatorTeamToken) == true
            ) {
                changeToSpectator(player, player)
                return@register
            }
            if (!StartModeLobbySupport.canChangePosition(player)) {
                player.sendSystemMessage(StartModeLobbySupport.positionChangeBlockedReason())
                return@register
            }
            if (!checkPositionNumb(args[0], player)) return@register
            val site = args[0].toInt() - 1
            if (StartModeLobbySupport.isSpectatorTeam(player.team) && !StartModeLobbySupport.canChangeToSpectator(player)) {
                player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            if (Data.configStartMode.enableSurvivalMapMode &&
                (StartModeSurvivalLayoutSupport.isReservedAiIndex(site, room) ||
                    (StartModeSurvivalLayoutSupport.hasDeclaredHumanSlots(room) && !StartModeSurvivalLayoutSupport.isHumanSlotIndex(site, room)))) {
                player.sendSystemMessage("该位置由生存地图保留，请选择人类玩家位置。")
                return@register
            }
            val forcedTeam = StartModeLobbySupport.forcedTeamForPosition(site)
            val newTeam = StartModeSpectatorSupport.teamAfterPositionChange(args.getOrNull(1), site, player.team, forcedTeam)
            if (newTeam == null) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noNumber"))
                return@register
            }
            if (newTeam != player.team && newTeam != forcedTeam && !StartModeLobbySupport.canChangeTeam(player)) {
                player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            if (newTeam != player.team && !requireSelfLobbyAction(player, StartModeRoomPermissionAction.CHANGE_TEAM)) return@register
            var enginePlayerMissing = false
            if (!mutateLobbyOnGameThread("self_move_player") {
                    synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                        val enginePlayer = n.k(player.index)
                        if (enginePlayer == null) enginePlayerMissing = true else {
                            GameEngine.netEngine.a(enginePlayer, site)
                            enginePlayer.r = newTeam
                            StartModeLobbySupport.updateLockedPosition(player, site)
                            StartModeLobbySupport.updateLockedTeam(player, newTeam)
                        }
                    }
                }) sendLobbyMutationUnavailable(player)
            else if (enginePlayerMissing) player.sendSystemMessage("Player slot is no longer available. Please refresh the lobby.")
        }
        handler.register("self_team", "<ToTeamNumber>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (StartModeSpectatorSupport.teamLockEnabled && !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                player.sendSystemMessage("队伍位置已锁定，仅房主或管理员可调整。")
                return@register
            }
            if (!requireSelfLobbyAction(player, StartModeRoomPermissionAction.CHANGE_TEAM)) return@register
            val newTeam = StartModeSpectatorSupport.parseTeamArgument(args[0], player.index)
            if (newTeam == null) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noNumber"))
                return@register
            }
            if (StartModeLobbySupport.isSpectatorTeam(newTeam)) {
                changeToSpectator(player, player)
                return@register
            }
            if (!StartModeLobbySupport.canChangeTeam(player)) {
                player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            changePlayingTeam(player, player, newTeam)
        }
        handler.register("move", "<$PlayerPosition> <ToSerialNumber> [Team]", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (StartModeSpectatorSupport.teamLockEnabled && !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                player.sendSystemMessage("队伍位置已锁定，仅房主或管理员可调整。")
                return@register
            }
            val target = playerFromPositionArgument(args[0], player) ?: return@register
            if (!requireLobbyTargetAction(player, target, StartModeRoomPermissionAction.MOVE_PLAYERS)) return@register
            if (StartModeSpectatorSupport.isSpectatorMoveTarget(args[1]) ||
                args.getOrNull(2)?.let(StartModeSpectatorSupport::isSpectatorTeamToken) == true
            ) {
                changeToSpectator(player, target)
                return@register
            }
            if (!StartModeLobbySupport.canChangePosition(player)) {
                player.sendSystemMessage(StartModeLobbySupport.positionChangeBlockedReason())
                return@register
            }
            if (Data.configStartMode.enableSurvivalMapMode) {
                player.sendSystemMessage("生存地图模式由地图规则安排玩家位置。")
                return@register
            }
            if (!checkPositionNumb(args[1], player)) return@register
            val site = args[1].toInt() - 1
            if (StartModeLobbySupport.isSpectatorTeam(target.team) && !StartModeLobbySupport.canChangeToSpectator(player)) {
                player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            val forcedTeam = StartModeLobbySupport.forcedTeamForPosition(site)
            val newTeam = StartModeSpectatorSupport.teamAfterPositionChange(args.getOrNull(2), site, target.team, forcedTeam)
            if (newTeam == null) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noNumber"))
                return@register
            }
            if (newTeam != target.team && ((newTeam != forcedTeam && !StartModeLobbySupport.canChangeTeam(player)) ||
                    !requireLobbyTargetAction(player, target, StartModeRoomPermissionAction.CHANGE_TEAM))) {
                if (!StartModeLobbySupport.canChangeTeam(player)) player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            var enginePlayerMissing = false
            if (!mutateLobbyOnGameThread("move_player") {
                    synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                        val enginePlayer = n.k(target.index)
                        if (enginePlayer == null) enginePlayerMissing = true else {
                            GameEngine.netEngine.a(enginePlayer, site)
                            enginePlayer.r = newTeam
                            StartModeLobbySupport.updateLockedPosition(target, site)
                            StartModeLobbySupport.updateLockedTeam(target, newTeam)
                        }
                    }
                }) sendLobbyMutationUnavailable(player)
            else if (enginePlayerMissing) player.sendSystemMessage("Player slot is no longer available. Please refresh the lobby.")
        }
        handler.register("team", "<$PlayerPosition> <ToTeamNumber>", "HIDE") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (StartModeSpectatorSupport.teamLockEnabled && !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                player.sendSystemMessage("队伍位置已锁定，仅房主或管理员可调整。")
                return@register
            }
            val target = playerFromPositionArgument(args[0], player) ?: return@register
            if (!requireLobbyTargetAction(player, target, StartModeRoomPermissionAction.CHANGE_TEAM)) return@register
            val newTeam = StartModeSpectatorSupport.parseTeamArgument(args[1], target.index)
            if (newTeam == null) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noNumber"))
                return@register
            }
            if (StartModeLobbySupport.isSpectatorTeam(newTeam)) {
                changeToSpectator(player, target)
                return@register
            }
            if (!StartModeLobbySupport.canChangeTeam(player)) {
                player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            changePlayingTeam(player, target, newTeam)
        }
        handler.register("am","<on/off>" ,"clientCommands.am") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (StartModeSpectatorSupport.teamLockEnabled && !StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                player.sendSystemMessage("队伍位置已锁定 仅房主或管理员可移动")
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.TOGGLE_MIXED_TEAM)) {
                if (Data.configStartMode.enableSurvivalMapMode) {
                    player.sendSystemMessage("Mixed teams cannot be toggled in survival map mode.")
                    StartModeLobbySupport.enforceLobbyRules()
                    return@register
                }
                if (Data.configStartMode.enableFreeForAllMode) {
                    player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                    StartModeLobbySupport.enforceLobbyRules()
                    return@register
                }
                if (Data.configStartMode.disableTeamChange && !StartModeLobbySupport.canChangeTeam(player)) {
                    player.sendSystemMessage(StartModeLobbySupport.teamChangeBlockedReason())
                    return@register
                }
                val enabled = parseBooleanArgument(args[0], player, "mixed teams") ?: return@register
                if (!mutateLobbyOnGameThread("set_mixed_teams") {
                        gameModule.room.isMixPlayer = enabled
                        if (enabled) {
                            synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                                for (site in 0 until Data.configServer.maxPlayer) {
                                    n.k(site)?.r = site
                                }
                            }
                        }
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                player.sendSystemMessage(player.i18NBundle.getinput("server.amTeam", enabled))
            }
        }
        handler.register("ffa", "#混战模式: 每个玩家独立队伍 + 随机分配槽位") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "ffa")
        }
        handler.register("teamlock", "<on/off>", "#禁止玩家自行移动槽位") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(localeUtil.getinput("err.startGame"))
                return@register
            }
            if (!requireAdminAction(player, StartModeRoomPermissionAction.MOVE_PLAYERS)) {
                return@register
            }
            val enabled = parseBooleanArgument(args[0], player, "teamlock") ?: return@register
            StartModeSpectatorSupport.teamLockEnabled = enabled
            val status = if (enabled) "已开启" else "已关闭"
            player.sendSystemMessage("队伍位置锁定 $status")
            room.call.sendSystemMessage("管理员${status}了队伍位置锁定 玩家无法自行移动槽位")
        }
        handler.register("teamshuffle", "#Team mode: shuffle slots and split odd/even teams") { _: Array<String>?, player: PlayerHess ->
            if (!requireAdminAction(player, StartModeRoomPermissionAction.TOGGLE_MIXED_TEAM)) {
                return@register
            }
            handleTeamLayout(player, "2")
        }
        handler.register("teamlayout", "<2/3/ffa/spectators>", "#队伍布局 2 3 ffa spectators") { args: Array<String>, player: PlayerHess ->
            handleTeamLayout(player, args[0])
        }
        handler.register("t2", "#分为两队并刷新玩家位置") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "2")
        }
        handler.register("t3", "#分为三队并刷新玩家位置") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "3")
        }
        handler.register("tffa", "#混战并刷新玩家位置") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "ffa")
        }
        handler.register("tv", "#全员观战") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "spectators")
        }
        handler.register("teamdivide", "<2/3>", "HIDE") { args: Array<String>, player: PlayerHess ->
            handleTeamLayout(player, args[0])
        }
        handler.register("layout_2sides", "HIDE") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "2")
        }
        handler.register("layout_3sides", "HIDE") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "3")
        }
        handler.register("layout_ffa", "HIDE") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "ffa")
        }
        handler.register("layout_spectators", "HIDE") { _: Array<String>?, player: PlayerHess ->
            handleTeamLayout(player, "spectators")
        }
        handler.register("battleroom",  "clientCommands.battleroom") { _: Array<String>, player: PlayerHess ->
            requestBattleRoom(player, "client_battleroom_command")
        }
        handler.register("pause",  "clientCommands.pause") { _: Array<String>, player: PlayerHess ->
            if (!room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noStartGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.PAUSE_GAME)) {
                val ok = (gameModule.gameLinkFunction as? net.rwhps.server.game.headless.core.link.CheckedGameControlLink)?.tryPauseGame(true) == true
                player.sendSystemMessage(if (ok) "Game paused." else "暂停状态未改变：游戏线程不可用或对局状态已变化。")
            }
        }
        handler.register("unpause",  "clientCommands.unpause") { _: Array<String>, player: PlayerHess ->
            if (!room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noStartGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.UNPAUSE_GAME)) {
                val ok = (gameModule.gameLinkFunction as? net.rwhps.server.game.headless.core.link.CheckedGameControlLink)?.tryPauseGame(false) == true
                player.sendSystemMessage(if (ok) "Game unpaused." else "暂停状态未改变：游戏线程不可用或对局状态已变化。")
            }
        }
    }

    private fun registerGameCommandX(handler: CommandHandler) {
        handler.register("help", "clientCommands.help") { _: Array<String>?, player: PlayerHess ->
            val str = StringBuilder(512)
            str.append("当前服务端为CNKD NEXT 官方QQ群组帮助910085695").append(Data.LINE_SEPARATOR)
            for (command in handler.commandList) {
                if ("HIDE" == command.description) {
                    continue
                }
                val description = if (command.description.startsWith("#")) {
                    command.description.substring(1)
                } else {
                    player.i18NBundle.getinput(command.description)
                }
                str.append(".").append(command.text)
                    .append(if (command.paramText.isEmpty()) "" else " ")
                    .append(command.paramText)
                    .append(" - ")
                    .append(description)
                    .append(Data.LINE_SEPARATOR)
            }
            player.sendSystemMessage(str.toString(), SystemMessageTextMode.HELP)
        }
        handler.register("map", "<MapNumber...>", "clientCommands.map") { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.CHANGE_MAP)) {
                if (room.isStartGame) {
                    player.sendSystemMessage(player.getinput("err.startGame"))
                    return@register
                }
                if (StartModeMapLockSupport.isEnabled()) {
                    val lockedMapName = StartModeMapLockSupport.lockedMapName()
                    if (lockedMapName == null) {
                        player.sendSystemMessage(
                            "Map lock is enabled, but lockMap is empty or missing. Keeping current map: ${room.maps.mapName}"
                        )
                        return@register
                    }

                    val changed = StartModeMapLockSupport.applyLockedMapIfNeeded()
                    if (!room.maps.mapName.equals(lockedMapName, ignoreCase = true)) {
                        player.sendSystemMessage("Map lock is enabled, but applying the locked map failed. Current map: ${room.maps.mapName}")
                        return@register
                    }
                    player.sendSystemMessage("Map lock enabled. Locked map: $lockedMapName")
                    if (changed) {
                        room.call.sendSystemMessage(localeUtil.getinput("map.to", player.name, room.maps.mapName))
                    }
                    return@register
                }
                val response = StringBuilder(args[0])
                var i = 1
                val lens = args.size
                while (i < lens) {
                    response.append(" ").append(args[i])
                    i++
                }
                val inputMapName = response.toString().replace("'", "").replace(" ", "").replace("-", "").replace("_", "")
                val mapPlayer = MapManage.gameMaps[inputMapName]
                if (inputMapName.equals("DEF", ignoreCase = true)) {
                    if (!mutateLobbyOnGameThread("select_default_map") {
                            GameEngine.netEngine.az = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
                            GameEngine.netEngine.ay.a = ai.a
                            GameEngine.netEngine.ay.b = "[z;p10]Crossing Large (10p).tmx"
                            room.maps.mapName = "Crossing Large (10p)"
                            room.maps.mapType = GameMaps.MapType.DefaultMap
                            room.maps.mapData = null
                            room.maps.mapPlayer = ""
                            StartModeSurvivalMapSupport.recordSelectedMapName(GameEngine.netEngine.az)
                        }) {
                        sendLobbyMutationUnavailable(player)
                        return@register
                    }
                    room.call.sendSystemMessage(localeUtil.getinput("map.to", player.name, room.maps.mapName))
                    return@register
                }
                if (mapPlayer != null) {
                    val data = mapPlayer.split("@").toTypedArray()
                    if (!mutateLobbyOnGameThread("select_builtin_map") {
                            GameEngine.netEngine.az = "maps/skirmish/${data[1]}${data[0]}.tmx"
                            GameEngine.netEngine.ay.a = ai.a
                            GameEngine.netEngine.ay.b = "${data[1]}${data[0]}.tmx"
                            room.maps.mapName = data[0]
                            room.maps.mapType = GameMaps.MapType.DefaultMap
                            room.maps.mapData = null
                            room.maps.mapPlayer = ""
                            StartModeSurvivalMapSupport.recordSelectedMapName(GameEngine.netEngine.az)
                        }) {
                        sendLobbyMutationUnavailable(player)
                        return@register
                    }
                    room.call.sendSystemMessage(localeUtil.getinput("map.to", player.name, room.maps.mapName))
                } else {
                    if (MapManage.mapsData.size == 0) {
                        player.sendSystemMessage(localeUtil.getinput("map.no"))
                        return@register
                    }
                    if (notIsNumeric(inputMapName)) {
                        player.sendSystemMessage(localeUtil.getinput("err.noNumber"))
                        return@register
                    }
                    val mapIndex = inputMapName.toInt()
                    val mapKeys = MapManage.mapsData.keys.toSeq()
                    if (mapIndex < 0 || mapIndex >= mapKeys.size) {
                        player.sendSystemMessage(
                            "地图编号无效，当前共有 ${mapKeys.size} 张地图，可用范围 0-${mapKeys.size - 1}"
                        )
                        return@register
                    }
                    val mapKey = mapKeys[mapIndex]
                    val data = MapManage.mapsData[mapKey]!!
                    val displayName = MapManage.displayNameForKey(mapKey)
                    try {
                        data.readMap()
                    } catch (e: Exception) {
                        player.sendSystemMessage("读取地图失败: ${e.message ?: displayName}")
                        return@register
                    }
                    val name = data.runtimeFileName
                    if (!mutateLobbyOnGameThread("select_custom_map") {
                            room.maps.mapData = data
                            room.maps.mapType = data.mapType
                            room.maps.mapName = displayName
                            room.maps.mapPlayer = ""
                            GameEngine.netEngine.az = "/SD/rusted_warfare_maps/$name"
                            GameEngine.netEngine.ay.a = ai.entries.toTypedArray()[data.mapType.ordinal]
                            GameEngine.netEngine.ay.b = name
                            StartModeSurvivalMapSupport.recordSelectedMapName(GameEngine.netEngine.az)
                        }) {
                        sendLobbyMutationUnavailable(player)
                        return@register
                    }

                    player.sendSystemMessage(player.i18NBundle.getinput("map.custom.info"))
                }
                room.call.sendSystemMessage(localeUtil.getinput("map.to", player.name, room.maps.mapName))
            }
        }
        handler.register("maps", "[page]", "clientCommands.maps") { args: Array<String>?, player: PlayerHess ->
            if (!requirePlayerAction(player, StartModeRoomPermissionAction.VIEW_MAPS)) {
                return@register
            }
            if (room.isStartGame) {
                player.sendSystemMessage(localeUtil.getinput("err.startGame"))
                return@register
            }
            sendCustomMapPage(player, args?.firstOrNull())
        }
        handler.register("afk", "clientCommands.afk") { _: Array<String>?, player: PlayerHess ->
            if (!requirePlayerAction(player, StartModeRoomPermissionAction.AFK_TAKE_ADMIN)) {
                return@register
            }
            if (!room.isAfk) {
                player.sendSystemMessage(player.i18NBundle.getinput("ban.comm", "afk"))
                return@register
            }
            if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                player.sendSystemMessage(player.i18NBundle.getinput("afk.adminNo"))
            } else {
                if (room.isStartGame) {
                    player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                    return@register
                }
                if (Threads.containsTimeTask(CallTimeTask.PlayerAfkTask)) {
                    return@register
                }
                val admin = AtomicBoolean(true)
                room.playerManage.playerGroup.eachAllFind(
                    { p: PlayerHess -> StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(p) },
                    { _: PlayerHess -> admin.set(false) }
                )
                if (admin.get()) {
                    player.isAdmin = true
                    player.autoAdmin = true
                    room.call.sendSystemMessageLocal("afk.end.noAdmin", player.name)
                    StartModeRoomPermissionSupport.refreshOnlineAuthorityView("afk_claim_empty_host")
                    return@register
                }
                Threads.newCountdown(CallTimeTask.PlayerAfkTask, 30, TimeUnit.SECONDS) {
                    if (player.con == null) {
                        return@newCountdown
                    }
                    if (room.isStartGame) {
                        player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                        return@newCountdown
                    }
                    if (!StartModeRoomPermissionSupport.require(
                            player,
                            StartModeRoomPermissionAction.AFK_TAKE_ADMIN
                        )) {
                        return@newCountdown
                    }
                    var onlineSuperAdmin = false
                    room.playerManage.playerGroup.eachFind({ p: PlayerHess ->
                        p.con != null && p.superAdmin &&
                            StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(p)
                    }) { onlineSuperAdmin = true }
                    if (onlineSuperAdmin) {
                        player.sendSystemMessage(player.i18NBundle.getinput("afk.adminNo"))
                        return@newCountdown
                    }
                    if (!StartModeRoomPermissionSupport.transferHost(room, player)) return@newCountdown
                    room.call.sendSystemMessageLocal("afk.end.ok", player.name)
                    StartModeRoomPermissionSupport.refreshOnlineAuthorityView("afk_host_takeover")
                }
                room.call.sendSystemMessageLocal("afk.start", player.name)
            }
        }
        handler.register("status", "clientCommands.status") { _: Array<String>?, player: PlayerHess ->
            if (!requirePlayerAction(player, StartModeRoomPermissionAction.VIEW_STATUS)) {
                return@register
            }
            player.sendSystemMessage(
                    player.i18NBundle.getinput(
                            "status.version", room.playerManage.countConnectedOccupants(), 0, Data.SERVER_CORE_VERSION, "RW-HPS-Hess"
                    )
            )
        }
        handler.register("vote", "<gameover>", "clientCommands.vote") { _: Array<String>?, player: PlayerHess ->
            if (!requirePlayerAction(player, StartModeRoomPermissionAction.VOTE_GAME_OVER)) {
                return@register
            }
            Vote.requestGameOver(player)
        }
        handler.register("iunit", "<$PlayerPosition> <unitID>", "clientCommands.iunit") { args: Array<String>, player: PlayerHess ->
            if (room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.startGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.SET_PLAYER_START_UNIT)) {
                val site = playerIndexFromPositionArgument(args[0], player) ?: return@register
                if (notIsNumeric(args[1])) {
                    player.sendSystemMessage(player.i18NBundle.getinput("err.noNumber"))
                    return@register
                }

                val startUnit = args[1].toInt()
                var playerUnitName: String? = null
                if (!mutateLobbyOnGameThread("set_player_start_unit") {
                        val playerUnit = room.playerManage.getPlayer(site)
                        if (playerUnit != null) {
                            playerUnit.startUnit = startUnit
                            playerUnitName = playerUnit.name
                        }
                    }) {
                    sendLobbyMutationUnavailable(player)
                    return@register
                }
                if (playerUnitName != null) {
                    player.sendSystemMessage("Player $playerUnitName starting unit set to $startUnit.")
                } else {
                    player.sendSystemMessage(player.i18NBundle.getinput("err.player.no.site", args[0]))
                }
            }
        }
    }

    private fun registerGameCustomCommand(handler: CommandHandler) {
        handler.register("summon", "<unitName>", "clientCommands.summon") { args: Array<String>, player: PlayerHess ->
            if (!room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noStartGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.SPAWN_UNITS)) {
                val unit = parseUnitNameArgument(args[0], player) ?: return@register
                registerOneShotMapPointAction(player, player.i18NBundle.getinput("clientCommands.summon.ping"), StartModeRoomPermissionAction.SPAWN_UNITS) { server, _, x, y ->
                    server.gameSummon(unit, x, y)
                }
            }
        }
        handler.register(
                "addmoney", "<$PlayerPosition> <money>", "clientCommands.addmoney"
        ) { args: Array<String>, player: PlayerHess ->
            if (requireAdminAction(player, StartModeRoomPermissionAction.ADD_MONEY_IN_GAME)) {
                val result = StartModeMoneyCommandSupport.addMoney(gameModule, args[0], args[1])
                player.sendSystemMessage(result.message)
            }
        }
        handler.register("nuke",  "clientCommands.nuke") { _: Array<String>, player: PlayerHess ->
            if (!room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noStartGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.FIRE_NUKE_IN_GAME)) {
                registerOneShotMapPointAction(player, player.i18NBundle.getinput("clientCommands.nuke.ping"), StartModeRoomPermissionAction.FIRE_NUKE_IN_GAME) { _, _, x, y ->
                    GameEngine.data.gameFunction.suspendMainThreadOperations {
                        val obj = SupBuild::class.java.findField("n", com.corrodinggames.rts.game.units.a.s::class.java)!!.get(null) as com.corrodinggames.rts.game.units.a.s
                        val no = com.corrodinggames.rts.game.units.h(false)
                        no.a(obj, false, PointF(x, y), null)
                        no.b(n.k(player.index))
                        //no.bX = n.k(player.site)
                        GameEngine.gameEngine.bS.j(no)
                        gameModule.gameLinkFunction.allPlayerSync()
                    }
                }
            }
        }
        handler.register("clone",  "clientCommands.clone") { _: Array<String>, player: PlayerHess ->
            if (!room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noStartGame"))
                return@register
            }
            if (requireAdminAction(player, StartModeRoomPermissionAction.CLONE_UNITS)) {
                registerOneShotMapPointAction(player, player.i18NBundle.getinput("clientCommands.clone.ping"), StartModeRoomPermissionAction.CLONE_UNITS) { _, _, x, y ->
                    gameModule.gameFunction.suspendMainThreadOperations {
                        val obj = SupBuild::class.java.findField("j", com.corrodinggames.rts.game.units.a.s::class.java)!!.get(null) as com.corrodinggames.rts.game.units.a.s
                        val no = com.corrodinggames.rts.game.units.h(false)
                        no.bX = n.k(player.index)
                        no.a(obj, false, PointF(x, y), null)
                        GameEngine.gameEngine.bS.j(no)
                        gameModule.gameLinkFunction.allPlayerSync()
                    }
                }
            }
        }
        handler.register("myid", "clientCommands.-") { _: Array<String>, player: PlayerHess ->
            if (!requirePlayerAction(player, StartModeRoomPermissionAction.VIEW_MY_ID)) {
                return@register
            }
            player.sendSystemMessage(player.i18NBundle.getinput("myid", BigInteger(HexUtils.decodeHex(player.connectHexID)).toInt().let { if (it < 0) -it else it }))
        }
        handler.register("surrenderstatus", "#团队投降进度") { _: Array<String>?, player: PlayerHess ->
            if (!room.isStartGame) {
                player.sendSystemMessage("当前不在游戏中，暂无团队投降进度。")
                return@register
            }
            if (Data.configStartMode.enableIndividualSurrender) {
                player.sendSystemMessage("单人投降模式已开启，可使用原版投降按钮或输入 surrender/ff。")
                return@register
            }
            player.sendSystemMessage(StartModeSurrenderSupport.getSurrenderVoteStatusMessage(room, player.team))
        }
    }

    init {
        registerGameCoreCommand(handler)
        registerGameCommandX(handler)
        registerGameCustomCommand(handler)
    }

    companion object {
        private const val MANUAL_BATTLE_ROOM_RETURN_SECONDS = 5
        private val localeUtil = Data.i18NBundle
        private val gameModule = HeadlessModuleManage.hessLoaderMap[this::class.java.classLoader.toString()]!!
        private val room = gameModule.room

        @JvmStatic
        internal fun requestStart(player: PlayerHess?): Boolean {
            return StartModeAutoStartSupport.requestStart(player)
        }

        /**
         * START-FREEZE: same as [requestStart] but runs the blocking start OFF the Netty worker
         * thread. See [StartModeAutoStartSupport.requestStartOffNetworkThread] for the evidence.
         */
        @JvmStatic
        internal fun requestStartOffNetworkThread(player: PlayerHess?, trigger: String): Boolean {
            return StartModeAutoStartSupport.requestStartOffNetworkThread(player, trigger)
        }

        @JvmStatic
        internal fun requestBattleRoom(player: PlayerHess, reason: String = "client_battleroom_command"): Boolean {
            if (!room.isStartGame) {
                player.sendSystemMessage(player.i18NBundle.getinput("err.noStartGame"))
                return false
            }
            val allowed = if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                StartModeRoomPermissionSupport.require(player, StartModeRoomPermissionAction.RETURN_TO_BATTLE_ROOM)
            } else {
                StartModeRoomPermissionSupport.requireDelegatedPlayerAction(
                    player,
                    StartModeRoomPermissionAction.RETURN_TO_BATTLE_ROOM
                )
            }
            if (!allowed) {
                return false
            }
            if (room.isBattleRoomTransitionInProgress()) {
                player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.transitionInProgress"))
                return false
            }
            var transitionCallError: Throwable? = null
            val transitionScheduled = runCatching {
                gameModule.gameLinkFunction.battleRoom(
                        time = MANUAL_BATTLE_ROOM_RETURN_SECONDS,
                        onCompleted = {
                            runCatching {
                                player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.returnCompleted"))
                            }
                        },
                        onFailed = {
                            runCatching {
                                player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.returnFailed"))
                            }
                            room.gr()
                        }
                    )
            }.getOrElse { error ->
                transitionCallError = error
                false
            }
            if (!transitionScheduled) {
                if (transitionCallError != null) {
                    Log.error("[BattleRoom] Failed to schedule return for ${player.name}", transitionCallError!!)
                    runCatching {
                        player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.returnFailed"))
                    }.onFailure { messageError ->
                        Log.warn("[BattleRoom] Failed to send return failure message to ${player.name}", messageError)
                    }
                    room.gr()
                    return false
                }
                if (room.isBattleRoomTransitionInProgress()) {
                    player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.transitionInProgress"))
                } else if (room.isGameOverResetInProgress() || StartModeGameOverReturnSupport.isSettling()) {
                    player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.resetInProgress"))
                } else {
                    player.sendSystemMessage(player.i18NBundle.getinput("battleRoom.returnFailed"))
                    room.gr()
                }
                return false
            }
            player.sendSystemMessage(
                player.i18NBundle.getinput("battleRoom.returnScheduled", MANUAL_BATTLE_ROOM_RETURN_SECONDS)
            )
            return true
        }
    }
}
