/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.core.thread.Threads.close
import net.rwhps.server.core.thread.Threads.runSavePool
import net.rwhps.server.data.global.Data
import net.rwhps.server.net.Administration
import net.rwhps.server.net.manage.HttpRequestManage
import net.rwhps.server.net.manage.MyOkHttpRetryInterceptor
import net.rwhps.server.util.algorithms.digest.DigestUtils
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.plugin.PluginData
import net.rwhps.server.util.file.plugin.PluginManage.runOnDisable
import net.rwhps.server.util.file.plugin.serializer.SerializersEnum
import net.rwhps.server.util.file.plugin.value
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.math.RandomUtils.getRandomString
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Core application state.
 *
 * @author Dr (dr@der.kim)
 */
class Application {
    companion object {
        /**
         * Fingerprint of the historical template Settings.bin that was mistakenly shipped
         * inside release archives. When detected, the file is deleted so every deployment
         * can regenerate a unique instance identity on first boot.
         */
        private const val TEMPLATE_SETTINGS_MD5 = "A6CA7F788FEBDC9577CE51345B618DB4"
    }

    private val saveStarted = AtomicBoolean(false)

    private val settingsFileUtil = FileUtils.getFolder(Data.ServerDataPath).toFile("Settings.bin").also { fileUtil ->
        runCatching {
            if (fileUtil.exists() && fileUtil.length() > 0L && fileUtil.md5.equals(TEMPLATE_SETTINGS_MD5, ignoreCase = true)) {
                fileUtil.delete()
                Log.warn("[StartMode] Detected shared template Settings.bin. Regenerating unique instance identity for this server.")
            }
        }.onFailure {
            Log.warn("[StartMode] Failed to inspect Settings.bin template fingerprint", it)
        }
    }

    /** Persistent local instance data. */
    val settings: PluginData = PluginData(SerializersEnum.Bin.create()).apply {
        setFileUtil(settingsFileUtil)
    }

    /** Unique server instance UUID. */
    val serverConnectUuid by settings.value(UUID.randomUUID().toString())

    /** Unique headless identity used by the game protocol layer. */
    val serverHessUuid by settings.value(
        BigInteger(1, DigestUtils.sha256(serverConnectUuid + UUID.randomUUID().toString())).toString(16).uppercase()
    )

    @JvmField
    var serverToken: String = getRandomString(40)

    val admin: Administration = Administration(settings)

    @JvmField
    var upServerList = false

    val http = buildHttpRequestManage()

    val rwHttp = buildHttpRequestManage(
        userAgent = "RW-HPS ${Data.SERVER_CORE_VERSION}",
        connectTimeoutSeconds = 10,
        readTimeoutSeconds = 10,
        writeTimeoutSeconds = 10
    ) { builder ->
        builder.addInterceptor(MyOkHttpRetryInterceptor.Builder().executionCount(5).retryInterval(2500).build())
    }

    val uplistHttp = buildHttpRequestManage(
        userAgent = "RW-HPS UPLIST ${Data.SERVER_CORE_VERSION}",
        retryOnConnectionFailure = false,
        connectTimeoutSeconds = 4,
        readTimeoutSeconds = 4,
        writeTimeoutSeconds = 4,
        maxIdleConnections = 2,
        keepAliveSeconds = 20
    )

    private fun buildHttpRequestManage(
        userAgent: String = Data.userAgent,
        retryOnConnectionFailure: Boolean = true,
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 10,
        writeTimeoutSeconds: Long = 10,
        maxIdleConnections: Int = 2,
        keepAliveSeconds: Long = 30,
        configure: (OkHttpClient.Builder) -> Unit = {}
    ): HttpRequestManage {
        val builder = OkHttpClient.Builder().also {
            it.retryOnConnectionFailure(retryOnConnectionFailure)
            it.connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            it.readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            it.writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            it.connectionPool(ConnectionPool(maxIdleConnections, keepAliveSeconds, TimeUnit.SECONDS))
            configure(it)
        }
        return HttpRequestManage(builder.build(), userAgent)
    }

    /**
     * Save server state before exit.
     */
    fun save() {
        if (!saveStarted.compareAndSet(false, true)) {
            return
        }
        net.rwhps.server.analytics.StatisticsBridge.shutdown()
        runSavePool()
        Threads.dataAutoSave.toList().forEachIndexed { index, task ->
            try {
                task.run()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    throw error
                }
                Log.error("Plugin auto-save callback #$index failed; remaining callbacks will continue", error)
            }
        }
        runOnDisable()
        Log.saveLog()
    }

    /**
     * Exit and save data.
     */
    @JvmOverloads
    inline fun exit(run: () -> Unit = {}) {
        try {
            NetServer.closeServer()
            save()
            close()
            releaseResources()
            Thread.setDefaultUncaughtExceptionHandler(null)
            exitProcess(0)
        } finally {
            run()
        }
    }

    /**
     * P3-10: release the long-lived resources this instance owns.
     *
     * `http`, `rwHttp` and `uplistHttp` each wrap an [OkHttpClient] whose dispatcher
     * executor service and connection pool are non-daemon, so without this the process
     * only ends because `exitProcess` kills it. [HttpRequestManage.close] is idempotent,
     * which is what makes calling this from both `exit()` and the shutdown hook safe.
     */
    fun releaseResources() {
        listOf(http, rwHttp, uplistHttp).forEach { it.close() }
    }

    /**
     * Force quit without graceful shutdown.
     */
    fun mandatoryExit() {
        exitProcess(0)
    }
}
