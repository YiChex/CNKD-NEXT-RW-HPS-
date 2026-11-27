/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom

internal object StartModeSurvivalLayoutSupport {
    fun layout(room: ServerRoom? = null): StartModeSurvivalMapSupport.SurvivalLayout {
        return StartModeSurvivalMapSupport.currentLayout(room)
    }

    fun humanAllyGroup(room: ServerRoom? = null): Int {
        return layout(room).humanAllyGroup
    }

    fun aiSlots(room: ServerRoom? = null): List<StartModeSurvivalMapSupport.SurvivalAiSlot> {
        return layout(room).aiSlots
    }

    fun primaryAiSlot(room: ServerRoom? = null): StartModeSurvivalMapSupport.SurvivalAiSlot {
        return layout(room).primaryAiSlot
    }

    fun humanSlots(room: ServerRoom? = null): List<StartModeSurvivalMapSupport.SurvivalHumanSlot> {
        return layout(room).humanSlots
    }

    fun primaryHumanSlot(room: ServerRoom? = null): StartModeSurvivalMapSupport.SurvivalHumanSlot {
        return layout(room).primaryHumanSlot
    }

    fun isHumanSlotIndex(index: Int, room: ServerRoom? = null): Boolean {
        return index in layout(room).humanPlayerIndexes
    }

    fun hasDeclaredHumanSlots(room: ServerRoom? = null): Boolean {
        return layout(room).hasDeclaredHumanSlots
    }

    fun isReservedAiIndex(index: Int, room: ServerRoom? = null): Boolean {
        return index in layout(room).reservedPlayerIndexes
    }

    fun aiAllyGroupForIndex(index: Int, room: ServerRoom? = null): Int? {
        return aiSlots(room).firstOrNull { it.playerIndex == index }?.allyGroup
    }

    fun humanAllyGroupForIndex(index: Int, room: ServerRoom? = null): Int? {
        return humanSlots(room).firstOrNull { it.playerIndex == index }?.allyGroup
    }

    fun findAvailableHumanPosition(players: Iterable<PlayerHess>, currentIndex: Int, room: ServerRoom? = null): Int? {
        val layout = layout(room)
        val reservedIndexes = layout.reservedPlayerIndexes
        val occupied = players.map { it.index }.toMutableSet()
        occupied.remove(currentIndex)
        for (slot in layout.humanSlots) {
            val index = slot.playerIndex
            if (index !in 0 until Data.configServer.maxPlayer) {
                continue
            }
            if (index in reservedIndexes) {
                continue
            }
            if (index !in occupied) {
                return index
            }
        }
        for (index in 0 until Data.configServer.maxPlayer) {
            if (index in reservedIndexes) {
                continue
            }
            if (index !in occupied) {
                return index
            }
        }
        return null
    }

    fun findAvailableDeclaredHumanPosition(players: Iterable<PlayerHess>, currentIndex: Int, room: ServerRoom? = null): Int? {
        val layout = layout(room)
        val reservedIndexes = layout.reservedPlayerIndexes
        val occupied = players.map { it.index }.toMutableSet()
        occupied.remove(currentIndex)
        for (slot in layout.humanSlots) {
            val index = slot.playerIndex
            if (index !in 0 until Data.configServer.maxPlayer) {
                continue
            }
            if (index in reservedIndexes) {
                continue
            }
            if (index !in occupied) {
                return index
            }
        }
        return null
    }

    fun findAvailableNonReservedPosition(
        players: Iterable<PlayerHess>,
        currentIndex: Int,
        room: ServerRoom? = null,
        avoidHumanSlots: Boolean = false
    ): Int? {
        val layout = layout(room)
        val reservedIndexes = layout.reservedPlayerIndexes
        val humanIndexes = layout.humanPlayerIndexes
        val occupied = players.map { it.index }.toMutableSet()
        occupied.remove(currentIndex)
        for (index in 0 until Data.configServer.maxPlayer) {
            if (index in reservedIndexes) {
                continue
            }
            if (avoidHumanSlots && index in humanIndexes) {
                continue
            }
            if (index !in occupied) {
                return index
            }
        }
        return null
    }

    fun reservedAiDescription(room: ServerRoom? = null): String {
        return aiSlots(room).joinToString(", ") { "slot ${it.playerIndex + 1}/ally ${it.allyGroup}" }
    }

    fun humanSlotsDescription(room: ServerRoom? = null): String {
        return humanSlots(room).joinToString(", ") { "slot ${it.playerIndex + 1}/ally ${it.allyGroup}" }
    }

    fun maxRequiredPlayerIndex(room: ServerRoom? = null): Int {
        val layout = layout(room)
        return (layout.aiSlots.map { it.playerIndex } + layout.humanSlots.map { it.playerIndex } + layout.profile.maxReferencedTeamIndex)
            .maxOrNull()
            ?: 1
    }
}
