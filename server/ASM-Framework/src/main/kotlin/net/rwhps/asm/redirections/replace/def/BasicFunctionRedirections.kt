/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.redirections.replace.def

import net.rwhps.asm.api.replace.RedirectionReplace

/**
 *
 *
 * @date 2023/12/25 15:15
 * @author Dr (dr@der.kim)
 */
object BasicFunctionRedirections {
    val Nanos = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.nanoTime() }
    val NanosToMillis = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.nanoTime() }
    val Millis = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.currentTimeMillis() }
    val Second = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.currentTimeMillis()/1000 }

    object ValueClass {
        object NanosValue : RedirectionReplace {
            override fun invoke(obj: Any, desc: String, type: Class<*>, args: Array<out Any?>): Long {
                return System.nanoTime()
            }
        }

        object NanosToMillisValue : RedirectionReplace {
            override fun invoke(obj: Any, desc: String, type: Class<*>, args: Array<out Any?>): Long {
                return System.nanoTime()/1000000L
            }
        }

        object MillisValue : RedirectionReplace {
            override fun invoke(obj: Any, desc: String, type: Class<*>, args: Array<out Any?>): Long {
                return System.currentTimeMillis()
            }
        }

        object SecondValue : RedirectionReplace {
            override fun invoke(obj: Any, desc: String, type: Class<*>, args: Array<out Any?>): Long {
                return System.currentTimeMillis() / 1000L
            }
        }
    }
}