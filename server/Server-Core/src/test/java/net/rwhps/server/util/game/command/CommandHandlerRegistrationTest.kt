package net.rwhps.server.util.game.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CommandHandlerRegistrationTest {
    @Test
    fun `case insensitive replacement keeps map and ordered list consistent`() {
        val handler = CommandHandler(".")
        var result = ""
        handler.register("Ping", "first", CommandHandler.CommandRunner<Any?> { _, _ -> result = "first" })
        handler.register("pING", "second", CommandHandler.CommandRunner<Any?> { _, _ -> result = "second" })

        assertEquals(1, handler.commandList.size)
        assertEquals(CommandHandler.ResponseType.valid, handler.handleMessage(".PING").type)
        assertEquals("second", result)

        handler.removeCommand("PING")
        assertEquals(0, handler.commandList.size)
        assertEquals(CommandHandler.ResponseType.unknownCommand, handler.handleMessage(".ping").type)
    }

    @Test
    fun `blank command names are rejected consistently`() {
        val handler = CommandHandler(".")

        assertThrows(IllegalArgumentException::class.java) {
            handler.register(" ", "invalid", CommandHandler.CommandRunner<Any?> { _, _ -> })
        }
        assertThrows(IllegalArgumentException::class.java) { handler.removeCommand(" ") }
    }

    @Test
    fun `failed registration transaction restores replaced and newly added commands`() {
        val handler = CommandHandler(".")
        var result = ""
        handler.register("base", "original", CommandHandler.CommandRunner<Any?> { _, _ -> result = "original" })

        assertThrows(IllegalStateException::class.java) {
            handler.runRegistrationTransaction {
                handler.register("BASE", "replacement", CommandHandler.CommandRunner<Any?> { _, _ -> result = "replacement" })
                handler.register("partial", "partial", CommandHandler.CommandRunner<Any?> { _, _ -> result = "partial" })
                error("registration failed")
            }
        }

        assertEquals(1, handler.commandList.size)
        assertEquals(CommandHandler.ResponseType.unknownCommand, handler.handleMessage(".partial").type)
        assertEquals(CommandHandler.ResponseType.valid, handler.handleMessage(".base").type)
        assertEquals("original", result)
    }

    @Test
    fun `exact removal does not delete a later replacement`() {
        val handler = CommandHandler(".")
        val first = handler.register("owned", "first", CommandHandler.CommandRunner<Any?> { _, _ -> })
        handler.register("OWNED", "second", CommandHandler.CommandRunner<Any?> { _, _ -> })

        assertEquals(false, handler.removeCommand("owned", first))
        assertEquals(1, handler.commandList.size)
        assertEquals(CommandHandler.ResponseType.valid, handler.handleMessage(".owned").type)
    }

    @Test
    fun `single optional status parameter is accepted for diagnostic commands`() {
        val handler = CommandHandler(".")
        var result = ""
        handler.register("pluginlog", "[status]", "diagnostics", CommandHandler.CommandRunner<Any?> { args, _ ->
            result = args.firstOrNull().orEmpty()
        })

        assertEquals(CommandHandler.ResponseType.valid, handler.handleMessage(".pluginlog").type)
        assertEquals("", result)
        assertEquals(CommandHandler.ResponseType.valid, handler.handleMessage(".pluginlog status").type)
        assertEquals("status", result)
    }
}
