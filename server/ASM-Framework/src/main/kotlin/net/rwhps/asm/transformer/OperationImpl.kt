/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.transformer

import net.rwhps.asm.data.MethodTypeInfoValue
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode

/**
 *
 *
 * @date 2024/1/10 21:35
 * @author Dr (dr@der.kim)
 */
internal class OperationImpl {
    class RemoveMethod : AbstractReplace() {
        override fun redirectMain(iterator: MutableListIterator<MethodNode>, mn: MethodNode, cn: ClassNode, methodTypeInfoValue: MethodTypeInfoValue?) {
            iterator.remove()
        }

        override fun addMethodInsnNode(il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?) {
        }
    }

    class RemoveMethodSynchronized : AbstractReplace() {
        /**
         * P0-2: 本变换器**保留**原方法体，只把 `MONITORENTER` 指令换成 `POP`，
         * 因此不得清空异常表 —— 原有的 handler 仍然有效，清空会静默删除异常处理。
         */
        override val replacesMethodBody: Boolean get() = false

        override fun redirectMain(iterator: MutableListIterator<MethodNode>, mn: MethodNode, cn: ClassNode, methodTypeInfoValue: MethodTypeInfoValue?) {
            val instructions: InsnList = mn.instructions
            for (insnNode in instructions.toArray()) {
                if (insnNode.opcode == Opcodes.MONITORENTER) {
                    // 将 monitorEnter 替换为 pop，即将监视器弹出堆栈
                    instructions[insnNode] = InsnNode(Opcodes.POP)
                }
            }

            // 移除 ACC_SYNCHRONIZED 标志
            mn.access = mn.access and Opcodes.ACC_SYNCHRONIZED.inv()
        }

        override fun addMethodInsnNode(il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?) {
        }
    }
}