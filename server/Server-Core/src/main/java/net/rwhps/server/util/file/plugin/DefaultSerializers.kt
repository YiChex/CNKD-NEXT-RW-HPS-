/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.plugin

import net.rwhps.server.util.log.Log

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.net.Administration.PlayerAdminInfo
import net.rwhps.server.net.Administration.PlayerInfo
import net.rwhps.server.struct.SerializerTypeAll.TypeSerializer
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.algorithms.NetConnectProofOfWork
import net.rwhps.server.util.file.plugin.serializer.MachineFriendlySerializers
import java.io.IOException

/**
 * @author Dr (dr@der.kim)
 */
internal object DefaultSerializers {
    fun register() {
        registrationBasis()
        registerMap()
        registerCustomClass()
    }

    private fun registrationBasis() {
        MachineFriendlySerializers.setSerializer(ByteArray::class.java, object: TypeSerializer<ByteArray> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: ByteArray) {
                paramDataOutput.writeBytesAndLength(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): ByteArray {
                return paramDataInput.readStreamBytes()
            }
        })
        MachineFriendlySerializers.setSerializer(Short::class.javaPrimitiveType!!, object: TypeSerializer<Short> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Short) {
                paramDataOutput.writeShort(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Short {
                return paramDataInput.readShort()
            }
        })
        MachineFriendlySerializers.setSerializer(Int::class.java, object: TypeSerializer<Int> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Int) {
                paramDataOutput.writeInt(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Int {
                return paramDataInput.readInt()
            }
        })
        MachineFriendlySerializers.setSerializer(Long::class.javaPrimitiveType!!, object: TypeSerializer<Long> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Long) {
                paramDataOutput.writeLong(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Long {
                return paramDataInput.readLong()
            }
        })
        MachineFriendlySerializers.setSerializer(Float::class.javaPrimitiveType!!, object: TypeSerializer<Float> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Float) {
                paramDataOutput.writeFloat(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Float {
                return paramDataInput.readFloat()
            }
        })
        MachineFriendlySerializers.setSerializer(Double::class.javaPrimitiveType!!, object: TypeSerializer<Double> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Double) {
                paramDataOutput.writeDouble(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Double {
                return paramDataInput.readDouble()
            }
        })
        MachineFriendlySerializers.setSerializer(Char::class.javaPrimitiveType!!, object: TypeSerializer<Char> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Char) {
                paramDataOutput.writeChar(objectParam)
            }

