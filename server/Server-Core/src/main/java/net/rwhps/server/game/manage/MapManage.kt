/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.manage

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.GameMaps
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.map.OrderedMap
import net.rwhps.server.util.algorithms.Base64
import net.rwhps.server.util.algorithms.digest.DigestUtils
import net.rwhps.server.util.compression.CompressionDecoderUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log
import java.io.File
import java.util.Locale

object MapManage {
    private const val MOD_RUNTIME_ROOT = "cnkd_mod_maps"
    private const val MOD_RUNTIME_MARKER = ".cnkd-runtime-map"
    private val MOD_RUNTIME_FOLDER_PATTERN = Regex("^[0-9a-f]{8}_.+")
    private val runtimeCacheLock = Any()

    @Volatile
    private var lastRuntimeCacheCleanupReport = RuntimeCacheCleanupReport.empty("not_run")

    /** 地图数据  */
    val maps = GameMaps()
    val mapsData = OrderedMap<String, GameMaps.MapData>(8)
    val gameMaps = ObjectMap<String, String>()
    private val mapAliases = ObjectMap<String, String>()

    init {
        with(gameMaps) {
            put("Beachlanding(2p)[byhxyy]", "Beach landing (2p) [by hxyy]@[p2]")
            put("BigIsland(2p)", "Big Island (2p)@[p2]")
            put("DireStraight(2p)[byuber]", "Dire_Straight (2p) [by uber]@[p2]")
            put("FireBridge(2p)[byuber]", "Fire Bridge (2p) [by uber]@[p2]")
            put("Hills(2p)[ByTstis&KPSS]", "Hills_(2p)_[By Tstis & KPSS]@[p2]")
            put("IceIsland(2p)", "Ice Island (2p)@[p2]")
            put("Lake(2p)", "Lake (2p)@[p2]")
            put("SmallIsland(2p)", "Small_Island (2p)@[p2]")
            put("Twocoldsides(2p)", "Two_cold_sides (2p)@[p2]")
            put("Hercules(2vs1p)[byuber]", "Hercules_(2vs1p) [by_uber]@[p3]")
            put("KingoftheMiddle(3p)", "King of the Middle (3p)@[p3]")
            put("Depthcharges(4p)[byhxyy]", "Depth charges (4p) [by hxyy]@[p4]")
            put("Desert(4p)", "Desert (4p)@[p4]")
            put("IceLake(4p)[byhxyy]", "Ice Lake (4p) [by hxyy]@[p4]")
            put("Islandfreeze(4p)[byhxyy]", "Island freeze (4p) [by hxyy]@[p4]")
            put("Islands(4p)", "Islands (4p)@[p4]")
            put("LavaMaze(4p)", "Lava Maze (4p)@[p4]")
            put("LavaVortex(4p)", "Lava Vortex (4p)@[p4]")
            put("MagmaIsland(4p)", "Magma Island (4p)@[p4]")
            put("Manipulation(4p)[ByTstis]", "Manipulation_(4p)_[By Tstis]@[p4]")
            put("Nuclearwar(4p)[byhxyy]", "Nuclear war (4p) [by hxyy]@[p4]")
            put("Crossing(6p)", "Crossing (6p)@[p6]")
            put("ShoretoShore(6p)", "Shore to Shore (6p)@[p6]")
            put("ValleyPass(6p)", "Valley Pass (6p)@[p6]")
            put("BridgesOverLava(8p)", "Bridges Over Lava (8p)@[p8]")
            put("Coastline(8p)[byhxyy]", "Coastline (8p) [by hxyy]@[p8]")
            put("HugeSubdivide(8p)", "Huge Subdivide (8p)@[p8]")
            put("Interlocked(8p)", "Interlocked (8p)@[p8]")
            put("InterlockedLarge(8p)", "Interlocked Large (8p)@[p8]")
            put("IsleRing(8p)", "Isle Ring (8p)@[p8]")
            put("LargeIceOutcrop(8p)", "Large Ice Outcrop (8p)@[p8]")
            put("LavaBiogrid(8p)", "Lava Bio-grid(8p)@[p8]")
            put("LavaDivide(8p)", "Lava Divide(8p)@[p8]")
            put("ManyIslands(8p)", "Many Islands (8p)@[p8]")
            put("RandomIslands(8p)", "Random Islands (8p)@[p8]")
            put("Tornadoeye(8p)[byhxyy]", "Tornado eye (8p) [by hxyy]@[p8]")
            put("TwoSides(8p)", "Two Sides (8p)@[p8]")
            put("Volcano(8p)", "Volcano (8p)@[p8]")
            put("VolcanoCrater(8p)", "Volcano Crater(8p)@[p8]")
            put("TwoSidesRemake(10p)", "Two Sides Remake (10p)@[z;p10]")
            put("ValleyArena(10p)[byuber]", "Valley Arena (10p) [by_uber]@[z;p10]")
            put("ManyIslandsLarge(10p)", "Many Islands Large (10p)@[z;p10]")
            put("CrossingLarge(10p)", "Crossing Large (10p)@[z;p10]")
            put("Kingdoms(10p)[byVulkan]", "Kingdoms (10p) [by Vulkan]@[z;p10]")
            put("LargeLavaDivide(10p)", "Large Lava Divide (10p)@[z;p10]")
            put("EnclosedIsland(10p)", "Enclosed Island (10p)@[z;p10]")
            put("TwoLargeIslands(10p)", "Two_Large_Islands_(10p)@[z;p10]")
            put("Wetlands(10p)", "Wetlands (10p)@[z;p10]")
        }
    }


