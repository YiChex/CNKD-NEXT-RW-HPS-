/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core.link

import net.rwhps.server.util.annotations.mark.GameSimulationLayer

data class LobbyPlayerSyncResult(
    val success: Boolean,
    val error: Throwable? = null
)


/**
 * 链接游戏内的方法
 *
 * @author Dr (dr@der.kim)
 */
interface AbstractLinkGameFunction {
    /** Full game snapshot sync for an active match. */
    fun allPlayerSync()

    /**
     * Refresh the lobby-facing server information from the injected game loader.
     */
    fun refreshLobbyServerInfo(): Boolean

    /**
     * Publish lobby-facing player and server information without serializing a
     * full game snapshot.
     */
    fun canSyncLobbyPlayers(): Boolean

    fun syncLobbyPlayers(): LobbyPlayerSyncResult

    fun pauseGame(pause: Boolean)

    /**
     * 返回战役室
     *
     * @param time 倒计时
     */
    fun battleRoom(
        time: Int = 5,
        allowDuringSettlement: Boolean = false,
        onCompleted: () -> Unit = {},
        onFailed: (Throwable) -> Unit = {}
    ): Boolean

    /**
     * 保存游戏到 save
     */
    fun saveGame()

    @GameSimulationLayer.GameSimulationLayer_KeyWords("exited!")
    fun clean()
}
