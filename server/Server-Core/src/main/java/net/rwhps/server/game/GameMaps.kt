/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.ModCompatibilitySupport
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.GameMaps.MapFileType.ExternalFile
import net.rwhps.server.game.GameMaps.MapFileType.File
import net.rwhps.server.game.GameMaps.MapFileType.WebDownLoad
import net.rwhps.server.game.GameMaps.MapFileType.Zip
import net.rwhps.server.util.algorithms.Base64
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.FileUtils.Companion.getFolder
import net.rwhps.server.util.log.Log.error
import java.io.File as JavaFile

/**
 * Runtime map selection state.
 *
 * @author Dr (dr@der.kim)
 */
class GameMaps {
    var mapType = MapType.DefaultMap
    var mapData: MapData? = null
    var mapName = "Crossing Large (10p)"
    var mapPlayer = "[z;p10]"

    enum class MapType(val fileType: String) {
        DefaultMap(".tmx"),
        CustomMap(".tmx"),
        SavedGames(".rwsave")
    }

    enum class MapFileType {
        File,
        Zip,
        ExternalFile,
        WebDownLoad
    }

    class MapData {
        val mapType: MapType
        val mapFileName: String
        val runtimeFileName: String

        private val mapFileType: MapFileType
        private val zipFileName: String?
        private val zipSourcePath: String?
        private val zipEntryName: String?
        private val sourceFilePath: String?
        private var mapClean = false
        private var mapFile: FileUtils? = null
        private var cachedMapBytes: ByteArray? = null
        private var cachedMapLength = -1L
        private var cachedMapModified = -1L
        private var cachedMapPath = ""

        constructor(mapType: MapType, mapFileType: MapFileType, mapFileName: String) {
            this.mapType = mapType
            this.mapFileType = mapFileType
            this.mapFileName = mapFileName
            this.runtimeFileName = "$mapFileName${mapType.fileType}"
            zipFileName = null
            zipSourcePath = null
            zipEntryName = null
            sourceFilePath = null
        }

        constructor(mapType: MapType, mapFileType: MapFileType, mapFileName: String, zipFileName: String) {
            this.mapType = mapType
            this.mapFileType = mapFileType
            this.mapFileName = mapFileName
            this.runtimeFileName = "$mapFileName${mapType.fileType}"
            this.zipFileName = zipFileName
            zipSourcePath = null
            zipEntryName = null
            sourceFilePath = null
        }

        constructor(
            mapType: MapType,
            mapFileType: MapFileType,
            mapFileName: String,
            sourceFilePath: String,
            runtimeFileNameNoSuffix: String
        ) {
            this.mapType = mapType
            this.mapFileType = mapFileType
            this.mapFileName = mapFileName
            this.runtimeFileName = "${sanitizeRuntimeFileName(runtimeFileNameNoSuffix)}${mapType.fileType}"
            this.zipFileName = null
            this.zipSourcePath = null
            this.zipEntryName = null
            this.sourceFilePath = sourceFilePath
        }

        constructor(
            mapType: MapType,
            mapFileType: MapFileType,
            mapFileName: String,
            zipFileName: String,
            zipSourcePath: String,
            zipEntryName: String
        ) {
            this.mapType = mapType
            this.mapFileType = mapFileType
            this.mapFileName = mapFileName
            this.runtimeFileName = "${sanitizeRuntimeFileName(mapFileName)}${mapType.fileType}"
            this.zipFileName = zipFileName
            this.zipSourcePath = zipSourcePath
            this.zipEntryName = zipEntryName
            this.sourceFilePath = null
        }

        constructor(
            mapType: MapType,
            mapFileType: MapFileType,
            mapFileName: String,
            zipFileName: String,
            zipSourcePath: String,
            zipEntryName: String,
            runtimeFileNameNoSuffix: String
        ) {
            this.mapType = mapType
            this.mapFileType = mapFileType
            this.mapFileName = mapFileName
            this.runtimeFileName = "${sanitizeRuntimeFileName(runtimeFileNameNoSuffix)}${mapType.fileType}"
            this.zipFileName = zipFileName
            this.zipSourcePath = zipSourcePath
            this.zipEntryName = zipEntryName
            this.sourceFilePath = null
        }

        fun readMap(cleanOnRelease: Boolean = true) {
            MapManage.withRuntimeCacheLock {
                readMapLocked(cleanOnRelease)
            }
        }

