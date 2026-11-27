package net.rwhps.server.plugin

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.util.file.FileUtils
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

class PluginSupplyChainLockTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `missing lock rejects every discovered plugin`() {
        val fingerprint = fixtureFingerprint()

        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(fingerprint))

        assertFalse(result.decisionFor(fingerprint).approved)
        assertTrue(result.decisionFor(fingerprint).reason.contains("lock is missing"))
        assertTrue(result.decisionFor(fingerprint).reason.contains("cnkd-plugin-tool.jar"))
    }

    @Test
    fun `development authorization bypasses only the approval lock`() {
        val fingerprint = fixtureFingerprint()

        val accepted = PluginSupplyChainLock.authorizeDevelopment(tempDir.toFile(), listOf(fingerprint))
        assertTrue(accepted.decisionFor(fingerprint).approved)
        assertTrue(accepted.decisionFor(fingerprint).reason.contains("development mode"))

        val rejected = PluginSupplyChainLock.authorizeDevelopment(
            tempDir.toFile(),
            listOf(fingerprint),
            listOf("corrupt.jar")
        )
        assertFalse(rejected.decisionFor(fingerprint).approved)
        assertTrue(rejected.decisionFor(fingerprint).reason.contains("invalid or unreadable archives"))
    }

    @Test
    fun `empty lock rejects an unknown plugin`() {
        writeLock("")
        val fingerprint = fixtureFingerprint()

        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(fingerprint))

        assertFalse(result.decisionFor(fingerprint).approved)
        assertTrue(result.decisionFor(fingerprint).reason.contains("unapproved archives are present"))
    }

    @Test
    fun `one unknown archive rejects the complete plugin set`() {
        val approved = fixtureFingerprint()
        val unknown = approved.copy(internalName = "unknown.plugin", fileName = "unknown.jar")
        writeLock(entryJson(approved))

        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(approved, unknown))

        assertFalse(result.decisionFor(approved).approved)
        assertFalse(result.decisionFor(unknown).approved)
        assertTrue(result.decisionFor(approved).reason.contains("unapproved archives are present"))
    }

    @Test
    fun `exact identity and both hashes are required`() {
        val fingerprint = fixtureFingerprint()
        writeLock(entryJson(fingerprint))

        val accepted = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(fingerprint))
        val changed = fingerprint.copy(sha256 = "b".repeat(64))
        val rejected = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(changed))

        assertTrue(accepted.decisionFor(fingerprint).approved)
        assertFalse(rejected.decisionFor(changed).approved)
        assertTrue(rejected.decisionFor(changed).reason.contains("sha256"))
    }

    @Test
    fun `api version plugin version file name and descriptor hash must all match`() {
        val fingerprint = fixtureFingerprint()
        writeLock(entryJson(fingerprint))
        val changedValues = listOf(
            fingerprint.copy(apiVersion = 1) to "apiVersion",
            fingerprint.copy(version = "1.0.1") to "version",
            fingerprint.copy(fileName = "renamed.jar") to "fileName",
            fingerprint.copy(descriptorSha256 = "d".repeat(64)) to "descriptorSha256"
        )

        changedValues.forEach { (changed, expectedField) ->
            val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(changed))
            assertFalse(result.decisionFor(changed).approved)
            assertTrue(result.decisionFor(changed).reason.contains(expectedField))
        }
    }

    @Test
    fun `duplicate lock ids invalidate the entire lock`() {
        val fingerprint = fixtureFingerprint()
        writeLock("${entryJson(fingerprint)},${entryJson(fingerprint.copy(fileName = "copy.jar"))}")

        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(fingerprint))

        assertFalse(result.decisionFor(fingerprint).approved)
        assertTrue(result.errors.any { it.contains("duplicate plugin internalName") })
    }

    @Test
    fun `duplicate archive ids reject the complete external plugin set before lock filtering`() {
        val duplicate = fixtureFingerprint()
        val duplicateCopy = duplicate.copy(fileName = "example-copy.jar")
        val independent = duplicate.copy(
            internalName = "independent.plugin",
            fileName = "independent.jar"
        )
        writeLock("${entryJson(duplicate)},${entryJson(independent)}")

        val result = PluginSupplyChainLock.authorize(
            tempDir.toFile(),
            listOf(duplicate, duplicateCopy, independent)
        )

        assertFalse(result.decisionFor(duplicate).approved)
        assertFalse(result.decisionFor(duplicateCopy).approved)
        assertFalse(result.decisionFor(independent).approved)
        assertTrue(result.errors.any { it.contains("duplicate plugin internalName values") })
    }

    @Test
    fun `invalid discovered archive rejects the complete external plugin set`() {
        val approved = fixtureFingerprint()
        writeLock(entryJson(approved))

        val result = PluginSupplyChainLock.authorize(
            tempDir.toFile(),
            listOf(approved),
            listOf("corrupt.jar")
        )

        assertFalse(result.decisionFor(approved).approved)
        assertTrue(result.errors.any { it.contains("invalid or unreadable archives") })
    }

    @Test
    fun `an approved but absent archive invalidates the complete manifest`() {
        val present = fixtureFingerprint()
        val absent = present.copy(internalName = "absent.plugin", fileName = "absent.jar")
        writeLock("${entryJson(present)},${entryJson(absent)}")

        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(present))

        assertFalse(result.decisionFor(present).approved)
        assertTrue(result.decisionFor(present).reason.contains("approved archives are absent"))
    }

    @Test
    fun `archive modification is detected before plugin execution`() {
        val archive = tempDir.resolve("example.jar")
        val descriptor = """
            {
              "apiVersion": 2,
              "internalName": "example.plugin",
              "version": "1.0.0"
            }
        """.trimIndent().toByteArray()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(descriptor)
            zip.closeEntry()
        }
        val fingerprint = PluginSupplyChainLock.fingerprint(
            archive.toFile(),
            BeanPluginInfo(
                internalName = "example.plugin",
                apiVersion = 2,
                version = "1.0.0"
            ),
            descriptor
        )

        assertNull(PluginSupplyChainLock.verifyUnchanged(archive.toFile(), fingerprint))
        archive.toFile().appendBytes(byteArrayOf(1))
        assertTrue(PluginSupplyChainLock.verifyUnchanged(archive.toFile(), fingerprint)!!.contains("SHA-256 changed"))
    }

    @Test
    fun `unsupported lock fields fail closed`() {
        tempDir.resolve(PluginSupplyChainLock.LOCK_FILE_NAME).writeText(
            """{"lockVersion":1,"plugins":[],"autoApprove":true}"""
        )

        val fingerprint = fixtureFingerprint()
        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(fingerprint))

        assertFalse(result.decisionFor(fingerprint).approved)
        assertTrue(result.errors.any { it.contains("unsupported root field") })
    }

    @Test
    fun `malformed lock fails closed`() {
        tempDir.resolve(PluginSupplyChainLock.LOCK_FILE_NAME).writeText("{not-json")

        val fingerprint = fixtureFingerprint()
        val result = PluginSupplyChainLock.authorize(tempDir.toFile(), listOf(fingerprint))

        assertFalse(result.decisionFor(fingerprint).approved)
        assertTrue(result.errors.any { it.contains("malformed JSON") })
    }

    @Test
    fun `only an approved javascript archive enables optional runtime detection`() {
        val archive = tempDir.resolve("script-plugin.zip")
        val descriptor = """
            {
              "apiVersion": 1,
              "name": "Script Plugin",
              "internalName": "script.plugin",
              "author": "CNKD",
              "main": "main.js",
              "description": "Runtime detection fixture",
              "version": "1.0.0",
              "supportedVersions": ">= 1.0.0"
            }
        """.trimIndent().toByteArray()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(descriptor)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("main.js"))
            zip.write("export default {};".toByteArray())
            zip.closeEntry()
        }
        val folder = FileUtils(tempDir.toFile())
        assertFalse(PluginsLoad.hasApprovedJavaScriptPlugin(folder))

        val fingerprint = PluginSupplyChainLock.fingerprint(
            archive.toFile(),
            BeanPluginInfo(
                internalName = "script.plugin",
                apiVersion = 1,
                version = "1.0.0"
            ),
            descriptor
        )
        writeLock(entryJson(fingerprint))

        assertTrue(PluginsLoad.hasApprovedJavaScriptPlugin(folder))

        tempDir.resolve("corrupt.jar").writeText("not a zip")
        assertFalse(PluginsLoad.hasApprovedJavaScriptPlugin(folder))
    }

    private fun fixtureFingerprint(): PluginArchiveFingerprint {
        return PluginArchiveFingerprint(
            internalName = "example.plugin",
            apiVersion = 2,
            version = "1.0.0",
            fileName = "example.jar",
            sha256 = "a".repeat(64),
            descriptorSha256 = "c".repeat(64)
        )
    }

    private fun writeLock(entries: String) {
        tempDir.resolve(PluginSupplyChainLock.LOCK_FILE_NAME).writeText(
            """{"lockVersion":1,"plugins":[$entries]}"""
        )
    }

    private fun entryJson(fingerprint: PluginArchiveFingerprint): String {
        return """
            {
              "internalName":"${fingerprint.internalName}",
              "apiVersion":${fingerprint.apiVersion},
              "version":"${fingerprint.version}",
              "fileName":"${fingerprint.fileName}",
              "sha256":"${fingerprint.sha256}",
              "descriptorSha256":"${fingerprint.descriptorSha256}",
              "approvedAt":"2026-08-04T00:00:00Z",
              "approvedBy":"test-operator"
            }
        """.trimIndent()
    }
}
