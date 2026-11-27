/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core

import com.corrodinggames.rts.game.units.custom.ag
import com.corrodinggames.rts.game.units.custom.l
import com.corrodinggames.rts.game.units.y
import com.corrodinggames.rts.gameFramework.w
import net.rwhps.server.game.headless.core.AbstractGameUnitData
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.inline.findField
import net.rwhps.server.util.log.Log

/**
 * @author Dr (dr@der.kim)
 */
internal class GameUnitData: AbstractGameUnitData {
    override var useMod: Boolean
        get() = GameEngine.netEngine.o
        set(value) {
            GameEngine.netEngine.o = value
        }

    override fun reloadUnitData() {
        ag.h()
    }

    @Suppress("UNCHECKED_CAST")
    override fun getUnitData(coreName: String): OrderedMap<String, ObjectMap<String, Int>> {
        val modsData = OrderedMap<String, ObjectMap<String, Int>>()
        val gameUnitDataList: List<l> = l.c as List<l>

        for (data in gameUnitDataList) {
            val group = data.t() ?: coreName
            if (modsData.containsKey(group)) {
                modsData[group]!![data.M] = data.H
            } else {
                val cache = ObjectMap<String, Int>()
                modsData[group] = cache
                cache[data.M] = data.H
            }
        }

        return modsData
    }

    @Suppress("UNCHECKED_CAST")
    override fun getRwModLoadInfo(): Seq<String> {
        return Seq<String>().apply {
            for (a in (GameEngine.gameEngine.bZ::class.java.findField("e")!![GameEngine.gameEngine.bZ]
                    as ArrayList<com.corrodinggames.rts.gameFramework.i.b>)) {
                a.U.iterator().forEach {
                    add(it.toString())
                }
            }
        }
    }

    /*
     * D-28: the debug helper `kill()` that used to live here had no caller anywhere in the
     * codebase and blocked the calling thread with `Thread.sleep(100)`. It was removed.
     * If a unit-removal hook is needed again, implement it on the game loop with an
     * explicit, documented delay rather than sleeping the caller.
     */
}