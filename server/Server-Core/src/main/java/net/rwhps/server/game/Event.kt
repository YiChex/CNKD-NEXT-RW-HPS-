/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game

import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.game.event.game.*
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.net.Administration.PlayerInfo
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAdvertisingRedirectSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLatencyHeartbeatSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeReplayPlayerDataSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurrenderSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeConsoleOutputSupport
import net.rwhps.server.plugin.internal.headless.support.StartModePlayerExperienceSupport
import net.rwhps.server.util.game.SystemMessageTextMode
import net.rwhps.server.plugin.internal.headless.support.StartModeReadinessSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.Time.millis
import net.rwhps.server.util.annotations.core.EventListenerHandler
import net.rwhps.server.util.compression.CompressionEncoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.coverConnect
import net.rwhps.server.util.inline.toPrettyPrintingJson
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.error
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED", "UNUSED_PARAMETER")
class Event: EventListenerHost {
    @EventListenerHandler
    fun registerServerHessStartPort(serverHessStartPort: ServerHessStartPort) {
        serverHessStartPort.gameModule.gameLinkServerData.maxUnit = Data.configServer.maxUnit
        serverHessStartPort.gameModule.gameLinkServerData.income = Data.configServer.defIncome
        StartModeGameOptionSupport.logConfiguredDefaults()
        StartModeLobbySupport.onServerStarted()
    }

    @EventListenerHandler
    fun registerPlayerJoinEvent(playerJoinEvent: PlayerJoinEvent) {
        val player = playerJoinEvent.player
        val room = playerJoinEvent.gameModule.room
        if (player.name.isBlank() || player.name.length > 30) {
            player.kickPlayer(player.getinput("kick.name.failed"))
            return
        }

        if (Data.core.admin.isUuidBanned(player.connectHexID)) {
            try {
                player.kickPlayer(player.i18NBundle.getinput("kick.ban"))
            } catch (ioException: IOException) {
                error("[Player] Send Kick Player Error", ioException)
            }
            return
        }

        if (Data.core.admin.playerDataCache.containsKey(player.connectHexID)) {
            val info = Data.core.admin.playerDataCache[player.connectHexID]!!
            if (info.timesKicked > millis()) {
                try {
                    player.kickPlayer(player.i18NBundle.getinput("kick.you.time"))
                } catch (ioException: IOException) {
                    error("[Player] Send Kick Player Error", ioException)
                }
                return
            } else {
                player.muteTime = info.timeMute
            }
        }
        val reconnectRecovered = StartModePlayerExperienceSupport.consumeReconnectRecovered(player)

        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        if (presence.reservedOccupants > Data.configServer.maxPlayer) {
            try {
                // D-23: was a hardcoded Chinese string, so EN/RU/HK players saw Chinese.
                player.kickPlayer(player.i18NBundle.getinput("kick.room.full", Data.configServer.maxPlayer))
            } catch (ioException: IOException) {
                error("[Player] Send Kick Player Error", ioException)
            }
            return
        }

        StartModeLobbySupport.onPlayerJoin(player)

        if (reconnectRecovered) {
            player.sendSystemMessage("重连成功，已恢复你的玩家身份。")
            logPlayerJoinLeave("[StartMode] Player reconnected within grace: ${player.name}")
        } else {
            room.call.sendSystemMessage(Data.i18NBundle.getinput("player.ent", player.name))
            logPlayerJoinLeave("&c" + Data.i18NBundle.getinput("player.ent", player.name))
        }
        if (!reconnectRecovered) {
            UpListMain.notifyHeadlessLobbyStateChanged("playerJoin")
        }

        if (!reconnectRecovered && Data.configServer.enterAd.isNotBlank()) {
            player.sendSystemMessage(Data.configServer.enterAd, SystemMessageTextMode.CONFIG_NOTICE)
        }
        val advertisingRedirectScheduled = StartModeAdvertisingRedirectSupport.onPlayerJoin(player, room)
        if (!advertisingRedirectScheduled) {
            StartModeAutoStartSupport.onPlayerJoin(room)
        }
        if (!advertisingRedirectScheduled) {
            StartModeReadinessSupport.onPlayerJoin(player, room, reconnectRecovered)
        }
        StartModeLatencyHeartbeatSupport.onPlayerJoin(player)

        if (Data.configServer.saveRePlayFile) {
            runCatching {
                playerJoinEvent.player.infoObject.run {
                    FileUtils.getFolder(Data.ServerPlayerPath).toFile("${this["Position"]}.json").writeFileByte(toPrettyPrintingJson().toByteArray())
                }
            }.onFailure {
                Log.warn("[Player] Failed to save join replay player info for ${player.name}", it)
            }
        }
    }

