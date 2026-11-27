/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.str

import net.rwhps.server.struct.map.ObjectMap

/**
 *
 *
 * @date 2024/6/23 下午7:00
 * @author Dr (dr@der.kim)
 */
object Parser {
    private val strData = ObjectMap<String, ()->String>().apply {
        put("\$ThreadName") { Thread.currentThread().name }
    }

    fun parseLog(text: String, vararg args: Any): String {
        return parse("{", "}", text, *args)
    }

    fun parseStr(text: String): String {
        var result = text
        strData.forEach { (t, u) ->
            result = result.replace(t, u())
        }
        return result
    }

    /**
     * 将字符串text中由openToken和closeToken组成的占位符依次替换为args数组中的值
     * @param openToken
     * @param closeToken
     * @param text
     * @param args
     * @return
     */
    private fun parse(openToken: String, closeToken: String, text: String, vararg args: Any): String {
        if (args.isEmpty() || text.isEmpty()) {
            return text
        }
        var argsIndex = 0

        val src = text.toCharArray()
        var offset = 0
        // search open token
        var start = text.indexOf(openToken, offset)
        if (start == -1) {
            return text
        }
        val builder = java.lang.StringBuilder()
        var expression: java.lang.StringBuilder? = null
        while (start > -1) {
            if (start > 0 && src[start - 1] == '\\') {
                // this open token is escaped. remove the backslash and continue.
                builder.appendRange(src, offset, offset + (start - offset - 1)).append(openToken)
                offset = start + openToken.length
            } else {
                // found open token. let's search close token.
                if (expression == null) {
                    expression = java.lang.StringBuilder()
                } else {
                    expression.setLength(0)
                }
                builder.appendRange(src, offset, offset + (start - offset))
                offset = start + openToken.length
                var end = text.indexOf(closeToken, offset)
                while (end > -1) {
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        expression.appendRange(src, offset, offset + (end - offset - 1)).append(closeToken)
                        offset = end + closeToken.length
                        end = text.indexOf(closeToken, offset)
                    } else {
                        expression.appendRange(src, offset, offset + (end - offset))
                        //offset = end + closeToken.length
                        break
                    }
                }
                if (end == -1) {
                    // close token was not found.
                    builder.appendRange(src, start, start + (src.size - start))
                    offset = src.size
                } else {
                    val value = if ((argsIndex <= args.size - 1)) (args[argsIndex].toString()) else expression.toString()
                    builder.append(value)
                    offset = end + closeToken.length
                    argsIndex++
                }
            }
            start = text.indexOf(openToken, offset)
        }
        if (offset < src.size) {
            builder.appendRange(src, offset, offset + (src.size - offset))
        }
        return builder.toString()
    }
}