/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class StartModeReadinessSupportTest {
    @Test
    fun mapEngineReadyOrFalseSuppressesOptionalEngineLinkageFailure() {
        val ready = StartModeReadinessSupport.mapEngineReadyOrFalse {
            throw NoClassDefFoundError("com/corrodinggames/librocket/scripts/Root")
        }

        assertEquals(false, ready)
    }

    @Test
    fun topLevelUserModEntriesIgnoresEmptyFoldersAndCoreNames(@TempDir modsDir: Path) {
        Files.createDirectories(modsDir.resolve("RW-HPS CoreUnits"))

        val entries = StartModeReadinessSupport.topLevelUserModEntries(modsDir.toFile())

        assertEquals(emptyList<String>(), entries.map { it.name })
    }

    @Test
    fun topLevelUserModEntriesFindsArchivesAndUnpackedMods(@TempDir modsDir: Path) {
        Files.write(modsDir.resolve("example.rwmod"), byteArrayOf(1))
        Files.write(modsDir.resolve("example.zip"), byteArrayOf(1))
        Files.write(modsDir.resolve("notes.txt"), byteArrayOf(1))
        Files.createDirectories(modsDir.resolve("unpacked").resolve("units"))

        val entries = StartModeReadinessSupport.topLevelUserModEntries(modsDir.toFile())

        assertEquals(listOf("example.rwmod", "example.zip", "unpacked"), entries.map { it.name })
    }
}
