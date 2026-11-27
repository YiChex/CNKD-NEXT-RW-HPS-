/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.util.fast

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.redirections.replace.def.BasicDataRedirections
import net.rwhps.asm.redirections.replace.def.BasicFunctionRedirections
import net.rwhps.asm.redirections.replace.def.BasicParamsValueRedirections

/**
 * Fast 基础数据, 避免走HashMap导致性能问题
 *
 * 当您使用 [BasicDataRedirections] 和 [BasicFunctionRedirections] 的内置 [RedirectionReplace] 时
 * ASM会自动为您替换成高性能方案
 *
 * @date 2023/12/23 19:59
 * @author Dr (dr@der.kim)
 */
object DefaultValueClass {
    fun coverPrivateValueClass(redirectionReplace: RedirectionReplace): Class<out RedirectionReplace>? {
        return when (redirectionReplace) {
            BasicDataRedirections.NULL -> BasicDataRedirections.ValueClass.NullValue::class.java
            BasicDataRedirections.BOOLEANT -> BasicDataRedirections.ValueClass.BooleanTrueValue::class.java
            BasicDataRedirections.BOOLEANF -> BasicDataRedirections.ValueClass.BooleanFalseValue::class.java
            BasicDataRedirections.BYTE -> BasicDataRedirections.ValueClass.ByteValue::class.java
            BasicDataRedirections.SHORT -> BasicDataRedirections.ValueClass.ShortValue::class.java
            BasicDataRedirections.INT -> BasicDataRedirections.ValueClass.IntValue::class.java
            BasicDataRedirections.LONG -> BasicDataRedirections.ValueClass.LongValue::class.java
            BasicDataRedirections.FLOAT -> BasicDataRedirections.ValueClass.FloatValue::class.java
            BasicDataRedirections.DOUBLE -> BasicDataRedirections.ValueClass.DoubleValue::class.java
            BasicDataRedirections.CHAR -> BasicDataRedirections.ValueClass.CharValue::class.java
            BasicDataRedirections.STRING -> BasicDataRedirections.ValueClass.StringValue::class.java
            else -> coverPrivateValueClassFunction(redirectionReplace)
        }
    }

    fun coverPrivateValueClassFunction(redirectionReplace: RedirectionReplace): Class<out RedirectionReplace>? {
        return when (redirectionReplace) {
            BasicFunctionRedirections.Nanos -> BasicFunctionRedirections.ValueClass.NanosValue::class.java
            BasicFunctionRedirections.NanosToMillis -> BasicFunctionRedirections.ValueClass.NanosToMillisValue::class.java
            BasicFunctionRedirections.Millis -> BasicFunctionRedirections.ValueClass.MillisValue::class.java
            BasicFunctionRedirections.Second -> BasicFunctionRedirections.ValueClass.SecondValue::class.java
            else -> coverPrivateValueClassParamsValue(redirectionReplace)
        }
    }

    fun coverPrivateValueClassParamsValue(redirectionReplace: RedirectionReplace): Class<out RedirectionReplace>? {
        return when (redirectionReplace) {
            BasicParamsValueRedirections.ReturnFirstParams -> BasicParamsValueRedirections.ValueClass.ReturnFirstParamsValue::class.java
            BasicParamsValueRedirections.ReturnSecondParams -> BasicParamsValueRedirections.ValueClass.ReturnSecondParamsValue::class.java
            else -> null
        }
    }
}