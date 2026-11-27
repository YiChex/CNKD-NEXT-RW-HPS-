/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.transformer

import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.asm.api.listener.RedirectionListenerApi
import net.rwhps.asm.data.MethodTypeInfoValue
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Modifier

/**
 * 监听Class实现
 *
 * 为 [Class] 的 [java.lang.reflect.Method] 注入监听方法
 *
 * @date 2023/10/22 12:33
 * @author Dr (dr@der.kim)
 */
class ListenerImpl {
    internal class ListenerPartialMethodsTransformer: AbstractReplace() {
        /**
         * FIX-A (P0-2) 接口约定：本变换器**保留**原方法体（只在方法首/尾插入一次调用），
         * 因此不得清空 `tryCatchBlocks` —— 原有异常处理器仍然有效。
         * 默认值 `true` 是 [ReplaceImpl]「替换整个方法体」的语义。
         */
        override val replacesMethodBody: Boolean get() = false

        override fun redirectMain(iterator: MutableListIterator<MethodNode>, mn: MethodNode, cn: ClassNode, methodTypeInfoValue: MethodTypeInfoValue?) {
            if (methodTypeInfoValue == null) {
                throw NullPointerException("redirectMain: MethodTypeInfoValue Null")
            }

            // FIX-E (P0-1) 幂等守卫。
            // 烘焙缓存 (GameModularReusableClass.bin) 里的类已经注入过 hook，而运行期 Agent 会对
            // 同一方法再次变换；没有守卫时每次变换都会再插一条调用（实测 1 -> 2 -> 3）。
            // 判据用**完整标记串**（含 "LS"/"LE" 后缀），而不是只比较 owner+name+desc：
            // "LS"（before）与 "LE"（end）是两种不同的注入，只比较前缀会把它们误判成同一个，
            // 从而漏掉其中一种监听。
            if (alreadyInjected(mn, listenerMarker(cn, mn, methodTypeInfoValue))) {
                return
            }

            val il = InsnList()
            injectRedirection(cn, mn, il, methodTypeInfoValue)

            if (methodTypeInfoValue.listenerBefore) {
                mn.instructions.insertBefore(mn.instructions.first, il)
            } else {
                mn.instructions.insertBefore(mn.instructions.last, il)
            }
        }

        /**
         * 本次注入会写入的标记常量。
         *
         * 必须与 [injectRedirection] 中生成的 `LDC` 完全一致 —— 两者共用此函数，
         * 避免「守卫判据」与「实际写入的标记」发生漂移。
         */
        private fun listenerMarker(cn: ClassNode, mn: MethodNode, methodTypeInfoValue: MethodTypeInfoValue): String {
            val suffix = if (methodTypeInfoValue.listenerBefore) "LS" else "LE"
            return "L" + cn.name + ";" + mn.name + mn.desc + suffix
        }

        /** [mn] 的指令流中是否已经存在 [marker] 常量（即该 hook 已被注入过）。 */
        private fun alreadyInjected(mn: MethodNode, marker: String): Boolean {
            var insn: AbstractInsnNode? = mn.instructions.first
            while (insn != null) {
                if (insn is LdcInsnNode && insn.cst == marker) {
                    return true
                }
                insn = insn.next
            }
            return false
        }

        override fun injectRedirection(cn: ClassNode, mn: MethodNode, il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?): Type {
            val isStatic = Modifier.isStatic(mn.access)
            if (isStatic) {
                il.add(LdcInsnNode(Type.getType("L" + cn.name + ";")))
            } else {
                il.add(VarInsnNode(Opcodes.ALOAD, 0))
            }
            il.add(LdcInsnNode(listenerMarker(cn, mn, methodTypeInfoValue!!)))
            val returnType = Type.VOID_TYPE
            loadArgArray(mn.desc, il, isStatic)

            addMethodInsnNode(il, methodTypeInfoValue)
            return returnType
        }

        override fun addMethodInsnNode(il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?) {
            if (methodTypeInfoValue?.listenerClass == null) {
                // 如果不指定, 那么默认走 [RedirectionListenerApi]
                il.add(MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(RedirectionListenerApi::class.java), RedirectionListener.METHOD_NAME, RedirectionListener.METHOD_DESC))
            } else {
                /*
                    如果指定, 就需要一个实例, 但是因为不能静态继承
                    那么我们规定使用 Kotlin 的 object 方法
                    即在 Java 中需要写一个 INSTANCE 变量
                 */
                il.insertBefore(il.first, FieldInsnNode(
                    Opcodes.GETSTATIC,
                    Type.getInternalName(methodTypeInfoValue.listenerClass),
                    "INSTANCE",
                    "L"+Type.getInternalName(methodTypeInfoValue.listenerClass)+";"
                ))
                il.add(MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(methodTypeInfoValue.listenerClass),
                    RedirectionListener.METHOD_NAME,
                    RedirectionListener.METHOD_DESC
                ))
            }
        }
    }
}