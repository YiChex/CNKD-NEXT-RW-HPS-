/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core

import com.sun.jna.NativeLibrary
import net.rwhps.server.Main
import net.rwhps.server.core.ServiceLoader.ServiceType
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.Data.serverCountry
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.dependent.LibraryManager
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.NetService
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.net.manage.DownloadManage
import net.rwhps.server.net.netconnectprotocol.FakeRwHps
import net.rwhps.server.net.netconnectprotocol.RwHps
import net.rwhps.server.net.netconnectprotocol.TypeRelay
import net.rwhps.server.net.netconnectprotocol.TypeRelayRebroadcast
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionPacket
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelayRebroadcast
import net.rwhps.server.plugin.PluginsLoad
import net.rwhps.server.plugin.PluginRuntimeSettings
import net.rwhps.server.util.str.StringUtils
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.util.Time
import net.rwhps.server.util.algorithms.Aes
import net.rwhps.server.util.algorithms.Rsa
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.load.I18NBundle
import net.rwhps.server.util.file.plugin.PluginData
import net.rwhps.server.util.inline.readBytes
import net.rwhps.server.util.inline.readFileListString
import net.rwhps.server.util.inline.toPrettyPrintingJson
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.ex.PrintEx
import java.io.File
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


/**
 * @author Dr (dr@der.kim)
 */
class Initialization {
    private fun checkEnvironment() {
        try {
            NativeLibrary.getProcess().name
        } catch (e: UnsatisfiedLinkError) {
            Log.clog("&r RW-HPS It may not be compatible with your architecture, there will be unexpected situations, Thank you !")
        }
    }

    private fun loadLang() {
        Data.i18NBundleMap["CN"] = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_zh_CN.properties")!!)
        Data.i18NBundleMap["HK"] = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_zh_HK.properties")!!)
        Data.i18NBundleMap["RU"] = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_ru_RU.properties")!!)
        Data.i18NBundleMap["EN"] = I18NBundle(Main::class.java.getResourceAsStream("/bundles/HPS_en_US.properties")!!)

        // Default use CN
        Data.i18NBundle = Data.i18NBundleMap["CN"]!!
    }

    private fun loadIpBin() {
        if (!Data.config.ipCheckMultiLanguageSupport) {
            return
        }/*
		try {
			Data.ip2Location = new IP2Location();
			Data.ip2Location.Open(FileUtil.getFolder(Data.Plugin_Data_Path).toFile("IP.bin").getPath(), true);
		} catch (IOException e) {
			Log.error("IP-LOAD ERR",e);
		}*/
    }

    private fun initRsa() {
        try {
            val rsa = Rsa().buildKeyPair()
            Rsa.getPublicKey(rsa.public)
            Rsa.getPrivateKey(rsa.private)
        } catch (e: Exception) {
            Log.error(e)
        }
    }

    private fun initGetServerData() {
        if (Data.pureStartMode) {
            return
        }
        Threads.newTimedTask(CallTimeTask.ServerUpStatistics, 1, 1, TimeUnit.MINUTES) {
            if (NetStaticData.RwHps.netType != IRwHps.NetType.NullProtocol) {
                try {
                    val data = when (NetStaticData.RwHps.netType) {
                        IRwHps.NetType.ServerProtocol, IRwHps.NetType.ServerProtocolOld, IRwHps.NetType.ServerTestProtocol -> {
                            BaseDataSend(
                                    IsServer = true,
                                    ServerData = BaseDataSend.Companion.ServerData(IpPlayerCountry = mutableMapOf<String, Int>().also {
                                        HeadlessModuleManage.hps.room.playerManage.playerGroup.eachAll { player ->
                                            val ipCountry = (player.con!! as AbstractNetConnect).ipCountry
                                            if (it.containsKey(ipCountry)) {
                                                it[ipCountry] = it[ipCountry]!! + 1
                                            } else {
                                                it[ipCountry] = 1
                                            }
                                        }
                                    })
                            )
                        }

                        IRwHps.NetType.RelayProtocol, IRwHps.NetType.RelayMulticastProtocol -> {
                            @PrivateMark
                            BaseDataSend(IsServer = false, RelayData = BaseDataSend.Companion.RelayData())
                        }

                        else -> {
                            BaseDataSend(IsServerRun = false, IsServer = false)
                        }
                    }


                    val out = GameOutputStream()
                    out.writeString("RW-HPS Statistics Data")
                    out.writeString(Data.core.serverConnectUuid)
                    out.writeBytesAndLength(
                            Aes.aesEncryptToBytes(data.toPrettyPrintingJson().toByteArray(Data.UTF_8), "RW-HPS Statistics Data")
                    )
                    val packet = out.createPacket(PacketType.SERVER_DEBUG_RECEIVE)
                    Socket().use {
                        it.connect(InetSocketAddress(InetAddress.getByName("relay.der.kim"), 6001), 10000)
                        DataOutputStream(it.getOutputStream()).use { outputStream ->
                            outputStream.writeInt(packet.bytes.size)
                            outputStream.writeInt(packet.type.typeInt)
                            outputStream.write(packet.bytes)
                            outputStream.flush()
                        }
                        it.close()
                    }
                } catch (e: Exception) {
                    // Ignored, should not be shown to the user
                }
            }
        }
    }

