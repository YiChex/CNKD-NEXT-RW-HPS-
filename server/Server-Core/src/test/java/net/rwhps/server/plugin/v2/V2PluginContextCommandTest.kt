package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.func.StrCons
import net.rwhps.server.plugin.V2PluginCapabilities
import net.rwhps.server.util.game.command.CommandHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class V2PluginContextCommandTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `scoped console command is removed when plugin scope closes`() {
        val scope = PluginResourceScope("example.command")
        val context = context(scope, setOf(V2PluginCapabilities.CONSOLE_COMMANDS))
        val handler = CommandHandler(".")
        var reply = ""
        context.commands().registerConsole("v2hello", "", "test") { invocation -> invocation.reply("hello") }
        context.attachConsoleCommands(handler)

        assertEquals(
            CommandHandler.ResponseType.valid,
            handler.handleMessage(".v2hello", StrCons { reply = it }).type
        )
        assertEquals("hello", reply)
        assertEquals(1, context.runtimeMetrics.commandInvocationCount())
        assertEquals(0, context.runtimeMetrics.commandFailureCount())

        scope.close()
        assertEquals(CommandHandler.ResponseType.unknownCommand, handler.handleMessage(".v2hello").type)
    }

    @Test
    fun `undeclared command capability is rejected`() {
        val scope = PluginResourceScope("example.denied")
        val context = context(scope, emptySet())

        assertThrows(IllegalStateException::class.java) {
            context.commands().registerConsole("denied", "", "test") { }
        }
        scope.close()
    }

    @Test
    fun `plugin command cannot override existing core command`() {
        val scope = PluginResourceScope("example.conflict")
        val context = context(scope, setOf(V2PluginCapabilities.CONSOLE_COMMANDS))
        val handler = CommandHandler(".")
        handler.register("status", "core") { _: Array<String> -> }
        context.commands().registerConsole("status", "", "plugin") { }

        assertThrows(IllegalStateException::class.java) { context.attachConsoleCommands(handler) }
        assertEquals(1, handler.commandList.size)
        scope.close()
    }

    @Test
    fun `failed immediate command registration leaves no retained definition`() {
        val scope = PluginResourceScope("example.transaction")
        val context = context(scope, setOf(V2PluginCapabilities.CONSOLE_COMMANDS))
        val handler = CommandHandler(".")
        handler.register("status", "core") { _: Array<String> -> }
        context.attachConsoleCommands(handler)

        assertThrows(IllegalStateException::class.java) {
            context.commands().registerConsole("status", "", "plugin") { }
        }
        handler.removeCommand("status")
        context.commands().registerConsole("status", "", "plugin") { invocation -> invocation.reply("plugin") }

        var reply = ""
        assertEquals(
            CommandHandler.ResponseType.valid,
            handler.handleMessage(".status", StrCons { reply = it }).type
        )
        assertEquals("plugin", reply)
        scope.close()
    }

    @Test
    fun `declared capabilities are immutable and inactive context rejects new work`() {
        val scope = PluginResourceScope("example.capabilities")
        val context = context(scope, setOf(V2PluginCapabilities.CONSOLE_COMMANDS))
        var closeCalls = 0

        assertThrows(UnsupportedOperationException::class.java) {
            (context.declaredCapabilities() as MutableSet<String>).add(V2PluginCapabilities.BROADCAST)
        }
        context.deactivate()
        assertTrue(!context.isActive())
        assertThrows(IllegalStateException::class.java) {
            context.commands().registerConsole("late", "", "late") { }
        }
        assertThrows(IllegalStateException::class.java) {
            context.manage(AutoCloseable { closeCalls++ })
        }
        assertEquals(1, closeCalls)
        scope.close()
    }

    @Test
    fun `failed command is recorded without changing command handling`() {
        val scope = PluginResourceScope("example.command.failure")
        val context = context(scope, setOf(V2PluginCapabilities.CONSOLE_COMMANDS))
        val handler = CommandHandler(".")
        var reply = ""
        context.commands().registerConsole("v2fails", "", "test") { error("expected") }
        context.attachConsoleCommands(handler)

        assertEquals(
            CommandHandler.ResponseType.valid,
            handler.handleMessage(".v2fails", StrCons { reply = it }).type
        )
        assertTrue(reply.contains("Plugin command failed"))
        assertEquals(1, context.runtimeMetrics.commandInvocationCount())
        assertEquals(1, context.runtimeMetrics.commandFailureCount())
        scope.close()
    }

    @Test
    fun `plugin logger delegates to the supplied V2 log sink`() {
        val scope = PluginResourceScope("example.logger")
        val received = mutableListOf<String>()
        val sink = object: PluginLogSink {
            override fun info(pluginId: String, message: String, metrics: net.rwhps.server.plugin.PluginRuntimeMetrics?) {
                received.add("INFO:$pluginId:$message")
            }

            override fun warn(pluginId: String, message: String, metrics: net.rwhps.server.plugin.PluginRuntimeMetrics?) {
                received.add("WARN:$pluginId:$message")
            }

            override fun error(
                pluginId: String,
                message: String,
                error: Throwable?,
                metrics: net.rwhps.server.plugin.PluginRuntimeMetrics?
            ) {
                received.add("ERROR:$pluginId:$message:${error?.message}")
            }
        }
        val context = V2PluginContext(
            descriptor(),
            tempDir,
            scope,
            pluginLogSink = sink
        )

        context.logger().info("ready")
        context.logger().warn("slow")
        context.logger().error("failed", IllegalStateException("expected"))

        assertEquals(
            listOf(
                "INFO:example.test:ready",
                "WARN:example.test:slow",
                "ERROR:example.test:failed:expected"
            ),
            received
        )
        scope.close()
    }

    private fun context(scope: PluginResourceScope, capabilities: Set<String>): V2PluginContext {
        return V2PluginContext(
            descriptor(capabilities),
            tempDir,
            scope
        )
    }

    private fun descriptor(capabilities: Set<String> = emptySet()) = BeanPluginInfo(
        name = "Test V2",
        internalName = "example.test",
        author = "test",
        main = "example.TestPlugin",
        description = "fixture",
        version = "1.0.0",
        supportedVersions = ">= 1.1.0",
        apiVersion = 2,
        capabilities = capabilities.toList()
    )
}
