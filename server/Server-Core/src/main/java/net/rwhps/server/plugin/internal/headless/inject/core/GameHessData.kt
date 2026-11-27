/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.core

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.game.units.am
import net.rwhps.server.data.global.Data
import net.rwhps.server.dependent.redirections.game.FPSSleepRedirections
import net.rwhps.server.game.event.game.ServerGameOverEvent.GameOverData
import net.rwhps.server.game.headless.core.AbstractGameHessData
import net.rwhps.server.game.manage.IRwHpsManage
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.plugin.internal.headless.inject.core.link.PrivateClassLinkPlayer
import net.rwhps.server.plugin.internal.headless.inject.lib.PlayerConnectX
import net.rwhps.server.plugin.internal.headless.inject.net.GameVersionServer
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.Time
import net.rwhps.server.util.log.Log
import org.newdawn.slick.GameContainer

/**
 * @author Dr (dr@der.kim)
 */
internal class GameHessData: AbstractGameHessData {
    private var nextStatisticsSlot=0
    private val statisticsIdentities=HashMap<Int,Pair<String,String>>()
    private fun statisticsIdentity(slot:Int,identity:String):String {
        val cached=statisticsIdentities[slot];if(cached?.first==identity)return cached.second
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        statisticsIdentities[slot]=identity to hash;return hash
    }
    // Accuracy-first default: copy the bounded roster in one simulation frame.
    // Operators may opt into a soft budget; missing players remain explicitly partial.
    private val statisticsCopyBudgetNanos=(System.getProperty("cnkd.statistics.copyBudgetMicros")?.toLongOrNull()?:0L).coerceIn(0,50_000)*1000
    override fun statisticsSnapshot(slots: IntArray): Map<String,Any> {
        val engine=GameEngine.gameEngine
        val players=ArrayList<Map<String,Any>>()
        val deadline=if(statisticsCopyBudgetNanos==0L)Long.MAX_VALUE else System.nanoTime()+statisticsCopyBudgetNanos
        var visited=0
        for(offset in slots.indices) {
            if(visited>0 && System.nanoTime()>deadline)break
            val slot=slots[(nextStatisticsSlot+offset)%slots.size];visited++
            val player=n.k(slot)?:continue
            val stats=engine.bY.a(player)
            players.add(mapOf<String,Any>("id" to "p$slot","slot" to slot,"color" to player.K(),
                "connectionHash" to statisticsIdentity(slot,player.O?:""),
                "counters" to listOf(stats.c,stats.d,stats.e,stats.f,stats.g,stats.h),
                "values" to com.corrodinggames.rts.gameFramework.bj.values().map{it.a().a(player)},
                "ended" to (player.b()||player.G||player.F||player.E)))
        }
        if(slots.isNotEmpty())nextStatisticsSlot=(nextStatisticsSlot+maxOf(1,visited))%slots.size
        players.sortBy{(it["slot"] as Number).toInt()}
        return mapOf("simulationMs" to engine.by.toLong(),"tick" to engine.bx.toLong(),"players" to players)
    }
    override val tickHess: Int get() = GameEngine.gameEngine.bx
    override val tickNetHess: Int get() = GameEngine.netEngine.X

    override val gameDelta: Long get() = FPSSleepRedirections.deltaMillis
    override val gameFPS: Int get() = (GameEngine.appGameContainerObject as GameContainer).fps

    override fun getWin(position: Int): Boolean {
        val teamData: n = n.k(position) ?: return false

        return !teamData.b() && !teamData.G && !teamData.F && !teamData.E
    }

    private fun getWin(player: n?): Boolean {
        val teamData: n = player ?: return false
        return !teamData.b() && !teamData.G && !teamData.F && !teamData.E
    }

    override fun getGameOverData(): GameOverData? {
        var lastWinTeam: Int = -1
        var lastWinCount = 0

        for (position in 0 until Data.configServer.maxPlayer) {
            val player: n = n.k(position) ?: continue
            if (getWin(player) && player.r != lastWinTeam) {
                lastWinTeam = player.r
                lastWinCount++
            }
        }

        if (lastWinCount == 1) {
            val winPlayer = Seq<String>().apply {
                for (position in 0 until Data.configServer.maxPlayer) {
                    val player: n = n.k(position) ?: continue
                    if (player.r == lastWinTeam) {
                        add(player.v)
                    }
                }
            }
            val allPlayer = Seq<String>()

            val statusData = ObjectMap<String, ObjectMap<String, Int>>().apply {
                for (position in 0 until Data.configServer.maxPlayer) {
                    val player: n = n.k(position) ?: continue
                    put(player.v, PrivateClassLinkPlayer(player).let {
                        ObjectMap<String, Int>().apply {
                            put("unitsKilled", it.unitsKilled)
                            put("buildingsKilled", it.buildingsKilled)
                            put("experimentalsKilled", it.experimentalsKilled)
                            put("unitsLost", it.unitsLost)
                            put("buildingsLost", it.buildingsLost)
                            put("experimentalsLost", it.experimentalsLost)
                        }
                    })
                    allPlayer.add(player.v)
                }
            }

            return GameOverData(
                    Time.concurrentSecond() - GameEngine.data.room.startTime,
                    allPlayer,
                    winPlayer,
                    GameEngine.data.room.maps.mapName,
                    statusData,
                    GameEngine.data.room.replayFileName
            )
        } else {
            return null
        }
    }

    override fun getPlayerBirthPointXY() {
        GameEngine.data.room.playerManage.playerGroup.eachAll { player ->
            n.k(player.index).let {
                var flagA = false
                var flagB = false
                var x: Float? = null
                var y: Float? = null
                var x2: Float? = null
                var y2: Float? = null

                for (amVar in am.bF()) {
                    if ((amVar is am) && !amVar.bV && amVar.bX == it) {
                        if (amVar.bO && !flagA) {
                            flagA = true
                            x = amVar.eo
                            y = amVar.ep
                        }
                        if (amVar.bP && !flagB) {
                            flagB = true
                            x2 = amVar.eo
                            y2 = amVar.ep
                        }
                    }
                }

                if (x == null) {
                    x = x2
                    y = y2
                }
                Log.clog("Position ${player.position} , $x $y")
            }
        }
    }

    override fun existPlayer(position: Int): Boolean {
        return n.k(position) != null
    }

    override fun getHeadlessAIServer(): AbstractNetConnectServer {
        val rwHps = IRwHpsManage.firstServer ?: IRwHpsManage.addIRwHps(IRwHps.NetType.ServerProtocol, "HeadlessAI")
        return GameVersionServer(PlayerConnectX(GameEngine.netEngine, ConnectionAgreement(rwHps)))
    }
}
