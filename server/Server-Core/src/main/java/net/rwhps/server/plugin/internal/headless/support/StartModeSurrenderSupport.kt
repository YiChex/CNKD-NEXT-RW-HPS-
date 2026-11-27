/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.log.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 投降（surrender）功能的**非排位**实现。
 *
 * 本对象是"投降"这一通用能力的唯一实现，在排位剥离时从 `RankedShardAdapterSupport` 中提取而来。
 * 它与排位平台无关：不依赖 prepared match、不依赖任何 `CNKD_RANKED_*` 开关、不向任何平台上报。
 *
 * ### 入口
 * - 命令层：`ClientCommands` 注册的 7 个别名 `surrender` / `ff` / `forfeit` / `giveup` / `resign` / `投降` / `放弃`
 * - 聊天层：[isSurrenderInput]（带前缀 9 个别名、不带前缀 7 个别名，比命令层更宽）
 * - 原版客户端动作：[isOriginalSurrenderAction] / [tryHandleOriginalSurrenderAction]
 *
 * ### 两种模式（由 `configStartMode.enableIndividualSurrender` 切换）
 * - `false`（默认）：团队投降投票，达到 [TeamSurrenderVotePolicy] 计算的法定人数后由
 *   [completeTeamSurrender] 统一提交；[ensureSurrenderVoteProbeTask] 每秒对账一次。
 * - `true`：单人投降，直接提交。
 *
 * ### 非排位路径也依赖的生命周期钩子
 * [onGameStart] / [onGameOver] / [onPlayerLeave] 在**非排位**的房间生命周期里同样会被调用
 * （原实现挂在 `RankedShardAdapterSupport.onGameStart` / `onGameOver` / `onPlayerLeave` 上，
 * 而这三个钩子本身与排位无关）。剥离时必须保留，否则投降投票状态会跨局残留。
 */
internal object StartModeSurrenderSupport {
    private const val SURRENDER_VOTE_PROBE_TASK_NAME = "SingleRoomSurrenderVoteProbe"
    private const val SURRENDER_VOTE_PROBE_TASK_GROUP = "[SingleRoomSurrender]"
    private const val SURRENDER_VOTE_PROBE_TASK_DESCRIPTION = "Reconcile active team surrender votes"
    private const val SURRENDER_VOTE_PROBE_INTERVAL_SECONDS = 1
    /** Tracks surrender vote: team number -> stable player vote keys. */
    private val surrenderVotesPerTeam = ConcurrentHashMap<Int, MutableSet<String>>()
    private val teamSurrenderSubmissionsInFlight = ConcurrentHashMap.newKeySet<Int>()
    private val teamSurrenderSubmittedVoterKeys = ConcurrentHashMap<Int, MutableSet<String>>()
    private val teamSurrenderRetryRequired = ConcurrentHashMap.newKeySet<Int>()
    private val surrenderVoteProgressSignatures = ConcurrentHashMap<Int, String>()
    private val surrenderVoteStateLock = Any()
    @Volatile
    private var surrenderVoteGeneration = 0L

    private data class SurrenderTeamSnapshot(
        val onlineMembers: List<PlayerHess>,
        val requiredVoters: List<PlayerHess>,
        val eliminatedExemptCount: Int,
        val disconnectedExemptCount: Int,
        val reconnectPendingCount: Int
    )

    private data class SurrenderVoteDecision(
        val progress: TeamSurrenderVoteProgress,
        val added: Boolean,
        val playerEligible: Boolean,
        val submissionInFlight: Boolean,
        val generation: Long
    )

    private data class SurrenderSubmissionStart(
        val alreadySubmittedKeys: Set<String>,
        val generation: Long
    )
    fun tryHandleSurrenderChat(message: String, player: PlayerHess): Boolean {
        if (!isSurrenderInput(message)) {
            return false
        }
        requestPlayerSurrender(player)
        return true
    }

    fun isOriginalSurrenderAction(isSystemAction: Boolean, systemAction: Int): Boolean {
        return TeamSurrenderVotePolicy.isOriginalSurrenderAction(isSystemAction, systemAction)
    }

