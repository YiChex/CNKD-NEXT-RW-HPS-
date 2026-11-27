/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core.web

import io.netty.handler.codec.http.HttpHeaderNames
import net.rwhps.server.data.global.RegexData
import net.rwhps.server.util.file.json.Json
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.inline.mutableObjectMapOf

/**
 * @date  2023/7/1 16:05
 * @author Dr (dr@der.kim)
 */
abstract class WebFunction {
    protected fun stringUrlDataResolveToJson(accept: AcceptWeb): Json {
        return stringUrlDataResolveToJson(accept.urlData)
    }

    protected fun stringUrlDataResolveToJson(data: String): Json {
        if (data.isEmpty()) {
            return Json(LinkedHashMap<String, String>())
        }
        val paramArray: Array<String> = data.split(RegexData.and).toTypedArray()
        val listMap = LinkedHashMap<String, String>()
        for (pam in paramArray) {
            val keyValue = pam.split(RegexData.amount).toTypedArray()
            listMap[keyValue[0]] = keyValue[1]
        }
        return Json(listMap)
    }

    protected fun headResolveToCookie(accept: AcceptWeb): ObjectMap<String, String> {
        val cookies: ObjectMap<String, String> = mutableObjectMapOf()
        val split = accept.getHeaders(HttpHeaderNames.COOKIE)?.split(RegexData.semicolon) ?: arrayListOf()
        for (cookie in split) {
            val split2 = cookie.split(RegexData.amount)
            cookies[split2[0]] = split2[1]
        }
        return cookies
    }
}