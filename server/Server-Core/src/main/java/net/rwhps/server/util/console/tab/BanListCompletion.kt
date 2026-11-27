/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.console.tab

import net.rwhps.server.struct.list.Seq
import org.jline.reader.Candidate

/**
 *
 *
 * @date 2024/2/7 9:12
 * @author Dr (dr@der.kim)
 */
class BanListCompletion : AbstractTabCompletion {
    override fun complete(paramsIn: String, candidates: MutableList<Candidate>) {
        val candidatesList = Seq<String>()
        val params = paramsIn.replace("\"", "")
        candidates += candidatesList.map {
            Candidate(it)
        }
    }
}