    fun tryHandleOriginalSurrenderAction(
        isSystemAction: Boolean,
        systemAction: Int,
        player: PlayerHess
    ): Boolean {
        if (!TeamSurrenderVotePolicy.shouldInterceptOriginalSurrenderAction(
                isSystemAction = isSystemAction,
                systemAction = systemAction,
                individualSurrenderEnabled = Data.configStartMode.enableIndividualSurrender
            )
        ) {
            return false
        }
        requestPlayerSurrender(player)
        return true
    }

    fun requestPlayerSurrender(player: PlayerHess): Boolean {
        val room = runCatching {
            if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps.room else null
        }.getOrNull()
        if (room == null) {
            player.sendSystemMessage("服务端未就绪 | 暂时无法投降")
            return false
        }
        if (!room.isStartGame) {
            player.sendSystemMessage("当前不在游戏中 | 无法投降")
            return false
        }
        if (player.isAi || StartModeLobbySupport.isSpectatorTeam(player.team)) {
            player.sendSystemMessage("观战玩家无需投降")
            return false
        }

        val connection = player.con
        if (connection == null) {
            player.sendSystemMessage("服务端未就绪 | 暂时无法投降")
            return false
        }

        // Team surrender keeps the original engine action. Eliminated and confirmed
        // offline humans are exempt; reconnect-grace players remain temporarily eligible.
        if (!Data.configStartMode.enableIndividualSurrender) {
            val team = player.team
            val playerKey = surrenderVoteKey(player)
            lateinit var snapshot: SurrenderTeamSnapshot
            lateinit var requiredVoterKeys: Set<String>

            val decision = synchronized(surrenderVoteStateLock) {
                if (Data.configStartMode.enableIndividualSurrender) {
                    null
                } else {
                    snapshot = buildSurrenderTeamSnapshot(room, team)
                    requiredVoterKeys = snapshot.requiredVoters.mapTo(linkedSetOf(), ::surrenderVoteKey)
                    if (!TeamSurrenderVotePolicy.shouldUseTeamVote(requiredVoterKeys.size)) {
                        null
                    } else if (team in teamSurrenderSubmissionsInFlight) {
                        SurrenderVoteDecision(
                            progress = calculateSurrenderVoteProgress(snapshot, surrenderVotesPerTeam[team].orEmpty()),
                            added = false,
                            playerEligible = playerKey in requiredVoterKeys,
                            submissionInFlight = true,
                            generation = surrenderVoteGeneration
                        )
                    } else if (playerKey !in requiredVoterKeys) {
                        val recordedVotes = surrenderVotesPerTeam[team]
                        recordedVotes?.retainAll(requiredVoterKeys)
                        teamSurrenderSubmittedVoterKeys[team]?.retainAll(requiredVoterKeys)
                        SurrenderVoteDecision(
                            progress = calculateSurrenderVoteProgress(snapshot, recordedVotes.orEmpty()),
                            added = false,
                            playerEligible = false,
                            submissionInFlight = false,
                            generation = surrenderVoteGeneration
                        )
                    } else {
                        val recordedVotes = surrenderVotesPerTeam.computeIfAbsent(team) { ConcurrentHashMap.newKeySet() }
                        recordedVotes.retainAll(requiredVoterKeys)
                        teamSurrenderSubmittedVoterKeys[team]?.retainAll(requiredVoterKeys)
                        val added = recordedVotes.add(playerKey)
                        val progress = calculateSurrenderVoteProgress(snapshot, recordedVotes)
                        surrenderVoteProgressSignatures[team] = surrenderVoteProgressSignature(snapshot, progress)
                        SurrenderVoteDecision(
                            progress = progress,
                            added = added,
                            playerEligible = true,
                            submissionInFlight = false,
                            generation = surrenderVoteGeneration
                        )
                    }
                }
            } ?: return executeSurrender(player, room, connection)

            if (decision.generation != surrenderVoteGeneration) {
                return true
            }
            if (decision.submissionInFlight) {
                player.sendSystemMessage("团队投降已通过，正在由游戏核心处理。")
                return true
            }
            if (!decision.playerEligible) {
                if (decision.progress.complete && surrenderVotesPerTeam[team]?.isNotEmpty() == true) {
                    return completeTeamSurrender(
                        team,
                        snapshot,
                        decision.progress,
                        allowRetry = true,
                        expectedGeneration = decision.generation
                    )
                }
                player.sendSystemMessage("你已被判定淘汰，无需参与团队投降投票。${renderSurrenderVoteProgress(decision.progress)}")
                return true
            }

            ensureSurrenderVoteProbeTask()
            if (decision.progress.complete) {
                return completeTeamSurrender(
                    team,
                    snapshot,
                    decision.progress,
                    allowRetry = true,
                    expectedGeneration = decision.generation
                )
            }

            if (decision.added) {
                val message = "${player.name} 已申请团队投降。${renderSurrenderVoteProgress(decision.progress)}"
                snapshot.onlineMembers.distinctBy(::surrenderVoteKey).forEach { it.sendSystemMessage(message) }
            } else {
                player.sendSystemMessage("你的团队投降申请已经记录。${renderSurrenderVoteProgress(decision.progress)}")
            }
            return true
        }

        return executeSurrender(player, room, connection)
    }
    private fun executeSurrender(player: PlayerHess, room: ServerRoom, connection: net.rwhps.server.net.core.server.AbstractNetConnectServer): Boolean {
        runCatching {
            connection.sendSurrender()
        }.getOrElse { error ->
            player.sendSystemMessage("投降提交失败 请稍后重试")
            Log.warn("[Surrender] Failed to surrender player ${player.name}", error)
            return false
        }
        return runCatching {
            val message = "已提交投降 本局会按游戏结果正常结算"
            player.sendSystemMessage(message)
            val broadcastMessage = "{player} 已投降".replace("{player}", player.name)
            room.playerManage.playerGroup.eachAll { target ->
                if (target !== player) {
                    target.sendSystemMessage(broadcastMessage)
                }
            }
            Log.clog(
                "[Surrender] Player surrender requested: room=${room.roomID}, player=${player.name}, index=${player.index}"
            )
            true
        }.getOrElse { error ->
            Log.warn("[Surrender] Surrender was submitted but acknowledgement failed for ${player.name}", error)
            true
        }
    }

