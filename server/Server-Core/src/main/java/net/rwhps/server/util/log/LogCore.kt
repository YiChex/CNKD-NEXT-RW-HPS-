/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.event.global.ServerConsolePrintEvent
import net.rwhps.server.util.Time.getMilliFormat
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.inline.ifNullResult
import net.rwhps.server.util.log.ColorCodes.formatColors
import net.rwhps.server.util.log.Log.skipping
import net.rwhps.server.util.log.exp.ExceptionX
import net.rwhps.server.util.str.Parser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Log 的核心实现
 *
 * @date 2024/6/23 上午1:19
 * @author Dr (dr@der.kim)
 */
abstract class LogCore {
    /* Log level defaults to WARN */
    private var logGrade = Logg.WARN
    private val errorLogLock = Any()
    private val activeErrorLogFile: File
    private var outLog: FileOutputStream
    private var logClosed = false

    /**
     * R1: WARN lines dropped from disk because the WARN share of the active file was exhausted.
     * Surfaced on rotation so the loss is never silent.
     */
    private var skippedWarnSinceRotate = 0L

    companion object {
        private val EMPTY_LINE_REGEX = Regex("(?m)^\\s*$(\\n|\\r\\n)")
        private val ANSI_ESCAPE_REGEX = Regex("\\e\\[[\\d;]*[^\\d;]")
        private const val DEFAULT_ERROR_LOG_MAX_SIZE_MB = 8
        private const val DEFAULT_ERROR_LOG_RETAIN_FILES = 3
        private const val MIN_ERROR_LOG_MAX_SIZE_MB = 1
        private const val MAX_ERROR_LOG_MAX_SIZE_MB = 64
        private const val MAX_ERROR_LOG_RETAIN_FILES = 10
    }

    init {
        activeErrorLogFile = FileUtils.getFolder(Data.ServerLogPath).toFile("Log.txt").file
        if (activeErrorLogFile.length() >= errorLogMaxSizeBytes()) {
            runCatching { rotateExistingErrorLogFiles(errorLogRetainFiles()) }
                .onFailure { System.err.println("[Log] Failed to rotate oversized startup error log: ${it.message}") }
        }
        outLog = FileOutputStream(activeErrorLogFile, true)
    }

    fun set(log: String) {
        logGrade = Logg.from(log)
    }

    fun saveLog() {
        synchronized(errorLogLock) {
            outLog.flush()
        }
    }

    /**
     * P3-10: close the error-log handle on exit.
     *
     * [saveLog] only flushed, and `close()` was reachable only from the log-rotation path,
     * so on exit the `FileOutputStream` (and its file handle) was never released. This is
     * idempotent and safe to call twice: `close()` on an already-closed stream is a no-op,
     * and the flag stops a later `writeErrorLog` from writing to a closed stream (which
     * would otherwise surface as a spurious "[Log] Failed to write core error log" line
     * during shutdown).
     */
    fun closeLog() {
        synchronized(errorLogLock) {
            if (logClosed) {
                return
            }
            logClosed = true
            runCatching {
                outLog.flush()
                outLog.close()
            }
        }
    }

