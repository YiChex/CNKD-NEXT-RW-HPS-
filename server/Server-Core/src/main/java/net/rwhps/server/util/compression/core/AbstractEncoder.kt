/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.compression.core

import net.rwhps.server.util.log.exp.CompressionException
import java.io.InputStream

/**
 * 压缩 通用接口
 * @author Dr (dr@der.kim)
 */
abstract class AbstractEncoder {
    @Throws(CompressionException.UnsupportedRatingsException::class)

    abstract fun setCompressibility(level: Int)

    fun addCompressBytes(name: String = "", compressionDecoder: AbstractDecoder) {
        compressionDecoder.use {
            it.getZipAllBytes().forEach { addCompressBytes(it.key, it.value) }
        }
    }

    fun addCompressBytes(name: String, inStream: InputStream) {
        addCompressBytes(name, inStream, null)
    }

    fun addCompressBytes(name: String, bytes: ByteArray) {
        addCompressBytes(name, null, bytes)
    }

    protected abstract fun addCompressBytes(name: String, inStream: InputStream?, bytes: ByteArray?)

    abstract fun flash(): ByteArray
}