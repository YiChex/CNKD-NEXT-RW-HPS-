/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.moreheadless

import net.rwhps.server.game.event.EventGlobalManage
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.util.game.command.CommandHandler

/**
 * @author Dr (dr@der.kim)
 */
class MoreHessMain: Plugin() {
    override fun registerGlobalEvents(eventManage: EventGlobalManage) {
    }

    override fun registerCoreCommands(handler: CommandHandler) {

    }

    override fun registerServerCommands(handler: CommandHandler) {
    }
}