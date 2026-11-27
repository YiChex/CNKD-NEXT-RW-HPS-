/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http.post.run

import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSpectatorSupport
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.player.unofficial.Cherry
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.plugin.beta.http.WebStatus.Companion.toWebStatusJson
import net.rwhps.server.plugin.beta.http.post.AbstractPost
import net.rwhps.server.util.game.command.CommandResponseFormatter

/**
 * 通过 API 运行服务器的 [Data.SERVER_COMMAND]
 *
 * @date 2024/2/16 17:19
 * @author Dr (dr@der.kim)
 */
object ClientCommand : AbstractPost() {
    override val prefixURL: String = "ClientCommand"

    override fun post(accept: AcceptWeb, send: SendWeb) {
        val result = StringBuilder()
        checkStringPost(accept, send, "runCommand")?.let { command ->
            if (!HeadlessModuleManage.initHPS()) {
                send.status = io.netty.handler.codec.http.HttpResponseStatus.CONFLICT
                send.setData("Headless module is not ready.".toWebStatusJson("CONFLICT"))
                return
            }
            val normalized = StartModeSpectatorSupport.normalizeLobbyCommand(command.trim().let {
                if (it.firstOrNull() in listOf('/', '.', '-', '_')) it else "/$it"
            }).orEmpty()
            val operation = normalized.lowercase(java.util.Locale.ROOT)
            if (operation == "/start" || operation == "/startcancel") {
                val accepted = if (operation == "/start") StartModeAutoStartSupport.requestStartOffNetworkThread(null, "api_manual")
                    else StartModeAutoStartSupport.cancelManualStart()
                send.status = if (accepted) io.netty.handler.codec.http.HttpResponseStatus.ACCEPTED else io.netty.handler.codec.http.HttpResponseStatus.CONFLICT
                send.setData((if (accepted) "Request accepted; query authenticated info/StartState for outcome." else "No action accepted; start is busy or no countdown exists.").toWebStatusJson(if (accepted) "ACCEPTED" else "CONFLICT"))
                return
            }
            val nmsl = Cherry()
            val response = HeadlessModuleManage.hps.room.clientHandler.handleMessage(normalized, nmsl as PlayerHess)
            result.append(nmsl.outString.joinToString(Data.LINE_SEPARATOR))
            CommandResponseFormatter.appendFormattedError(response) {
                if (result.isNotEmpty()) {
                    result.append(Data.LINE_SEPARATOR)
                }
                result.append(it)
            }
            if (result.isEmpty()) {
                result.append("Command executed.")
            }
            send.setData(result.toString().toWebStatusJson())
        }
    }
}
