/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.struct.list

/**
 * @date  2023/6/10 10:48
 * @author Dr (dr@der.kim)
 */
class SeqSave {
    val data = Seq<String>()

    private val maxSeq: Int

    constructor(maxSeq: Int) {
        this.maxSeq = maxSeq
    }

    fun addSeq(string: String) {
        if (data.size >= maxSeq) {
            data.removeFirst()
        }
        data.add(string)
    }
}