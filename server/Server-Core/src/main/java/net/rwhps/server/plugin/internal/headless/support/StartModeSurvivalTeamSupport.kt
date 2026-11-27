/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import com.corrodinggames.rts.game.a.a as AiTeamData
import com.corrodinggames.rts.game.e as HumanTeamData
import com.corrodinggames.rts.game.n
import net.rwhps.server.data.bean.BeanServerConfigFile
import net.rwhps.server.game.room.ServerRoom
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object StartModeSurvivalTeamSupport {
    private val repairFailures = AtomicLong(0L)
    private val runtimePlaceholderTeams = ConcurrentHashMap.newKeySet<Int>()

    fun provisionCoreTeams(
        room: ServerRoom,
        layout: StartModeSurvivalMapSupport.SurvivalLayout
    ): CoreTeamProvisionReport {
        return runCatching {
            val requiredIndexes = linkedSetOf<Int>()
            requiredIndexes += layout.profile.referencedTeams
            requiredIndexes += layout.humanSlots.map { it.playerIndex }
            requiredIndexes += layout.aiSlots.map { it.playerIndex }

            val validIndexes = requiredIndexes
                .filter { it >= 0 }
                .sorted()
            val requestedSlots = ((validIndexes.maxOrNull() ?: -1) + 1).coerceAtLeast(10)
            val safeSlots = requestedSlots.coerceAtMost(BeanServerConfigFile.GAME_MAX_PLAYER_SIZE)
            val skipped = validIndexes.filter { it >= safeSlots }.toMutableList()
            n.b(safeSlots, true)

            val created = ArrayList<Int>()
            val replaced = ArrayList<Int>()
            val repaired = ArrayList<Int>()
            val conflicts = ArrayList<Int>()
            val humanIndexes = layout.humanPlayerIndexes
            val aiSlotByIndex = layout.aiSlots.associateBy { it.playerIndex }
            val teamInfoByIndex = layout.profile.teamInfos.associateBy { it.team }

            for (index in validIndexes) {
                if (index >= safeSlots) {
                    continue
                }

                val managedPlayer = room.playerManage.getPlayer(index)
                val isHumanSlot = index in humanIndexes
                val aiSlot = aiSlotByIndex[index]
                val allyGroup = when {
                    isHumanSlot -> StartModeSurvivalLayoutSupport.humanAllyGroupForIndex(index, room) ?: layout.humanAllyGroup
                    aiSlot != null -> aiSlot.allyGroup
                    else -> teamInfoByIndex[index]?.allyGroup ?: layout.primaryAiSlot.allyGroup
                }

                val current = n.k(index)
                val team = when {
                    isHumanSlot -> {
                        val shouldCreateHumanPlaceholder = current == null ||
                            (managedPlayer == null && current is AiTeamData)
                        if (shouldCreateHumanPlaceholder) {
                            HumanTeamData(index, true).also {
                                if (current == null) {
                                    created += index
                                } else {
                                    replaced += index
                                }
                                if (managedPlayer == null) {
                                    runtimePlaceholderTeams += index
                                }
                            }
                        } else {
                            current
                        }
                    }
                    managedPlayer != null && !managedPlayer.isAi -> {
                        conflicts += index
                        current ?: continue
                    }
                    current == null -> AiTeamData(index, true).also {
                        created += index
                        runtimePlaceholderTeams += index
                    }
                    current is AiTeamData -> current
                    else -> AiTeamData(index, true).also {
                        replaced += index
                        if (managedPlayer == null) {
                            runtimePlaceholderTeams += index
                        }
                    }
                }

                val survivalAiBehavior = !isHumanSlot
                if (configureCoreTeam(team, index, allyGroup, survivalAiBehavior)) {
                    repaired += index
                }
            }

            CoreTeamProvisionReport(
                required = validIndexes,
                requestedSlots = requestedSlots,
                safeSlots = safeSlots,
                created = created,
                replaced = replaced,
                repaired = repaired.distinct(),
                skipped = skipped,
                conflicts = conflicts
            )
        }.getOrElse { error ->
            repairFailures.incrementAndGet()
            CoreTeamProvisionReport(error = error.message ?: error.javaClass.simpleName)
        }
    }

    fun clearRuntimePlaceholderTeams(room: ServerRoom) {
        val indexes = runtimePlaceholderTeams.toList()
        runtimePlaceholderTeams.clear()
        for (index in indexes) {
            if (room.playerManage.getPlayer(index) != null) {
                continue
            }
            runCatching {
                val team = n.k(index) ?: return@runCatching
                if (team.O == "CNKD-Survival-Team-$index") {
                    team.I()
                }
            }.onFailure {
                repairFailures.incrementAndGet()
            }
        }
    }

    fun repairFailureCount(): Long {
        return repairFailures.get()
    }

    private fun configureCoreTeam(team: n, index: Int, allyGroup: Int, survivalAiBehavior: Boolean): Boolean {
        var changed = false
        if (team.r != allyGroup) {
            team.r = allyGroup
            changed = true
        }
        if (team.v.isNullOrBlank()) {
            team.v = if (survivalAiBehavior) "CNKD-Survival-Enemy-${index + 1}" else "CNKD-Survival-Human-${index + 1}"
            changed = true
        }
        if (team.O.isNullOrBlank()) {
            team.O = "CNKD-Survival-Team-$index"
            changed = true
        }
        if (team is AiTeamData && team.U != survivalAiBehavior) {
            team.U = survivalAiBehavior
            changed = true
        }
        return changed
    }

    data class CoreTeamProvisionReport(
        val required: List<Int> = emptyList(),
        val requestedSlots: Int = 0,
        val safeSlots: Int = 0,
        val created: List<Int> = emptyList(),
        val replaced: List<Int> = emptyList(),
        val repaired: List<Int> = emptyList(),
        val skipped: List<Int> = emptyList(),
        val conflicts: List<Int> = emptyList(),
        val error: String = ""
    ) {
        fun toDiagnosticString(): String {
            if (error.isNotBlank()) {
                return "teams=error:$error"
            }
            return "teams=required:${required.joinToString(",").ifBlank { "<none>" }}/slots:$safeSlots/$requestedSlots/created:${created.joinToString(",").ifBlank { "<none>" }}/replaced:${replaced.joinToString(",").ifBlank { "<none>" }}/repaired:${repaired.joinToString(",").ifBlank { "<none>" }}/skipped:${skipped.joinToString(",").ifBlank { "<none>" }}/conflicts:${conflicts.joinToString(",").ifBlank { "<none>" }}"
        }
    }
}
