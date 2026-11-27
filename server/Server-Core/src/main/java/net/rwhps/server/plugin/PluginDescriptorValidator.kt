/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.data.global.Data
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile

internal object PluginDescriptorValidator {
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 2_048

    private val pluginIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val javaMainPattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    private val reservedBuiltInIds = setOf("hessserver", "uplist", "connectlimit", "bindplayer")

    fun normalizedId(pluginInfo: BeanPluginInfo): String = pluginInfo.internalName.lowercase(Locale.ROOT)

    fun isJavaScriptMain(main: String): Boolean {
        return main.endsWith(".js", ignoreCase = true) || main.endsWith(".mjs", ignoreCase = true)
    }

    fun isReservedBuiltInId(pluginInfo: BeanPluginInfo): Boolean = normalizedId(pluginInfo) in reservedBuiltInIds

    fun validateDescriptor(pluginInfo: BeanPluginInfo): String? {
        if (pluginInfo.apiVersion !in 1..2) {
            return "apiVersion must be 1 or 2"
        }
        if (pluginInfo.name.isBlank() || pluginInfo.name.length > 96) {
            return "name must contain 1..96 characters"
        }
        if (pluginInfo.author.isBlank() || pluginInfo.author.length > 160) {
            return "author must contain 1..160 characters"
        }
        if (pluginInfo.description.length > 500) {
            return "description cannot exceed 500 characters"
        }
        if (!pluginIdPattern.matches(pluginInfo.internalName)) {
            return "internalName must match ${pluginIdPattern.pattern}"
        }
        if (pluginInfo.apiVersion == 2 && pluginInfo.internalName != pluginInfo.internalName.lowercase(Locale.ROOT)) {
            return "apiVersion 2 internalName must be lowercase"
        }
        if (pluginInfo.main.isBlank()) {
            return "main cannot be blank"
        }

        if (pluginInfo.apiVersion == 2 && isJavaScriptMain(pluginInfo.main)) {
            return "apiVersion 2 currently supports JVM plugins only"
        }
        if (isJavaScriptMain(pluginInfo.main)) {
            val normalizedMain = pluginInfo.main.replace('\\', '/')
            if (pluginInfo.main.contains('\\') || normalizedMain.startsWith('/') || normalizedMain.split('/').any { it == ".." }) {
                return "JavaScript main must be a relative archive path"
            }
        } else if (!javaMainPattern.matches(pluginInfo.main)) {
            return "Java main must be a valid fully qualified class name"
        }
        if (pluginInfo.apiVersion == 2 && pluginInfo.main.startsWith("net.rwhps.server.")) {
            return "apiVersion 2 main cannot use the reserved net.rwhps.server package"
        }

        if (pluginInfo.version.isBlank()) {
            return "version cannot be blank"
        }
        runCatching { GetVersion(pluginInfo.version) }.getOrElse {
            return "version must be valid SemVer: ${safeMessage(it)}"
        }

        if (pluginInfo.supportedVersions.isBlank()) {
            return "supportedVersions cannot be blank"
        }
        runCatching { GetVersion(Data.SERVER_API_VERSION).getIfVersion(pluginInfo.supportedVersions) }.getOrElse {
            return "supportedVersions is invalid: ${safeMessage(it)}"
        }
        if (pluginInfo.capabilities.size > 32) {
            return "capabilities cannot contain more than 32 entries"
        }
        val normalizedCapabilities = pluginInfo.capabilities.map { it.trim().lowercase(Locale.ROOT) }
        if (normalizedCapabilities.any { it.isBlank() || !pluginIdPattern.matches(it) }) {
            return "capabilities must use plugin ID syntax"
        }
        if (normalizedCapabilities.toSet().size != normalizedCapabilities.size) {
            return "capabilities cannot contain duplicates"
        }
        if (pluginInfo.apiVersion == 2) {
            if (pluginInfo.import.isNotBlank()) {
                return "apiVersion 2 cannot use legacy import ordering"
            }
            if (pluginInfo.capabilities.any { it != it.lowercase(Locale.ROOT) }) {
                return "apiVersion 2 capabilities must be lowercase"
            }
            val unsupported = normalizedCapabilities - V2PluginCapabilities.ALL
            if (unsupported.isNotEmpty()) {
                return "unsupported apiVersion 2 capabilities: ${unsupported.sorted().joinToString(",")}" 
            }
        }
        return null
    }

