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
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.inline.getDataResult
import net.rwhps.server.util.inline.getDataResultObject
import net.rwhps.server.util.inline.toPrettyPrintingJson
import org.json.JSONArray
import org.json.JSONObject

//写的越久，BUG越多，伤痕越疼，脾气越差/-活得越久 故事越多 伤痕越疼，脾气越差
/**
 * Json 模块
 *
 * @author Dr (dr@der.kim)
 */
class Json {
    private val jsonObject: JSONObject

    constructor(json: String) {
        jsonObject = JSONObject(json)
    }

    constructor(json: Map<*, *>) {
        jsonObject = JSONObject(json)
    }

    constructor(json: JSONObject) {
        jsonObject = json
    }

    @JvmOverloads
    fun getString(str: String, defaultValue: String = ""): String {
        return jsonObject.getDataResult(defaultValue) {
            it.getString(str)
        }!!
    }

    @JvmOverloads
    fun getInt(str: String, defaultValue: Int? = null): Int? {
        return jsonObject.getDataResult(defaultValue) {
            it.getInt(str)
        }
    }

    @JvmOverloads
    fun getLong(str: String, defaultValue: Long? = null): Long? {
        return jsonObject.getDataResult(defaultValue) {
            it.getLong(str)
        }
    }

    @JvmOverloads
    fun getBoolean(str: String, defaultValue: Boolean? = null): Boolean? {
        return jsonObject.getDataResult(defaultValue) {
            it.getBoolean(str)
        }
    }

    fun getJson(str: String): Json? {
        return Json(jsonObject.getJSONObject(str))
    }


    fun getInnerMap(): ObjectMap<String, Any> {
        return ObjectMap<String, Any>().apply {
            jsonObject.toMap().forEach {
                when (it.value) {
                    is JSONArray -> {
                        put(it.key, JsonArray(it.value as JSONArray))
                    }
                    is ArrayList<*> -> {
                        put(it.key, JsonArray(JSONArray((it.value as Iterable<*>))))
                    }
                    else -> {
                        put(it.key, it.value)
                    }
                }
            }
        }
    }

    fun getArrayData(str: String): JsonArray {
        val rArray = JsonArray(jsonObject.getDataResultObject({ JSONArray() }) {
            it.getJSONArray(str)
        })
        return rArray
    }

    fun getArraySeqData(str: String): Seq<Json> {
        return getArrayData(str).toJsonList()
    }

    companion object {
        /**
         * Map to JSON formatted
         * @param map Map<String, Any>
         * @return Json
         */
        @JvmStatic
        fun toJson(map: Map<String, Any?>): String {
            return map.toPrettyPrintingJson()
        }

        /**
         * Map to JSON formatted
         * @param map Map<String, Any>
         * @return Json
         */
        @JvmStatic
        fun toJson(map: ObjectMap<String, Any?>): String {
            return HashMap<String, Any?>().also { map.eachAll { key, value -> it[key] = value } }.toPrettyPrintingJson()
        }
    }
}