    /**
     * WLog：
     * @param i Warning level -INT
     * @param tag Title / Default empty
     * @param eIn Info
     * i>=Set the level to write to the file
     */
    protected fun logs(i: Logg, tag: Any, eIn: Any? = null) {
        val errorFlag = (tag is Throwable || eIn is Throwable)
        if (logGrade.level > i.level && !errorFlag) {
            return
        }

        /*
         * P0-STALL observability: `Log.txt` used to receive a line only when a Throwable was
         * involved, so `Log.warn(...)` and `Log.error("plain string")` never reached disk and
         * production incidents could only be diagnosed from the supervisor's stdout capture.
         *
         * Persist WARN and above regardless of how the message was constructed. Note that this
         * enum is NOT monotonic: FATAL is 7 while WARN is 5, and OFF(99)/CONSOLE(10) are
         * sentinels rather than severities -- hence the explicit sentinel exclusions instead of
         * a plain `level >= WARN` comparison.
         *
         * `errorFlag` is deliberately left untouched: it still controls the level-filter bypass
         * above, which must keep working exactly as before.
         *
         * ⚠️ R1 (found by independent regression audit): widening persistence means WARN volume now
         * shares the rotation budget. Several per-packet WARN sites are not rate-limited and are
         * remotely triggerable (malformed SERVER_INFO, and one line per disconnected player when
         * broadcasting), so a chatty client could push genuine ERRORs out of the retained window --
         * measured as shrinking retention from days to minutes at 60+ lines/s. `warnBudget` below
         * caps how much of the file WARN may consume, so ERROR retention is protected while the
         * observability win is kept.
         */
        /*
         * R1: budget applies by LEVEL, not by whether a Throwable was attached.
         *
         * The flagged per-packet sites are `Log.warn(message, error)` -- they carry a Throwable, so
         * a rule keyed on `errorFlag` would have left exactly the risky call sites uncapped. What
         * matters for protecting ERROR retention is the severity: WARN is bounded, ERROR/FATAL are
         * not. (WARN lines still always reach stdout.)
         */
        val isWarnOnly = i == Logg.WARN
        val persistFlag = errorFlag ||
            (i != Logg.OFF && i != Logg.CONSOLE && i.level >= Logg.WARN.level)

        val lines = (if (tag is Throwable) {
            ExceptionX.resolveTrace(tag)
        } else if (eIn is Throwable) {
            ExceptionX.resolveTrace(eIn)
        } else {
            eIn.ifNullResult("") { it.toString() }
        }).split(Data.LINE_SEPARATOR).toTypedArray()

        val printData = StringBuilder()

        // [Time][Log-Leve]
        printData.append("[").append(getMilliFormat(1)).append("]").append(i.tag).append("&b[\$ThreadName]&fr")
        // [Time][Log-Leve][Tag]
        if (tag !is Throwable && tag != "") {
            printData.append(" ").append(tag)
        }
        // [Time] [Log-Leve] [Tag] :
        if (lines[0] != "") {
            printData.append(": ")
        }

        var flag = false
        // 避免换行
        if (lines.size > 1) {
            printData.append(Data.LINE_SEPARATOR)
            for (line in lines) {
                if (line.trim().replace(EMPTY_LINE_REGEX, "").isNotEmpty()) {
                    if (flag) {
                        printData.append(Data.LINE_SEPARATOR)
                    }
                    printData.append(line)
                    flag = true
                }
            }
        } else {
            printData.append(lines[0])
        }

        printData.append("&fr")

        print(persistFlag, formatColors(Parser.parseStr(printData.toString())), isWarnOnly)
    }

    private fun print(persist: Boolean, text: String, warnOnly: Boolean = false) {
        println(text)

        PluginManage.runGlobalEventManage(ServerConsolePrintEvent(text))

        if (persist) {
            writeErrorLog(text, warnOnly)
        }
    }

    private fun writeErrorLog(text: String, warnOnly: Boolean = false) {
        val bytes = "${text.replace(ANSI_ESCAPE_REGEX, "")}${Data.LINE_SEPARATOR}".toByteArray()
        synchronized(errorLogLock) {
            if (logClosed) {
                return
            }
            try {
                /*
                 * R1: cap the WARN share of the file so ERROR retention is not evicted by
                 * remotely-triggerable, un-rate-limited WARN lines. ERROR/FATAL keep writing
                 * unconditionally, so they are never dropped -- only WARN is bounded.
                 *
                 * WARN is still always echoed to stdout (see `println` above), so nothing is
                 * hidden from the supervisor; it just cannot consume the whole rotation budget.
                 */
                val size = activeErrorLogFile.length()
                val warnBudgetExceeded = warnOnly && size >= warnBudgetBytes()
                if (warnBudgetExceeded) {
                    skippedWarnSinceRotate++
                    return
                }
                if (size > 0L && size + bytes.size > errorLogMaxSizeBytes()) {
                    rotateActiveErrorLog()
                }
                outLog.write(bytes)
                outLog.flush()
            } catch (error: Exception) {
                // Logging must never feed a second exception back through Log.error.
                System.err.println("[Log] Failed to write core error log: ${error.message}")
            }
        }
    }