    companion object {
        /**
         * D-19: this used to be a `@Volatile var isClose = true` that was only ever
         * assigned `true`, so the `if (!isClose) return` guard in the shutdown hook was
         * dead code and pretended there was re-entrancy protection. The hook body is now
         * guarded by a real one-shot flag.
         */
        private val shutdownSaveDone = java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun startInit() {
            val pluginData = Data.core.settings
            initServerLanguage(pluginData)
            eula(pluginData)
        }

        /**
         * The country is determined according to the server's export ip when it is first started
         * Choose the language environment according to the country
         */
        internal fun initServerLanguage(pluginData: PluginData, country: String = "") {
            serverCountry = if (country.isBlank()) {
                when (pluginData["serverCountry", "CN"].uppercase()) {
                    "HK" -> "HK"
                    "RU" -> "RU"
                    "EN" -> "EN"
                    else -> "CN"
                }.also {
                    pluginData["serverCountry"] = it
                }
            } else {
                when {
                    country.contains("HK") -> "HK"
                    country.contains("RU") -> "RU"
                    country.contains("EN") -> "EN"
                    else -> "CN"
                }.also {
                    pluginData["serverCountry"] = it
                }
            }

            Data.i18NBundle = Data.i18NBundleMap[serverCountry]!!
            Log.clog(Data.i18NBundle.getinput("server.language"))
        }

        private fun eula(pluginData: PluginData) {
            var startupStateChanged = false
            if (pluginData["eulaVersion", ""] != Data.SERVER_EULA_VERSION) {
                pluginData["eulaVersion"] = Data.SERVER_EULA_VERSION
                startupStateChanged = true
                Log.clog("[Startup] Open-source notice initialized automatically. No interactive confirmation is required.")
            }
            if (pluginData["initStart", true]) {
                pluginData["initStart"] = false
                startupStateChanged = true
                Log.clog("[Startup] First-run initialization completed automatically. The server will continue starting.")
            }
            if (startupStateChanged) {
                pluginData.save()
            }
            return
            // Eula
            if (pluginData["eulaVersion", ""] != Data.SERVER_EULA_VERSION) {
                PrintEx.waitLicense({
                    val eulaBytes = if (serverCountry == "CN") {
                        FileUtils.getInternalFileStream("/eula/Main-China.md").readBytes()
                    } else {
                        FileUtils.getInternalFileStream("/eula/Main-English.md").readBytes()
                    }
                    Log.clog(StringUtils.str(eulaBytes, Data.UTF_8))
                }, { pluginData["eulaVersion"] = Data.SERVER_EULA_VERSION }, { Data.core.exit() })
            }

            if (pluginData["initStart", true]) {
                PrintEx.waitLicense({
                    val initStart = if (serverCountry == "CN") {
                        "检测到这是该实例的首次启动，是否需要使用 Web 面板辅助初始化？"
                    } else {
                        "It looks like you are running the server for the first time. Do you need the WEB panel to help you initialize?"
                    }
                    Log.clog(initStart)
                }, {
                    pluginData["initStart"] = false
                }, {})
            }

            pluginData.save()
        }

        internal fun loadLib() {
            val libraryManager = LibraryManager()
            val loadJavaScriptRuntime = hasJavaScriptPlugins()

            val excludeImport: (String) -> Unit = {
                val libData = it.split(":")
                if (libData[0] == "jar" && shouldImportCompileOnlyLibrary(libData, loadJavaScriptRuntime)) {
                    libraryManager.implementation(libData[1], libData[2], libData[3], libData[4].let { classifier -> if (classifier == "null") "" else classifier })
                }
            }
            FileUtils.getInternalFileStream("/maven/ASM-Framework/compileOnly.txt").readFileListString().eachAll(excludeImport)
            FileUtils.getInternalFileStream("/maven/Server-Core/compileOnly.txt").readFileListString().eachAll(excludeImport)
            FileUtils.getInternalFileStream("/maven/TimeTaskQuartz/compileOnly.txt").readFileListString().eachAll(excludeImport)


            val libImport: (String) -> Unit = {
                val libData = it.split(":")
                if (libData[0] == "jar") {
                    libraryManager.exclude(libData[1], libData[2], libData[3], libData[4].let { classifier -> if (classifier == "null") "" else classifier })
                }
            }
            FileUtils.getInternalFileStream("/maven/ASM-Framework/implementation.txt").readFileListString().eachAll(libImport)
            FileUtils.getInternalFileStream("/maven/Server-Core/implementation.txt").readFileListString().eachAll(libImport)
            FileUtils.getInternalFileStream("/maven/TimeTaskQuartz/implementation.txt").readFileListString().eachAll(libImport)

            if (loadJavaScriptRuntime) {
                val wasm = FileUtils.getFolder(Data.ServerLibPath).toFile("Wasm.jar")
                // P3-11: a 0-byte Wasm.jar satisfies exists() but is not loadable, so the
                // old `!wasm.exists()` gate skipped the download and let startup continue
                // until a confusing NoClassDefFoundError surfaced later. Treat an empty
                // file exactly like a missing one so it is re-downloaded here.
                if ((!wasm.exists() || wasm.length() == 0L) && !DownloadManage.addDownloadTask(DownloadManage.DownloadData(Data.urlData.readString("Get.Core.ResDown") + "Wasm.zip", wasm, progressFlag = true))) {
                    Log.fatal("WASM Down Error")
                    return
                }
                libraryManager.customImportLib(wasm)
            } else {
                Log.info(Data.i18NBundle.getinput("server.library.js.disabled"))
            }

            libraryManager.loadToClassLoader()

            loadPrivateLib()
        }

        private fun hasJavaScriptPlugins(): Boolean {
            return PluginsLoad.hasApprovedJavaScriptPlugin(PluginRuntimeSettings.pluginDirectoryUtils(false))
        }

        private fun shouldImportCompileOnlyLibrary(libData: List<String>, loadJavaScriptRuntime: Boolean): Boolean {
            if (loadJavaScriptRuntime) {
                return true
            }
            if (libData.size < 4) {
                return true
            }
            val group = libData[1]
            val module = libData[2]
            return !(
                (group == "org.graalvm.js" && module == "js") ||
                    (group == "org.graalvm.regex" && module == "regex") ||
                    (group == "org.graalvm.truffle" && module == "truffle-api") ||
                    (group == "org.graalvm.sdk" && module == "graal-sdk") ||
                    (group == "com.ibm.icu" && module == "icu4j")
                )
        }

        private fun loadPrivateLib() {
            //val fileUtils = FileUtils.getFolder(Data.ServerPluginsPath).toFile("RW-HPS-JSLib.zip")
            // TODO: 加载JS-Lib
        }

        internal fun loadService() {
            if (!Data.pureStartMode) {
                @PrivateMark
                ServiceLoader.addService(ServiceType.ProtocolType, IRwHps.NetType.RelayProtocol.name, TypeRelay::class.java)
                @PrivateMark
                ServiceLoader.addService(ServiceType.ProtocolType, IRwHps.NetType.RelayMulticastProtocol.name, TypeRelayRebroadcast::class.java)
                @PrivateMark
                ServiceLoader.addService(ServiceType.Protocol, IRwHps.NetType.RelayProtocol.name, GameVersionRelay::class.java)
                @PrivateMark
                ServiceLoader.addService(ServiceType.Protocol, IRwHps.NetType.RelayMulticastProtocol.name, GameVersionRelayRebroadcast::class.java)
            }

            ServiceLoader.addService(ServiceType.ProtocolPacket, IRwHps.NetType.GlobalProtocol.name, GameVersionPacket::class.java)

            ServiceLoader.addServiceObject(ServiceType.PacketType, IRwHps.NetType.GlobalProtocol.name, PacketType.INSTANCE)

            ServiceLoader.addService(ServiceType.IRwHps, "IRwHps", RwHps::class.java)
            ServiceLoader.addService(ServiceType.IRwHps, IRwHps.NetType.RemoteControlProtocol.name, FakeRwHps::class.java)
            ServiceLoader.addService(ServiceType.IRwHps, IRwHps.NetType.HttpProtocol.name, FakeRwHps::class.java)
        }

        internal data class BaseDataSend(
            val SendTime: Int = Time.concurrentSecond(),
            val ServerRunPort: Int = Data.config.port,
            val ServerNetType: String = NetStaticData.RwHps.netType.name,
            val System: String = SystemUtils.osName,
            val JavaVersion: String = SystemUtils.javaVersion,
            val VersionCount: String = Data.SERVER_CORE_VERSION,
            val IsServerRun: Boolean = true,
            val IsServer: Boolean,
            val ServerData: ServerData? = null,
            val RelayData: RelayData? = null,
        ) {
            companion object {
                data class ServerData(
                    val PlayerSize: Int = AtomicInteger().also {
                        NetStaticData.netService.eachAll { e: NetService -> it.addAndGet(e.getConnectSize()) }
                    }.get(),
                    val MaxPlayer: Int = Data.configServer.maxPlayer,
                    val PlayerVersion: Int = (NetStaticData.RwHps.typeConnect.abstractNetConnect as AbstractNetConnectServer).supportedVersionInt,
                    val IpPlayerCountry: Map<String, Int>,
                )

                @PrivateMark
                data class RelayData(
                    val PlayerSize: Int = AtomicInteger().also {
                        NetStaticData.netService.eachAll { e: NetService -> it.addAndGet(e.getConnectSize()) }
                    }.get(),
                    val RoomAllSize: Int = RelayRoom.roomAllSize,
                    val RoomNoStartSize: Int = RelayRoom.roomNoStartSize,
                    val RoomPublicListSize: Int = RelayRoom.roomPublicSize,
                    val PlayerVersion: Map<Int, Int> = RelayRoom.getAllRelayVersion(),
                    val IpPlayerCountry: Map<String, Int> = RelayRoom.getAllRelayIpCountry(),
                )
            }
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(object: Thread("Exit Handler") {
            override fun run() {
                // D-19: run the exit save at most once (Application.save() also has its own
                // saveStarted CAS, this only avoids duplicate work and duplicate output).
                if (!shutdownSaveDone.compareAndSet(false, true)) {
                    return
                }

                // P3-9: this hook used to save and then do nothing else, so on Ctrl-C /
                // SIGTERM / exitProcess the Netty boss+worker groups, the listening sockets
                // and the four Threads pools were never released -- NetServer.closeServer()
                // and Threads.close() were reachable only from Application.exit(), which the
                // signal path never reaches. Both are idempotent, so running them here is
                // safe even when exit() already ran them (see P3-CLOSURE-REPORT.md).
                //
                // Ordering (see the report for the full rationale):
                //   1. network  -- stop accepting/reading so nothing new mutates state;
                //   2. save     -- persist while the thread pools and the Quartz scheduler
                //                  are still alive, because save() runs plugin onDisable()
                //                  which deregisters tasks through Threads/SERVICE;
                //   3. pools    -- stop the thread pools and scheduler last;
                //   4. resources-- release the unowned non-daemon resources (OkHttp,
                //                  GroupNet executors, the error-log handle) last, because
                //                  save()/onDisable() may still perform final I/O.
                runCatching { NetServer.closeServer() }
                    .onFailure { System.err.println("[Exit] Failed to close net services: ${it.message}") }

                // Also guarded (task-35 residual): save() was the only step left unwrapped.
                // A JVM shutdown hook stops executing at the first uncaught throwable, so if
                // save() escaped, every release below would be skipped -- exactly the
                // incomplete-shutdown class of bug this hook exists to fix. save() can throw:
                // runSavePool()/the dataAutoSave loop deliberately rethrow
                // VirtualMachineError/ThreadDeath, and runOnDisable() is a
                // try/finally whose shutdown() body can propagate. Nothing below needs it.
                runCatching { Data.core.save() }
                    .onFailure { System.err.println("[Exit] Failed to save core data: ${it.message}") }
                println("Exit Save Ok")

                runCatching { Threads.close() }
                    .onFailure { System.err.println("[Exit] Failed to close thread pools: ${it.message}") }

                runCatching { Data.core.releaseResources() }
                    .onFailure { System.err.println("[Exit] Failed to release HTTP clients: ${it.message}") }

                // NetStaticData.groupNet is a lazy singleton. Reading it here can construct
                // it, but construction allocates no threads (Executors.newSingleThreadExecutor
                // creates its thread on first task, and DefaultChannelGroup does not start
                // GlobalEventExecutor), so this cannot leak a thread just to close one.
                runCatching { NetStaticData.groupNet.disconnect() }
                    .onFailure { System.err.println("[Exit] Failed to disconnect group net: ${it.message}") }

                // Close the error-log handle last: everything above may still log.
                runCatching { Log.closeLog() }
                    .onFailure { System.err.println("[Exit] Failed to close error log: ${it.message}") }

                System.setOut(Data.privateOut)
            }
        })

        checkEnvironment()
        //
        loadLang()

        //initRsa()
        initGetServerData()
    }
}
