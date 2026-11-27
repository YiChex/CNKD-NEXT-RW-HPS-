/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.input

/**
 * 这个是为ByteArrayInputStream做出的特殊改造
 * 你可以直接为Stream加入数据 并清除以前读取过的数据(防止Stream越来越大)
 *
 * @author Dr (dr@der.kim)
 */
open class ClearableAndReusableByteArrayInputStream: ByteArrayInputStream(ByteArray(0)) {
    /**
     * 向字节流中加入数据
     * 设置length的目的是 你输入的bytes长度无法猜测 你可以直接把你的buf[]输入进来 再把length设置为读取到的长度
     * @param bytes 新加入的字节组
     * @param length 加入的长度
     */
    @Synchronized
    fun addBytes(bytes: ByteArray, length: Int) {
        count += length
        buf = mergeBytes(buf, bytes, length)
    }

    /**
     * 清理字节流已读数据
     */
    @Synchronized
    fun removeOldRead() {
        count -= pos
        buf = removeBytes(buf, pos, count)
        pos = 0
        mark = 0
    }

    fun position(): Int {
        return pos
    }

    fun count(): Int {
        return count
    }

    override fun close() {
        buf = ByteArray(0)
        count = 0
        pos = 0
        mark = 0
    }

    /**
     * 将两个byte数组合并为一个
     * @param data1  要合并的数组1
     * @param data2  要合并的数组2
     * @param length 数组2长度
     * @return 合并后的新数组
     */
    private fun mergeBytes(data1: ByteArray, data2: ByteArray, length: Int): ByteArray {
        val data3 = ByteArray(data1.size + length)
        System.arraycopy(data1, 0, data3, 0, data1.size)
        System.arraycopy(data2, 0, data3, data1.size, length)
        return data3
    }

    /**
     * 将两个byte数组合并为一个
     * @param src byte源数组
     * @param srcPos 截取源byte数组起始位置（0位置有效）
     * @param destPos 截取后存放的数组起始位置（0位置有效）
     * @param length 截取的数据长度
     * @return 合并后的新数组
     */
    private fun removeBytes(src: ByteArray, srcPos: Int, length: Int): ByteArray {
        val data3 = ByteArray(length)
        System.arraycopy(src, srcPos, data3, 0, length)
        return data3
    }
}