            @Throws(IOException::class)
                override fun read(paramDataInput: GameInputStream): Char {
                return paramDataInput.readChar()
            }
        })
        MachineFriendlySerializers.setSerializer(Boolean::class.javaPrimitiveType!!, object: TypeSerializer<Boolean> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Boolean) {
                paramDataOutput.writeBoolean(objectParam)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Boolean {
                return paramDataInput.readBoolean()
            }
        })
        MachineFriendlySerializers.setSerializer(String::class.java, object: TypeSerializer<String?> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: String?) {
                paramDataOutput.writeString(objectParam ?: "")
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): String {
                return paramDataInput.readString()
            }
        })
    }

    private fun registerMap() {
        MachineFriendlySerializers.setSerializer(Seq::class.java, Seq.serializer)
        MachineFriendlySerializers.setSerializer(ObjectMap::class.java, object: TypeSerializer<ObjectMap<*, *>> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: ObjectMap<*, *>) {
                paramDataOutput.writeInt(objectParam.size)
                if (objectParam.size == 0) {
                    return
                }
                val entry = objectParam.entries.iterator().next()
                val key = entry.key!!
                val value = entry.value!!
                val keySer = MachineFriendlySerializers.getSerializer(key.javaClass) ?: throw getError(key.javaClass.toString())
                val valSer = MachineFriendlySerializers.getSerializer(value.javaClass) ?: throw getError(value.javaClass.toString())
                paramDataOutput.writeString(key.javaClass.name)
                paramDataOutput.writeString(value.javaClass.name)
                for (e in objectParam.entries) {
                    keySer.write(paramDataOutput, e.key)
                    valSer.write(paramDataOutput, e.value)
                }
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): ObjectMap<*, *> {
                val map = ObjectMap<Any?, Any?>()
                try {
                    val size = paramDataInput.readInt()
                    if (size == 0) {
                        return map
                    }
                    val keySerName = paramDataInput.readString()
                    val valSerName = paramDataInput.readString()
                    val keySer = MachineFriendlySerializers.getSerializer(lookup(keySerName)) ?: throw getError(keySerName)
                    val valSer = MachineFriendlySerializers.getSerializer(lookup(valSerName)) ?: throw getError(valSerName)
                    for (i in 0 until size) {
                        val key = keySer.read(paramDataInput)
                        val `val` = valSer.read(paramDataInput)
                        map[key] = `val`
                    }
                    return map
                } catch (e: ClassNotFoundException) {
                    Log.error("[D-30] Unexpected exception", e)
                }
                return map
            }
        })
        MachineFriendlySerializers.setSerializer(OrderedMap::class.java, object: TypeSerializer<OrderedMap<*, *>> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: OrderedMap<*, *>) {
                paramDataOutput.writeInt(objectParam.size)
                if (objectParam.size == 0) {
                    return
                }
                val entry = objectParam.entries.iterator().next()
                val key = entry.key!!
                val value = entry.value!!
                val keySer = MachineFriendlySerializers.getSerializer(key.javaClass) ?: throw getError(key.javaClass.toString())
                val valSer = MachineFriendlySerializers.getSerializer(value.javaClass) ?: throw getError(value.javaClass.toString())
                paramDataOutput.writeString(key.javaClass.name)
                paramDataOutput.writeString(value.javaClass.name)
                for (e in objectParam.entries) {
                    keySer.write(paramDataOutput, e.key)
                    valSer.write(paramDataOutput, e.value)
                }
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): OrderedMap<*, *> {
                val map = OrderedMap<Any?, Any?>()
                try {
                    val size = paramDataInput.readInt()
                    if (size == 0) {
                        return map
                    }
                    val keySerName = paramDataInput.readString()
                    val valSerName = paramDataInput.readString()
                    val keySer = MachineFriendlySerializers.getSerializer(lookup(keySerName)) ?: throw getError(keySerName)
                    val valSer = MachineFriendlySerializers.getSerializer(lookup(valSerName)) ?: throw getError(valSerName)
                    for (i in 0 until size) {
                        val key = keySer.read(paramDataInput)
                        val `val` = valSer.read(paramDataInput)
                        map[key] = `val`
                    }
                    return map
                } catch (e: ClassNotFoundException) {
                    Log.error("[D-30] Unexpected exception", e)
                }
                return map
            }
        })
    }

    private fun registerCustomClass() {
        MachineFriendlySerializers.setSerializer(PlayerInfo::class.java, object: TypeSerializer<PlayerInfo> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: PlayerInfo) {
                MachineFriendlySerializers.getSerializer(String::class.java)!!.write(paramDataOutput, objectParam.uuid)
                paramDataOutput.writeLong(objectParam.timesKicked)
                paramDataOutput.writeLong(objectParam.timesJoined)
                paramDataOutput.writeLong(objectParam.timeMute)
                paramDataOutput.writeBoolean(objectParam.admin)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): PlayerInfo {
                val objectParam = PlayerInfo(MachineFriendlySerializers.getSerializer(String::class.java)!!.read(paramDataInput) as String)
                objectParam.timesKicked = paramDataInput.readLong()
                objectParam.timesJoined = paramDataInput.readLong()
                objectParam.timeMute = paramDataInput.readLong()
                objectParam.admin = paramDataInput.readBoolean()
                return objectParam
            }
        })

        MachineFriendlySerializers.setSerializer(PlayerAdminInfo::class.java, object: TypeSerializer<PlayerAdminInfo> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: PlayerAdminInfo) {
                paramDataOutput.writeString(objectParam.uuid)
                paramDataOutput.writeBoolean(objectParam.admin)
                paramDataOutput.writeBoolean(objectParam.superAdmin)
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): PlayerAdminInfo {
                return PlayerAdminInfo(paramDataInput.readString(), paramDataInput.readBoolean(), paramDataInput.readBoolean())
            }
        })

        MachineFriendlySerializers.setSerializer(Packet::class.java, Packet.serializer)
        MachineFriendlySerializers.setSerializer(NetConnectProofOfWork::class.java, NetConnectProofOfWork.serializer)
    }

    @Throws(ClassNotFoundException::class)
    fun lookup(name: String): Class<*> {
        return Class.forName(name)
    }

    fun getError(className: String): IllegalArgumentException {
        return IllegalArgumentException("$className does not have a serializer registered!")
    }
}