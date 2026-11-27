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
import net.rwhps.asm.api.replace.RedirectionReplace
import net.rwhps.asm.api.replace.RedirectionReplaceApi
import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.asm.util.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Modifier
import java.util.*

/**
 * A given [ClassNode] will be transformed in the following ways:
 *
 * -if it's a module a `requires headlessmc.lwjgl` will be added.
 *
 * -the no-args constructor will be made public or created if necessary
 *
 * -Every method body will have its code removed and will call
 * [RedirectionReplaceApi.invoke].
 *
 * -the constructor will keep its body, a [RedirectionReplaceApi.invoke] will be added before all RETURN instructions in
 * the constructor. Class casts in constructors will be redirected.
 *
 * -if the class is abstract and not an interface the abstract modifier
 * will be removed.
 *
 * -All abstract and native methods will be turned into normal methods, with
 * their body transformed as described above.
 *
 * WARN :
 * 唯一的问题在于 :
 * TODO: 需要支持 <init> <clinit> 的 Class 替代 : 我认为没啥用
 *
 * @date 2023/10/22 10:47
 * @author Dr (dr@der.kim)
 */
internal abstract class AbstractReplace : Transformer {
    /**
     * P0-2: 本次变换是否**替换**了被处理方法的整个方法体。
     *
     * [redirect] 末尾会清空 `tryCatchBlocks`。这对「替换整个方法体」的变换器是**正确且必要**的
     * （旧异常表里的 handler 指向已被丢弃的指令）；但对「保留原方法体、仅插入一次调用」的变换器
     * （[ListenerImpl.ListenerPartialMethodsTransformer] 的 `insertBefore`、
     * [OperationImpl.RemoveMethodSynchronized] 仅把 `MONITORENTER` 换成 `POP`）是**破坏性的**：
     * 它会静默删除仍然有效的异常处理器。
     *
     * 因此默认值取「替换方法体」（`true`），保留 [ReplaceImpl] 既有行为不变；
     * 保留方法体的变换器必须 override 为 `false`。
     */
    protected open val replacesMethodBody: Boolean get() = true

    override fun transform(classNode: ClassNode, methodTypeInfoValueList: ArrayList<MethodTypeInfoValue>?) {
        // transformModule
        if (classNode.module != null) {
            classNode.module.visitRequire("rwhps.asm", Opcodes.ACC_MANDATED, null)
            classNode.module.access = classNode.module.access or Opcodes.ACC_OPEN
        }

        if (classNode.access and Opcodes.ACC_MODULE == 0) {
            patchClass(classNode, classNode.access and Opcodes.ACC_INTERFACE != 0, methodTypeInfoValueList)
            // make all non-static fields non-final
            for (fn in classNode.fields) {
                if (fn.access and Opcodes.ACC_STATIC == 0) {
                    fn.access = fn.access and Opcodes.ACC_FINAL.inv()
                }
            }
        }
    }

