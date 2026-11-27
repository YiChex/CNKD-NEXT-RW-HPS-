/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanConsoleConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.util.log.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object StartModeConsoleOutputSupport {
    private val fallbackConfig = BeanConsoleConfig()
    private val throttleStates = ConcurrentHashMap<String, ThrottleState>()
    private val throttleSuppressedTotal = AtomicLong(0L)
    @Volatile
    private var cachedLowLatencySummary = LowLatencySummaryCache(fallbackConfig, false)

    private data class LowLatencySummaryCache(
        val config: BeanConsoleConfig,
        val enabled: Boolean
    )

    enum class Category {
        PLAYER_JOIN_LEAVE,
        PLAYER_CHAT,
        LOW_LATENCY_SUMMARY,
        SURVIVAL_REPAIR,
        RANKED_COMPATIBILITY,
        LIFECYCLE_RECOVERY
    }

    private data class ThrottleState(
        var lastPrintAtMillis: Long = 0L,
        var suppressed: Long = 0L
    )

    fun config(): BeanConsoleConfig {
        return rawConfig().normalized()
    }

    fun verbosity(): String {
        return config().verbosity
    }

    fun isQuiet(): Boolean {
        return verbosity() == "quiet"
    }

    fun isVerboseOrDebug(): Boolean {
        return verbosity() == "verbose" || verbosity() == "debug"
    }

    fun shouldPrint(category: Category): Boolean {
        val config = config()
        return when (config.verbosity) {
            "quiet" -> false
            "debug" -> true
            "verbose" -> category != Category.PLAYER_CHAT || config.showPlayerChat
            else -> categoryEnabledBySwitch(config, category)
        }
    }

    fun playerJoinLeaveEnabled(): Boolean {
        return shouldPrint(Category.PLAYER_JOIN_LEAVE)
    }

    fun playerChatEnabled(): Boolean {
        return shouldPrint(Category.PLAYER_CHAT)
    }

    fun lowLatencySummaryEnabled(): Boolean {
        return shouldPrint(Category.LOW_LATENCY_SUMMARY)
    }

    fun lowLatencySummaryHotPathEnabled(): Boolean {
        val config = rawConfig()
        val cached = cachedLowLatencySummary
        if (cached.config === config) {
            return cached.enabled
        }

        return lowLatencySummaryEnabled(config).also { enabled ->
            cachedLowLatencySummary = LowLatencySummaryCache(config, enabled)
        }
    }

    fun survivalRepairEnabled(): Boolean {
        return shouldPrint(Category.SURVIVAL_REPAIR)
    }

    fun rankedCompatibilityEnabled(): Boolean {
        return shouldPrint(Category.RANKED_COMPATIBILITY)
    }

    fun print(category: Category, message: String, vararg params: Any): Boolean {
        if (!shouldPrint(category)) {
            return false
        }
        Log.clog(message, *params)
        return true
    }

    fun printThrottled(
        category: Category,
        key: String,
        message: String,
        minIntervalMillis: Long = defaultThrottleMillis(),
        vararg params: Any
    ): Boolean {
        if (!shouldPrint(category)) {
            return false
        }
        val interval = effectiveThrottleMillis(minIntervalMillis)
        if (interval <= 0L) {
            Log.clog(message, *params)
            return true
        }

        val now = System.currentTimeMillis()
        val state = throttleStates.computeIfAbsent("${category.name}:$key") { ThrottleState() }
        synchronized(state) {
            val last = state.lastPrintAtMillis
            if (last > 0L && now - last < interval) {
                state.suppressed++
                throttleSuppressedTotal.incrementAndGet()
                return false
            }

            val suppressed = state.suppressed
            state.suppressed = 0L
            state.lastPrintAtMillis = now
            val effectiveMessage = if (suppressed > 0L) {
                "$message (suppressed=$suppressed)"
            } else {
                message
            }
            Log.clog(effectiveMessage, *params)
            return true
        }
    }

    fun buildDiagnostics(): List<String> {
        val config = config()
        return listOf(
            "console.verbosity=${config.verbosity}",
            "console.showPlayerJoinLeave=${config.showPlayerJoinLeave}",
            "console.showPlayerChat=${config.showPlayerChat}",
            "console.showLowLatencySummary=${config.showLowLatencySummary}",
            "console.showSurvivalRepair=${config.showSurvivalRepair}",
            "console.showRankedCompatibility=${config.showRankedCompatibility}",
            "console.effectivePlayerJoinLeave=${shouldPrint(Category.PLAYER_JOIN_LEAVE)}",
            "console.effectivePlayerChat=${shouldPrint(Category.PLAYER_CHAT)}",
            "console.effectiveLowLatencySummary=${shouldPrint(Category.LOW_LATENCY_SUMMARY)}",
            "console.effectiveSurvivalRepair=${shouldPrint(Category.SURVIVAL_REPAIR)}",
            "console.effectiveRankedCompatibility=${shouldPrint(Category.RANKED_COMPATIBILITY)}",
            "console.effectiveLifecycleRecovery=${shouldPrint(Category.LIFECYCLE_RECOVERY)}",
            "console.throttleKeys=${throttleStates.size}",
            "console.throttleSuppressedTotal=${throttleSuppressedTotal.get()}",
            "console.throttlePendingSuppressed=${pendingSuppressedCount()}"
        )
    }

    private fun categoryEnabledBySwitch(config: BeanConsoleConfig, category: Category): Boolean {
        return when (category) {
            Category.PLAYER_JOIN_LEAVE -> config.showPlayerJoinLeave
            Category.PLAYER_CHAT -> config.showPlayerChat
            Category.LOW_LATENCY_SUMMARY -> config.showLowLatencySummary
            Category.SURVIVAL_REPAIR -> config.showSurvivalRepair
            Category.RANKED_COMPATIBILITY -> config.showRankedCompatibility
            Category.LIFECYCLE_RECOVERY -> true
        }
    }

    private fun lowLatencySummaryEnabled(config: BeanConsoleConfig): Boolean {
        return when (BeanConsoleConfig.normalizeVerbosity(config.verbosity)) {
            "quiet" -> false
            "debug" -> true
            "verbose" -> true
            else -> config.showLowLatencySummary
        }
    }

    private fun rawConfig(): BeanConsoleConfig {
        return try {
            Data.config.console
        } catch (_: Throwable) {
            fallbackConfig
        }
    }

    private fun defaultThrottleMillis(): Long {
        return when (verbosity()) {
            "debug" -> 0L
            "verbose" -> 15_000L
            else -> 60_000L
        }
    }

    private fun effectiveThrottleMillis(requestedMillis: Long): Long {
        val requested = requestedMillis.coerceAtLeast(0L)
        return when (verbosity()) {
            "debug" -> 0L
            "verbose" -> requested.coerceAtMost(15_000L)
            else -> requested.coerceAtLeast(30_000L)
        }
    }

    private fun pendingSuppressedCount(): Long {
        return throttleStates.values.sumOf { it.suppressed }
    }
}
