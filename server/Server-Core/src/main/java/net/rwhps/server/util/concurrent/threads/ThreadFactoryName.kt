/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.concurrent.threads

import net.rwhps.server.util.log.Log
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Dr (dr@der.kim)
 */
object ThreadFactoryName {
    /**
     * Custom Thread Factory
     * @return java.util.concurrent.ThreadFactory
     */
    @JvmStatic
    fun nameThreadFactory(name: String): ThreadFactory {
        val tag = AtomicInteger(1)
        return ThreadFactory { r: Runnable ->
            val thread = Thread(r)
            thread.name = name + tag.getAndIncrement()
            thread.uncaughtExceptionHandler = Log.errorDispose
            thread
        }
    }
}