    fun getSurrenderVoteProgress(room: ServerRoom, team: Int): TeamSurrenderVoteProgress {
        val snapshot = buildSurrenderTeamSnapshot(room, team)
        val requiredVoterKeys = snapshot.requiredVoters.mapTo(linkedSetOf(), ::surrenderVoteKey)
        return synchronized(surrenderVoteStateLock) {
            val recordedVotes = surrenderVotesPerTeam[team]
            recordedVotes?.retainAll(requiredVoterKeys)
            teamSurrenderSubmittedVoterKeys[team]?.retainAll(requiredVoterKeys)
            calculateSurrenderVoteProgress(snapshot, recordedVotes.orEmpty())
        }
    }

    fun getSurrenderVoteStatusMessage(room: ServerRoom, team: Int): String {
        if (team in teamSurrenderSubmissionsInFlight) {
            return "团队投降已通过，正在由游戏核心处理。"
        }
        val progress = renderSurrenderVoteProgress(getSurrenderVoteProgress(room, team))
        return if (team in teamSurrenderRetryRequired) {
            "上次团队投降提交未完成，请任一存活队员重新点击投降。$progress"
        } else {
            progress
        }
    }

    fun renderSurrenderVoteProgress(progress: TeamSurrenderVoteProgress): String {
        val exemptions = buildList {
            if (progress.eliminatedExemptCount > 0) {
                add("已淘汰免投票 ${progress.eliminatedExemptCount} 人")
            }
            if (progress.disconnectedExemptCount > 0) {
                add("离线免投票 ${progress.disconnectedExemptCount} 人")
            }
            if (progress.reconnectPendingCount > 0) {
                add("短线重连确认 ${progress.reconnectPendingCount} 人")
            }
        }
        val exemptionText = if (exemptions.isEmpty()) "" else "；${exemptions.joinToString("，")}"
        return "团队投降进度：已申请 ${progress.votedCount}/${progress.requiredCount} 人，还差 ${progress.remainingCount} 人$exemptionText"
    }

