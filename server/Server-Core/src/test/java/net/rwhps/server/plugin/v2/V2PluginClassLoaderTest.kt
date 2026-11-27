package net.rwhps.server.plugin.v2

import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.api.CnkdPlugin
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class V2PluginClassLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `api is parent first while server internals are hidden`() {
        V2PluginClassLoader(tempDir.toUri().toURL(), javaClass.classLoader).use { loader ->
            assertSame(CnkdPlugin::class.java, loader.loadClass(CnkdPlugin::class.java.name))
            assertThrows(ClassNotFoundException::class.java) {
                loader.loadClass(Plugin::class.java.name)
            }
        }
    }
}
