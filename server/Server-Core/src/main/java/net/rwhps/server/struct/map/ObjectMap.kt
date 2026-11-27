/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.struct.map

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * @date  2023/6/14 20:36
 * @author Dr (dr@der.kim)
 */
class ObjectMap<K, V>: BaseMap<K, V> {
    @JvmOverloads
    constructor(threadSafety: Boolean = false): this(16, threadSafety)

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(capacity: Int, threadSafety: Boolean = false): super(
            if (threadSafety) {
                ConcurrentHashMap<K, V>(capacity)
            } else {
                Object2ObjectOpenHashMap<K, V>(capacity)
            } as java.util.Map<K, V>, threadSafety
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <K, V> of(vararg values: Any?): ObjectMap<K, V> {
            val map = ObjectMap<K, V>()
            for (i in 0 until (values.size / 2)) {
                map[(values[i * 2] as K)] = (values[i * 2 + 1] as V)
            }
            return map
        }
    }
}