    private fun buildSurrenderTeamSnapshot(room: ServerRoom, team: Int): SurrenderTeamSnapshot {
        val teamMembers = ArrayList<PlayerHess>()
        room.playerManage.playerAll.eachAll { teammate ->
            if (!teammate.isAi && teammate.team == team && !StartModeLobbySupport.isSpectatorTeam(teammate.team)) {
                teamMembers += teammate
            }
        }
        val onlineMembers = ArrayList<PlayerHess>(teamMembers.size)
        val requiredVoters = ArrayList<PlayerHess>(teamMembers.size)
        var eliminatedExemptCount = 0
        var disconnectedExemptCount = 0
        var reconnectPendingCount = 0

        teamMembers.forEach { teammate ->
            val alive = runCatching { teammate.survive }.getOrDefault(true)
            val connected = room.playerManage.isConnectedHumanPlayer(teammate)
            val reconnectPending = !connected && StartModePlayerExperienceSupport.isWithinReconnectGrace(teammate)
            if (connected) {
                onlineMembers += teammate
            }
            when {
                !alive -> eliminatedExemptCount++
                connected -> requiredVoters += teammate
                reconnectPending -> {
                    requiredVoters += teammate
                    reconnectPendingCount++
                }
                else -> disconnectedExemptCount++
            }
        }

        return SurrenderTeamSnapshot(
            onlineMembers = onlineMembers,
            requiredVoters = requiredVoters,
            eliminatedExemptCount = eliminatedExemptCount,
            disconnectedExemptCount = disconnectedExemptCount,
            reconnectPendingCount = reconnectPendingCount
        )
    }

    private fun calculateSurrenderVoteProgress(
        snapshot: SurrenderTeamSnapshot,
        recordedVotes: Set<String>
    ): TeamSurrenderVoteProgress {
        return TeamSurrenderVotePolicy.calculate(
            requiredVoterKeys = snapshot.requiredVoters.mapTo(linkedSetOf(), ::surrenderVoteKey),
            recordedVoteKeys = recordedVotes,
            eliminatedExemptCount = snapshot.eliminatedExemptCount,
            disconnectedExemptCount = snapshot.disconnectedExemptCount,
            reconnectPendingCount = snapshot.reconnectPendingCount
        )
    }

