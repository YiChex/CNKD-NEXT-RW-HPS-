/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import java.util.Collections
import java.util.Locale
import java.util.Random

/**
 * Pure policy helpers for the pre-game team-layout commands.
 * Engine mutation stays in ClientCommands so every layout uses the same
 * game-thread dispatch, permission checks and single lobby broadcast.
 */
internal object StartModeTeamLayoutSupport {
    data class Assignment(
        val playerSlot: Int,
        val targetPosition: Int,
        val team: Int
    )

    enum class Layout(val wireName: String) {
        TWO_SIDES("layout_2sides"),
        THREE_SIDES("layout_3sides"),
        FFA("layout_ffa"),
        SPECTATORS("layout_spectators")
    }

    fun parseLayout(raw: String): Layout? {
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "2", "2v2", "2teams", "two", "two_sides", "layout_2sides" -> Layout.TWO_SIDES
            "3", "3v3", "3teams", "three", "three_sides", "layout_3sides" -> Layout.THREE_SIDES
            "ffa", "mixed", "freeforall", "free_for_all", "layout_ffa" -> Layout.FFA
            "spectators", "spectator", "all_spectators", "layout_spectators" -> Layout.SPECTATORS
            else -> null
        }
    }

    /**
     * Builds a compact stock-style layout from the currently active players.
     * The native helper sizes its lanes from the engine capacity, which can
     * leave the last team in a distant slot when maxPlayer is larger than the
     * current lobby. This path keeps the official team lanes while using only
     * the lowest available positions and preserving spectator slots.
     */
    fun planAssignments(
        activePlayerSlots: List<Int>,
        spectatorSlots: Set<Int>,
        maxSlots: Int,
        layout: Layout,
        random: Random = Random()
    ): List<Assignment> {
        if (activePlayerSlots.isEmpty() || maxSlots <= 0) {
            return emptyList()
        }

        val active = activePlayerSlots.distinct()
        val reserved = spectatorSlots.filter { it in 0 until maxSlots }.toSet()
        val targetSlots = (0 until maxSlots).filter { it !in reserved }
        if (targetSlots.size < active.size) {
            return emptyList()
        }
        val shuffled = active.toMutableList()
        Collections.shuffle(shuffled, random)

        val assignments = ArrayList<Assignment>(active.size)
        val firstTeam = if (layout == Layout.TWO_SIDES &&
            shuffled.size % 2 != 0 && random.nextInt(2) == 1
        ) {
            1
        } else {
            0
        }

        shuffled.forEachIndexed { index, playerSlot ->
            val target = targetSlots[index]
            val team = when (layout) {
                Layout.TWO_SIDES -> (index + firstTeam) % 2
                Layout.THREE_SIDES -> index % 3
                Layout.FFA -> index
                Layout.SPECTATORS -> StartModeLobbySupport.SPECTATOR_TEAM
            }
            assignments += Assignment(playerSlot, target, team)
        }

        return assignments
    }
}
