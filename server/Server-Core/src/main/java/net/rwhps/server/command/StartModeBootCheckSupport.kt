/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.core.RuntimeModeSupport
import net.rwhps.server.data.global.Data
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencyProfiles
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.plugin.internal.headless.support.StartModePlayerExperienceSupport

internal object StartModeBootCheckSupport {
    fun buildSummary(): List<String> {
        val warnings = mutableListOf<String>()
        val tips = mutableListOf<String>()
        val lowLatencyConfig = SingleRoomLowLatencySupport.runtimeConfig()
        val lowLatencyProfile = SingleRoomLowLatencyProfiles.currentName(lowLatencyConfig)
        val autoStart = StartModeAutoStartSupport.buildAutoStartDiagnostics()
        val gameOver = diagnosticsMap(StartModeGameOverReturnSupport.buildDiagnostics())
        val reconnect = diagnosticsMap(StartModePlayerExperienceSupport.buildDiagnostics())
        val mapLockSummary = if (Data.configStartMode.enableMapLock) {
            "on:${Data.configStartMode.lockMap.ifBlank { "<blank>" }}"
        } else {
            "off"
        }
        val metricsMode = if (lowLatencyConfig.metricsEnabled) "test" else "production"

        if (!RuntimeModeSupport.isPureStartMode()) {
            warnings += "defStartCommand is not pure Start mode; single-room boot defaults may not fully apply"
        }
        if (Data.config.port !in 1..65535) {
            warnings += "common.port is outside 1-65535: ${Data.config.port}"
        }
        if (Data.configServer.maxPlayer < 1) {
            warnings += "gameplay.maxPlayer is below 1: ${Data.configServer.maxPlayer}"
        }
        if (Data.configServer.maxUnit > 1000) {
            warnings += "gameplay.maxUnit is above the current single-room default 1000: ${Data.configServer.maxUnit}"
        }
        if (Data.configStartMode.enableMapLock && Data.configStartMode.lockMap.isBlank()) {
            warnings += "map lock is enabled but lockMap is blank"
        }
        if (!lowLatencyConfig.enabled) {
            warnings += "singleRoomLowLatency.enabled=false; real-game low-latency path is disabled"
        }
        if (lowLatencyProfile == "custom") {
            warnings += "single-room low-latency profile is custom; compare it with lowlatency profiles before pressure testing"
        }
        if (lowLatencyConfig.nettyWorkerThreadsOverride == 0 &&
            lowLatencyConfig.serverInjectedCommandLeadTicks <= 8 &&
            lowLatencyConfig.hessOutboundCommandLeadTicks <= 8
        ) {
            warnings += "nettyWorkerThreadsOverride=0 keeps original worker selection for a low-lead profile"
        }
        if (lowLatencyConfig.metricsEnabled) {
            tips += "lowLatencyMode=test is good for first validation; after stable verification use lowlatency apply production"
        }
        tips += "nettyWorkerThreadsOverride is startup-only; edit ServerConfig.json and restart after changing it"

        val lines = mutableListOf<String>()
        lines += "========== STARTMODE BOOTCHECK =========="
        lines += "boot.status=${if (warnings.isEmpty()) "ok" else "warn"}"
        lines += "boot.pureStartMode=${RuntimeModeSupport.isPureStartMode()}"
        lines += "boot.defStartCommand=${Data.config.defStartCommand}"
        lines += "boot.port=${Data.config.port}"
        lines += "boot.serverName=${Data.config.serverName}"
        lines += "boot.chatSenderName=${Data.config.chatSenderName}"
        lines += "boot.maxPlayer=${Data.configServer.maxPlayer}"
        lines += "boot.maxUnit=${Data.configServer.maxUnit}"
        lines += "boot.mapLock=$mapLockSummary"
        lines += "boot.lowLatency.enabled=${lowLatencyConfig.enabled}"
        lines += "boot.lowLatency.profile=$lowLatencyProfile"
        lines += "boot.lowLatency.mode=$metricsMode"
        lines += "boot.lowLatency.lead=${lowLatencyConfig.serverInjectedCommandLeadTicks}/${lowLatencyConfig.hessOutboundCommandLeadTicks}"
        lines += "boot.lowLatency.fps=${lowLatencyConfig.headlessFpsOverride}"
        lines += "boot.lowLatency.nettyWorkers=${lowLatencyConfig.nettyWorkerThreadsOverride}"
        lines += "boot.lowLatency.nettyWorkersStartupOnly=true"
        lines += "boot.lowLatency.directEventLoopWrites=${lowLatencyConfig.directEventLoopWrites}"
        lines += "boot.console.verbosity=${Data.config.console.verbosity}"
        lines += "boot.console.showLowLatencySummary=${Data.config.console.showLowLatencySummary}"
        lines += "boot.flow.moduleReady=${autoStart["moduleReady"]}"
        lines += "boot.flow.roomStarted=${autoStart["roomStarted"]}"
        lines += "boot.flow.mapEngineReady=${autoStart["mapEngineReady"] ?: "unknown"}"
        lines += "boot.flow.autoStart=${autoStartState(autoStart)}"
        lines += "boot.flow.autoStartBlockedReason=${autoStart["blockedReason"] ?: "none"}"
        lines += "boot.flow.gameOverState=${gameOver["gameOver.state"] ?: "unknown"}"
        lines += "boot.flow.noOnlineHumanGraceActive=${gameOver["flow.noOnlineHumanGraceActive"] ?: "unknown"}"
        lines += "boot.flow.reconnectState=${reconnect["reconnect.state"] ?: "unknown"}"
        lines += "boot.flow.reconnectLastEvent=${reconnect["reconnect.lastEvent"] ?: "unknown"}"

        warnings.forEachIndexed { index, warning ->
            lines += "boot.warning.${index + 1}=$warning"
        }
        tips.forEachIndexed { index, tip ->
            lines += "boot.tip.${index + 1}=$tip"
        }
        lines += "commands.next=startmode bootcheck | startmode diagnose | lowlatency summary | config check"
        return lines
    }

    private fun autoStartState(autoStart: Map<String, Any?>): String {
        val enabled = autoStart["autoStartEnabled"] == true
        val canStartNow = autoStart["canAutoStartNow"] == true
        return when {
            !enabled -> "disabled"
            canStartNow -> "ready"
            else -> "blocked"
        }
    }

    private fun diagnosticsMap(lines: List<String>): Map<String, String> {
        return lines.mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) {
                null
            } else {
                line.substring(0, index) to line.substring(index + 1)
            }
        }.toMap()
    }
}