    private fun completeTeamSurrender(
        team: Int,
        snapshot: SurrenderTeamSnapshot,
        progress: TeamSurrenderVoteProgress,
        allowRetry: Boolean = false,
        expectedGeneration: Long? = null
    ): Boolean {
        val requiredVoters = snapshot.requiredVoters.distinctBy(::surrenderVoteKey)
        val requiredVoterKeys = requiredVoters.mapTo(linkedSetOf(), ::surrenderVoteKey)
        val submissionStart = synchronized(surrenderVoteStateLock) {
            if (expectedGeneration != null && expectedGeneration != surrenderVoteGeneration) {
                return true
            }
            val activeVotes = surrenderVotesPerTeam[team]
            if (activeVotes == null || activeVotes.isEmpty()) {
                return true
            }
            val submittedKeys = teamSurrenderSubmittedVoterKeys
                .computeIfAbsent(team) { ConcurrentHashMap.newKeySet() }
                .also { it.retainAll(requiredVoterKeys) }
            val pendingKeys = TeamSurrenderVotePolicy.pendingSubmissionKeys(requiredVoterKeys, submittedKeys)
            if (!allowRetry && team in teamSurrenderRetryRequired && pendingKeys.isNotEmpty()) {
                return true
            }
            if (!teamSurrenderSubmissionsInFlight.add(team)) {
                return true
            }
            if (allowRetry) {
                teamSurrenderRetryRequired.remove(team)
            }
            SurrenderSubmissionStart(
                alreadySubmittedKeys = submittedKeys.toSet(),
                generation = surrenderVoteGeneration
            )
        }
        val pendingVoters = requiredVoters.filter { surrenderVoteKey(it) !in submissionStart.alreadySubmittedKeys }
        if (pendingVoters.isEmpty() && requiredVoters.isEmpty()) {
            synchronized(surrenderVoteStateLock) {
                if (submissionStart.generation == surrenderVoteGeneration && team in teamSurrenderSubmissionsInFlight) {
                    teamSurrenderSubmissionsInFlight.remove(team)
                    surrenderVotesPerTeam.remove(team)
                    teamSurrenderSubmittedVoterKeys.remove(team)
                    teamSurrenderRetryRequired.remove(team)
                    surrenderVoteProgressSignatures.remove(team)
                }
            }
            return true
        }
        val failedPlayers = ArrayList<PlayerHess>()
        pendingVoters.forEach { teammate ->
            var submitError: Exception? = null
            val submitted = synchronized(surrenderVoteStateLock) {
                if (submissionStart.generation != surrenderVoteGeneration || team !in teamSurrenderSubmissionsInFlight) {
                    return true
                }
                val teammateConnection = teammate.con
                if (teammateConnection == null) {
                    false
                } else {
                    try {
                        teammateConnection.sendSurrender()
                        teamSurrenderSubmittedVoterKeys
                            .computeIfAbsent(team) { ConcurrentHashMap.newKeySet() }
                            .add(surrenderVoteKey(teammate))
                        true
                    } catch (error: Exception) {
                        submitError = error
                        false
                    }
                }
            }
            if (!submitted) {
                failedPlayers += teammate
                submitError?.let { Log.warn("[Surrender] Failed to submit team surrender for ${teammate.name}", it) }
                return@forEach
            }
        }
        if (failedPlayers.isNotEmpty()) {
            val totalSubmittedCount = synchronized(surrenderVoteStateLock) {
                if (submissionStart.generation != surrenderVoteGeneration || team !in teamSurrenderSubmissionsInFlight) {
                    return true
                }
                teamSurrenderSubmissionsInFlight.remove(team)
                teamSurrenderRetryRequired.add(team)
                val submittedCount = teamSurrenderSubmittedVoterKeys[team]?.count { it in requiredVoterKeys } ?: 0
                val message = "团队投降提交未完成：已提交 $submittedCount/${requiredVoters.size} 人，" +
                        "失败 ${failedPlayers.size} 人。请稍后重新点击投降。"
                snapshot.onlineMembers.distinctBy(::surrenderVoteKey).forEach { it.sendSystemMessage(message) }
                submittedCount
            }
            Log.warn(
                "[Surrender] Team $team surrender submission incomplete: " +
                        "submitted=$totalSubmittedCount/${requiredVoters.size}, failed=${failedPlayers.size}"
            )
            return false
        }
        val submissionStillCurrent = synchronized(surrenderVoteStateLock) {
            if (submissionStart.generation != surrenderVoteGeneration || team !in teamSurrenderSubmissionsInFlight) {
                false
            } else {
                surrenderVotesPerTeam.remove(team)
                teamSurrenderSubmittedVoterKeys.remove(team)
                teamSurrenderRetryRequired.remove(team)
                surrenderVoteProgressSignatures.remove(team)
                true
            }
        }
        if (!submissionStillCurrent) {
            return true
        }
        val message = "团队投降已通过。${renderSurrenderVoteProgress(progress)}"
        val acknowledgementCurrent = synchronized(surrenderVoteStateLock) {
            if (submissionStart.generation != surrenderVoteGeneration || team !in teamSurrenderSubmissionsInFlight) {
                false
            } else {
                snapshot.onlineMembers.distinctBy(::surrenderVoteKey).forEach { it.sendSystemMessage(message) }
                true
            }
        }
        if (!acknowledgementCurrent) {
            return true
        }
        Log.clog(
            "[Surrender] Team $team surrender accepted: voted=${progress.votedCount}/${progress.requiredCount}, " +
                    "eliminatedExempt=${progress.eliminatedExemptCount}, disconnectedExempt=${progress.disconnectedExemptCount}"
        )
        return true
    }

