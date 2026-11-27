package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.BeanPluginLoggingConfig
import net.rwhps.server.plugin.PluginRuntimeMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class PluginLogServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plugin info remains visible when global log threshold is WARN`() {
        val console = mutableListOf<String>()
        val metrics = PluginRuntimeMetrics()
        val service = service(console = console)

        service.info("example.info", "visible info", metrics)
        service.close()

        assertEquals(1, console.size)
        assertTrue(console.single().contains("[INFO]"))
        assertTrue(console.single().contains("[example.info] visible info"))
        assertTrue(Files.readString(tempDir.resolve("example.info.log")).contains("visible info"))
        assertEquals(1, metrics.pluginLogEmissionCount())
    }

    @Test
    fun `global OFF suppresses ordinary plugin records but preserves throwable errors`() {
        val console = mutableListOf<String>()
        val service = service(console = console, coreLogLevel = "OFF")

        service.info("example.off", "hidden", null)
        service.warn("example.off", "also hidden", null)
        service.error("example.off", "visible failure", IllegalStateException("expected"), null)
        service.close()

        assertEquals(1, console.size)
        assertTrue(console.single().contains("visible failure"))
        assertFalse(console.single().contains("hidden"))
        assertTrue(Files.readString(tempDir.resolve("example.off.log")).contains("IllegalStateException: expected"))
    }

    @Test
    fun `info rate limiting reports the previous window suppression summary`() {
        val console = mutableListOf<String>()
        var now = 1_000_000L
        val config = BeanPluginLoggingConfig(infoLinesPerMinutePerPlugin = 10)
        val metrics = PluginRuntimeMetrics()
        val service = service(console = console, config = { config }, clock = { now })

        repeat(11) { service.info("example.rate", "burst-$it", metrics) }
        now += 60_000L
        service.info("example.rate", "next-window", metrics)
        service.close()

        assertEquals(1, metrics.pluginLogSuppressedCount())
        assertTrue(console.any { it.contains("INFO logs suppressed=1 in the previous 60s window") })
        assertTrue(console.any { it.contains("next-window") })
    }

    @Test
    fun `thousand INFO burst is bounded by the configured plugin window`() {
        val console = mutableListOf<String>()
        val metrics = PluginRuntimeMetrics()
        val service = service(
            console = console,
            config = {
                BeanPluginLoggingConfig(
                    consoleLevel = BeanPluginLoggingConfig.LEVEL_INFO,
                    fileLevel = BeanPluginLoggingConfig.LEVEL_OFF,
                    infoLinesPerMinutePerPlugin = 600
                )
            }
        )

        repeat(1_000) { service.info("example.burst", "record-$it", metrics) }
        service.close()

        assertEquals(600, console.size)
        assertEquals(400, metrics.pluginLogSuppressedCount())
        assertEquals(600, metrics.pluginLogEmissionCount())
    }

    @Test
    fun `messages are rendered as sanitized bounded single lines`() {
        val console = mutableListOf<String>()
        val service = service(
            console = console,
            config = { BeanPluginLoggingConfig(maxMessageLength = 256) }
        )

        service.info("example sanitize", "\u001B[31mhello\r\nworld\u0000 " + "x".repeat(300), null)
        service.close()

        val line = console.single()
        assertTrue(line.contains("[example_sanitize] hello world"))
        assertTrue(line.endsWith("[truncated]"))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertFalse(line.contains('\u001B'))
    }

    @Test
    fun `writer rotates active logs and removes expired retained files`() {
        val console = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val config = BeanPluginLoggingConfig(maxFileSizeMb = 1, retainDays = 1)
        val active = tempDir.resolve("example.rotate.log")
        val expired = tempDir.resolve("expired.log")
        Files.write(active, ByteArray(1_024 * 1_024))
        Files.writeString(expired, "expired")
        Files.setLastModifiedTime(expired, FileTime.fromMillis(now - 2 * 24 * 60 * 60 * 1000L))
        val service = service(console = console, config = { config }, clock = { now })

        service.info("example.rotate", "new active record", null)
        service.close()

        assertTrue(Files.exists(active))
        assertTrue(Files.readString(active).contains("new active record"))
        assertFalse(Files.exists(expired))
        assertEquals(now, service.diagnostics().lastFileWriteAtMillis)
        Files.list(tempDir).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().startsWith("example.rotate-") })
        }
    }

    @Test
    fun `file output failure is isolated and retained in diagnostics`() {
        val console = mutableListOf<String>()
        val blockedDirectory = Files.createFile(tempDir.resolve("not-a-directory"))
        val metrics = PluginRuntimeMetrics()
        val service = service(console = console, logDirectory = blockedDirectory)

        service.info("example.failure", "still returns", metrics)
        service.close()

        val diagnostics = service.diagnostics()
        assertTrue(console.any { it.contains("still returns") })
        assertTrue(console.any { it.contains("[PluginLog] file output failed") })
        assertEquals(1, diagnostics.fileFailures)
        assertTrue(diagnostics.lastFileFailure.contains("FileAlreadyExistsException"))
        assertEquals(1, metrics.pluginLogFileFailureCount())
    }

    private fun service(
        console: MutableList<String>,
        config: () -> BeanPluginLoggingConfig = { BeanPluginLoggingConfig() },
        coreLogLevel: String = "WARN",
        clock: () -> Long = System::currentTimeMillis,
        logDirectory: Path = tempDir
    ): PluginLogService {
        return PluginLogService(
            logDirectory = logDirectory,
            configProvider = config,
            coreLogLevelProvider = { coreLogLevel },
            consoleSink = { console.add(it) },
            clock = clock
        )
    }
}
