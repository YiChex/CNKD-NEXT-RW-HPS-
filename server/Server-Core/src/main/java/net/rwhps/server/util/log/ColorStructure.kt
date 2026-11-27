/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log

/**
 *
 *
 * @date 2024/6/22 下午10:18
 * @author Dr (dr@der.kim)
 */
class ColorStructure(
    private val backGroundRgb: IntArray? = null,
    private val foreGroundRgb: IntArray? = null
) {
    private var backGround = (backGroundRgb != null)
    private var foreGround = (foreGroundRgb != null)

    var specialEfficacyBoldHighlight = false
    var specialEfficacyReduceBrightness = false
    var specialEfficacyItalics = false
    var specialEfficacyUnderline = false
    var specialEfficacyReverseColor = false

    var bit = Bit.Bit_8

    fun getColor(): String {
        if (!System.getProperty("rwhps.log.color","true").toBoolean()) {
            return ""
        }

        var result = ""

        if (specialEfficacyReverseColor) {
            result += "\u001b[7m"
        }
        if (specialEfficacyUnderline) {
            result += "\u001b[4m"
        }
        if (specialEfficacyItalics) {
            result += "\u001b[3m"
        }
        if (specialEfficacyReduceBrightness) {
            result += "\u001b[2m"
        }
        if (specialEfficacyBoldHighlight) {
            result += "\u001b[1m"
        }

        if (backGround) {
            result += "\u001B[48;5;${rgbTransformBit(backGroundRgb!!)}m"
        }
        if (foreGround) {
            result += "\u001B[38;5;${rgbTransformBit(foreGroundRgb!!)}m"
        }

        return result
    }

    private fun rgbTransformBit(rgb: IntArray): Int {
        return when (bit) {
            Bit.Bit_3 -> 0
            Bit.Bit_4 -> 0
            Bit.Bit_8 -> 16 + (rgb[0] * 5 / 255) * 36 + (rgb[1] * 5 / 255) * 6 + (rgb[2] * 5 / 255)
        }
    }

    private fun bitTransformRgb(bit: Int, bitIn: Bit): IntArray {
        return when (bitIn) {
            Bit.Bit_3 -> intArrayOf(0, 0, 0)
            Bit.Bit_4 -> intArrayOf(0, 0, 0)
            Bit.Bit_8 -> intArrayOf(((bit - 16) / 36) * 255 / 5, (((bit - 16) % 36) / 6) * 255 / 5, ((bit - 16) % 6) * 255 / 5)
        }
    }

    override fun toString(): String {
        return getColor()
    }

    companion object {
        enum class Bit {
            Bit_3,
            Bit_4,
            Bit_8
        }
    }
}