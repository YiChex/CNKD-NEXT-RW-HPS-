/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server

import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.game.command.CommandHandler.Command

/**
 * @date 2023/7/27 11:01
 * @author Dr (dr@der.kim)
 */
class Test : Plugin() {
    override fun registerServerCommands(handler: CommandHandler) {
        val command = handler.commandList.find {
            // 找到命令为 start 的 Command 实例
            it.text == "start"
        }
        handler.removeCommand("start")


        handler.register("start", "clientCommands.start") { args: Array<String>?, player: PlayerHess? ->
            @Suppress("UNCHECKED_CAST")
            val runStart = ReflectionUtils.findField(Command::class.java, "runner", CommandHandler.CommandRunner::class.java)!!.get(command)
                    as CommandHandler.CommandRunner<PlayerHess?>
            runStart.accept(args, player)
        }
    }
}
