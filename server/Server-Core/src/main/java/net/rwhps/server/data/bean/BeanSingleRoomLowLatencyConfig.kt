/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

data class BeanSingleRoomLowLatencyConfig(
    val enabled: Boolean = true,
    val metricsEnabled: Boolean = true,
    val summaryIntervalSeconds: Int = 10,
    val fastGameCommand: Boolean = true,
    val preTickGameCommandDrain: Boolean = true,
    val preTickGameCommandDrainRequiresStartedRoom: Boolean = true,
    val preTickGameCommandDrainLimit: Int = 256,
    val inlineGameCommandProcessing: Boolean = false,
    val inlineGameCommandRequiresStartedRoom: Boolean = true,
    val skipPlayerOperationEvents: Boolean = true,
    val skipPingActionIntercept: Boolean = true,
    val disableTurnStoneIntoGoldOnFastPath: Boolean = true,
    val serverInjectedCommandLeadTicks: Int = 10,
    val hessOutboundCommandLeadTicks: Int = 10,
    val hessFrameInvariantGuardEnabled: Boolean = true,
    val headlessFpsOverride: Int = 0,
    val nettyWorkerThreadsOverride: Int = 0,
    val directEventLoopWrites: Boolean = false
) {
    fun normalized(): BeanSingleRoomLowLatencyConfig {
        return copy(
            summaryIntervalSeconds = summaryIntervalSeconds.coerceIn(1, 3_600),
            preTickGameCommandDrainLimit = preTickGameCommandDrainLimit.coerceIn(1, 4_096),
            serverInjectedCommandLeadTicks = serverInjectedCommandLeadTicks.coerceIn(0, 60),
            hessOutboundCommandLeadTicks = hessOutboundCommandLeadTicks.coerceIn(1, 60),
            headlessFpsOverride = headlessFpsOverride.coerceIn(0, 240),
            nettyWorkerThreadsOverride = nettyWorkerThreadsOverride.coerceIn(0, 8)
        )
    }
}
