/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.func

/**
 * 作为和 Find 的区别
 * 对于自定义的 eachAll, 有时候我们需要返回, 或者是类似于 Find, 但不需要返回
 *
 * @date  2023/6/22 17:24
 * @author Dr (dr@der.kim)
 */
object Control {
    enum class ControlFind {
        BREAK,
        CONTINUE
    }

    enum class ControlSync {
        ASync,
        Sync
    }

    enum class ControlListening {
        LISTENING,
        STOPPED
    }

    enum class EventNext {
        CONTINUE,
        STOPPED
    }
}
