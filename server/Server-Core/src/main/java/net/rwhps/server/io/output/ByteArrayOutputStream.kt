/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.output

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 线程安全的版本
 * 继承 {@link AbstractByteArrayOutputStream}
 *
 * 创建新的字节数组输出流 缓冲容量为 {@value AbstractByteArrayOutputStream#DEFAULT_SIZE} 字节 尽管它的大小在必要时会增加
 * 默认为512bytes
 *
 * @author Dr (dr@der.kim)
 */
open class ByteArrayOutputStream @JvmOverloads constructor(size: Int = DEFAULT_SIZE): AbstractByteArrayOutputStream() {
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || off > b.size || len < 0 || off + len > b.size || off + len < 0) {
            throw IndexOutOfBoundsException()
        }
        if (len == 0) {
            return
        }
        synchronized(this) {
            writeImpl(b, off, len)
        }
    }

    @Synchronized
    override fun writeBytes(b: ByteArray) {
        write(b, 0, b.size)
    }

    @Synchronized
    override fun write(b: Int) {
        writeImpl(b)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun write(`in`: InputStream): Int {
        return writeImpl(`in`)
    }

    @Synchronized
    override fun size(): Int {
        return count
    }

    @Synchronized
    override fun reset() {
        resetImpl()
    }

    @Synchronized
    @Throws(IOException::class)
    override fun writeTo(out: OutputStream) {
        writeToImpl(out)
    }

    @Synchronized
    override fun toByteArray(): ByteArray {
        return toByteArrayImpl()
    }

    /**
     * 创建新的字节数组输出流 其缓冲区容量为指定大小（以字节为单位）。
     * @param size 初始大小
     * @throws IllegalArgumentException 大小是负数
     */
    init {
        require(size >= 0) {
            "Negative initial size: $size"
        }
        synchronized(this) {
            needNewBuffer(size)
        }
    }
}