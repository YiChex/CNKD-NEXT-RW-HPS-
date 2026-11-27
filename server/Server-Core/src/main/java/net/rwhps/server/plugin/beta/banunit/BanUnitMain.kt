/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.banunit

import net.rwhps.server.plugin.Plugin
import net.rwhps.server.util.game.command.CommandHandler

/**
 * @author Dr (dr@der.kim)
 */
class BanUnitMain: Plugin() {
    override fun registerServerCommands(handler: CommandHandler) {

    }

    /**
     * Inject multiple languages into the server
     * @author Dr (dr@der.kim)
     */
    private class AddLang(val plugin: Plugin) {
        init {
            help()
        }

        private fun help() {
            loadCN("banunit", "禁止玩家使用指定的单位")
            loadCN("banunit.nofind", "没有找到指定ID的数据")
        }

        private fun loadCN(k: String, v: String) {
            plugin.loadLang("CN", k, v)
        }

        private fun loadEN(k: String, v: String) {
            plugin.loadLang("EN", k, v)
        }

        private fun loadHK(k: String, v: String) {
            plugin.loadLang("HK", k, v)
        }

        private fun loadRU(k: String, v: String) {
            plugin.loadLang("RU", k, v)
        }
    }
}