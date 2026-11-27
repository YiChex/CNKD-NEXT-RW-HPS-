/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.kotlin

/**
 *
 *
 * @date 2024/5/4 下午12:14
 * @author Dr (dr@der.kim)
 */
object JavaKotlinFix {
    fun className(classObj: Class<*>): Class<*> {
        return when (classObj.name) {
            "java.lang.Boolean"   -> Boolean::class.java
            "java.lang.Character" -> Char::class.java
            "java.lang.Byte"      -> Byte::class.java
            "java.lang.Short"     -> Short::class.java
            "java.lang.Integer"   -> Int::class.java
            "java.lang.Float"     -> Float::class.java
            "java.lang.Long"      -> Long::class.java
            "java.lang.Double"    -> Double::class.java
            "java.lang.Void"      -> Void.TYPE
            else -> classObj
        } as Class<*>
    }
}