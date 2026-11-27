/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

@file:JvmName("InlineUtils") @file:JvmMultifileClass

package net.rwhps.server.util.inline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

val gsonToString: Gson = GsonBuilder()
    .setPrettyPrinting()
    .create()
val gsonToStringNoPrettyPrinting: Gson = GsonBuilder()
    .create()
val stringToGson: Gson = Gson()

@PublishedApi
internal fun stripLineComments(raw: String): String {
    return raw.lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("#") || trimmed.startsWith("//")
        }
        .joinToString(System.lineSeparator())
}

fun Any.toJson(): String {
    return gsonToStringNoPrettyPrinting.toJson(this)
}

fun Any.toPrettyPrintingJson(): String {
    return gsonToString.toJson(this)
}

inline fun <reified T> Class<T>.toGson(classData: String): T {
    return stringToGson.fromJson(stripLineComments(classData).ifBlank { "{}" }, T::class.java)
}

fun <T> toGson(classData: String, type: Class<T>): T {
    return stringToGson.fromJson(stripLineComments(classData).ifBlank { "{}" }, type)
}
inline fun <reified T> toGson(classData: String, type: TypeToken<T>): T {
    return stringToGson.fromJson(stripLineComments(classData).ifBlank { "{}" }, type.type)
}

fun <R> JSONObject.getDataResult(defaultValue: R?, block: (JSONObject) -> R): R? {
    return try {
        block(this)
    } catch (_: JSONException) {
        defaultValue
    }
}

fun <R> JSONObject.getDataResultObject(defaultValue: () -> R, block: (JSONObject) -> R): R {
    return try {
        block(this)
    } catch (_: JSONException) {
        defaultValue()
    }
}

fun <R> JSONArray.getDataResult(defaultValue: R?, block: (JSONArray) -> R): R? {
    return try {
        block(this)
    } catch (_: JSONException) {
        defaultValue
    }
}

fun <R> JSONArray.getDataResultObject(defaultValue: () -> R, block: (JSONArray) -> R): R {
    return try {
        block(this)
    } catch (_: JSONException) {
        defaultValue()
    }
}