    private fun ensureSurrenderVoteProbeTask() {
        if (Threads.containsTimeTask(SURRENDER_VOTE_PROBE_TASK_NAME, SURRENDER_VOTE_PROBE_TASK_GROUP)) {
            return
        }
        synchronized(surrenderVoteStateLock) {
            if (Threads.containsTimeTask(SURRENDER_VOTE_PROBE_TASK_NAME, SURRENDER_VOTE_PROBE_TASK_GROUP)) {
                return
            }
            Threads.newTimedTask(
                SURRENDER_VOTE_PROBE_TASK_NAME,
                SURRENDER_VOTE_PROBE_TASK_GROUP,
                SURRENDER_VOTE_PROBE_TASK_DESCRIPTION,
                1,
                SURRENDER_VOTE_PROBE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            ) {
                runCatching { reconcileActiveSurrenderVotes("scheduled") }
                    .onFailure { Log.warn("[Surrender] Active vote reconciliation failed", it) }
            }
        }
    }

    private fun reconcileActiveSurrenderVotes(reason: String) {
        if (surrenderVotesPerTeam.isEmpty()) {
            return
        }
        if (Data.configStartMode.enableIndividualSurrender) {
            clearSurrenderVoteRuntimeState()
            return
        }
        val room = runCatching {
            if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps.room else null
        }.getOrNull() ?: return
        if (!room.isStartGame) {
            clearSurrenderVoteRuntimeState()
            return
        }
        surrenderVotesPerTeam.keys.toList().forEach { team ->
            reconcileSurrenderTeamIfActive(room, team, reason)
        }
    }

    fun reconcileSurrenderTeamIfActive(room: ServerRoom, team: Int, reason: String) {
        if (!room.isStartGame || Data.configStartMode.enableIndividualSurrender) {
            return
        }
        lateinit var snapshot: SurrenderTeamSnapshot
        var progressChanged = false
        var decisionGeneration = -1L
        val progress = synchronized(surrenderVoteStateLock) {
            snapshot = buildSurrenderTeamSnapshot(room, team)
            val requiredVoterKeys = snapshot.requiredVoters.mapTo(linkedSetOf(), ::surrenderVoteKey)
            val recordedVotes = surrenderVotesPerTeam[team] ?: return
            recordedVotes.retainAll(requiredVoterKeys)
            teamSurrenderSubmittedVoterKeys[team]?.retainAll(requiredVoterKeys)
            if (recordedVotes.isEmpty()) {
                surrenderVotesPerTeam.remove(team, recordedVotes)
                teamSurrenderSubmittedVoterKeys.remove(team)
                teamSurrenderRetryRequired.remove(team)
                surrenderVoteProgressSignatures.remove(team)
                return
            }
            val current = calculateSurrenderVoteProgress(snapshot, recordedVotes)
            val signature = surrenderVoteProgressSignature(snapshot, current)
            val previous = surrenderVoteProgressSignatures.put(team, signature)
            progressChanged = previous != null && previous != signature
            decisionGeneration = surrenderVoteGeneration
            current
        }

        if (progress.complete) {
            completeTeamSurrender(
                team,
                snapshot,
                progress,
                expectedGeneration = decisionGeneration
            )
            return
        }
        if (decisionGeneration != surrenderVoteGeneration) {
            return
        }
        if (progressChanged) {
            val message = "团队投降资格已更新。${renderSurrenderVoteProgress(progress)}"
            snapshot.onlineMembers.distinctBy(::surrenderVoteKey).forEach { it.sendSystemMessage(message) }
            Log.clog("[Surrender] Team $team vote reconciled: reason=$reason, voted=${progress.votedCount}/${progress.requiredCount}")
        }
    }

