/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanRoomPermissionConfig
import net.rwhps.server.data.bean.BeanRoomPermissionPlayerConfig
import net.rwhps.server.data.bean.BeanRoomPermissionRoleConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.util.log.Log

internal enum class StartModeRoomPermissionAction(val label: String) {
    START_GAME("开始游戏"),
    CHANGE_MAP("切换地图"),
    VIEW_MAPS("查看地图列表"),
    KICK_PLAYERS("踢出玩家"),
    ADD_AI("添加 AI"),
    SET_CREDITS("修改初始资金"),
    SET_INCOME("修改收入倍率"),
    SET_FOG("修改迷雾"),
    SET_STARTING_UNITS("修改默认出兵"),
    TOGGLE_NUKES("切换禁核"),
    TOGGLE_SHARED_CONTROL("切换共享控制"),
    SET_AI_DIFFICULTY("调整 AI 难度"),
    MOVE_PLAYERS("调整玩家位置/队伍"),
    CHANGE_TEAM("调整队伍"),
    TRANSFER_HOST("转移房主"),
    TOGGLE_MIXED_TEAM("切换混队模式"),
    RETURN_TO_BATTLE_ROOM("回到战备室"),
    PAUSE_GAME("暂停游戏"),
    UNPAUSE_GAME("继续游戏"),
    SET_PLAYER_START_UNIT("设置玩家初始单位"),
    SPAWN_UNITS("生成单位"),
    ADD_MONEY_IN_GAME("对局内加钱"),
    FIRE_NUKE_IN_GAME("对局内发射核弹"),
    CLONE_UNITS("复制单位"),
    VOTE_GAME_OVER("发起结束投票"),
    AFK_TAKE_ADMIN("通过 AFK 接管管理员"),
    VIEW_STATUS("查看房间状态"),
    VIEW_MY_ID("查看自己的 ID")
}

private enum class StartModeRoomRole(val label: String) {
    HOST("房主"),
    ADMIN("管理员"),
    PLAYER("普通玩家")
}

internal object StartModeRoomPermissionSupport {
    private fun config(): BeanRoomPermissionConfig {
        return Data.configRoomPermission ?: BeanRoomPermissionConfig()
    }

    private fun enabled(): Boolean = config().enable

    private fun isSuperAdminBypass(player: PlayerHess): Boolean {
        return enabled() && config().moderation.superAdminBypass && player.superAdmin
    }

    fun hasEffectiveAdminAuthority(player: PlayerHess): Boolean {
        return StartModeHostAuthorityPolicy.hasAdminAuthority(
            ordinaryHostAuthorityEnabled = Data.configServer.oneAdmin,
            isAdmin = player.isAdmin,
            superAdmin = player.superAdmin,
        )
    }

    fun hasEffectiveHostAuthority(player: PlayerHess): Boolean {
        return StartModeHostAuthorityPolicy.hasHostAuthority(
            ordinaryHostAuthorityEnabled = Data.configServer.oneAdmin,
            isAdmin = player.isAdmin,
            autoAdmin = player.autoAdmin,
            superAdmin = player.superAdmin,
        )
    }

    fun releaseTemporaryHost(player: PlayerHess) {
        val registered = Data.core.admin.playerAdminData.entries.any {
            it.key.equals(player.connectHexID.trim(), ignoreCase = true) && it.value.admin
        }
        val flags = StartModeHostAuthorityPolicy.releaseTemporaryHost(
            player.isAdmin, player.autoAdmin, player.superAdmin, registered
        )
        player.isAdmin = flags.isAdmin
        player.autoAdmin = flags.autoAdmin
        player.superAdmin = flags.superAdmin
    }

