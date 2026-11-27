/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.player.unofficial

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.game.SystemMessageTextMode

/**
 * 叔叔我啊 最喜欢钱了
 *
 * @date 2024/2/16 17:55
 * @author Dr (dr@der.kim)
 */
class Cherry: PlayerHess(
        null,
        Data.i18NBundle,
        HeadlessModuleManage.hps.gameLinkServerData.getDefPlayerData()
) {
    init {
        isAdmin = true
        autoAdmin = true
        superAdmin = true
    }

    val outString: Seq<String> = Seq()

    override fun sendSystemMessage(text: String, mode: SystemMessageTextMode) {
        outString.add(text)
    }

    override fun kickPlayer(text: String, time: Int) {
        outString.add(text)
    }
}
