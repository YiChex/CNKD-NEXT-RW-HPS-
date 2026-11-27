/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core.link

import com.corrodinggames.rts.game.n
import net.rwhps.server.game.headless.core.link.AbstractLinkPlayerData
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine

/**
 * @author Dr (dr@der.kim)
 */
internal open class PrivateClassLinkPlayer(private val playerData: n): AbstractLinkPlayerData {
    private var gameStatistics = GameEngine.gameStatistics.a(playerData)

    override fun updateDate() {
        gameStatistics = GameEngine.gameStatistics.a(playerData)
    }

    // 队伍是否为观战 && 没判定过该玩家 && 不知道 && 不知道
    override val survive get() = (!playerData.b() && !playerData.G && !playerData.F && !playerData.E)


    /** 单位击杀数 */
    override val unitsKilled: Int get() = gameStatistics.c

    /** 建筑毁灭数 */
    override val buildingsKilled get() = gameStatistics.d

    /** 单实验单位击杀数 */
    override val experimentalsKilled get() = gameStatistics.e

    /** 单位被击杀数 */
    override val unitsLost get() = gameStatistics.f

    /** 建筑被毁灭数 */
    override val buildingsLost get() = gameStatistics.g

    /** 单实验单位被击杀数 */
    override val experimentalsLost get() = gameStatistics.h

    /** 玩家的资金 */
    override var credits
        get() = playerData.o.toInt()
        set(value) {
            playerData.o = value.toDouble()
        }

    override val name = playerData.v
    override val connectHexID = playerData.O

    override var index
        get() = playerData.k
        set(value) {
            playerData.k = value
        }
    override var team
        get() = playerData.r
        set(value) {
            playerData.r = value
        }

    override var startUnit
        get() = playerData.A ?: defaultStartingUnit()
        set(value) {
            playerData.A = value
        }

    override var color
        get() = playerData.C
        set(value) {
            playerData.C = value
        }

    override var aiDifficulty = Int.MIN_VALUE

    override fun removePlayer() {
        playerData.I()
    }

    private fun defaultStartingUnit(): Int {
        return runCatching { GameEngine.netEngine.ay.g }.getOrDefault(0)
    }
}
