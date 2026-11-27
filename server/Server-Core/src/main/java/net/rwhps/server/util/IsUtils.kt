/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import java.net.InetAddress
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * @author Dr (dr@der.kim)
 */
object IsUtils {
    private val PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?")
    private val PATTERNNegativeNumber = Pattern.compile("[-+]?[0-9]*")
    private val IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private const val IPV4_MAX_OCTET_VALUE = 255

    @JvmStatic
    fun isBlank(string: Any?): Boolean {
        return string == null || "" == string.toString().trim { it <= ' ' }
    }

    @JvmStatic
    fun notIsBlank(string: Any?): Boolean {
        return !isBlank(string)
    }

    @JvmStatic
    fun isNull(string: Any?): Boolean {
        return string == null
    }

    @JvmStatic
    fun notIsNull(string: Any?): Boolean {
        return !isNull(string)
    }

    @JvmStatic
    fun isBlankDefaultResult(string: Any?): String {
        return if (isBlank(string)) {
            ""
        } else {
            string.toString()
        }
    }

    @JvmStatic
    fun isNumeric(string: String): Boolean {
        return PATTERN.matcher(string).matches()
    }

    @JvmStatic
    fun isNumericNegative(string: String): Boolean {
        return PATTERNNegativeNumber.matcher(string).matches()
    }

    @JvmStatic
    fun notIsNumeric(string: String): Boolean {
        return !isNumeric(string)
    }

    @JvmStatic
    fun isTwoTimes(n: Int): Boolean {
        return n > 0 && n and 1 == 0
    }

    @JvmStatic
    fun isPowerOfTwo(n: Int): Boolean {
        return n > 0 && n and n - 1 == 0
    }

    @JvmStatic
    fun inTwoNumbers(min: Double, b: Double, max: Double): Boolean {
        return max(min, b) == min(b, max)
    }

    @JvmStatic
    fun inTwoNumbersNoSE(min: Double, b: Double, max: Double): Boolean {
        return if (doubleToLong(min) != doubleToLong(b) && doubleToLong(max) != doubleToLong(b)) {
            inTwoNumbers(min, b, max)
        } else false
    }

    @JvmStatic
    fun inTwoNumbersNoSrE(min: Double, b: Double, max: Double, start: Boolean): Boolean {
        if (start) {
            if (doubleToLong(max) != doubleToLong(b)) {
                return inTwoNumbers(min, b, max)
            }
        } else {
            if (doubleToLong(min) != doubleToLong(b)) {
                return inTwoNumbers(min, b, max)
            }
        }
        return false
    }

    @JvmStatic
    fun isDomainName(domain: String): Boolean {
        return try {
            isIPv4Address(InetAddress.getByName(domain).hostAddress)
            true
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    fun isIPv4Address(name: String): Boolean {
        val m = IPV4_PATTERN.matcher(name)
        if (!m.matches() || m.groupCount() != 4) {
            return false
        }

        // 验证地址子组是否合法
        for (i in 1 .. 4) {
            val ipSegment = m.group(i)
            val iIpSegment = ipSegment.toInt()
            if (iIpSegment > IPV4_MAX_OCTET_VALUE) {
                return false
            }
            if (ipSegment.length > 1 && ipSegment.startsWith("0")) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun doubleToLong(d: Double): Long {
        return java.lang.Double.doubleToLongBits(d)
    }
}