    fun transferHost(room: ServerRoom, target: PlayerHess): Boolean {
        if (!ordinaryHostGrantEnabled() || !HeadlessModuleManage.initHPS()) return false
        val module = HeadlessModuleManage.hps
        if (module.room !== room) return false
        var changed = false
        val dispatched = module.gameFunction.suspendMainThreadOperations {
            if (room.isStartGame || room.isLobbyPlayerSyncBlocked() || StartModeAutoStartSupport.isStartOperationInProgress()) {
                return@suspendMainThreadOperations
            }
            synchronized(module.gameLinkServerData.teamOperationsSyncObject) {
                val players = room.playerManage.playerGroup.toList()
                if (target.isAi || players.none { it === target }) return@synchronized
                players.filter { it !== target && it.autoAdmin }.forEach(::releaseTemporaryHost)
                target.isAdmin = true
                target.autoAdmin = true
                changed = true
            }
        }
        return dispatched && changed
    }

    private fun ordinaryHostGrantEnabled(): Boolean {
        return StartModeHostAuthorityPolicy.allowOrdinaryHostGrant(
            ordinaryHostAuthorityEnabled = Data.configServer.oneAdmin,
        )
    }

    private fun denyDisabledHostGrant(player: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        if (
            ordinaryHostGrantEnabled() ||
            (action != StartModeRoomPermissionAction.TRANSFER_HOST && action != StartModeRoomPermissionAction.AFK_TAKE_ADMIN)
        ) {
            return false
        }
        player.sendSystemMessage("已关闭保持至少一个房主，普通玩家不能接管或转移房主权限。")
        return true
    }

    private fun roleOf(player: PlayerHess): StartModeRoomRole {
        return when {
            !hasEffectiveAdminAuthority(player) -> StartModeRoomRole.PLAYER
            player.superAdmin -> StartModeRoomRole.ADMIN
            player.autoAdmin -> StartModeRoomRole.HOST
            else -> StartModeRoomRole.ADMIN
        }
    }

    private fun roleConfigFor(role: StartModeRoomRole): BeanRoomPermissionRoleConfig {
        val current = config()
        return when (role) {
            StartModeRoomRole.HOST -> current.host
            StartModeRoomRole.ADMIN -> current.admin
            StartModeRoomRole.PLAYER -> error("Player role uses BeanRoomPermissionPlayerConfig, not BeanRoomPermissionRoleConfig")
        }
    }

    private fun isPlayerScopedAction(action: StartModeRoomPermissionAction): Boolean {
        return when (action) {
            StartModeRoomPermissionAction.VOTE_GAME_OVER,
            StartModeRoomPermissionAction.AFK_TAKE_ADMIN,
            StartModeRoomPermissionAction.VIEW_STATUS,
            StartModeRoomPermissionAction.VIEW_MY_ID -> true

            else -> false
        }
    }

    private fun playerAllows(action: StartModeRoomPermissionAction): Boolean {
        val player = config().player
        return when (action) {
            StartModeRoomPermissionAction.VOTE_GAME_OVER -> player.allowVoteGameOver
            StartModeRoomPermissionAction.AFK_TAKE_ADMIN -> player.allowAfkTakeAdmin
            StartModeRoomPermissionAction.VIEW_STATUS -> player.allowViewStatus
            StartModeRoomPermissionAction.VIEW_MY_ID -> player.allowViewMyId
            StartModeRoomPermissionAction.VIEW_MAPS -> player.allowViewMaps
            StartModeRoomPermissionAction.START_GAME -> player.allowStartGame
            StartModeRoomPermissionAction.CHANGE_MAP -> player.allowChangeMap
            StartModeRoomPermissionAction.ADD_AI -> player.allowAddAi
            StartModeRoomPermissionAction.SET_CREDITS -> player.allowSetCredits
            StartModeRoomPermissionAction.SET_INCOME -> player.allowSetIncome
            StartModeRoomPermissionAction.SET_FOG -> player.allowSetFog
            StartModeRoomPermissionAction.SET_STARTING_UNITS -> player.allowSetStartingUnits
            StartModeRoomPermissionAction.TOGGLE_NUKES -> player.allowToggleNukes
            StartModeRoomPermissionAction.TOGGLE_SHARED_CONTROL -> player.allowToggleSharedControl
            StartModeRoomPermissionAction.SET_AI_DIFFICULTY -> player.allowSetAiDifficulty
            StartModeRoomPermissionAction.MOVE_PLAYERS -> player.allowMovePlayers
            StartModeRoomPermissionAction.CHANGE_TEAM -> player.allowChangeTeam
            StartModeRoomPermissionAction.TRANSFER_HOST -> player.allowTransferHost
            StartModeRoomPermissionAction.TOGGLE_MIXED_TEAM -> player.allowToggleMixedTeam
            StartModeRoomPermissionAction.KICK_PLAYERS,
            StartModeRoomPermissionAction.RETURN_TO_BATTLE_ROOM,
            StartModeRoomPermissionAction.PAUSE_GAME,
            StartModeRoomPermissionAction.UNPAUSE_GAME,
            StartModeRoomPermissionAction.SET_PLAYER_START_UNIT,
            StartModeRoomPermissionAction.SPAWN_UNITS,
            StartModeRoomPermissionAction.ADD_MONEY_IN_GAME,
            StartModeRoomPermissionAction.FIRE_NUKE_IN_GAME,
            StartModeRoomPermissionAction.CLONE_UNITS -> false
        }
    }

