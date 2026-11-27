/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.util

import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.file.load.I18NBundle

/**
 *
 *
 * @date 2024/2/7 14:24
 * @author Dr (dr@der.kim)
 */
object TabCompleterProcess {
    private val playerManage = GameEngine.data.room.playerManage

    fun playerPosition(nameOrPosition: String, strCons: StrCons): PlayerHess? {
        return playerPosition(nameOrPosition, Data.i18NBundle) { strCons(it) }
    }

    fun playerPosition(nameOrPosition: String, player: PlayerHess): PlayerHess? {
        return playerPosition(nameOrPosition, player.i18NBundle) { player.sendSystemMessage(it) }
    }

    private fun playerPosition(nameOrPosition: String, i18NBundle: I18NBundle, print: (String)->Unit): PlayerHess? {
        var result = playerManage.playerAll.find { it.name == nameOrPosition }
        if (result == null) {
            if (IsUtils.notIsNumeric(nameOrPosition)) {
                print(i18NBundle.getinput("err.noNumber"))
                return null
            } else {
                result = playerManage.getPlayer(nameOrPosition.toInt() - 1)
                if (result == null) {
                    print(i18NBundle.getinput("err.player.no.site", nameOrPosition.toInt()))
                    return null
                }
            }
        }
        return result
    }
}