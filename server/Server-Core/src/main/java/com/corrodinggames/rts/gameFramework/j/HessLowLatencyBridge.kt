/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package com.corrodinggames.rts.gameFramework.j

import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap

internal object HessLowLatencyBridge {
    private data class NetEngineAccessors(
        val qField: Field?,
        val rField: Field?,
        val xField: Field?,
        val queueField: Field?
    )

    private data class PacketAccessors(
        val typeField: Field?
    )

    private data class MethodKey(
        val targetClass: Class<*>,
        val argumentClass: Class<*>
    )

    private val netEngineAccessorsCache = ConcurrentHashMap<Class<*>, NetEngineAccessors>()
    private val packetAccessorsCache = ConcurrentHashMap<Class<*>, PacketAccessors>()
    private val processPacketMethodCache = ConcurrentHashMap<MethodKey, Method>()
    private val missingProcessPacketMethods = ConcurrentHashMap.newKeySet<MethodKey>()

    fun beforeNetEngineFrame(netEngine: Any) {
        try {
            val lowLatencyConfig = SingleRoomLowLatencySupport.runtimeConfig()
            SingleRoomLowLatencySupport.tryApplyHessOutboundCommandLead(netEngine, lowLatencyConfig)
            repairFrameInvariant(netEngine, lowLatencyConfig)
            val roomStarted = roomStarted()
            val gameCommandHotPathRoomReady = gameCommandHotPathRoomReady()
            val metricsEnabled = lowLatencyConfig.metricsEnabled
            val accessors = netEngineAccessors(netEngine.javaClass)
            val queue = accessors.queueField?.get(netEngine) as? Queue<*>
            if (metricsEnabled) {
                SingleRoomLowLatencySupport.recordHessFrameState(
                    q = intFieldValue(netEngine, accessors.qField),
                    r = intFieldValue(netEngine, accessors.rField),
                    x = intFieldValue(netEngine, accessors.xField),
                    queueDepth = queue?.size ?: -1,
                    roomStarted = roomStarted,
                    runtimeConfig = lowLatencyConfig
                )
            }
            if (queue != null) {
                drainGameCommandsBeforeTick(netEngine, queue, gameCommandHotPathRoomReady, metricsEnabled, lowLatencyConfig)
            }
        } catch (error: ThreadDeath) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: LinkageError) {
            SingleRoomLowLatencySupport.recordEngineBridgeLinkageFailed(
                "hess_frame_bridge",
                error,
                SingleRoomLowLatencySupport.runtimeConfig()
            )
        } catch (_: Throwable) {
            SingleRoomLowLatencySupport.recordHessFrameBridgeFailed()
        }
    }

    /**
     * Repairs the engine's frame/nest-step invariant `l.bx <= ad.X` before the tick advances.
     *
     * The engine asserts this invariant in `ad.a(FZ)V` and throws
     * `RuntimeException("game frame:" + l.bx + " is greater then nest step:" + ad.X)` when it is
     * violated. `ad.s()` resets `ad.X` to 0 but never clears `l.bx`; only `ad.aD()` zeroes both,
     * and that path is gated off by `ad.aY`. Any asymmetric reset therefore leaves `l.bx` stale.
     *
     * This runs at the `ad.a(F)V` entry injection point (pc=22), which dominates every `ad.a(FZ)V`
     * assertion site in the tick, and repairs by mirroring the engine's own resync semantics
     * (`ad.w()V` pc=219 and `ad.a(au)V` pc=817 both compute `X = l.bx + 1`).
     *
     * NOTE: this is a *boundary* reconciliation, not an intra-tick enforcer — `l.bx` can still be
     * raised later in the same tick (e.g. the `ba.h()` resync path). The repair counter is the
     * signal that such a residual path is active, so it is logged unconditionally rather than
     * routed through the metrics-gated bridge-failure counter.
     *
     * Runs inside the caller's try/catch, but additionally guards itself so a failure here cannot
     * abort the surrounding low-latency work.
     */
    private fun repairFrameInvariant(netEngine: Any, runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig) {
        try {
            if (!SingleRoomLowLatencySupport.hessFrameInvariantGuardEnabled(runtimeConfig)) {
                return
            }
            val xField = netEngineAccessors(netEngine.javaClass).xField
            if (xField == null) {
                SingleRoomLowLatencySupport.recordHessFrameInvariantGuardFailed("x_field_missing", null)
                return
            }
            val gameFrame = GameEngine.gameEngine.bx
            val nextStep = xField.getInt(netEngine)
            if (gameFrame <= nextStep) {
                return
            }
            xField.setInt(netEngine, gameFrame + 1)
            SingleRoomLowLatencySupport.recordHessFrameInvariantRepaired(gameFrame, nextStep)
        } catch (error: ThreadDeath) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            // Never let the guard itself break the frame loop, but never fail silently either:
            // a broken guard must be distinguishable from "nothing to repair".
            SingleRoomLowLatencySupport.recordHessFrameInvariantGuardFailed(
                error.javaClass.simpleName,
                error
            )
        }
    }

    private fun drainGameCommandsBeforeTick(
        netEngine: Any,
        queue: Queue<*>,
        roomStarted: Boolean,
        metricsEnabled: Boolean,
        lowLatencyConfig: SingleRoomLowLatencySupport.RuntimeConfig
    ) {
        if (!SingleRoomLowLatencySupport.shouldDrainGameCommandBeforeTick(roomStarted, lowLatencyConfig)) {
            if (metricsEnabled && SingleRoomLowLatencySupport.shouldRecordPreTickDrainSkippedNotStarted(roomStarted, lowLatencyConfig)) {
                SingleRoomLowLatencySupport.recordGameCommandPreTickDrainSkippedNotStarted(queue.size, lowLatencyConfig)
            }
            return
        }

        var drained = 0
        val limit = lowLatencyConfig.preTickGameCommandDrainLimit
        val netEngineClass = netEngine.javaClass
        var cachedPacketClass: Class<*>? = null
        var cachedPacketAccessors: PacketAccessors? = null
        var cachedProcessPacket: Method? = null
        while (drained < limit) {
            val packet = queue.peek() ?: break
            val packetClass = packet.javaClass
            if (cachedPacketClass !== packetClass) {
                cachedPacketClass = packetClass
                cachedPacketAccessors = packetAccessors(packetClass)
                cachedProcessPacket = processPacketMethod(netEngineClass, packetClass) ?: return
            }

            val packetAccessors = cachedPacketAccessors ?: return
            val processPacket = cachedProcessPacket ?: return
            val packetType = intFieldValue(packet, packetAccessors.typeField)
            if (packetType != PacketType.GAMECOMMAND_RECEIVE.typeInt) {
                if (metricsEnabled) {
                    SingleRoomLowLatencySupport.recordGameCommandPreTickDrainBlockedByNonCommand(packetType, queue.size, lowLatencyConfig)
                }
                break
            }

            try {
                val drainedPacket = queue.poll() ?: break
                processPacket.invoke(netEngine, drainedPacket)
                drained++
            } catch (_: Exception) {
                if (metricsEnabled) {
                    SingleRoomLowLatencySupport.recordGameCommandPreTickDrainFailed(lowLatencyConfig)
                }
                break
            }
        }

        if (metricsEnabled) {
            SingleRoomLowLatencySupport.recordGameCommandPreTickDrained(drained, queue.size, lowLatencyConfig)
        }
    }

    private fun roomStarted(): Boolean {
        return try {
            GameEngine.data.room.isStartGame
        } catch (_: Exception) {
            false
        }
    }

    private fun gameCommandHotPathRoomReady(): Boolean {
        return try {
            val room = GameEngine.data.room
            SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(
                room.isStartGame,
                room.isGameOverResetInProgress()
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun intFieldValue(target: Any, field: Field?): Int {
        return (field?.get(target) as? Int) ?: -1
    }

    private fun netEngineAccessors(targetClass: Class<*>): NetEngineAccessors {
        return netEngineAccessorsCache.computeIfAbsent(targetClass) {
            NetEngineAccessors(
                qField = field(it, "Q"),
                rField = field(it, "R"),
                xField = field(it, "X"),
                queueField = field(it, "aN")
            )
        }
    }

    private fun packetAccessors(targetClass: Class<*>): PacketAccessors {
        return packetAccessorsCache.computeIfAbsent(targetClass) {
            PacketAccessors(typeField = field(it, "b"))
        }
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

    private fun processPacketMethod(targetClass: Class<*>, argumentClass: Class<*>): Method? {
        val key = MethodKey(targetClass, argumentClass)
        processPacketMethodCache[key]?.let { return it }
        if (key in missingProcessPacketMethods) {
            return null
        }

        var current: Class<*>? = targetClass
        while (current != null) {
            val method = current.declaredMethods.firstOrNull {
                it.name == "a" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(argumentClass)
            }
            if (method != null) {
                method.isAccessible = true
                processPacketMethodCache[key] = method
                return method
            }
            current = current.superclass
        }
        missingProcessPacketMethods += key
        return null
    }
}
