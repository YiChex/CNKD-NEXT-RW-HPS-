/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.core.RuntimeModeSupport
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencyProfiles
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport

internal object StartModeFirstTestChecklistSupport {
    fun buildChecklist(): List<String> {
        val warnings = mutableListOf<String>()
        val tips = mutableListOf<String>()
        val lowLatencyConfig = SingleRoomLowLatencySupport.runtimeConfig()
        val lowLatencyProfile = SingleRoomLowLatencyProfiles.currentName(lowLatencyConfig)
        val joinTipsEnabled = Data.configStartMode.enableJoinReadinessTips
        val lowLatencyMode = if (lowLatencyConfig.metricsEnabled) "test" else "production"
        val moduleReady = HeadlessModuleManage.initHPS()
        val mapLockSummary = if (Data.configStartMode.enableMapLock) {
            "on:${Data.configStartMode.lockMap.ifBlank { "<blank>" }}"
        } else {
            "off"
        }

        if (!RuntimeModeSupport.isPureStartMode()) {
            warnings += "not pure Start mode; use this checklist only for the standalone single-room server"
        }
        if (!moduleReady) {
            warnings += "headless module is not ready; start the room before real-player validation"
        }
        if (!lowLatencyConfig.enabled) {
            warnings += "singleRoomLowLatency.enabled=false; run lowlatency apply recommended before latency validation"
        }
        if (!lowLatencyConfig.metricsEnabled) {
            warnings += "metrics mode is production; run lowlatency apply test before collecting first-round readout"
        }
        if (lowLatencyProfile == "custom") {
            warnings += "current low-latency profile is custom; compare with lowlatency profiles before first public test"
        }
        if (lowLatencyConfig.nettyWorkerThreadsOverride == 0 &&
            lowLatencyConfig.serverInjectedCommandLeadTicks <= 8 &&
            lowLatencyConfig.hessOutboundCommandLeadTicks <= 8
        ) {
            warnings += "nettyWorkerThreadsOverride=0 keeps original worker selection for a low-lead profile; restart after changing it"
        }
        if (Data.configStartMode.enableMapLock && Data.configStartMode.lockMap.isBlank()) {
            warnings += "map lock is enabled but lockMap is blank"
        }
        if (Data.configServer.maxPlayer > 100) {
            warnings += "maxPlayer is above the current single-room default 100; verify CPU/network capacity before public testing"
        }
        if (Data.configServer.maxUnit > 1000) {
            warnings += "maxUnit is above the current single-room default 1000; expect heavier frame and memory pressure"
        }

        if (!joinTipsEnabled) {
            tips += "join readiness tips are disabled; players will not see the one-time low-latency room hint"
        }
        if (Data.config.console.showLowLatencySummary) {
            tips += "console.showLowLatencySummary=true can make background logs noisy; keep it off for normal live play"
        }
        if (StartModeMapLockSupport.isEnabled()) {
            tips += "map lock is enabled; verify the selected map matches the intended first test map"
        }
        if (lowLatencyConfig.metricsEnabled) {
            tips += "test mode is active; after stable validation use lowlatency apply production for long-running service"
        }

        val lines = mutableListOf<String>()
        lines += "========== STARTMODE PRETEST =========="
        lines += "pretest.status=${if (warnings.isEmpty()) "ready" else "warn"}"
        lines += "pretest.scope=single-room-start-mode"
        lines += "pretest.pureStartMode=${RuntimeModeSupport.isPureStartMode()}"
        lines += "pretest.moduleReady=$moduleReady"
        lines += "pretest.port=${Data.config.port}"
        lines += "pretest.serverName=${Data.config.serverName}"
        lines += "pretest.chatSenderName=${Data.config.chatSenderName}"
        lines += "pretest.maxPlayer=${Data.configServer.maxPlayer}"
        lines += "pretest.maxUnit=${Data.configServer.maxUnit}"
        lines += "pretest.mapLock=$mapLockSummary"
        lines += "pretest.lowLatency.enabled=${lowLatencyConfig.enabled}"
        lines += "pretest.lowLatency.profile=$lowLatencyProfile"
        lines += "pretest.lowLatency.mode=$lowLatencyMode"
        lines += "pretest.lowLatency.lead=${lowLatencyConfig.serverInjectedCommandLeadTicks}/${lowLatencyConfig.hessOutboundCommandLeadTicks}"
        lines += "pretest.lowLatency.fps=${lowLatencyConfig.headlessFpsOverride}"
        lines += "pretest.lowLatency.nettyWorkers=${lowLatencyConfig.nettyWorkerThreadsOverride}"
        lines += "pretest.lowLatency.directEventLoopWrites=${lowLatencyConfig.directEventLoopWrites}"
        lines += "pretest.joinTips.enabled=$joinTipsEnabled"
        lines += "pretest.joinTips.lowLatencyHint=${joinTipsEnabled && lowLatencyConfig.enabled}"
        lines += "pretest.console.showLowLatencySummary=${Data.config.console.showLowLatencySummary}"
        lines += "pretest.sequence.1=startmode bootcheck"
        lines += "pretest.sequence.2=lowlatency apply test"
        lines += "pretest.sequence.3=lowlatency reset"
        lines += "pretest.sequence.4=play one real round or at least 60 seconds of active commands"
        lines += "pretest.sequence.5=lowlatency readout"
        lines += "pretest.sequence.6=lowlatency summary"
        lines += "pretest.sequence.7=lowlatency advise"

        warnings.forEachIndexed { index, warning ->
            lines += "pretest.warning.${index + 1}=$warning"
        }
        tips.forEachIndexed { index, tip ->
            lines += "pretest.tip.${index + 1}=$tip"
        }
        lines += "commands.next=startmode bootcheck | lowlatency apply test | lowlatency reset"
        return lines
    }
}
