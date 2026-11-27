/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.transformer

import net.rwhps.asm.api.Transformer
import net.rwhps.asm.api.wrap.RedirectionWrapper
import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.data.manager.WrapRedirectionsDataManager
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Modifier

/**
 * FIX-LOOP-GUARD：把**整个原方法体**包进 `try { <原指令> } catch (Throwable t) { <处理器>; <默认返回> }`。
 *
 * 与 [ListenerImpl.ListenerPartialMethodsTransformer] 的本质区别：
 * - `ListenerImpl` 只在方法**首/尾**插入一次调用（`insertBefore`），**方法体本身仍在 try 范围之外**，
 *   因此**无法**捕获方法体抛出的异常；
 * - 本变换器插入 `TryCatchBlockNode`，其 `start..end` **覆盖全部原指令**，因此方法体抛出的
 *   任何 `Throwable` 都会先进入我们的 handler。
 *
 * 这是"一帧异常 = 主循环线程死亡（永久僵尸实例）"的根治手段。
 *
 * ### 为什么是独立的 [Transformer] 而不是 [AbstractReplace] 子类
 * [AbstractReplace.patchClass] 会顺带：去掉 `ACC_ABSTRACT`、把非静态字段改成非 final、
 * 补无参构造、清空 `localVariables`/`parameters`。这些是「替换整个方法体」变换器的语义，
 * 对「只加一层 try/catch」是**破坏性的副作用**。因此这里实现纯净的 [Transformer]。
 *
 * ### 帧（StackMapTable）
 * 新增 handler 改变了控制流，必须重算帧。框架的 [net.rwhps.asm.agent.AsmAgent.transform]
 * 已经用 `ClassWriter.COMPUTE_FRAMES` 写出（见 `AsmAgent.kt:113`），
 * 且 [net.rwhps.asm.util.transformer.SafeClassWriter] 覆写了 `getCommonSuperClass`
 * （不加载类，只读 `.class` 资源），所以**无需修改 writer 标志**。
 *
 * @author FIX-LOOP-GUARD
 */
internal class WrapMethodBodyTransformer : Transformer {

    override fun transform(classNode: ClassNode, methodTypeInfoValueList: ArrayList<MethodTypeInfoValue>?) {
        if (methodTypeInfoValueList.isNullOrEmpty()) {
            return
        }
        for (mn in classNode.methods) {
            for (parameter in methodTypeInfoValueList) {
                if (mn.name != parameter.methodName || mn.desc != parameter.methodParamsInfo) {
                    continue
                }
                wrapMethodBody(classNode, mn, parameter)
            }
        }
    }