    fun readMapAndSave() {
        synchronized(runtimeCacheLock) {
        mapAliases.clear()
        mapsData.clear()
        GameMaps.MapData.clearPreparedSupportCache()
        readList(FileUtils.getFolder(Data.ServerMapsPath)) { file, postpone, name ->
            when (postpone) {
                ".tmx" -> try {
                    val mapData = GameMaps.MapData(GameMaps.MapType.CustomMap, GameMaps.MapFileType.File, name)
                    registerMapData(name, mapData, file.name)
                } catch (exception: Exception) {
                    Log.error("read tmx Maps", exception)
                }
                ".zip" -> try {
                    CompressionDecoderUtils.zip(file).use {
                        val zipTmx = it.getTheFileNameOfTheSpecifiedSuffixInTheZip("tmx")
                        zipTmx.eachAll { zipMapName: String ->
                            val mapData = GameMaps.MapData(
                                GameMaps.MapType.CustomMap, GameMaps.MapFileType.Zip, zipMapName, name
                            )
                            registerMapData(zipMapName, mapData, zipMapName, fileNameNoSuffix(zipMapName))
                        }
                    }
                } catch (exception: Exception) {
                    Log.error("ZIP READ", exception)
                }
            }
        }
        readModPackageMaps()
        readList(FileUtils.getFolder(Data.ServerSavePath)) { file, postpone, name ->
            when (postpone) {
                ".rwsave" -> try {
                    mapsData[name] = GameMaps.MapData(GameMaps.MapType.SavedGames, GameMaps.MapFileType.File, name)
                } catch (exception: Exception) {
                    Log.error("read save Maps", exception)
                }
                ".zip" -> try {
                    CompressionDecoderUtils.zip(file).use {
                        val zipSave = it.getTheFileNameOfTheSpecifiedSuffixInTheZip("save")
                        zipSave.eachAll { zipSaveName: String ->
                            mapsData[zipSaveName] = GameMaps.MapData(
                                    GameMaps.MapType.SavedGames, GameMaps.MapFileType.Zip, zipSaveName, name
                            )
                        }
                    }
                } catch (exception: Exception) {
                    Log.error("ZIP READ", exception)
                }
            }
        }
        lastRuntimeCacheCleanupReport = reclaimModMapRuntimeCaches(dryRun = false, force = false)
        }
    }

