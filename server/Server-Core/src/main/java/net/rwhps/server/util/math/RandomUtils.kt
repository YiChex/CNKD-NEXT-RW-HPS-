/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.math

import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * @author Dr (dr@der.kim)
 */
object RandomUtils {
    private val defaultRanges: Array<CharRange> = arrayOf('a' .. 'z', 'A' .. 'Z', '0' .. '9')
    private val letterRanges: Array<CharRange> = arrayOf('a' .. 'z', 'A' .. 'Z')
    private val intCharRanges: Array<CharRange> = arrayOf('0' .. '9')


    @JvmStatic
    fun getRandomByteArray(length: Int, random: Random = Random): ByteArray = ByteArray(length) { random.nextInt(0 .. 255).toByte() }

    /**
     * 随机生成一个正整数
     */
    @JvmStatic
    fun getRandomUnsignedInt(): Int = Random.nextInt().absoluteValue

    /**
     * 随机生成长度为 [length] 的 [String].
     */
    @JvmStatic
    @JvmOverloads
    fun getRandomString(length: Int, random: Random = Random): String = getRandomString(length, *defaultRanges, random = random)

    /**
     * 根据所给 [charRange] 随机生成长度为 [length] 的 [String].
     */
    @JvmStatic
    fun getRandomString(length: Int, charRange: CharRange, random: Random = Random): String = CharArray(length) {
        charRange.random(
                random
        )
    }.concatToString()

    /**
     * 根据所给 [charRanges] 随机生成长度为 [length] 的 [String].
     */
    @JvmStatic
    fun getRandomString(length: Int, vararg charRanges: CharRange, random: Random = Random): String = CharArray(
            length
    ) { charRanges[random.nextInt(0 .. charRanges.lastIndex)].random(random) }.concatToString()


    /**
     * 产生len长度的随机数字
     * @param length
     * @return
     */
    @JvmStatic
    fun getRandomIntString(length: Int, random: Random = Random): String = getRandomString(length, *intCharRanges, random = random)

    /**
     * 产生len长度的随机字母串(只包含大小写字母)
     * @param length 随机字符串长度
     * @return
     */
    @JvmStatic
    fun getRandomIetterString(length: Int, random: Random = Random): String = getRandomString(length, *letterRanges, random = random)

    /**
     * 返回一个定长的随机纯大写字母字符串(只包含大写字母)
     * @param length 随机字符串长度
     * @return 随机字符串
     */
    @JvmStatic
    fun generateLowerStr(length: Int): String = getRandomIetterString(length).lowercase()

    /**
     * 返回一个定长的随机纯小写字母字符串(只包含小写字母)
     * @param length 随机字符串长度
     * @return 随机字符串
     */
    @JvmStatic
    fun generateUpperStr(length: Int): String = getRandomIetterString(length).uppercase()
}