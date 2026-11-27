/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.fast

import net.rwhps.asm.api.replace.RedirectionReplace

/**
 *
 * 在高并发中快速返回固定 [Long] 数据, 避免在 ASM 框架中被 [HashMap] 影响效率
 *
 * @date 2024/1/11 21:36
 * @author Dr (dr@der.kim)
 */
class FastLongValueClass {
    object Long_1_Value : RedirectionReplace {
        override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Long {
            return 1L
        }
    }

    object Long_1000_Value : RedirectionReplace {
        override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Long {
            return 1000L
        }
    }
}