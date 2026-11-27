/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.asm.util.transformer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/**
 * Utility for dealing with Instructions.
 */
internal object InstructionUtil {
    @JvmStatic
    fun loadType(type: Type): AbstractInsnNode {
        return when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE, Type.CHAR, Type.VOID -> FieldInsnNode(
                    Opcodes.GETSTATIC, getWrapper(type).internalName, "TYPE", Type.getDescriptor(Class::class.java)
            )
            else -> LdcInsnNode(type)
        }
    }

    @JvmStatic
    fun box(type: Type): MethodInsnNode? {
        return when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE -> {
                val wrap = getWrapper(type)
                MethodInsnNode(Opcodes.INVOKESTATIC, wrap.internalName, "valueOf", "(" + type.descriptor + ")" + wrap.descriptor, false)
            }
            else -> null
        }
    }

    fun getWrapper(type: Type): Type {
        return when (type.sort) {
            Type.BOOLEAN -> Type.getType("Ljava/lang/Boolean;")
            Type.CHAR -> Type.getType("Ljava/lang/Char;")
            Type.BYTE -> Type.getType("Ljava/lang/Byte;")
            Type.SHORT -> Type.getType("Ljava/lang/Short;")
            Type.INT -> Type.getType("Ljava/lang/Integer;")
            Type.FLOAT -> Type.getType("Ljava/lang/Float;")
            Type.LONG -> Type.getType("Ljava/lang/Long;")
            Type.DOUBLE -> Type.getType("Ljava/lang/Double;")
            Type.VOID -> Type.getType("Ljava/lang/Void;")
            else -> type
        }
    }

    fun isPrimitive(type: Type): Boolean {
        return getWrapper(type) !== type
    }

    @JvmStatic
    fun unbox(type: Type): InsnList {
        val name: String
        val il = InsnList()
        name = when (type.sort) {
            Type.BOOLEAN -> "booleanValue"
            Type.CHAR -> "charValue"
            Type.BYTE -> "byteValue"
            Type.SHORT -> "shortValue"
            Type.INT -> "intValue"
            Type.FLOAT -> "floatValue"
            Type.LONG -> "longValue"
            Type.DOUBLE -> "doubleValue"
            Type.VOID -> {
                il.add(InsnNode(Opcodes.POP))
                return il
            }
            else -> {
                il.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
                return il
            }
        }
        val o = getWrapper(type).internalName
        val s = "()" + type.descriptor
        il.add(TypeInsnNode(Opcodes.CHECKCAST, o))
        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, o, name, s))
        return il
    }

    @JvmStatic
    fun loadParam(type: Type, `var`: Int): VarInsnNode {
        return when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> VarInsnNode(Opcodes.ILOAD, `var`)
            Type.FLOAT -> VarInsnNode(Opcodes.FLOAD, `var`)
            Type.LONG -> VarInsnNode(Opcodes.LLOAD, `var`)
            Type.DOUBLE -> VarInsnNode(Opcodes.DLOAD, `var`)
            Type.VOID -> throw IllegalArgumentException("Can't load VOID type!")
            else -> VarInsnNode(Opcodes.ALOAD, `var`)
        }
    }

    @JvmStatic
    fun makeReturn(type: Type): InsnNode {
        return when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> InsnNode(
                    Opcodes.IRETURN
            )
            Type.FLOAT -> InsnNode(Opcodes.FRETURN)
            Type.LONG -> InsnNode(Opcodes.LRETURN)
            Type.DOUBLE -> InsnNode(Opcodes.DRETURN)
            Type.VOID -> InsnNode(Opcodes.RETURN)
            else -> InsnNode(Opcodes.ARETURN)
        }
    }
}