    private fun roleAllows(config: BeanRoomPermissionRoleConfig, action: StartModeRoomPermissionAction): Boolean {
        return when (action) {
            StartModeRoomPermissionAction.START_GAME -> config.allowStartGame
            StartModeRoomPermissionAction.CHANGE_MAP -> config.allowChangeMap
            StartModeRoomPermissionAction.VIEW_MAPS -> config.allowViewMaps
            StartModeRoomPermissionAction.KICK_PLAYERS -> config.allowKickPlayers
            StartModeRoomPermissionAction.ADD_AI -> config.allowAddAi
            StartModeRoomPermissionAction.SET_CREDITS -> config.allowSetCredits
            StartModeRoomPermissionAction.SET_INCOME -> config.allowSetIncome
            StartModeRoomPermissionAction.SET_FOG -> config.allowSetFog
            StartModeRoomPermissionAction.SET_STARTING_UNITS -> config.allowSetStartingUnits
            StartModeRoomPermissionAction.TOGGLE_NUKES -> config.allowToggleNukes
            StartModeRoomPermissionAction.TOGGLE_SHARED_CONTROL -> config.allowToggleSharedControl
            StartModeRoomPermissionAction.SET_AI_DIFFICULTY -> config.allowSetAiDifficulty
            StartModeRoomPermissionAction.MOVE_PLAYERS -> config.allowMovePlayers
            StartModeRoomPermissionAction.CHANGE_TEAM -> config.allowChangeTeam
            StartModeRoomPermissionAction.TRANSFER_HOST -> config.allowTransferHost != false
            StartModeRoomPermissionAction.TOGGLE_MIXED_TEAM -> config.allowToggleMixedTeam
            StartModeRoomPermissionAction.RETURN_TO_BATTLE_ROOM -> config.allowReturnToBattleRoom
            StartModeRoomPermissionAction.PAUSE_GAME -> config.allowPauseGame
            StartModeRoomPermissionAction.UNPAUSE_GAME -> config.allowUnpauseGame
            StartModeRoomPermissionAction.SET_PLAYER_START_UNIT -> config.allowSetPlayerStartUnit
            StartModeRoomPermissionAction.SPAWN_UNITS -> config.allowSpawnUnits
            StartModeRoomPermissionAction.ADD_MONEY_IN_GAME -> config.allowAddMoneyInGame
            StartModeRoomPermissionAction.FIRE_NUKE_IN_GAME -> config.allowFireNukeInGame
            StartModeRoomPermissionAction.CLONE_UNITS -> config.allowCloneUnits
            StartModeRoomPermissionAction.VOTE_GAME_OVER,
            StartModeRoomPermissionAction.AFK_TAKE_ADMIN,
            StartModeRoomPermissionAction.VIEW_STATUS,
            StartModeRoomPermissionAction.VIEW_MY_ID -> true
        }
    }

