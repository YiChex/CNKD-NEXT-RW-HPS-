/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanSingleRoomLowLatencyConfig

internal data class SingleRoomLowLatencyProfile(
    val name: String,
    val serverLeadTicks: Int,
    val hessLeadTicks: Int,
    val drainLimit: Int,
    val headlessFpsOverride: Int,
    val nettyWorkerThreadsOverride: Int,
    val directEventLoopWrites: Boolean,
    val description: String,
    val aliases: Set<String> = emptySet(),
    val alternateHeadlessFpsOverrides: Set<Int> = emptySet()
) {
    fun matches(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig): Boolean {
        return runtimeConfig.serverInjectedCommandLeadTicks == serverLeadTicks &&
            runtimeConfig.hessOutboundCommandLeadTicks == hessLeadTicks &&
            runtimeConfig.preTickGameCommandDrainLimit == drainLimit &&
            (runtimeConfig.headlessFpsOverride == headlessFpsOverride ||
                runtimeConfig.headlessFpsOverride in alternateHeadlessFpsOverrides) &&
            runtimeConfig.nettyWorkerThreadsOverride == nettyWorkerThreadsOverride &&
            runtimeConfig.directEventLoopWrites == directEventLoopWrites
    }

    fun summaryLine(): String {
        val fpsSummary = (listOf(headlessFpsOverride) + alternateHeadlessFpsOverrides.sorted())
            .joinToString("|")
        val aliasSummary = aliases.sorted().joinToString("|").ifBlank { "none" }
        return "singleRoomLowLatency.profile.$name: lead=$serverLeadTicks/$hessLeadTicks, " +
            "drainLimit=$drainLimit, headlessFps=$fpsSummary, nettyWorkers=$nettyWorkerThreadsOverride, " +
            "directEventLoopWrites=$directEventLoopWrites, metrics=true, aliases=$aliasSummary, $description"
    }

    fun applyTo(baseConfig: BeanSingleRoomLowLatencyConfig): BeanSingleRoomLowLatencyConfig {
        return baseConfig.copy(
            enabled = true,
            metricsEnabled = true,
            fastGameCommand = true,
            preTickGameCommandDrain = true,
            preTickGameCommandDrainRequiresStartedRoom = true,
            preTickGameCommandDrainLimit = drainLimit,
            inlineGameCommandProcessing = false,
            inlineGameCommandRequiresStartedRoom = true,
            skipPlayerOperationEvents = true,
            skipPingActionIntercept = true,
            disableTurnStoneIntoGoldOnFastPath = true,
            serverInjectedCommandLeadTicks = serverLeadTicks,
            hessOutboundCommandLeadTicks = hessLeadTicks,
            headlessFpsOverride = headlessFpsOverride,
            nettyWorkerThreadsOverride = nettyWorkerThreadsOverride,
            directEventLoopWrites = directEventLoopWrites
        ).normalized()
    }
}

internal object SingleRoomLowLatencyProfiles {
    private val profiles = listOf(
        SingleRoomLowLatencyProfile("A.observe", 8, 8, 128, 120, 1, true, "first online observation"),
        SingleRoomLowLatencyProfile("B.recommended", 8, 8, 128, 60, 1, true, "preferred live profile for low-spec single-room servers"),
        SingleRoomLowLatencyProfile("C.aggressive", 4, 4, 512, 120, 1, true, "higher desync/jitter risk"),
        SingleRoomLowLatencyProfile(
            name = "D.rollback",
            serverLeadTicks = 10,
            hessLeadTicks = 10,
            drainLimit = 128,
            headlessFpsOverride = 60,
            nettyWorkerThreadsOverride = 1,
            directEventLoopWrites = true,
            description = "stable/rollback profile for large maps or isolating low-latency regressions",
            aliases = setOf("stable", "safe"),
            alternateHeadlessFpsOverrides = setOf(0)
        )
    )

    fun summaryLines(): List<String> {
        return profiles.map { it.summaryLine() } + experienceProfileLines()
    }

    fun find(rawProfileName: String): SingleRoomLowLatencyProfile? {
        val normalized = rawProfileName
            .trim()
            .lowercase()
            .removePrefix("singleroomlowlatency.profile.")

        return profiles.firstOrNull { profile ->
            val profileName = profile.name.lowercase()
            val shortName = profileName.substringBefore(".")
            val alias = profileName.substringAfter(".", profileName)
            normalized == profileName ||
                normalized == shortName ||
                normalized == alias ||
                normalized in profile.aliases
        }
    }

    fun match(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig): SingleRoomLowLatencyProfile? {
        return profiles.firstOrNull { it.matches(runtimeConfig) }
    }

    fun currentName(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig): String {
        return when {
            !runtimeConfig.enabled -> "disabled"
            else -> match(runtimeConfig)?.name ?: "custom"
        }
    }

