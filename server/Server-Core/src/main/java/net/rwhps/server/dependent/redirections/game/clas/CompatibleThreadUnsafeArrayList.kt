/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game.clas

import com.corrodinggames.rts.gameFramework.j.HessLowLatencyBridge
import com.corrodinggames.rts.gameFramework.j.au
import com.corrodinggames.rts.gameFramework.j.k
import net.rwhps.asm.api.listener.RedirectionListener
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport

/**
 *
 *
 * @date 2024/3/30 13:18
 * @author Dr (dr@der.kim)
 */
object CompatibleThreadUnsafeArrayList : RedirectionListener {
    override fun invoke(obj: Any, desc: String, vararg args: Any?) {
        if (obj.javaClass.name == "com.corrodinggames.rts.gameFramework.j.ad") {
            HessLowLatencyBridge.beforeNetEngineFrame(obj)
        }

        val commandPacketList = GameEngine.data.gameData.commandPacketList
        if (commandPacketList.isNotEmpty() && SingleRoomLowLatencySupport.isServerInjectedCommandReady()) {
            val cache = commandPacketList.drain()
            cache.eachAll { bytes ->
                val commandPacket = GameEngine.gameEngine.cf.b()
                GameOutputStream().use {
                    it.flushEncodeData(CompressOutputStream.getGzipOutputStream("c", false).apply {
                        writeBytes(bytes)
                    })
                    commandPacket.a(k(transformHessPacketNullSource(it.createPacket(PacketType.TICK))))
                }

                commandPacket.c = GameEngine.data.gameHessData.tickNetHess + SingleRoomLowLatencySupport.serverInjectedCommandLeadTicks()
                GameEngine.gameEngine.cf.b.add(commandPacket)
            }
        }
    }

    private fun transformHessPacketNullSource(packet: net.rwhps.server.io.packet.Packet): au {
        return au(packet.type.typeInt).apply {
            c = packet.bytes
        }
    }
}