    private fun readModPackageMaps() {
        ModCompatibilitySupport.beginModMapScan()
        try {
            if (!ModCompatibilitySupport.modPackageMapDiscoveryEnabled()) {
                return
            }

            readList(FileUtils.getFolder(Data.Plugin_Mods_Path)) { file, postpone, name ->
                if (!postpone.equals(".rwmod", ignoreCase = true) && !postpone.equals(".zip", ignoreCase = true)) {
                    return@readList
                }

                ModCompatibilitySupport.recordArchiveCandidate()
                ModCompatibilitySupport.shouldSkipArchive(file)?.let { reason ->
                    ModCompatibilitySupport.recordArchiveSkipped(file, reason)
                    return@readList
                }

                try {
                    ModCompatibilitySupport.recordArchiveScanned(readMapsFromModArchive(file, name))
                } catch (exception: Exception) {
                    ModCompatibilitySupport.recordScanError(file.name, exception)
                    Log.error("RWMod Map READ", exception)
                }
            }

            FileUtils.getFolder(Data.Plugin_Mods_Path).file
                .listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { folder ->
                    try {
                        ModCompatibilitySupport.recordUnpackedFolderScanned(readMapsFromUnpackedModFolder(folder))
                    } catch (exception: Exception) {
                        ModCompatibilitySupport.recordScanError(folder.name, exception)
                        Log.error("RWMod Folder Map READ", exception)
                    }
                }
        } finally {
            ModCompatibilitySupport.finishModMapScan()
        }
    }

    private fun readMapsFromModArchive(file: File, archiveDisplayName: String): Int {
        var discovered = 0
        val archiveIdentity = archiveMapIdentity(file)
        val archiveRuntimeFolder = modRuntimeFolderName(file.nameWithoutExtension, archiveIdentity)
        CompressionDecoderUtils.zip(file).use { decoder ->
            val modInfoEntries = decoder.getFilesByFileNameIgnoreCase("mod-info.txt")
            val mapSources = if (modInfoEntries.isEmpty()) {
                listOf(ModMapSource("", "", archiveDisplayName))
            } else {
                modInfoEntries.entries.map { entry ->
                    val infoPath = entry.key.replace("\\", "/")
                    val infoFileName = infoPath.substringAfterLast("/")
                    val rootPrefix = infoPath.removeSuffix(infoFileName)
                    val infoText = entry.value.toString(Data.UTF_8)
                    ModMapSource(
                        rootPrefix = rootPrefix,
                        sourceFolder = parseModInfoValue(infoText, "maps", "sourceFolder").ifBlank { "maps/" },
                        title = parseModInfoValue(infoText, "mod", "title").ifBlank { archiveDisplayName }
                    )
                }
            }

            decoder.getTheFileNamesOfTheSpecifiedSuffixInTheZipIgnoreCase("tmx", withSuffix = true).forEach { rawEntryName ->
                val entryName = rawEntryName.replace("\\", "/")
                val source = mapSources.firstOrNull { it.matches(entryName) } ?: return@forEach
                val displayName = fileNameNoSuffix(entryName)
                val mapData = GameMaps.MapData(
                    GameMaps.MapType.CustomMap,
                    GameMaps.MapFileType.Zip,
                    displayName,
                    file.name,
                    Data.Plugin_Mods_Path,
                    entryName,
                    runtimeMapPath(archiveRuntimeFolder, entryName, source.rootPrefix)
                )
                registerMapData(
                    displayName,
                    mapData,
                    entryName,
                    entryName.substringAfterLast("/"),
                    fileNameNoSuffix(entryName),
                    "MOD|$archiveIdentity//${clientModMapPath(entryName, source.rootPrefix)}",
                    "MOD|$archiveIdentity//$entryName"
                )
                autoExtractModMap(displayName, mapData)
                ModCompatibilitySupport.recordDiscoveredMap(displayName)
                discovered++
            }
        }
        return discovered
    }