    @EventListenerHandler
    fun registerPlayerLeaveEvent(playerLeaveEvent: PlayerLeaveEvent) {
        val player = playerLeaveEvent.player
        StartModeLobbySupport.onPlayerLeave(player)
        // 投降投票状态与玩家离开强相关（清理其投票键、重新评估队伍投降资格），
        // 与排位无关，因此在排位剥离后必须继续调用。
        StartModeSurrenderSupport.onPlayerLeave(player, playerLeaveEvent.gameModule.room)
        if (
            Data.configServer.oneAdmin &&
            StartModeRoomPermissionSupport.allowAutoAdminTransferOnLeave() &&
            !player.superAdmin &&
            StartModeRoomPermissionSupport.hasEffectiveHostAuthority(player) &&
            !StartModeRoomPermissionSupport.hasOtherOnlineEffectiveHost(
                playerLeaveEvent.gameModule.room,
                player
            ) &&
            playerLeaveEvent.gameModule.room.playerManage.buildPlayerPresenceSnapshot().connectedOccupants > 0
        ) {
            playerLeaveEvent.gameModule.room.playerManage.playerGroup.eachFind({
                it !== player && it.con != null && !it.isAi && !Data.neverEnd
            }) {
                it.isAdmin = true
                it.autoAdmin = true
                player.isAdmin = false
                player.autoAdmin = false
                playerLeaveEvent.gameModule.room.call.sendSystemMessage("give.ok", it.name)
                StartModeRoomPermissionSupport.refreshOnlineAuthorityView("host_leave_transfer")
            }
        }

        // Prevent playerDataCache from growing unbounded when game never starts (lobby-only scenario)
        val cache = Data.core.admin.playerDataCache
        if (cache.size > 512) {
            cache.clear()
        }
        cache[player.connectHexID] = PlayerInfo(player.connectHexID, player.kickTime, player.muteTime)

        if (playerLeaveEvent.gameModule.room.isStartGame) {
            playerLeaveEvent.gameModule.room.call.sendSystemMessage("player.dis", player.name)
        } else {
            playerLeaveEvent.gameModule.room.call.sendSystemMessage("player.disNoStart", player.name)
        }
        logPlayerJoinLeave("&c" + Data.i18NBundle.getinput("player.dis", player.name))

        StartModeAutoStartSupport.onPlayerLeave(playerLeaveEvent.gameModule.room)
        playerLeaveEvent.gameModule.room.call.requestImmediateGameOverIfNoHumans("playerLeave:${player.name}")
        UpListMain.notifyHeadlessLobbyStateChanged("playerLeave")
        if (Data.configServer.saveRePlayFile) {
            FileUtils.getFolder(Data.ServerPlayerPath).toFile("${playerLeaveEvent.player.position}.json").delete()
        }
    }

