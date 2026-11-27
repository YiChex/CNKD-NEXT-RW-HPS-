/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http.post.run

import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
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
object ServerCommand : AbstractPost() {
    override val prefixURL: String = "ServerCommand"

    override fun post(accept: AcceptWeb, send: SendWeb) {
        val result = StringBuilder()
        checkStringPost(accept, send, "runCommand")?.let { command ->
            val response = Data.SERVER_COMMAND.handleMessage(command, StrCons {
                if (result.isNotEmpty()) {
                    result.append(Data.LINE_SEPARATOR)
                }
                result.append(it)
            })
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
