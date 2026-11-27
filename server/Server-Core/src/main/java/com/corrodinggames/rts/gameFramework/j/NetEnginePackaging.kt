/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package com.corrodinggames.rts.gameFramework.j

import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.plugin.internal.headless.inject.lib.PlayerConnectX
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import com.corrodinggames.rts.gameFramework.j.au as PacketHess

/**
 * 将 Game-Lib 的 Packet 与 RW-HPS 的 Packet 互相转换
 * 并且将包输入进 NetEngine
 *
 * @property netEngine NetEngine
 * @property playerConnectX PlayerConnectX
 * @constructor
 *
 * @author Dr (dr@der.kim)
 */
class NetEnginePackaging(private val netEngine: Any, private val playerConnectX: PlayerConnectX) {
    private data class EngineAccessors(
        val processMethod: Method?,
        val directMethod: Method?,
        val frameQueueField: Field?
    )
    /**
     * 将 Packet 扔进 Hess 内的处理器
     *
     * @param packet Packet
     */
    fun addPacket(packet: Packet) {
        val packetHess = transformHessPacket(packet)
        val lowLatencyConfig = if (packet.type == PacketType.GAMECOMMAND_RECEIVE) {
            SingleRoomLowLatencySupport.runtimeConfig()
        } else {
            null
        }

        if (lowLatencyConfig != null) {
            SingleRoomLowLatencySupport.tryApplyHessOutboundCommandLead(netEngine, lowLatencyConfig)
            if (lowLatencyConfig.metricsEnabled) {
                SingleRoomLowLatencySupport.recordEngineEnqueue(packet, lowLatencyConfig)
            }
        }

        val gameCommandHotPathRoomReady = lowLatencyConfig != null &&
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                playerConnectX.room.isStartGame,
                playerConnectX.room.isGameOverResetInProgress()
            )

        if (lowLatencyConfig != null &&
            SingleRoomLowLatencySupport.shouldInlineGameCommandProcessing(packet, gameCommandHotPathRoomReady, lowLatencyConfig)
        ) {
            try {
                val processed = invokePacketMethod(engineAccessors().processMethod, packetHess)
                if (lowLatencyConfig.metricsEnabled) {
                    if (processed) {
                        SingleRoomLowLatencySupport.recordGameCommandInlineProcessed(lowLatencyConfig)
                    } else {
                        SingleRoomLowLatencySupport.recordGameCommandInlineFailed(lowLatencyConfig)
                    }
                }
                if (processed) {
                    return
                }
            } catch (_: Exception) {
                if (lowLatencyConfig.metricsEnabled) {
                    SingleRoomLowLatencySupport.recordGameCommandInlineFailed(lowLatencyConfig)
                }
            }
        }

        val accessors = engineAccessors()
        if (packet.type.typeInt > 100) {
            if (!invokePacketMethod(accessors.directMethod, packetHess)) {
                throw IllegalStateException("NetEngine direct packet method c(au) not found")
            }
        } else {
            val frameQueue = frameQueue(accessors)
            frameQueue.add(packetHess)
            if (lowLatencyConfig != null &&
                lowLatencyConfig.metricsEnabled &&
                SingleRoomLowLatencySupport.shouldRecordGameCommandFrameQueueDepth(packet, lowLatencyConfig)
            ) {
                SingleRoomLowLatencySupport.recordGameCommandFrameQueued(packet, frameQueue.size, lowLatencyConfig)
            }
        }
    }

    /**
     * 转换[Packet]为Hess的[PacketHess]
     *
     * @param packet RW-HPS的[PacketHess]
     * @return Hess 的 [PacketHess]
     */
    fun transformHessPacket(packet: Packet): PacketHess {
        return PacketHess(packet.type.typeInt).apply {
            c = packet.bytes
            a = playerConnectX
        }
    }

    /**
     * 转换[Packet]为Hess的[PacketHess]
     *
     * @param packetHess Hess的[PacketHess]
     * @return RW-HPS的[PacketHess]
     */
    fun transformPacket(packetHess: PacketHess): Packet {
        val packetType = when (packetHess.b) {
            PacketType.TICK.typeInt -> PacketType.TICK
            PacketType.SYNC.typeInt -> PacketType.SYNC
            PacketType.SYNC_CHECK.typeInt -> PacketType.SYNC_CHECK
            PacketType.SYNCCHECKSUM_STATUS.typeInt -> PacketType.SYNCCHECKSUM_STATUS
            PacketType.TEAM_LIST.typeInt -> PacketType.TEAM_LIST
            PacketType.SERVER_INFO.typeInt -> PacketType.SERVER_INFO
            PacketType.HEART_BEAT.typeInt -> PacketType.HEART_BEAT
            PacketType.HEART_BEAT_RESPONSE.typeInt -> PacketType.HEART_BEAT_RESPONSE
            PacketType.CHAT.typeInt -> PacketType.CHAT
            PacketType.KICK.typeInt -> PacketType.KICK
            PacketType.DISCONNECT.typeInt -> PacketType.DISCONNECT
            PacketType.START_GAME.typeInt -> PacketType.START_GAME
            PacketType.RETURN_TO_BATTLEROOM.typeInt -> PacketType.RETURN_TO_BATTLEROOM
            else -> null
        }
        return if (packetType != null) {
            Packet(packetType, packetHess.c)
        } else {
            Packet(packetHess.b, packetHess.c)
        }
    }

    companion object {
        private val engineAccessorsCache = ConcurrentHashMap<Class<*>, EngineAccessors>()

        fun transformHessPacketNullSource(packet: Packet): PacketHess {
            return PacketHess(packet.type.typeInt).apply {
                c = packet.bytes
            }
        }

        private fun packetMethod(targetClass: Class<*>, name: String, argumentClass: Class<*>): Method? {
            var current: Class<*>? = targetClass
            while (current != null) {
                val method = current.declaredMethods.firstOrNull {
                    it.name == name &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(argumentClass)
                }
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
                current = current.superclass
            }
            return null
        }

        private fun field(targetClass: Class<*>, name: String): Field? {
            var current: Class<*>? = targetClass
            while (current != null) {
                try {
                    return current.getDeclaredField(name).also {
                        it.isAccessible = true
                    }
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            return null
        }
    }

    private fun engineAccessors(): EngineAccessors {
        return engineAccessorsCache.computeIfAbsent(netEngine.javaClass) {
            EngineAccessors(
                processMethod = packetMethod(it, "a", PacketHess::class.java),
                directMethod = packetMethod(it, "c", PacketHess::class.java),
                frameQueueField = field(it, "aN")
            )
        }
    }

    private fun invokePacketMethod(method: Method?, packetHess: PacketHess): Boolean {
        method ?: return false
        method.invoke(netEngine, packetHess)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun frameQueue(accessors: EngineAccessors): MutableCollection<Any?> {
        return accessors.frameQueueField?.get(netEngine) as? MutableCollection<Any?>
            ?: throw IllegalStateException("NetEngine frame packet queue aN not found")
    }
}
