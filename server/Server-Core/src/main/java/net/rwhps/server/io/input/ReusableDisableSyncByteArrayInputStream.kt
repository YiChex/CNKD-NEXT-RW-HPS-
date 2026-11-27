/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.input

import kotlin.math.min

/**
 * @author Dr (dr@der.kim)
 */
open class ReusableDisableSyncByteArrayInputStream: DisableSyncByteArrayInputStream(ByteArray(0)) {
    fun position(): Int {
        return pos
    }

    fun setBytes(bytes: ByteArray) {
        pos = 0
        count = bytes.size
        mark = 0
        buf = bytes
    }

    fun setBytes(bytes: ByteArray, offset: Int, length: Int) {
        buf = bytes
        pos = offset
        count = min(offset + length, bytes.size)
        mark = offset
    }
}