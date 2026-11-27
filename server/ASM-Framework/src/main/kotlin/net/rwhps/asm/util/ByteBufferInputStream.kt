/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.asm.util

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * [InputStream] wrapping a [ByteBuffer]. Note that every read will
 * advance the buffer.
 */
// TODO: we should/could also read without advancing the buffer
class ByteBufferInputStream(private val buffer: ByteBuffer): InputStream() {
    @Throws(IOException::class)
    override fun read(): Int {
        return if (!buffer.hasRemaining()) {
            -1
        } else {
            buffer.get().toInt() and 0xFF
        }
    }

    @Throws(IOException::class)
    override fun read(bytes: ByteArray, off: Int, lenIn: Int): Int {
        var len = lenIn
        if (!buffer.hasRemaining()) {
            return -1
        }
        len = min(len, buffer.remaining())
        buffer[bytes, off, len]
        return len
    }

    override fun available(): Int {
        return buffer.remaining()
    }
}