    fun require(player: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        if (denyDisabledHostGrant(player, action)) {
            return false
        }
        if (!enabled()) {
            return true
        }
        if (isSuperAdminBypass(player)) {
            return true
        }

        val role = roleOf(player)
        val allowed = when (role) {
            StartModeRoomRole.PLAYER -> playerAllows(action)
            StartModeRoomRole.HOST,
            StartModeRoomRole.ADMIN -> {
                if (isPlayerScopedAction(action)) {
                    true
                } else {
                    roleAllows(roleConfigFor(role), action)
                }
            }
        }

        if (!allowed) {
            player.sendSystemMessage("RoomPermissionConfig 已禁止${role.label}执行：${action.label}")
        }
        return allowed
    }

    fun requireDelegatedPlayerAction(player: PlayerHess, action: StartModeRoomPermissionAction): Boolean {
        if (denyDisabledHostGrant(player, action)) {
            return false
        }
        if (!enabled()) {
            player.sendSystemMessage(player.i18NBundle.getinput("err.noAdmin"))
            return false
        }
        if (isSuperAdminBypass(player)) {
            return true
        }
        if (roleOf(player) != StartModeRoomRole.PLAYER) {
            return require(player, action)
        }

        val allowed = playerAllows(action)
        if (!allowed) {
            player.sendSystemMessage("RoomPermissionConfig 已禁止普通玩家执行：${action.label}")
        }
        return allowed
    }

    fun allowAutoAdminAssignment(): Boolean {
        if (!ordinaryHostGrantEnabled()) {
            return false
        }
        return !enabled() || config().moderation.allowAutoAdminAssignment
    }

    fun allowAutoAdminTransferOnLeave(): Boolean {
        if (!ordinaryHostGrantEnabled()) {
            return false
        }
        return !enabled() || config().moderation.allowAutoAdminTransferOnLeave
    }

    fun allowManualAdminGrantFromConsole(): Boolean {
        return !enabled() || config().moderation.allowManualAdminGrantFromConsole
    }

    fun allowGiveAdminFromConsole(): Boolean {
        if (!ordinaryHostGrantEnabled()) {
            return false
        }
        return !enabled() || config().moderation.allowGiveAdminFromConsole
    }

    fun visibleHostPlayer(room: ServerRoom): PlayerHess? {
        val onlinePlayers = room.playerManage.playerGroup
            .filter { !it.isAi && it.con != null }
            .sortedBy { it.index }

        return onlinePlayers.firstOrNull {
            StartModeHostAuthorityPolicy.hasOrdinaryHostAuthority(
                ordinaryHostAuthorityEnabled = Data.configServer.oneAdmin,
                isAdmin = it.isAdmin,
                autoAdmin = it.autoAdmin,
                superAdmin = it.superAdmin,
            )
        }
    }

    fun isVisibleHost(player: PlayerHess, room: ServerRoom): Boolean {
        if (player.superAdmin && hasEffectiveHostAuthority(player)) {
            return true
        }
        val visibleHost = visibleHostPlayer(room) ?: return false
        return visibleHost === player || visibleHost.connectHexID == player.connectHexID
    }

    fun refreshOnlineAuthorityView(reason: String): Boolean {
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }
        val module = HeadlessModuleManage.hps
        val room = module.room
        if (room.isStartGame) {
            Log.debug("[RoomPermission] Authority view refresh deferred until lobby: reason=$reason")
            return false
        }
        if (room.isLobbyPlayerSyncBlocked()) {
            Log.debug("[RoomPermission] Authority view refresh deferred during battle-room transition: reason=$reason")
            return false
        }

