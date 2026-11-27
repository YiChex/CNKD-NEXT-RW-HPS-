package net.rwhps.server.plugin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PluginRuntimeSettingsTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearProperties() {
        System.clearProperty(PluginRuntimeSettings.MODE_PROPERTY)
        System.clearProperty(PluginRuntimeSettings.DIRECTORY_PROPERTY)
    }

    @Test
    fun `plugin loading defaults to production mode`() {
        assertEquals(PluginRuntimeSettings.Mode.PRODUCTION, PluginRuntimeSettings.mode())
        assertTrue(PluginRuntimeSettings.summaryLines().contains("pluginRuntime.lockRequired=true"))
    }

    @Test
    fun `development mode can use an isolated absolute plugin directory`() {
        System.setProperty(PluginRuntimeSettings.MODE_PROPERTY, "dev")
        System.setProperty(PluginRuntimeSettings.DIRECTORY_PROPERTY, tempDir.toString())

        assertEquals(PluginRuntimeSettings.Mode.DEVELOPMENT, PluginRuntimeSettings.mode())
        assertEquals(tempDir.toFile().absoluteFile.toPath().normalize().toFile(), PluginRuntimeSettings.pluginDirectory())
        assertTrue(PluginRuntimeSettings.summaryLines().contains("pluginRuntime.lockRequired=false"))
    }
}
