/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core.link

import net.rwhps.server.util.annotations.mark.GameSimulationLayer

@GameSimulationLayer.GameSimulationLayer_KeyWords(
        "Note to modifiers: Changing credits will not allow you to cheat in multiplayer games, but it will only break sync"
)
interface AbstractLinkPlayerData {
    fun updateDate()

    @GameSimulationLayer.GameSimulationLayer_KeyWords("is victorious!")
    val survive: Boolean

    @GameSimulationLayer.GameSimulationLayer_KeyWords("Units Killed")
            /** 单位击杀数 */
    val unitsKilled: Int

    /** 建筑毁灭数 */
    val buildingsKilled: Int

    /** 单实验单位击杀数 */
    val experimentalsKilled: Int

    /** 单位被击杀数 */
    val unitsLost: Int

    /** 建筑被毁灭数 */
    val buildingsLost: Int

    /** 单实验单位被击杀数 */
    val experimentalsLost: Int

    var credits: Int

    val name: String
    val connectHexID: String
    var index: Int
    var team: Int
    var startUnit: Int
    var color: Int

    var aiDifficulty: Int

    fun removePlayer() {}
}