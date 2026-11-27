/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.redirections.replace

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.api.replace.RedirectionReplaceManager

class CastRedirectionReplace(private val manager: RedirectionReplaceManager): RedirectionReplace {
    @Throws(Throwable::class)
    override fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Any? {
        return if (type.isInstance(obj)) {
            obj
        } else {
            manager.invoke(obj, "<init> $desc", type, *args)
        }
    }
}
