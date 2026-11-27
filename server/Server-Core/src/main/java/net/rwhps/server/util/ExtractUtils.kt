/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import net.rwhps.server.util.inline.ifNull
import net.rwhps.server.util.log.Log

/**
 * @date 2023/7/19 11:29
 * @author Dr (dr@der.kim)
 */
object ExtractUtils {
    fun tryRunTest(tag: String? = null, run: () -> Unit) {
        try {
            run()
        } catch (e: Exception) {
            tag.ifNull({ Log.error(it, e) }) { Log.error(e) }
        }
    }

    inline fun synchronizedX(isSync: Boolean, obj: Any? = null, run: () -> Unit) {
        if (isSync) {
            synchronized(obj!!) {
                run()
            }
        } else {
            run()
        }
    }
}