/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.log.ColorCodes
import net.rwhps.server.util.log.Log

internal object StartModeTeamChatSupport {
    private val prefixes = arrayOf(".t", ".tc", ".teamchat", "-t", "-tc", "-teamchat", "/t", "/tc", "/teamchat")

    fun tryHandleTeamChat(message: String, player: PlayerHess, room: ServerRoom): Boolean {
        val rawBody = extractTeamChatBody(message) ?: return false
        val body = rawBody.trim()
        if (body.isBlank()) {
            sendQuietSystemMessage(player, "用法: .t <队内消息> 或 -t <队内消息>")
            return true
        }
        if (body.length > Data.configServer.maxMessageLen && !MapPingMessageWhitelist.isWhitelisted(body)) {
            sendQuietSystemMessage(player, Data.i18NBundle.getinput("message.maxLen"))
            return true
        }
        if (room.muteAll) {
            sendQuietSystemMessage(player, "当前房间已全员禁言，无法发送队内消息")
            return true
        }

        val filtered = Data.core.admin.filterMessage(player, body) ?: return true
        val recipients = sameTeamRecipients(player, room)
        if (recipients.isEmpty()) {
            sendQuietSystemMessage(player, "当前没有可接收队内消息的队友")
            return true
        }

        val taggedMessage = "[队聊] $filtered"
        // 排位剥离：原 chatSenderColorOverride 在非托管对局下返回 fallbackTeam == player.team。
        val displayColor = player.team
        recipients.forEach { recipient ->
            runCatching { recipient.con?.sendChatMessage(taggedMessage, player.name, displayColor) }
                .onFailure { Log.warn("[StartModeTeamChat] Failed to send team chat to ${recipient.name}", it) }
        }
        StartModeConsoleOutputSupport.print(
            StartModeConsoleOutputSupport.Category.PLAYER_CHAT,
            "[team &by {0} &fr]: &y{1}",
            player.name,
            ColorCodes.formatColors(filtered, true)
        )
        return true
    }

    private fun extractTeamChatBody(message: String): String? {
        val trimmed = message.trimStart()
        for (prefix in prefixes) {
            if (trimmed.equals(prefix, ignoreCase = true)) {
                return ""
            }
            if (trimmed.length > prefix.length && trimmed.startsWith(prefix, ignoreCase = true) && trimmed[prefix.length].isWhitespace()) {
                return trimmed.substring(prefix.length).trimStart()
            }
        }
        return null
    }

    private fun sameTeamRecipients(player: PlayerHess, room: ServerRoom): List<PlayerHess> {
        val recipients = ArrayList<PlayerHess>()
        room.playerManage.playerGroup.eachAll { candidate ->
            if (!candidate.isAi && candidate.con != null && candidate.team == player.team) {
                recipients += candidate
            }
        }
        return recipients
    }

    private fun sendQuietSystemMessage(player: PlayerHess, message: String) {
        runCatching { player.sendSystemMessage(message) }
            .onFailure { Log.warn("[StartModeTeamChat] Failed to send team chat notice to ${player.name}", it) }
    }
}
