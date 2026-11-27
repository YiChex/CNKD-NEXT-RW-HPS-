/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.game

import net.rwhps.server.util.file.FileName


/**
 * @author Dr (dr@der.kim)
 */
object GameOtherUtils {
    @JvmStatic
    fun getBetaVersion(version: Int): Boolean {
        return version in 152 .. 175
    }

    /**
     * 处理 Map 的名称
     *
     * 游戏存在 :
     * MOD|1B16891712A941BEFBBFD57AD9F5388EAC58B99DB01D020C4E883834E64A2E27//maps/[杂乱（10P）大型太空（无固定资源点）]
     * NEW_PATH|maps2/10p幸运环湖3
     * 这种, 我们需要手动处理
     *
     * @param mapName 需要处理的名字
     */
    @JvmStatic
    fun mapProcessing(mapName: String): String {
        // 暴力点, 懒得处理
        return FileName.getFileName(mapName)
    }

    @JvmStatic
    fun getPoint(floats: Map<Float, Float>): FloatArray {
        var xOut = 0F
        var yOut = 0F
        floats.forEach { (x, y) ->
            if (xOut == 0F && yOut == 0F) {
                xOut = x
                yOut = y
            } else {
                xOut += x
                yOut += y

                xOut /= 2
                yOut /= 2
            }
        }
        return floatArrayOf(xOut, yOut)
    }
}