    /**
     * How much of the active log file WARN-level lines may occupy before they are dropped from
     * disk (stdout is unaffected). ERROR and above ignore this budget.
     */
    private fun warnBudgetBytes(): Long = errorLogMaxSizeBytes() / 2L

    private fun rotateActiveErrorLog() {
        runCatching {
            outLog.flush()
            outLog.close()
        }
        // R1: make dropped WARN lines visible rather than silent. Written to stderr, not to the
        // log file, so it cannot itself consume the budget it is reporting on.
        if (skippedWarnSinceRotate > 0L) {
            System.err.println(
                "[Log] WARN budget reached; $skippedWarnSinceRotate WARN line(s) were shown on " +
                    "stdout but not persisted (ERROR/FATAL are never dropped)"
            )
            skippedWarnSinceRotate = 0L
        }
        try {
            rotateExistingErrorLogFiles(errorLogRetainFiles())
        } finally {
            outLog = FileOutputStream(activeErrorLogFile, false)
        }
    }

    private fun rotateExistingErrorLogFiles(retainFiles: Int) {
        val parent = activeErrorLogFile.parentFile ?: return
        val normalizedRetain = retainFiles.coerceIn(0, MAX_ERROR_LOG_RETAIN_FILES)
        if (normalizedRetain == 0) {
            Files.deleteIfExists(activeErrorLogFile.toPath())
            return
        }

        for (index in normalizedRetain downTo 2) {
            val source = File(parent, "${activeErrorLogFile.name}.${index - 1}")
            val target = File(parent, "${activeErrorLogFile.name}.$index")
            if (source.exists()) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        if (activeErrorLogFile.exists()) {
            val firstArchive = File(parent, "${activeErrorLogFile.name}.1")
            Files.move(activeErrorLogFile.toPath(), firstArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun errorLogMaxSizeBytes(): Long {
        val configuredMb = runCatching { Data.config.errorLogMaxSizeMb }.getOrDefault(DEFAULT_ERROR_LOG_MAX_SIZE_MB)
            .coerceIn(MIN_ERROR_LOG_MAX_SIZE_MB, MAX_ERROR_LOG_MAX_SIZE_MB)
        return configuredMb.toLong() * 1024L * 1024L
    }

    private fun errorLogRetainFiles(): Int {
        return runCatching { Data.config.errorLogRetainFiles }.getOrDefault(DEFAULT_ERROR_LOG_RETAIN_FILES)
            .coerceIn(0, MAX_ERROR_LOG_RETAIN_FILES)
    }

    protected enum class Logg(
        val level: Int,
        val tag: String,
    ) {
        /* ALL during development */
        OFF(99 ,""),
        CONSOLE(10 ,""),
        FATAL(7,"${ColorStructure(backGroundRgb = intArrayOf(255, 0, 0))}[FATAL]&fr"),
        ERROR(6,"&r[ERROR]&fr"),
        WARN(5,"&y[WARN]&fr"),
        INFO(4,"&g[INFO]&fr"),
        DEBUG(3,"${ColorStructure(foreGroundRgb = intArrayOf(128, 128, 128))}[DEBUG]&fr"),
        TRACK(2,"${ColorStructure(foreGroundRgb = intArrayOf(128, 128, 128))}[TRACK]&fr"),
        ALL(1,"[All]");

        companion object {
            fun from(type: String): Logg = entries.find { it.name == type || it.name.lowercase() == type.lowercase() }.ifNullResult({
                skipping("Log Level Set Error , In: $type")
                ALL
            }) { it }
        }
    }
}
