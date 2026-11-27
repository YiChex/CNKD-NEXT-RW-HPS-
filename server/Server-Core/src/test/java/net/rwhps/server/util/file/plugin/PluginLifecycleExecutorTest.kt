package net.rwhps.server.util.file.plugin

import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.PluginLoadData
import net.rwhps.server.plugin.PluginRuntimeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginLifecycleExecutorTest {
    @Test
    fun `external failure is isolated and failed plugin skips later phases`() {
        var healthyCalls = 0
        var failedLaterCalls = 0
        val failed = plugin("failed", object: Plugin() {})
        val healthy = plugin("healthy", object: Plugin() {})

        PluginLifecycleExecutor.runPhase(listOf(failed, healthy), "onEnable", PluginRuntimeState.ENABLED) { plugin ->
            if (plugin === failed.main) {
                error("expected failure")
            }
            healthyCalls++
        }
        PluginLifecycleExecutor.runPhase(listOf(failed, healthy), "init", PluginRuntimeState.ACTIVE) { plugin ->
            if (plugin === failed.main) {
                failedLaterCalls++
            }
        }

        assertEquals(PluginRuntimeState.FAILED, failed.state)
        assertEquals("onEnable", failed.failurePhase)
        assertEquals(1, healthyCalls)
        assertEquals(0, failedLaterCalls)
        assertEquals(PluginRuntimeState.ACTIVE, healthy.state)
    }

    @Test
    fun `built in failure is reported after remaining plugins are inspected`() {
        var remainingCalls = 0
        val builtIn = plugin("core", object: Plugin() {}, builtIn = true)
        val remaining = plugin("remaining", object: Plugin() {})

        val error = assertThrows(IllegalStateException::class.java) {
            PluginLifecycleExecutor.runPhase(listOf(builtIn, remaining), "init", PluginRuntimeState.ACTIVE) { plugin ->
                if (plugin === builtIn.main) {
                    error("core failed")
                }
                remainingCalls++
            }
        }

        assertTrue(error.message!!.contains("core"))
        assertEquals(1, remainingCalls)
        assertEquals(PluginRuntimeState.FAILED, builtIn.state)
        assertEquals(PluginRuntimeState.ACTIVE, remaining.state)
    }

    @Test
    fun `shutdown is reverse order exact once and continues after failure`() {
        val order = mutableListOf<String>()
        val first = plugin("first", disablingPlugin("first", order))
        val second = plugin("second", disablingPlugin("second", order, fail = true))
        val third = plugin("third", disablingPlugin("third", order))
        val plugins = listOf(first, second, third)

        PluginLifecycleExecutor.shutdown(plugins)
        PluginLifecycleExecutor.shutdown(plugins)

        assertEquals(listOf("third", "second", "first"), order)
        plugins.forEach { assertEquals(PluginRuntimeState.DISABLED, it.state) }
        assertEquals("onDisable", second.failurePhase)
    }

    private fun disablingPlugin(name: String, order: MutableList<String>, fail: Boolean = false): Plugin {
        return object: Plugin() {
            override fun onDisable() {
                order.add(name)
                if (fail) {
                    error("disable failed")
                }
            }
        }
    }

    private fun plugin(internalName: String, main: Plugin, builtIn: Boolean = false): PluginLoadData {
        return PluginLoadData(
            name = internalName,
            internalName = internalName,
            author = "test",
            description = "test fixture",
            version = "1.0.0",
            main = main,
            mkdir = false,
            skip = builtIn
        )
    }
}
