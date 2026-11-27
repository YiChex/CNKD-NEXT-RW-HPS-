/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.io

import java.io.*
import java.nio.charset.StandardCharsets

/**
 * @author Dr (dr@der.kim)
 */
object IoOutConversion {
    @JvmStatic
    @Throws(IOException::class)
    fun outToOutStream(outputStream: OutputStream): OutputStreamWriter = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)

    @JvmStatic
    @Throws(IOException::class)
    fun fileToOutStream(file: File, cover: Boolean): OutputStreamWriter = OutputStreamWriter(
            fileToStream(file, cover), StandardCharsets.UTF_8
    )

    @JvmStatic
    @Throws(IOException::class)
    fun fileToStream(file: File, tail: Boolean): FileOutputStream = FileOutputStream(file, tail)
}