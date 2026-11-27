/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.input

import java.io.IOException
import java.io.OutputStream

/**
 * @author Dr (dr@der.kim)
 */
open class ByteArrayInputStream: DisableSyncByteArrayInputStream {

    constructor(buf: ByteArray): super(buf)

    constructor(buf: ByteArray, offset: Int, length: Int): super(buf, offset, length)

    @Synchronized
    override fun read(): Int {
        return super.read()
    }

    @Synchronized
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return super.read(b, off, len)
    }

    @Synchronized
    override fun readAllBytes(): ByteArray {
        return super.readAllBytes()
    }

    @Synchronized
    override fun readNBytes(len: Int): ByteArray {
        return super.readNBytes(len)
    }

    @Synchronized
    override fun readNBytes(b: ByteArray, off: Int, len: Int): Int {
        return super.readNBytes(b, off, len)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun transferTo(out: OutputStream): Long {
        return super.transferTo(out)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun transferToFixedLength(out: OutputStream, len: Int): Long {
        return super.transferToFixedLength(out, len)
    }

    @Synchronized
    override fun skip(n: Long): Long {
        return super.skip(n)
    }

    @Synchronized
    override fun available(): Int {
        return super.available()
    }

    @Synchronized
    override fun markSupported(): Boolean {
        return super.markSupported()

    }

    @Synchronized
    override fun mark(readAheadLimit: Int) {
        super.mark(readAheadLimit)
    }

    @Synchronized
    override fun reset() {
        super.reset()
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        super.close()
    }
}