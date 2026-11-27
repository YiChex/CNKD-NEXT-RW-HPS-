/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.plugin.internal.headless.inject.ex

import com.corrodinggames.rts.gameFramework.j.au
import com.corrodinggames.rts.gameFramework.j.k
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.game.enums.GameInternalUnits
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.util.ExtractUtils
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.util.Time
import net.rwhps.server.util.annotations.DidNotFinish
import net.rwhps.server.util.game.CommonUtils
import net.rwhps.server.util.log.Log
import java.util.concurrent.TimeUnit

/**
 * ONLY Test
 * 能跑, 但不是理想状态, 我不想去折腾
 *
 * @date  2023/5/28 17:49
 * @author Dr (dr@der.kim)
 */
@DidNotFinish
class FFA_X {
    var setup = false
    var circleX = 0f
    var circleY = 0f
    var circleSize = 0f
    var nextUpdate = 0
    var nextUpdateWarned = false
    var circleLastChangedBy = 0f
    var conf_shrinkByPercent = 0f
    var conf_shrinkByAmount = 0f
    var conf_minSize = 0f
    var conf_firstShrinkTime = 0
    var conf_shrinkTime = 0
    var conf_shrinkWarnTime = 0

    fun onNewCommand(): Boolean {
        //if (command == "shrinknow") {
        nextUpdate = 0
        return true
        //}
//        if (command == "borderpause") {
//            nextUpdate = Int.MAX_VALUE
//            return true
//        }
//        return false
    }

    fun onLoad() {
        Log.debug("ClosingBorder module ready")
        //val section = "closingBorder"
        conf_shrinkByPercent = 30f
        conf_shrinkByAmount = 100f
        conf_firstShrinkTime = 10
        conf_shrinkTime = 10
        conf_shrinkWarnTime = 5
        conf_minSize = 400f
    }

    fun onNewGame() {
        setup = false
    }

    fun onEachFrame() {
        val map = GameEngine.mapEngineOrNull ?: return
        if (!map.W) {
            return
        }
        val getWidthInPixels = map.C.toFloat()
        val getHeightInPixels = map.D.toFloat()
        if (!setup) {
            setup = true
            Log.debug("ClosingBorder setup")

            //circleX=getWidthInPixels/2;
            //circleY=getHeightInPixels/2;
            circleX = getWidthInPixels / 2
            circleY = getHeightInPixels / 2
            circleSize = (map.C / 2).toFloat() * 1.4f
            circleLastChangedBy = 0f
            showSafeZone(circleX, circleY, circleSize)
            setCircle(circleX, circleY, circleSize)
            nextUpdate = Time.concurrentSecond() + conf_firstShrinkTime
        }
        if (!nextUpdateWarned && Time.concurrentSecond() > nextUpdate - conf_shrinkWarnTime) {
            nextUpdateWarned = true


            //float newSize=circleSize * 0.9f - 100;
            var newSize = circleSize * (1 - conf_shrinkByPercent / 100f) - conf_shrinkByAmount
            if (newSize < conf_minSize) newSize = conf_minSize
            val maxMovement = circleSize - newSize
            if (maxMovement > 0) {
                //game.network.sendSystemMessage("The border will shrink soon")
            }
            circleLastChangedBy = maxMovement
            val randomDir: Float = CommonUtils.rnd(-180f, 180f)
            val randomMove: Float = CommonUtils.rnd(0f, maxMovement)
            circleX += randomMove * CommonUtils.cos(randomDir)
            circleY += randomMove * CommonUtils.sin(randomDir)
            if (circleX < 0) circleX = 0f
            if (circleY < 0) circleY = 0f
            if (circleX > getWidthInPixels) circleX = getWidthInPixels
            if (circleY > getHeightInPixels) circleY = getHeightInPixels
            circleSize = newSize
            showSafeZone(circleX, circleY, circleSize)
        }
        if (Time.concurrentSecond() > nextUpdate) {
            if (circleLastChangedBy > 0) {
                //game.network.sendSystemMessage("The border is shrinking")
            }
            nextUpdate = Time.concurrentSecond() + conf_shrinkTime
            nextUpdateWarned = false
            setCircle(circleX, circleY, circleSize)
        }
    }

    fun setCircle(x: Float, y: Float, size: Float) {
        Log.debug("ClosingBorder: moving border to: $x, $y size:$size")
        queueUnitSpawnCommand(GameInternalUnits.damagingBorder, x, y, (size / 100).toInt())
    }

    fun showSafeZone(x: Float, y: Float, size: Float) {
        Log.debug("ClosingBorder: showing safe zone at: $x, $y size:$size")
        queueUnitSpawnCommand(GameInternalUnits.zoneMarker, x, y, (size / 100).toInt())
    }

    fun queueUnitSpawnCommand(unit: GameInternalUnits, x: Float, y: Float, size: Int) {
        try {
            val commandPacket = GameEngine.gameEngine.cf.b()

            val out = GameOutputStream()
            out.flushEncodeData(CompressOutputStream.getGzipOutputStream("c", false).apply {
                writeBytes(NetStaticData.RwHps.abstractNetPacket.gameSummonPacket(-1, unit.name, x, y, size).bytes)
            })

            commandPacket.a(k(transformHessPacketNullSource(out.createPacket(PacketType.TICK))))

            commandPacket.c = GameEngine.data.gameHessData.tickNetHess + SingleRoomLowLatencySupport.serverInjectedCommandLeadTicks()
            GameEngine.gameEngine.cf.b.add(commandPacket)
            //GameEngine.netEngine.a(commandPacket)
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    private fun transformHessPacketNullSource(packet: net.rwhps.server.io.packet.Packet): au {
        return au(packet.type.typeInt).apply {
            c = packet.bytes
        }
    }

    companion object {
        @JvmStatic
        fun start() {
            Log.clog("Start")
            val FFA = FFA_X()
            FFA.onNewCommand()
            FFA.onLoad()
            FFA.onNewGame()

            Threads.newTimedTask(
                    "FFA", "FFA", "FFA", 5000, 100, TimeUnit.MILLISECONDS
            ) {
                ExtractUtils.tryRunTest {
                    FFA.onEachFrame()
                }
            }
        }
    }
}
