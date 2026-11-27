/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.struct.list

import net.rwhps.server.util.log.Log

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import it.unimi.dsi.fastutil.objects.ObjectLists
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.struct.SerializerTypeAll
import net.rwhps.server.util.ExtractUtils
import net.rwhps.server.util.file.plugin.AbstractSerializableData
import net.rwhps.server.util.file.plugin.DefaultSerializers
import net.rwhps.server.util.file.plugin.serializer.MachineFriendlySerializers
import java.io.IOException
import java.util.List

/**
 * 可调整大小，有序的对象数组
 *
 * 别骂了别骂了 找不到什么好方法
 * 获取同步后得到的就是 ObjectList 只能这样分着写
 * 我不想每次判断
 *
 * @param T Type
 *
 * @author Dr (dr@der.kim)
 */
class Seq<T>: BaseSeq<T> {
    @Suppress("UNCHECKED_CAST")
    private val listFastUtil = listObject as ObjectArrayList<T>

    @JvmOverloads
    constructor(threadSafety: Boolean = false): this(16, threadSafety)

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(capacity: Int, threadSafety: Boolean = false): super(
            ObjectArrayList<T>(capacity) as List<T>,
            { i -> ObjectLists.synchronize<T>(i as ObjectArrayList<T>,i) as List<T> },
            threadSafety
            )

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(array: Array<T>, threadSafety: Boolean = false): super(
            ObjectArrayList<T>(array) as List<T>,
            { i -> ObjectLists.synchronize<T>(i as ObjectArrayList<T>,i) as List<T> },
            threadSafety
    )

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(array: Array<T>, length: Int, threadSafety: Boolean = false): super(
            ObjectArrayList<T>(array, 0, length) as List<T>,
            { i -> ObjectLists.synchronize<T>(i as ObjectArrayList<T>,i) as List<T> },
            threadSafety
    )

    override fun elements(): Array<T> = listFastUtil.elements()

    @Suppress("UNCHECKED_CAST")
    override fun <E> toArray(classJava: Class<E>): Array<E> = listFastUtil.toArray(java.lang.reflect.Array.newInstance(classJava, size) as Array<E>) as Array<E>

    override fun clone(): Seq<T> = Seq(listFastUtil.elements(), size, threadSafety)

    fun drain(): Seq<T> {
        val drained = Seq<T>(size)
        ExtractUtils.synchronizedX(threadSafety, listObject) {
            drained.addAll(listObject)
            listObject.clear()
        }
        return drained
    }

    companion object {
        /*
         * serializer
         */
        val serializer = object: SerializerTypeAll.TypeSerializer<Seq<*>> {
            @Throws(IOException::class)
            override fun write(paramDataOutput: GameOutputStream, objectParam: Seq<*>) {
                paramDataOutput.writeInt(objectParam.size)
                if (objectParam.size != 0) {
                    val first = objectParam.first()!!
                    val ser = MachineFriendlySerializers.getSerializer(first.javaClass) ?: throw DefaultSerializers.getError(
                            first.javaClass.toString()
                    )
                    paramDataOutput.writeString(first.javaClass.name)
                    for (element in objectParam) {
                        ser.write(paramDataOutput, element)
                    }
                }
            }

            @Throws(IOException::class)
            override fun read(paramDataInput: GameInputStream): Seq<*> {
                val arr = Seq<Any?>()
                try {
                    val size = paramDataInput.readInt()
                    if (size == 0) {
                        return arr
                    }
                    val type = paramDataInput.readString()
                    val ser = MachineFriendlySerializers.getSerializer(DefaultSerializers.lookup(type)) ?: throw DefaultSerializers.getError(type)

                    for (i in 0 until size) {
                        arr.add(ser.read(paramDataInput))
                    }
                    return arr
                } catch (e: ClassNotFoundException) {
                    Log.error("[D-30] Unexpected exception", e)
                }
                return arr
            }
        }
    }
}
