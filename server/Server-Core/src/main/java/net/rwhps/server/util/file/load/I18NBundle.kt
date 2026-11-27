/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.load

import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.io.IoReadConversion
import net.rwhps.server.util.log.Log
import org.jetbrains.annotations.Nls
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.MessageFormat
import java.util.*

/**
 * @author Dr (dr@der.kim)
 * @Date ?
 */
class I18NBundle {
    private val languageData = OrderedMap<String, String>()

    constructor(fileUtils: FileUtils) {
        try {
            load(fileUtils.readInputsStream())
        } catch (e: Exception) {
            Log.fatal("[Load Language Error]", e)
        }
    }

    constructor(inputStream: InputStream) {
        try {
            load(IoReadConversion.fileToReadStream(inputStream))
        } catch (e: Exception) {
            Log.fatal("[Load Language Error]", e)
        }
    }

    @Throws(IOException::class)
    fun addLanguageData(fileUtils: FileUtils) {
        load(fileUtils.readInputsStream())
    }

    private fun load(inputStreamReader: InputStreamReader) {
        try {
            val p = Properties()
            p.load(inputStreamReader)
            p.forEach { key: Any, value: Any ->
                languageData[key as String] = value as String
            }
        } catch (e: Exception) {
            Log.error("[Load Language File] Error", e)
        }
    }

    private fun language(input: String, params: Array<Any?>?): String {
        val text: String? = languageData[input]
        return if (IsUtils.notIsBlank(text)) {
            if (IsUtils.isBlank(params)) text!! else MessageFormat(text!!).format(params)
        } else {
            Log.warn("Translation missing, please check", input)
            "$input : Key is invalid."
        }
    }

    private fun core(input: String, params: Array<Any?>?): String {
        return params?.let { language(input, it) } ?: language(input, null)
    }

    /**
     * 向本地语言中加入自定义
     *
     * @param kv Properties
     * @param cover 强制覆盖
     * @return 是否成功
     */
    internal fun addLang(kv: LoadIni, cover: Boolean): Boolean {
        var flag = cover

        kv.data.eachAll { key: Any, value: Any ->
            if (cover) {
                languageData[key as String] = value as String
            } else {
                flag = !languageData.containsKey(key as String)

                if (flag) {
                    languageData[key] = value as String
                }
            }
        }

        return flag
    }

    /**
     * 向本地语言中加入自定义
     *
     * @param k String
     * @param v String
     * @param cover 强制覆盖
     * @return 是否成功
     */
    internal fun addLang(k: String, v: String, cover: Boolean): Boolean {
        var flag = cover

        if (cover) {
            languageData[k] = v
        } else {
            flag = !languageData.containsKey(k)

            if (flag) {
                languageData[k] = v
            }
        }

        return flag
    }

    /**
     * 传多参
     *
     * @param      input   localeKey
     * @param      params  传入的参数, 永于替换 {0} {1}等
     * @return     文本
     */
    @Nls
    fun getinput(input: String, vararg params: Any?): String {
        val ps = arrayOfNulls<Any>(params.size)
        System.arraycopy(params, 0, ps, 0, params.size)
        return core(input, ps)
    }

    /**
     * 传多参
     *
     * @param      input   localeKey
     * @param      params  传入的参数, 永于替换 {0} {1}等
     * @return     文本
     */
    @Nls
    fun getinputt(input: String, params: Array<Any?>?): String {
        return core(input, params)
    }


}