    fun advice(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig, profile: SingleRoomLowLatencyProfile?): String {
        if (!runtimeConfig.enabled) {
            return "enable singleRoomLowLatency.enabled before latency testing"
        }
        if (!runtimeConfig.fastGameCommand) {
            return "fastGameCommand is disabled, so the low-latency command path is mostly inactive"
        }
        if (!runtimeConfig.preTickGameCommandDrain) {
            return "preTick drain is disabled, so commands can still miss the current Hess tick"
        }
        return when (profile?.name) {
            "A.observe" -> "safe first observation profile before lowering lead ticks"
            "B.recommended" -> "preferred first real test profile for latency reduction"
            "C.aggressive" -> "aggressive profile, use only after B is stable"
            "D.rollback" -> "stable/rollback profile for large maps, low-spec machines, or isolating regressions"
            else -> "custom profile, compare with lowlatency profiles before pressure testing"
        }
    }

    fun experienceProfileLines(): List<String> {
        return listOf(
            "singleRoomLowLatency.experienceProfile.stable=lowlatency apply stable -> D.rollback; safest live profile for large maps, heavy mods, or unstable machines",
            "singleRoomLowLatency.experienceProfile.recommended=lowlatency apply recommended -> B.recommended; default first real latency test profile",
            "singleRoomLowLatency.experienceProfile.aggressive=lowlatency apply aggressive -> C.aggressive; only after recommended is stable",
            "singleRoomLowLatency.experienceProfile.lowMemory=lowlatency apply lowMemory -> D.rollback parameters with metrics disabled; runtime only, edit ServerConfig.json for persistence",
            "singleRoomLowLatency.experienceProfile.production=lowlatency apply production only disables metrics and keeps current low-latency parameters"
        )
    }

    fun warnings(runtimeConfig: SingleRoomLowLatencySupport.RuntimeConfig): List<String> {
        if (!runtimeConfig.enabled) {
            return emptyList()
        }

        val warnings = mutableListOf<String>()
        if (!runtimeConfig.fastGameCommand) {
            warnings += "fastGameCommand=false disables the command fast path"
        }
        if (!runtimeConfig.preTickGameCommandDrain) {
            warnings += "preTickGameCommandDrain=false leaves queued commands behind tick generation"
        }
        if (runtimeConfig.inlineGameCommandProcessing) {
            warnings += "inlineGameCommandProcessing=true is experimental and should stay off until B or C is verified"
        }
        if (runtimeConfig.serverInjectedCommandLeadTicks != runtimeConfig.hessOutboundCommandLeadTicks) {
            warnings += "server and Hess lead ticks differ, which makes timing diagnosis harder"
        }
        if (runtimeConfig.serverInjectedCommandLeadTicks < 4 || runtimeConfig.hessOutboundCommandLeadTicks < 4) {
            warnings += "lead ticks below 4 are high risk for jitter or desync"
        }
        if (runtimeConfig.serverInjectedCommandLeadTicks > 10 || runtimeConfig.hessOutboundCommandLeadTicks > 10) {
            warnings += "lead ticks above 10 are unlikely to improve perceived latency"
        }
        if (runtimeConfig.preTickGameCommandDrainLimit < 64) {
            warnings += "preTick drain limit below 64 can miss burst commands"
        }
        if (runtimeConfig.preTickGameCommandDrainLimit > 1024) {
            warnings += "preTick drain limit above 1024 can monopolize a Hess frame during bursts"
        }
        if (runtimeConfig.headlessFpsOverride in 1..59) {
            warnings += "headlessFpsOverride below 60 can slow the Headless main loop below the original frame pacing"
        }
        if (runtimeConfig.headlessFpsOverride == 0 &&
            runtimeConfig.serverInjectedCommandLeadTicks <= 8 &&
            runtimeConfig.hessOutboundCommandLeadTicks <= 8
        ) {
            warnings += "headlessFpsOverride=0 keeps original frame pacing, so A/B/C latency profiles will not fully apply"
        }
        if (runtimeConfig.headlessFpsOverride > 120) {
            warnings += "headlessFpsOverride above 120 can raise CPU load and frame jitter before latency improves further"
        }
        if (runtimeConfig.nettyWorkerThreadsOverride > 2) {
            warnings += "nettyWorkerThreadsOverride above 2 can reduce per-room ordering locality for this single-room profile"
        }
        if (runtimeConfig.nettyWorkerThreadsOverride == 0 &&
            runtimeConfig.serverInjectedCommandLeadTicks <= 8 &&
            runtimeConfig.hessOutboundCommandLeadTicks <= 8
        ) {
            warnings += "nettyWorkerThreadsOverride=0 keeps original worker selection, so A/B/C latency profiles will not fully apply"
        }
        if (!runtimeConfig.directEventLoopWrites &&
            runtimeConfig.serverInjectedCommandLeadTicks <= 8 &&
            runtimeConfig.hessOutboundCommandLeadTicks <= 8
        ) {
            warnings += "directEventLoopWrites=false keeps same-eventLoop TCP sends on the queued write path"
        }
        if (!runtimeConfig.preTickGameCommandDrainRequiresStartedRoom) {
            warnings += "preTick drain before room start increases lobby-side behavior risk"
        }
        if (!runtimeConfig.skipPlayerOperationEvents ||
            !runtimeConfig.skipPingActionIntercept ||
            !runtimeConfig.disableTurnStoneIntoGoldOnFastPath
        ) {
            warnings += "one or more fast-path bypass toggles are disabled, so command handling may fall back to the slower path"
        }
        if (!runtimeConfig.metricsEnabled) {
            warnings += "metrics are disabled, so status counters cannot validate the live effect"
        }
        return warnings
    }
}
