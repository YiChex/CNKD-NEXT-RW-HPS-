package net.rwhps.server.plugin.v2

import net.rwhps.server.plugin.PluginRuntimeMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginResourceScopeTest {
    @Test
    fun `scope closes resources in reverse order and exact once`() {
        val order = mutableListOf<String>()
        val scope = PluginResourceScope("test.scope")
        scope.manage(AutoCloseable { order.add("first") })
        val registration = scope.registration { order.add("second") }

        assertTrue(registration.isActive)
        scope.close()
        scope.close()

        assertEquals(listOf("second", "first"), order)
        assertFalse(registration.isActive)
    }

    @Test
    fun `closing one registration early remains idempotent during scope close`() {
        var calls = 0
        val scope = PluginResourceScope("test.early")
        val registration = scope.registration { calls++ }

        registration.close()
        scope.close()

        assertEquals(1, calls)
    }

    @Test
    fun `resource registered after scope close is closed immediately`() {
        var calls = 0
        val scope = PluginResourceScope("test.closed")
        scope.close()

        assertThrows(IllegalStateException::class.java) {
            scope.manage(AutoCloseable { calls++ })
        }
        assertEquals(1, calls)
        assertFalse(scope.isActive())
    }

    @Test
    fun `resource cleanup failures use the supplied plugin log sink`() {
        val messages = mutableListOf<String>()
        val metrics = PluginRuntimeMetrics()
        val sink = object: PluginLogSink {
            override fun info(pluginId: String, message: String, metrics: PluginRuntimeMetrics?) = Unit
            override fun warn(pluginId: String, message: String, metrics: PluginRuntimeMetrics?) = Unit
            override fun error(
                pluginId: String,
                message: String,
                error: Throwable?,
                metrics: PluginRuntimeMetrics?
            ) {
                messages.add("$pluginId:$message:${error?.message}")
            }
        }
        val scope = PluginResourceScope("test.failure", sink, metrics)
        scope.manage(AutoCloseable { error("close failed") })

        scope.close()

        assertEquals(listOf("test.failure:Resource cleanup #0 failed:close failed"), messages)
    }
}
