/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import net.rwhps.server.data.global.ArrayData
import net.rwhps.server.data.global.RegexData
import net.rwhps.server.util.log.exp.VariableException
import java.net.*
import java.util.*
import java.util.regex.Pattern

/**
 * IP 工具类
 * @author Dr (dr@der.kim)
 */
object IpUtils {
    private val IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private const val IPV4_MAX_OCTET_VALUE = 255

    private const val IPV6_MAX_HEX_GROUPS = 8
    private const val IPV6_MAX_HEX_DIGITS_PER_GROUP = 4
    private const val MAX_UNSIGNED_SHORT = 0xffff
    private const val BASE_16 = 16

    private val REG_NAME_PART_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]*$")

    // Cached Regex patterns for IP utilities (compiled once, reused across hot path calls)
    private val COLON_REGEX = ":".toRegex()
    private val AMPERSAND_REGEX = "&".toRegex()
    private val DOT_REGEX = "\\.".toRegex()

    /**
     * 获取本机内网ip
     * @return
     * @throws UnknownHostException
     * @author RW-HPS/fgsqme
     */
    @JvmStatic
    @JvmOverloads
    @Throws(UnknownHostException::class)
    fun getPrivateIp(ipv4: Boolean = true): String? {
        val allNetInterfaces = NetworkInterface.getNetworkInterfaces()
        for (netInterface in allNetInterfaces) {
            if (netInterface.isLoopback || netInterface.isVirtual || !netInterface.isUp) {
                continue
            }
            val addresses = netInterface.inetAddresses
            for (ip in addresses) {
                if (ipv4) {
                    if (ip is Inet4Address) {
                        return ip.hostAddress
                    }
                } else {
                    if (ip is Inet6Address) {
                        return ip.hostAddress
                    }
                }
            }

        }
        return null
    }

    /**
     * Builds the ban-table key for an IP address.
     *
     * D-11: `separateAddress` used to default to `true`, which **keeps** the last octet
     * and therefore produces the key of an exact address, not of a /24 network. Every
     * production call site (`ConnectionAgreement`, `RelayAdminApiSupport`) passes
     * `false` explicitly to obtain the /24 key that `Administration.bannedIP24` stores.
     * A caller relying on the old default would have written/queried a key that never
     * matches, silently failing open. The default is now aligned with the function name
     * and with every existing call site.
     *
     * Note: this is not general CIDR support -- only whole /24 (IPv4) or /64-equivalent
     * (IPv6) network keys can be expressed.
     *
     * @param separateAddress `false` (recommended) zeroes the host part, yielding the
     *                        network key; `true` keeps the full address.
     */
    @JvmStatic
    @JvmOverloads
    fun ipToLong24(strIp: String, separateAddress: Boolean = false): String {
        return if (isIPv4Address(strIp)) {
            "[IPV4]-"+ipv4ToLongs(strIp, separateAddress)
        } else if (isIPv6Address(strIp)) {
            "[IPV6]-"+ipv6ToLongs(ipv6Format(strIp), separateAddress)
        } else {
            throw VariableException("[IP-Check IPV4-IPV6 Unknown]: $strIp")
        }
    }

    @JvmStatic
    fun longToIp(strLong: String): String {
        val v4Flag = "[IPV4]-"
        val v6Flag = "[IPV6]-"
        return if (strLong.startsWith(v4Flag)) {
            longToIP(strLong.removePrefix(v4Flag).toLong())
        } else if (strLong.startsWith(v6Flag)) {
            longsToIpv6(strLong.removePrefix(v6Flag))
        } else {
            longToIP(strLong.toLong())
        }
    }

    /**
     * ip地址转成long型数字
     * 将IP地址转化成整数的方法如下：
     * 1、通过String的split方法按.分隔得到4个长度的数组
     * 2、通过左移位操作（<<）给每一段的数字加权，第一段的权为2的24次方，第二段的权为2的16次方，第三段的权为2的8次方，最后一段的权为1
     *
     * 忽略最后一位 直接取段
     * @param strIp
     * @return
     */
    private fun ipv4ToLongs(strIp: String, separateAddress: Boolean): String {
        if (strIp == "0") {
            return strIp
        }
        val ipv4Array0 = strIp.split(".")
        val ipv4Array = LongArray(4) {
            ipv4Array0[it].toLong()
        }
        return (((ipv4Array[0] shl 24) + (ipv4Array[1] shl 16) + (ipv4Array[2] shl 8)) + if (separateAddress) ipv4Array[3] else 0).toString()
    }

    /**
     * 将 IPv6 地址转为 long 数组，只支持冒分十六进制表示法
     */
    private fun ipv6ToLongs(ipv6: String, separateAddress: Boolean): String {
        val ipSlices = ipv6.split(COLON_REGEX).dropLastWhile { it.isEmpty() }.toTypedArray()
        val ipv6Array = LongArray(2)
        for (i in 0 .. 7) {
            val slice = ipSlices[i]
            // 以 16 进制解析
            val num = slice.toLong(16).let {
                if (separateAddress) 0 else it
            }
            // 每组 16 位
            val right = num shl (16 * i)
            // 每个 long 保存四组，i >> 2 等于 i / 4
            val length = i shr 2 //即int length=i / 4;
            ipv6Array[length] = ipv6Array[length] or right
        }

        return "${ipv6Array[0]}@${ipv6Array[1]}"
    }


    /**
     * 将十进制整数形式转换成127.0.0.1形式的ip地址
     * 将整数形式的IP地址转化成字符串的方法如下：
     * 1、将整数值进行右移位操作（>>>），右移24位，右移时高位补0，得到的数字即为第一段IP。
     * 2、通过与操作符（&）将整数值的高8位设为0，再右移16位，得到的数字即为第二段IP。
     * 3、通过与操作符吧整数值的高16位设为0，再右移8位，得到的数字即为第三段IP。
     * 4、通过与操作符吧整数值的高24位设为0，得到的数字即为第四段IP。
     * @param longIp
     * @return
     */
    private fun longToIP(longIp: Long): String {
        val sb = StringBuffer("")
        // 直接右移24位
        sb.append((longIp ushr 24).toString()).append(".")
        // 将高位8 位置0，然后右移16位
        sb.append((longIp and 0x00FFFFFF ushr 16).toString()).append(".")
        // 将高位16 位置0，然后右移8位
        sb.append((longIp and 0x0000FFFF ushr 8).toString()).append(".")
        // 将高位24 位置0
        sb.append((longIp and 0x000000FF).toString())
        return sb.toString()
    }

    /**
     * 将 long 数组转为冒分十六进制表示法的 IPv6 地址
     */
    private fun longsToIpv6(numbers: String): String {
        val sb = java.lang.StringBuilder(32)

        val ipv6Array = LongArray(2) {
            numbers.split("@")[it].toLong()
        }
        for (numSlice0 in ipv6Array) {
            var numSlice = numSlice0
            // 每个 long 保存四组
            for (j in 0 .. 3) {
                // 取最后 16 位
                val current = numSlice and 0xFFFFL
                sb.append(current.toString(16)).append(":")
                // 右移 16 位，即去除掉已经处理过的 16 位
                numSlice = numSlice shr 16
            }
        }

        // 去掉最后的 :
        return sb.substring(0, sb.length - 1)
    }


    /**
     * Checks whether a given string is a valid host name according to
     * RFC 3986.
     *
     *
     * Accepted are IP addresses (v4 and v6) as well as what the
     * RFC calls a "reg-name". Percent encoded names don't seem to be
     * valid names in UNC paths.
     *
     * @see "https://tools.ietf.org/html/rfc3986.section-3.2.2"
     *
     * @param name the hostname to validate
     * @return true if the given name is a valid host name
     */
    @JvmStatic
    fun isValidHostName(name: String): Boolean {
        return isIPv4Address(name) || isIPv6Address(name) || isRFC3986HostName(name)
    }

    /**
     * Checks whether a given string represents a valid IPv4 address.
     *
     * @param name the name to validate
     * @return true if the given name is a valid IPv4 address
     */
    // mostly copied from org.apache.commons.validator.routines.InetAddressValidator#isValidInet4Address
    private fun isIPv4Address(name: String): Boolean {
        val m = IPV4_PATTERN.matcher(name)
        if (!m.matches() || m.groupCount() != 4) {
            return false
        }

        // verify that address subgroups are legal
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

    /**
     * Checks whether a given string represents a valid IPv6 address.
     *
     * @param inet6Address the name to validate
     * @return true if the given name is a valid IPv6 address
     */
    private fun isIPv6Address(inet6Address: String): Boolean {
        val containsCompressedZeroes = inet6Address.contains("::")
        if (containsCompressedZeroes && inet6Address.indexOf("::") != inet6Address.lastIndexOf("::")) {
            return false
        }
        if (inet6Address.startsWith(":") && !inet6Address.startsWith("::") || inet6Address.endsWith(":") && !inet6Address.endsWith("::")) {
            return false
        }
        var octets = inet6Address.split(RegexData.colon).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (containsCompressedZeroes) {
            val octetList: ArrayList<String> = ArrayList(listOf(*octets))
            if (inet6Address.endsWith("::")) {
                // String.split() drops ending empty segments
                octetList.add("")
            } else if (inet6Address.startsWith("::") && octetList.isNotEmpty()) {
                octetList.removeAt(0)
            }
            octets = octetList.toArray(ArrayData.stringArray)
        }
        if (octets.size > IPV6_MAX_HEX_GROUPS) {
            return false
        }
        var validOctets = 0
        // consecutive empty chunks
        var emptyOctets = 0
        for (index in octets.indices) {
            val octet = octets[index]
            if (octet.isEmpty()) {
                emptyOctets++
                if (emptyOctets > 1) {
                    return false
                }
            } else {
                emptyOctets = 0
                // Is last chunk an IPv4 address?
                if (index == octets.size - 1 && octet.contains(".")) {
                    if (!isIPv4Address(octet)) {
                        return false
                    }
                    validOctets += 2
                    continue
                }
                if (octet.length > IPV6_MAX_HEX_DIGITS_PER_GROUP) {
                    return false
                }
                val octetInt: Int = try {
                    octet.toInt(BASE_16)
                } catch (e: NumberFormatException) {
                    return false
                }
                if (octetInt < 0 || octetInt > MAX_UNSIGNED_SHORT) {
                    return false
                }
            }
            validOctets++
        }
        return validOctets <= IPV6_MAX_HEX_GROUPS && (validOctets >= IPV6_MAX_HEX_GROUPS || containsCompressedZeroes)
    }

    private fun ipv6Format(ipv6: String): String {
        val ipmat = ipv6.replace("::", "&")
        var ipv6No0 = ipv6
        val index = ipmat.indexOf("&")

        //判断是否有::
        if (ipmat.contains("&")) {
            //判断：的数量
            val n = ipmat.length - ipmat.replace(COLON_REGEX, "").length
            //如果出现:: 在第一个的位置 或者 最后的位置
            if (index == 0 || index == ipmat.length - 1) {
                //需要补0的数量
                val i = 8 - n
                val str = StringBuilder()
                var bj = ""
                //如果出现:: 在第一个的位置
                bj = if (index == 0) {
                    "0:"
                } else { //如果出现:: 在最后的位置
                    ":0"
                }
                for (j in 0 until i) {
                    str.append(bj)
                }
                ipv6No0 = ipmat.replace("&", str.toString())
            } else { // 如果出现:: 中间位置
                val split = ipmat.split(AMPERSAND_REGEX).dropLastWhile { it.isEmpty() }.toTypedArray()
                //需要补0的数量
                val i = 8 - (n + 2)
                val str = StringBuilder()
                for (j in 0 until i) {
                    str.append(":0")
                }
                str.append(":")
                ipv6No0 = split[0] + str.toString() + split[1]
            }
        }
        val split = ipv6No0.split(COLON_REGEX).dropLastWhile { it.isEmpty() }.toTypedArray()
        val ipv6s = StringBuilder()

        //补0
        for (i in split.indices) {
            val s = split[i]
            when (s.length) {
                1 -> ipv6s.append("000$s")
                2 -> ipv6s.append("00$s")
                3 -> ipv6s.append("0$s")
                4 -> ipv6s.append(s)
            }
            if (i != split.size - 1) {
                ipv6s.append(":")
            }
        }
        return ipv6s.toString()
    }

    /**
     * Checks whether a given string is a valid host name according to
     * RFC 3986 - not accepting IP addresses.
     *
     * @see "https://tools.ietf.org/html/rfc3986.section-3.2.2"
     *
     * @param name the hostname to validate
     * @return true if the given name is a valid host name
     */
    private fun isRFC3986HostName(name: String): Boolean {
        val parts = name.split(DOT_REGEX).toTypedArray()
        for (i in parts.indices) {
            if (parts[i].isEmpty()) {
                // trailing dot is legal, otherwise we've hit a .. sequence
                return i == parts.size - 1
            }
            if (!REG_NAME_PART_PATTERN.matcher(parts[i]).matches()) {
                return false
            }
        }
        return true
    }
}