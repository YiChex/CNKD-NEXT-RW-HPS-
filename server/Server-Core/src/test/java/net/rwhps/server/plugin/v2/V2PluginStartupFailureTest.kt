package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.plugin.api.CnkdPlugin
import net.rwhps.server.plugin.api.PluginContext
import net.rwhps.server.util.file.FileUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path

class V2PluginStartupFailureTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `startup failure disables once and closes managed resources`() {
        var disableCalls = 0
        var closeCalls = 0
        val plugin = object: CnkdPlugin {
            override fun onEnable(context: PluginContext) {
                context.manage(AutoCloseable { closeCalls++ })
                error("startup failed")
            }

            override fun onReady() = Unit

            override fun onDisable() {
                disableCalls++
            }
        }
        val loader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val runtime = V2PluginRuntime(descriptor(), plugin, loader, FileUtils(tempDir.toFile()))
        val adapter = V2PluginAdapter(runtime)

        assertThrows(IllegalStateException::class.java) { adapter.onEnable() }
        adapter.onDisable()

        assertEquals(1, disableCalls)
        assertEquals(1, closeCalls)
        assertFalse(runtime.context.isActive())
        loader.close()
    }

    private fun descriptor() = BeanPluginInfo(
        name = "Startup Failure",
        internalName = "example.startup-failure",
        author = "test",
        main = "example.StartupFailurePlugin",
        description = "fixture",
        version = "1.0.0",
        supportedVersions = ">= 1.1.0",
        apiVersion = 2
    )
}