    @EventListenerHandler
    fun registerGameStartEvent(serverGameStartEvent: ServerGameStartEvent) {
        Data.core.admin.playerDataCache.clear()
        StartModeAutoStartSupport.onGameStart(serverGameStartEvent.gameModule.room)
        // 投降投票状态必须每局重置；原实现挂在 RankedShardAdapterSupport.onGameStart 上，
        // 但该调用在非排位路径同样执行，剥离后由非排位实现直接承担。
        StartModeSurrenderSupport.onGameStart()

        if (Data.configServer.startAd.isNotBlank()) {
            serverGameStartEvent.gameModule.room.call.sendSystemMessage(
                Data.configServer.startAd,
                SystemMessageTextMode.CONFIG_NOTICE
            )
        }

        StartModeGameOptionSupport.applyConfiguredStartPlayerDefaults(serverGameStartEvent.gameModule)

        if (Data.configServer.saveRePlayFile) {
            serverGameStartEvent.gameModule.room.playerData = Seq<Map<String, Any>>().apply {
                serverGameStartEvent.gameModule.room.playerManage.playerAll.eachAll {
                    if (!it.isAi) {
                        runCatching { add(it.infoObject) }
                            .onFailure { error -> Log.warn("[Player] Failed to collect replay player info for ${it.name}", error) }
                    }
                }
            }
        }

        UpListMain.notifyHeadlessLobbyStateChanged("gameStart")
        Log.clog("[Start New Game]")
    }

    @EventListenerHandler
    fun registerGameOverEvent(serverGameOverEvent: ServerGameOverEvent) {
        serverGameOverEvent.gameModule.room.playerData?.let { playerData ->
            CompressionEncoderUtils.zipStream().apply {
                playerData.eachAll {
                    addCompressBytes("${it["Position"]}.json", it.toPrettyPrintingJson().toByteArray())
                }
            }.run {
                StartModeReplayPlayerDataSupport.writePlayerDataZip(serverGameOverEvent.gameModule.room.replayFileName, this.flash())
            }
        }
        // 结算时清空投降投票状态（原挂在 RankedShardAdapterSupport.onGameOver 上，非排位路径同样执行）。
        StartModeSurrenderSupport.onGameOver()
        UpListMain.notifyHeadlessLobbyStateChanged("gameOver")
    }

    @EventListenerHandler
    fun registerPlayerBanEvent(serverBanEvent: PlayerBanEvent) {
        val player = serverBanEvent.player
        Data.core.admin.banUuid(player.connectHexID)
        player.con?.let { con -> Data.core.admin.banIp(con.coverConnect().ip) }
        try {
            player.kickPlayer(player.i18NBundle.getinput("kick.ban"))
        } catch (ioException: IOException) {
            error("[Player] Send Kick Player Error", ioException)
        }
        serverBanEvent.gameModule.room.call.sendSystemMessage("ban.yes", player.name)
    }

    private fun logPlayerJoinLeave(message: String) {
        StartModeConsoleOutputSupport.print(
            StartModeConsoleOutputSupport.Category.PLAYER_JOIN_LEAVE,
            message
        )
    }

    /**
     * D-15: IP-scoped ban notification.
     *
     * This listener used to duplicate the kick + broadcast that [registerPlayerBanEvent]
     * already performs, and had no fire point at all. It is now the IP-only record step:
     * `ban` fires this event and then [PlayerBanEvent], so keeping the kick here would
     * kick (and broadcast) twice for one ban.
     */
    @EventListenerHandler
    fun registerPlayerIpBanEvent(serverIpBanEvent: PlayerIpBanEvent) {
        val player = serverIpBanEvent.player
        player.con?.let { con -> Data.core.admin.banIp(con.coverConnect().ip) }
        Log.clog("[Ban] IP ban recorded for player: ${player.name}")
    }

    /**
     * D-15: fired by the `unban` console command. Previously unreachable.
     */
    @EventListenerHandler
    fun registerPlayerUnBanEvent(serverUnBanEvent: PlayerUnBanEvent) {
        val player = serverUnBanEvent.player
        Log.clog("[Ban] Player unbanned: ${player.name}")
    }

    /**
     * D-15: fired by the `unban` console command. Previously unreachable.
     */
    @EventListenerHandler
    fun registerPlayerIpUnBanEvent(serverIpUnBanEvent: PlayerIpUnBanEvent) {
        val player = serverIpUnBanEvent.player
        Log.clog("[Ban] IP unban recorded for player: ${player.name}")
    }

}
