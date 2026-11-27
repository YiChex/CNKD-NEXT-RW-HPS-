/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import net.rwhps.server.plugin.GetVersion.Companion.equals
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.IsUtils.doubleToLong
import net.rwhps.server.util.str.StringFilteringUtil.StringMatcherData
import net.rwhps.server.util.annotations.NeedToRefactor
import java.util.*

/**
 * [语义化版本](https://semver.org/lang/zh-CN/) 支持
 *
 * ### 解析示例
 *
 * `1.0.0-M1+c000000a` 将会解析出下面的内容,
 * [major] (主本号), [minor] (次版本号), [patch] (修订号), [identifier] (先行版本号) 和 [metadata] (元数据).
 * ```
 * SemVersion(
 * major = 1,
 * minor = 0,
 * patch = 0,
 * identifier  = "M1"
 * metadata    = "c000000a"
 * )
 * ```
 * 其中 identifier 和 metadata 都是可选的.
 *
 * 对于核心版本号, 此实现稍微比语义化版本规范宽松一些, 允许 x.y 的存在.
 * @author Dr (dr@der.kim)
 */
class GetVersion(version: String) {
    /**
     * 主版本号
     */
    val major: Int

    /**
     * 次版本号
     */
    val minor: Int

    /**
     * 修订号
     */
    val patch: Int

    /**
     * 先行版本号识别符
     */
    val identifier: String

    /**
     * 版本号元数据, 不参与版本号对比([compareTo]), 但是参与版本号严格对比([equals])
     */
    val metadata: String

    /**
     * 是否是正式版
     */
    val formalEdition: Boolean

    /**
     * 解析一个版本号, 将会返回一个 [GetVersion()],
     * 如果发生解析错误将会抛出一个 {@link IllegalArgumentException} 或者 {@link NumberFormatException}
     *
     * 对于版本号的组成, 有以下规定:
     * - 必须包含主版本号和次版本号
     * - 存在 先行版本号 的时候 先行版本号 不能为空
     * - 存在 元数据 的时候 元数据 不能为空
     * - 核心版本号只允许 `x.y` 和`x.y.z` 的存在
     * - `1.0-RC` 是合法的
     * - `1.0.0-RC` 也是合法的, 与 `1.0-RC` 一样
     * - `1.0.0.0-RC` 是不合法的, 将会抛出一个 [IllegalArgumentException]
     *
     * 注意情况:
     * - 第一个 `+` 之后的所有内容全部识别为元数据
     * - `1.0+METADATA-M4`, metadata="METADATA-M4"
     */
    init {
        val getVersion = StringMatcherData(versionMatch, version)
        major = getVersion.getInt(1)
        minor = getVersion.getInt(2)
        patch = getVersion.getInt(3)
        identifier = getVersion.getString(4)
        metadata = getVersion.getString(5)

        formalEdition = identifier.isBlank()
    }

    /**
     * 1.0 -> 1000
     * 1.0.0 -> 1000
     * 1.0.0-DEV -> 1000.001
     * 1.0.0-M1 -> 1000.1
     * 1.0.0-RC -> 1001
     * @return
     */
    private fun toMainInt(): Int {
        return major * 1000 + minor * 100 + patch * 10
    }

    val version: Double
        get() {
            var version = toMainInt().toDouble()
            val identifierUpCase = identifier.uppercase(Locale.getDefault())
            when {
                identifierUpCase.startsWith("M") -> {
                    version += identifierUpCase.removePrefix("M").toInt() * 0.1
                }
                identifierUpCase.startsWith("RC") -> {
                    version += 9.999
                }
                identifierUpCase.startsWith("DEV") -> {
                    version += (identifierUpCase.removePrefix("DEV").ifBlank { "1" }).toInt() * 0.001
                }
            }
            return version
        }


    /**
     * 比较双方版本大小
     *
     * 输入 :
     * >/>=/ 等
     *
     * @param version 待比较
     * @return Boolean
     */
    @NeedToRefactor
    fun getIfVersion(version: String): Boolean {
        return if (IsUtils.isBlank(version)) {
            false
        } else DemandChainDescription(version).getIfVersion(this)
    }

    override fun toString(): String {
        return "GetVersion{" + "major=" + major + ", minor=" + minor + ", patch=" + patch + ", identifier='" + identifier + '\'' + ", metadata='" + metadata + '\'' + ", formalEdition='" + formalEdition + '\'' + '}'
    }

    /**
     * 这玩意离不开空格, 过于傻逼, 需要重写
     *
     * @property equalVersion Function1<[@kotlin.ParameterName] GetVersion, Boolean>
     * @constructor
     */
    @NeedToRefactor
    private class DemandChainDescription(version: String) {
        private val equalVersion: ((getVersion: GetVersion) -> Boolean)

        init {
            equalVersion = register(version)
        }

        fun getIfVersion(getVersion: GetVersion): Boolean {
            return equalVersion(getVersion)
        }

        private fun register(version: String): (GetVersion) -> Boolean {
            var versionCache = version
            if (versionCache.contains("(") || versionCache.contains(")") || versionCache.contains("[") || versionCache.contains("]")) {
                versionCache = versionCache.replace(" ", "")
                val versionArray = versionCache.substring(1, versionCache.length - 1).split(",").toTypedArray()
                val startVersion = GetVersion(versionArray[0]).version
                val endVersion = GetVersion(versionArray[1]).version
                return when {
                    versionCache.startsWith("(") && versionCache.endsWith(")") -> { e: GetVersion ->
                        IsUtils.inTwoNumbersNoSE(startVersion, e.version, endVersion)
                    }
                    versionCache.startsWith("(") && versionCache.endsWith("]") -> { e: GetVersion ->
                        IsUtils.inTwoNumbersNoSrE(startVersion, e.version, endVersion, false)
                    }
                    versionCache.startsWith("[") && versionCache.endsWith(")") -> { e: GetVersion ->
                        IsUtils.inTwoNumbersNoSrE(startVersion, e.version, endVersion, true)
                    }
                    versionCache.startsWith("[") && versionCache.endsWith("]") -> { e: GetVersion ->
                        IsUtils.inTwoNumbers(startVersion, e.version, endVersion)
                    }
                    else -> { _: GetVersion -> false }
                }
            } else {
                val versionArray = versionCache.trim { it <= ' ' }.split(" ").toTypedArray()
                val sourceVersion = doubleToLong(GetVersion(if (versionArray.size > 1) versionArray[1] else versionArray[0]).version)
                return when {
                    versionArray[0] == ">" -> { e: GetVersion -> doubleToLong(e.version) > sourceVersion }
                    versionArray[0] == ">=" -> { e: GetVersion -> doubleToLong(e.version) >= sourceVersion }
                    versionArray[0] == "<" -> { e: GetVersion -> doubleToLong(e.version) < sourceVersion }
                    versionArray[0] == "<=" -> { e: GetVersion -> doubleToLong(e.version) <= sourceVersion }
                    versionArray[0] == "!=" -> { e: GetVersion -> doubleToLong(e.version) != sourceVersion }
                    else -> { e: GetVersion -> doubleToLong(e.version) == sourceVersion }
                }
            }
        }
    }

    companion object {
        private const val versionMatch = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
    }
}