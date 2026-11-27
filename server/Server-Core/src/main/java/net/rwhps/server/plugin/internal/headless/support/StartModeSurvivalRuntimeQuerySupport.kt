/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.game.units.am
import com.corrodinggames.rts.game.units.d.e as CommandCenterUnit
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.inline.findMethod
import java.util.ArrayList

internal object StartModeSurvivalRuntimeQuerySupport {
    fun snapshotPlayers(room: ServerRoom): List<PlayerHess> {
        val players = ArrayList<PlayerHess>()
        room.playerManage.playerGroup.eachAll { player ->
            players += player
        }
        return players
    }

    fun snapshotAllPlayers(room: ServerRoom): List<PlayerHess> {
        val players = ArrayList<PlayerHess>()
        room.playerManage.playerAll.eachAll { player ->
            players += player
        }
        return players
    }

    fun hasHumanInsideReconnectGrace(room: ServerRoom): Boolean {
        return snapshotAllPlayers(room).any { player ->
            !player.isAi &&
                player.con == null &&
                StartModePlayerExperienceSupport.isWithinReconnectGrace(player)
        }
    }

    fun hasEnemyUnitOutsideHumanGroup(humanGroup: Int): Boolean {
        return runCatching {
            for (unit in am.bF()) {
                if (
                    unit is am &&
                    !unit.bV &&
                    unit.bX != null &&
                    unit.bX.r >= 0 &&
                    unit.bX.r != humanGroup
                ) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    fun countHumanMainBuildings(
        layout: StartModeSurvivalMapSupport.SurvivalLayout,
        primaryOnly: Boolean
    ): Int {
        return runCatching {
            val humanIndexes = if (primaryOnly) {
                setOf(layout.primaryHumanSlot.playerIndex)
            } else {
                layout.humanPlayerIndexes
            }
            var count = 0
            for (unit in am.bF()) {
                if (
                    unit is am &&
                    !unit.bV &&
                    !unit.u() &&
                    unit.bX != null &&
                    unit.bX.k in humanIndexes &&
                    isMainBaseUnit(unit)
                ) {
                    count++
                }
            }
            count
        }.getOrDefault(0)
    }

    fun countMainBuildingsForPlayerIndex(playerIndex: Int): Int {
        return countMainBuildingsForPlayer(n.k(playerIndex))
    }

    fun countMainBuildingsForPlayer(player: n?): Int {
        if (player == null) {
            return 0
        }
        return runCatching {
            var count = 0
            for (unit in am.bF()) {
                if (
                    unit is am &&
                    !unit.bV &&
                    !unit.u() &&
                    unit.bX === player &&
                    isMainBaseUnit(unit)
                ) {
                    count++
                }
            }
            count
        }.getOrDefault(0)
    }

    /**
     * Counts live units owned by one human player after the engine has created
     * the starting roster. Ranked maps may use aircraft or other non-building
     * starts, so this is separate from main-building probes.
     */
    fun countLiveUnitsForPlayer(player: PlayerHess): Int {
        val module = runCatching {
            if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps else null
        }.getOrNull() ?: return 0

        var count = 0
        val inspected = runCatching {
            module.gameFunction.suspendMainThreadOperations {
                count = countLiveUnitsForPlayer(module.useClassLoader, player.index)
            }
        }.getOrDefault(false)
        return if (inspected) count else 0
    }

    /**
     * Game classes live behind HPS's isolated loader. Resolving them through
     * this support object's loader fails after ranked start callbacks begin.
     */
    private fun countLiveUnitsForPlayer(loader: ClassLoader, playerIndex: Int): Int {
        return runCatching {
            val playerClass = Class.forName("com.corrodinggames.rts.game.n", false, loader)
            val unitClass = Class.forName("com.corrodinggames.rts.game.units.am", false, loader)
            val enginePlayer = playerClass.findMethod("k", Int::class.javaPrimitiveType!!)
                ?.also { it.trySetAccessible() }
                ?.invoke(null, playerIndex)
                ?: return@runCatching 0
            val units = unitClass.findMethod("bF")
                ?.also { it.trySetAccessible() }
                ?.invoke(null)
                ?: return@runCatching 0
            val iterator = unitIterator(units) ?: return@runCatching 0
            val removedField = unitClass.findField("bV") ?: return@runCatching 0
            val destroyedMethod = unitClass.findMethod("u")
                ?.also { it.trySetAccessible() }
                ?: return@runCatching 0
            val ownerField = unitClass.findField("bX") ?: return@runCatching 0
            var count = 0
            while (iterator.hasNext()) {
                val unit = iterator.next() ?: continue
                if (!unitClass.isInstance(unit) || removedField.getBoolean(unit)) {
                    continue
                }
                if ((destroyedMethod.invoke(unit) as? Boolean) == true) {
                    continue
                }
                if (ownerField.get(unit) === enginePlayer) {
                    count++
                }
            }
            count
        }.getOrDefault(0)
    }

    private fun unitIterator(units: Any): Iterator<Any?>? {
        if (units is Iterable<*>) {
            return units.iterator()
        }
        if (!units.javaClass.isArray) {
            return null
        }
        val size = java.lang.reflect.Array.getLength(units)
        return object : Iterator<Any?> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): Any? = java.lang.reflect.Array.get(units, index++)
        }
    }

    private fun isMainBaseUnit(unit: am): Boolean {
        return unit is CommandCenterUnit || unit.bP
    }
}
