/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanSingleRoomLowLatencyConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.io.packet.GameCommandOnePacket
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.util.log.Log
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object SingleRoomLowLatencySupport {
    private const val MAX_PENDING_METRIC_PACKETS = 8_192
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val HESS_FRAME_INVARIANT_WARN_INTERVAL_NANOS = 5L * NANOS_PER_SECOND

    private val defaultConfig = BeanSingleRoomLowLatencyConfig()
    @Volatile
    private var cachedRuntimeConfig: RuntimeConfig? = null
    @Volatile
    private var runtimeOverrideConfig: BeanSingleRoomLowLatencyConfig? = null
    @Volatile
    private var runtimeOverrideName: String? = null
    private val receivedGameCommandAtNanos = ConcurrentHashMap<Int, Long>()
    private val hessQFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val hessQFieldMissingClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    private val gameCommandReceivedCount = AtomicLong(0L)
    private val gameCommandPendingMetricPacketCount = AtomicLong(0L)
    private val gameCommandFastPathCount = AtomicLong(0L)
    private val gameCommandFastPathRejectedCount = AtomicLong(0L)
    private val gameCommandFastPathFallbackCount = AtomicLong(0L)
    private val gameCommandNormalPathCount = AtomicLong(0L)
    private val gameCommandStoppedCount = AtomicLong(0L)
    private val gameCommandClassifiedCount = AtomicLong(0L)
    private val gameCommandSystemActionCount = AtomicLong(0L)
    private val gameCommandSystemActionLast = AtomicLong(0L)
    private val gameCommandMapPingCount = AtomicLong(0L)
    private val gameCommandUnitOperationCount = AtomicLong(0L)
    private val gameCommandFactoryActionCount = AtomicLong(0L)
    private val gameCommandInternalActionCount = AtomicLong(0L)
    private val gameCommandUnknownActionCount = AtomicLong(0L)
    private val gameCommandClassificationFailedCount = AtomicLong(0L)
    private val gameCommandEngineQueuedCount = AtomicLong(0L)
    private val gameCommandFrameQueuedCount = AtomicLong(0L)
    private val gameCommandInlineProcessedCount = AtomicLong(0L)
    private val gameCommandInlineFailedCount = AtomicLong(0L)
    private val gameCommandFrameQueueDepthSamples = AtomicLong(0L)
    private val gameCommandFrameQueueDepthTotal = AtomicLong(0L)
    private val gameCommandFrameQueueDepthMax = AtomicLong(0L)
    private val gameCommandPreTickDrainedCount = AtomicLong(0L)
    private val gameCommandPreTickDrainFailedCount = AtomicLong(0L)
    private val gameCommandPreTickDrainPassCount = AtomicLong(0L)
    private val gameCommandPreTickDrainPassMax = AtomicLong(0L)
    private val gameCommandQueueLatencySamples = AtomicLong(0L)
    private val gameCommandQueueLatencyNanos = AtomicLong(0L)
    private val receiveToEngineLatencyWindow = LatencySampleWindow()
    private val outgoingTickCount = AtomicLong(0L)
    private val outgoingSyncCount = AtomicLong(0L)
    private val outgoingSyncCheckCount = AtomicLong(0L)
    private val outgoingTeamListCount = AtomicLong(0L)
    private val hessLeadAppliedCount = AtomicLong(0L)
    private val hessLeadLastValue = AtomicLong(10L)
    private val hessFrameObservedCount = AtomicLong(0L)
    private val hessFrameBridgeFailedCount = AtomicLong(0L)
    private val hessFrameInvariantRepairedCount = AtomicLong(0L)
    private val hessFrameInvariantGuardFailedCount = AtomicLong(0L)
    private val hessFrameInvariantGuardFailedLastWarnNanos = AtomicLong(0L)
    private val hessFrameInvariantLastGameFrame = AtomicLong(-1L)
    private val hessFrameInvariantLastNextStep = AtomicLong(-1L)
    private val hessFrameInvariantLastWarnNanos = AtomicLong(0L)
    private val inboundEngineRelayControlRejectedCount = AtomicLong(0L)
    private val inboundEngineRelayControlRejectedLastWarnNanos = AtomicLong(0L)
    private val hessLastQ = AtomicLong(10L)
    private val hessLastR = AtomicLong(0L)
    private val hessLastX = AtomicLong(0L)
    private val hessRoomStartedLast = AtomicLong(0L)
    private val hessFrameQueueDepthSamples = AtomicLong(0L)
    private val hessFrameQueueDepthTotal = AtomicLong(0L)
    private val hessFrameQueueDepthMax = AtomicLong(0L)
    private val hessFrameQueueDepthLast = AtomicLong(0L)
    private val hessFrameQueueDepthAfterDrainSamples = AtomicLong(0L)
    private val hessFrameQueueDepthAfterDrainTotal = AtomicLong(0L)
    private val hessFrameQueueDepthAfterDrainMax = AtomicLong(0L)
    private val hessFrameQueueDepthAfterDrainLast = AtomicLong(0L)
    private val engineBridgeFuseAtNanos = AtomicLong(0L)
    private val gameCommandPreTickDrainSkippedNotStartedCount = AtomicLong(0L)
    private val gameCommandPreTickDrainBlockedByNonCommandCount = AtomicLong(0L)
    private val gameCommandPreTickDrainBlockedByNonCommandLastType = AtomicLong(-1L)
    private val lastSummaryAtNanos = AtomicLong(0L)
    @Volatile
    private var gameCommandFastPathRejectedLastReason = "none"
    @Volatile
    private var gameCommandFastPathFallbackLastReason = "none"
    @Volatile
    private var gameCommandClassificationLastKind = "none"
    @Volatile
    private var gameCommandClassificationLastActionId = "none"
    @Volatile
    private var gameCommandClassificationLastSender = "none"
    @Volatile
    private var gameCommandClassificationLastSharedControlCheck = 0
    @Volatile
    private var gameCommandClassificationLastFailure = "none"
    @Volatile
    private var engineBridgeFuseReason = "none"

    enum class GameCommandClassification {
        SYSTEM_ACTION,
        MAP_PING,
        UNIT_OPERATION,
        FACTORY_ACTION,
        INTERNAL_ACTION,
        UNKNOWN
    }

    data class RuntimeConfig(
        val source: BeanSingleRoomLowLatencyConfig,
        val enabled: Boolean,
        val metricsEnabled: Boolean,
        val fastGameCommand: Boolean,
        val preTickGameCommandDrain: Boolean,
        val preTickGameCommandDrainRequiresStartedRoom: Boolean,
        val preTickGameCommandDrainLimit: Int,
        val inlineGameCommandProcessing: Boolean,
        val inlineGameCommandRequiresStartedRoom: Boolean,
        val skipPlayerOperationEvents: Boolean,
        val skipPingActionIntercept: Boolean,
        val disableTurnStoneIntoGoldOnFastPath: Boolean,
        val serverInjectedCommandLeadTicks: Int,
        val hessOutboundCommandLeadTicks: Int,
        val hessFrameInvariantGuardEnabled: Boolean,
        val headlessFpsOverride: Int,
        val nettyWorkerThreadsOverride: Int,
        val directEventLoopWrites: Boolean
    )

    fun enabled(): Boolean {
        return runtimeConfig().enabled
    }

    fun metricsEnabled(): Boolean {
        return runtimeConfig().metricsEnabled
    }

    fun runtimeConfig(): RuntimeConfig {
        val config = config()
        val cached = cachedRuntimeConfig
        if (cached != null && cached.source === config) {
            return cached
        }

        return runtimeConfig(config).also { cachedRuntimeConfig = it }
    }

    fun shouldUseGameCommandFastPath(playerReady: Boolean): Boolean {
        val config = runtimeConfig()
        return config.enabled &&
            config.fastGameCommand &&
            playerReady &&
            config.skipPlayerOperationEvents &&
            config.skipPingActionIntercept &&
            config.disableTurnStoneIntoGoldOnFastPath
    }

    fun shouldUseGameCommandFastPath(playerReady: Boolean, runtimeConfig: RuntimeConfig): Boolean {
        return runtimeConfig.enabled &&
            runtimeConfig.fastGameCommand &&
            playerReady &&
            runtimeConfig.skipPlayerOperationEvents &&
            runtimeConfig.skipPingActionIntercept &&
            runtimeConfig.disableTurnStoneIntoGoldOnFastPath
    }

    fun shouldInlineGameCommandProcessing(packet: Packet, gameCommandHotPathReady: Boolean): Boolean {
        return shouldInlineGameCommandProcessing(packet, gameCommandHotPathReady, runtimeConfig())
    }

    fun shouldInlineGameCommandProcessing(
        packet: Packet,
        gameCommandHotPathReady: Boolean,
        runtimeConfig: RuntimeConfig
    ): Boolean {
        return runtimeConfig.enabled &&
            runtimeConfig.fastGameCommand &&
            runtimeConfig.inlineGameCommandProcessing &&
            packet.type == PacketType.GAMECOMMAND_RECEIVE &&
            gameCommandHotPathReady
    }

    /**
     * The room START_GAME packet can be visible for a few frames before the
     * engine has rebuilt its map object. Optimized command handling must wait
     * until that object is ready.
     */
    fun isGameCommandHotPathRoomReady(roomStarted: Boolean, resetInProgress: Boolean): Boolean {
        val postReturnTrafficBlocked = runCatching {
            HeadlessModuleManage.initHPS() &&
                HeadlessModuleManage.hps.room.isBattleRoomTransitionCommitBlocking()
        }.getOrDefault(false)
        return isGameCommandHotPathRoomReady(
            roomStarted = roomStarted,
            resetInProgress = resetInProgress,
            playing = StartModeGameOverReturnSupport.isPlaying(),
            mapEngineReady = isMapEngineReady(),
            postReturnTrafficBlocked = postReturnTrafficBlocked
        )
    }

    internal fun isGameCommandHotPathRoomReady(
        roomStarted: Boolean,
        resetInProgress: Boolean,
        playing: Boolean,
        mapEngineReady: Boolean,
        postReturnTrafficBlocked: Boolean = false
    ): Boolean {
        return roomStarted && !resetInProgress && playing && mapEngineReady && !postReturnTrafficBlocked
    }

    fun isServerInjectedCommandReady(): Boolean {
        return runCatching {
            if (!HeadlessModuleManage.initHPS()) {
                return@runCatching false
            }
            val room = HeadlessModuleManage.hps.room
            isGameCommandHotPathRoomReady(room.isStartGame, room.isGameOverResetInProgress())
        }.getOrDefault(false)
    }

    fun isMapEngineReady(): Boolean {
        return StartModeReadinessSupport.mapEngineReadiness().ready
    }

    fun shouldDrainGameCommandBeforeTick(gameCommandHotPathReady: Boolean): Boolean {
        return shouldDrainGameCommandBeforeTick(gameCommandHotPathReady, runtimeConfig())
    }

    fun shouldDrainGameCommandBeforeTick(
        gameCommandHotPathReady: Boolean,
        runtimeConfig: RuntimeConfig
    ): Boolean {
        return runtimeConfig.enabled &&
            runtimeConfig.fastGameCommand &&
            runtimeConfig.preTickGameCommandDrain &&
            gameCommandHotPathReady
    }

    fun shouldForwardGameCommandDirectToEngine(
        roomStarted: Boolean,
        resetInProgress: Boolean,
        runtimeConfig: RuntimeConfig
    ): Boolean {
        return runtimeConfig.enabled &&
            runtimeConfig.fastGameCommand &&
            isGameCommandHotPathRoomReady(roomStarted, resetInProgress) &&
            !isEngineBridgeFused()
    }

    fun shouldRecordPreTickDrainSkippedNotStarted(roomStarted: Boolean): Boolean {
        return shouldRecordPreTickDrainSkippedNotStarted(roomStarted, runtimeConfig())
    }

    fun shouldRecordPreTickDrainSkippedNotStarted(roomStarted: Boolean, runtimeConfig: RuntimeConfig): Boolean {
        return runtimeConfig.enabled &&
            runtimeConfig.fastGameCommand &&
            runtimeConfig.preTickGameCommandDrain &&
            runtimeConfig.preTickGameCommandDrainRequiresStartedRoom &&
            runtimeConfig.metricsEnabled &&
            !roomStarted
    }

    fun preTickGameCommandDrainLimit(): Int {
        return runtimeConfig().preTickGameCommandDrainLimit
    }

    fun serverInjectedCommandLeadTicks(): Int {
        return runtimeConfig().serverInjectedCommandLeadTicks
    }

    fun hessOutboundCommandLeadTicks(): Int {
        return runtimeConfig().hessOutboundCommandLeadTicks
    }

    fun headlessFps(defaultFps: Int): Int {
        val runtimeConfig = runtimeConfig()
        return if (runtimeConfig.enabled && runtimeConfig.headlessFpsOverride > 0) {
            runtimeConfig.headlessFpsOverride
        } else {
            defaultFps
        }
    }

    fun applyHessOutboundCommandLead(netEngine: Any) {
        applyHessOutboundCommandLead(netEngine, runtimeConfig())
    }

    fun applyHessOutboundCommandLead(netEngine: Any, runtimeConfig: RuntimeConfig) {
        if (isEngineBridgeFused()) {
            return
        }

        val leadTicks = runtimeConfig.hessOutboundCommandLeadTicks
        val leadTicksLong = leadTicks.toLong()

        try {
            val qField = hessQField(netEngine) ?: run {
                recordHessFrameBridgeFailed(runtimeConfig)
                return
            }
            if (qField.getInt(netEngine) == leadTicks) {
                if (hessLeadLastValue.get() != leadTicksLong) {
                    hessLeadLastValue.set(leadTicksLong)
                }
                return
            }

            qField.setInt(netEngine, leadTicks)
            hessLeadLastValue.set(leadTicksLong)
            if (runtimeConfig.metricsEnabled) {
                hessLeadAppliedCount.incrementAndGet()
                maybeLogSummary(runtimeConfig.source, System.nanoTime())
            }
        } catch (error: ThreadDeath) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: LinkageError) {
            tripEngineBridgeFuse("hess_outbound_lead:${error.javaClass.simpleName}", error, runtimeConfig)
        } catch (_: Throwable) {
            recordHessFrameBridgeFailed(runtimeConfig)
        }
    }

    fun tryApplyHessOutboundCommandLead(netEngine: Any, runtimeConfig: RuntimeConfig): Boolean {
        if (isEngineBridgeFused()) {
            return false
        }

        return try {
            applyHessOutboundCommandLead(netEngine, runtimeConfig)
            !isEngineBridgeFused()
        } catch (error: ThreadDeath) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: LinkageError) {
            tripEngineBridgeFuse("hess_outbound_lead_call:${error.javaClass.simpleName}", error, runtimeConfig)
            false
        } catch (error: Throwable) {
            recordHessFrameBridgeFailed(runtimeConfig)
            false
        }
    }

    fun recordDirectEngineForwardFailed(error: Throwable, runtimeConfig: RuntimeConfig) {
        val reason = "direct_engine_forward:${error.javaClass.simpleName}"
        tripEngineBridgeFuse(reason, error, runtimeConfig)
        recordGameCommandFastPathFallback(reason, runtimeConfig)
    }

    fun recordEngineBridgeLinkageFailed(context: String, error: LinkageError, runtimeConfig: RuntimeConfig) {
        tripEngineBridgeFuse("$context:${error.javaClass.simpleName}", error, runtimeConfig)
    }

    fun recordHessFrameState(
        q: Int,
        r: Int,
        x: Int,
        queueDepth: Int,
        roomStarted: Boolean
    ) {
        recordHessFrameState(q, r, x, queueDepth, roomStarted, runtimeConfig())
    }

    fun recordHessFrameState(
        q: Int,
        r: Int,
        x: Int,
        queueDepth: Int,
        roomStarted: Boolean,
        runtimeConfig: RuntimeConfig
    ) {
        if (!runtimeConfig.metricsEnabled) {
            return
        }

        hessFrameObservedCount.incrementAndGet()
        hessLastQ.set(q.toLong())
        hessLastR.set(r.toLong())
        hessLastX.set(x.toLong())
        hessRoomStartedLast.set(if (roomStarted) 1L else 0L)
        recordHessQueueDepth(queueDepth, afterDrain = false)
        maybeLogSummary(runtimeConfig.source, System.nanoTime())
    }

    fun recordHessFrameBridgeFailed() {
        recordHessFrameBridgeFailed(runtimeConfig())
    }

    private fun recordHessFrameBridgeFailed(runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            hessFrameBridgeFailedCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    /**
     * Whether the frame/nest-step invariant guard in [com.corrodinggames.rts.gameFramework.j.HessLowLatencyBridge]
     * should repair `l.bx > ad.X`. Kept independent of [metricsEnabled] so the guard cannot be
     * silently disabled by the production metrics setting.
     */
    fun hessFrameInvariantGuardEnabled(): Boolean {
        return runtimeConfig().hessFrameInvariantGuardEnabled
    }

    fun hessFrameInvariantGuardEnabled(runtimeConfig: RuntimeConfig): Boolean {
        return runtimeConfig.hessFrameInvariantGuardEnabled
    }

    /**
     * Records a frame-invariant repair. Unlike [recordHessFrameBridgeFailed] this counter is
     * **unconditional** on purpose: production runs with `metricsEnabled=false`, and a silent
     * repair would hide the very signal we need to tell "root cause fixed" from "second path".
     */
    fun recordHessFrameInvariantRepaired(gameFrame: Int, nextStep: Int): Long {
        val repairs = hessFrameInvariantRepairedCount.incrementAndGet()
        hessFrameInvariantLastGameFrame.set(gameFrame.toLong())
        hessFrameInvariantLastNextStep.set(nextStep.toLong())

        val now = System.nanoTime()
        val last = hessFrameInvariantLastWarnNanos.get()
        if (last == 0L || now - last >= HESS_FRAME_INVARIANT_WARN_INTERVAL_NANOS) {
            if (hessFrameInvariantLastWarnNanos.compareAndSet(last, now)) {
                Log.warn(
                    "[SingleRoomLowLatency] Frame invariant repaired: gameFrame=$gameFrame > nextStep=$nextStep; " +
                        "set nextStep=${gameFrame + 1}. repairs=$repairs. " +
                        "This indicates l.bx advanced past ad.X; report it if it keeps growing."
                )
            }
        }
        return repairs
    }

    fun hessFrameInvariantRepairCount(): Long {
        return hessFrameInvariantRepairedCount.get()
    }

    fun hessFrameInvariantGuardFailedCount(): Long {
        return hessFrameInvariantGuardFailedCount.get()
    }

    /**
     * Records that the frame-invariant guard could not run (missing `ad.X` field, reflective
     * access failure, unexpected throwable). Unconditional and separate from the repair counter:
     * without it, "guard silently broken" would be indistinguishable from "no repair needed",
     * which would make a zero repair count a false clean bill of health.
     */
    fun recordHessFrameInvariantGuardFailed(reason: String, error: Throwable?) {
        val count = hessFrameInvariantGuardFailedCount.incrementAndGet()
        val now = System.nanoTime()
        val last = hessFrameInvariantGuardFailedLastWarnNanos.get()
        if (last == 0L || now - last >= HESS_FRAME_INVARIANT_WARN_INTERVAL_NANOS) {
            if (hessFrameInvariantGuardFailedLastWarnNanos.compareAndSet(last, now)) {
                val message =
                    "[SingleRoomLowLatency] Frame invariant guard could not run: reason=$reason " +
                        "(failures=$count). The l.bx <= ad.X guard is NOT active; a frame/nest-step " +
                        "crash can no longer be repaired automatically."
                if (error == null) {
                    Log.warn(message)
                } else {
                    Log.warn(message, error)
                }
            }
        }
    }

    /**
     * Records that an inbound engine-relay control packet (170/178) was dropped at the engine
     * enqueue boundary. Unconditional: this is a security signal, not a metrics-gated statistic.
     */
    fun recordInboundEngineRelayControlRejected(packetType: Any) {
        val count = inboundEngineRelayControlRejectedCount.incrementAndGet()
        val now = System.nanoTime()
        val last = inboundEngineRelayControlRejectedLastWarnNanos.get()
        if (last == 0L || now - last >= HESS_FRAME_INVARIANT_WARN_INTERVAL_NANOS) {
            if (inboundEngineRelayControlRejectedLastWarnNanos.compareAndSet(last, now)) {
                Log.warn(
                    "[StartMode] Dropped inbound engine relay-control packet type=$packetType " +
                        "at the engine enqueue boundary (rejected=$count). " +
                        "A game client should never send RELAY_BECOME_SERVER/PACKET_RECONNECT_TO."
                )
            }
        }
    }

    fun inboundEngineRelayControlRejectedCount(): Long {
        return inboundEngineRelayControlRejectedCount.get()
    }

    fun onGameCommandReceived(packet: Packet) {
        onGameCommandReceived(packet, runtimeConfig())
    }

    fun onGameCommandReceived(packet: Packet, runtimeConfig: RuntimeConfig) {
        if (!runtimeConfig.metricsEnabled || packet.type != PacketType.GAMECOMMAND_RECEIVE) {
            return
        }

        val now = System.nanoTime()
        if (gameCommandPendingMetricPacketCount.get() > MAX_PENDING_METRIC_PACKETS) {
            clearPendingGameCommandMetrics()
        }
        putPendingGameCommandMetric(packet, now)
        gameCommandReceivedCount.incrementAndGet()
        maybeLogSummary(runtimeConfig.source, now)
    }

    fun onGameCommandReceiveCommandFinished(packet: Packet, willForward: Boolean) {
        onGameCommandReceiveCommandFinished(packet, willForward, runtimeConfig())
    }

    fun onGameCommandReceiveCommandFinished(packet: Packet, willForward: Boolean, runtimeConfig: RuntimeConfig) {
        if (!runtimeConfig.metricsEnabled || packet.type != PacketType.GAMECOMMAND_RECEIVE) {
            return
        }

        if (!willForward) {
            removePendingGameCommandMetric(packet)
            gameCommandStoppedCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun transferPendingGameCommandMetric(from: Packet, to: Packet, runtimeConfig: RuntimeConfig) {
        if (!runtimeConfig.metricsEnabled ||
            from === to ||
            from.type != PacketType.GAMECOMMAND_RECEIVE ||
            to.type != PacketType.GAMECOMMAND_RECEIVE
        ) {
            return
        }

        val receivedAt = removePendingGameCommandMetric(from) ?: return
        putPendingGameCommandMetric(to, receivedAt)
    }

    fun recordGameCommandFastPath() {
        recordGameCommandFastPath(runtimeConfig())
    }

    fun recordGameCommandFastPath(runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandFastPathCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandFastPathRejected(reason: String, runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandFastPathRejectedCount.incrementAndGet()
            gameCommandFastPathRejectedLastReason = reason.take(80).ifBlank { "unknown" }
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandFastPathFallback(reason: String, runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandFastPathFallbackCount.incrementAndGet()
            gameCommandFastPathFallbackLastReason = reason.take(80).ifBlank { "unknown" }
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandNormalPath() {
        recordGameCommandNormalPath(runtimeConfig())
    }

    fun recordGameCommandNormalPath(runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandNormalPathCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandClassification(
        command: GameCommandOnePacket,
        classification: GameCommandClassification,
        runtimeConfig: RuntimeConfig
    ) {
        if (!runtimeConfig.metricsEnabled) {
            return
        }

        gameCommandClassifiedCount.incrementAndGet()
        gameCommandClassificationLastKind = classification.name
        gameCommandClassificationLastActionId = command.actionIdData.actionId.take(120).ifBlank { "none" }
        gameCommandClassificationLastSender = renderGameCommandSender(command)
        gameCommandClassificationLastSharedControlCheck = command.sharedControlCheck.toInt()
        when (classification) {
            GameCommandClassification.SYSTEM_ACTION -> {
                gameCommandSystemActionCount.incrementAndGet()
                gameCommandSystemActionLast.set(command.systemAction.toLong())
            }
            GameCommandClassification.MAP_PING -> gameCommandMapPingCount.incrementAndGet()
            GameCommandClassification.UNIT_OPERATION -> gameCommandUnitOperationCount.incrementAndGet()
            GameCommandClassification.FACTORY_ACTION -> gameCommandFactoryActionCount.incrementAndGet()
            GameCommandClassification.INTERNAL_ACTION -> gameCommandInternalActionCount.incrementAndGet()
            GameCommandClassification.UNKNOWN -> gameCommandUnknownActionCount.incrementAndGet()
        }
        maybeLogSummary(runtimeConfig.source, System.nanoTime())
    }

    fun recordGameCommandClassificationFailed(reason: String, runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandClassificationFailedCount.incrementAndGet()
            gameCommandClassificationLastFailure = reason.take(120).ifBlank { "unknown" }
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordEngineEnqueue(packet: Packet) {
        recordEngineEnqueue(packet, runtimeConfig())
    }

    fun recordEngineEnqueue(packet: Packet, runtimeConfig: RuntimeConfig) {
        if (!runtimeConfig.metricsEnabled || packet.type != PacketType.GAMECOMMAND_RECEIVE) {
            return
        }

        val now = System.nanoTime()
        gameCommandEngineQueuedCount.incrementAndGet()
        removePendingGameCommandMetric(packet)?.let { receivedAt ->
            val latencyNanos = (now - receivedAt).coerceAtLeast(0L)
            gameCommandQueueLatencySamples.incrementAndGet()
            gameCommandQueueLatencyNanos.addAndGet(latencyNanos)
            receiveToEngineLatencyWindow.record(latencyNanos)
        }
        maybeLogSummary(runtimeConfig.source, now)
    }

    fun recordGameCommandFrameQueued(packet: Packet) {
        recordGameCommandFrameQueued(packet, -1)
    }

    fun shouldRecordGameCommandFrameQueueDepth(packet: Packet): Boolean {
        return shouldRecordGameCommandFrameQueueDepth(packet, runtimeConfig())
    }

    fun shouldRecordGameCommandFrameQueueDepth(packet: Packet, runtimeConfig: RuntimeConfig): Boolean {
        return runtimeConfig.metricsEnabled && packet.type == PacketType.GAMECOMMAND_RECEIVE
    }

    fun recordGameCommandFrameQueued(packet: Packet, frameQueueDepth: Int) {
        recordGameCommandFrameQueued(packet, frameQueueDepth, runtimeConfig())
    }

    fun recordGameCommandFrameQueued(packet: Packet, frameQueueDepth: Int, runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled && packet.type == PacketType.GAMECOMMAND_RECEIVE) {
            gameCommandFrameQueuedCount.incrementAndGet()
            if (frameQueueDepth >= 0) {
                gameCommandFrameQueueDepthSamples.incrementAndGet()
                gameCommandFrameQueueDepthTotal.addAndGet(frameQueueDepth.toLong())
                updateMax(gameCommandFrameQueueDepthMax, frameQueueDepth.toLong())
            }
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandPreTickDrained(count: Int) {
        recordGameCommandPreTickDrained(count, -1)
    }

    fun recordGameCommandPreTickDrained(count: Int, remainingQueueDepth: Int) {
        recordGameCommandPreTickDrained(count, remainingQueueDepth, runtimeConfig())
    }

    fun recordGameCommandPreTickDrained(count: Int, remainingQueueDepth: Int, runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            recordHessQueueDepth(remainingQueueDepth, afterDrain = true)
            if (count > 0) {
                gameCommandPreTickDrainPassCount.incrementAndGet()
                gameCommandPreTickDrainedCount.addAndGet(count.toLong())
                updateMax(gameCommandPreTickDrainPassMax, count.toLong())
            }
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandPreTickDrainSkippedNotStarted(queueDepth: Int) {
        recordGameCommandPreTickDrainSkippedNotStarted(queueDepth, runtimeConfig())
    }

    fun recordGameCommandPreTickDrainSkippedNotStarted(queueDepth: Int, runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandPreTickDrainSkippedNotStartedCount.incrementAndGet()
            recordHessQueueDepth(queueDepth, afterDrain = true)
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandPreTickDrainBlockedByNonCommand(packetType: Int, queueDepth: Int) {
        recordGameCommandPreTickDrainBlockedByNonCommand(packetType, queueDepth, runtimeConfig())
    }

    fun recordGameCommandPreTickDrainBlockedByNonCommand(
        packetType: Int,
        queueDepth: Int,
        runtimeConfig: RuntimeConfig
    ) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandPreTickDrainBlockedByNonCommandCount.incrementAndGet()
            gameCommandPreTickDrainBlockedByNonCommandLastType.set(packetType.toLong())
            recordHessQueueDepth(queueDepth, afterDrain = true)
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandPreTickDrainFailed() {
        recordGameCommandPreTickDrainFailed(runtimeConfig())
    }

    fun recordGameCommandPreTickDrainFailed(runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandPreTickDrainFailedCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandInlineProcessed() {
        recordGameCommandInlineProcessed(runtimeConfig())
    }

    fun recordGameCommandInlineProcessed(runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandInlineProcessedCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordGameCommandInlineFailed() {
        recordGameCommandInlineFailed(runtimeConfig())
    }

    fun recordGameCommandInlineFailed(runtimeConfig: RuntimeConfig) {
        if (runtimeConfig.metricsEnabled) {
            gameCommandInlineFailedCount.incrementAndGet()
            maybeLogSummary(runtimeConfig.source, System.nanoTime())
        }
    }

    fun recordOutboundPacket(packet: Packet) {
        val packetType = packet.type.typeInt
        if (!isOutboundMetricPacketType(packetType)) {
            return
        }

        val runtimeConfig = runtimeConfig()
        if (!runtimeConfig.metricsEnabled) {
            return
        }

        when (packetType) {
            PacketType.TICK.typeInt -> outgoingTickCount.incrementAndGet()
            PacketType.SYNC.typeInt -> outgoingSyncCount.incrementAndGet()
            PacketType.SYNC_CHECK.typeInt,
            PacketType.SYNCCHECKSUM_STATUS.typeInt -> outgoingSyncCheckCount.incrementAndGet()
            PacketType.TEAM_LIST.typeInt -> outgoingTeamListCount.incrementAndGet()
        }
        maybeLogSummary(runtimeConfig.source, System.nanoTime())
    }

    fun resetMetrics(): List<String> {
        clearPendingGameCommandMetrics()

        gameCommandReceivedCount.set(0L)
        gameCommandFastPathCount.set(0L)
        gameCommandFastPathRejectedCount.set(0L)
        gameCommandFastPathRejectedLastReason = "none"
        gameCommandFastPathFallbackCount.set(0L)
        gameCommandFastPathFallbackLastReason = "none"
        gameCommandNormalPathCount.set(0L)
        gameCommandStoppedCount.set(0L)
        gameCommandClassifiedCount.set(0L)
        gameCommandSystemActionCount.set(0L)
        gameCommandSystemActionLast.set(0L)
        gameCommandMapPingCount.set(0L)
        gameCommandUnitOperationCount.set(0L)
        gameCommandFactoryActionCount.set(0L)
        gameCommandInternalActionCount.set(0L)
        gameCommandUnknownActionCount.set(0L)
        gameCommandClassificationFailedCount.set(0L)
        gameCommandClassificationLastKind = "none"
        gameCommandClassificationLastActionId = "none"
        gameCommandClassificationLastSender = "none"
        gameCommandClassificationLastSharedControlCheck = 0
        gameCommandClassificationLastFailure = "none"
        gameCommandEngineQueuedCount.set(0L)
        gameCommandFrameQueuedCount.set(0L)
        gameCommandInlineProcessedCount.set(0L)
        gameCommandInlineFailedCount.set(0L)
        gameCommandFrameQueueDepthSamples.set(0L)
        gameCommandFrameQueueDepthTotal.set(0L)
        gameCommandFrameQueueDepthMax.set(0L)
        gameCommandPreTickDrainedCount.set(0L)
        gameCommandPreTickDrainFailedCount.set(0L)
        gameCommandPreTickDrainPassCount.set(0L)
        gameCommandPreTickDrainPassMax.set(0L)
        gameCommandQueueLatencySamples.set(0L)
        gameCommandQueueLatencyNanos.set(0L)
        receiveToEngineLatencyWindow.clear()
        outgoingTickCount.set(0L)
        outgoingSyncCount.set(0L)
        outgoingSyncCheckCount.set(0L)
        outgoingTeamListCount.set(0L)
        hessLeadAppliedCount.set(0L)
        hessFrameObservedCount.set(0L)
        hessFrameBridgeFailedCount.set(0L)
        engineBridgeFuseAtNanos.set(0L)
        engineBridgeFuseReason = "none"
        hessFrameQueueDepthSamples.set(0L)
        hessFrameQueueDepthTotal.set(0L)
        hessFrameQueueDepthMax.set(0L)
        hessFrameQueueDepthLast.set(0L)
        hessFrameQueueDepthAfterDrainSamples.set(0L)
        hessFrameQueueDepthAfterDrainTotal.set(0L)
        hessFrameQueueDepthAfterDrainMax.set(0L)
        hessFrameQueueDepthAfterDrainLast.set(0L)
        gameCommandPreTickDrainSkippedNotStartedCount.set(0L)
        gameCommandPreTickDrainBlockedByNonCommandCount.set(0L)
        gameCommandPreTickDrainBlockedByNonCommandLastType.set(-1L)
        lastSummaryAtNanos.set(0L)

        return listOf(
            "singleRoomLowLatency.metricsReset=true",
            "singleRoomLowLatency.hessLeadLastValue=${hessLeadLastValue.get()}",
            "singleRoomLowLatency.hessLastQ=${hessLastQ.get()}",
            "singleRoomLowLatency.hessLastR=${hessLastR.get()}",
            "singleRoomLowLatency.hessLastX=${hessLastX.get()}",
            "singleRoomLowLatency.engineBridgeFuse=${engineBridgeFuseStatus()}"
        )
    }

    fun applyProfile(rawProfileName: String?): List<String> {
        val profileName = rawProfileName?.trim().orEmpty()
        if (profileName.isBlank()) {
            return applyProfileUsage("missingProfile")
        }

        return when (profileName.lowercase()) {
            "config", "file", "clear", "reset-config" -> clearRuntimeOverride()
            "off", "disable", "disabled" -> applyRuntimeOverride(
                name = "off",
                config = baseConfig().copy(enabled = false).normalized()
            )
            "production", "prod", "live", "metrics-off", "metrics_off" -> applyRuntimeOverride(
                name = "production",
                config = config().copy(metricsEnabled = false).normalized()
            )
            "test", "measure", "measurement", "diagnostic", "diagnostics", "metrics-on", "metrics_on" -> applyRuntimeOverride(
                name = "test",
                config = config().copy(metricsEnabled = true).normalized()
            )
            "lowmemory", "low-memory", "low_memory" -> applyRuntimeOverride(
                name = "lowMemory",
                config = lowMemoryProfileConfig()
            )
            else -> {
                val profile = SingleRoomLowLatencyProfiles.find(profileName) ?: return applyProfileUsage("unknownProfile=$profileName")
                applyRuntimeOverride(
                    name = profile.name,
                    config = profile.applyTo(baseConfig())
                )
            }
        }
    }

    fun buildProfiles(): List<String> {
        return SingleRoomLowLatencyProfiles.summaryLines() + listOf(
            "singleRoomLowLatency.profile.applyUsage: lowlatency apply <A|B|C|D|stable|observe|recommended|aggressive|rollback|lowMemory|production|test|off|config>",
            "singleRoomLowLatency.profile.production: lowlatency apply production keeps current low-latency parameters and disables metrics for lower hot-path overhead",
            "singleRoomLowLatency.profile.test: lowlatency apply test keeps current low-latency parameters and enables metrics for validation",
            "singleRoomLowLatency.profile.runtimeOnly: apply changes current process only; edit ServerConfig.json for persistent defaults",
            "singleRoomLowLatency.profile.startupOnly.nettyWorkerThreadsOverride: edit ServerConfig.json and restart server to rebuild Netty worker group",
            "singleRoomLowLatency.profile.productionTip: after stable verification, use lowlatency apply production or persist metricsEnabled=false"
        )
    }

    fun buildDiagnostics(): List<String> {
        val runtimeConfig = runtimeConfig()
        return SingleRoomLowLatencyReports.buildDiagnostics(
            runtimeConfig = runtimeConfig,
            overrideActive = runtimeOverrideConfig != null,
            overrideName = runtimeOverrideName,
            summaryConsoleEnabled = StartModeConsoleOutputSupport.lowLatencySummaryEnabled(),
            metrics = metricsSnapshot()
        )
    }

    fun buildDegradeAdvice(): List<String> {
        return SingleRoomLowLatencyReports.buildDegradeAdvice(
            runtimeConfig = runtimeConfig(),
            overrideActive = runtimeOverrideConfig != null,
            metrics = metricsSnapshot()
        )
    }

    fun buildTestSummary(): List<String> {
        return SingleRoomLowLatencyReports.buildTestSummary(
            runtimeConfig = runtimeConfig(),
            overrideActive = runtimeOverrideConfig != null,
            metrics = metricsSnapshot()
        )
    }

    fun buildFirstTestReadout(): List<String> {
        return SingleRoomLowLatencyReports.buildFirstTestReadout(
            runtimeConfig = runtimeConfig(),
            overrideActive = runtimeOverrideConfig != null,
            metrics = metricsSnapshot()
        )
    }

    fun buildExperienceSummary(): List<String> {
        return SingleRoomLowLatencyReports.buildExperienceSummary(
            runtimeConfig = runtimeConfig(),
            overrideActive = runtimeOverrideConfig != null,
            metrics = metricsSnapshot()
        )
    }

    private fun metricsSnapshot(): SingleRoomLowLatencyMetricsSnapshot {
        val latencySamples = gameCommandQueueLatencySamples.get()
        val latencyPercentiles = receiveToEngineLatencyWindow.snapshot()
        val gameCommandPreTickDrained = gameCommandPreTickDrainedCount.get()
        val gameCommandPreTickDrainPasses = gameCommandPreTickDrainPassCount.get()
        val blockedByNonCommandLastType = gameCommandPreTickDrainBlockedByNonCommandLastType.get()

        return SingleRoomLowLatencyMetricsSnapshot(
            hessLeadApplied = hessLeadAppliedCount.get(),
            hessLeadLastValue = hessLeadLastValue.get(),
            hessFrameObserved = hessFrameObservedCount.get(),
            hessFrameBridgeFailed = hessFrameBridgeFailedCount.get(),
            engineBridgeFused = isEngineBridgeFused(),
            engineBridgeFuseReason = engineBridgeFuseStatus(),
            hessLastQ = hessLastQ.get(),
            hessLastR = hessLastR.get(),
            hessLastX = hessLastX.get(),
            hessRoomStartedLast = hessRoomStartedLast.get() == 1L,
            hessQueueDepthAvg = average(hessFrameQueueDepthTotal.get(), hessFrameQueueDepthSamples.get()),
            hessQueueDepthMax = hessFrameQueueDepthMax.get(),
            hessQueueDepthLast = hessFrameQueueDepthLast.get(),
            hessQueueDepthAfterDrainAvg = average(hessFrameQueueDepthAfterDrainTotal.get(), hessFrameQueueDepthAfterDrainSamples.get()),
            hessQueueDepthAfterDrainMax = hessFrameQueueDepthAfterDrainMax.get(),
            hessQueueDepthAfterDrainLast = hessFrameQueueDepthAfterDrainLast.get(),
            gameCommandReceived = gameCommandReceivedCount.get(),
            gameCommandPendingMetricPackets = gameCommandPendingMetricPacketCount.get(),
            gameCommandFastPath = gameCommandFastPathCount.get(),
            gameCommandFastPathRejected = gameCommandFastPathRejectedCount.get(),
            gameCommandFastPathRejectedLastReason = gameCommandFastPathRejectedLastReason,
            gameCommandFastPathFallback = gameCommandFastPathFallbackCount.get(),
            gameCommandFastPathFallbackLastReason = gameCommandFastPathFallbackLastReason,
            gameCommandNormalPath = gameCommandNormalPathCount.get(),
            gameCommandStopped = gameCommandStoppedCount.get(),
            gameCommandClassified = gameCommandClassifiedCount.get(),
            gameCommandSystemAction = gameCommandSystemActionCount.get(),
            gameCommandSystemActionLast = gameCommandSystemActionLast.get(),
            gameCommandMapPing = gameCommandMapPingCount.get(),
            gameCommandUnitOperation = gameCommandUnitOperationCount.get(),
            gameCommandFactoryAction = gameCommandFactoryActionCount.get(),
            gameCommandInternalAction = gameCommandInternalActionCount.get(),
            gameCommandUnknownAction = gameCommandUnknownActionCount.get(),
            gameCommandClassificationFailed = gameCommandClassificationFailedCount.get(),
            gameCommandClassificationLastKind = gameCommandClassificationLastKind,
            gameCommandClassificationLastActionId = gameCommandClassificationLastActionId,
            gameCommandClassificationLastSender = gameCommandClassificationLastSender,
            gameCommandClassificationLastSharedControlCheck = gameCommandClassificationLastSharedControlCheck,
            gameCommandClassificationLastFailure = gameCommandClassificationLastFailure,
            gameCommandEngineQueued = gameCommandEngineQueuedCount.get(),
            gameCommandFrameQueued = gameCommandFrameQueuedCount.get(),
            gameCommandFrameQueueDepthAvg = average(gameCommandFrameQueueDepthTotal.get(), gameCommandFrameQueueDepthSamples.get()),
            gameCommandFrameQueueDepthMax = gameCommandFrameQueueDepthMax.get(),
            gameCommandPreTickDrained = gameCommandPreTickDrained,
            gameCommandPreTickDrainFailed = gameCommandPreTickDrainFailedCount.get(),
            gameCommandPreTickDrainPasses = gameCommandPreTickDrainPasses,
            gameCommandPreTickDrainPassAvg = average(gameCommandPreTickDrained, gameCommandPreTickDrainPasses),
            gameCommandPreTickDrainPassMax = gameCommandPreTickDrainPassMax.get(),
            gameCommandPreTickDrainSkippedNotStarted = gameCommandPreTickDrainSkippedNotStartedCount.get(),
            gameCommandPreTickDrainBlockedByNonCommand = gameCommandPreTickDrainBlockedByNonCommandCount.get(),
            gameCommandPreTickDrainBlockedByNonCommandLastType = blockedByNonCommandLastType,
            gameCommandPreTickDrainBlockedByNonCommandLastName = packetTypeName(blockedByNonCommandLastType.toInt()),
            gameCommandInlineProcessed = gameCommandInlineProcessedCount.get(),
            gameCommandInlineFailed = gameCommandInlineFailedCount.get(),
            receiveToEngineAvgMicros = averageMicros(gameCommandQueueLatencyNanos.get(), latencySamples),
            receiveToEngineP50Micros = latencyPercentiles.p50Nanos / 1_000L,
            receiveToEngineP95Micros = latencyPercentiles.p95Nanos / 1_000L,
            receiveToEngineMaxMicros = latencyPercentiles.maxNanos / 1_000L,
            receiveToEnginePercentileSamples = latencyPercentiles.sampleCount.toLong(),
            receiveToEngineSamples = latencySamples,
            outgoingTick = outgoingTickCount.get(),
            outgoingSync = outgoingSyncCount.get(),
            outgoingSyncCheck = outgoingSyncCheckCount.get(),
            outgoingTeamList = outgoingTeamListCount.get()
        )
    }

    private fun applyRuntimeOverride(name: String, config: BeanSingleRoomLowLatencyConfig): List<String> {
        runtimeOverrideConfig = config
        runtimeOverrideName = name
        cachedRuntimeConfig = null
        resetMetrics()

        return listOf(
            "singleRoomLowLatency.apply=true",
            "singleRoomLowLatency.applyProfile=$name",
            "singleRoomLowLatency.runtimeOverride=true",
            "singleRoomLowLatency.persisted=false",
            "singleRoomLowLatency.rollbackHint=lowlatency apply config"
        ) + SingleRoomLowLatencyReports.buildCurrentProfileDiagnostics(runtimeConfig(config))
    }

    private fun clearRuntimeOverride(): List<String> {
        runtimeOverrideConfig = null
        runtimeOverrideName = null
        cachedRuntimeConfig = null
        resetMetrics()

        return listOf(
            "singleRoomLowLatency.apply=true",
            "singleRoomLowLatency.applyProfile=config",
            "singleRoomLowLatency.runtimeOverride=false",
            "singleRoomLowLatency.persisted=true",
            "singleRoomLowLatency.note=using ServerConfig.json values"
        ) + SingleRoomLowLatencyReports.buildCurrentProfileDiagnostics(runtimeConfig())
    }

    private fun applyProfileUsage(reason: String): List<String> {
        return listOf(
            "singleRoomLowLatency.apply=false",
            "singleRoomLowLatency.applyReason=$reason",
            "Usage: lowlatency apply <A|B|C|D|stable|observe|recommended|aggressive|rollback|lowMemory|production|test|off|config>"
        ) + buildProfiles()
    }

    private fun lowMemoryProfileConfig(): BeanSingleRoomLowLatencyConfig {
        return baseConfig().copy(
            enabled = true,
            metricsEnabled = false,
            fastGameCommand = true,
            preTickGameCommandDrain = true,
            preTickGameCommandDrainRequiresStartedRoom = true,
            preTickGameCommandDrainLimit = 128,
            inlineGameCommandProcessing = false,
            inlineGameCommandRequiresStartedRoom = true,
            skipPlayerOperationEvents = true,
            skipPingActionIntercept = true,
            disableTurnStoneIntoGoldOnFastPath = true,
            serverInjectedCommandLeadTicks = 10,
            hessOutboundCommandLeadTicks = 10,
            headlessFpsOverride = 60,
            nettyWorkerThreadsOverride = 1,
            directEventLoopWrites = true
        ).normalized()
    }

    private fun config(): BeanSingleRoomLowLatencyConfig {
        runtimeOverrideConfig?.let { return it }
        return baseConfig()
    }

    private fun baseConfig(): BeanSingleRoomLowLatencyConfig {
        return try {
            Data.configServer.singleRoomLowLatency
        } catch (_: Exception) {
            defaultConfig
        }
    }

    private fun runtimeConfig(config: BeanSingleRoomLowLatencyConfig): RuntimeConfig {
        val enabled = config.enabled
        return RuntimeConfig(
            source = config,
            enabled = enabled,
            metricsEnabled = enabled && config.metricsEnabled,
            fastGameCommand = config.fastGameCommand,
            preTickGameCommandDrain = config.preTickGameCommandDrain,
            preTickGameCommandDrainRequiresStartedRoom = config.preTickGameCommandDrainRequiresStartedRoom,
            preTickGameCommandDrainLimit = config.preTickGameCommandDrainLimit.coerceIn(1, 4_096),
            inlineGameCommandProcessing = config.inlineGameCommandProcessing,
            inlineGameCommandRequiresStartedRoom = config.inlineGameCommandRequiresStartedRoom,
            skipPlayerOperationEvents = config.skipPlayerOperationEvents,
            skipPingActionIntercept = config.skipPingActionIntercept,
            disableTurnStoneIntoGoldOnFastPath = config.disableTurnStoneIntoGoldOnFastPath,
            serverInjectedCommandLeadTicks = config.serverInjectedCommandLeadTicks.coerceIn(0, 60),
            hessOutboundCommandLeadTicks = if (enabled) {
                config.hessOutboundCommandLeadTicks.coerceIn(1, 60)
            } else {
                10
            },
            // Deliberately NOT gated on `enabled`: the frame/nest-step invariant violation is an
            // engine-level defect (ad.s() zeroes ad.X but never l.bx) and reproduces independently
            // of the low-latency profile, so the guard stays on even when the profile is disabled.
            hessFrameInvariantGuardEnabled = config.hessFrameInvariantGuardEnabled,
            headlessFpsOverride = config.headlessFpsOverride.coerceIn(0, 240),
            nettyWorkerThreadsOverride = if (enabled) {
                config.nettyWorkerThreadsOverride.coerceIn(0, 8)
            } else {
                0
            },
            directEventLoopWrites = enabled && config.directEventLoopWrites
        )
    }

    private fun packetMetricKey(packet: Packet): Int {
        // Packet.equals compares only with packet types, so identity is the safest lightweight metric key.
        return System.identityHashCode(packet)
    }

    private fun putPendingGameCommandMetric(packet: Packet, now: Long) {
        if (receivedGameCommandAtNanos.put(packetMetricKey(packet), now) == null) {
            gameCommandPendingMetricPacketCount.incrementAndGet()
        }
    }

    private fun removePendingGameCommandMetric(packet: Packet): Long? {
        val receivedAt = receivedGameCommandAtNanos.remove(packetMetricKey(packet))
        if (receivedAt != null) {
            gameCommandPendingMetricPacketCount.updateAndGet { current -> (current - 1L).coerceAtLeast(0L) }
        }
        return receivedAt
    }

    private fun clearPendingGameCommandMetrics() {
        receivedGameCommandAtNanos.clear()
        gameCommandPendingMetricPacketCount.set(0L)
    }

    private fun renderGameCommandSender(command: GameCommandOnePacket): String {
        val sender = command.sendBy ?: return "unknown"
        return runCatching {
            "pos=${sender.position},index=${sender.index},team=${sender.team}," +
                "spectator=${StartModeLobbySupport.isSpectatorTeam(sender.team)}," +
                "ai=${sender.isAi},conn=${sender.con != null}"
        }.getOrDefault("unavailable")
    }

    private fun isOutboundMetricPacketType(packetType: Int): Boolean {
        return packetType == PacketType.TICK.typeInt ||
            packetType == PacketType.SYNC.typeInt ||
            packetType == PacketType.SYNC_CHECK.typeInt ||
            packetType == PacketType.SYNCCHECKSUM_STATUS.typeInt ||
            packetType == PacketType.TEAM_LIST.typeInt
    }

    private fun maybeLogSummary(config: BeanSingleRoomLowLatencyConfig, now: Long) {
        if (!StartModeConsoleOutputSupport.lowLatencySummaryHotPathEnabled()) {
            return
        }

        val intervalNanos = config.summaryIntervalSeconds
            .coerceIn(1, 3_600)
            .toLong() * NANOS_PER_SECOND
        val last = lastSummaryAtNanos.get()
        if (last == 0L) {
            lastSummaryAtNanos.compareAndSet(0L, now)
            return
        }
        if (now - last < intervalNanos) {
            return
        }
        if (!lastSummaryAtNanos.compareAndSet(last, now)) {
            return
        }
        val summaryMessage = "[SingleRoomLowLatency] enabled=${config.enabled}, fastGameCommand=${config.fastGameCommand}, " +
                "preTickDrain=${config.preTickGameCommandDrain}, inlineGameCommand=${config.inlineGameCommandProcessing}, " +
                "leadTicks=${config.serverInjectedCommandLeadTicks.coerceIn(0, 60)}, " +
                "hessLead=${if (config.enabled) config.hessOutboundCommandLeadTicks.coerceIn(1, 60) else 10}, " +
                "headlessFps=${if (config.enabled) config.headlessFpsOverride.coerceIn(0, 240) else 0}, " +
                "nettyWorkers=${if (config.enabled) config.nettyWorkerThreadsOverride.coerceIn(0, 8) else 0}, " +
                "directEventLoopWrites=${config.enabled && config.directEventLoopWrites}, " +
                "hessLeadApplied=${hessLeadAppliedCount.get()}, hessQ=${hessLastQ.get()}, hessR=${hessLastR.get()}, " +
                "hessX=${hessLastX.get()}, hessQueueDepth=${hessFrameQueueDepthLast.get()}, " +
                "hessQueueDepthMax=${hessFrameQueueDepthMax.get()}, bridgeFailed=${hessFrameBridgeFailedCount.get()}, " +
                "frameInvariantRepairs=${hessFrameInvariantRepairedCount.get()}, " +
                "frameInvariantGuardFailures=${hessFrameInvariantGuardFailedCount.get()}, " +
                "frameInvariantLastGameFrame=${hessFrameInvariantLastGameFrame.get()}, " +
                "frameInvariantLastNextStep=${hessFrameInvariantLastNextStep.get()}, " +
                "inboundRelayControlRejected=${inboundEngineRelayControlRejectedCount.get()}, " +
                "engineBridgeFuse=${engineBridgeFuseStatus()}, " +
                "commandIn=${gameCommandReceivedCount.get()}, " +
                "fast=${gameCommandFastPathCount.get()}, normal=${gameCommandNormalPathCount.get()}, " +
                "fastRejected=${gameCommandFastPathRejectedCount.get()}, fastRejectedLast=$gameCommandFastPathRejectedLastReason, " +
                "fastFallback=${gameCommandFastPathFallbackCount.get()}, fastFallbackLast=$gameCommandFastPathFallbackLastReason, " +
                "stopped=${gameCommandStoppedCount.get()}, queued=${gameCommandEngineQueuedCount.get()}, " +
                "classified=${gameCommandClassifiedCount.get()}, systemAction=${gameCommandSystemActionCount.get()}, " +
                "systemActionLast=${gameCommandSystemActionLast.get()}, mapPing=${gameCommandMapPingCount.get()}, " +
                "unitOp=${gameCommandUnitOperationCount.get()}, factory=${gameCommandFactoryActionCount.get()}, " +
                "internal=${gameCommandInternalActionCount.get()}, unknown=${gameCommandUnknownActionCount.get()}, " +
                "classifyFailed=${gameCommandClassificationFailedCount.get()}, lastKind=$gameCommandClassificationLastKind, " +
                "lastSender=$gameCommandClassificationLastSender, sharedControlCheck=$gameCommandClassificationLastSharedControlCheck, " +
                "frameQueued=${gameCommandFrameQueuedCount.get()}, inline=${gameCommandInlineProcessedCount.get()}, " +
                "inlineFailed=${gameCommandInlineFailedCount.get()}, " +
                "preTickDrained=${gameCommandPreTickDrainedCount.get()}, " +
                "preTickDrainPct=${percentage(gameCommandPreTickDrainedCount.get(), gameCommandFrameQueuedCount.get())}, " +
                "preTickDrainFailed=${gameCommandPreTickDrainFailedCount.get()}, " +
                "preTickDrainPassMax=${gameCommandPreTickDrainPassMax.get()}, " +
                "preTickDrainSkippedNotStarted=${gameCommandPreTickDrainSkippedNotStartedCount.get()}, " +
                "preTickDrainBlockedByNonCommand=${gameCommandPreTickDrainBlockedByNonCommandCount.get()}, " +
                "preTickDrainBlockedLast=${packetTypeName(gameCommandPreTickDrainBlockedByNonCommandLastType.get().toInt())}, " +
                "frameQueueDepthAvg=${average(gameCommandFrameQueueDepthTotal.get(), gameCommandFrameQueueDepthSamples.get())}, " +
                "frameQueueDepthMax=${gameCommandFrameQueueDepthMax.get()}, " +
                "receiveToEngineAvgUs=${averageMicros(gameCommandQueueLatencyNanos.get(), gameCommandQueueLatencySamples.get())}, " +
                "outTick=${outgoingTickCount.get()}, outSync=${outgoingSyncCount.get()}, " +
                "outSyncCheck=${outgoingSyncCheckCount.get()}, outTeamList=${outgoingTeamListCount.get()}"
        StartModeConsoleOutputSupport.printThrottled(
            StartModeConsoleOutputSupport.Category.LOW_LATENCY_SUMMARY,
            "single_room_low_latency_summary",
            summaryMessage,
            minIntervalMillis = 30_000L
        )
    }

    private fun isEngineBridgeFused(): Boolean {
        return engineBridgeFuseAtNanos.get() > 0L
    }

    private fun engineBridgeFuseStatus(): String {
        return if (isEngineBridgeFused()) engineBridgeFuseReason else "none"
    }

    private fun tripEngineBridgeFuse(reason: String, error: Throwable, runtimeConfig: RuntimeConfig) {
        if (engineBridgeFuseAtNanos.compareAndSet(0L, System.nanoTime())) {
            val detail = "${error.javaClass.simpleName}:${error.message.orEmpty()}".take(180)
            engineBridgeFuseReason = listOf(reason, detail)
                .filter { it.isNotBlank() }
                .joinToString("|")
            Log.warn("[SingleRoomLowLatency] Engine bridge fused; falling back to original command path: $engineBridgeFuseReason", error)
        }
        recordHessFrameBridgeFailed(runtimeConfig)
    }

    private fun averageMicros(totalNanos: Long, count: Long): Long {
        if (count <= 0L) {
            return 0L
        }
        return totalNanos / count / 1_000L
    }

    private fun average(total: Long, count: Long): Long {
        if (count <= 0L) {
            return 0L
        }
        return total / count
    }

    private fun percentage(value: Long, total: Long): Long {
        if (total <= 0L) {
            return 0L
        }
        return (value * 100L) / total
    }

    private fun recordHessQueueDepth(queueDepth: Int, afterDrain: Boolean) {
        if (queueDepth < 0) {
            return
        }

        if (afterDrain) {
            hessFrameQueueDepthAfterDrainSamples.incrementAndGet()
            hessFrameQueueDepthAfterDrainTotal.addAndGet(queueDepth.toLong())
            hessFrameQueueDepthAfterDrainLast.set(queueDepth.toLong())
            updateMax(hessFrameQueueDepthAfterDrainMax, queueDepth.toLong())
        } else {
            hessFrameQueueDepthSamples.incrementAndGet()
            hessFrameQueueDepthTotal.addAndGet(queueDepth.toLong())
            hessFrameQueueDepthLast.set(queueDepth.toLong())
            updateMax(hessFrameQueueDepthMax, queueDepth.toLong())
        }
    }

    private fun hessQField(netEngine: Any): Field? {
        val engineClass = netEngine.javaClass
        hessQFieldCache[engineClass]?.let { return it }
        if (hessQFieldMissingClasses.contains(engineClass)) {
            return null
        }

        var currentClass: Class<*>? = engineClass
        while (currentClass != null) {
            val searchClass = currentClass
            try {
                return searchClass.getDeclaredField("Q").also { field ->
                    field.isAccessible = true
                    hessQFieldCache[engineClass] = field
                }
            } catch (_: NoSuchFieldException) {
                currentClass = searchClass.superclass
            } catch (_: SecurityException) {
                hessQFieldMissingClasses.add(engineClass)
                return null
            } catch (_: LinkageError) {
                hessQFieldMissingClasses.add(engineClass)
                return null
            }
        }

        hessQFieldMissingClasses.add(engineClass)
        return null
    }

    private fun packetTypeName(packetType: Int): String {
        if (packetType < 0) {
            return "none"
        }
        return PacketType.entries.firstOrNull { it.typeInt == packetType }?.nameEnum ?: "raw:$packetType"
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current) {
                return
            }
            if (target.compareAndSet(current, value)) {
                return
            }
        }
    }
}
