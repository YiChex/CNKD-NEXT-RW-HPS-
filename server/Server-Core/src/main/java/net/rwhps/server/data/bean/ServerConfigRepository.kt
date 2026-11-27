/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson
import net.rwhps.server.util.inline.toPrettyPrintingJson
import net.rwhps.server.util.log.Log
import java.text.SimpleDateFormat
import java.util.Date

object ServerConfigRepository {
    val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("ServerConfig.json")

    @JvmStatic
    fun load(): BeanServerConfigFile {
        val raw = fileUtils.readFileStringData()
        if (raw.isNotBlank()) {
            parseServerConfig(raw)?.let { return normalizeAndPersist(it) }

            val backupName = "ServerConfig.broken-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}.json"
            val backupFile = FileUtils.getFolder(Data.ServerDataPath).toFile(backupName)
            runCatching {
                backupFile.writeFile(raw)
                Log.warn("[ServerConfig] Existing ServerConfig.json is malformed and could not be repaired. Backup created: ${backupFile.file.name}")
            }.onFailure {
                Log.warn("[ServerConfig] Existing ServerConfig.json is malformed and could not be repaired. Failed to create backup.", it)
            }
        }

        val migrated = migrateLegacy()
        val normalized = migrated.normalized()
        fileUtils.writeFile(normalized.toPrettyPrintingJson())
        return normalized
    }

    @JvmStatic
    fun save(core: BeanCoreConfig, server: BeanServerConfig, startMode: BeanStartModeConfig) {
        val merged = BeanServerConfigFile.fromCurrent(core, server, startMode)
        fileUtils.writeFile(merged.toPrettyPrintingJson())
    }

    private fun migrateLegacy(): BeanServerConfigFile {
        val coreFile = FileUtils.getFolder(Data.ServerDataPath).toFile("Config.json")
        val serverFile = FileUtils.getFolder(Data.ServerDataPath).toFile("ConfigServer.json")
        val startModeFile = FileUtils.getFolder(Data.ServerDataPath).toFile("StartModeConfig.json")

        val core = if (coreFile.exists()) {
            BeanCoreConfig::class.java.toGson(coreFile.readFileStringData())
        } else {
            BeanCoreConfig()
        }
        val server = if (serverFile.exists()) {
            BeanServerConfig::class.java.toGson(serverFile.readFileStringData())
        } else {
            BeanServerConfig()
        }
        val startMode = if (startModeFile.exists()) {
            BeanStartModeConfig::class.java.toGson(startModeFile.readFileStringData())
        } else {
            BeanStartModeConfig()
        }

        return BeanServerConfigFile.fromCurrent(core, server, startMode)
    }

    private fun parseServerConfig(raw: String): BeanServerConfigFile? {
        runCatching {
            return BeanServerConfigFile::class.java.toGson(raw)
        }

        val sanitized = sanitizeLegacyAnnotatedJson(raw)
        if (sanitized.isBlank()) {
            return null
        }

        return runCatching {
            BeanServerConfigFile::class.java.toGson(sanitized).also {
                fileUtils.writeFile(it.toPrettyPrintingJson())
                Log.warn("[ServerConfig] Repaired legacy/non-standard ServerConfig.json and rewrote it to standard JSON.")
            }
        }.getOrNull()
    }

    private fun normalizeAndPersist(config: BeanServerConfigFile): BeanServerConfigFile {
        val normalized = config.normalized()
        if (normalized != config) {
            fileUtils.writeFile(normalized.toPrettyPrintingJson())
            Log.warn("[ServerConfig] Invalid runtime values were normalized and written back to ServerConfig.json.")
        }
        return normalized
    }

    private fun sanitizeLegacyAnnotatedJson(raw: String): String {
        return raw
            .replace("\uFEFF", "")
            .lineSequence()
            .mapNotNull(::sanitizeLegacyAnnotatedLine)
            .joinToString("\n")
            .trim()
    }

    private fun sanitizeLegacyAnnotatedLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val firstJsonIndex = line.indexOfFirst { it == '"' || it == '{' || it == '}' || it == '[' || it == ']' }
        if (firstJsonIndex < 0) {
            return null
        }

        var candidate = line.substring(firstJsonIndex).trimEnd()
        if (candidate.isBlank()) {
            return null
        }

        if (candidate.count { it == '"' } % 2 == 1 && candidate.contains(':')) {
            candidate = when {
                candidate.endsWith(",") -> candidate.dropLast(1) + "\","
                candidate.endsWith("}") -> candidate.dropLast(1) + "\"}"
                else -> "$candidate\""
            }
        }

        return candidate
    }
}
