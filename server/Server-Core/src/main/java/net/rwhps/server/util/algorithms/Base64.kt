/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.algorithms

import net.rwhps.server.data.global.Data
import java.util.Base64

/**
 * @author Dr (dr@der.kim)
 */
object Base64 {
    /**
     *
     * @param str Base64字符串
     * @return 解密后
     */
    @JvmStatic
    fun decode(str: String): ByteArray {
        return Base64.getDecoder().decode(str)
    }

    @JvmStatic
    fun decodeString(str: String): String {
        return String(Base64.getDecoder().decode(str), Data.UTF_8)
    }

    /**
     *
     * @return 加密后
     */
    @JvmStatic
    fun encode(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    @JvmStatic
    fun encode(str: String): String {
        return Base64.getEncoder().encodeToString(str.toByteArray(Data.UTF_8))
    }

    @JvmStatic
    fun isBase64(`val`: String): Boolean {
        try {
            val key = Base64.getDecoder().decode(`val`)
            val str = String(key)
            val result = Base64.getEncoder().encodeToString(str.toByteArray())
            if (result.equals(`val`, ignoreCase = true)) {
                return true
            }
        } catch (ignored: Exception) {
        }
        return false
    }
}