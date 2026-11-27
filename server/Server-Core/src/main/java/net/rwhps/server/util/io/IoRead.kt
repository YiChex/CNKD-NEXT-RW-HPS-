/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.io

import net.rwhps.server.io.output.DisableSyncByteArrayOutputStream
import java.io.*

/**
 * @author Dr (dr@der.kim)
 */
object IoRead {
    /** 在复制方法中使用的默认缓冲区大小  */
    const val DEFAULT_BUFFER_SIZE = 8192

    /**
     * 将文件内容读入字节数组。流总是被关闭。
     *
     * @param file 不能读取要读取的文件 `null`
     * @return 文件内容，从不 `null`
     * @throws NullPointerException 如果文件是 `null`.
     * @throws FileNotFoundException 如果文件不存在，是一个目录而不是一个常规文件，或者由于其他原因无法打开进行读取。
     * @throws IOException if an I/O error occurs.
     * @since 1.1
     */
    @JvmStatic
    @Throws(IOException::class)
    fun readFileToByteArray(file: File): ByteArray {
        IoReadConversion.fileToStream(file).use { inputStream ->
            val fileLength = file.length()
            // file.length（）对于依赖于系统的实体可能返回0，将0视为未知长度-请参阅IO-453
            return if (fileLength > 0) readInputStreamBytes(inputStream, fileLength) else readInputStreamBytes(inputStream)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStreamBytes(inputStream: InputStream, size: Long): ByteArray {
        val bytes = readInputStreamBytes(inputStream)
        if (bytes.size.toLong() != size) {
            throw IOException("Unexpected read size, current: " + bytes.size + ", expected: " + size)
        }
        return bytes
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStreamBytes(inputStream: InputStream): ByteArray {
        inputStream.use {
            DisableSyncByteArrayOutputStream().use { byteArrayOutputStream ->
                copyLarge(inputStream, byteArrayOutputStream, ByteArray(DEFAULT_BUFFER_SIZE))
                return byteArrayOutputStream.toByteArray()
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyInputStream(inputStream: InputStream, outputStream: OutputStream, back: (Int) -> Unit = {}): Int {
        return copyLarge(inputStream, outputStream, ByteArray(DEFAULT_BUFFER_SIZE), back)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyLarge(inputStream: InputStream, outputStream: OutputStream, buffer: ByteArray, back: (Int) -> Unit = {}): Int {
        var len = 0
        var n: Int
        while (IOUtils.EOF != inputStream.read(buffer).also { n = it }) {
            outputStream.write(buffer, 0, n)
            len += n
            back(len)
        }
        return len
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStream(inputStream: InputStream, back: (ByteArray, Int) -> Unit = { _,_ -> }): Int {
        return readLarge(inputStream, ByteArray(DEFAULT_BUFFER_SIZE), back)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readLarge(inputStream: InputStream, buffer: ByteArray, back: (ByteArray, Int) -> Unit = { _,_ -> }): Int {
        var len = 0
        var n: Int
        while (IOUtils.EOF != inputStream.read(buffer).also { n = it }) {
            back(buffer, n)
            len += n
        }
        return len
    }

    class MultiplexingReadStream @JvmOverloads constructor(byteSize: Int = 16384): Closeable {
        private val BYTE_BUFFER_SIZE: ByteArray
        private val byteArrayOutputStream: DisableSyncByteArrayOutputStream

        init {/* 使用的默认缓冲区大小 */
            BYTE_BUFFER_SIZE = ByteArray(byteSize)
            byteArrayOutputStream = DisableSyncByteArrayOutputStream(byteSize)
        }

        @Throws(IOException::class)
        fun readInputStreamBytes(inputStream: InputStream): ByteArray {
            return try {
                copyLarge(inputStream, byteArrayOutputStream, BYTE_BUFFER_SIZE)
                byteArrayOutputStream.toByteArray()
            } finally {
                byteArrayOutputStream.reset()
            }
        }

        @Throws(IOException::class)
        override fun close() {
            byteArrayOutputStream.close()
        }
    }
}