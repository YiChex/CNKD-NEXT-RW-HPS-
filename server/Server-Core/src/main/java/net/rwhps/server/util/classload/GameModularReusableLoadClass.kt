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
import net.rwhps.server.util.compression.CompressionAlgorithm
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.plugin.PluginData
import net.rwhps.server.util.file.plugin.serializer.MachineFriendlySerializers
import net.rwhps.server.util.file.plugin.serializer.SerializersEnum

/**
 * Reusable class loader
 *
 * @date  2023/5/26 15:14
 * @author Dr (dr@der.kim)
 */
class GameModularReusableLoadClass(
    mainClassLoader: ClassLoader, jdkClassLoader: ClassLoader
): GameModularLoadClass(mainClassLoader, jdkClassLoader) {
    /** 是否是缓存加载 */
    private var asmCacheFlag = false

    //You don't have to go through ASM because it's caching
    override fun asmClass(className: String, classfileBuffer: ByteArray): ByteArray {
        return if (asmCacheFlag) {
            classfileBuffer
        } else {
            super.asmClass(className, classfileBuffer)
        }
    }

    /**
     * Read the cached file
     *
     * @param classPathMap OrderedMap<String, ByteArray>
     */
    fun readData(file: FileUtils) {
        val serializers = SerializersEnum.Bin.create().apply { (this as MachineFriendlySerializers).code = CompressionAlgorithm.SevenZip }
        val data = PluginData(serializers).apply {
            setFileUtil(file, false)
        }

        this.classPathMapData.clear()
        data.getMap().forEach {
            this.classPathMapData.put(it.key, it.value.value as ByteArray)
        }
        asmCacheFlag = true
    }

    /**
     * Save the cache file (go through ASM again)
     *
     * @param file FileUtil
     */
    fun saveData(file: FileUtils) {
        val serializers = SerializersEnum.Bin.create().apply { (this as MachineFriendlySerializers).code = CompressionAlgorithm.SevenZip }
        val data = PluginData(serializers).apply {
            setFileUtil(file)
        }
        classPathMapData.forEach {
            data.set(it.key, super.asmClass(it.key, it.value))
        }

        data.save()
    }

    /**
     * D-01 / D-02: verify that the cached game-class archive really contains the hooks the
     * current redirection table expects.
     *
     * While [asmCacheFlag] is set, [asmClass] returns the cached bytes untouched, so the
     * runtime redirection table is **not** applied to game classes -- correctness depends
     * entirely on the cache having been produced from the current redirection table. When
     * that precondition breaks (a hook was added after the `.bin` was built, which is how the
     * `com/corrodinggames/rts/game/i` loop-entry drain hook went missing in 1.1.11), the
     * server used to keep running with silently un-redirected game classes.
     *
     * A baked class carries at least one `net/rwhps` constant-pool reference; a registered
     * target without one means its hook is absent from the cache.
     *
     * @return sorted JVM-internal class paths whose hook is missing from the cache
     */
    fun findMissingBakedRedirections(): List<String> {
        if (!asmCacheFlag) {
            return emptyList()
        }
        val registered = AsmCore.registeredClassPaths()
        if (registered.isEmpty()) {
            return emptyList()
        }
        /*
         * FIX-FALSE-POSITIVE: 只有「需要往字节码里写入 net/rwhps 引用」的 hook 类型，
         * 才能用 "net/rwhps" 字面量判断是否烘焙过。
         *
         * `addAllReplace` 是**整类替换**：把该类所有方法体换成空实现（return/return 0/null）。
         * 替换后的字节码**不引用任何 net/rwhps 类** —— 用字面量检测必然误报。
         *
         * 实测（2026-10-21，.bin 解压内容逐类统计）：
         *   a/a/d                            出现在 .bin 中 33 次
         *   com/corrodinggames/rts/gameFramework/j/d   4 次
         *   com/corrodinggames/rts/gameFramework/j/e   4 次
         *   org/newdawn/slick/util/DefaultLogSystem    3 次
         * 这 4 个都是 addAllReplace 目标，旧检测全部误报为 "missing baked hook"。
         *
         * ⇒ 判据改为：**只有注入型 hook（redirectL/redirectR/redirectWrap）才要求
         * net/rwhps 字面量**；整类替换型只要求缓存里有非空字节。
         * 用「是否出现在注入型注册表里」来区分，避免依赖 ASM 模块的 internal 字段。
         */
        val injectionTargets = AsmCore.injectionClassPaths()
        val marker = "net/rwhps".toByteArray(Charsets.US_ASCII)
        val missing = ArrayList<String>()
        classPathMapData.eachAll { name, bytes ->
            if (registered.contains(name)) {
                val baked = if (injectionTargets.contains(name)) {
                    bytes.containsAscii(marker)
                } else {
                    // 整类替换（addAllReplace）或未分类：缓存里有字节即算烘焙
                    bytes.isNotEmpty()
                }
                if (!baked) {
                    missing.add(name)
                }
            }
        }
        missing.sort()
        return missing
    }

    private fun ByteArray.containsAscii(needle: ByteArray): Boolean {
        if (needle.isEmpty() || this.size < needle.size) {
            return false
        }
        outer@ for (i in 0..(this.size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    continue@outer
                }
            }
            return true
        }
        return false
    }
}