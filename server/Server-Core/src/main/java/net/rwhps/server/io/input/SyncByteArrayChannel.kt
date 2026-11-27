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
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.*
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.min

/**
 * 线程不安全的字节通道
 *
 * @date 2023/6/15 8:33
 * @author jdk.nio.zipfs.ByteArrayChannel
 */
class SyncByteArrayChannel: SeekableByteChannel {
    private val rwlock: ReadWriteLock = ReentrantReadWriteLock()
    private var buf: ByteArray?

    /*
     * The current position of this channel.
     */
    private var pos: Int

    /*
     * The index that is one greater than the last valid byte in the channel.
     */
    private var last: Int
    private var closed = false
    private val readonly: Boolean

    /*
     * Creates a {@code ByteArrayChannel} with size {@code sz}.
     */
    constructor(sz: Int, readonly: Boolean) {
        buf = ByteArray(sz)
        last = 0
        pos = last
        this.readonly = readonly
    }

    /*
     * Creates a ByteArrayChannel with its 'pos' at 0 and its 'last' at buf's end.
     * Note: no defensive copy of the 'buf', used directly.
     */
    constructor(buf: ByteArray, readonly: Boolean) {
        this.buf = buf
        pos = 0
        last = buf.size
        this.readonly = readonly
    }

    override fun isOpen(): Boolean {
        return !closed
    }

    @Throws(IOException::class)
    override fun position(): Long {
        beginRead()
        return try {
            ensureOpen()
            pos.toLong()
        } finally {
            endRead()
        }
    }

    @Throws(IOException::class)
    override fun position(pos: Long): SeekableByteChannel {
        beginWrite()
        return try {
            ensureOpen()
            require(!(pos < 0 || pos >= Int.MAX_VALUE)) { "Illegal position $pos" }
            this.pos = min(pos.toInt(), last)
            this
        } finally {
            endWrite()
        }
    }

    @Throws(IOException::class)
    override fun read(dst: ByteBuffer): Int {
        beginWrite()
        return try {
            ensureOpen()
            if (pos == last) return -1
            val n = min(dst.remaining(), last - pos)
            dst.put(buf, pos, n)
            pos += n
            n
        } finally {
            endWrite()
        }
    }

    @Throws(IOException::class)
    override fun truncate(size: Long): SeekableByteChannel {
        if (readonly) throw NonWritableChannelException()
        ensureOpen()
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun write(src: ByteBuffer): Int {
        if (readonly) throw NonWritableChannelException()
        beginWrite()
        return try {
            ensureOpen()
            val n = src.remaining()
            ensureCapacity(pos + n)
            src[buf, pos, n]
            pos += n
            if (pos > last) {
                last = pos
            }
            n
        } finally {
            endWrite()
        }
    }

    @Throws(IOException::class)
    override fun size(): Long {
        beginRead()
        return try {
            ensureOpen()
            last.toLong()
        } finally {
            endRead()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) {
            return
        }
        beginWrite()
        try {
            closed = true
            buf = null
            pos = 0
            last = 0
        } finally {
            endWrite()
        }
    }

    /**
     * Creates a newly allocated byte array. Its size is the current
     * size of this channel and the valid contents of the buffer
     * have been copied into it.
     *
     * @return the current contents of this channel, as a byte array.
     */
    @Throws(IOException::class)
    fun toByteArray(): ByteArray {
        beginRead()
        ensureOpen()
        return try {
            // avoid copy if last == bytes.length?
            buf!!.copyOf(last)
        } finally {
            endRead()
        }
    }

    @Throws(IOException::class)
    private fun ensureOpen() {
        if (closed) {
            throw ClosedChannelException()
        }
    }

    private fun beginWrite() {
        rwlock.writeLock().lock()
    }

    private fun endWrite() {
        rwlock.writeLock().unlock()
    }

    private fun beginRead() {
        rwlock.readLock().lock()
    }

    private fun endRead() {
        rwlock.readLock().unlock()
    }

    private fun ensureCapacity(minCapacity: Int) {
        // overflow-conscious code
        if (minCapacity - buf!!.size > 0) {
            grow(minCapacity)
        }
    }

    /**
     * Increases the capacity to ensure that it can hold at least the
     * number of elements specified by the minimum capacity argument.
     *
     * @param minCapacity the desired minimum capacity
     */
    @Throws(IOException::class)
    private fun grow(minCapacity: Int) {
        ensureOpen()
        // overflow-conscious code
        val oldCapacity = buf!!.size
        var newCapacity = oldCapacity shl 1
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity
        }
        if (newCapacity - MAX_ARRAY_SIZE > 0) {
            newCapacity = hugeCapacity(minCapacity)
        }
        buf = buf!!.copyOf(newCapacity)
    }

    companion object {
        /**
         * The maximum size of array to allocate.
         * Some VMs reserve some header words in an array.
         * Attempts to allocate larger arrays may result in
         * OutOfMemoryError: Requested array size exceeds VM limit
         */
        private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8

        private fun hugeCapacity(minCapacity: Int): Int {
            // overflow
            if (minCapacity < 0) {
                throw OutOfMemoryError("Required length exceeds implementation limit")
            }
            return if (minCapacity > MAX_ARRAY_SIZE) {
                Int.MAX_VALUE
            } else {
                MAX_ARRAY_SIZE
            }
        }
    }
}
