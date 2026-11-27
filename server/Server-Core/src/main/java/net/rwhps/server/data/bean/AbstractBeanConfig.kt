/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.algorithms.Base64
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toPrettyPrintingJson
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.debug
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 *
 *
 * @date 2024/5/10 上午10:09
 * @author Dr (dr@der.kim)
 */
abstract class AbstractBeanConfig(
    @Transient
    private val thisBeanClass: Class<*>,
    @Transient
    private val beanConfigPrefix: String
) {
    @Transient
    private lateinit var fileUtils: FileUtils

    /**
     * The real bean class that carries the configuration fields.
     *
     * Every subclass passes `this::class.java` from inside a data class that declares a
     * `companion object`. Kotlin resolves that expression to the **companion holder**
     * (`XxxConfig$Companion`), not to `XxxConfig`. A companion holder has no configuration
     * fields, so using it directly made [allName] return an empty set: `readProperty()` never
     * entered its loop, so every `-Drwhps.config.*` override and every runtime [coverField]
     * call silently did nothing (13 of 13 beans affected, including `BeanCoreConfig` itself).
     *
     * A class that already is an [AbstractBeanConfig] is used as-is, so passing
     * `XxxConfig::class.java` explicitly also works, and a nested helper class is never
     * mistaken for its outer class.
     */
    private val configClass: Class<*>
        get() = resolveConfigClass(thisBeanClass)

    fun bindFile(fileUtils: FileUtils) {
        this.fileUtils = fileUtils
    }

    open fun readProperty() {
        if (beanConfigPrefix.isEmpty()) {
            Log.track("Config Property", "{} no Property", fileUtils.name)
            return
        }

        allName().eachAll {
            // `System.getProperty` returns null for every field that has no matching -D flag,
            // which is the normal case. Base64.isBase64 takes a non-null String (it checks the
            // parameter before its own try/catch), so an absent flag has to be skipped here
            // rather than passed through.
            val data = System.getProperties().getProperty("$beanConfigPrefix.$it")?.let { str ->
                if (Base64.isBase64(str)) {
                    Base64.decodeString(str)
                } else {
                    str
                }
            }

            if (data != null) {
                // Write to THIS bean, not to Data.config. Every bean calls readProperty() on
                // itself (13 call sites), so `config.coverField` used to send a non-core bean's
                // override into the core bean: either polluting BeanCoreConfig when the field
                // name happened to exist there, or failing outright when it did not.
                if (coverField(it, data)) {
                    debug("${configClass.name} : Set OK $it = $data")
                } else {
                    debug("${configClass.name} : Set ERROR $it = $data")
                }
            }
        }

    }

    open fun save() {
        if (!this::fileUtils.isInitialized) {
            return
        }
        fileUtils.writeFile(this.toPrettyPrintingJson())
    }

    fun coverField(name: String, value: Any): Boolean {
        return try {
            val field: Field = ReflectionUtils.findField(configClass, name) ?: return false
            field.isAccessible = true
            field[this] = when (field[this]) {
                is Byte -> value.toString().toByte()
                is Short -> value.toString().toShort()
                is Char -> value.toString()[0]
                is Int -> value.toString().toInt()
                is Long -> value.toString().toLong()
                is Boolean -> value.toString().toBoolean()
                else -> value
            }
            field.isAccessible = false
            true
        } catch (e: Exception) {
            // A failed write must not be reported as success: callers use the result to decide
            // whether the value was really applied.
            Log.error("Cover Value error", e)
            false
        }
    }

    private fun allName(): Seq<String> {
        val allName = Seq<String>()
        for (field in configClass.declaredFields) {
            // 过滤Kt生成的、静态的(Companion 与伴生对象转发的 fileUtils)以及不能被覆盖的字段
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic) {
                continue
            }
            if (field.name in NON_CONFIG_FIELD_NAMES) {
                continue
            }
            allName.add(field.name)
        }
        return allName
    }

    companion object {
        /**
         * Fields that live on the bean type but never carry a `-D` overridable configuration
         * value. `fileUtils` was previously filtered under the misspelled name `"fileUtil"`,
         * so it was being offered as a configuration key.
         */
        private val NON_CONFIG_FIELD_NAMES = setOf("Companion", "fileUtils", "thisBeanClass", "beanConfigPrefix")

        private fun resolveConfigClass(beanClass: Class<*>): Class<*> {
            if (AbstractBeanConfig::class.java.isAssignableFrom(beanClass)) {
                return beanClass
            }
            val enclosing = beanClass.enclosingClass
            if (enclosing != null && AbstractBeanConfig::class.java.isAssignableFrom(enclosing)) {
                return enclosing
            }
            return beanClass
        }
    }
}
