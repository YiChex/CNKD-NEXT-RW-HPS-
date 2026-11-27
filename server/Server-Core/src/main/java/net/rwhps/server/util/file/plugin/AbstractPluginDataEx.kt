/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.plugin

import net.rwhps.server.util.file.plugin.value.DelegatedValue
import net.rwhps.server.util.file.plugin.value.Value
import net.rwhps.server.util.kotlin.ProvideDelegate

/**
 *
 *
 * @date 2024/2/15 12:29
 * @author Dr (dr@der.kim)
 */
interface AbstractPluginDataEx {
    fun <T> valueImpl(default: T): ProvideDelegate<T, DelegatedValue<T>>
}

/**
 * 创建一个 [Byte] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Byte): ProvideDelegate<Byte, DelegatedValue<Byte>> = valueImpl(default)

/**
 * 创建一个 [Short] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Short): ProvideDelegate<Short, DelegatedValue<Short>> = valueImpl(default)

/**
 * 创建一个 [Int] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Int): ProvideDelegate<Int, DelegatedValue<Int>> = valueImpl(default)

/**
 * 创建一个 [Long] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Long): ProvideDelegate<Long, DelegatedValue<Long>> = valueImpl(default)

/**
 * 创建一个 [Float] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Float): ProvideDelegate<Float, DelegatedValue<Float>> = valueImpl(default)

/**
 * 创建一个 [Double] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Double): ProvideDelegate<Double, DelegatedValue<Double>> = valueImpl(default)

/**
 * 创建一个 [Char] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Char): ProvideDelegate<Char, DelegatedValue<Char>> = valueImpl(default)

/**
 * 创建一个 [Boolean] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: Boolean): ProvideDelegate<Boolean, DelegatedValue<Boolean>> = valueImpl(default)

/**
 * 创建一个 [String] 类型的 [Value], 并设置初始值为 [default]
 */
fun AbstractSerializableData.value(default: String): ProvideDelegate<String, DelegatedValue<String>> = valueImpl(default)