package net.rwhps.server.data.bean

import com.google.gson.JsonParser
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson

/** Read-only preparation. Invalid reloads must never replace files with startup defaults. */
internal object RuntimeConfigCandidate {
    fun main(raw: String): BeanServerConfigFile {
        require(raw.isNotBlank()) { "ServerConfig.json is empty; existing runtime configuration was kept" }
        require(JsonParser.parseString(raw).isJsonObject) { "ServerConfig.json must be an object" }
        return BeanServerConfigFile::class.java.toGson(raw).normalized()
    }

    fun <T: AbstractBeanConfig> read(file: FileUtils, type: Class<T>, default: () -> T, required: Boolean = false): T {
        require(!required || file.exists()) { "${file.name} is missing; runtime configuration was kept" }
        val raw = if (file.exists()) file.file.readText(Charsets.UTF_8) else ""
        require(!file.exists() || raw.isNotBlank()) { "${file.name} is empty; runtime configuration was kept" }
        val value = if (raw.isBlank()) default() else {
            require(JsonParser.parseString(raw).isJsonObject) { "${file.name} must be an object" }
            net.rwhps.server.util.inline.toGson(raw, type)
        }
        value.bindFile(file)
        value.readProperty()
        return value
    }
}
