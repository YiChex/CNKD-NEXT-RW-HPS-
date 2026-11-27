package net.rwhps.server.plugin.v2

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class V2PluginExecutionTest {
    @Test
    fun `context class loader is installed and restored after callback`() {
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        val pluginLoader = object: ClassLoader(original) {}

        V2PluginExecution.withContextClassLoader(pluginLoader) {
            assertSame(pluginLoader, thread.contextClassLoader)
        }

        assertSame(original, thread.contextClassLoader)
    }

    @Test
    fun `context class loader is restored after callback failure`() {
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        val pluginLoader = object: ClassLoader(original) {}

        assertThrows(IllegalStateException::class.java) {
            V2PluginExecution.withContextClassLoader(pluginLoader) {
                error("failed")
            }
        }

        assertSame(original, thread.contextClassLoader)
    }
}
