/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.algorithms

/**
 * @author Dr (dr@der.kim)
 */
object Algorithms {
    /**
     * in (0,0,100,0,100) -> 0
     * in (100,0,100,0,100) -> 100
     * in (1000,0,100,0,100) -> 100
     *
     *
     * @param valueIn Double
     * @param baseMin Double
     * @param baseMax Double
     * @param limitMin Double
     * @param limitMax Double
     * @return Double
     */
    fun scale(valueIn: Double, baseMin: Double, baseMax: Double, limitMin: Double, limitMax: Double): Double {
        return (limitMax - limitMin) * (valueIn - baseMin) / (baseMax - baseMin) + limitMin
    }
}