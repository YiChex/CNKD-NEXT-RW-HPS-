/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.struct.map

import net.rwhps.server.func.*
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.ExtractUtils

/**
 * 主要方法(复用)
 *
 * @param K
 * @param V
 * @property map Map<K, V>
 * @property size Int
 * @property entries MutableSet<MutableEntry<K, V>>
 * @property keys MutableSet<K>
 * @property values MutableCollection<V>
 * @constructor
 *
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
abstract class BaseMap<K, V>(private val map: java.util.Map<K, V>, private val threadSafety: Boolean): MutableMap<K, V>, Map<K, V> {
    override val size: Int get() = map.size()
    override fun isEmpty(): Boolean = map.isEmpty

    override fun get(key: K): V? = map[key]
    operator fun get(key: K, defaultValue: Prov<V>): V {
        val value = map[key] ?: defaultValue.get()
        put(key, value)
        return value
    }

    operator fun get(key: K, defaultValue: V): V {
        val value = map[key] ?: defaultValue
        put(key, value)
        return value
    }

    override fun getOrDefault(key: K, defaultValue: V): V = map.getOrDefault(key, defaultValue)

    override fun put(key: K, value: V): V? = map.put(key, value)
    override fun putAll(from: Map<out K, V>) = map.putAll(from)

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entrySet()
    override val keys: MutableSet<K> get() = map.keySet()
    override val values: MutableCollection<V> get() = map.values()

    override fun remove(key: K): V? = map.remove(key)
    override fun containsValue(value: V): Boolean = map.containsValue(value)
    override fun containsKey(key: K): Boolean = map.containsKey(key)

    fun find(findA: FindMapKV<K, V, Boolean>): KeyValue<K, V>? {
        var result: KeyValue<K, V>? = null
        ExtractUtils.synchronizedX(threadSafety, map) {
            map.forEach { k, v ->
                // D-13: keep the FIRST match, consistent with BaseSeq.find (which returns
                // on its first hit). Previously the loop kept overwriting `result`, so
                // this returned the LAST match while the list variant returned the first.
                if (result == null && findA(k, v)) {
                    result = KeyValue(k, v)
                }
            }
        }
        return result
    }

    fun find(findA: FindMapKV<K, V, Boolean>, findB: FindMapKV<K, V, Boolean>): KeyValue<K, V>? {
        var result: KeyValue<K, V>? = null
        ExtractUtils.synchronizedX(threadSafety, map) {
            map.forEach { k, v ->
                // D-13: first match wins, see above.
                if (result == null && findA(k, v) && findB(k, v)) {
                    result = KeyValue(k, v)
                }
            }
        }
        return result
    }

    fun eachAll(block: ConsMap<K, V>) {
        ExtractUtils.synchronizedX(threadSafety, map) {
            map.forEach { k, v -> block(k, v) }
        }
    }

    fun eachControl(findA: FindMapKV<K, V, Control.ControlFind>) {
        eachControl { entry ->
            findA(entry.key, entry.value)
        }
    }

    fun eachControl(findA: FindMapE<Map.Entry<K, V>, Control.ControlFind>) {
        ExtractUtils.synchronizedX(threadSafety, map) {
            map.entrySet().forEach {
                if (findA(it) == Control.ControlFind.BREAK) {
                    return@synchronizedX
                }
            }
        }
    }

    fun eachAllFind(find: FindMapKV<K, V, Boolean>, block: ConsMap<K, V>) = eachAll { k, v ->
        if (find(k, v)) {
            block(k, v)
        }
    }

    fun eachFind(find: FindMapKV<K, V, Boolean>, block: ConsMap<K, V>) = find(find)?.let { block(it.key, it.value) }
    fun eachAllFinds(findA: FindMapKV<K, V, Boolean>, findB: FindMapKV<K, V, Boolean>, block: ConsMap<K, V>) = find(
            findA, findB
    )?.let { block(it.key, it.value) }

    override fun clear() = map.clear()

    fun toArrayKey(): Seq<K> = keys.toSeq()
    fun toArrayValues(): Seq<V> = values.toSeq()


    override fun toString(): String = map.toString()

    companion object {
        fun <K> MutableSet<K>.toSeq(): Seq<K> {
            val set = this
            return Seq<K>().apply {
                set.forEach {
                    add(it)
                }
            }
        }

        fun <V> MutableCollection<V>.toSeq(): Seq<V> {
            val set = this
            return Seq<V>().apply {
                set.forEach {
                    add(it)
                }
            }
        }
    }
}
/**
 * Pain will come with the blade
 * Pain will wake up the despondent crowd in this dormant world somehow
 */