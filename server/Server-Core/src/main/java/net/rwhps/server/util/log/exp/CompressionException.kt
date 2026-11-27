/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log.exp

/**
 * @date  2023/5/26 15:34
 * @author Dr (dr@der.kim)
 */
open class CompressionException(type: String): Exception(type) {
    class CryptographicException(type: String): CompressionException(type)
    class UnsupportedRatingsException(type: String): CompressionException(type)
}