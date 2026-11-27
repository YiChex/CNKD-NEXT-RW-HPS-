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

inline fun <T> T?.ifNull(blockNotNull: (T) -> Unit, block: () -> Unit = {}): T? {
    if (this == null) {
        block()
    } else {
        blockNotNull(this)
    }
    return this
}

inline fun <R, T> T?.ifNullResult(block: () -> R, blockNotNull: (T) -> R): R {
    return if (this == null) {
        block()
    } else {
        blockNotNull(this)
    }
}

inline fun <R> String?.ifEmptyResult(block: () -> R, blockNotNull: (String) -> R): R {
    return if (this.isNullOrEmpty()) {
        block()
    } else {
        blockNotNull(this)
    }
}

inline fun <R, T> T?.ifNullResult(result: R, blockNotNull: (T) -> R): R {
    return if (this == null) {
        result
    } else {
        blockNotNull(this)
    }
}

inline fun <R, T> T.ifResult(find: (T) -> Boolean, blockNotNull: (T) -> R, block: () -> R): R {
    return if (find(this)) {
        blockNotNull(this)
    } else {
        block()
    }
}