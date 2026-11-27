package net.rwhps.server.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PluginDiagnosticsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `diagnostics are bounded filterable and written for developers`() {
        PluginDiagnostics.reset(tempDir.toFile())
        PluginDiagnostics.record("broken.jar", "descriptor", "bad descriptor")
        PluginDiagnostics.record("other.jar", "archive", "bad archive")

        val filtered = PluginDiagnostics.buildReport("broken")
        assertTrue(filtered.contains("pluginDiagnostics.records=1"))
        assertTrue(filtered.any { it.contains("pluginDiagnostics[0].suggestion=") })

        PluginDiagnostics.writeReport()
        val report = tempDir.resolve("plugin-diagnostics.txt")
        assertTrue(Files.isRegularFile(report))
        assertTrue(Files.readString(report).contains("broken.jar"))
        assertEquals(2, PluginDiagnostics.buildReport().first { it.startsWith("pluginDiagnostics.records=") }.substringAfter('=').toInt())
    }
}
