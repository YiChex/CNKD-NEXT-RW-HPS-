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
 * @date  2023/6/11 20:27
 * @author Dr (dr@der.kim)
 */
data class KeyValue<K, V>(
    val key: K, val value: V
)

// “作为客人去批评主人总是不礼貌的 作为用户去批评开发者总是愚蠢的”
