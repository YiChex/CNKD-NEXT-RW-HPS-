/*
 *
 *  * Copyright 2020-2024 RW-HPS Team and contributors.
 *  *
 *  * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *  *
 *  * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 *  
 */

package net.rwhps.server.util.concurrent.lock

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 通过 Kotlin 的委托, 来完成 Get/Set 的同步方案
 *
 * 注意 :
 * 这个同步为重锁, 不适用高并发
 *
 * @param T
 * @property get Get时返回的数据
 * @property set Set时需要设置的数据
 * @property waitSyncValue 值
 * @constructor
 *
 * @date  2023/6/23 8:29
 * @author Dr (dr@der.kim)
 */
class Synchronize<T>(
    defaultValue: T, private val get: (T) -> T = { it }, private val set: (T) -> T = { it }
): ReadWriteProperty<Any, T> {
    private var waitSyncValue = defaultValue

    override fun getValue(thisRef: Any, property: KProperty<*>): T {
        return synchronized(this) {
            get(waitSyncValue)
        }
    }

    override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
        synchronized(this) {
            waitSyncValue = set(value)
        }
    }
}