/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http.data

import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.event.game.ServerGameOverEvent.GameOverData
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.list.SeqSave
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.data.global.Data
import net.rwhps.server.util.Time

import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.net.NetService
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import java.util.concurrent.atomic.AtomicInteger

/**
 * @date  2023/6/27 16:03
 * @author Dr (dr@der.kim)
 */
internal object GetData {
    const val ServerTypeClose = "{ \"Status\": \"Unsupported protocols, only Server is supported\" }"
    val consoleCache = SeqSave(20)
    val agentConsole = Seq<String>(16, true)
    val agentConsoleLog = ObjectMap<String, (String) -> Unit>(true)

    data class GameOverPositive(
        val gameOverData: GameOverData
    ) {
        companion object {
            val data = SeqSave(10)
        }
    }

    data class SystemInfo(
        val system: String = SystemUtils.osName,
        val arch: String = SystemUtils.osArch,
        val jvmName: String = SystemUtils.javaName,
        val jvmVersion: String = SystemUtils.javaVersion
    )

    data class GameInfo(
        val income: Float = HeadlessModuleManage.hps.gameLinkServerData.income,
        val noNukes: Boolean = StartModeGameOptionSupport.currentNoNukes(),
        val credits: Int = StartModeGameOptionSupport.currentDisplayedCreditsAmount(),
        val fog: Int = StartModeGameOptionSupport.currentFogValue(),
        val sharedControl: Boolean = HeadlessModuleManage.hps.gameLinkServerData.sharedcontrol,
        val startGame: Boolean = HeadlessModuleManage.hps.room.isStartGame,
        val players: List<String> = Seq<String>().apply {
            HeadlessModuleManage.hps.room.playerManage.playerAll.eachAll {
                add(it.playerInfo)
            }
        }
    )
    
    data class RelayInfo(
        val serverVersion: String = Data.SERVER_CORE_VERSION.also { println("服务器版本: $it") },
    )
}
