/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean.internal

import net.rwhps.server.data.totalizer.TimeAndNumber
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.Time
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 *
 *
 * @date 2024/2/23 10:59
 * @author Dr (dr@der.kim)
 */
class RCN {
    var startTime = Time.concurrentMillis()
    // 房间数量
    val roomSize = AtomicInteger(0)
    // UP了的
    val upCount = AtomicInteger(0)
    // 完成游戏人次
    val playerCount = AtomicLong(0)

    val playerChatCount = ObjectMap<String, TimeAndNumber>()
}