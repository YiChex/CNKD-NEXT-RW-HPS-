/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util.internal.net.rudp

import java.io.IOException
import java.io.OutputStream
import kotlin.math.min

/**
 * This class extends OutputStream to implement a ReliableSocketOutputStream.
 * Note that this class should **NOT** be public.
 *
 * Creates a new ReliableSocketOutputStream.
 * This method can only be called by a ReliableSocket.
 *
 * @param sock    the actual Reliable UDP socket to writes bytes on.
 * @throws IOException if an I/O error occurs.
 *
 * @author Adrian Granados
 * @author Dr (dr@der.kim)
 */
internal open class ReliableSocketOutputStream(sock: ReliableSocket?): OutputStream() {
    @Synchronized
    @Throws(IOException::class)
    override fun write(b: Int) {
        if (count >= bytes.size) {
            flush()
        }
        bytes[count++] = (b and 0xFF).toByte()
    }

    @Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException()
        }
        var buflen: Int
        var writtenBytes = 0
        while (writtenBytes < len) {
            buflen = min(bytes.size, len - writtenBytes)
            if (buflen > bytes.size - count) {
                flush()
            }
            System.arraycopy(b, off + writtenBytes, bytes, count, buflen)
            count += buflen
            writtenBytes += buflen
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun flush() {
        if (count > 0) {
            reliableSocket.write(bytes, 0, count)
            count = 0
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        flush()
        reliableSocket.shutdownOutput()
    }

    private var reliableSocket: ReliableSocket
    protected var bytes: ByteArray
    protected var count: Int

    init {
        if (sock == null) {
            throw NullPointerException("sock")
        }
        reliableSocket = sock
        bytes = ByteArray(reliableSocket.sendBufferSize)
        count = 0
    }
}
