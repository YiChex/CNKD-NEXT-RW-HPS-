/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util

import net.rwhps.server.util.log.Log

/**
 * @author Dr (dr@der.kim)
 */
object Font16 {
    private val ENCODE = "GB2312"

    /**
     * 解析成点阵
     * @param str 需要转换的字符 (单个)
     * @return Byte[][]
     */
    fun resolveString(str: String): Array<ByteArray> {
        val data: ByteArray
        val code: IntArray
        var byteCount: Int
        var lCount: Int

        //返回的二位数组
        val arr: Array<ByteArray>
        if (str[0].code < 0x80) {
            // 字母
            //ascii码 8*16
            val fontWidth = 8
            //ascii码 8*16
            val fontHeight = 16
            arr = Array(fontHeight) { ByteArray(fontWidth) }
            data = read_a(str[0])
            byteCount = 0
            for (line in 0 .. 15) {
                lCount = 0
                for (k in 0 .. 0) {
                    for (j in 0 .. 7) {
                        if (((data[byteCount].toInt() shr (7 - j)) and 0x1) == 1) {
                            arr[line][lCount] = 1
                        } else {
                            arr[line][lCount] = 0
                        }
                        lCount++
                    }
                    byteCount++
                }
            }
        } else {
            //16*16
            val all_16_32 = 16
            arr = Array(all_16_32) { ByteArray(all_16_32) }
            // 截取一个字符
            code = getByteCode(str.substring(0, 1))
            data = read(code[0], code[1])
            byteCount = 0
            for (line in 0 until all_16_32) {
                lCount = 0
                //一个汉字等于两个字节
                val all_2_4 = 2
                for (k in 0 until all_2_4) {
                    for (j in 0 .. 7) {
                        if (((data[byteCount].toInt() shr (7 - j)) and 0x1) == 1) {
                            arr[line][lCount] = 1
                        } else {
                            arr[line][lCount] = 0
                        }
                        lCount++
                    }
                    byteCount++
                }
            }
        }
        return arr
    }

    /**
     * 读取字库中的ASCII 码
     */
    private fun read_a(charNum: Char): ByteArray {
        val data: ByteArray

        //定义缓存区的大小
        //ascii码解析成8*16 所占字节数
        val all_16 = 16
        data = ByteArray(all_16)
        Font16::class.java.getResourceAsStream("/ASC16")!!.use { inputStream ->
            //ascii码在字库里的偏移量
            val offset = charNum.code * 16
            inputStream.skip(offset.toLong())
            //读取字库中ascii码点阵数据
            inputStream.read(data, 0, all_16)
            inputStream.close()
        }
        return data
    }

    /**
     * 读取字库中的汉字
     * @param areaCode
     * @param posCode
     * @return
     */
    private fun read(areaCode: Int, posCode: Int): ByteArray {
        //定义缓存区的大小
        val data: ByteArray

        //区码
        val area = areaCode - 0xa0
        //位码
        val pos = posCode - 0xa0
        //汉字在字库里的偏移量
        val all_32_128 = 32
        //汉字解析成16*16 所占字节数
        val offset = all_32_128 * ((area - 1) * 94L + pos - 1)
        data = ByteArray(all_32_128)

        Font16::class.java.getResourceAsStream("/HZK16")!!.use { inputStream ->
            //跳过偏移量
            inputStream.skip(offset)
            //读取该汉字的点阵数据
            inputStream.read(data, 0, all_32_128)
        }
        return data
    }

    /**
     * 获取汉字的区，位（ascii码不需要区码，位码）
     * @param str
     * @return
     */
    private fun getByteCode(str: String): IntArray {
        val byteCode = IntArray(2)
        try {
            val data = str.toByteArray(charset(ENCODE))
            byteCode[0] = if (data[0] < 0) 256 + data[0] else data[0].toInt()
            byteCode[1] = if (data[1] < 0) 256 + data[1] else data[1].toInt()
        } catch (ex: Exception) {
            Log.error("[D-30] Unexpected exception", ex)
        }
        return byteCode
    }
}