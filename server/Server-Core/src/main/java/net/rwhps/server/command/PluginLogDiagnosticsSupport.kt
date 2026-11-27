package net.rwhps.server.command

import net.rwhps.server.data.global.Data
import net.rwhps.server.plugin.PluginLoadData
import net.rwhps.server.plugin.PluginRuntimeMetrics
import net.rwhps.server.plugin.v2.V2PluginLogRuntime
import net.rwhps.server.util.file.plugin.PluginManage

/** Read-only operational report for the server-owned V2 plugin log pipeline. */
internal object PluginLogDiagnosticsSupport {
    fun buildReport(): List<String> {
        val config = Data.config.pluginLogging.normalized()
        val aggregate = V2LogMetrics()
        PluginManage.run { plugin: PluginLoadData ->
            if (plugin.apiVersion == 2) {
                aggregate.pluginCount++
                plugin.runtimeMetrics?.let(aggregate::add)
            }
        }

        return buildList {
            add("pluginLogging.config console=${config.consoleLevel} file=${config.fileLevel} infoRate=${config.infoLinesPerMinutePerPlugin}/min")
            add("pluginLogging.limits message=${config.maxMessageLength} chars file=${config.maxFileSizeMb}MB retain=${config.retainDays}d")
            add(
                "pluginLogging.v2 plugins=${aggregate.pluginCount} emitted=${aggregate.emissions} " +
                    "suppressed=${aggregate.suppressed} truncated=${aggregate.truncated} " +
                    "queueDrops=${aggregate.queueDrops} fileFailures=${aggregate.fileFailures}"
            )
            V2PluginLogRuntime.diagnostics()?.let { diagnostics ->
                add(
                    "pluginLogging.runtime accepting=${diagnostics.accepting} activeWindows=${diagnostics.activePluginWindows} " +
                        "consoleLines=${diagnostics.consoleLines} fileQueued=${diagnostics.fileLinesQueued} " +
                        "queueDepth=${diagnostics.fileQueueDepth} queueDrops=${diagnostics.fileQueueDrops} " +
                        "fileFailures=${diagnostics.fileFailures} lastWriteAt=${diagnostics.lastFileWriteAtMillis}"
                )
                add("pluginLogging.lastFileFailure at=${diagnostics.lastFileFailureAtMillis} detail=${diagnostics.lastFileFailure}")
                add("pluginLogging.path=${diagnostics.logDirectory}")
            } ?: add("pluginLogging.runtime inactive")
            add("pluginLogging.note V2 context.logger is independent from common.log except common.log=OFF")
        }
    }

    private class V2LogMetrics {
        var pluginCount = 0
        var emissions = 0L
        var suppressed = 0L
        var truncated = 0L
        var queueDrops = 0L
        var fileFailures = 0L

        fun add(metrics: PluginRuntimeMetrics) {
            emissions += metrics.pluginLogEmissionCount()
            suppressed += metrics.pluginLogSuppressedCount()
            truncated += metrics.pluginLogTruncatedCount()
            queueDrops += metrics.pluginLogFileQueueDropCount()
            fileFailures += metrics.pluginLogFileFailureCount()
        }
    }
}
