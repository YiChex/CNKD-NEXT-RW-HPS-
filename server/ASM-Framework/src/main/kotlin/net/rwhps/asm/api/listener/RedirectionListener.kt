/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.api.listener

/**
 * @author Dr (dr@der.kim)
 */
fun interface RedirectionListener {
    @Throws(Throwable::class)
    operator fun invoke(obj: Any, desc: String, vararg args: Any?)

    companion object {
        const val METHOD_NAME = "invoke"
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V"
    }
}