    private fun readMapsFromUnpackedModFolder(folder: File): Int {
        var discovered = 0
        val folderIdentity = unpackedModIdentity(folder)
        val runtimeFolder = modRuntimeFolderName(folder.name, folderIdentity)
        val modInfoFiles = folder.walkTopDown()
            .filter { it.isFile && it.name.equals("mod-info.txt", ignoreCase = true) }
            .toList()
        val mapSources = if (modInfoFiles.isEmpty()) {
            listOf(ModMapSource("", "", folder.name))
        } else {
            modInfoFiles.map { modInfoFile ->
                val infoPath = modInfoFile.relativeTo(folder).path.replace("\\", "/")
                val infoFileName = infoPath.substringAfterLast("/")
                val rootPrefix = infoPath.removeSuffix(infoFileName)
                val infoText = modInfoFile.readText(Data.UTF_8)
                ModMapSource(
                    rootPrefix = rootPrefix,
                    sourceFolder = parseModInfoValue(infoText, "maps", "sourceFolder").ifBlank { "maps/" },
                    title = parseModInfoValue(infoText, "mod", "title").ifBlank { folder.name }
                )
            }
        }

        folder.walkTopDown()
            .filter { it.isFile && it.extension.equals("tmx", ignoreCase = true) }
            .forEach { mapFile ->
                val entryName = mapFile.relativeTo(folder).path.replace("\\", "/")
                val source = mapSources.firstOrNull { it.matches(entryName) } ?: return@forEach
                val displayName = fileNameNoSuffix(entryName)
                val mapData = GameMaps.MapData(
                    GameMaps.MapType.CustomMap,
                    GameMaps.MapFileType.ExternalFile,
                    displayName,
                    mapFile.absolutePath,
                    runtimeMapPath(runtimeFolder, entryName, source.rootPrefix)
                )
                registerMapData(
                    displayName,
                    mapData,
                    entryName,
                    entryName.substringAfterLast("/"),
                    fileNameNoSuffix(entryName),
                    "MOD|$folderIdentity//${clientModMapPath(entryName, source.rootPrefix)}",
                    "MOD|$folderIdentity//$entryName"
                )
                autoExtractModMap(displayName, mapData)
                ModCompatibilitySupport.recordDiscoveredMap(displayName)
                discovered++
            }
        return discovered
    }

    fun resolveCustomMap(rawName: String): ResolvedMapTarget? {
        synchronized(runtimeCacheLock) {
        val normalized = normalizeMapAlias(rawName)
        if (normalized.isBlank()) {
            return null
        }

        mapAliases[normalized]?.let { mapKey ->
            mapsData[mapKey]?.let { return ResolvedMapTarget(it.mapFileName, it) }
        }

        val fileNameOnly = normalizeMapAlias(rawName.substringAfterLast('/').substringAfterLast('\\'))
        mapAliases[fileNameOnly]?.let { mapKey ->
            mapsData[mapKey]?.let { return ResolvedMapTarget(it.mapFileName, it) }
        }

        val loose = normalizeLooseAlias(rawName)
        for (candidate in mapsData.keys) {
            val mapKey = candidate ?: continue
            val mapData = mapsData[mapKey] ?: continue
            if (mapData.mapType != GameMaps.MapType.CustomMap) {
                continue
            }
            if (normalizeLooseAlias(mapData.mapFileName) == loose || normalizeLooseAlias(mapData.runtimeFileName) == loose) {
                return ResolvedMapTarget(mapData.mapFileName, mapData)
            }
        }
        return null
        }
    }

    private fun registerMapData(displayName: String, mapData: GameMaps.MapData, vararg aliases: String) {
        val mapKey = allocateMapKey(displayName, mapData)
        mapsData[mapKey] = mapData
        registerMapAliases(mapKey, displayName, mapData.mapFileName, mapData.runtimeFileName)
        registerMapAliases(mapKey, "/SD/rusted_warfare_maps/${mapData.runtimeFileName}")
        registerMapAliases(mapKey, *aliases)
    }

    private fun registerMapAliases(mapKey: String, vararg aliases: String) {
        aliases.forEach { alias ->
            val normalized = normalizeMapAlias(alias)
            if (normalized.isNotBlank()) {
                // A MOD|hash//path alias identifies one package and may safely replace
                // an earlier fallback alias. Plain filenames remain first-match aliases.
                if (normalized.startsWith("mod|")) {
                    mapAliases[normalized] = mapKey
                } else if (!mapAliases.containsKey(normalized)) {
                    mapAliases[normalized] = mapKey
                }
            }
        }
    }

    /** Mark a generated runtime directory as recently used by a map load. */
    fun recordRuntimeMapUse(runtimeFileName: String) {
        synchronized(runtimeCacheLock) {
            managedRuntimeDirectory(runtimeFileName)?.let { directory ->
                markManagedRuntimeDirectory(directory, runtimeFileName)
            }
        }
    }

