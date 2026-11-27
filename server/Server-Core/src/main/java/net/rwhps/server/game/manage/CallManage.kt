/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.manage

import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.headless.core.AbstractGameModule
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.internal.headless.support.StartModeDefeatedPlayerKickSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurvivalSupport
import net.rwhps.server.util.Time
import net.rwhps.server.util.game.SystemMessageTextMode
import net.rwhps.server.util.log.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * @author Dr (dr@der.kim)
 */
class CallManage(private val gameModule: AbstractGameModule) {
    private companion object {
        private const val START_GAME_PLAYER_COUNT_GRACE_SECONDS = 30
        private const val NO_ONLINE_HUMAN_RESET_GRACE_SECONDS = 45
    }

    /**
     * 向服务器玩家发送指定消息
     *
     * @param text String
     */
    fun sendSystemMessage(text: String) {
        gameModule.room.playerManage.playerGroup.eachAll { e: PlayerHess -> e.sendSystemMessage(text) }
    }

    fun sendSystemMessage(text: String, mode: SystemMessageTextMode) {
        gameModule.room.playerManage.playerGroup.eachAll { e: PlayerHess -> e.sendSystemMessage(text, mode) }
    }

    /**
     * 向服务器玩家发送从 i18NBundle 获取过的消息
     *
     * @param text String
     * @param obj Array<out Any>
     */
    fun sendSystemMessageLocal(text: String, vararg obj: Any) {
        gameModule.room.playerManage.playerGroup.eachAll { e: PlayerHess ->
            e.sendSystemMessage(e.i18NBundle.getinput(text, *obj))
        }
    }

    fun sendSystemMessageLocal(text: String, mode: SystemMessageTextMode, vararg obj: Any) {
        gameModule.room.playerManage.playerGroup.eachAll { e: PlayerHess ->
            e.sendSystemMessage(e.i18NBundle.getinput(text, *obj), mode)
        }
    }

    fun sendSystemTeamMessageLocal(team: Int, text: String, vararg obj: Any) {
        gameModule.room.playerManage.playerGroup.eachAllFind({ e: PlayerHess -> e.team == team }) { p: PlayerHess ->
            p.sendSystemMessage("[TEAM] " + p.i18NBundle.getinput(text, *obj))
        }
    }

    fun sendSystemMessage(text: String, vararg obj: Any) {
        gameModule.room.playerManage.playerGroup.eachAll { e: PlayerHess ->
            e.sendSystemMessage(e.i18NBundle.getinput(text, *obj))
        }
    }

    /** Compatibility facade: selects lobby refresh or active-match snapshot by room state. */
    fun syncAllPlayer() {
        if (gameModule.room.isStartGame) {
            gameModule.gameLinkFunction.allPlayerSync()
            return
        }
        val result = gameModule.gameLinkFunction.syncLobbyPlayers()
        if (!result.success) {
            throw result.error ?: IllegalStateException("lobby_player_sync_failed")
        }
    }

    /**
     * 踢掉全部玩家
     *
     * @param msg 踢出消息
     */
    @JvmOverloads
    fun killAllPlayer(msg: String = "Game Over") {
        gameModule.room.playerManage.playerGroup.clone().eachAll { e: PlayerHess ->
            try {
                e.kickPlayer(msg)
            } catch (err: IOException) {
                Log.error("[ALL] Kick All Player Error", e)
            }
        }
    }

