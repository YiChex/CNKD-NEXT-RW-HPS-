/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */


@file:JvmName("InlineUtils") @file:JvmMultifileClass

package net.rwhps.server.util.inline

import net.rwhps.server.struct.map.ObjectMap

/**
 * @date  2023/6/27 10:27
 * @author Dr (dr@der.kim)
 */

fun <K, V> mutableObjectMapOf(vararg pairs: Pair<K, V>): ObjectMap<K, V> = ObjectMap<K, V>(mapCapacity(pairs.size)).apply { putAll(pairs) }

private fun mapCapacity(expectedSize: Int): Int = when {
    // We are not coercing the value to a valid one and not throwing an exception. It is up to the caller to
    // properly handle negative values.
    expectedSize < 0 -> expectedSize
    expectedSize < 3 -> expectedSize + 1
    expectedSize < INT_MAX_POWER_OF_TWO -> ((expectedSize / 0.75F) + 1.0F).toInt()
    // any large value
    else -> Int.MAX_VALUE
}

private const val INT_MAX_POWER_OF_TWO: Int = 1 shl (Int.SIZE_BITS - 2)