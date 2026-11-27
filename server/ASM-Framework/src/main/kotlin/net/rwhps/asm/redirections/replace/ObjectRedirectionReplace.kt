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
import net.rwhps.asm.util.DescriptionUtil.getDesc
import java.lang.reflect.Array
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.*

class ObjectRedirectionReplace(private val manager: RedirectionReplaceManager): RedirectionReplace {
    override operator fun invoke(obj: Any, desc: String, type: Class<*>, vararg args: Any?): Any? {
        var typePrivate = type

        return when {
            typePrivate.isInterface -> {
                return Proxy.newProxyInstance(typePrivate.classLoader, arrayOf(typePrivate), ProxyRedirectionReplace(manager, getDesc(typePrivate)))
            }
            typePrivate.isArray -> {
                var dimension = 0

                while (typePrivate.isArray) {
                    dimension++
                    typePrivate = typePrivate.componentType
                }

                val dimensions = IntArray(dimension)
                Arrays.fill(dimensions, 0)
                Array.newInstance(typePrivate, *dimensions)
            }
            Modifier.isAbstract(typePrivate.modifiers) -> {
                System.err.println("Can't return abstract class: $desc")
                null
            }
            else -> {
                try {
                    val constructor = typePrivate.getDeclaredConstructor()
                    constructor.isAccessible = true
                    constructor.newInstance()
                } catch (e: SecurityException) {
                    // D-30: keep the module's existing System.err convention, but add context
                    // so the trace is attributable. (ASM-Framework has no server logger.)
                    System.err.println("[ASM] Cannot instantiate fallback object for: $desc")
                    e.printStackTrace()
                    null
                } catch (e: ReflectiveOperationException) {
                    System.err.println("[ASM] Cannot instantiate fallback object for: $desc")
                    e.printStackTrace()
                    null
                }
            }
        }
    }
}
