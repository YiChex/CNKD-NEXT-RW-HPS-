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
import net.rwhps.asm.redirections.replace.def.BasicDataRedirections
import net.rwhps.asm.util.DescriptionUtil.getDesc
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.function.Supplier

class ProxyRedirectionReplace(private val manager: RedirectionReplaceManager, private val internalName: String): InvocationHandler {
    @Throws(Throwable::class)
    override fun invoke(proxy: Any, method: Method, argsIn: Array<Any>?): Any? {
        val desc = internalName + getDesc(method)
        var fb = Supplier<RedirectionReplace> { manager }

        if (desc.endsWith(";equals(Ljava/lang/Object;)Z")) {
            fb = Supplier { BasicDataRedirections.EQUALS }
        } else if (desc.endsWith(";hashCode()I")) {
            fb = Supplier { BasicDataRedirections.HASHCODE }
        }
        val args = arrayOfNulls<Any>(if (argsIn == null) 1 else argsIn.size + 1)

        // there's basically no way the method is static
        args[0] = proxy
        if (argsIn != null) {
            System.arraycopy(argsIn, 0, args, 1, argsIn.size)
        }
        return manager.invoke(desc, method.returnType, proxy, fb, *args)
    }
}
