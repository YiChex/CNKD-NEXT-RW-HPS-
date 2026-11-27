/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log.ex

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.algorithms.Algorithms
import java.io.Closeable

/**
 * @author Dr (dr@der.kim)
 */
class ProgressBar(
    private val start: Int, private val end: Int
): Closeable {

    fun progress(progressIn: Int) {
        val progress = Algorithms.scale(progressIn.toDouble(), start.toDouble(), end.toDouble(), 0.0, 100.0).toInt()
        Data.privateOut.print(String.format("\r[%-100s] %d%%", "#".repeat(progress), progress))
    }

    override fun close() {
        Data.privateOut.print(Data.LINE_SEPARATOR)
    }
}