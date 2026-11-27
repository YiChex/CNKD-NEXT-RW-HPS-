package net.rwhps.server.plugin.v2

import net.rwhps.server.data.bean.BeanPluginLoggingConfig
import net.rwhps.server.plugin.PluginRuntimeMetrics
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Server-owned V2 plugin log sink. It never delegates normal log records to
 * [net.rwhps.server.util.log.Log], so normal plugin output cannot re-enter the
 * legacy ServerConsolePrintEvent pipeline.
 */
internal class PluginLogService(
    private val logDirectory: Path,
    private val configProvider: () -> BeanPluginLoggingConfig,
    private val coreLogLevelProvider: () -> String,
    private val consoleSink: (String) -> Unit,
    private val failureSink: (String) -> Unit = consoleSink,
    private val clock: () -> Long = System::currentTimeMillis
) : PluginLogSink, AutoCloseable {
    private val accepting = AtomicBoolean(true)
    private val writerStarted = AtomicBoolean(false)
    private val fileQueue = ArrayBlockingQueue<FileRecord>(FILE_QUEUE_CAPACITY)
    private val pluginWindows = java.util.concurrent.ConcurrentHashMap<String, RateWindow>()
    private val rotationSequence = AtomicLong()
    private val nextCleanupAtMillis = AtomicLong(0L)
    private val lastFailureReportAtMillis = AtomicLong(0L)

    private val consoleLines = AtomicLong()
    private val fileLinesQueued = AtomicLong()
    private val suppressedInfoLines = AtomicLong()
    private val truncatedLines = AtomicLong()
    private val fileQueueDrops = AtomicLong()
    private val fileFailures = AtomicLong()
    private val lastFileWriteAtMillis = AtomicLong(0L)

    @Volatile
    private var lastFileFailure = "none"

    @Volatile
    private var lastFileFailureAtMillis = 0L

    @Volatile
    private var writerThread: Thread? = null

    override fun info(pluginId: String, message: String, metrics: PluginRuntimeMetrics?) {
        log(PluginLogLevel.INFO, pluginId, message, null, metrics)
    }

    override fun warn(pluginId: String, message: String, metrics: PluginRuntimeMetrics?) {
        log(PluginLogLevel.WARN, pluginId, message, null, metrics)
    }

    override fun error(pluginId: String, message: String, error: Throwable?, metrics: PluginRuntimeMetrics?) {
        log(PluginLogLevel.ERROR, pluginId, message, error, metrics)
    }

    fun diagnostics(): PluginLogDiagnostics {
        return PluginLogDiagnostics(
            accepting = accepting.get(),
            logDirectory = logDirectory.toAbsolutePath().normalize().toString(),
            activePluginWindows = pluginWindows.size,
            consoleLines = consoleLines.get(),
            fileLinesQueued = fileLinesQueued.get(),
            suppressedInfoLines = suppressedInfoLines.get(),
            truncatedLines = truncatedLines.get(),
            fileQueueDrops = fileQueueDrops.get(),
            fileFailures = fileFailures.get(),
            fileQueueDepth = fileQueue.size,
            lastFileWriteAtMillis = lastFileWriteAtMillis.get(),
            lastFileFailure = lastFileFailure,
            lastFileFailureAtMillis = lastFileFailureAtMillis
        )
    }

    override fun close() {
        if (!accepting.compareAndSet(true, false)) {
            return
        }
        val worker = writerThread ?: return
        if (worker !== Thread.currentThread()) {
            worker.join(SHUTDOWN_FLUSH_TIMEOUT_MILLIS)
        }
    }

    private fun log(
        level: PluginLogLevel,
        rawPluginId: String,
        rawMessage: String,
        error: Throwable?,
        metrics: PluginRuntimeMetrics?
    ) {
        if (!accepting.get()) {
            return
        }

        val config = configProvider().normalized()
        val forcedError = error != null
        if (!forcedError && coreLogLevelProvider().trim().equals(BeanPluginLoggingConfig.LEVEL_OFF, ignoreCase = true)) {
            return
        }

        val pluginId = normalizePluginId(rawPluginId)
        if (level == PluginLogLevel.INFO) {
            when (val decision = acquireInfoSlot(pluginId, config.infoLinesPerMinutePerPlugin, clock())) {
                is InfoDecision.Suppressed -> {
                    suppressedInfoLines.incrementAndGet()
                    metrics?.recordPluginLogSuppressed()
                    return
                }
                is InfoDecision.Accepted -> {
                    if (decision.previousSuppressed > 0L) {
                        emit(
                            level = PluginLogLevel.WARN,
                            pluginId = pluginId,
                            message = "INFO logs suppressed=${decision.previousSuppressed} in the previous 60s window",
                            config = config,
                            forceOutput = false,
                            metrics = metrics
                        )
                    }
                }
            }
        }

        val source = if (error == null) rawMessage else "$rawMessage | ${stackTrace(error)}"
        val message = sanitizeMessage(source, config.maxMessageLength)
        if (message.truncated) {
            truncatedLines.incrementAndGet()
            metrics?.recordPluginLogTruncated()
        }
        emit(level, pluginId, message.value, config, forcedError, metrics)
    }

    private fun emit(
        level: PluginLogLevel,
        pluginId: String,
        message: String,
        config: BeanPluginLoggingConfig,
        forceOutput: Boolean,
        metrics: PluginRuntimeMetrics?
    ) {
        val now = clock()
        val line = formatLine(now, Thread.currentThread().name, level, pluginId, message)
        var emitted = false

        if (forceOutput || allows(config.consoleLevel, level)) {
            try {
                consoleSink(line)
                consoleLines.incrementAndGet()
                emitted = true
            } catch (error: Throwable) {
                reportFailure("console output", error)
            }
        }

        if (forceOutput || allows(config.fileLevel, level)) {
            emitted = enqueue(FileRecord(line, level, pluginId, config.maxFileSizeMb, config.retainDays, now, metrics)) || emitted
        }

        if (emitted) {
            metrics?.recordPluginLogEmission()
        }
    }

    private fun enqueue(record: FileRecord): Boolean {
        if (fileQueue.offer(record)) {
            fileLinesQueued.incrementAndGet()
            startWriterIfNeeded()
            return true
        }

        // INFO is disposable under file backpressure. WARN and ERROR may evict one
        // queued INFO record, keeping fault evidence available without blocking a game callback.
        if (record.level != PluginLogLevel.INFO && evictQueuedInfoRecord() && fileQueue.offer(record)) {
            fileLinesQueued.incrementAndGet()
            startWriterIfNeeded()
            return true
        }

        recordFileQueueDrop(record)
        return false
    }

    private fun evictQueuedInfoRecord(): Boolean {
        val iterator = fileQueue.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.level == PluginLogLevel.INFO) {
                iterator.remove()
                recordFileQueueDrop(queued)
                return true
            }
        }
        return false
    }

    private fun recordFileQueueDrop(record: FileRecord) {
        fileQueueDrops.incrementAndGet()
        record.metrics?.recordPluginLogFileQueueDrop()
    }

    private fun startWriterIfNeeded() {
        if (!writerStarted.compareAndSet(false, true)) {
            return
        }
        writerThread = thread(
            start = true,
            isDaemon = true,
            name = "PluginLogWriter"
        ) {
            try {
                while (accepting.get() || fileQueue.isNotEmpty()) {
                    val record = fileQueue.poll(FILE_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                    writeRecord(record)
                }
            } finally {
                writerStarted.set(false)
            }
        }
    }

    private fun writeRecord(record: FileRecord) {
        try {
            val root = resolveLogDirectory()
            val active = activeFile(root, record.pluginId)
            val data = (record.line + System.lineSeparator()).toByteArray(StandardCharsets.UTF_8)
            val maxBytes = record.maxFileSizeMb.toLong() * BYTES_PER_MEGABYTE
            if (Files.exists(active) && Files.size(active) > 0L && Files.size(active) + data.size > maxBytes) {
                rotate(active, root, record.pluginId, record.timestampMillis)
            }
            Files.write(active, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            lastFileWriteAtMillis.set(clock())
            cleanupExpired(root, record.retainDays, record.timestampMillis)
        } catch (error: Throwable) {
            fileFailures.incrementAndGet()
            record.metrics?.recordPluginLogFileFailure()
            lastFileFailure = sanitizeMessage(
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                FAILURE_SUMMARY_MAX_LENGTH
            ).value
            lastFileFailureAtMillis = clock()
            reportFailure("file output", error)
        }
    }

    private fun resolveLogDirectory(): Path {
        Files.createDirectories(logDirectory)
        return logDirectory.toFile().canonicalFile.toPath()
    }

    private fun activeFile(root: Path, pluginId: String): Path {
        val active = root.resolve("$pluginId.log").normalize()
        check(active.parent == root) { "Plugin log path escaped its configured directory" }
        return active
    }

    private fun rotate(active: Path, root: Path, pluginId: String, timestampMillis: Long) {
        val timestamp = ROTATION_TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMillis))
        val rotated = root.resolve("$pluginId-$timestamp-${rotationSequence.incrementAndGet()}.log").normalize()
        check(rotated.parent == root) { "Plugin rotation path escaped its configured directory" }
        Files.move(active, rotated)
    }

    private fun cleanupExpired(root: Path, retainDays: Int, nowMillis: Long) {
        val scheduledAt = nextCleanupAtMillis.get()
        if (nowMillis < scheduledAt || !nextCleanupAtMillis.compareAndSet(scheduledAt, nowMillis + CLEANUP_INTERVAL_MILLIS)) {
            return
        }

        val cutoffMillis = nowMillis - retainDays.toLong() * MILLIS_PER_DAY
        Files.list(root).use { paths ->
            paths.forEach { path ->
                if (
                    Files.isRegularFile(path) &&
                    path.fileName.toString().endsWith(".log") &&
                    Files.getLastModifiedTime(path).toMillis() in 1 until cutoffMillis
                ) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun acquireInfoSlot(pluginId: String, limit: Int, nowMillis: Long): InfoDecision {
        val window = pluginWindows.computeIfAbsent(pluginId) { RateWindow(nowMillis) }
        synchronized(window) {
            var previousSuppressed = 0L
            if (nowMillis - window.startedAtMillis >= MILLIS_PER_MINUTE) {
                previousSuppressed = window.suppressed
                window.startedAtMillis = nowMillis
                window.accepted = 0
                window.suppressed = 0L
            }
            if (window.accepted >= limit) {
                window.suppressed++
                return InfoDecision.Suppressed
            }
            window.accepted++
            return InfoDecision.Accepted(previousSuppressed)
        }
    }

    private fun reportFailure(operation: String, error: Throwable) {
        val now = clock()
        val previous = lastFailureReportAtMillis.get()
        if (now - previous < FAILURE_REPORT_INTERVAL_MILLIS || !lastFailureReportAtMillis.compareAndSet(previous, now)) {
            return
        }
        try {
            failureSink("[PluginLog] $operation failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        } catch (_: Throwable) {
            // Do not let an unavailable console sink affect plugin callbacks.
        }
    }

    private fun allows(configuredLevel: String, messageLevel: PluginLogLevel): Boolean {
        return when (configuredLevel) {
            BeanPluginLoggingConfig.LEVEL_INFO -> true
            BeanPluginLoggingConfig.LEVEL_WARN -> messageLevel != PluginLogLevel.INFO
            BeanPluginLoggingConfig.LEVEL_ERROR -> messageLevel == PluginLogLevel.ERROR
            else -> false
        }
    }

    private fun formatLine(timestampMillis: Long, threadName: String, level: PluginLogLevel, pluginId: String, message: String): String {
        val timestamp = LINE_TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMillis))
        return "[$timestamp][PLUGIN][${level.name}][$threadName][$pluginId] $message"
    }

    private fun normalizePluginId(rawPluginId: String): String {
        val normalized = buildString {
            rawPluginId.trim().take(MAX_PLUGIN_ID_LENGTH).forEach { character ->
                append(if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') character else '_')
            }
        }.trim('_', '.', '-')
        return normalized.ifBlank { "unknown" }
    }

    private fun sanitizeMessage(rawMessage: String, maxLength: Int): SanitizedMessage {
        val withoutAnsi = ANSI_ESCAPE_REGEX.replace(rawMessage, "")
        val oneLine = buildString(withoutAnsi.length) {
            withoutAnsi.forEach { character ->
                when {
                    character == '\r' || character == '\n' -> {
                        if (isEmpty() || last() != ' ') {
                            append(' ')
                        }
                    }
                    Character.isISOControl(character) -> Unit
                    else -> append(character)
                }
            }
        }.trim().ifBlank { "(empty)" }
        if (oneLine.length <= maxLength) {
            return SanitizedMessage(oneLine, false)
        }
        val prefixLength = (maxLength - TRUNCATION_SUFFIX.length).coerceAtLeast(0)
        return SanitizedMessage(oneLine.take(prefixLength) + TRUNCATION_SUFFIX, true)
    }

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private data class FileRecord(
        val line: String,
        val level: PluginLogLevel,
        val pluginId: String,
        val maxFileSizeMb: Int,
        val retainDays: Int,
        val timestampMillis: Long,
        val metrics: PluginRuntimeMetrics?
    )

    private data class RateWindow(
        var startedAtMillis: Long,
        var accepted: Int = 0,
        var suppressed: Long = 0L
    )

    private sealed interface InfoDecision {
        data class Accepted(val previousSuppressed: Long): InfoDecision
        data object Suppressed: InfoDecision
    }

    private data class SanitizedMessage(
        val value: String,
        val truncated: Boolean
    )

    companion object {
        private const val FILE_QUEUE_CAPACITY = 512
        private const val FILE_POLL_INTERVAL_MILLIS = 200L
        private const val SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 2_000L
        private const val FAILURE_REPORT_INTERVAL_MILLIS = 60_000L
        private const val CLEANUP_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
        private const val MAX_PLUGIN_ID_LENGTH = 64
        private const val FAILURE_SUMMARY_MAX_LENGTH = 256
        private const val TRUNCATION_SUFFIX = " [truncated]"
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val LINE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        private val ROTATION_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
    }
}

internal data class PluginLogDiagnostics(
    val accepting: Boolean,
    val logDirectory: String,
    val activePluginWindows: Int,
    val consoleLines: Long,
    val fileLinesQueued: Long,
    val suppressedInfoLines: Long,
    val truncatedLines: Long,
    val fileQueueDrops: Long,
    val fileFailures: Long,
    val fileQueueDepth: Int,
    val lastFileWriteAtMillis: Long,
    val lastFileFailure: String,
    val lastFileFailureAtMillis: Long
)

private enum class PluginLogLevel {
    INFO,
    WARN,
    ERROR
}