        /**
         * Return the prepared map file used by the game engine.
         *
         * The engine needs the TMX bytes in the START_GAME packet for maps that
         * are not part of the client's built-in map set. Keep this behind the
         * same runtime cache lock as readMap() so a cleanup cannot race the
         * packet construction.
         */
        fun readMapBytes(cleanOnRelease: Boolean = false): ByteArray {
            return MapManage.withRuntimeCacheLock {
                readMapLocked(cleanOnRelease)
                val file = mapFile?.file
                    ?: throw IllegalStateException("地图运行文件未准备: $runtimeFileName")
                val length = file.length()
                val modified = file.lastModified()
                val path = file.absolutePath
                val cached = cachedMapBytes
                if (cached != null && length == cachedMapLength && modified == cachedMapModified && path == cachedMapPath) {
                    // The caller owns its returned bytes; no mutable cached array escapes.
                    cached.copyOf()
                } else {
                    val bytes = file.readBytes()
                    // Bound retention for unusually large maps; normal TMX files reuse one read.
                    cachedMapBytes = if (bytes.size <= 16 * 1024 * 1024) bytes else null
                    cachedMapLength = length
                    cachedMapModified = modified
                    cachedMapPath = path
                    bytes.copyOf()
                }
            }
        }

        private fun readMapLocked(cleanOnRelease: Boolean) {
            if (!mapClean && mapFile?.file?.isFile == true) {
                MapManage.recordRuntimeMapUse(runtimeFileName)
                return
            }

            val outputFileUtil = if (mapType == MapType.SavedGames) {
                getFolder(Data.ServerSavePath, true)
            } else {
                getFolder(Data.ServerMapsPath, true)
            }
            val archiveFileUtil = zipSourcePath?.let { getFolder(it, true) } ?: outputFileUtil

            val resolvedFileName = runtimeFileName
            val resolvedFile = outputFileUtil.toFile(resolvedFileName)
            if (resolvedFile.exists()) {
                mapFile = resolvedFile
                mapClean = false
                MapManage.recordRuntimeMapUse(runtimeFileName)
                extractZipMapSupportFiles(archiveFileUtil, outputFileUtil)
                extractExternalMapSupportFiles(outputFileUtil)
                return
            }

            if (mapFileType == File) {
                val sourceFile = outputFileUtil.fileListNotNullSizeSort.find { candidate ->
                    val originalName = if (Base64.isBase64(candidate.name)) Base64.decodeString(candidate.name) else candidate.name
                    originalName.equals(resolvedFileName, ignoreCase = true)
                } ?: throw IllegalStateException("未找到地图文件: $resolvedFileName")
                copyMapFileToRuntime(sourceFile, resolvedFile, archiveFileUtil, outputFileUtil, cleanOnRelease)
                return
            }

            if (mapFileType == ExternalFile) {
                copyMapFileToRuntime(JavaFile(sourceFilePath!!), resolvedFile, archiveFileUtil, outputFileUtil, cleanOnRelease)
                return
            }

            val bytesMap = when (mapFileType) {
                File -> {
                    null
                }

                Zip -> {
                    try {
                        CompressionDecoderUtils.zip(archiveFileUtil.toFile(zipFileName!!).file)
                            .use { decoder ->
                                zipEntryName?.let { entryName ->
                                    decoder.getZipNameBytes(entryName, ignoreCase = true)
                                } ?: decoder.getTheFileBytesOfTheSpecifiedSuffixInTheZip(this)
                            }
                    } catch (e: Exception) {
                        error("Read Map Bytes Error", e)
                        null
                    }
                }

                ExternalFile -> {
                    null
                }

                WebDownLoad -> {
                    throw IllegalStateException("暂不支持从网络源加载地图: $resolvedFileName")
                }
            } ?: throw IllegalStateException("地图数据为空或读取失败: $resolvedFileName")

            mapClean = cleanOnRelease
            mapFile = resolvedFile
            mapFile!!.writeFileByte(bytesMap)
            MapManage.recordRuntimeMapUse(runtimeFileName)
            extractZipMapSupportFiles(archiveFileUtil, outputFileUtil)
            extractExternalMapSupportFiles(outputFileUtil)
        }

        private fun copyMapFileToRuntime(
            sourceFile: JavaFile,
            resolvedFile: FileUtils,
            archiveFileUtil: FileUtils,
            outputFileUtil: FileUtils,
            cleanOnRelease: Boolean
        ) {
            resolvedFile.file.parentFile?.mkdirs()
            sourceFile.copyTo(resolvedFile.file, overwrite = true)
            mapClean = cleanOnRelease
            mapFile = resolvedFile
            MapManage.recordRuntimeMapUse(runtimeFileName)
            extractZipMapSupportFiles(archiveFileUtil, outputFileUtil)
            extractExternalMapSupportFiles(outputFileUtil)
        }

        fun clean() {
            MapManage.withRuntimeCacheLock {
                cachedMapBytes = null
                cachedMapLength = -1L
                if (mapClean) {
                    mapFile?.delete()
                    mapFile = null
                }
            }
        }

        private fun sanitizeRuntimeFileName(value: String): String {
            val invalidChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
            val normalized = value.replace("\\", "/").trim('/')
            val sanitized = normalized
                .split("/")
                .map { segment ->
                    segment
                        .map { char -> if (char.code < 32 || char in invalidChars) '_' else char }
                        .joinToString("")
                        .trim()
                        .takeIf { it.isNotBlank() && it != "." && it != ".." }
                        ?: "mod_map"
                }
                .joinToString("/")
                .trim('/')
            return sanitized
                .ifBlank { "mod_map" }
        }