    private fun wrapMethodBody(cn: ClassNode, mn: MethodNode, parameter: MethodTypeInfoValue) {
        // 无方法体（abstract/native）没有指令可包；构造器里 `this` 可能未初始化，包装会让
        // 默认返回值路径破坏 <init> 契约 —— 两者都明确跳过。
        if (mn.instructions == null || mn.instructions.size() == 0) return
        if (mn.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return
        if (mn.name == "<init>" || mn.name == "<clinit>") return

        val wrapperClass = WrapRedirectionsDataManager.wrapData[parameter.desc] ?: return
        val wrapperInternalName = Type.getInternalName(wrapperClass)

        // FIX-E 幂等守卫：烘焙缓存 (.bin) 里的类可能已注入过，运行期 Agent 会对同一方法再次变换。
        // 判据用完整标记串（"LW" 前缀区别于 ListenerImpl 的 "LS"/"LE"），
        // 与 wrapMethodBody 实际写入的 LDC 常量共用 wrapMarker()，避免判据漂移。
        val marker = wrapMarker(cn, mn)
        if (alreadyWrapped(mn, marker)) {
            return
        }

        val isStatic = Modifier.isStatic(mn.access)
        val returnType = Type.getReturnType(mn.desc)
        val throwableSlot = maxOf(mn.maxLocals, firstFreeSlot(mn))

        // 1) 在首/尾各放一个 Label，圈定"原方法体"范围。
        //    注意：必须在**插入 handler 之前**完成，否则 handler 会被圈进 try 范围导致自捕获死循环。
        val start = LabelNode()
        val end = LabelNode()
        mn.instructions.insert(start)
        mn.instructions.add(end)

        val handler = LabelNode()
        val rethrow = LabelNode()

        val il = InsnList()
        il.add(handler)
        // 保存异常
        il.add(VarInsnNode(Opcodes.ASTORE, throwableSlot))
        // 幂等标记（栈中性：LDC + POP）
        il.add(LdcInsnNode(marker))
        il.add(InsnNode(Opcodes.POP))
        // JVM 致命错误必须重抛（与 Application.kt:136 / Threads.kt:127 / PluginsLoad.kt:326 约定一致）
        il.add(VarInsnNode(Opcodes.ALOAD, throwableSlot))
        il.add(TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/VirtualMachineError"))
        il.add(JumpInsnNode(Opcodes.IFNE, rethrow))
        il.add(VarInsnNode(Opcodes.ALOAD, throwableSlot))
        il.add(TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/ThreadDeath"))
        il.add(JumpInsnNode(Opcodes.IFNE, rethrow))
        // 调用处理器：GETSTATIC INSTANCE; <obj>; <desc>; <throwable>; INVOKEVIRTUAL invoke
        il.add(
            FieldInsnNode(
                Opcodes.GETSTATIC,
                wrapperInternalName,
                "INSTANCE",
                "L$wrapperInternalName;"
            )
        )
        if (isStatic) {
            il.add(InsnNode(Opcodes.ACONST_NULL))
        } else {
            il.add(VarInsnNode(Opcodes.ALOAD, 0))
        }
        il.add(LdcInsnNode(parameter.desc))
        il.add(VarInsnNode(Opcodes.ALOAD, throwableSlot))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                wrapperInternalName,
                RedirectionWrapper.METHOD_NAME,
                RedirectionWrapper.METHOD_DESC,
                false
            )
        )
        // 按返回类型给出默认值，让调用方继续
        il.add(defaultReturn(returnType))
        // 重抛分支
        il.add(rethrow)
        il.add(VarInsnNode(Opcodes.ALOAD, throwableSlot))
        il.add(InsnNode(Opcodes.ATHROW))

        mn.instructions.add(il)

        // 2) 注册覆盖**全部原指令**的 handler。
        //    handler 自身位于 end 之后 => 不在 try 范围内 => 不会自我捕获。
        mn.tryCatchBlocks.add(TryCatchBlockNode(start, end, handler, "java/lang/Throwable"))

        // 3) 新增了局部变量槽位；COMPUTE_FRAMES 会重算，这里同步一次以保证 ClassNode 自洽。
        mn.maxLocals = maxOf(mn.maxLocals, throwableSlot + 1)
    }

    /** 本次注入会写入的标记常量。 */
    private fun wrapMarker(cn: ClassNode, mn: MethodNode): String {
        return "LW" + cn.name + ";" + mn.name + mn.desc
    }

    /** [mn] 的指令流中是否已存在 [marker]（即方法体已被包装过）。 */
    private fun alreadyWrapped(mn: MethodNode, marker: String): Boolean {
        var insn: AbstractInsnNode? = mn.instructions.first
        while (insn != null) {
            if (insn is LdcInsnNode && insn.cst == marker) {
                return true
            }
            insn = insn.next
        }
        return false
    }

    /** 参数之后第一个空闲局部变量槽位。 */
    private fun firstFreeSlot(mn: MethodNode): Int {
        var slot = if (Modifier.isStatic(mn.access)) 0 else 1
        for (type in Type.getArgumentTypes(mn.desc)) {
            slot += type.size
        }
        return slot
    }

    /** 非 void 返回类型需要压入默认值再 return。 */
    private fun defaultReturn(type: Type): InsnList {
        val il = InsnList()
        when (type.sort) {
            Type.VOID -> il.add(InsnNode(Opcodes.RETURN))
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                il.add(InsnNode(Opcodes.ICONST_0))
                il.add(InsnNode(Opcodes.IRETURN))
            }
            Type.LONG -> {
                il.add(InsnNode(Opcodes.LCONST_0))
                il.add(InsnNode(Opcodes.LRETURN))
            }
            Type.FLOAT -> {
                il.add(InsnNode(Opcodes.FCONST_0))
                il.add(InsnNode(Opcodes.FRETURN))
            }
            Type.DOUBLE -> {
                il.add(InsnNode(Opcodes.DCONST_0))
                il.add(InsnNode(Opcodes.DRETURN))
            }
            else -> {
                il.add(InsnNode(Opcodes.ACONST_NULL))
                il.add(InsnNode(Opcodes.ARETURN))
            }
        }
        return il
    }
}
