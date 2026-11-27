/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.struct

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import java.io.IOException

/**
 * 序列化/反序列化使用的读取与写入
 * 用在DefaultSerializers.java中
 * @author Dr (dr@der.kim)
 */
class SerializerTypeAll {
    interface TypeSerializer<T> {
        /**
         * 序列化写入
         * @param paramDataOutput 输出流
         * @param param 输入的数据
         * @throws IOException Error
         */
        @Throws(IOException::class)
        fun write(paramDataOutput: GameOutputStream, objectParam: T)

        /**
         * 反序列化读取
         * @param paramDataInput 输入流
         * @return 反序列化后的数据
         * @throws IOException Error
         */
        @Throws(IOException::class)
        fun read(paramDataInput: GameInputStream): T
    }
}
