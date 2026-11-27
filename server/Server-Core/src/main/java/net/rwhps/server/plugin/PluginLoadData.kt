/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import java.io.File
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

enum class PluginRuntimeState {
    LOADED,
    ENABLED,
    ACTIVE,
    FAILED,
    DISABLING,
    DISABLED
}

/**
 * @author Dr (dr@der.kim)
 */
class PluginLoadData(
    @JvmField
    val name: String,
    @JvmField
    val internalName: String,
    @JvmField
    val author: String,
    @JvmField
    val description: String,
    @JvmField
    val version: String,
    @JvmField
    val main: Plugin,
    private val mkdir: Boolean = true,
    private val skip: Boolean = false,
    internal val classLoader: URLClassLoader? = null,
    @JvmField
    val apiVersion: Int = 1,
    @JvmField
    val capabilities: Set<String> = emptySet(),
    /** Plugin root used by this load. Null keeps the normal server data path. */
    internal val pluginDirectory: File? = null
) {
    private val disableStarted = AtomicBoolean(false)

    internal var runtimeMetrics: PluginRuntimeMetrics? = null

    @Volatile
    var state: PluginRuntimeState = PluginRuntimeState.LOADED
        internal set

    @Volatile
    var failurePhase: String? = null
        internal set

    @Volatile
    var failureMessage: String? = null
        internal set

    @Volatile
    var lastPhase: String? = null
        internal set

    @Volatile
    var lastPhaseDurationNanos: Long = 0L
        internal set

    internal val isBuiltInPlugin: Boolean
        get() = skip

    init {
        if (mkdir) {
            val root = pluginDirectory ?: PluginRuntimeSettings.pluginDirectory()
            main.pluginDataFileUtils = FileUtils(root, true).toFolder("data").toFolder(this.internalName)
        }
    }

    internal fun recordSuccess(phase: String, durationNanos: Long, nextState: PluginRuntimeState?) {
        lastPhase = phase
        lastPhaseDurationNanos = durationNanos.coerceAtLeast(0L)
        if (nextState != null) {
            state = nextState
        }
    }

    internal fun recordFailure(phase: String, error: Throwable, durationNanos: Long = 0L) {
        lastPhase = phase
        lastPhaseDurationNanos = durationNanos.coerceAtLeast(0L)
        failurePhase = phase
        failureMessage = error.message?.take(240)?.ifBlank { error.javaClass.simpleName } ?: error.javaClass.simpleName
        state = PluginRuntimeState.FAILED
        PluginDiagnostics.record(
            internalName,
            phase,
            failureMessage ?: error.javaClass.simpleName,
            "检查插件生命周期回调和依赖；修复后重启开发实例或重新审批生产插件"
        )
        PluginDiagnostics.writeReport()
    }

    internal fun isRunnable(): Boolean {
        return state != PluginRuntimeState.FAILED &&
            state != PluginRuntimeState.DISABLING &&
            state != PluginRuntimeState.DISABLED
    }

    internal fun tryBeginDisable(): Boolean {
        val started = disableStarted.compareAndSet(false, true)
        if (started) {
            state = PluginRuntimeState.DISABLING
        }
        return started
    }

    internal fun markDisabled(durationNanos: Long) {
        lastPhase = "onDisable"
        lastPhaseDurationNanos = durationNanos.coerceAtLeast(0L)
        state = PluginRuntimeState.DISABLED
    }

    internal fun closeClassLoader() {
        classLoader?.close()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return if (other == null || javaClass != other.javaClass) {
            false
        } else internalName.equals((other as PluginLoadData).internalName, ignoreCase = true)
    }

    override fun hashCode(): Int {
        return Objects.hash(internalName.lowercase(Locale.ROOT))
    }
}
