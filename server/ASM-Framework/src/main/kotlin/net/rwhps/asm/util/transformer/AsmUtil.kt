/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.util.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

internal object AsmUtil {
    fun read(clazz: ByteArray?, vararg flags: Int): ClassNode {
        val result = ClassNode()
        val reader = ClassReader(clazz)
        reader.accept(result, toFlag(*flags))
        return result
    }

    fun write(loader: ClassLoader?, classNode: ClassNode, vararg flags: Int): ByteArray {
        val writer = SafeClassWriter(null, loader, toFlag(*flags))
        classNode.accept(writer)
        return writer.toByteArray()
    }

    private fun toFlag(vararg flags: Int): Int {
        var flag = 0
        for (f in flags) {
            flag = flag or f
        }
        return flag
    }
}