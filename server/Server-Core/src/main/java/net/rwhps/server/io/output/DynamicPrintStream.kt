/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.output

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * @author Dr (dr@der.kim)
 */
internal class DynamicPrintStream(private val block: (String) -> Unit): PrintStream(ByteArrayOutputStream()) {
    private val bufOut = out as ByteArrayOutputStream

    private var last = -1

    override fun write(byteIn: Int) {
        if (last == 13 && byteIn == 10) {// \r\n
            last = -1
            return
        }
        last = byteIn
        if (byteIn == 13 || byteIn == 10) {
            flush()
        } else {
            super.write(byteIn)
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (len < 0) {
            throw ArrayIndexOutOfBoundsException(len)
        }
        for (i in 0 until len) {
            write(buf[off + i].toInt())
        }
    }

    @Synchronized
    override fun flush() {
        val str = try {
            bufOut.toString()
        } finally {
            bufOut.reset()
        }
        block(str)
    }
}