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

import net.rwhps.server.util.algorithms.HexUtils


@JvmOverloads
fun Byte.toStringHex(toLowerCase: Boolean = false, format: Boolean = true): String {
    return byteArrayOf(this).toStringHex(toLowerCase, format)
}

@JvmOverloads
fun ByteArray.toStringHex(toLowerCase: Boolean = false, format: Boolean = true): String {
    HexUtils.encodeHexStr(this, toLowerCase).let {
        if (format) {
            return HexUtils.format(it)
        } else {
            return it
        }
    }
}

fun String.hexToByte(): Byte {
    return HexUtils.decodeHex(this)[0]
}

fun String.hexToBytes(): ByteArray {
    return HexUtils.decodeHex(this)
}