    fun startCheckThread() {
        Threads.closeTimeTask(CallTimeTask.GameOverTask)
        Threads.closeTimeTask(CallTimeTask.AutoCheckTask)
        var aiEndTime = 0
        var noOnlineHumanSince = 0
        Threads.newTimedTask(CallTimeTask.AutoCheckTask, 0, 1, TimeUnit.SECONDS) {
            if (Data.neverEnd) {
                return@newTimedTask
            }
            if (StartModeGameOverReturnSupport.isSettling() || gameModule.room.isGameOverResetInProgress()) {
                return@newTimedTask
            }

            if ((Data.configServer.maxGameIngTime != -1 && Time.concurrentSecond() > gameModule.room.endTime)) {
                if (gameModule.room.flagData.forcedCloseSendMsg) {
                    StartModeGameOverReturnSupport.onSettle(gameModule, "time_limit")
                }
                gameModule.room.flagData.forcedCloseSendMsg = false
                return@newTimedTask
            }

            val protectedHumanCount = graceProtectedHumanPlayerCount()
            if (protectedHumanCount == 0) {
                val now = Time.concurrentSecond()
                if (isWithinStartPlayerCountGrace()) {
                    noOnlineHumanSince = 0
                    return@newTimedTask
                }
                if (noOnlineHumanSince == 0) {
                    noOnlineHumanSince = now
                    StartModeGameOverReturnSupport.recordNoOnlineHumanGraceStarted(
                        "auto_check",
                        NO_ONLINE_HUMAN_RESET_GRACE_SECONDS
                    )
                    Log.clog(
                        "[${gameModule.room.roomID}] No online human players detected; waiting " +
                                "${NO_ONLINE_HUMAN_RESET_GRACE_SECONDS}s before gameover reset."
                    )
                    return@newTimedTask
                }
                if (now - noOnlineHumanSince < NO_ONLINE_HUMAN_RESET_GRACE_SECONDS) {
                    return@newTimedTask
                }
                Log.clog("[${gameModule.room.roomID}] No online human players remain in game after grace. Triggering gameover reset.")
                noOnlineHumanSince = 0
                StartModeGameOverReturnSupport.recordNoOnlineHumanGraceExpired("auto_check_expired")
                gameModule.room.gr()
                return@newTimedTask
            } else {
                StartModeGameOverReturnSupport.clearNoOnlineHumanGrace("human_returned")
                noOnlineHumanSince = 0
            }

            val autoKickOperationSucceeded = gameModule.gameFunction.suspendMainThreadOperations {
                StartModeDefeatedPlayerKickSupport.evaluateAndKick(gameModule.room)
            }
            if (!autoKickOperationSucceeded) {
                Log.warn("[${gameModule.room.roomID}] Unable to evaluate defeated-player auto-kick on the game thread.")
            }

            if (StartModeSurvivalSupport.shouldUseSurvivalPlayerCheck(gameModule.room)) {
                if (StartModeSurvivalSupport.shouldReturnBecauseHumansDefeated(gameModule.room)) {
                    StartModeGameOverReturnSupport.onSettle(gameModule, "survival_defeat")
                    return@newTimedTask
                }
            } else if (gameModule.room.flagData.ai || hasAnyAiPlayer()) {
                if (gameModule.room.flagData.aiWarn) {
                    gameModule.room.flagData.aiWarn = false
                    sendSystemMessageLocal("gameOver.ai")
                }
                if (protectedHumanCount == 0) {
                    if (aiEndTime == 0) {
                        aiEndTime = Time.concurrentSecond() + Data.configServer.maxOnlyAIGameIngTime
                    } else if (Data.configServer.maxOnlyAIGameIngTime != -1 && Time.concurrentSecond() > aiEndTime) {
                        gameModule.room.gr()
                        return@newTimedTask
                    }
                } else {
                    aiEndTime = 0
                }
            } else if (StartModeGameOverReturnSupport.isPlaying()) {
                if (isWithinStartPlayerCountGrace()) {
                    return@newTimedTask
                }
                when (protectedHumanCount) {
                    0 -> gameModule.room.gr()
                    1 -> StartModeGameOverReturnSupport.onSettle(gameModule, "one_player_left")
                    else -> { /* enough players, keep playing */ }
                }
            }


            if (StartModeGameOverReturnSupport.isPlaying() && gameModule.room.flagData.sendGameStatusFlag) {
                if (StartModeSurvivalSupport.shouldSkipGenericVerdictProbe(gameModule.room)) {
                    return@newTimedTask
                }

                val gameOverData = gameModule.gameHessData.getGameOverData() ?: return@newTimedTask
                if (StartModeSurvivalSupport.shouldSuppressGenericVerdict(gameModule.room, gameOverData)) {
                    return@newTimedTask
                }

                gameModule.room.gameOverData = gameOverData
                gameModule.room.flagData.sendGameStatusFlag = false

                val last = gameModule.room.gameOverData!!.winPlayerList.toArray(String::class.java).contentToString()
                Log.clog("[${gameModule.room.roomID}] Last Win Player: {0}", last)
                sendSystemMessageLocal("survive.player", SystemMessageTextMode.WINNER_RESULT, last)
                StartModeGameOverReturnSupport.onSettle(gameModule, "verdict")
            }
        }
    }

    fun requestImmediateGameOverIfNoHumans(reason: String): Boolean {
        if (Data.neverEnd || !StartModeGameOverReturnSupport.isPlaying()) {
            return false
        }
        if (graceProtectedHumanPlayerCount() > 0) {
            return false
        }
        Log.clog(
            "[${gameModule.room.roomID}] No online humans after $reason; " +
                    "deferred to grace-based gameover check."
        )
        StartModeGameOverReturnSupport.recordNoOnlineHumanDeferred(reason, NO_ONLINE_HUMAN_RESET_GRACE_SECONDS)
        return false
    }

    private fun graceProtectedHumanPlayerCount(): Int {
        return gameModule.room.playerManage.countGraceProtectedHumanPlayers()
    }

    private fun hasAnyAiPlayer(): Boolean {
        var hasAi = false
        gameModule.room.playerManage.playerGroup.eachFind({ it.isAi }) {
            hasAi = true
        }
        return hasAi
    }

    private fun isWithinStartPlayerCountGrace(): Boolean {
        val startTime = gameModule.room.startTime
        return startTime > 0 && Time.concurrentSecond() - startTime < START_GAME_PLAYER_COUNT_GRACE_SECONDS
    }
}
