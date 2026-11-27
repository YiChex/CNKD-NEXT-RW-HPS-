/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

@file:JvmName("InlineUtils") @file:JvmMultifileClass

package net.rwhps.server.util.inline

import net.rwhps.server.util.ReflectionUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection
 * @author Dr (dr@der.kim)
 */

/**
 * Attempt to find a [Method] on the supplied class with the supplied name
 * and parameter types. Searches all superclasses up to [Object].
 * Returns `null` if no [Method] can be found.
 *
 * @receiver Class<*>
 * @param name the name of the method
 * @param paramTypes the parameter types of the method
 * @return the Method object
 */
fun Class<*>.findMethod(name: String, vararg paramTypes: Class<*>): Method? {
    return ReflectionUtils.findMethod(this, name, *paramTypes)
}

/**
 * Make the given field accessible, explicitly setting it accessible if
 * necessary. The [Field.setAccessible] method is only called
 * when actually necessary, to avoid unnecessary conflicts with a JVM
 * SecurityManager (if active).
 *
 * @receiver Class<*>
 * @param name String
 * @param type Class<*>?
 * @return Field?
 */
fun Class<*>.findField(name: String, type: Class<*>? = null): Field? {
    return ReflectionUtils.findField(this, name, type)?.also {
        ReflectionUtils.makeAccessible(it)
    }
}

/**
 * Get [ClassLoader] in [Any]
 * Get the [Class] for [ClassLoader]
 * When [ClassLoader] is `null`, use the default loader
 *
 * @receiver String
 * @param loader ClassLoader?
 * @return Class<*>?
 */
fun String.toClassAutoLoader(obj: Any): Class<*>? {
    return if (obj is Class<*>) {
        this.toClass(obj.classLoader)
    } else if (obj is ClassLoader) {
        this.toClass(obj)
    } else {
        this.toClass(obj.javaClass.classLoader)
    }
}

/**
 * Get the [Class] for [ClassLoader]
 * When [ClassLoader] is `null`, use the default loader
 *
 * @receiver String
 * @param loader ClassLoader?
 * @return Class<*>?
 */
fun String.toClass(loader: ClassLoader?): Class<*>? {
    return loader.ifNullResult({
        Class.forName(this)
    }) {
        Class.forName(this, true, loader)
    }
}

/**
 * Obtain an accessible constructor for the given class and parameters.
 *
 * @receiver Class<T>
 * @param parameterTypes the parameter types of the desired constructor
 * @return the constructor reference
 * @throws NoSuchMethodException if no such constructor exists
 */
@Throws(NoSuchMethodException::class)
fun <T> Class<T>.accessibleConstructor(vararg parameterTypes: Class<*>): Constructor<T> {
    return ReflectionUtils.accessibleConstructor(this, *parameterTypes)
}

fun Class<*>.forName(): String {
    return this.toString().replace("class ", "")
}
