/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.command

import com.corrodinggames.rts.game.n
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.Data.LINE_SEPARATOR
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.event.game.PlayerBanEvent
import net.rwhps.server.game.event.game.PlayerIpBanEvent
import net.rwhps.server.game.event.game.PlayerIpUnBanEvent
import net.rwhps.server.game.event.game.PlayerUnBanEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.manage.ModCompatibilitySupport
import net.rwhps.server.game.manage.ModManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMoneyCommandSupport
import net.rwhps.server.plugin.internal.headless.support.StartModePersistentAccessSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSpectatorSupport
import net.rwhps.server.plugin.internal.headless.inject.util.TabCompleterProcess
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.BaseMap.Companion.toSeq
import net.rwhps.server.util.Font16
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.Time
import net.rwhps.server.util.Time.getTimeFutureMillis
import net.rwhps.server.util.console.tab.TabDefaultEnum.PlayerPosition
import net.rwhps.server.util.console.tab.TabDefaultEnum.PlayerPositionNoAI
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.inline.coverConnect
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.error
import org.newdawn.slick.Graphics
import java.io.IOException

/**
 * @author Dr (dr@der.kim)
 */
internal class ServerCommands(handler: CommandHandler) {
    private val mapListPageSize = 50
    private val textBuildMaxChars = 32

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
            Log.warn("[Headless] Failed to apply console lobby mutation on the game thread: $action")
        } else if (battleRoomRecoveryBeforeDispatch) {
            Log.debug("[Headless] Deferred console lobby mutation during battle-room recovery: $action")
        } else if (startOperationBeforeDispatch) {
            Log.debug("[Headless] Deferred console lobby mutation while start confirmation is pending: $action")
        }
        return operationSucceeded && !roomStartedBeforeDispatch && !battleRoomRecoveryBeforeDispatch && !startOperationBeforeDispatch
    }

    private fun appendCustomMapPage(response: StringBuilder, rawPage: String?): Boolean {
        val mapKeys = MapManage.mapsData.keys.toSeq()
        if (mapKeys.size == 0) {
            response.append("No custom maps loaded.")
            return false
        }
        val maxPage = ((mapKeys.size - 1) / mapListPageSize) + 1
        val page = rawPage
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
            ?: 1
        if (page !in 1..maxPage) {
            response.append("Map page out of range. Available pages: 1-").append(maxPage)
            return false
        }

        val fromIndex = (page - 1) * mapListPageSize
        val toIndexExclusive = kotlin.math.min(fromIndex + mapListPageSize, mapKeys.size)
        response.append("Custom maps page ").append(page).append("/").append(maxPage)
            .append(", total ").append(mapKeys.size).append(LINE_SEPARATOR)
        for (index in fromIndex until toIndexExclusive) {
            response.append(
                localeUtil.getinput("maps.info", index, MapManage.displayNameForKey(mapKeys[index]))
            ).append(LINE_SEPARATOR)
        }
        if (page < maxPage) {
            response.append("Use maps ").append(page + 1).append(" for next page.")
        }
        return true
    }

    private fun maintenanceBlockedByRoomLifecycle(): Boolean {
        return room.isStartGame ||
            room.isGameOverResetInProgress() ||
            room.isBattleRoomTransitionInProgress() ||
            room.isBattleRoomLobbyRecoveryInProgress() ||
            StartModeAutoStartSupport.isStartOperationInProgress()
    }

    private fun parseTextBuildIndex(raw: String?): Int? {
        if (raw.isNullOrBlank()) {
            return -1
        }
        return if (IsUtils.isNumericNegative(raw)) raw.toInt() else null
    }

    private fun registerPlayerCommand(handler: CommandHandler) {
        handler.register("say", "<text...>", "serverCommands.say") { arg: Array<String>, log: StrCons ->
            val msg = arg[0].replace("<>", "")
            room.call.sendSystemMessage(msg)
            log("All players has received the message : {0}", msg)
        }
        handler.register("whisper", "<$PlayerPositionNoAI> <text...>", "#发送私聊消息") { arg: Array<String>, log: StrCons ->
            TabCompleterProcess.playerPosition(arg[0], log)?.let {
                val msg = arg[1].replace("<>", "")
                it.sendSystemMessage(msg)
                log("{0} has received the message : {1}", it.name, msg)
            }
        }
        handler.register("gamestart", "[start|cancel|status]", "#开局倒计时管理（start 仍用于启动服务端）") { args: Array<String>?, log: StrCons ->
            when (args?.firstOrNull()?.lowercase(java.util.Locale.ROOT) ?: "start") {
                "start" -> log(if (StartModeAutoStartSupport.requestStartOffNetworkThread(null, "console_manual"))
                    "Start request queued; see gamestart status for countdown/confirmation." else "Start request busy; retry later.")
                "cancel" -> log(if (StartModeAutoStartSupport.cancelManualStart()) "Countdown cancelled." else "No cancellable manual countdown.")
                "status" -> StartModeAutoStartSupport.buildAutoStartDiagnostics().forEach { (key, value) -> log("$key=$value") }
                else -> log("Usage: gamestart [start|cancel|status]")
            }
        }
        handler.register("gametime", "#查看当前对局时长") { _: Array<String>, log: StrCons ->
            if (room.isStartGame) {
                log("Gameing Time : {0}", Time.format(Time.getTimeSinceSecond(room.startTime) * 1000L, 6))
            } else {
                log("No Start Game")
            }
        }
        handler.register("gameover", "serverCommands.gameover") { _: Array<String>?, log: StrCons ->
            GameEngine.data.room.gr()
            log("Game reset requested.")
        }
        handler.register("save", "serverCommands.save") { _: Array<String>?, log: StrCons ->
            if (room.isStartGame) {
                val ok = (gameModule.gameLinkFunction as? net.rwhps.server.game.headless.core.link.CheckedGameControlLink)?.trySaveGame() == true
                log(if (ok) "Game saved." else "Save failed: game thread unavailable or match changed.")
            } else {
                log("No Start Game")
            }
        }
        handler.register(
                "admin", "<add/remove> <$PlayerPositionNoAI>", "serverCommands.admin"
        ) { arg: Array<String>, log: StrCons ->
            if (maintenanceBlockedByRoomLifecycle()) {
                log("Cannot change persistent administrators while the room lifecycle is busy.")
                return@register
            }
            if (!StartModeRoomPermissionSupport.allowManualAdminGrantFromConsole()) {
                log("RoomPermissionConfig disabled console admin add/remove.")
                return@register
            }
            if (room.isStartGame) {
                log(localeUtil.getinput("err.startGame"))
                return@register
            }
            if (!("add" == arg[0] || "remove" == arg[0])) {
                log("Second parameter must be either 'add' or 'remove'.")
                return@register
            }
            val add = "add" == arg[0]
            TabCompleterProcess.playerPosition(arg[1], log)?.let { player->
                if (add) {
                    Data.core.admin.addAdmin(player.connectHexID, true)
                } else {
                    Data.core.admin.removeAdmin(player.connectHexID)
                }
                StartModePersistentAccessSupport.reclassifyRuntimeAdminCommand(player, add)

                player.isAdmin = add
                player.superAdmin = add
                if (!add) {
                    player.autoAdmin = false
                }

                StartModeRoomPermissionSupport.reconcileOnlineAuthorityState(
                    reason = "console_admin_${if (add) "add" else "remove"}",
                    excludedOrdinaryHostUuids = if (add) emptySet() else setOf(player.connectHexID)
                )
                StartModeRoomPermissionSupport.refreshOnlineAuthorityView("console_admin_${if (add) "add" else "remove"}")
                log("Changed admin status of player: {0}", player.name)
            }

        }
        handler.register("clearbanall", "serverCommands.clearbanall") { _: Array<String>?, log: StrCons ->
            Data.core.admin.clearBans()
            log("Cleared all ban records.")
        }
        handler.register("ban", "<$PlayerPositionNoAI>", "serverCommands.ban") { arg: Array<String>, log: StrCons ->
            TabCompleterProcess.playerPosition(arg[0], log)?.let { player ->
                // D-15: PlayerIpBanEvent had a listener but no fire point anywhere.
                GameEngine.data.eventManage.fire(PlayerIpBanEvent(gameModule, player))
                GameEngine.data.eventManage.fire(PlayerBanEvent(gameModule, player))
                log("Ban requested for player: {0}", player.name)
            }
        }
        handler.register("unban", "<player/uuid/ip/subnet> [identity]", "serverCommands.ban") { arg: Array<String>, log: StrCons ->
            val admin = Data.core.admin
            val kind = arg[0].lowercase(java.util.Locale.ROOT)
            val identity = arg.getOrNull(1)?.trim()
            if (kind in setOf("uuid", "ip", "subnet")) {
                if (identity.isNullOrBlank()) {
                    log("Usage: unban uuid <UUID> | unban ip <IP> | unban subnet <a.b.c>")
                    return@register
                }
                val removed = when (kind) {
                    "uuid" -> admin.unbanUuid(identity)
                    "ip" -> admin.unbanIp(identity)
                    else -> {
                        val key = net.rwhps.server.net.BanIdentitySupport.subnetKey(identity)
                        if (key == null) {
                            log("Invalid subnet. Use a.b.c, a.b.c.0/24 or the stored subnet key.")
                            return@register
                        }
                        admin.unbanIp24(key)
                    }
                }
                log(if (removed) "Removed the selected ban record." else "No matching runtime record; persistent record removal was requested.")
                return@register
            }
            if (arg.size > 1) {
                log("Unknown unban identity type. Use uuid, ip or subnet.")
                return@register
            }
            // Keep the online player shortcut, and accept an exact offline ban key.
            val raw = arg[0].trim()
            if (admin.isUuidBanned(raw)) {
                admin.unbanUuid(raw)
                log("Removed UUID ban. IP/subnet bans, if any, must be removed separately.")
            } else if (admin.bannedIPs.contains(raw)) {
                admin.unbanIp(raw)
                log("Removed IP ban.")
            } else if (admin.bannedIP24.contains(raw)) {
                admin.unbanIp24(raw)
                log("Removed subnet ban.")
            } else {
                TabCompleterProcess.playerPosition(raw, log)?.let { player ->
                    admin.unbanUuid(player.connectHexID)
                    player.con?.coverConnect()?.ip?.let(admin::unbanIp)
                    GameEngine.data.eventManage.fire(PlayerIpUnBanEvent(gameModule, player))
                    GameEngine.data.eventManage.fire(PlayerUnBanEvent(gameModule, player))
                    log("Removed online player's UUID/IP bans.")
                }
            }
        }
        handler.register("mute", "<$PlayerPositionNoAI> [Time(s)]", "serverCommands.mute") { arg: Array<String>, log: StrCons ->
            TabCompleterProcess.playerPosition(arg[0], log)?.let { player ->
                val muteSeconds = if (arg.size > 1) {
                    arg[1].toLongOrNull()?.takeIf { it > 0 } ?: run {
                        log("Mute time must be a positive integer in seconds.")
                        return@register
                    }
                } else {
                    43200L
                }
                player.muteTime = getTimeFutureMillis(muteSeconds * 1000L)
                log("Muted player {0} for {1} seconds.", player.name, muteSeconds)
            }
        }
        handler.register("kick", "<$PlayerPosition> [time]", "serverCommands.kick") { arg: Array<String>, log: StrCons ->
            if (room.isStartGame) {
                log("Cannot kick by slot while a game is running. Use kill/surrender handling or reset the room instead.")
                return@register
            }
            val position = arg[0].trim().toIntOrNull()
            if (position == null || position <= 0) {
                log(localeUtil.getinput("err.noInt"))
                return@register
            }
            val targetIndex = position - 1

            // Only clean slots that still have a server-side PlayerHess binding.
            // Forcing an unbound Hess slot can corrupt the engine player table and crash render.
            val kickTarget = TabCompleterProcess.playerPosition(arg[0], log)
            if (kickTarget == null) {
                log("No server-side player at position {0}; refused to nuke engine slot.", position)
                Log.warn("[Kick-Console] Refused to nuke unbound engine slot $targetIndex")
                return@register
            }

            var targetStillBound = false
            var targetLabel = arg[0]
            if (!mutateLobbyOnGameThread("console_kick_player") {
                    synchronized(gameModule.gameLinkServerData.teamOperationsSyncObject) {
                        val boundPlayer = room.playerManage.getPlayer(targetIndex)
                        if (boundPlayer === kickTarget) {
                            targetStillBound = true
                            targetLabel = runCatching { "${kickTarget.name}#${kickTarget.position}" }.getOrDefault(arg[0])
                            kickTarget.kickPlayer(localeUtil.getinput("kick.you"))
                            Log.clog("[Kick-Console] Sent kick/disconnect to player: $targetLabel")
                            synchronized(kickTarget) {
                                kickTarget.con = null
                                room.playerManage.playerGroup.remove(kickTarget)
                                room.playerManage.playerAll.remove(kickTarget)
                                StartModeLobbySupport.forgetPlayer(kickTarget)
                                runCatching { kickTarget.playerPrivateData.removePlayer() }
                                    .onFailure { Log.warn("[Kick-Console] Failed to remove player private data", it) }
                                kickTarget.clear()
                            }
                            runCatching { gameModule.room.call.syncAllPlayer() }
                                .onFailure { Log.warn("[Kick-Console] Failed to sync the lobby after removing $targetLabel", it) }
                        }
                    }
                }) {
                log("Unable to kick player because the lobby state changed.")
                return@register
            }
            if (!targetStillBound) {
                log("No server-side player remained at position {0}; no engine slot was changed.", position)
                return@register
            }
            log("Kicked player: {0}.", targetLabel)
            Log.clog("[Kick-Console] Kicked player $targetLabel from slot $targetIndex by console command")
        }
        handler.register("kill", "<$PlayerPosition>", "serverCommands.kill") { arg: Array<String>, log: StrCons ->
            if (room.isStartGame) {
                TabCompleterProcess.playerPosition(arg[0], log)?.let { player ->
                    val connection = player.con
                    if (connection == null) {
                        log("Player has no connection, cannot send kill")
                    } else {
                        runCatching { connection.sendSurrender() }
                            .onFailure { log("Failed to submit kill for ${player.name}: ${it.message ?: it.javaClass.simpleName}") }
                    }
                }
            } else {
                log(localeUtil.getinput("err.noStartGame"))
            }
        }
        handler.register("giveadmin", "<$PlayerPositionNoAI>", "serverCommands.giveadmin") { arg: Array<String>, log: StrCons ->
            if (maintenanceBlockedByRoomLifecycle()) {
                log("Cannot transfer room authority while the room lifecycle is busy.")
                return@register
            }
            if (!StartModeRoomPermissionSupport.allowGiveAdminFromConsole()) {
                log("RoomPermissionConfig disabled console admin transfer.")
                return@register
            }
            if (room.isStartGame) {
                log(localeUtil.getinput("err.startGame"))
                return@register
            }
            val player = TabCompleterProcess.playerPosition(arg[0], log) ?: return@register
            if (!StartModeRoomPermissionSupport.transferHost(room, player)) {
                log("Cannot transfer authority while the lobby state is changing.")
                return@register
            }
            room.call.sendSystemMessageLocal("give.ok", player.name)
            StartModeRoomPermissionSupport.refreshOnlineAuthorityView("console_give_admin")
            log("Transferred host/admin to player: {0}", player.name)
        }
        handler.register("clearmuteall", "serverCommands.clearmuteall") { _: Array<String>?, log: StrCons ->
            room.playerManage.playerGroup.eachAll { e: PlayerHess -> e.muteTime = 0 }
            log("Cleared mute status for all online players.")
        }

        handler.register("team", "<$PlayerPosition> <Team>", "serverCommands.team") { arg: Array<String>, log: StrCons ->
            if (GameEngine.data.room.isStartGame) {
                log(localeUtil.getinput("err.startGame"))
                return@register
            }
            val target = TabCompleterProcess.playerPosition(arg[0], log) ?: return@register
            val newTeam = StartModeSpectatorSupport.parseTeamArgument(arg[1], target.index)
            if (newTeam == null) {
                log("Invalid team. Use 1-100, -1 for automatic team, or S for spectator.")
                return@register
            }
            if (!StartModeLobbySupport.canChangeTeam() && !StartModeLobbySupport.isSpectatorTeam(newTeam)) {
                log(StartModeLobbySupport.teamChangeBlockedReason())
                return@register
            }
            val changed = if (StartModeLobbySupport.isSpectatorTeam(newTeam)) {
                StartModeSpectatorSupport.moveToSpectator(target)
            } else {
                StartModeSpectatorSupport.changePlayingTeam(target, newTeam)
            }
            log(if (changed) "Player team updated." else "Unable to change team: no playing slot or lobby is busy.")
        }
    }

    private fun registerPlayerStatusCommand(handler: CommandHandler) {
        handler.register("players", "serverCommands.players") { _: Array<String>?, log: StrCons ->
            val presence = room.playerManage.buildPlayerPresenceSnapshot()
            val connectedOccupants = presence.connectedOccupants
            val reservedOccupants = presence.reservedOccupants
            val reconnectGraceHumans = presence.reconnectGraceHumanCount
            if (connectedOccupants == 0 && reservedOccupants == 0) {
                log("No players are currently in the server.")
            } else {
                log(
                    "Players: {0} connected, {1} reserved, {2} reconnect-grace",
                    connectedOccupants,
                    reservedOccupants,
                    reconnectGraceHumans
                )
                val data = StringBuilder()
                room.playerManage.playerGroup.eachAll { player ->
                    data.append(LINE_SEPARATOR).append(player.playerInfo)
                }
                log(data.toString())
            }
        }

        handler.register("admins", "serverCommands.admins") { _: Array<String>?, log: StrCons ->
            if (Data.core.admin.playerAdminData.size == 0) {
                log("No admins are currently in the server.")
            } else {
                log("Admins: {0}", Data.core.admin.playerAdminData.size)
                val data = StringBuilder()
                for (player in Data.core.admin.playerAdminData.values) {
                    data.append(LINE_SEPARATOR).append(player.name).append(" / ").append("ID: ").append(player.uuid).append(" / ")
                        .append("Admin: ").append(player.admin).append(" / ").append("SuperAdmin: ").append(player.superAdmin)
                }
                log(data.toString())
            }
        }

        handler.register("reloadmods", "serverCommands.reloadmods") { _: Array<String>?, log: StrCons ->
            if (maintenanceBlockedByRoomLifecycle()) {
                log("Cannot reload mods while a game start, match, return, recovery or reset is in progress.")
            } else {
                log(localeUtil.getinput("server.loadMod", ModManage.reLoadMods()))
            }

        }
        handler.register("reloadmaps", "serverCommands.reloadmaps") { _: Array<String>?, log: StrCons ->
            if (maintenanceBlockedByRoomLifecycle()) {
                log("Cannot reload maps while a game start, match, return, recovery or reset is in progress.")
                return@register
            }
            val size = MapManage.mapsData.size
            MapManage.readMapAndSave()
            if (StartModeMapLockSupport.applyLockedMapIfNeeded()) {
                log("Map lock reapplied: {0}", room.maps.mapName)
            }
            // Reload 旧列表的Custom数量 : 新列表的Custom数量
            log("Reload Old Size:New Size is {0}:{1}", size, MapManage.mapsData.size)
            log(MapManage.lastRuntimeCacheCleanupReport().summary())
        }
        handler.register("mapgc", "[action]", "serverCommands.mapgc") { args: Array<String>?, log: StrCons ->
            val action = args?.firstOrNull()?.trim()?.lowercase() ?: "status"
            when (action) {
                "status" -> log(MapManage.reclaimModMapRuntimeCaches(dryRun = true, force = false).summary())
                "dryrun", "dry-run" -> log(MapManage.reclaimModMapRuntimeCaches(dryRun = true, force = false).summary())
                "cleanup" -> if (maintenanceBlockedByRoomLifecycle()) {
                    log("Cannot clean map runtime caches while the room lifecycle is busy.")
                } else {
                    log(MapManage.reclaimModMapRuntimeCaches(dryRun = false, force = false).summary())
                }
                "cleanup-force", "force" -> if (maintenanceBlockedByRoomLifecycle()) {
                    log("Cannot force-clean map runtime caches while the room lifecycle is busy.")
                } else {
                    log(MapManage.reclaimModMapRuntimeCaches(dryRun = false, force = true).summary())
                }
                else -> log("Usage: mapgc status|dryrun|cleanup|cleanup-force")
            }
        }
        handler.register("mods", "[status]", "serverCommands.mods") { arg: Array<String>?, log: StrCons ->
            if (!arg.isNullOrEmpty() && arg[0].equals("status", ignoreCase = true)) {
                ModCompatibilitySupport.buildDiagnostics().forEach { log(it) }
                return@register
            }
            val mods = ModManage.getModsList()
            if (mods.isEmpty()) {
                log("No mods loaded.")
                return@register
            }
            for ((index, name) in mods.withIndex()) {
                log(localeUtil.getinput("mod.info", index, name))
            }
        }
        handler.register("maps", "[page]", "serverCommands.maps") { args: Array<String>?, log: StrCons ->
            val response = StringBuilder()
            appendCustomMapPage(response, args?.firstOrNull())
            log(response.toString())
        }
    }

    private fun registerPlayerCustomEx(handler: CommandHandler) {
        handler.register("addmoney", "<$PlayerPosition> <money>", "serverCommands.addmoney") { arg: Array<String>, log: StrCons ->
            val result = StartModeMoneyCommandSupport.addMoney(gameModule, arg[0], arg[1])
            log(result.message)
        }

        handler.register(
                "textbuild", "<UnitName> <Text> [index(NeutralByDefault)]", "serverCommands.textbuild"
        ) { arg: Array<String>, log: StrCons ->
            if (arg[0].isBlank() || arg[0].length > 128 || arg[0].any { Character.isISOControl(it) }) {
                log("Unit name must be 1-128 non-control characters.")
                return@register
            }
            if (arg[1].isBlank() || arg[1].length > textBuildMaxChars) {
                log("Text length must be 1-{0} characters.", textBuildMaxChars)
                return@register
            }
            val index = parseTextBuildIndex(arg.getOrNull(2))
            if (index == null) {
                log("Textbuild index must be an integer, or omitted for neutral.")
                return@register
            }
            val cache = Seq<Array<ByteArray>>()

            arg[1].forEach {
                cache.add(Font16.resolveString(it.toString()))
            }

            // 偏移
            var off = 0
            var spawned = 0

            cache.eachAll {
                var i = 0
                var lg = true
                for ((height, lineArray) in it.withIndex()) {
                    for ((width, b) in lineArray.withIndex()) {
                        if (lg) {
                            i++
                        }
                        if (b.toInt() == 1) {
                            try {
                                GameEngine.data.gameData.commandPacketList.add(
                                        NetStaticData.RwHps.abstractNetPacket.gameSummonPacket(
                                        index, arg[0], ((off + width) * 20).toFloat(), (height * 20).toFloat()
                                ).bytes)
                                spawned++
                            } catch (e: Exception) {
                                error(e)
                            }
                        }
                    }
                    lg = false
                }
                i++
                off += i
            }
            log("Textbuild queued {0} unit commands.", spawned)
        }

        handler.register("test", "serverCommands.addmoney") { _: Array<String>, _: StrCons ->
            // 截图
            GameEngine.mainObject.j?.a(null as Graphics?, true)
        }
    }

    companion object {
        private val localeUtil = Data.i18NBundle
        private val gameModule = HeadlessModuleManage.hessLoaderMap[this::class.java.classLoader.toString()]!!
        private val room = gameModule.room
    }

    init {
        registerPlayerCommand(handler)
        registerPlayerStatusCommand(handler)
        registerPlayerCustomEx(handler)
    }
}
