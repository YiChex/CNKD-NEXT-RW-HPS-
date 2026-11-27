package net.rwhps.server.plugin

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.data.global.Data
import net.rwhps.server.plugin.api.ApiVersions
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class PluginCompatibilityFoundationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `branded display version is separated from semver api version`() {
        assertEquals("CNKD NEXT", Data.SERVER_PRODUCT_NAME)
        assertEquals("1.3.0", Data.SERVER_API_VERSION)
        assertEquals(Data.SERVER_API_VERSION, ApiVersions.SERVER_API)
        assertTrue(Data.SERVER_CORE_VERSION.contains(Data.SERVER_PRODUCT_NAME))
        assertNotNull(GetVersion(Data.SERVER_API_VERSION))
        assertThrows(IllegalStateException::class.java) { GetVersion(Data.SERVER_CORE_VERSION) }
    }

    @Test
    fun `descriptor accepts a compatible legacy plugin`() {
        val descriptor = validDescriptor()

        assertNull(PluginDescriptorValidator.validateDescriptor(descriptor))
        assertTrue(PluginDescriptorValidator.supportsCurrentApi(descriptor))
        assertEquals("example.plugin", PluginDescriptorValidator.normalizedId(descriptor))
    }

    @Test
    fun `descriptor rejects unsafe ids and javascript paths`() {
        val unsafeId = validDescriptor().copy(internalName = "../outside")
        val unsafeMain = validDescriptor().copy(main = "../main.js")

        assertTrue(PluginDescriptorValidator.validateDescriptor(unsafeId)!!.contains("internalName"))
        assertTrue(PluginDescriptorValidator.validateDescriptor(unsafeMain)!!.contains("relative archive path"))
    }

    @Test
    fun `javascript module extensions and built in ids are classified explicitly`() {
        assertTrue(PluginDescriptorValidator.isJavaScriptMain("main.js"))
        assertTrue(PluginDescriptorValidator.isJavaScriptMain("main.mjs"))
        assertTrue(PluginDescriptorValidator.isReservedBuiltInId(validDescriptor().copy(internalName = "HessServer")))
    }

    @Test
    fun `v2 descriptor accepts stable capabilities and rejects unsupported ones`() {
        val validV2 = validDescriptor().copy(
            apiVersion = 2,
            internalName = "example.plugin",
            capabilities = listOf(V2PluginCapabilities.PLAYER_COMMANDS, V2PluginCapabilities.PLAYER_EVENTS)
        )
        val unsupported = validV2.copy(capabilities = listOf("raw-packets"))
        val scriptV2 = validV2.copy(main = "main.mjs")

        assertNull(PluginDescriptorValidator.validateDescriptor(validV2))
        assertTrue(PluginDescriptorValidator.validateDescriptor(unsupported)!!.contains("unsupported"))
        assertTrue(PluginDescriptorValidator.validateDescriptor(scriptV2)!!.contains("JVM plugins only"))
        assertNull(
            PluginDescriptorValidator.validateDescriptor(
                validV2.copy(capabilities = listOf(V2PluginCapabilities.READ_STATE))
            )
        )
    }

    @Test
    fun `v2 descriptor rejects uppercase identity capability and legacy import`() {
        val validV2 = validDescriptor().copy(
            apiVersion = 2,
            internalName = "example.plugin",
            capabilities = listOf(V2PluginCapabilities.PLAYER_EVENTS)
        )

        assertTrue(
            PluginDescriptorValidator.validateDescriptor(validV2.copy(internalName = "Example.Plugin"))!!
                .contains("lowercase")
        )
        assertTrue(
            PluginDescriptorValidator.validateDescriptor(validV2.copy(capabilities = listOf("Player-Events")))!!
                .contains("lowercase")
        )
        assertTrue(
            PluginDescriptorValidator.validateDescriptor(validV2.copy(import = "Legacy Plugin"))!!
                .contains("legacy import")
        )
    }

    @Test
    fun `legacy descriptors remain api version one by default`() {
        assertEquals(1, validDescriptor().apiVersion)
        assertTrue(validDescriptor().capabilities.isEmpty())
    }

    @Test
    fun `archive validation rejects traversal entries and corrupt files`() {
        val unsafeArchive = tempDir.resolve("unsafe.zip")
        ZipOutputStream(unsafeArchive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val corruptArchive = tempDir.resolve("corrupt.jar")
        corruptArchive.toFile().writeText("not a zip")

        assertTrue(PluginDescriptorValidator.validateArchive(unsafeArchive.toFile())!!.contains("unsafe entry path"))
        assertTrue(PluginDescriptorValidator.validateArchive(corruptArchive.toFile())!!.contains("invalid ZIP/JAR"))
    }

    @Test
    fun `archive validation accepts a bounded plugin archive`() {
        val archive = tempDir.resolve("valid.jar")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        assertNull(PluginDescriptorValidator.validateArchive(archive.toFile()))
    }

    @Test
    fun `archive validation rejects duplicate entry names`() {
        val archive = tempDir.resolve("duplicate-entry.jar")
        ZipArchiveOutputStream(archive.toFile()).use { zip ->
            repeat(2) {
                zip.putArchiveEntry(ZipArchiveEntry("plugin.json"))
                zip.write("{}".toByteArray())
                zip.closeArchiveEntry()
            }
        }

        assertTrue(PluginDescriptorValidator.validateArchive(archive.toFile())!!.contains("duplicate entry"))
    }

    @Test
    fun `archive validation rejects nested plugin descriptors`() {
        val archive = tempDir.resolve("nested-descriptor.jar")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("dependency/plugin.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        assertTrue(
            PluginDescriptorValidator.validateArchive(archive.toFile())!!
                .contains("nested plugin descriptor")
        )
    }

    @Test
    fun `v2 archive cannot bundle server or plugin api classes`() {
        val archive = tempDir.resolve("bundled-api.jar")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("net/rwhps/server/plugin/api/CnkdPlugin.class"))
            zip.write(byteArrayOf(0, 1, 2, 3))
            zip.closeEntry()
        }

        assertTrue(
            PluginDescriptorValidator.validateV2ArchiveContents(archive.toFile())!!
                .contains("cannot bundle CNKD server or plugin API classes")
        )
    }

    @Test
    fun `v2 archive requires its main class and java eleven bytecode`() {
        val missingMain = tempDir.resolve("missing-main.jar")
        ZipOutputStream(missingMain.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("example/Other.class"))
            zip.write(classHeader(55))
            zip.closeEntry()
        }
        val newBytecode = tempDir.resolve("new-bytecode.jar")
        ZipOutputStream(newBytecode.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("example/Main.class"))
            zip.write(classHeader(Runtime.version().feature() + 45))
            zip.closeEntry()
        }

        assertTrue(
            PluginDescriptorValidator.validateV2ArchiveContents(missingMain.toFile(), "example.Main")!!
                .contains("main class is missing")
        )
        assertTrue(
            PluginDescriptorValidator.validateV2ArchiveContents(newBytecode.toFile(), "example.Main")!!
                .contains("archive class must target Java 11 or lower")
        )
    }

    @Test
    fun `v2 archive rejects newer bytecode in helper classes too`() {
        val archive = tempDir.resolve("new-helper-bytecode.jar")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("example/Main.class"))
            zip.write(classHeader(55))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("example/Helper.class"))
            zip.write(classHeader(56))
            zip.closeEntry()
        }

        assertTrue(
            PluginDescriptorValidator.validateV2ArchiveContents(archive.toFile(), "example.Main")!!
                .contains("example/Helper.class")
        )
    }

    private fun classHeader(majorVersion: Int): ByteArray {
        return byteArrayOf(
            0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(),
            0, 0, (majorVersion ushr 8).toByte(), majorVersion.toByte()
        )
    }

    private fun validDescriptor(): BeanPluginInfo {
        return BeanPluginInfo(
            name = "Example Plugin",
            internalName = "Example.Plugin",
            author = "CNKD",
            main = "example.plugin.Main",
            description = "Compatibility fixture",
            version = "1.0.0",
            supportedVersions = ">= 1.0.0"
        )
    }
}
