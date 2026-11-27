/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import net.renfei.ip2location.IP2Location
import net.renfei.ip2location.IPResult
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log


/**
 * @author Dr (dr@der.kim)
 */
object IPCountry {
    private val searcher = IP2Location().apply {
        Open(CompressionDecoderUtils.sevenAllReadStream(FileUtils.getInternalFileStream("/ip2location.7z"))
            .getZipAllBytes(false)["IP2LOCATION-LITE-DB5.BIN"])
    }

    fun test() {
        Log.clog(checkIpData("111.173.64.99").toString())
        Log.clog(checkIpData("111.173.64.99").toString())
        Log.clog(checkIpData("47.106.105.236").toString())
        Log.clog(checkIpData("222.66.202.6").toString())
        Log.clog(checkIpData("120.194.55.139").toString())
        Log.clog(checkIpData("61.164.39.68").toString())
    }

    @JvmSynthetic
    @JvmStatic
    fun getIpCountry(ip: String): String {
        return checkIpData(ip).countryLong
    }

    @JvmSynthetic
    @JvmStatic
    fun getIpCountryAll(ip: String): String {
        //中国|亚洲|湖北|十堰|电信
        val data = checkIpData(ip)
        return "${data.countryLong}|${data.region}|${data.city}"
    }

    @JvmSynthetic
    @JvmStatic
    fun getLatitudeLongitude(ip: String): String {
        return checkIpData(ip).let { "${it.latitude}&${it.longitude}" }
    }

    private fun checkIpData(ip: String): IPResult {
        val rec: IPResult = searcher.IPQuery(ip)
        return when (rec.status) {
            "OK" -> rec
            "EMPTY_IP_ADDRESS" -> {
                throw Exception("IP address cannot be blank.")
            }
            "INVALID_IP_ADDRESS" -> {
                throw Exception("Invalid IP address.")
            }
            "MISSING_FILE" -> {
                throw Exception("Invalid database path.")
            }
            "IPV6_NOT_SUPPORTED" -> {
                throw Exception("This BIN does not contain IPv6 data.")
            }
            else -> {
                throw Exception("Unknown error." + rec.status)
            }
        }
    }
}