/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.compression

import net.rwhps.server.util.compression.core.AbstractEncoder
import net.rwhps.server.util.compression.encoder.SevenZipEncoder
import net.rwhps.server.util.compression.encoder.ZipEncoder

/**
 * 获取压缩构造器
 *
 * @author Dr (dr@der.kim)
 */
object CompressionEncoderUtils {
    /**
     * 获取 Zip 压缩构造器
     * @return AbstractEncoder
     */
    fun zipStream(): AbstractEncoder = ZipEncoder()

    /**
     * 获取 7z 压缩构造器
     * @return AbstractEncoder
     */
    fun sevenStream(): AbstractEncoder = SevenZipEncoder()
}