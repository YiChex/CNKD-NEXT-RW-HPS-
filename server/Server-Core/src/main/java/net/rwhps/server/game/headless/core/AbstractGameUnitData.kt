/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core

import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.annotations.mark.GameSimulationLayer

interface AbstractGameUnitData {

    var useMod: Boolean

    @GameSimulationLayer.GameSimulationLayer_KeyWords("Failed to reserve memory pre-mod load")
    fun reloadUnitData()

    @GameSimulationLayer.GameSimulationLayer_KeyWords("NULL")
    fun getUnitData(coreName: String): OrderedMap<String, ObjectMap<String, Int>>

    fun getRwModLoadInfo(): Seq<String>
}