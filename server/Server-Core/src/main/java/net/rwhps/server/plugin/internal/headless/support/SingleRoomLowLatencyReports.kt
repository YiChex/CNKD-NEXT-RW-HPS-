/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal object SingleRoomLowLatencyReports {
    private data class DegradeRisk(
        val severity: Int,
        val reason: String
    )

    private data class DegradeAdvice(
        val level: String,
        val score: Int,
        val command: String,
        val reasons: List<String>
    )

    private data class ExperienceSummary(
        val state: String,
        val bottleneck: String,
        val action: String,
        val command: String,
        val reason: String,
        val profile: String,
        val latencyDisplayState: String,
        val latencyDisplayAdvice: String
    )

    fun buildDiagnostics(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        overrideActive: Boolean,
        overrideName: String?,
        summaryConsoleEnabled: Boolean,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): List<String> {
        val config = runtimeConfig.source
        return listOf(
            "singleRoomLowLatency.configSource=${if (overrideActive) "runtimeOverride" else "ServerConfig.json"}",
            "singleRoomLowLatency.runtimeOverride=$overrideActive",
            "singleRoomLowLatency.runtimeOverrideProfile=${overrideName ?: "none"}",
            "singleRoomLowLatency.enabled=${config.enabled}",
            "singleRoomLowLatency.fastGameCommand=${config.fastGameCommand}",
            "singleRoomLowLatency.preTickGameCommandDrain=${config.preTickGameCommandDrain}",
            "singleRoomLowLatency.preTickGameCommandDrainRequiresStartedRoom=${config.preTickGameCommandDrainRequiresStartedRoom}",
            "singleRoomLowLatency.preTickGameCommandDrainLimit=${runtimeConfig.preTickGameCommandDrainLimit}",
            "singleRoomLowLatency.inlineGameCommandProcessing=${config.inlineGameCommandProcessing}",
            "singleRoomLowLatency.metricsEnabled=${runtimeConfig.metricsEnabled}",
            "singleRoomLowLatency.metricsConfigured=${config.metricsEnabled}",
            "singleRoomLowLatency.summaryConsoleEnabled=$summaryConsoleEnabled",
            "singleRoomLowLatency.serverInjectedCommandLeadTicks=${runtimeConfig.serverInjectedCommandLeadTicks}",
            "singleRoomLowLatency.hessOutboundCommandLeadTicks=${runtimeConfig.hessOutboundCommandLeadTicks}",
            "singleRoomLowLatency.headlessFpsOverride=${runtimeConfig.headlessFpsOverride}",
            "singleRoomLowLatency.nettyWorkerThreadsOverride=${runtimeConfig.nettyWorkerThreadsOverride}",
            "singleRoomLowLatency.directEventLoopWrites=${runtimeConfig.directEventLoopWrites}"
        ) + buildCurrentProfileDiagnostics(runtimeConfig) + buildDegradeAdvice(
            runtimeConfig = runtimeConfig,
            overrideActive = overrideActive,
            metrics = metrics
        ) + buildExperienceSummary(
            runtimeConfig = runtimeConfig,
            overrideActive = overrideActive,
            metrics = metrics
        ) + listOf(
            "singleRoomLowLatency.hessLeadApplied=${metrics.hessLeadApplied}",
            "singleRoomLowLatency.hessLeadLastValue=${metrics.hessLeadLastValue}",
            "singleRoomLowLatency.hessFrameObserved=${metrics.hessFrameObserved}",
            "singleRoomLowLatency.hessFrameBridgeFailed=${metrics.hessFrameBridgeFailed}",
            "singleRoomLowLatency.engineBridgeFused=${metrics.engineBridgeFused}",
            "singleRoomLowLatency.engineBridgeFuseReason=${metrics.engineBridgeFuseReason}",
            "singleRoomLowLatency.hessLastQ=${metrics.hessLastQ}",
            "singleRoomLowLatency.hessLastR=${metrics.hessLastR}",
            "singleRoomLowLatency.hessLastX=${metrics.hessLastX}",
            "singleRoomLowLatency.hessRoomStartedLast=${metrics.hessRoomStartedLast}",
            "singleRoomLowLatency.hessQueueDepthAvg=${metrics.hessQueueDepthAvg}",
            "singleRoomLowLatency.hessQueueDepthMax=${metrics.hessQueueDepthMax}",
            "singleRoomLowLatency.hessQueueDepthLast=${metrics.hessQueueDepthLast}",
            "singleRoomLowLatency.hessQueueDepthAfterDrainAvg=${metrics.hessQueueDepthAfterDrainAvg}",
            "singleRoomLowLatency.hessQueueDepthAfterDrainMax=${metrics.hessQueueDepthAfterDrainMax}",
            "singleRoomLowLatency.hessQueueDepthAfterDrainLast=${metrics.hessQueueDepthAfterDrainLast}",
            "singleRoomLowLatency.gameCommandReceived=${metrics.gameCommandReceived}",
            "singleRoomLowLatency.gameCommandPendingMetricPackets=${metrics.gameCommandPendingMetricPackets}",
            "singleRoomLowLatency.gameCommandFastPath=${metrics.gameCommandFastPath}",
            "singleRoomLowLatency.gameCommandFastPathPercent=${percentage(metrics.gameCommandFastPath, metrics.gameCommandReceived)}",
            "singleRoomLowLatency.gameCommandFastPathRejected=${metrics.gameCommandFastPathRejected}",
            "singleRoomLowLatency.gameCommandFastPathRejectedLastReason=${metrics.gameCommandFastPathRejectedLastReason}",
            "singleRoomLowLatency.gameCommandFastPathFallback=${metrics.gameCommandFastPathFallback}",
            "singleRoomLowLatency.gameCommandFastPathFallbackLastReason=${metrics.gameCommandFastPathFallbackLastReason}",
            "singleRoomLowLatency.gameCommandNormalPath=${metrics.gameCommandNormalPath}",
            "singleRoomLowLatency.gameCommandStopped=${metrics.gameCommandStopped}",
            "singleRoomLowLatency.gameCommandClassified=${metrics.gameCommandClassified}",
            "singleRoomLowLatency.gameCommandSystemAction=${metrics.gameCommandSystemAction}",
            "singleRoomLowLatency.gameCommandSystemActionLast=${metrics.gameCommandSystemActionLast}",
            "singleRoomLowLatency.gameCommandMapPing=${metrics.gameCommandMapPing}",
            "singleRoomLowLatency.gameCommandUnitOperation=${metrics.gameCommandUnitOperation}",
            "singleRoomLowLatency.gameCommandFactoryAction=${metrics.gameCommandFactoryAction}",
            "singleRoomLowLatency.gameCommandInternalAction=${metrics.gameCommandInternalAction}",
            "singleRoomLowLatency.gameCommandUnknownAction=${metrics.gameCommandUnknownAction}",
            "singleRoomLowLatency.gameCommandClassificationFailed=${metrics.gameCommandClassificationFailed}",
            "singleRoomLowLatency.gameCommandClassificationLastKind=${metrics.gameCommandClassificationLastKind}",
            "singleRoomLowLatency.gameCommandClassificationLastActionId=${metrics.gameCommandClassificationLastActionId}",
            "singleRoomLowLatency.gameCommandClassificationLastSender=${metrics.gameCommandClassificationLastSender}",
            "singleRoomLowLatency.gameCommandClassificationLastSharedControlCheck=${metrics.gameCommandClassificationLastSharedControlCheck}",
            "singleRoomLowLatency.gameCommandClassificationLastFailure=${metrics.gameCommandClassificationLastFailure}",
            "singleRoomLowLatency.gameCommandEngineQueued=${metrics.gameCommandEngineQueued}",
            "singleRoomLowLatency.gameCommandFrameQueued=${metrics.gameCommandFrameQueued}",
            "singleRoomLowLatency.gameCommandFrameQueueDepthAvg=${metrics.gameCommandFrameQueueDepthAvg}",
            "singleRoomLowLatency.gameCommandFrameQueueDepthMax=${metrics.gameCommandFrameQueueDepthMax}",
            "singleRoomLowLatency.gameCommandPreTickDrained=${metrics.gameCommandPreTickDrained}",
            "singleRoomLowLatency.gameCommandPreTickDrainPercentOfFrameQueued=${percentage(metrics.gameCommandPreTickDrained, metrics.gameCommandFrameQueued)}",
            "singleRoomLowLatency.gameCommandPreTickDrainFailed=${metrics.gameCommandPreTickDrainFailed}",
            "singleRoomLowLatency.gameCommandPreTickDrainPasses=${metrics.gameCommandPreTickDrainPasses}",
            "singleRoomLowLatency.gameCommandPreTickDrainPassAvg=${metrics.gameCommandPreTickDrainPassAvg}",
            "singleRoomLowLatency.gameCommandPreTickDrainPassMax=${metrics.gameCommandPreTickDrainPassMax}",
            "singleRoomLowLatency.gameCommandPreTickDrainSkippedNotStarted=${metrics.gameCommandPreTickDrainSkippedNotStarted}",
            "singleRoomLowLatency.gameCommandPreTickDrainBlockedByNonCommand=${metrics.gameCommandPreTickDrainBlockedByNonCommand}",
            "singleRoomLowLatency.gameCommandPreTickDrainBlockedByNonCommandLastType=${metrics.gameCommandPreTickDrainBlockedByNonCommandLastType}",
            "singleRoomLowLatency.gameCommandPreTickDrainBlockedByNonCommandLastName=${metrics.gameCommandPreTickDrainBlockedByNonCommandLastName}",
            "singleRoomLowLatency.gameCommandInlineProcessed=${metrics.gameCommandInlineProcessed}",
            "singleRoomLowLatency.gameCommandInlineFailed=${metrics.gameCommandInlineFailed}",
            "singleRoomLowLatency.receiveToEngineAvgMicros=${metrics.receiveToEngineAvgMicros}",
            "singleRoomLowLatency.receiveToEngineP50Micros=${metrics.receiveToEngineP50Micros}",
            "singleRoomLowLatency.receiveToEngineP95Micros=${metrics.receiveToEngineP95Micros}",
            "singleRoomLowLatency.receiveToEngineMaxMicros=${metrics.receiveToEngineMaxMicros}",
            "singleRoomLowLatency.receiveToEnginePercentileSamples=${metrics.receiveToEnginePercentileSamples}",
            "singleRoomLowLatency.outgoingTick=${metrics.outgoingTick}",
            "singleRoomLowLatency.outgoingSync=${metrics.outgoingSync}",
            "singleRoomLowLatency.outgoingSyncCheck=${metrics.outgoingSyncCheck}",
            "singleRoomLowLatency.outgoingTeamList=${metrics.outgoingTeamList}"
        )
    }

    fun buildCurrentProfileDiagnostics(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig): List<String> {
        val profile = SingleRoomLowLatencyProfiles.match(runtimeConfig)
        val profileName = SingleRoomLowLatencyProfiles.currentName(runtimeConfig)
        val lines = mutableListOf(
            "singleRoomLowLatency.currentProfile=$profileName",
            "singleRoomLowLatency.currentProfileLead=${runtimeConfig.serverInjectedCommandLeadTicks}/${runtimeConfig.hessOutboundCommandLeadTicks}",
            "singleRoomLowLatency.currentProfileDrainLimit=${runtimeConfig.preTickGameCommandDrainLimit}",
            "singleRoomLowLatency.currentProfileFps=${runtimeConfig.headlessFpsOverride}",
            "singleRoomLowLatency.currentProfileNettyWorkers=${runtimeConfig.nettyWorkerThreadsOverride}",
            "singleRoomLowLatency.currentProfileNettyWorkersStartupOnly=true",
            "singleRoomLowLatency.currentProfileNettyWorkersNote=requires server restart to rebuild Netty worker group",
            "singleRoomLowLatency.currentProfileDirectEventLoopWrites=${runtimeConfig.directEventLoopWrites}",
            "singleRoomLowLatency.currentProfileMode=${if (runtimeConfig.metricsEnabled) "test" else "production"}",
            "singleRoomLowLatency.currentProfileMetrics=${if (runtimeConfig.metricsEnabled) "enabled" else "disabled"}",
            "singleRoomLowLatency.currentProfileAdvice=${SingleRoomLowLatencyProfiles.advice(runtimeConfig, profile)}"
        )

        val warnings = SingleRoomLowLatencyProfiles.warnings(runtimeConfig)
        if (warnings.isEmpty()) {
            lines += "singleRoomLowLatency.currentProfileWarning=none"
        } else {
            warnings.forEachIndexed { index, warning ->
                lines += "singleRoomLowLatency.currentProfileWarning.${index + 1}=$warning"
            }
        }
        return lines
    }

    fun buildDegradeAdvice(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        overrideActive: Boolean,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): List<String> {
        val advice = currentDegradeAdvice(runtimeConfig, metrics)
        val lines = mutableListOf(
            "singleRoomLowLatency.degrade.mode=adviceOnly",
            "singleRoomLowLatency.degrade.level=${advice.level}",
            "singleRoomLowLatency.degrade.score=${advice.score}",
            "singleRoomLowLatency.degrade.command=${advice.command}",
            "singleRoomLowLatency.degrade.runtimeOverride=$overrideActive"
        )

        if (advice.reasons.isEmpty()) {
            lines += "singleRoomLowLatency.degrade.reason=none"
        } else {
            advice.reasons.forEachIndexed { index, reason ->
                lines += "singleRoomLowLatency.degrade.reason.${index + 1}=$reason"
            }
        }

        return lines
    }

    fun buildTestSummary(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        overrideActive: Boolean,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): List<String> {
        val profile = SingleRoomLowLatencyProfiles.currentName(runtimeConfig)
        val advice = currentDegradeAdvice(runtimeConfig, metrics)
        val experience = currentExperienceSummary(runtimeConfig, advice, metrics)
        val lines = mutableListOf(
            "singleRoomLowLatency.testSummary.profile=$profile",
            "singleRoomLowLatency.testSummary.configSource=${if (overrideActive) "runtimeOverride" else "ServerConfig.json"}",
            "singleRoomLowLatency.testSummary.enabled=${runtimeConfig.enabled}",
            "singleRoomLowLatency.testSummary.result=${advice.level}",
            "singleRoomLowLatency.testSummary.next=${advice.command}",
            "experience.state=${experience.state}",
            "experience.bottleneck=${experience.bottleneck}",
            "experience.action=${experience.action}",
            "experience.command=${experience.command}",
            "experience.reason=${experience.reason}",
            "experience.profile=${experience.profile}",
            "experience.latencyDisplayState=${experience.latencyDisplayState}",
            "experience.latencyDisplayAdvice=${experience.latencyDisplayAdvice}",
            "singleRoomLowLatency.testSummary.commands=total=${metrics.gameCommandReceived}, fast=${metrics.gameCommandFastPath}, normal=${metrics.gameCommandNormalPath}, fastRejected=${metrics.gameCommandFastPathRejected}, fastRejectedLast=${metrics.gameCommandFastPathRejectedLastReason}, fastFallback=${metrics.gameCommandFastPathFallback}, fastFallbackLast=${metrics.gameCommandFastPathFallbackLastReason}, fastPct=${percentage(metrics.gameCommandFastPath, metrics.gameCommandReceived)}",
            "singleRoomLowLatency.testSummary.commandTypes=classified=${metrics.gameCommandClassified}, system=${metrics.gameCommandSystemAction}, systemLast=${metrics.gameCommandSystemActionLast}, mapPing=${metrics.gameCommandMapPing}, unit=${metrics.gameCommandUnitOperation}, factory=${metrics.gameCommandFactoryAction}, internal=${metrics.gameCommandInternalAction}, unknown=${metrics.gameCommandUnknownAction}, classifyFailed=${metrics.gameCommandClassificationFailed}, lastKind=${metrics.gameCommandClassificationLastKind}, lastSender=${metrics.gameCommandClassificationLastSender}, sharedControlCheck=${metrics.gameCommandClassificationLastSharedControlCheck}",
            "singleRoomLowLatency.testSummary.frameQueue=queued=${metrics.gameCommandFrameQueued}, depthAvg=${metrics.gameCommandFrameQueueDepthAvg}, depthMax=${metrics.gameCommandFrameQueueDepthMax}",
            "singleRoomLowLatency.testSummary.preTick=drained=${metrics.gameCommandPreTickDrained}, drainPct=${percentage(metrics.gameCommandPreTickDrained, metrics.gameCommandFrameQueued)}, blocked=${metrics.gameCommandPreTickDrainBlockedByNonCommand}, failed=${metrics.gameCommandPreTickDrainFailed}, passMax=${metrics.gameCommandPreTickDrainPassMax}",
            "singleRoomLowLatency.testSummary.hessQueue=beforeAvg=${metrics.hessQueueDepthAvg}, beforeMax=${metrics.hessQueueDepthMax}, afterAvg=${metrics.hessQueueDepthAfterDrainAvg}, afterMax=${metrics.hessQueueDepthAfterDrainMax}",
            "singleRoomLowLatency.testSummary.bridge=failed=${metrics.hessFrameBridgeFailed}, fused=${metrics.engineBridgeFused}, fuseReason=${metrics.engineBridgeFuseReason}, hessLeadApplied=${metrics.hessLeadApplied}, hessLastQ=${metrics.hessLastQ}, hessLastR=${metrics.hessLastR}, hessLastX=${metrics.hessLastX}",
            "singleRoomLowLatency.testSummary.latency=receiveToEngineAvgMicros=${metrics.receiveToEngineAvgMicros}, receiveToEngineP50Micros=${metrics.receiveToEngineP50Micros}, receiveToEngineP95Micros=${metrics.receiveToEngineP95Micros}, receiveToEngineMaxMicros=${metrics.receiveToEngineMaxMicros}, percentileSamples=${metrics.receiveToEnginePercentileSamples}, samples=${metrics.receiveToEngineSamples}",
            "singleRoomLowLatency.testSummary.packets=outTick=${metrics.outgoingTick}, outSync=${metrics.outgoingSync}, outSyncCheck=${metrics.outgoingSyncCheck}, outTeamList=${metrics.outgoingTeamList}",
            "singleRoomLowLatency.testSummary.nextRound=lowlatency reset"
        )

        if (advice.reasons.isEmpty()) {
            lines += "singleRoomLowLatency.testSummary.reason=none"
        } else {
            advice.reasons.forEachIndexed { index, reason ->
                lines += "singleRoomLowLatency.testSummary.reason.${index + 1}=$reason"
            }
        }

        return lines
    }

    fun buildFirstTestReadout(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        overrideActive: Boolean,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): List<String> {
        val profile = SingleRoomLowLatencyProfiles.currentName(runtimeConfig)
        val advice = currentDegradeAdvice(runtimeConfig, metrics)
        val experience = currentExperienceSummary(runtimeConfig, advice, metrics)
        val phase = firstTestPhase(runtimeConfig, advice, metrics)
        val decision = firstTestDecision(advice, experience)
        val lines = mutableListOf(
            "singleRoomLowLatency.readout.phase=$phase",
            "singleRoomLowLatency.readout.profile=$profile",
            "singleRoomLowLatency.readout.configSource=${if (overrideActive) "runtimeOverride" else "ServerConfig.json"}",
            "singleRoomLowLatency.readout.metricsMode=${if (runtimeConfig.metricsEnabled) "test" else "production"}",
            "singleRoomLowLatency.readout.serverResult=${advice.level}",
            "singleRoomLowLatency.readout.clientLatency=${experience.latencyDisplayState}",
            "singleRoomLowLatency.readout.bottleneck=${experience.bottleneck}",
            "singleRoomLowLatency.readout.decision=$decision",
            "singleRoomLowLatency.readout.action=${experience.action}",
            "singleRoomLowLatency.readout.command=${experience.command}",
            "singleRoomLowLatency.readout.reason=${experience.reason}",
            "singleRoomLowLatency.readout.sequence.1=startmode bootcheck",
            "singleRoomLowLatency.readout.sequence.2=lowlatency reset",
            "singleRoomLowLatency.readout.sequence.3=play one real round or at least 60 seconds of active commands",
            "singleRoomLowLatency.readout.sequence.4=lowlatency readout",
            "singleRoomLowLatency.readout.sequence.5=lowlatency summary",
            "singleRoomLowLatency.readout.sequence.6=lowlatency advise",
            "singleRoomLowLatency.readout.rule.ok=keep current profile; after more stable rounds use lowlatency apply production",
            "singleRoomLowLatency.readout.rule.observe=collect another round; if clientLatency is high or jitter, compare route/client ping before changing server profile",
            "singleRoomLowLatency.readout.rule.degrade=follow command and keep low-latency mode on stable/recommended profile",
            "singleRoomLowLatency.readout.rule.rollback=apply stable or keep stable and collect status; off is manual emergency comparison only",
            "singleRoomLowLatency.readout.detail.fastPct=${percentage(metrics.gameCommandFastPath, metrics.gameCommandReceived)}",
            "singleRoomLowLatency.readout.detail.fastRejected=${metrics.gameCommandFastPathRejected}",
            "singleRoomLowLatency.readout.detail.fastRejectedLast=${metrics.gameCommandFastPathRejectedLastReason}",
            "singleRoomLowLatency.readout.detail.fastFallback=${metrics.gameCommandFastPathFallback}",
            "singleRoomLowLatency.readout.detail.fastFallbackLast=${metrics.gameCommandFastPathFallbackLastReason}",
            "singleRoomLowLatency.readout.detail.systemAction=${metrics.gameCommandSystemAction}",
            "singleRoomLowLatency.readout.detail.systemActionLast=${metrics.gameCommandSystemActionLast}",
            "singleRoomLowLatency.readout.detail.commandTypes=system=${metrics.gameCommandSystemAction}, mapPing=${metrics.gameCommandMapPing}, unit=${metrics.gameCommandUnitOperation}, factory=${metrics.gameCommandFactoryAction}, internal=${metrics.gameCommandInternalAction}, unknown=${metrics.gameCommandUnknownAction}, classifyFailed=${metrics.gameCommandClassificationFailed}",
            "singleRoomLowLatency.readout.detail.drainPct=${percentage(metrics.gameCommandPreTickDrained, metrics.gameCommandFrameQueued)}",
            "singleRoomLowLatency.readout.detail.hessQueueMax=${metrics.hessQueueDepthMax}",
            "singleRoomLowLatency.readout.detail.hessQueueAfterDrainMax=${metrics.hessQueueDepthAfterDrainMax}",
            "singleRoomLowLatency.readout.detail.receiveToEngineAvgMicros=${metrics.receiveToEngineAvgMicros}",
            "singleRoomLowLatency.readout.detail.receiveToEngineP50Micros=${metrics.receiveToEngineP50Micros}",
            "singleRoomLowLatency.readout.detail.receiveToEngineP95Micros=${metrics.receiveToEngineP95Micros}",
            "singleRoomLowLatency.readout.detail.receiveToEngineMaxMicros=${metrics.receiveToEngineMaxMicros}",
            "singleRoomLowLatency.readout.detail.receiveToEnginePercentileSamples=${metrics.receiveToEnginePercentileSamples}"
        )

        if (advice.reasons.isEmpty()) {
            lines += "singleRoomLowLatency.readout.degradeReason=none"
        } else {
            advice.reasons.forEachIndexed { index, reason ->
                lines += "singleRoomLowLatency.readout.degradeReason.${index + 1}=$reason"
            }
        }
        return lines
    }

    fun buildExperienceSummary(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        overrideActive: Boolean,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): List<String> {
        val advice = currentDegradeAdvice(runtimeConfig, metrics)
        val experience = currentExperienceSummary(runtimeConfig, advice, metrics)
        return listOf(
            "experience.state=${experience.state}",
            "experience.bottleneck=${experience.bottleneck}",
            "experience.action=${experience.action}",
            "experience.command=${experience.command}",
            "experience.reason=${experience.reason}",
            "experience.profile=${experience.profile}",
            "experience.configSource=${if (overrideActive) "runtimeOverride" else "ServerConfig.json"}",
            "experience.metricsMode=${if (runtimeConfig.metricsEnabled) "test" else "production"}",
            "experience.latencyDisplayState=${experience.latencyDisplayState}",
            "experience.latencyDisplayAdvice=${experience.latencyDisplayAdvice}"
        )
    }

    private fun firstTestPhase(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        advice: DegradeAdvice,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): String {
        return when {
            !runtimeConfig.enabled -> "disabled"
            !runtimeConfig.metricsEnabled -> "production_without_validation_metrics"
            metrics.hessFrameObserved == 0L && metrics.gameCommandReceived == 0L -> "before_or_between_rounds"
            advice.level == "ok" -> "validated_round"
            advice.level == "watch" -> "watch_round"
            advice.level == "degrade" -> "needs_profile_step_down"
            advice.level == "rollback" -> "needs_stable_profile"
            else -> advice.level
        }
    }

    private fun firstTestDecision(advice: DegradeAdvice, experience: ExperienceSummary): String {
        return when (advice.level) {
            "ok" -> if (experience.latencyDisplayState == "stable" || experience.latencyDisplayState == "unknown") {
                "keep_current_profile"
            } else {
                "server_ok_check_client_network"
            }
            "watch", "observe" -> "collect_one_more_round"
            "production" -> "apply_test_before_validation"
            "degrade", "rollback" -> experience.action
            "disabled" -> "enable_low_latency_before_test"
            else -> experience.action
        }
    }

    private fun currentDegradeAdvice(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): DegradeAdvice {
        if (!runtimeConfig.enabled) {
            return DegradeAdvice(
                level = "disabled",
                score = 0,
                command = "lowlatency apply recommended",
                reasons = listOf("low latency mode is disabled; enable the recommended profile before latency testing")
            )
        }

        if (!runtimeConfig.metricsEnabled) {
            return DegradeAdvice(
                level = "production",
                score = 0,
                command = "lowlatency apply test",
                reasons = listOf("metrics are disabled for lower hot-path overhead; run lowlatency apply test before collecting validation summaries")
            )
        }

        if (metrics.hessFrameObserved == 0L && metrics.gameCommandReceived == 0L) {
            return DegradeAdvice(
                level = "observe",
                score = 0,
                command = "lowlatency status",
                reasons = listOf("no live samples yet; run one test round after lowlatency reset")
            )
        }

        val risks = currentDegradeRisks(runtimeConfig, metrics)
        var maxSeverity = 0
        risks.forEach { risk ->
            if (risk.severity > maxSeverity) {
                maxSeverity = risk.severity
            }
        }
        val level = when {
            maxSeverity >= 3 -> "rollback"
            maxSeverity == 2 -> "degrade"
            maxSeverity == 1 -> "watch"
            else -> "ok"
        }
        return DegradeAdvice(
            level = level,
            score = maxSeverity,
            command = degradeCommand(runtimeConfig, maxSeverity),
            reasons = risks.map { risk -> "S${risk.severity}: ${risk.reason}" }
        )
    }

    private fun currentExperienceSummary(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        advice: DegradeAdvice,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): ExperienceSummary {
        val latencyDisplay = StartModeLatencyHeartbeatSupport.currentDisplayAssessment()
        val serverState = when (advice.level) {
            "rollback" -> "rollback"
            "degrade" -> "degrade"
            "watch", "observe", "disabled" -> "observe"
            "ok", "production" -> "stable"
            else -> "observe"
        }
        val state = when {
            serverState == "rollback" || serverState == "degrade" -> serverState
            latencyDisplay.state == "high" || latencyDisplay.state == "jitter" || latencyDisplay.state == "stale" -> "observe"
            else -> serverState
        }
        val command = experienceCommand(state, advice.command, latencyDisplay.state)

        return ExperienceSummary(
            state = state,
            bottleneck = inferExperienceBottleneck(metrics, latencyDisplay),
            action = experienceAction(state, command),
            command = command,
            reason = experienceReason(state, advice, latencyDisplay),
            profile = SingleRoomLowLatencyProfiles.currentName(runtimeConfig),
            latencyDisplayState = latencyDisplay.state,
            latencyDisplayAdvice = latencyDisplay.advice
        )
    }

    private fun inferExperienceBottleneck(
        metrics: SingleRoomLowLatencyMetricsSnapshot,
        latencyDisplay: StartModeLatencyHeartbeatSupport.LatencyDisplayAssessment
    ): String {
        if (
            metrics.hessFrameBridgeFailed > 0L ||
            metrics.hessQueueDepthMax >= 64L ||
            metrics.hessQueueDepthAfterDrainMax >= 48L ||
            metrics.hessQueueDepthAvg >= 24L ||
            metrics.hessQueueDepthAfterDrainAvg >= 24L
        ) {
            return "hess_queue"
        }
        if (metrics.gameCommandPreTickDrainSkippedNotStarted > 0L && !metrics.hessRoomStartedLast) {
            return "map_load"
        }
        if (
            metrics.gameCommandPreTickDrainFailed > 0L ||
            metrics.gameCommandInlineFailed > 0L ||
            metrics.gameCommandPreTickDrainBlockedByNonCommand >= 10L ||
            metrics.gameCommandFrameQueueDepthMax >= 20L
        ) {
            return "server_frame_wait"
        }
        if (latencyDisplay.state == "high" || latencyDisplay.state == "jitter" || latencyDisplay.state == "stale") {
            return "client_network"
        }
        return "unknown"
    }

    private fun experienceCommand(state: String, adviceCommand: String, latencyDisplayState: String): String {
        if (state == "stable") {
            return "keep current profile"
        }
        if (adviceCommand != "keep current profile") {
            return adviceCommand
        }
        return if (latencyDisplayState == "high" || latencyDisplayState == "jitter" || latencyDisplayState == "stale") {
            "startmode diagnose"
        } else {
            "lowlatency status"
        }
    }

    private fun experienceAction(state: String, command: String): String {
        return when {
            state == "stable" -> "keep"
            command.contains("apply stable") -> "apply stable"
            command.contains("apply recommended") -> "apply recommended"
            command.contains("apply rollback") -> "apply rollback"
            command.contains("apply off") -> "apply off"
            else -> "collect status"
        }
    }

    private fun experienceReason(
        state: String,
        advice: DegradeAdvice,
        latencyDisplay: StartModeLatencyHeartbeatSupport.LatencyDisplayAssessment
    ): String {
        if (advice.reasons.isNotEmpty()) {
            return advice.reasons.first()
        }
        return when {
            latencyDisplay.state == "high" -> "latency display reports high ping"
            latencyDisplay.state == "jitter" -> "latency display reports jitter or partial stale samples"
            latencyDisplay.state == "stale" -> "latency display samples are stale"
            state == "stable" -> "current profile has no high-risk server-side symptoms"
            else -> "collect more live samples before changing profile"
        }
    }

    private fun currentDegradeRisks(
        runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig,
        metrics: SingleRoomLowLatencyMetricsSnapshot
    ): List<DegradeRisk> {
        val risks = mutableListOf<DegradeRisk>()
        val fastPercent = percentage(metrics.gameCommandFastPath, metrics.gameCommandReceived)
        val blockedPercentOfFrameQueued = percentage(
            metrics.gameCommandPreTickDrainBlockedByNonCommand,
            metrics.gameCommandFrameQueued
        )
        val drainPercentOfFrameQueued = percentage(
            metrics.gameCommandPreTickDrained,
            metrics.gameCommandFrameQueued
        )

        if (metrics.hessFrameBridgeFailed > 0L) {
            risks += DegradeRisk(3, "Hess lead bridge failed ${metrics.hessFrameBridgeFailed} times; outbound lead override may not be stable")
        }
        if (metrics.engineBridgeFused) {
            risks += DegradeRisk(3, "engine bridge fuse is active, reason=${metrics.engineBridgeFuseReason}; server has fallen back to the original command path")
        }
        if (metrics.gameCommandFastPathRejected > 0L) {
            risks += DegradeRisk(2, "fast path rejected ${metrics.gameCommandFastPathRejected} command(s), lastReason=${metrics.gameCommandFastPathRejectedLastReason}; check startmode diagnose if players report no-control")
        }
        if (metrics.gameCommandFastPathFallback > 0L) {
            risks += DegradeRisk(1, "fast path fell back ${metrics.gameCommandFastPathFallback} command(s), lastReason=${metrics.gameCommandFastPathFallbackLastReason}; normal path kept commands flowing")
        }
        if (metrics.gameCommandPreTickDrainFailed > 0L) {
            risks += DegradeRisk(3, "preTick drain failed ${metrics.gameCommandPreTickDrainFailed} times; keep larger lead ticks before lowering latency further")
        }
        if (metrics.gameCommandInlineFailed > 0L) {
            risks += DegradeRisk(3, "inline command processing failed ${metrics.gameCommandInlineFailed} times; keep low-latency mode but return to stable profile before retest")
        }
        if (metrics.hessQueueDepthMax >= 128L || metrics.hessQueueDepthAfterDrainMax >= 96L) {
            risks += DegradeRisk(3, "Hess queue depth spiked to before=${metrics.hessQueueDepthMax} afterDrain=${metrics.hessQueueDepthAfterDrainMax}")
        } else if (
            metrics.hessQueueDepthMax >= 64L ||
            metrics.hessQueueDepthAfterDrainMax >= 48L ||
            metrics.hessQueueDepthAvg >= 24L ||
            metrics.hessQueueDepthAfterDrainAvg >= 24L
        ) {
            risks += DegradeRisk(2, "Hess queue depth is high beforeAvg=${metrics.hessQueueDepthAvg} beforeMax=${metrics.hessQueueDepthMax} afterAvg=${metrics.hessQueueDepthAfterDrainAvg} afterMax=${metrics.hessQueueDepthAfterDrainMax}")
        }
        if (
            metrics.gameCommandFrameQueued >= 20L &&
            metrics.gameCommandPreTickDrainBlockedByNonCommand >= 10L &&
            blockedPercentOfFrameQueued >= 25L
        ) {
            risks += DegradeRisk(2, "preTick drain is often blocked by non-command packets: blocked=${metrics.gameCommandPreTickDrainBlockedByNonCommand} frameQueued=${metrics.gameCommandFrameQueued} pct=$blockedPercentOfFrameQueued")
        }
        if (
            runtimeConfig.preTickGameCommandDrain &&
            metrics.hessRoomStartedLast &&
            metrics.gameCommandFrameQueued >= 20L &&
            metrics.gameCommandPreTickDrained == 0L
        ) {
            risks += DegradeRisk(2, "preTick drain has no successful drains while commands are frame queued")
        }
        if (metrics.gameCommandReceived >= 20L && fastPercent < 80L) {
            risks += DegradeRisk(2, "fast path ratio is low: fast=${metrics.gameCommandFastPath} normal=${metrics.gameCommandNormalPath} total=${metrics.gameCommandReceived} pct=$fastPercent")
        } else if (metrics.gameCommandReceived >= 20L && fastPercent < 95L) {
            risks += DegradeRisk(1, "fast path ratio is below target: fast=${metrics.gameCommandFastPath} normal=${metrics.gameCommandNormalPath} total=${metrics.gameCommandReceived} pct=$fastPercent")
        }
        if (metrics.gameCommandFrameQueued >= 20L && drainPercentOfFrameQueued < 20L) {
            risks += DegradeRisk(1, "preTick drain hit rate is low: drained=${metrics.gameCommandPreTickDrained} frameQueued=${metrics.gameCommandFrameQueued} pct=$drainPercentOfFrameQueued")
        }

        return risks
    }

    private fun degradeCommand(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig, severity: Int): String {
        if (severity <= 0) {
            return "keep current profile"
        }

        val profile = SingleRoomLowLatencyProfiles.match(runtimeConfig)?.name.orEmpty()
        return when {
            severity >= 3 && profile == "D.rollback" -> "lowlatency reset; keep stable profile and collect status"
            severity >= 3 -> "lowlatency apply stable"
            severity == 2 && profile == "C.aggressive" -> "lowlatency apply recommended"
            severity == 2 && runtimeConfig.serverInjectedCommandLeadTicks <= 6 -> "lowlatency apply stable"
            severity == 2 -> "lowlatency reset; retest current profile"
            else -> "lowlatency reset; watch one more round"
        }
    }

    private fun percentage(value: Long, total: Long): Long {
        if (total <= 0L) {
            return 0L
        }
        return (value * 100L) / total
    }
}