    fun <T> withRuntimeCacheLock(action: () -> T): T {
        return synchronized(runtimeCacheLock) { action() }
    }

    /**
     * Reconcile server-generated mod-map runtime directories with the current map index.
     * Unknown directories and active-room maps are never deleted automatically.
     */
    fun reclaimModMapRuntimeCaches(dryRun: Boolean, force: Boolean): RuntimeCacheCleanupReport {
        synchronized(runtimeCacheLock) {
            if (runCatching { !Data.configStartMode.enableModPackageMapRuntimeGc }.getOrDefault(false)) {
                return RuntimeCacheCleanupReport.empty("disabled")
            }
            val root = modRuntimeRootDirectory() ?: return RuntimeCacheCleanupReport.empty("maps_root_unavailable")
            if (!root.exists()) {
                return RuntimeCacheCleanupReport.empty("runtime_root_missing")
            }

            val desiredFolders = mapsData.values
                .mapNotNull { managedRuntimeDirectory(it.runtimeFileName)?.name }
                .toSet()
            val activeFolders = activeRuntimeFolders()
            val graceMillis = runtimeGcGraceHours() * 60L * 60L * 1000L
            val maxDeletes = runtimeGcMaxDeletes()
            var managed = 0
            var orphaned = 0
            var protectedActive = 0
            var protectedGrace = 0
            var unknown = 0
            var deleted = 0
            var failed = 0
            var deletedBytes = 0L
            val candidates = ArrayList<String>()

            root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase(Locale.ROOT) }?.forEach { directory ->
                if (!isManagedRuntimeDirectory(directory)) {
                    unknown++
                    return@forEach
                }
                managed++
                if (directory.name in desiredFolders) {
                    markManagedRuntimeDirectory(directory, "")
                    return@forEach
                }

                orphaned++
                if (directory.name in activeFolders) {
                    protectedActive++
                    return@forEach
                }

                val ageMillis = (System.currentTimeMillis() - runtimeLastUsed(directory)).coerceAtLeast(0L)
                if (!force && ageMillis < graceMillis) {
                    protectedGrace++
                    return@forEach
                }
                candidates += directory.name
            }

            if (!dryRun) {
                candidates.take(maxDeletes).forEach { name ->
                    val directory = File(root, name).canonicalFile
                    if (!isSafeRuntimeChild(root, directory)) {
                        failed++
                        return@forEach
                    }
                    val bytes = directorySize(directory)
                    if (directory.deleteRecursively()) {
                        deleted++
                        deletedBytes += bytes
                    } else {
                        failed++
                    }
                }
            }

            return RuntimeCacheCleanupReport(
                reason = "ok",
                managedDirectories = managed,
                orphanDirectories = orphaned,
                candidateDirectories = candidates.size,
                protectedActiveDirectories = protectedActive,
                protectedGraceDirectories = protectedGrace,
                unknownDirectories = unknown,
                deletedDirectories = deleted,
                failedDirectories = failed,
                deletedBytes = deletedBytes,
                dryRun = dryRun,
                force = force
            )
        }
    }

    fun lastRuntimeCacheCleanupReport(): RuntimeCacheCleanupReport = lastRuntimeCacheCleanupReport

    data class RuntimeCacheCleanupReport(
        val reason: String,
        val managedDirectories: Int,
        val orphanDirectories: Int,
        val candidateDirectories: Int,
        val protectedActiveDirectories: Int,
        val protectedGraceDirectories: Int,
        val unknownDirectories: Int,
        val deletedDirectories: Int,
        val failedDirectories: Int,
        val deletedBytes: Long,
        val dryRun: Boolean,
        val force: Boolean
    ) {
        fun summary(): String {
            return "runtimeCache reason=$reason managed=$managedDirectories orphaned=$orphanDirectories " +
                "candidates=$candidateDirectories activeProtected=$protectedActiveDirectories " +
                "graceProtected=$protectedGraceDirectories unknown=$unknownDirectories " +
                "deleted=$deletedDirectories failed=$failedDirectories bytes=$deletedBytes " +
                "dryRun=$dryRun force=$force"
        }

        companion object {
            fun empty(reason: String): RuntimeCacheCleanupReport {
                return RuntimeCacheCleanupReport(reason, 0, 0, 0, 0, 0, 0, 0, 0, 0L, true, false)
            }
        }
    }

    private fun modRuntimeRootDirectory(): File? {
        return runCatching {
            File(FileUtils.getFolder(Data.ServerMapsPath, false).file, MOD_RUNTIME_ROOT).canonicalFile
        }.getOrNull()
    }

    private fun managedRuntimeDirectory(runtimeFileName: String): File? {
        val normalized = runtimeFileName.replace('\\', '/').trim('/')
        val prefix = "$MOD_RUNTIME_ROOT/"
        if (!normalized.startsWith(prefix, ignoreCase = true)) {
            return null
        }
        val folderName = normalized.substring(prefix.length).substringBefore('/')
        if (folderName.isBlank()) {
            return null
        }
        val root = modRuntimeRootDirectory() ?: return null
        val directory = File(root, folderName).canonicalFile
        return directory.takeIf { isSafeRuntimeChild(root, it) }
    }

    private fun isSafeRuntimeChild(root: File, child: File): Boolean {
        return child.parentFile?.canonicalFile == root.canonicalFile
    }

    private fun isManagedRuntimeDirectory(directory: File): Boolean {
        if (File(directory, MOD_RUNTIME_MARKER).isFile) {
            return true
        }
        return MOD_RUNTIME_FOLDER_PATTERN.matches(directory.name) &&
            File(directory, "maps").isDirectory &&
            File(directory, "maps").walkTopDown().any { it.isFile && it.extension.equals("tmx", ignoreCase = true) }
    }

    private fun markManagedRuntimeDirectory(directory: File, runtimeFileName: String) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val marker = File(directory, MOD_RUNTIME_MARKER)
        if (!marker.exists()) {
            runCatching {
                marker.writeText(
                    "version=1\n" +
                        "runtimeFile=${runtimeFileName.replace('\\', '/')}\n" +
                        "createdAt=${System.currentTimeMillis()}\n",
                    Charsets.UTF_8
                )
            }
        }
        marker.setLastModified(System.currentTimeMillis())
    }

    private fun runtimeLastUsed(directory: File): Long {
        val marker = File(directory, MOD_RUNTIME_MARKER)
        return if (marker.isFile) marker.lastModified() else directory.lastModified()
    }

    private fun activeRuntimeFolders(): Set<String> {
        if (!HeadlessModuleManage.initHPS()) {
            return emptySet()
        }
        val runtimeFileName = runCatching { HeadlessModuleManage.hps.room.maps.mapData?.runtimeFileName }.getOrNull()
            ?: return emptySet()
        return managedRuntimeDirectory(runtimeFileName)?.let { setOf(it.name) } ?: emptySet()
    }

    private fun directorySize(directory: File): Long {
        return directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun runtimeGcGraceHours(): Long {
        return runCatching { Data.configStartMode.modPackageMapRuntimeGcGraceHours.coerceIn(0, 8760).toLong() }
            .getOrDefault(24L)
    }

    private fun runtimeGcMaxDeletes(): Int {
        return runCatching { Data.configStartMode.modPackageMapRuntimeGcMaxDeletePerRun.coerceIn(1, 1000) }
            .getOrDefault(20)
    }

    fun displayNameForKey(mapKey: String): String {
        return mapsData[mapKey]?.mapFileName ?: mapKey
    }

    private fun allocateMapKey(displayName: String, mapData: GameMaps.MapData): String {
        if (!mapsData.containsKey(displayName)) {
            return displayName
        }

        val baseKey = "@mod:${shortHash(mapData.runtimeFileName)}"
        if (!mapsData.containsKey(baseKey)) {
            return baseKey
        }

        var index = 2
        while (index <= 10000) {
            val indexedKey = "$baseKey:$index"
            if (!mapsData.containsKey(indexedKey)) {
                return indexedKey
            }
            index++
        }
        return "$baseKey:${System.currentTimeMillis()}"
    }

    private fun autoExtractModMap(displayName: String, mapData: GameMaps.MapData) {
        if (!ModCompatibilitySupport.modPackageMapAutoExtractionEnabled()) {
            return
        }
        runCatching {
            mapData.readMap(cleanOnRelease = false)
            ModCompatibilitySupport.recordAutoExtraction(displayName)
        }.onFailure { error ->
            ModCompatibilitySupport.recordAutoExtractionFailure(displayName, error)
            Log.warn("[ModMap] Failed to auto-extract mod map: $displayName", error)
        }
    }

    private fun parseModInfoValue(text: String, section: String, key: String): String {
        var inTargetSection = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) {
                return@forEach
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inTargetSection = line.substring(1, line.length - 1).equals(section, ignoreCase = true)
                return@forEach
            }
            if (inTargetSection) {
                val delimiterIndex = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=')
                if (delimiterIndex > 0 && line.substring(0, delimiterIndex).trim().equals(key, ignoreCase = true)) {
                    return line.substring(delimiterIndex + 1).trim()
                }
            }
        }
        return ""
    }

    private fun sanitizeFileName(value: String): String {
        val invalidChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        return value
            .map { char -> if (char.code < 32 || char in invalidChars) '_' else char }
            .joinToString("")
            .trim()
    }

    private fun fileNameNoSuffix(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast(".", fileName)
    }

    private fun modRuntimeFolderName(sourceName: String, stableKey: String): String {
        val label = sanitizeFileName(sourceName).take(32).ifBlank { "mod" }
        return "${shortHash(stableKey)}_$label"
    }

    private fun archiveMapIdentity(file: File): String {
        return runCatching {
            DigestUtils.sha256Hex(file).uppercase(Locale.ROOT)
        }.getOrElse {
            "PATH_${shortHash(file.absolutePath + "|" + file.length() + "|" + file.lastModified())}"
        }
    }

    private fun unpackedModIdentity(folder: File): String {
        val signature = buildString {
            folder.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(folder).path.replace("\\", "/") to it }
                .sortedBy { it.first.lowercase(Locale.ROOT) }
                .forEach { (relativePath, file) ->
                    append(relativePath)
                    append('|')
                    append(file.length())
                    append('|')
                    append(file.lastModified())
                    append('\n')
                }
        }
        return "FOLDER_${shortHash(signature)}"
    }

    /**
     * Keep the original map basename and relative directory in the runtime path.
     * The game derives sidecar names such as <map>_map.png from the runtime
     * basename, so an internal hash must not be prepended to the map filename.
     */
    private fun runtimeMapPath(runtimeFolder: String, entryName: String, rootPrefix: String): String {
        val safeRelativeEntry = clientModMapPath(entryName, rootPrefix)
            .split('/')
            .map { sanitizeRuntimePathSegment(it) }
            .filter { it.isNotBlank() }
            .joinToString("/")
            .ifBlank { "mod_map.tmx" }

        val runtimeEntry = safeRelativeEntry.removeMapSuffix()
        return "$MOD_RUNTIME_ROOT/$runtimeFolder/$runtimeEntry"
    }

    private fun String.removeMapSuffix(): String {
        return if (endsWith(".tmx", ignoreCase = true)) dropLast(".tmx".length) else this
    }

    private fun relativeMapEntryPath(entryName: String, rootPrefix: String): String {
        val normalizedEntry = entryName.replace("\\", "/").trim('/')
        val normalizedRoot = rootPrefix.replace("\\", "/").trim('/')
        return if (
            normalizedRoot.isNotBlank() &&
            normalizedEntry.startsWith("$normalizedRoot/", ignoreCase = true)
        ) {
            normalizedEntry.substring(normalizedRoot.length + 1)
        } else {
            normalizedEntry
        }
    }

    private fun clientModMapPath(entryName: String, rootPrefix: String): String {
        val relative = relativeMapEntryPath(entryName, rootPrefix)
        if (relative.startsWith("maps/", ignoreCase = true)) {
            return relative
        }
        val mapsMarker = relative.indexOf("/maps/", ignoreCase = true)
        return if (mapsMarker >= 0) relative.substring(mapsMarker + 1) else relative
    }

    private fun sanitizeRuntimePathSegment(value: String): String {
        val invalidChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        return value
            .map { char -> if (char.code < 32 || char in invalidChars) '_' else char }
            .joinToString("")
            .trim()
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "mod_map"
    }

    private fun shortHash(value: String): String {
        return Integer.toHexString(value.lowercase(Locale.ROOT).hashCode()).padStart(8, '0').takeLast(8)
    }

    private fun normalizeMapAlias(rawName: String): String {
        var value = rawName
            .trim()
            .trim('"', '\'')
            .trimStart('\u0001')
            .replace("\\", "/")
            .trim()

        if (value.startsWith("MOD|", ignoreCase = true)) {
            val separator = value.indexOf("//")
            if (separator > 4) {
                val modHash = value.substring(4, separator).trim().lowercase(Locale.ROOT)
                val mapPath = value.substring(separator + 2)
                value = "mod|$modHash//$mapPath"
            } else {
                value = value.substringAfter("|", value)
            }
        } else if (value.startsWith("NEW_PATH|", ignoreCase = true)) {
            value = value.substringAfter("|", value)
        }

        listOf(
            "/SD/rusted_warfare_maps/",
            "/SD/rustedWarfare/maps/",
            "rusted_warfare_maps/",
            "rustedWarfare/maps/"
        ).forEach { prefix ->
            if (value.startsWith(prefix, ignoreCase = true)) {
                value = value.substring(prefix.length)
            }
        }

        value = value.trim().trim('/')
        val modSeparator = value.indexOf("//")
        if (value.startsWith("mod|", ignoreCase = true) && modSeparator > 4) {
            val modPrefix = value.substring(0, modSeparator).lowercase(Locale.ROOT)
            val mapPath = value.substring(modSeparator + 2)
                .trim('/')
                .removeSuffixIgnoreCase(".tmx")
                .removeSuffixIgnoreCase(".rwsave")
            return "$modPrefix//$mapPath".lowercase(Locale.ROOT)
        }
        return value
            .removeSuffixIgnoreCase(".tmx")
            .removeSuffixIgnoreCase(".rwsave")
            .lowercase(Locale.ROOT)
    }

    private fun normalizeLooseAlias(rawName: String): String {
        return normalizeMapAlias(rawName)
            .substringAfterLast("/")
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
            .lowercase(Locale.ROOT)
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String {
        return if (endsWith(suffix, ignoreCase = true)) {
            substring(0, length - suffix.length)
        } else {
            this
        }
    }

    private fun readList(fileUtils: FileUtils, process: (File, String, String)->Unit) {
        val list = fileUtils.fileListNotNullSizeSort
        list.eachAll { e: File ->
            val original = if (Base64.isBase64(e.name)) Base64.decodeString(e.name) else e.name
            val dotIndex = original.lastIndexOf(".")
            if (dotIndex <= 0 || dotIndex == original.length - 1) {
                return@eachAll
            }
            val postpone = original.substring(original.lastIndexOf("."))
            val name = original.substring(0, original.length - postpone.length)
            process(e, postpone, name)
        }
    }

    private data class ModMapSource(
        val rootPrefix: String,
        val sourceFolder: String,
        val title: String
    ) {
        fun matches(entryName: String): Boolean {
            val normalizedEntryName = entryName.replace("\\", "/").trimStart('/')
            val normalizedRootPrefix = rootPrefix.replace("\\", "/").trimStart('/')
            val normalizedSourceFolder = sourceFolder
                .replace("\\", "/")
                .trim('/')
            if (normalizedSourceFolder.isBlank()) {
                val relative = if (normalizedRootPrefix.isBlank()) {
                    normalizedEntryName
                } else {
                    normalizedEntryName.removePrefix(normalizedRootPrefix)
                }
                return relative.startsWith("maps/", ignoreCase = true) ||
                    relative.contains("/maps/", ignoreCase = true)
            }

            val expectedPrefix = normalizedRootPrefix + normalizedSourceFolder + "/"
            return normalizedEntryName.startsWith(expectedPrefix, ignoreCase = true)
        }
    }

    data class ResolvedMapTarget(
        val mapName: String,
        val mapData: GameMaps.MapData
    )
}