    private fun patchClass(cn: ClassNode, isInterface: Boolean, methodTypeInfoValueList: ArrayList<MethodTypeInfoValue>?) {
        var shouldAddNoArgsCtr = true
        // TODO: while we can implement all abstract methods which are directly
        //  present in the class, methods inherited from an interface or another
        //  abstract class may pose a problem.
        //  We could at least warn every time a formerly abstract class is being
        //  instantiated?
        if (!isInterface) {
            cn.access = cn.access and Opcodes.ACC_ABSTRACT.inv()
        }

        val iterator = cn.methods.listIterator()
        for (mn in iterator) {
            if (mn.name == "<init>" && mn.desc == "()V") {
                // TODO: check this
                mn.access = mn.access or Opcodes.ACC_PUBLIC
                mn.access = mn.access and (Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv()
                shouldAddNoArgsCtr = false
            }
            if (!isInterface || mn.access and Opcodes.ACC_STATIC != 0 || mn.access and Opcodes.ACC_ABSTRACT == 0) {
                if (methodTypeInfoValueList.isNullOrEmpty()) {
                    // Filter out the all method
                    redirect(iterator, mn, cn, null)
                } else {
                    // Filter out the specified method
                    for (parameter in methodTypeInfoValueList) {
                        if (mn.name == parameter.methodName && mn.desc == parameter.methodParamsInfo) {
                            redirect(iterator, mn, cn, parameter)
                        }
                    }
                }
            }
        }

        // TODO: super class containing lwjgl is no guarantee for it to
        //  have a default constructor!
        if (shouldAddNoArgsCtr && !isInterface &&
            (cn.superName == null ||
            cn.superName.lowercase(Locale.getDefault()).contains("lwjgl") ||
            cn.superName == Type.getInternalName(Any::class.java))
        ) {
            // Add NoArgs Constructor for the ObjectRedirection
            val mn = MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, arrayOfNulls(0))
            val il = InsnList()
            mn.instructions = il
            il.add(VarInsnNode(Opcodes.ALOAD, 0))
            il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, cn.superName, "<init>", "()V", false))
            injectRedirection(cn, mn, il, null)
            il.add(InsnNode(Opcodes.RETURN))
            cn.methods.add(mn)
            mn.visitMaxs(0, 0)
        }
    }

    /**
     * 向Class的方法进行修改
     *
     * @param mn MethodNode
     * @param cn ClassNode
     * @param methodTypeInfoValue 修改的目标方法信息和需要指向的 Class 方法, null时为不指向, 为全局
     */
    private fun redirect(iterator: MutableListIterator<MethodNode>, mn: MethodNode, cn: ClassNode, methodTypeInfoValue: MethodTypeInfoValue?) {
        if ("<init>" == mn.name && mn.desc.endsWith(")V")) {
            // There's not really a way to determine when this() or super()
            // is called in a constructor, because of that we'll just inject
            // the RedirectionManager at every RETURN instruction.
            // This hasn't become a problem, yet...
            for (insnNode in mn.instructions) {
                if (insnNode is TypeInsnNode && insnNode.opcode == Opcodes.CHECKCAST) {
                    mn.instructions.insertBefore(insnNode, redirectCast(insnNode, methodTypeInfoValue))
                } else if (insnNode.opcode == Opcodes.RETURN) {
                    val il = InsnList()
                    injectRedirection(cn, mn, il, methodTypeInfoValue)
                    mn.instructions.insertBefore(insnNode, il)
                }
            }
        } else {
            redirectMain(iterator, mn, cn, methodTypeInfoValue)
        }
        // P0-2: 仅当方法体被替换时才清空异常表。保留方法体的变换器（redirectL /
        // RemoveMethodSynchronized）必须保留原有 tryCatchBlocks，否则会静默删除仍然有效的异常处理器。
        if (replacesMethodBody) {
            mn.tryCatchBlocks = ArrayList()
        }
        // localVariables / parameters 只是调试信息，不影响语义，保持原有清空行为以缩小改动面。
        mn.localVariables = ArrayList()
        mn.parameters = ArrayList()
        mn.access = mn.access and Opcodes.ACC_NATIVE.inv() and Opcodes.ACC_ABSTRACT.inv()
    }

    protected open fun redirectMain(iterator: MutableListIterator<MethodNode>, mn: MethodNode, cn: ClassNode, methodTypeInfoValue: MethodTypeInfoValue?) {
        val il = InsnList()
        il.add(InstructionUtil.makeReturn(injectRedirection(cn, mn, il, methodTypeInfoValue)))
        mn.instructions = il
    }

    /**
     * 有泛型擦除的情况下
     *
     * @param cast TypeInsnNode
     * @param methodTypeInfoValue MethodTypeInfoValue?
     * @return InsnList
     */
    protected open fun redirectCast(cast: TypeInsnNode, methodTypeInfoValue: MethodTypeInfoValue?): InsnList {
        val il = InsnList()
        il.add(LdcInsnNode(RedirectionReplace.CAST_PREFIX + cast.desc))
        // TODO: does this really cover all cases?
        if (cast.desc.startsWith("[")) {
            il.add(LdcInsnNode(Type.getType(cast.desc)))
        } else {
            il.add(LdcInsnNode(Type.getType("L" + cast.desc + ";")))
        }
        loadArgArray("()V", il, false) // create empty array
        // 在这里修改全部的方法 并代理
        addMethodInsnNode(il, methodTypeInfoValue)
        return il
    }

    /**
     * 正常情况
     *
     * @param cn ClassNode
     * @param mn MethodNode
     * @param il InsnList
     * @param methodTypeInfoValue MethodTypeInfoValue?
     * @return Type
     */
    protected open fun injectRedirection(cn: ClassNode, mn: MethodNode, il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?): Type {
        val isStatic = Modifier.isStatic(mn.access)
        if (isStatic) {
            il.add(LdcInsnNode(Type.getType("L" + cn.name + ";")))
        } else {
            il.add(VarInsnNode(Opcodes.ALOAD, 0))
        }
        il.add(LdcInsnNode("L" + cn.name + ";" + mn.name + mn.desc))
        val returnType = Type.getReturnType(mn.desc)
        il.add(InstructionUtil.loadType(returnType))
        loadArgArray(mn.desc, il, isStatic)

        addMethodInsnNode(il, methodTypeInfoValue)
        il.add(InstructionUtil.unbox(returnType))
        return returnType
    }

    protected abstract fun addMethodInsnNode(il: InsnList, methodTypeInfoValue: MethodTypeInfoValue?)

    protected fun loadArgArray(desc: String?, il: InsnList, isStatic: Boolean) {
        val args = Type.getArgumentTypes(desc)
        il.add(LdcInsnNode(args.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, Type.getInternalName(Any::class.java)))

        var v = if (isStatic) 0 else 1
        for (i in args.indices) {
            il.add(InsnNode(Opcodes.DUP))
            val type = args[i]
            il.add(LdcInsnNode(i))
            il.add(InstructionUtil.loadParam(type, v))
            if (type.sort == Type.DOUBLE || type.sort == Type.LONG) {
                // double and long take up two registers, so we skip one
                v++
            }
            val boxing = InstructionUtil.box(type)
            if (boxing != null) {
                il.add(boxing)
            }
            il.add(InsnNode(Opcodes.AASTORE))
            v++
        }
    }
}