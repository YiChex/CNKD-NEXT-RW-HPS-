/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.test

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log

/**
 * @author Dr (dr@der.kim)
 */
class Relay {
    fun test() {
        val bytes = GameOutputStream()
        CompressionDecoderUtils.zipAllReadStream(FileUtils.getFile("A.zip").getInputsStream()).getZipAllBytes().eachAllFind( { k,_ -> k.contains("Accept") } ) { _, v ->
            bytes.writeBytes(v)
        }

        val read = GameInputStream(bytes.getByteArray())
        while (read.getSize() > 0) {
            val length = read.readInt()
            val type = read.readInt()
            read.skip(length.toLong())
            Log.debug(length, type.toString() + "   " + IRwHps.packetType.from(type).nameEnum)
        }


    }
}