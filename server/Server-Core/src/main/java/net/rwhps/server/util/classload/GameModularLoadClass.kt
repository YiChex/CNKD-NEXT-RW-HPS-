/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.classload

import net.rwhps.asm.agent.AsmCore
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.inline.toClass
import net.rwhps.server.util.math.RandomUtils
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Broken Java parent delegation mechanism
 * To complete the isolation loading Class
 *
 * Java Class 加载机制
 * ```
 * The Java platform uses a delegation model for loading classes.
 * The basic idea is that every class loader has a "parent" class loader.
 * When loading a class, a class loader first "delegates" the search for the class to its parent class loader before attempting to find the class itself.
 *
 * ```
 *
 * @property mainClassLoader MainClassLoader (Load RW-HPS.class ...)
 * @property jdkClassLoader ClassLoader (Load Java.lang.* class)
 * @property classPathMap OrderedMap<String, ByteArray>
 *
 * @author Dr (dr@der.kim)
 */
open class GameModularLoadClass(
    private val mainClassLoader: ClassLoader,
    private val jdkClassLoader: ClassLoader,
    private val classPathMap: OrderedMap<String, ByteArray> = OrderedMap()
): ClassLoader() {
    private val hashCode = Integer.toHexString(super.hashCode())

    private val name = "${RandomUtils.getRandomIetterString(5)}:$hashCode"

    /**
     * Get all the Bytes of this loader, for the next reuse, and at the same time make each loader share the byte pool
     */
    val classPathMapData get() = classPathMap

    /**
     * Add Jar to loader (load with bytes)
     * @param bytes ByteArray
     */
    open fun addSourceJar(bytes: ByteArray) {
        addSourceJar(ByteArrayInputStream(bytes))
    }

    /**
     * Add Jar to loader (load with stream)
     * @param inStream InputStream
     */
    open fun addSourceJar(inStream: InputStream) {
        // [CompressionDecoderUtils.zipStream] cannot be used here, otherwise it will prompt ZIP 0x0
        // I do not know why either
        CompressionDecoderUtils.zipAllReadStream(inStream).also {
            it.getZipAllBytes().forEach {
                var name = it.key
                if (name.startsWith("/")) {
                    name = name.substring(1)
                }
                name = name.replace(".class", "")
                classPathMap[name] = it.value
            }
        }.close()
    }

    /**
     * Add byte hot to this loader
     *
     * @param name String
     * @param bytes ByteArray
     */
    fun addClassBytes(name: String, bytes: ByteArray, cove: Boolean = false) {
        if (!classPathMap.containsKey(name.replace(".", "/")) || cove) {
            classPathMap[name.replace(".", "/")] = bytes
        }
    }

    /**
     * Add byte hot to this loader
     * If it has been hot loaded, then use [Class.forName] directly
     *
     * @param name String
     * @param bytes ByteArray
     * @return Class<*>?
     */
    fun loadClassBytes(name: String, bytes: ByteArray): Class<*>? {
        if (classPathMap.containsKey(name.replace(".", "/"))) {
            return name.toClass(this)
        }
        classPathMap[name.replace(".", "/")] = bytes
        return name.toClass(this)
    }

    /**
     * Finds the class with the specified binary name
     * This method should be overridden by class loader implementations that
     * follow the delegation model for loading classes, and will be invoked by
     * the [loadClass] method after checking the
     * parent class loader for the requested class.
     *
     * @param   nameIn The name of the class
     * @return  The resulting {@code Class} object
     * @throws  ClassNotFoundException If the class could not be found
     */
    @Throws(ClassNotFoundException::class)
    public override fun findClass(nameIn: String): Class<*>? {
        val name = nameIn.replace(".", "/")
        var classBytes: ByteArray? = classPathMap[name]
        if (classBytes == null || classBytes.isEmpty()) {
            throw ClassNotFoundException()
        }
        classBytes = asmClass(name, classBytes)
        /*
         * FIX-AGENT-BYPASS: 这里原本是 `defineClass(null, ...)`。
         *
         * JDK 契约：`ClassLoader#defineClass(String name, ...)` 的 name 会**原样**传给
         * `ClassFileTransformer#transform(loader, className, ...)`。传 null 时
         * transformer 收到的 className 就是 null，而不是 JVM 从字节码推断出的名字。
         *
         * 而 `AsmAgent.transform()` 的 6 个分派全部是
         *   `SomeManager.partialClassPathCache.contains(className)`
         * `Set<String>.contains(null)` 恒为 false ⇒ 所有分支不进入 ⇒ 字节原样返回
         * ⇒ **注册到游戏类上的 hook 全部不注入**。
         *
         * 实测（2026-10-21，自写 premain 探针，addTransformer(..., false) 与 AsmAgent 一致）：
         *   游戏加载器 GameModularReusableLoadClass 收到 1131 次 transform，
         *   其中 1130 次 className == null（唯一有名字的是 jdk/proxy3/$Proxy7），
         *   整个日志里 "corrodinggames" 出现 0 次。
         * 运行期 attach 探针交叉验证：GAME 副本 loopTickCount 恒 0（8 秒 delta=0），
         *   RenderGateGuard 在游戏加载器从未被加载。
         *
         * 修法：传真实类名。**注意格式**：`ClassLoader#defineClass` 的 name 必须是
         * **binary name（点号分隔）**，不是 internal form（斜杠分隔）——
         * `ClassLoader.preDefineClass` 会用 `IllegalName` 拒绝斜杠。
         * （实测踩坑：第一版传了斜杠格式，导致 2 个测试 `NoClassDefFoundError: IllegalName`。）
         *
         * transformer 收到的 className 仍是 internal form，因为 JVM 内部会做转换。
         */
        return defineClass(name.replace('/', '.'), classBytes, 0, classBytes.size)
    }

    @Throws(ClassNotFoundException::class)
    override fun loadClass(nameIn: String, resolve: Boolean): Class<*>? {
        var result: Class<*>? = null
        try {
            //Here we need to use the JDK class loader to load the classes in the java.lang package
            result = jdkClassLoader.loadClass(nameIn)
        } catch (_: Exception) {
            //忽略
        }
        if (result != null) {
            return result
        }

        val name = nameIn.replace(".", "/")
        var classBytes: ByteArray? = classPathMap[name]

        if (classBytes == null || classBytes.isEmpty()) {
            try {
                result = mainClassLoader.loadClass(nameIn)
            } catch (_: Exception) {
                //忽略
            }

            if (result != null) {
                return result
            }

            throw ClassNotFoundException()
        }


        classBytes = asmClass(name, classBytes)
        // FIX-AGENT-BYPASS: 见 findClass() 中的详细说明 —— 传真实类名（点号分隔的 binary name），
        // 否则 transformer 收到 className=null，AsmAgent 的所有 contains(className) 分派全部落空。
        return defineClass(name.replace('/', '.'), classBytes, 0, classBytes.size)
    }

    /**
     * Make [ClassLoader.getResourceAsStream] get valid bytes
     * Compatible with [net.rwhps.asm.transformer.SafeClassWriter]
     *
     * @param  path
     * @return [ByteArrayInputStream]
     */
    override fun getResourceAsStream(path: String): InputStream? {
        val classBytes: ByteArray? = classPathMap[path.replace(".class", "")]
        return if (classBytes == null) {
            return null
        } else {
            ByteArrayInputStream(classBytes)
        }
    }

    /**
     * Let the class be changed via ASM
     *
     * @param className Class Name
     * @param classfileBuffer Class Bytes
     * @return ByteArray
     */
    protected open fun asmClass(className: String, classfileBuffer: ByteArray): ByteArray {
        return AsmCore.transform(this, className, classfileBuffer)
    }

    override fun getName(): String {
        return name
    }

    override fun toString(): String {
        return "${javaClass.simpleName}@$hashCode"
    }
}