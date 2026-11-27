/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.struct.map

import it.unimi.dsi.fastutil.objects.Object2IntMaps
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap

/**
 * @author Dr (dr@der.kim)
 */
class ObjectIntMap<K>: BaseMap<K, Int> {
    @JvmOverloads
    constructor(threadSafety: Boolean = false): this(16, threadSafety)

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(capacity: Int, threadSafety: Boolean = false): super(Object2IntOpenHashMap<K>(capacity).let {
        if (threadSafety) {
            Object2IntMaps.synchronize<K>(it, it)
        } else {
            it
        }
    } as java.util.Map<K, Int>, threadSafety)
}