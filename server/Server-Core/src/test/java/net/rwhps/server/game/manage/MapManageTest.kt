/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.manage

import net.rwhps.server.data.bean.BeanStartModeConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.GameMaps
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionPacket
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.algorithms.digest.DigestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class MapManageTest {
    @Test
    fun readMapAndSaveDiscoversRwmodMapsAndAutoExtractsRuntimeFiles() {
        val previousRoot = FileUtils.getFolder().path
        val previousConfig = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        val tempRoot = Files.createTempDirectory("rw-hps-mod-map").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            Log.set("OFF")
            Data.configStartMode = BeanStartModeConfig(
                enableModPackageMapDiscovery = true,
                enableModPackageMapSupportExtraction = true,
                enableModPackageMapAutoExtraction = true
            )

            File(tempRoot, "data/mods").mkdirs()
            File(tempRoot, "data/maps").mkdirs()
            File(tempRoot, "data/saves").mkdirs()
            File(tempRoot, "data/mods/sample.rwmod").writeBytes(buildSampleRwmod())

            MapManage.mapsData.clear()
            MapManage.readMapAndSave()

            val mapData = MapManage.mapsData["Space Test Map"]
            assertNotNull(mapData)
            assertFalse(MapManage.mapsData.containsKey("Should Not Discover"))

            val runtimeFile = File(tempRoot, "data/maps/${mapData!!.runtimeFileName}")
            assertTrue(runtimeFile.exists(), "mod map should be prepared during startup scan")
            assertTrue(
                mapData.runtimeFileName.replace('\\', '/').endsWith("/maps/Space Test Map.tmx"),
                "runtime path should preserve the original map basename and relative map folder"
            )
            assertFalse(
                mapData.runtimeFileName.endsWith(".tmx.tmx", ignoreCase = true),
                "runtime path must not duplicate the map suffix"
            )
            assertEquals("Space Test Map", mapData.mapFileName)
            assertTrue(File(runtimeFile.parentFile, "Space Test Map_map.png").exists())
            assertTrue(File(runtimeFile.parentFile, "tiles/misc.tsx").exists())

            assertNotNull(MapManage.resolveCustomMap("MOD|sampleHash//maps/Space Test Map.tmx"))
            assertNotNull(MapManage.resolveCustomMap("/SD/rusted_warfare_maps/${mapData.runtimeFileName}"))

            val diagnostics = ModCompatibilitySupport.buildDiagnostics().joinToString("\n")
            assertTrue(diagnostics.contains("modMaps.discovered=1"))
            assertTrue(diagnostics.contains("modMaps.archiveCandidates=1"))
            assertTrue(diagnostics.contains("modMaps.autoExtractions=1"))

            val firstModified = runtimeFile.lastModified()
            MapManage.readMapAndSave()
            val reloadedMapData = MapManage.mapsData["Space Test Map"]
            assertNotNull(reloadedMapData)
            assertEquals(1, MapManage.mapsData.size)
            assertEquals(mapData.runtimeFileName, reloadedMapData!!.runtimeFileName)
            assertEquals(firstModified, File(tempRoot, "data/maps/${reloadedMapData.runtimeFileName}").lastModified())
        } finally {
            MapManage.mapsData.clear()
            Data.configStartMode = previousConfig
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun readMapAndSaveHonorsModMapDiscoverySwitch() {
        val previousRoot = FileUtils.getFolder().path
        val previousConfig = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        val tempRoot = Files.createTempDirectory("rw-hps-mod-map-disabled").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            Log.set("OFF")
            Data.configStartMode = BeanStartModeConfig(enableModPackageMapDiscovery = false)

            File(tempRoot, "data/mods").mkdirs()
            File(tempRoot, "data/maps").mkdirs()
            File(tempRoot, "data/saves").mkdirs()
            File(tempRoot, "data/mods/sample.rwmod").writeBytes(buildSampleRwmod())

            MapManage.mapsData.clear()
            MapManage.readMapAndSave()

            assertFalse(MapManage.mapsData.containsKey("Space Test Map"))
            val diagnostics = ModCompatibilitySupport.buildDiagnostics().joinToString("\n")
            assertTrue(diagnostics.contains("modMaps.discoveryEnabled=false"))
        } finally {
            MapManage.mapsData.clear()
            Data.configStartMode = previousConfig
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun readMapAndSaveCanDiscoverWithoutAutoExtraction() {
        val previousRoot = FileUtils.getFolder().path
        val previousConfig = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        val tempRoot = Files.createTempDirectory("rw-hps-mod-map-no-auto").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            Log.set("OFF")
            Data.configStartMode = BeanStartModeConfig(
                enableModPackageMapDiscovery = true,
                enableModPackageMapSupportExtraction = true,
                enableModPackageMapAutoExtraction = false
            )

            File(tempRoot, "data/mods").mkdirs()
            File(tempRoot, "data/maps").mkdirs()
            File(tempRoot, "data/saves").mkdirs()
            File(tempRoot, "data/mods/sample.rwmod").writeBytes(buildSampleRwmod())

            MapManage.mapsData.clear()
            MapManage.readMapAndSave()

            val mapData = MapManage.mapsData["Space Test Map"]
            assertNotNull(mapData)
            assertFalse(File(tempRoot, "data/maps/${mapData!!.runtimeFileName}").exists())

            mapData.readMap(cleanOnRelease = false)
            assertTrue(File(tempRoot, "data/maps/${mapData.runtimeFileName}").exists())
        } finally {
            MapManage.mapsData.clear()
            Data.configStartMode = previousConfig
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun duplicateModMapNamesKeepOriginalDisplayNameAndUseInternalKeys() {
        val previousRoot = FileUtils.getFolder().path
        val previousConfig = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        val tempRoot = Files.createTempDirectory("rw-hps-mod-map-duplicates").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            Log.set("OFF")
            Data.configStartMode = BeanStartModeConfig(
                enableModPackageMapDiscovery = true,
                enableModPackageMapSupportExtraction = true,
                enableModPackageMapAutoExtraction = true
            )

            File(tempRoot, "data/mods").mkdirs()
            File(tempRoot, "data/maps").mkdirs()
            File(tempRoot, "data/saves").mkdirs()
            File(tempRoot, "data/mods/alpha.rwmod").writeBytes(buildSampleRwmod("Alpha Mod", "alpha"))
            File(tempRoot, "data/mods/beta.rwmod").writeBytes(buildSampleRwmod("Beta Mod", "beta"))
            val alphaHash = DigestUtils.sha256Hex(File(tempRoot, "data/mods/alpha.rwmod")).uppercase()

            MapManage.mapsData.clear()
            MapManage.readMapAndSave()

            val matching = MapManage.mapsData.entries
                .filter { it.value.mapFileName == "Space Test Map" }
            assertEquals(2, matching.size)
            assertTrue(matching.all { it.key == "Space Test Map" || it.key.startsWith("@mod:") })
            assertTrue(matching.all { !it.value.mapFileName.contains("[") })
            assertEquals(2, matching.map { it.value.runtimeFileName }.toSet().size)
            val exactTarget = MapManage.resolveCustomMap("MOD|$alphaHash//maps/Space Test Map.tmx")
            assertTrue(exactTarget?.mapData?.runtimeFileName?.contains("alpha", ignoreCase = true) == true)
            matching.forEach { (_, data) ->
                assertTrue(File(tempRoot, "data/maps/${data.runtimeFileName}").exists())
                assertEquals("Space Test Map", MapManage.resolveCustomMap(data.runtimeFileName)?.mapName)
            }
        } finally {
            MapManage.mapsData.clear()
            Data.configStartMode = previousConfig
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun runtimeCacheGcRemovesOnlyExpiredManagedOrphans() {
        val previousRoot = FileUtils.getFolder().path
        val previousConfig = runCatching { Data.configStartMode }.getOrDefault(BeanStartModeConfig())
        val tempRoot = Files.createTempDirectory("rw-hps-mod-map-gc").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            Log.set("OFF")
            Data.configStartMode = BeanStartModeConfig(
                enableModPackageMapRuntimeGc = true,
                modPackageMapRuntimeGcGraceHours = 24,
                modPackageMapRuntimeGcMaxDeletePerRun = 20
            )
            MapManage.mapsData.clear()

            val runtimeRoot = File(tempRoot, "data/maps/cnkd_mod_maps")
            val orphan = File(runtimeRoot, "12345678_removed/maps/Removed.tmx")
            orphan.parentFile.mkdirs()
            orphan.writeText("map")
            val marker = File(orphan.parentFile.parentFile, ".cnkd-runtime-map")
            marker.writeText("version=1\n")
            marker.setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1000L)

            val unknown = File(runtimeRoot, "do-not-touch/Unknown.tmx")
            unknown.parentFile.mkdirs()
            unknown.writeText("unknown")
            unknown.parentFile.setLastModified(System.currentTimeMillis() - 48L * 60L * 60L * 1000L)

            val dryRun = MapManage.reclaimModMapRuntimeCaches(dryRun = true, force = false)
            assertEquals(1, dryRun.orphanDirectories)
            assertEquals(1, dryRun.candidateDirectories)
            assertEquals(1, dryRun.unknownDirectories)
            assertEquals(0, dryRun.deletedDirectories)
            assertTrue(orphan.parentFile.parentFile.exists())

            val cleanup = MapManage.reclaimModMapRuntimeCaches(dryRun = false, force = false)
            assertEquals(1, cleanup.deletedDirectories)
            assertFalse(orphan.parentFile.parentFile.exists())
            assertTrue(unknown.parentFile.exists())
        } finally {
            MapManage.mapsData.clear()
            Data.configStartMode = previousConfig
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun mapByteCacheReturnsOwnedCopiesAndInvalidatesAfterFileChanges() {
        val previousRoot = FileUtils.getFolder().path
        val tempRoot = Files.createTempDirectory("cnkd-map-byte-cache").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            val maps = File(tempRoot, "data/maps").apply { mkdirs() }
            val file = File(maps, "cache-test.tmx").apply { writeText("first-map") }
            val map = net.rwhps.server.game.GameMaps.MapData(
                net.rwhps.server.game.GameMaps.MapType.CustomMap,
                net.rwhps.server.game.GameMaps.MapFileType.File, "cache-test"
            )
            val first = map.readMapBytes()
            first[0] = '!'.code.toByte()
            assertEquals("first-map", map.readMapBytes().toString(Charsets.UTF_8))
            file.writeText("a-different-map-with-different-length")
            assertEquals(file.readText(), map.readMapBytes().toString(Charsets.UTF_8))
            map.clean()
            assertEquals(file.readText(), map.readMapBytes().toString(Charsets.UTF_8))
        } finally {
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun customStartGamePacketEmbedsPreparedMapBytes() {
        val previousRoot = FileUtils.getFolder().path
        val tempRoot = Files.createTempDirectory("rw-hps-custom-start-packet").toFile()
        try {
            FileUtils.setFilePath(fileUtilsRoot(tempRoot))
            Log.set("OFF")
            File(tempRoot, "data/maps").mkdirs()
            File(tempRoot, "data/saves").mkdirs()

            val mapBytes = "<map version=\"1.10\"><layer/></map>".toByteArray()
            val source = File(tempRoot, "source/air-map.tmx").apply {
                parentFile.mkdirs()
                writeBytes(mapBytes)
            }
            val customMapData = GameMaps.MapData(
                GameMaps.MapType.CustomMap,
                GameMaps.MapFileType.ExternalFile,
                "Air Map",
                source.absolutePath,
                "air-map"
            )
            val maps = GameMaps().apply {
                mapType = GameMaps.MapType.CustomMap
                mapName = "Air Map"
                mapData = customMapData
            }

            val packet = GameVersionPacket().getCustomStartGamePacket(maps)
            assertEquals(net.rwhps.server.io.packet.type.PacketType.START_GAME, packet.type)
            GameInputStream(packet).use { input ->
                assertEquals(0, input.readByte())
                assertEquals(GameMaps.MapType.CustomMap.ordinal, input.readInt())
                val embeddedLength = input.readInt()
                assertEquals(mapBytes.size, embeddedLength)
                assertArrayEquals(mapBytes, input.readNBytes(embeddedLength))
                assertEquals("SAVE:Air Map.tmx", input.readString())
                assertFalse(input.readBoolean())
            }
        } finally {
            FileUtils.setFilePath(fileUtilsRoot(previousRoot))
            tempRoot.deleteRecursively()
        }
    }

    private fun buildSampleRwmod(
        modTitle: String = "Sample Mod",
        mapContent: String = "sample"
    ): ByteArray {
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                fun add(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Data.UTF_8))
                    zip.closeEntry()
                }

                add(
                    "SampleMod/mod-info.txt",
                    """
                    [mod]
                    title:$modTitle
                    [maps]
                    sourceFolder:maps/
                    """.trimIndent()
                )
                add("SampleMod/maps/Space Test Map.tmx", "<map version=\"1.10\">$mapContent</map>")
                add("SampleMod/maps/Space Test Map_map.png", "fake-png")
                add("SampleMod/maps/tiles/misc.tsx", "<tileset></tileset>")
                add("SampleMod/units/Should Not Discover.tmx", "<map></map>")
            }
            bytes.toByteArray()
        }
    }

    private fun fileUtilsRoot(file: File): String {
        return fileUtilsRoot(file.absolutePath)
    }

    private fun fileUtilsRoot(path: String): String {
        val normalized = path.replace("\\", "/")
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }
}
