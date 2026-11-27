/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/deng-rui/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.asm.agent

import net.rwhps.asm.api.Transformer
import net.rwhps.asm.data.manager.ListenerRedirectionsDataManager
import net.rwhps.asm.data.manager.RemoveRedirectionsDataManager
import net.rwhps.asm.data.manager.ReplaceRedirectionsDataManager
import net.rwhps.asm.data.manager.WrapRedirectionsDataManager
import net.rwhps.asm.func.Find
import net.rwhps.asm.transformer.ListenerImpl
import net.rwhps.asm.transformer.OperationImpl
import net.rwhps.asm.transformer.ReplaceImpl
import net.rwhps.asm.transformer.WrapMethodBodyTransformer
import net.rwhps.asm.util.transformer.AsmUtil
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.FileOutputStream
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.IllegalClassFormatException
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * A JavaAgent calling the [ReplaceImpl.AllMethodsTransformer] and [ReplaceImpl.PartialMethodTransformer].
 */
class AsmAgent: ClassFileTransformer, AsmCore() {

    private val transformer: Transformer = ReplaceImpl.AllMethodsTransformer()
    private val partialMethodTransformer: Transformer = ReplaceImpl.PartialMethodTransformer()
    private val listenerMethodTransformer: Transformer = ListenerImpl.ListenerPartialMethodsTransformer()
    private val removeMethodTransformer: Transformer = OperationImpl.RemoveMethod()
    private val removeMethodSynchronizedTransformer: Transformer = OperationImpl.RemoveMethodSynchronized()
    /** FIX-LOOP-GUARD: 把方法体包进 try/catch，使方法体异常不再杀死调用线程。 */
    private val wrapMethodBodyTransformer: Transformer = WrapMethodBodyTransformer()

    init {
        setAgent(this)
    }

    /**
     * Transforms the given class file and returns a new replacement class file.
     * This method is invoked when the {@link Module} bearing {@link
     * ClassFileTransformer#transform(Module,ClassLoader,String,Class,ProtectionDomain,byte[])
     * transform} is not overridden.
     *
     * @param loader                the defining loader of the class to be transformed,
     *                              may be {@code null} if the bootstrap loader
     * @param className             the name of the class in the internal form of fully
     *                              qualified class and interface names as defined in
     *                              <i>The Java Virtual Machine Specification</i>.
     *                              For example, <code>"java/util/List"</code>.
     * @param classBeingRedefined   if this is triggered by a redefine or retransform,
     *                              the class being redefined or retransformed;
     *                              if this is a class load, {@code null}
     * @param protectionDomain      the protection domain of the class being defined or redefined
     * @param classfileBuffer       the input byte buffer in class file format - must not be modified
     *
     * @throws IllegalClassFormatException
     *         if the input does not represent a well-formed class file
     * @return a well-formed class file buffer (the result of the transform),
     *         or {@code null} if no transform is performed
     */
    @Throws(IllegalClassFormatException::class)
    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray {
        // 使用中文
        var node: ClassNode? = null

        if (ReplaceRedirectionsDataManager.allClassPathCache.contains(className) ||
            ReplaceRedirectionsDataManager.allReplaceFindPacketName.forFind(className)
        ) {
            node = AsmUtil.read(classfileBuffer)
            transformer.transform(node)
        }

        if (ReplaceRedirectionsDataManager.allRemoveSyncClassPathCache.contains(className)) {
            removeMethodSynchronizedTransformer.transform(node!!)
        }

        if (ReplaceRedirectionsDataManager.partialClassPathCache.contains(className)) {
            if (node == null) {
                node = AsmUtil.read(classfileBuffer)
            }
            partialMethodTransformer.transform(node, ReplaceRedirectionsDataManager.partialReplaceMethodName[className])
        }

        if (ListenerRedirectionsDataManager.partialClassPathCache.contains(className)) {
            if (node == null) {
                node = AsmUtil.read(classfileBuffer)
            }
            listenerMethodTransformer.transform(node, ListenerRedirectionsDataManager.partialListenerMethodName[className])
        }

        if (RemoveRedirectionsDataManager.partialClassPathCache.contains(className)) {
            if (node == null) {
                node = AsmUtil.read(classfileBuffer)
            }
            removeMethodTransformer.transform(node, RemoveRedirectionsDataManager.partialListenerMethodName[className])
        }

        // FIX-LOOP-GUARD: 必须放在 redirectL 之后。
        // redirectL 把 FPSSleepRedirections 插在方法首部；若先包装再插监听，监听调用会落在
        // try 范围**之外**（监听自身抛异常仍会杀死主循环）。放在最后 => 监听调用被一并包住。
        if (WrapRedirectionsDataManager.partialClassPathCache.contains(className)) {
            if (node == null) {
                node = AsmUtil.read(classfileBuffer)
            }
            wrapMethodBodyTransformer.transform(node, WrapRedirectionsDataManager.partialWrapMethodName[className])
        }

        return if (node == null) {
            classfileBuffer
        } else {
            AsmUtil.write(loader, node, ClassWriter.COMPUTE_FRAMES).save(className)
        }
    }

    private fun ArrayList<Find<String, Boolean>>.forFind(value: String): Boolean {
        var result: Boolean
        this.forEach {
            result = it(value)
            if (result) {
                return true
            }
        }
        return false
    }

    private fun ByteArray.save(name: String): ByteArray {
        if (name == "DR@RW-HPS@DR#DER.KIM") {
            FileOutputStream("a.class").let {
                it.write(this)
                it.flush()
            }
        }
        return this
    }

    companion object {
        @JvmStatic
        fun agentmain(instrumentation: Instrumentation) {
            instrumentation.addTransformer(AsmAgent())
        }
    }
}