/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.str

import net.rwhps.server.util.IsUtils.notIsBlank
import net.rwhps.server.util.log.Log
import java.util.regex.Matcher
import java.util.regex.Pattern


/**
 * @author Dr (dr@der.kim)
 */
object StringFilteringUtil {
    private val pattern = Pattern.compile("[1-9][0-9]{4,14}")

    private val BLANK_REMOVE_REGEX = Regex("[　*| *| *|//s*]*")
    private val TRIM_START_REGEX = Regex("^[　*| *| *|//s*]*")
    private val TRIM_END_REGEX = Regex("[　*| *| *|//s*]*$")
    private val EN_ONLY_REGEX = Regex("[^(A-Za-z)]")
    private val CN_ONLY_REGEX = Regex("[^(\\u4e00-\\u9fa5)]")
    private val FILTER_CHINESE_REGEX = Regex("[^(a-zA-Z0-9\\u4e00-\\u9fa5)]")

    private val IP_ADDRESS: Pattern = Pattern.compile(
            "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]).(25[0-5]|2[0-4]" + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0).(25[0-5]|2[0-4][0-9]|[0-1]" + "[0-9]{2}|[1-9][0-9]|[1-9]|0).(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}" + "|[1-9][0-9]|[0-9]))"
    )

    /**
     * Good characters for Internationalized Resource Identifiers (IRI).
     * This comprises most common used Unicode characters allowed in IRI
     * as detailed in RFC 3987.
     * Specifically, those two byte Unicode characters are not included.
     */
    private const val GOOD_IRI_CHAR = "a-zA-Z0-9\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF"

    /**
     * RFC 1035 Section 2.3.4 limits the labels to a maximum 63 octets.
     */
    private const val IRI = "[$GOOD_IRI_CHAR]([$GOOD_IRI_CHAR\\-]{0,61}[$GOOD_IRI_CHAR]){0,1}"
    private const val GOOD_GTLD_CHAR = "a-zA-Z\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF"
    private val GTLD: String = "[$GOOD_GTLD_CHAR]{2,63}"
    private val HOST_NAME = "($IRI.)+$GTLD"
    private val DOMAIN_NAME = Pattern.compile("($HOST_NAME|$IP_ADDRESS)")

    /**
     * Regular expression pattern to match most part of RFC 3987
     * Internationalized URLs, aka IRIs.  Commonly used Unicode characters are
     * added.
     */
    private val WEB_URL = Pattern.compile(
            "((?:(http|https|Http|Https|rtsp|Rtsp)://(?:(?:[a-zA-Z0-9$\\-_.+!*'()" + ",;?&=]|%[a-fA-F0-9]{2}){1,64}(?::(?:[a-zA-Z0-9$\\-_" + ".+!*'(),;?&=]|%[a-fA-F0-9]{2}){1,25})?@)?)?" + "DOM" + DOMAIN_NAME + "AIN_NAME" + "(?::\\d{1,5})?)" // plus option port number
                    + "(/(?:[GOO" + GOOD_IRI_CHAR + "D_IRI_CHA" // plus option query params
                    + "R;/?:@&=#~\\-.+!*'(),_]|%[a-fA-F0-9]{2})*)?" + "(?:\\b|$)"
    )
    private val WEB_URL0 = Pattern.compile(
            "([hH][tT]{2}[pP]://|[hH][tT]{2}[pP][sS]://|[wW]{3}.|[wW][aA][pP].|[fF][tT][pP].|[fF][iI][lL][eE].)[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]\n"
    )

    private fun findFirstGroup(matcher: Matcher): String? {
        matcher.find()
        return matcher.group(0)
    }

    @JvmStatic
    fun findMatchString(str: String, regEx: String): String {
        return try {
            val pattern = Pattern.compile(regEx)
            val matcher = pattern.matcher(str)
            findFirstGroup(matcher) ?:""
        } catch (e: java.lang.Exception) {
            Log.error("[Find Match] Error", e)
            ""
        }
    }


    @JvmStatic
    fun getkeys(textRuselt: String, keys: String, numbero: Int, numbert: Int): String {
        var tkk = ""
        // 去除返回数据空格
        val text = removeAllisBlank(textRuselt)
        if (notIsBlank(textRuselt)) {
            val matchString: String = findMatchString(text, keys)
            // 提取目标
            tkk = matchString.substring(numbero, matchString.length - numbert)
        }
        return tkk
    }

    @JvmStatic
    fun removeAllisBlank(s: String): String {
        var result = ""
        if ("" != s) {
            result = s.replace(BLANK_REMOVE_REGEX, "")
        }
        return result
    }

    @JvmStatic
    fun trim(s: String): String {
        var result = ""
        if ("" != s) {
            result = s.replace(TRIM_START_REGEX, "").replace(TRIM_END_REGEX, "")
        }
        return result
    }

    @JvmStatic
    fun removeAllEn(s: String): String {
        var result = ""
        if ("" != s) {
            result = s.replace(EN_ONLY_REGEX, "")
        }
        return result
    }

    @JvmStatic
    fun removeAllCn(s: String): String {
        var result = ""
        if ("" != s) {
            result = s.replace(CN_ONLY_REGEX, "")
        }
        return result
    }

    @JvmStatic
    fun readQQ(str: String): String {
        val matcher = pattern.matcher(str)
        return if (matcher.find()) {
            matcher.group(0)
        } else ""
    }

    @JvmStatic
    fun cutting(str: String, length: Int): String {
        return if (str.length < length) {
            str
        } else str.substring(0, length)
    }

    @JvmStatic
    fun cuttingEnd(str: String): String {
        return str.substring(0, str.length - 1)
    }


    /**
     *
     * @Title : filter
     * @Type : FilterStr
     * @date : 2014年3月12日 下午9:17:22
     * @Description : 过滤出字母、数字和中文
     * @param character
     * @return
     */
    @JvmStatic
    fun filterChines(character: String): String {
        return character.replace(FILTER_CHINESE_REGEX, "")
    }


    /***
     * 把中文替换为指定字符<br></br>
     * 注意:一次只匹配一个中文字符
     * @param source
     * @param replacement
     * @return
     */
    @JvmStatic
    fun replaceChinese(source: String, replacement: String): String {
        val reg = "[\u4e00-\u9fa5]"
        val pat = Pattern.compile(reg)
        val mat = pat.matcher(source)
        return mat.replaceAll(replacement)
    }

    @JvmStatic
    fun checkURL(str: String): Boolean {
        val matcher = WEB_URL.matcher(str)
        return matcher.find()
    }

    class StringMatcherData(patternString: String, text: String) {
        private val matcher: Matcher = Pattern.compile(patternString).matcher(text)

        fun getString(position: Int): String {
            val result = matcher.group(position)
            return if (notIsBlank(result)) result else ""
        }

        fun getInt(position: Int): Int {
            val result = matcher.group(position)
            return if (notIsBlank(result)) result.toInt() else 0
        }

        init {
            matcher.find()
        }
    }
}