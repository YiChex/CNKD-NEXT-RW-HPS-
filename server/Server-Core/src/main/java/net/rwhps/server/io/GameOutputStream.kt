/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import net.rwhps.server.io.output.AbstractByteArrayOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.output.DisableSyncByteArrayOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.util.IsUtils
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException


/**
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
open class GameOutputStream @JvmOverloads constructor(private var buffer: AbstractByteArrayOutputStream = DisableSyncByteArrayOutputStream()): Closeable {

    private var stream: DataOutputStream = DataOutputStream(buffer)

    fun createPacket(type: PacketType): Packet {
        try {
            stream.use {
                buffer.use {
                    stream.flush()
                    buffer.flush()
                    return Packet(type, buffer.toByteArray()!!)
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun getByteBuf(type: Int): ByteBuf {
        try {
            stream.use {
                buffer.use {
                    stream.flush()
                    buffer.flush()
                    val bytes = buffer.toByteArray()!!
                    val buf = ByteBufAllocator.DEFAULT.buffer(bytes.size + 8)
                    buf.writeInt(bytes.size)
                    buf.writeInt(type)
                    buf.writeBytes(bytes)
                    return buf
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun getByteArray(): ByteArray {
        try {
            stream.use {
                buffer.use {
                    stream.flush()
                    buffer.flush()
                    return buffer.toByteArray()!!
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun size(): Int {
        return this.buffer.size()
    }

    @Throws(IOException::class)
    fun writeByte(value: Byte): GameOutputStream {
        writeByte(value.toInt())
        return this
    }

    @Throws(IOException::class)
    fun writeByte(value: Int): GameOutputStream {
        stream.writeByte(value)
        return this
    }

    @Throws(IOException::class)
    fun writeBytes(value: ByteArray): GameOutputStream {
        buffer.writeBytes(value)
        return this
    }

    @Throws(IOException::class)
    fun writeBytesAndLength(value: ByteArray): GameOutputStream {
        writeInt(value.size)
        writeBytes(value)
        return this
    }

    @Throws(IOException::class)
    fun writeBoolean(value: Boolean): GameOutputStream {
        stream.writeBoolean(value)
        return this
    }

    @Throws(IOException::class)
    fun writeInt(value: Int): GameOutputStream {
        stream.writeInt(value)
        return this
    }

    @Throws(IOException::class)
    fun writeBackwardsInt(value: Int): GameOutputStream {
        stream.write(value ushr 0 and 0xFF)
        stream.write(value ushr 8 and 0xFF)
        stream.write(value ushr 16 and 0xFF)
        stream.write(value ushr 24 and 0xFF)
        return this
    }

    @Throws(IOException::class)
    fun writeIsInt(value: Int?): GameOutputStream {
        if (IsUtils.isBlank(value)) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeInt(value!!)
        }
        return this
    }

    @Throws(IOException::class)
    fun writeIsInt(gameInputStream: GameInputStream) {
        if (gameInputStream.readBoolean()) {
            writeBoolean(true)
            writeInt(gameInputStream.readInt())
        } else {
            writeBoolean(false)
        }
    }

    @Throws(IOException::class)
    fun writeShort(value: Short): GameOutputStream {
        stream.writeShort(value.toInt())
        return this
    }

    @Throws(IOException::class)
    fun writeBackwardsShort(value: Short): GameOutputStream {
        stream.write(value.toInt() ushr 0 and 0xFF)
        stream.write(value.toInt() ushr 8 and 0xFF)
        return this
    }

    @Throws(IOException::class)
    fun writeFloat(value: Int): GameOutputStream {
        stream.writeFloat(value.toFloat())
        return this
    }

    @Throws(IOException::class)
    fun writeFloat(value: Float): GameOutputStream {
        stream.writeFloat(value)
        return this
    }

    @Throws(IOException::class)
    fun writeDouble(value: Double): GameOutputStream {
        stream.writeDouble(value)
        return this
    }

    @Throws(IOException::class)
    fun writeLong(value: Long): GameOutputStream {
        stream.writeLong(value)
        return this
    }

    @Throws(IOException::class)
    fun writeChar(value: Char): GameOutputStream {
        stream.writeChar(value.code)
        return this
    }

    @Throws(IOException::class)
    fun writeString(value: String): GameOutputStream {
        stream.writeUTF(value)
        return this
    }

    @Throws(IOException::class)
    fun writeIsString(value: String?) {
        if (IsUtils.isBlank(value)) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeString(value!!)
        }
    }

    @Throws(IOException::class)
    fun writeIsString(gameInputStream: GameInputStream) {
        if (gameInputStream.readBoolean()) {
            writeBoolean(true)
            writeString(gameInputStream.readString())
        } else {
            writeBoolean(false)
        }
    }

    /*
    @Throws(IOException::class)
    fun writeGameObject(value: GameObject) {
        if (value == null) {
            stream.writeLong(-1L)
        } else {
            stream.writeLong(value.id)
        }
    }

    @Throws(IOException::class)
    fun writeUnit(value: Unit) {
        writeGameObject(value)
    }

    @Throws(IOException::class)
    fun writeOrderableUnit(value: OrderableUnit) {
        writeGameObject(value)
    }
    */

    @Throws(IOException::class)
    fun writeEnum(value: Enum<*>?) {
        if (value == null) {
            stream.writeInt(-1)
        } else {
            stream.writeInt(value.ordinal)
        }
    }

    @Throws(IOException::class)
    fun transferTo(inputStream: GameInputStream) {
        inputStream.transferTo(buffer)
    }

    @Throws(IOException::class)
    fun transferToFixedLength(inputStream: GameInputStream, length: Int) {
        inputStream.transferToFixedLength(buffer, length)
    }

    @Throws(IOException::class)
    fun flushEncodeData(enc: CompressOutputStream) {
        val bytes = enc.getByteArray()
        writeString(enc.head)
        writeInt(bytes.size)
        writeBytes(bytes)
    }

    @Throws(IOException::class)
    fun flushEncodeData(inputStream: GameInputStream) {
        writeString(inputStream.readString())
        val length = inputStream.readInt()
        writeInt(length)
        transferToFixedLength(inputStream, length)
    }

    @Throws(IOException::class)
    fun flushMapData(mapSize: Int, bytes: ByteArray) {
        writeInt(mapSize)
        stream.write(bytes)
    }

    fun reset() {
        this.buffer.reset()
    }

    override fun close() {
        stream.use {
            it.flush()
        }
        buffer.use {
            it.flush()
        }
    }

    companion object {
        @JvmStatic
        fun intToBytes(value: Int): ByteArray {
            return byteArrayOf(
                    (value ushr 24 and 0xFF).toByte(),
                    (value ushr 16 and 0xFF).toByte(),
                    (value ushr 8 and 0xFF).toByte(),
                    (value ushr 0 and 0xFF).toByte()
            )
        }
    }
}