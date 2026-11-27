/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.json

import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.inline.getDataResult
import net.rwhps.server.util.inline.toJson
import org.json.JSONArray

/**
 * 解析数组
 *
 * @property jsonObject JSONArray
 * @author Dr (dr@der.kim)
 */
class JsonArray {
    private val jsonObject: JSONArray

    constructor(json: String) {
        jsonObject = JSONArray(json)
    }

    constructor(json: Array<Any>) {
        jsonObject = JSONArray(json)
    }

    constructor(json: JSONArray) {
        jsonObject = json
    }

    fun getJson(index: Int): Json {
        return jsonObject.getDataResult(Json("{}")) {
            Json(it.getJSONObject(index))
        }!!
    }

    @JvmOverloads
    fun getString(index: Int, defaultValue: String = ""): String {
        return jsonObject.getDataResult(defaultValue) {
            it.getString(index)
        }!!
    }

    @JvmOverloads
    fun getInt(index: Int, defaultValue: Int? = null): Int? {
        return jsonObject.getDataResult(defaultValue) {
            it.getInt(index)
        }
    }

    @JvmOverloads
    fun getLong(index: Int, defaultValue: Long? = null): Long? {
        return jsonObject.getDataResult(defaultValue) {
            it.getLong(index)
        }
    }

    @JvmOverloads
    fun getBoolean(index: Int, defaultValue: Boolean? = null): Boolean? {
        return jsonObject.getDataResult(defaultValue) {
            it.getBoolean(index)
        }
    }

    fun size(): Int {
        return jsonObject.length()
    }

    fun toJsonList(): Seq<Json> {
        return Seq<Json>().apply {
            jsonObject.toList().forEach {
                add(Json(it.toJson()))
            }
        }
    }
}