        private fun runtimeSupportFolder(outputFileUtil: FileUtils): JavaFile {
            val runtimeFolder = runtimeFileName
                .replace("\\", "/")
                .substringBeforeLast("/", "")
                .trim('/')
            return if (runtimeFolder.isBlank()) {
                outputFileUtil.file
            } else {
                JavaFile(outputFileUtil.file, runtimeFolder).apply { mkdirs() }
            }
        }

        private fun sanitizeSupportRelativePath(path: String): String {
            val invalidChars = setOf(':', '*', '?', '"', '<', '>', '|')
            return path
                .replace("\\", "/")
                .trim('/')
                .split("/")
                .mapNotNull { segment ->
                    val safe = segment
                        .map { char -> if (char.code < 32 || char in invalidChars || char == '/' || char == '\\') '_' else char }
                        .joinToString("")
                        .trim()
                    safe.takeIf { it.isNotBlank() && it != "." && it != ".." }
                }
                .joinToString("/")
        }

        private fun hasSupportSuffix(fileName: String): Boolean {
            return MAP_SUPPORT_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }
        }

        private fun extractZipMapSupportFiles(archiveFileUtil: FileUtils, outputFileUtil: FileUtils) {
            if (mapFileType != Zip || zipFileName == null || zipEntryName == null) {
                return
            }
            if (!ModCompatibilitySupport.modPackageMapSupportExtractionEnabled()) {
                return
            }

            val entryFolder = zipEntryName
                .replace("\\", "/")
                .substringBeforeLast("/", "")
            if (entryFolder.isBlank()) {
                return
            }

            val archiveFile = archiveFileUtil.toFile(zipFileName).file
            val targetFolder = runtimeSupportFolder(outputFileUtil)
            val supportKey = "${archiveFile.absolutePath}|$entryFolder|${targetFolder.absolutePath}"
            synchronized(preparedZipMapSupportFolders) {
                if (!preparedZipMapSupportFolders.add(supportKey)) {
                    return
                }
            }

            try {
                CompressionDecoderUtils.zip(archiveFile).use { decoder ->
                    val extracted = decoder.extractEntriesUnderPrefix(
                        "$entryFolder/",
                        targetFolder,
                        excludeEntries = setOf(zipEntryName),
                        allowedSuffixes = MAP_SUPPORT_SUFFIXES
                    )
                    ModCompatibilitySupport.recordSupportExtraction(archiveFile.name, entryFolder, extracted)
                }
            } catch (e: Exception) {
                synchronized(preparedZipMapSupportFolders) {
                    preparedZipMapSupportFolders.remove(supportKey)
                }
                throw e
            }
        }

        private fun extractExternalMapSupportFiles(outputFileUtil: FileUtils) {
            if (mapFileType != ExternalFile || sourceFilePath == null) {
                return
            }
            if (!ModCompatibilitySupport.modPackageMapSupportExtractionEnabled()) {
                return
            }

            val sourceFile = JavaFile(sourceFilePath)
            val sourceFolder = sourceFile.parentFile ?: return
            val targetFolder = runtimeSupportFolder(outputFileUtil)
            val supportKey = "${sourceFolder.absolutePath}|${targetFolder.absolutePath}"
            synchronized(preparedZipMapSupportFolders) {
                if (!preparedZipMapSupportFolders.add(supportKey)) {
                    return
                }
            }

            try {
                var extracted = 0
                sourceFolder.walkTopDown()
                    .filter { it.isFile && !it.equals(sourceFile) && hasSupportSuffix(it.name) }
                    .forEach { supportFile ->
                        val relativePath = sanitizeSupportRelativePath(supportFile.relativeTo(sourceFolder).path)
                        if (relativePath.isBlank()) {
                            return@forEach
                        }
                        val outputFile = JavaFile(targetFolder, relativePath).canonicalFile
                        val canonicalTargetFolder = targetFolder.canonicalFile
                        if (!outputFile.path.startsWith(canonicalTargetFolder.path + JavaFile.separator)) {
                            return@forEach
                        }
                        outputFile.parentFile?.mkdirs()
                        supportFile.copyTo(outputFile, overwrite = true)
                        extracted++
                    }
                ModCompatibilitySupport.recordSupportExtraction(sourceFolder.name, sourceFile.name, extracted)
            } catch (e: Exception) {
                synchronized(preparedZipMapSupportFolders) {
                    preparedZipMapSupportFolders.remove(supportKey)
                }
                throw e
            }
        }

        companion object {
            private val preparedZipMapSupportFolders = HashSet<String>()
            private val MAP_SUPPORT_SUFFIXES = setOf(
                ".tsx",
                ".png",
                ".jpg",
                ".jpeg",
                ".webp",
                ".bmp",
                ".ogg",
                ".wav",
                ".ini",
                ".template",
                ".properties"
            )

            fun clearPreparedSupportCache() {
                synchronized(preparedZipMapSupportFolders) {
                    preparedZipMapSupportFolders.clear()
                }
            }
        }
    }
}
