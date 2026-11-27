/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.plugin.serializer

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.input.ReusableDisableSyncByteArrayInputStream
import net.rwhps.server.io.output.ByteArrayOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.struct.SerializerTypeAll
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.compression.CompressionAlgorithm
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.compression.decoder.GzipDecoder
import net.rwhps.server.util.file.plugin.DefaultSerializers
import net.rwhps.server.util.file.plugin.value.SerializableValue
import net.rwhps.server.util.file.plugin.value.Value
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.exp.CompressionException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 *
 *
 * @date 2024/2/15 12:52
 * @author Dr (dr@der.kim)
 */
class MachineFriendlySerializers : AbstractSerializers {
    private val byteInputStream = ReusableDisableSyncByteArrayInputStream()
    private val byteStream = ByteArrayOutputStream()
    private val dataOutput = GameOutputStream(byteStream)

    var code: CompressionAlgorithm = CompressionAlgorithm.Gzip

    override fun checkContainsSerializers(className: Class<*>): Boolean {
        return SERIALIZERS.containsKey(className)
    }

    override fun read(pluginData: OrderedMap<String, Value<*>>, inStream: InputStream) {
        val gameInputStream: GameInputStream = when (code) {
            CompressionAlgorithm.Gzip -> GameInputStream(GzipDecoder.getGzipInputStream(inStream))
            CompressionAlgorithm.Zip -> GameInputStream(CompressionDecoderUtils.zipAllReadStream(inStream).getZipAllBytes()["file"]!!)
            CompressionAlgorithm.SevenZip -> GameInputStream(CompressionDecoderUtils.sevenAllReadStream(inStream).getZipAllBytes()["file"]!!)
            else -> throw CompressionException.CryptographicException(code.name)
        }

        gameInputStream.use { stream ->
            val amount = stream.readInt()
            for (i in 0 until amount) {
                var length: Int
                val key = stream.readString()
                when (val type = stream.readByte()) {
                    0 -> pluginData[key] = SerializableValue(stream.readBoolean())
                    1 -> pluginData[key] = SerializableValue(stream.readInt())
                    2 -> pluginData[key] = SerializableValue(stream.readLong())
                    3 -> pluginData[key] = SerializableValue(stream.readFloat())
                    4 -> pluginData[key] = SerializableValue(stream.readString())
                    5 -> {
                        /* 把String转为Class,来进行反序列化 */
                        val classCache: Class<*> = Class.forName(stream.readString())
                        length = stream.readInt()
                        val bytes = stream.readNBytes(length)
                        pluginData[key] = SerializableValue(getObject(classCache, bytes))
                    }

                    else -> throw IllegalStateException("Unexpected value: $type")
                }
            }
        }
    }

    override fun save(pluginData: OrderedMap<String, Value<*>>, outputStream: OutputStream) {
        val gameOutputStream: GameOutputStream = when (code) {
            CompressionAlgorithm.Gzip -> CompressOutputStream.getGzipOutputStream("", true)
            CompressionAlgorithm.Zip -> CompressOutputStream.getZipOutputStream("", true)
            CompressionAlgorithm.SevenZip -> CompressOutputStream.get7zOutputStream("", true)
            else -> throw CompressionException.CryptographicException(code.name)
        }

        gameOutputStream.use { stream ->
            stream.writeInt(pluginData.size)
            for (entry in pluginData) {
                stream.writeString(entry.key)
                when (val value = entry.value.value!!) {
                    is Boolean -> {
                        stream.writeByte(0)
                        stream.writeBoolean(value)
                    }
                    is Int -> {
                        stream.writeByte(1)
                        stream.writeInt(value)
                    }
                    is Long -> {
                        stream.writeByte(2)
                        stream.writeLong(value)
                    }
                    is Float -> {
                        stream.writeByte(3)
                        stream.writeFloat(value)
                    }
                    is String -> {
                        stream.writeByte(4)
                        stream.writeString(value)
                    }
                    else -> {
                        try {
                            val bytes = putBytes(value)
                            stream.writeByte(5)
                            /* 去除ToString后的前缀(class com.xxx~) */
                            stream.writeString(value.javaClass.toString().replace("class ", ""))
                            stream.writeInt(bytes.size)
                            stream.writeBytes(bytes)
                        } catch (e: IOException) {
                            Log.error("Save Error", e)
                        }
                    }
                }
            }
        }
        outputStream.write(gameOutputStream.getByteArray())
        outputStream.flush()

    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getObject(type: Class<T>, bytes: ByteArray): T? {
        require(checkContainsSerializers(type)) {
            "Type $type does not have a serializer registered!"
        }
        val serializer = SERIALIZERS[type]!!
        return try {
            byteInputStream.setBytes(bytes)
            val obj = serializer.read(GameInputStream(byteInputStream)) ?: return null
            obj as T
        } catch (e: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    private fun putBytes(value: Any, type: Class<*> = value.javaClass): ByteArray {
        require(checkContainsSerializers(type)) {
            "Type $type does not have a serializer registered!"
        }
        byteStream.reset()
        val serializer: SerializerTypeAll.TypeSerializer<Any?> = SERIALIZERS[type] as SerializerTypeAll.TypeSerializer<in Any?>
        serializer.write(dataOutput, value)
        return byteStream.toByteArray()
    }

    companion object {
        private val SERIALIZERS = ObjectMap<Class<*>, SerializerTypeAll.TypeSerializer<Any?>>()

        init {
            DefaultSerializers.register()
        }

        internal fun getSerializer(type: Class<*>): SerializerTypeAll.TypeSerializer<Any?>? {
            return SERIALIZERS[type]
        }

        @Suppress("UNCHECKED_CAST")
        internal fun <T> setSerializer(type: Class<*>, ser: SerializerTypeAll.TypeSerializer<T>) {
            SERIALIZERS[type] = (ser as SerializerTypeAll.TypeSerializer<Any?>)
        }
    }
}