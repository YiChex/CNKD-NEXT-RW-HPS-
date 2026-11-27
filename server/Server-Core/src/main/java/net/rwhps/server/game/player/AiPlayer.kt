/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.player

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.headless.core.link.AbstractLinkPlayerData
import net.rwhps.server.net.core.server.AbstractNetConnectServer

/**
 *
 *
 * @date 2024/1/30 18:58
 * @author Dr (dr@der.kim)
 */
class AiPlayer(
    conIn: AbstractNetConnectServer,
    playerPrivateData: AbstractLinkPlayerData
): PlayerHess(
        conIn,
        Data.i18NBundle,
        playerPrivateData
) {
    override val isAi: Boolean = true

    override fun kickPlayer(text: String, time: Int) {
        playerPrivateData.removePlayer()

        con?.let { c ->
            c.room.playerManage.playerGroup.remove(this)
            c.room.playerManage.playerAll.remove(this)
        }

        super.kickPlayer(text, time)
    }
}