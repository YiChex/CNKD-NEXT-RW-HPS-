/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util

import net.rwhps.server.util.log.Log
import java.nio.charset.Charset
import java.util.*

/**
 * @author Dr (dr@der.kim)
 */
object SystemUtils {
    /** 获取服务器默认编码 (优先使用 UTF-8) */
    val defaultEncoding: Charset
        get() {
            val encodingName = get("sun.stdout.encoding") ?: Charset.defaultCharset().name()
            return if (encodingName.uppercase() == "UTF-8" || encodingName.uppercase() == "UTF8") {
                Charset.forName(encodingName)
            } else {
                // Force UTF-8 for consistent cross-platform behavior
                Charset.forName("UTF-8")
            }
        }

    /** 获取服务器堆大小 */
    val javaHeap: Long
        get() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    /** 获取服务器内存总大小 */
    val javaTotalMemory: Long
        get() = Runtime.getRuntime().totalMemory()

    /** 获取服务器空闲内存大小 */
    val javaFreeMemory: Long
        get() = Runtime.getRuntime().freeMemory()

    /** 获取服务器可用CPU数量 */
    val availableProcessors: Int
        get() = Runtime.getRuntime().availableProcessors()

    /** 获取Java名称 */
    val javaName: String
        get() = get("java.vm.name")!!

    /** 获取Java作者 */
    val javaVendor: String
        get() = get("java.vendor")!!

    /** 获取Java版本 */
    val javaVersion: String
        get() = get("java.version")!!

    /** 获取系统名称 */
    val osName: String
        get() = get("os.name")!!

    /** 获取系统架构 */
    val osArch: String
        get() = get("os.arch")!!

    /**
     * 判断 [requiredVersion] 版本是否和 [javaVersion] 相同或者更大
     *
     * @param requiredVersion Java版本
     * @return 是否和 [javaVersion] 相同或者更大
     */
    fun isJavaVersionAtLeast(requiredVersion: Float): Boolean {
        val version = get("java.version")!!.split(".")[0]
        return (if (IsUtils.isBlank(version)) 0f else version.toFloat()) >= requiredVersion
    }

    /** 判断是否为Windows系统 */
    val isWindows: Boolean
        get() {
            val os = get("os.name")!!
            return IsUtils.isBlank(os) || os.lowercase(Locale.getDefault()).contains("windows")
        }

    /** 获取JVM运行的PID */
    val pid: Long = ProcessHandle.current().pid()

    /**
     * 取得系统属性，如果因为Java安全的限制而失败，则将错误打在Log中，然后返回
     * @param name  属性名
     * @return 属性值或null
     * @see System String
     * @see System String
     */
    private operator fun get(name: String): String? {
        var value: String? = null
        try {
            value = System.getProperty(name)
        } catch (e: SecurityException) {
            Log.error("Security level limit", e)
        }
        if (null == value) {
            try {
                value = System.getenv(name)
            } catch (e: SecurityException) {
                Log.error("Security level limit", e)
            }
        }
        return value
    }
}