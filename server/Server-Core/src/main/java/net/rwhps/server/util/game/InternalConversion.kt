/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.game

/**
 *
 *
 * @date 2024/1/30 19:29
 * @author Dr (dr@der.kim)
 */
object InternalConversion {
    fun getAIDifficultString(i: Int): String {
        return when (i) {
            -2 -> "Very Easy"
            -1 -> "Easy"
            0 -> "Medium"
            1 -> "Hard"
            2 -> "Very Hard"
            3 -> "Impossible"
            else -> "Unknown"
        }
    }
}