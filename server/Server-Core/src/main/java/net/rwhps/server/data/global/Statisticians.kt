/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.global

import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.Time
import net.rwhps.server.util.log.exp.VariableException

/**
 *
 *
 * @date 2024/2/4 13:48
 * @author Dr (dr@der.kim)
 */
object Statisticians {
    private val time = ObjectMap<String, Long>()

    /**
     * 设置一个ID绑定启始时间
     *
     * @param name ID
     */
    fun addTime(name: String) {
        if (time.contains(name)) {
            throw VariableException.RepeatAddException("[Statisticians.Time] Add $name")
        }
        time[name] = Time.millis()
    }
    /**
     * 通过ID获取时间差异
     * @param name String
     * @return Long
     */
    fun computeTime(name: String): Long {
        return Time.millis() - time.remove(name)!!
    }
}