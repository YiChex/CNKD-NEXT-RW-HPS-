/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.game.command

object CommandResponseFormatter {
    fun format(response: CommandHandler.CommandResponse?): String? {
        if (response == null) {
            return null
        }
        return when (response.type) {
            CommandHandler.ResponseType.manyArguments -> "Too many arguments. Usage: ${usage(response)}"
            CommandHandler.ResponseType.fewArguments -> "Too few arguments. Usage: ${usage(response)}"
            CommandHandler.ResponseType.unknownCommand -> "Unknown command: ${response.runCommand ?: "<empty>"}. Use help to view available commands."
            CommandHandler.ResponseType.noCommand,
            CommandHandler.ResponseType.valid -> null
        }
    }

    fun appendFormattedError(
        response: CommandHandler.CommandResponse?,
        append: (String) -> Unit
    ): Boolean {
        val message = format(response) ?: return false
        append(message)
        return true
    }

    private fun usage(response: CommandHandler.CommandResponse): String {
        val commandText = response.command?.text ?: response.runCommand ?: "<unknown>"
        val params = response.command?.paramText.orEmpty()
        return if (params.isBlank()) {
            commandText
        } else {
            "$commandText $params"
        }
    }
}
