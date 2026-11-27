/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.transformer

import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.api.replace.RedirectionReplaceApi
import net.rwhps.asm.data.MethodTypeInfoValue
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/**
 *
 *
 * @date 2023/10/22 12:33
 * @author Dr (dr@der.kim)
 */
class ReplaceImpl {
    internal open class AbstractReplacePrivate : AbstractReplace() {
        override fun addMethodInsnNode(il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?) {
            if (methodTypeInfoValue?.replaceClass == null) {
                il.add(MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(RedirectionReplaceApi::class.java), RedirectionReplace.METHOD_NAME, RedirectionReplace.METHOD_DESC))
            } else {
                il.insertBefore(il.first,
                        FieldInsnNode(
                                Opcodes.GETSTATIC,
                                Type.getInternalName(methodTypeInfoValue.replaceClass),
                                "INSTANCE",
                                "L"+Type.getInternalName(methodTypeInfoValue.replaceClass)+";")
                )
                il.add(
                        MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                Type.getInternalName(methodTypeInfoValue.replaceClass),
                                RedirectionReplace.METHOD_NAME,
                                RedirectionReplace.METHOD_DESC
                        )
                )
            }
        }
    }

    internal class AllMethodsTransformer: AbstractReplacePrivate() {
        override fun redirectCast(cast: TypeInsnNode, methodTypeInfoValue: MethodTypeInfoValue?): InsnList {
            return InsnList()
        }

        override fun injectRedirection(cn: ClassNode, mn: MethodNode, il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?): Type {
            if (Type.getReturnType(mn.desc).sort == Type.VOID) {
                return Type.VOID_TYPE
            }
            return super.injectRedirection(cn, mn, il, methodTypeInfoValue)
        }

        override fun addMethodInsnNode(il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?) {
            il.add(MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(RedirectionReplaceApi::class.java), RedirectionReplace.METHOD_SPACE_NAME, RedirectionReplace.METHOD_DESC))
        }
    }

    internal class PartialMethodTransformer: AbstractReplacePrivate() {
    }
}