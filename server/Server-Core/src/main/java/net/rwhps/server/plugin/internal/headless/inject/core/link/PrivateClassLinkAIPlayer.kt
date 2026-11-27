/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core.link

import com.corrodinggames.rts.game.n
import net.rwhps.server.util.game.InternalConversion
import net.rwhps.server.util.inline.ifNullResult
import net.rwhps.server.util.math.RandomUtils

/**
 *
 *
 * @date 2024/1/30 19:06
 * @author Dr (dr@der.kim)
 */
internal class PrivateClassLinkAIPlayer(private val playerData: n): PrivateClassLinkPlayer(playerData) {
    override val name get() = "AI-${InternalConversion.getAIDifficultString(aiDifficulty)}"

    override val connectHexID = "AI-${RandomUtils.getRandomString(8)}"

    override var aiDifficulty: Int
        get() = playerData.z.ifNullResult(playerData.x) { it }
        set(value) {
            playerData.z = value
        }
}