        val refreshAttempt = runCatching { module.gameLinkFunction.syncLobbyPlayers() }
        val refreshResult = refreshAttempt.getOrNull()
        val teamListRefreshed = refreshResult?.success == true
        refreshAttempt.exceptionOrNull()?.let { error ->
            Log.warn("[RoomPermission] Failed to refresh authority lobby state: reason=$reason", error)
        }
        refreshResult?.error?.let { error ->
            Log.warn("[RoomPermission] Failed to refresh authority lobby state: reason=$reason", error)
        }
        if (!teamListRefreshed) {
            Log.debug("[RoomPermission] Authority lobby refresh deferred until engine ready: reason=$reason")
        }
        Log.debug(
            "[RoomPermission] Authority view refresh finished: reason=$reason, " +
                "teamList=$teamListRefreshed"
        )
        return teamListRefreshed
    }

    fun reconcileOnlineAuthorityState(
        reason: String,
        excludedOrdinaryHostUuids: Set<String> = emptySet()
    ): Boolean {
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }
        val room = HeadlessModuleManage.hps.room

        val players = mutableListOf<PlayerHess>()
        room.playerManage.playerAll.eachAll { player ->
            if (!player.isAi) {
                players += player
            }
        }
        var changed = false
        if (!Data.configServer.oneAdmin) {
            players.forEach { player ->
                val next = StartModeHostAuthorityPolicy.disableOrdinaryAuthority(
                    isAdmin = player.isAdmin,
                    autoAdmin = player.autoAdmin,
                    superAdmin = player.superAdmin
                )
                if (player.isAdmin != next.isAdmin || player.autoAdmin != next.autoAdmin) {
                    player.isAdmin = next.isAdmin
                    player.autoAdmin = next.autoAdmin
                    changed = true
                }
            }
        } else {
            val connected = players.filter { it.con != null }.sortedBy { it.index }
            connected.forEach { player ->
                Data.core.admin.playerAdminData[player.connectHexID]?.let { adminData ->
                    if (player.isAdmin != adminData.admin || player.superAdmin != adminData.superAdmin) {
                        player.isAdmin = adminData.admin
                        player.superAdmin = adminData.superAdmin
                        changed = true
                    }
                }
            }
            val existingHost = connected.firstOrNull {
                it.autoAdmin && it.isAdmin && !it.superAdmin
            }
            players.filter { it !== existingHost && !it.superAdmin && it.autoAdmin }.forEach { player ->
                releaseTemporaryHost(player)
                changed = true
            }
            if (
                existingHost == null &&
                connected.none { player ->
                    StartModeHostAuthorityPolicy.hasOrdinaryHostAuthority(
                        ordinaryHostAuthorityEnabled = Data.configServer.oneAdmin,
                        isAdmin = player.isAdmin,
                        autoAdmin = player.autoAdmin,
                        superAdmin = player.superAdmin,
                    )
                } &&
                allowAutoAdminAssignment()
            ) {
                val candidates = connected.filter { player ->
                    excludedOrdinaryHostUuids.none { excluded ->
                        excluded.equals(player.connectHexID.trim(), ignoreCase = true)
                    }
                }
                (candidates.firstOrNull { it.isAdmin && !it.superAdmin }
                    ?: candidates.firstOrNull())?.let { player ->
                    if (!player.isAdmin || !player.autoAdmin) {
                        player.isAdmin = true
                        player.autoAdmin = true
                        changed = true
                    }
                }
            }
        }
        Log.clog(
            "[RoomPermission] Online authority state reconciled: reason=$reason, " +
                "oneAdmin=${Data.configServer.oneAdmin}, players=${players.size}, changed=$changed"
        )
        return changed
    }

    fun hasOtherOnlineEffectiveHost(room: ServerRoom, excluded: PlayerHess): Boolean {
        var found = false
        room.playerManage.playerGroup.eachFind({ player ->
            player !== excluded && player.con != null && !player.isAi &&
                StartModeHostAuthorityPolicy.hasOrdinaryHostAuthority(
                    ordinaryHostAuthorityEnabled = Data.configServer.oneAdmin,
                    isAdmin = player.isAdmin,
                    autoAdmin = player.autoAdmin,
                    superAdmin = player.superAdmin,
                )
        }) { found = true }
        return found
    }

    fun buildDiagnostics(): List<String> {
        val current = config()
        val lines = mutableListOf<String>()
        lines += "========== ROOM PERMISSION =========="
        lines += "enabled=${current.enable}"
        lines += "moderation.superAdminBypass=${current.moderation.superAdminBypass}"
        lines += "moderation.allowAutoAdminAssignment=${current.moderation.allowAutoAdminAssignment}"
        lines += "moderation.allowAutoAdminTransferOnLeave=${current.moderation.allowAutoAdminTransferOnLeave}"
        lines += "moderation.allowManualAdminGrantFromConsole=${current.moderation.allowManualAdminGrantFromConsole}"
        lines += "moderation.allowGiveAdminFromConsole=${current.moderation.allowGiveAdminFromConsole}"
        lines += "hostAuthority.oneAdminConfigured=${Data.configServer.oneAdmin}"
        lines += "hostAuthority.ordinaryEffective=${ordinaryHostGrantEnabled()}"
        lines += "hostAuthority.superAdminExemptFromOneAdmin=true"
        lines += "moderation.autoAdminAssignmentEffective=${allowAutoAdminAssignment()}"
        lines += "moderation.autoAdminTransferOnLeaveEffective=${allowAutoAdminTransferOnLeave()}"
        lines += "moderation.manualAdminGrantFromConsoleEffective=${allowManualAdminGrantFromConsole()}"
        lines += "moderation.giveAdminFromConsoleEffective=${allowGiveAdminFromConsole()}"
        lines += "host.allowed=${renderAllowedRoleActions(current.host)}"
        lines += "admin.allowed=${renderAllowedRoleActions(current.admin)}"
        lines += "player.allowed=${renderAllowedPlayerActions(current.player)}"
        return lines
    }

    private fun renderAllowedRoleActions(config: BeanRoomPermissionRoleConfig): String {
        return StartModeRoomPermissionAction.entries
            .filterNot(::isPlayerScopedAction)
            .filter { roleAllows(config, it) }
            .joinToString(",") { it.name }
            .ifBlank { "<none>" }
    }

    private fun renderAllowedPlayerActions(config: BeanRoomPermissionPlayerConfig? = null): String {
        val current = config ?: this.config().player
        val allowed = mutableListOf<String>()
        if (current.allowVoteGameOver) allowed += StartModeRoomPermissionAction.VOTE_GAME_OVER.name
        if (current.allowAfkTakeAdmin) allowed += StartModeRoomPermissionAction.AFK_TAKE_ADMIN.name
        if (current.allowViewStatus) allowed += StartModeRoomPermissionAction.VIEW_STATUS.name
        if (current.allowViewMyId) allowed += StartModeRoomPermissionAction.VIEW_MY_ID.name
        if (current.allowViewMaps) allowed += StartModeRoomPermissionAction.VIEW_MAPS.name
        if (current.allowStartGame) allowed += StartModeRoomPermissionAction.START_GAME.name
        if (current.allowChangeMap) allowed += StartModeRoomPermissionAction.CHANGE_MAP.name
        if (current.allowAddAi) allowed += StartModeRoomPermissionAction.ADD_AI.name
        if (current.allowSetCredits) allowed += StartModeRoomPermissionAction.SET_CREDITS.name
        if (current.allowSetIncome) allowed += StartModeRoomPermissionAction.SET_INCOME.name
        if (current.allowSetFog) allowed += StartModeRoomPermissionAction.SET_FOG.name
        if (current.allowSetStartingUnits) allowed += StartModeRoomPermissionAction.SET_STARTING_UNITS.name
        if (current.allowToggleNukes) allowed += StartModeRoomPermissionAction.TOGGLE_NUKES.name
        if (current.allowToggleSharedControl) allowed += StartModeRoomPermissionAction.TOGGLE_SHARED_CONTROL.name
        if (current.allowSetAiDifficulty) allowed += StartModeRoomPermissionAction.SET_AI_DIFFICULTY.name
        if (current.allowMovePlayers) allowed += StartModeRoomPermissionAction.MOVE_PLAYERS.name
        if (current.allowChangeTeam) allowed += StartModeRoomPermissionAction.CHANGE_TEAM.name
        if (current.allowTransferHost != false) allowed += StartModeRoomPermissionAction.TRANSFER_HOST.name
        if (current.allowToggleMixedTeam) allowed += StartModeRoomPermissionAction.TOGGLE_MIXED_TEAM.name
        return allowed.joinToString(",").ifBlank { "<none>" }
    }
}