    fun supportsCurrentApi(pluginInfo: BeanPluginInfo): Boolean {
        return GetVersion(Data.SERVER_API_VERSION).getIfVersion(pluginInfo.supportedVersions)
    }

    fun validateArchive(file: File): String? {
        if (!file.isFile) {
            return "archive is not a regular file"
        }
        if (Files.isSymbolicLink(file.toPath())) {
            return "archive cannot be a symbolic link"
        }
        if (file.length() <= 0L || file.length() > MAX_ARCHIVE_BYTES) {
            return "archive size must be within 1..$MAX_ARCHIVE_BYTES bytes"
        }

        return runCatching {
            ZipFile(file).use { zip ->
                var entries = 0
                var expandedBytes = 0L
                val entryNames = HashSet<String>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    entries++
                    if (entries > MAX_ENTRIES) {
                        return "archive contains more than $MAX_ENTRIES entries"
                    }

                    val normalizedName = entry.name.replace('\\', '/')
                    if (entry.name.contains('\\') || normalizedName.startsWith('/') || normalizedName.split('/').any { it == ".." }) {
                        return "archive contains an unsafe entry path: ${entry.name.take(120)}"
                    }
                    if (normalizedName.endsWith("/plugin.json")) {
                        return "archive cannot contain a nested plugin descriptor: ${entry.name.take(120)}"
                    }
                    if (!entryNames.add(normalizedName)) {
                        return "archive contains a duplicate entry: ${entry.name.take(120)}"
                    }
                    if (entry.isDirectory) {
                        continue
                    }

                    if (entry.size < 0L) {
                        return "archive entry has unknown expanded size: ${entry.name.take(120)}"
                    }
                    if (entry.size > MAX_ENTRY_BYTES) {
                        return "archive entry exceeds $MAX_ENTRY_BYTES bytes: ${entry.name.take(120)}"
                    }
                    expandedBytes += entry.size
                    if (expandedBytes > MAX_EXPANDED_BYTES) {
                        return "archive expanded size exceeds $MAX_EXPANDED_BYTES bytes"
                    }
                }
            }
            null
        }.getOrElse { "invalid ZIP/JAR archive: ${safeMessage(it)}" }
    }

    fun validateV2ArchiveContents(file: File, mainClass: String? = null): String? {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                val expectedMainEntry = mainClass?.replace('.', '/')?.plus(".class")
                var mainEntryFound = expectedMainEntry == null
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name.replace('\\', '/')
                    if (entryName == expectedMainEntry) {
                        mainEntryFound = true
                    }
                    if (entryName.endsWith(".class", ignoreCase = true) && entryName.startsWith("net/rwhps/server/")) {
                        return@use "apiVersion 2 archive cannot bundle CNKD server or plugin API classes: ${entryName.take(160)}"
                    }
                    if (entryName.endsWith(".class", ignoreCase = true)) {
                        val header = zip.getInputStream(entry).use { it.readNBytes(8) }
                        if (header.size != 8 ||
                            (header[0].toInt() and 0xff) != 0xca ||
                            (header[1].toInt() and 0xff) != 0xfe ||
                            (header[2].toInt() and 0xff) != 0xba ||
                            (header[3].toInt() and 0xff) != 0xbe
                        ) {
                            return@use "apiVersion 2 archive contains an invalid class header: ${entryName.take(160)}"
                        }
                        val majorVersion = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                        if (majorVersion > MAX_PLUGIN_CLASS_MAJOR) {
                            return@use "apiVersion 2 archive class must target Java 11 or lower (major=$majorVersion, max=$MAX_PLUGIN_CLASS_MAJOR): ${entryName.take(160)}"
                        }
                    }
                }
                if (!mainEntryFound) {
                    return@use "apiVersion 2 main class is missing from the archive: $expectedMainEntry"
                }
                null
            }
        } catch (error: Throwable) {
            "failed to inspect apiVersion 2 archive contents: ${safeMessage(error)}"
        }
    }

    private fun safeMessage(error: Throwable): String {
        return error.message?.take(160)?.ifBlank { error.javaClass.simpleName } ?: error.javaClass.simpleName
    }

    private const val MAX_PLUGIN_CLASS_MAJOR = 55
}
