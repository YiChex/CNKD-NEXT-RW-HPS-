/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.internal.kotlin

/**
 * Kotlin的一些离谱玩法, 个人使用/看
 *
 * @date 2024/2/15 11:34
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
class SuppressProMax {
    fun a() {
        val some: List<*> = emptyList<Nothing>()
        @Suppress("UNCHECKED_CAST")
        some as List<String>
    }

    fun npeError() {
        println((null as String?).let { it!!.length })
    }

    fun arrayPro() {
        fun some(any: Any) {
            if (any is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val arr = any as Array<String>
                println(arr.size)
            }
        }
    }

    private var lateInitVal: String = ""
        private set
    fun lateinitVal() {
        lateInitVal = "1"
        println(lateInitVal)
        lateInitVal = "2"
        println(lateInitVal)
    }
}
