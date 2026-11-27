/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.file.plugin.serializer

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.file.json.Json
import net.rwhps.server.util.file.plugin.value.SerializableValue
import net.rwhps.server.util.file.plugin.value.Value
import net.rwhps.server.util.inline.readString
import java.io.InputStream
import java.io.OutputStream


/**
 *
 *
 * @date 2024/2/15 12:52
 * @author Dr (dr@der.kim)
 */
class PeopleFriendlySerializers : AbstractSerializers {

    var fileType = "json"

    override fun checkContainsSerializers(className: Class<*>): Boolean {
        return when (className) {
            ByteArray::class.java,
            Short::class.javaPrimitiveType!!,
            Integer::class.java,
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            Char::class.javaPrimitiveType!!,
            String::class.java -> true
            else -> false
        }
    }

    override fun read(pluginData: OrderedMap<String, Value<*>>, inStream: InputStream) {
        gson.fromJson(inStream.readString().ifBlank { "{}" }, Map::class.java).forEach {
            pluginData[it.key.toString()] = SerializableValue(it.value)
        }
    }

    override fun save(pluginData: OrderedMap<String, Value<*>>, outputStream: OutputStream) {
        outputStream.write(Json.toJson(HashMap<String, Any?>().apply {
            pluginData.eachAll { k,v ->
                put(k, v.value)
            }
        }).toByteArray())
        outputStream.flush()
    }

    companion object {
        val gson: Gson = GsonBuilder().apply {
            setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER)
        }.create()
    }
}