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
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 流转换
 * @author Dr (dr@der.kim)
 */
object IoReadConversion {
    @JvmStatic
    @JvmOverloads
    fun streamBuffer(inputStream: InputStream, charset: Charset = StandardCharsets.UTF_8): InputStreamReader = InputStreamReader(
            inputStream, charset
    )

    @JvmStatic
    @JvmOverloads
    fun streamBufferRead(inputStream: InputStream, charset: Charset = StandardCharsets.UTF_8): BufferedReader = BufferedReader(
            InputStreamReader(inputStream, charset)
    )

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun fileToBufferReadStream(file: File, charset: Charset = StandardCharsets.UTF_8): BufferedReader = BufferedReader(
            fileToReadStream(file, charset)
    )

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun fileToReadStream(file: File, charset: Charset = StandardCharsets.UTF_8): InputStreamReader = InputStreamReader(
            fileToStream(file), charset
    )

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun fileToReadStream(inputStream: InputStream, charset: Charset = StandardCharsets.UTF_8): InputStreamReader = InputStreamReader(
            inputStream, charset
    )

    @JvmStatic
    @Throws(IOException::class)
    fun fileToStream(file: File): FileInputStream = FileInputStream(file)
}