    private fun surrenderVoteProgressSignature(
        snapshot: SurrenderTeamSnapshot,
        progress: TeamSurrenderVoteProgress
    ): String {
        val requiredKeys = snapshot.requiredVoters.map(::surrenderVoteKey).sorted().joinToString(",")
        return "$requiredKeys|${progress.votedCount}/${progress.requiredCount}|" +
                "${progress.eliminatedExemptCount}/${progress.disconnectedExemptCount}/${progress.reconnectPendingCount}"
    }

    fun clearSurrenderVoteRuntimeState() {
        synchronized(surrenderVoteStateLock) {
            surrenderVoteGeneration++
            surrenderVotesPerTeam.clear()
            teamSurrenderSubmissionsInFlight.clear()
            teamSurrenderSubmittedVoterKeys.clear()
            teamSurrenderRetryRequired.clear()
            surrenderVoteProgressSignatures.clear()
        }
    }

    fun onSurrenderModeConfigReload(previousIndividualMode: Boolean, currentIndividualMode: Boolean) {
        if (previousIndividualMode == currentIndividualMode) {
            return
        }
        clearSurrenderVoteRuntimeState()
        Log.clog(
            "[Surrender] Cleared active vote state after mode change: " +
                    "individual=$previousIndividualMode->$currentIndividualMode"
        )
    }

    private fun surrenderVoteKey(player: PlayerHess): String {
        return player.connectHexID.trim().ifBlank { "slot:${player.index}" }
    }

    fun isSurrenderInput(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return false
        }
        val prefixed = trimmed.first() in charArrayOf('/', '.', '-', '_')
        val normalized = if (prefixed) {
            trimmed.substring(1).trimStart().substringBefore(' ').lowercase(Locale.ROOT)
        } else {
            trimmed.lowercase(Locale.ROOT)
        }
        return if (prefixed) {
            normalized in setOf("surrender", "ff", "forfeit", "giveup", "give_up", "resign", "投降", "认输", "放弃")
        } else {
            normalized in setOf("投降", "认输", "放弃", "我要投降", "我要认输", "我要放弃", "ff")
        }
    }

    // ------------------------------------------------------------------
    // 非排位路径也依赖的生命周期钩子
    // ------------------------------------------------------------------

    /** 开局时清空上一局的投降投票状态。**非排位路径必须调用。** */
    fun onGameStart() {
        clearSurrenderVoteRuntimeState()
    }

    /** 结算时清空投降投票状态。**非排位路径必须调用。** */
    fun onGameOver() {
        clearSurrenderVoteRuntimeState()
    }

    /**
     * 玩家离开：清理其投票键，并在没有同键替补玩家时移除其选票，最后重新评估该队伍的投降资格。
     * **非排位路径必须调用**（原本位于 `RankedShardAdapterSupport.onPlayerLeave` 的排位分支之前）。
     */
    fun onPlayerLeave(player: PlayerHess, room: ServerRoom) {
        val playerVoteKey = surrenderVoteKey(player)
        var replacementConnected = false
        room.playerManage.playerAll.eachAll {
            if (it !== player &&
                surrenderVoteKey(it) == playerVoteKey &&
                room.playerManage.isConnectedHumanPlayer(it)
            ) {
                replacementConnected = true
            }
        }
        if (!replacementConnected) {
            synchronized(surrenderVoteStateLock) {
                surrenderVotesPerTeam[player.team]?.remove(playerVoteKey)
                teamSurrenderSubmittedVoterKeys[player.team]?.remove(playerVoteKey)
            }
        }
        reconcileSurrenderTeamIfActive(room, player.team, "player_leave")
    }

    /** 玩家加入后重新评估其队伍的投降资格（供排位路径复用；非排位路径由探针任务周期对账）。 */
    fun onPlayerJoinReconcile(room: ServerRoom, team: Int) {
        reconcileSurrenderTeamIfActive(room, team, "player_join")
    }
}