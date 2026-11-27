/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command

import net.rwhps.server.command.relay.RelayCommands
import net.rwhps.server.core.Initialization
import net.rwhps.server.core.NetServer
import net.rwhps.server.core.RuntimeModeSupport
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.bean.BeanRelayApiConfig
import net.rwhps.server.data.bean.BeanRelayConfig
import net.rwhps.server.data.bean.BeanRelayLicenseConfig
import net.rwhps.server.data.bean.BeanRelayRoomCodeConfig
import net.rwhps.server.data.bean.BeanRelayTextAuditConfig
import net.rwhps.server.data.bean.BeanPersistentAccessConfig
import net.rwhps.server.data.bean.BeanRoomPermissionConfig
import net.rwhps.server.data.bean.ServerConfigRepository
import net.rwhps.server.data.bean.internal.BeanGithubReleasesApi
import net.rwhps.server.data.bean.RuntimeConfigCandidate
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.dependent.HotLoadClass
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.event.global.ServerStartTypeEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.IRwHpsManage
import net.rwhps.server.game.room.RelayRoom.Companion.sendAllMsg
import net.rwhps.server.io.output.DisableSyncByteArrayOutputStream
import net.rwhps.server.net.NetService
import net.rwhps.server.net.api.RelayApiSupport
import net.rwhps.server.net.api.RelayLicenseSupport
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.manage.DownloadManage
import net.rwhps.server.plugin.GetVersion
import net.rwhps.server.plugin.PluginLoadData
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.plugin.center.PluginCenter
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOptionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAdvertisingRedirectSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeAutoStartSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeLobbySupport
import net.rwhps.server.plugin.internal.headless.support.StartModeMapLockSupport
import net.rwhps.server.plugin.internal.headless.support.StartModePersistentAccessSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameOverReturnSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeGameCommandCompatibilitySupport
import net.rwhps.server.plugin.internal.headless.support.StartModePlayerExperienceSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeReadinessSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeSurrenderSupport
import net.rwhps.server.plugin.internal.headless.support.SingleRoomLowLatencySupport
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.util.alone.DiffUpdate
import net.rwhps.server.util.annotations.NeedHelp
import net.rwhps.server.util.console.tab.TabDefaultEnum
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.inline.toGson
import net.rwhps.server.util.log.Log
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Dr (dr@der.kim)
 */
class CoreCommands(handler: CommandHandler) {
    private fun registerCore(handler: CommandHandler) {
        handler.register("help", "serverCommands.help") { _: Array<String>?, log: StrCons ->
            log("Commands:")
            for (command in handler.commandList) {
                if (command.description.startsWith("#")) {
                    log(
                            "   " + command.text + (if (command.paramText.isEmpty()) "" else " ") + command.paramText + " - " + command.description.substring(
                                    1
                            )
                    )
                } else {
                    if ("HIDE" == command.description) {
                        continue
                    }
                    log(
                            "   " + command.text + (if (command.paramText.isEmpty()) "" else " ") + command.paramText + " - " + Data.i18NBundle.getinput(
                                    command.description
                            )
                    )
                }
            }
        }

        @NeedHelp handler.register("stop", "HIDE") { _: Array<String>?, log: StrCons ->
            if (NetStaticData.netService.size == 0) {
                log("Server does not start")
                return@register
            }
            NetServer.closeServer()
        }

        handler.register("version", "serverCommands.version") { _: Array<String>?, log: StrCons ->
            log(
                    localeUtil.getinput(
                            "status.versionS",
                            SystemUtils.javaHeap / 1024 / 1024,
                            Data.SERVER_CORE_VERSION,
                            NetStaticData.RwHps.netType.name
                    )
            )
            if (NetStaticData.RwHps.netType.ordinal in IRwHps.NetType.ServerProtocol.ordinal .. IRwHps.NetType.ServerTestProtocol.ordinal) {
                HeadlessModuleManage.hessLoaderMap.values.forEach {
                    val room = it.room
                    val presence = room.playerManage.buildPlayerPresenceSnapshot()
                    log(
                            localeUtil.getinput(
                                    "status.versionS.server",
                                    room.maps.mapName,
                                    presence.connectedOccupants,
                                    NetStaticData.RwHps.netType.name,
                                    it.useClassLoader,
                                    room.roomID
                            )
                    )
                }
            } else if (NetStaticData.RwHps.netType == IRwHps.NetType.RelayProtocol || NetStaticData.RwHps.netType == IRwHps.NetType.RelayMulticastProtocol) {
                val size = AtomicInteger()
                NetStaticData.netService.eachAll { e: NetService -> size.addAndGet(e.getConnectSize()) }
                log(localeUtil.getinput("status.versionS.relay", size.get()))
            }
        }
        handler.register("setlanguage", "<${TabDefaultEnum.Language}>", "serverCommands.setlanguage") { arg: Array<String>, _: StrCons ->
            Initialization.initServerLanguage(Data.core.settings, arg[0])
        }

        handler.register("reloadconfig", "serverCommands.reloadconfig") { _: Array<String>?, log: StrCons ->
            reloadRuntimeConfig(log)
        }

        handler.register("exit", "serverCommands.exit") { Data.core.exit() }
    }

    private fun registerInfo(handler: CommandHandler) {
        handler.register("plugins", "[action] [id]", "serverCommands.plugins") { args: Array<String>?, log: StrCons ->
            val action = args?.firstOrNull()?.trim().orEmpty()
            if (action.equals("diagnose", ignoreCase = true) || action.equals("diagnosis", ignoreCase = true)) {
                PluginManage.buildDiagnosticsReport(args?.getOrNull(1)).forEach { line -> log(line) }
                return@register
            }
            if (action.equals("health", ignoreCase = true)) {
                PluginManage.buildHealthReport(args?.getOrNull(1)).forEach { line -> log(line) }
                return@register
            }

            var matched = 0
            PluginManage.run { e: PluginLoadData ->
                val requestedId = action
                if (requestedId.isEmpty() || e.internalName.equals(requestedId, ignoreCase = true)) {
                    matched++
                    log(localeUtil.getinput("plugin.info", e.name, e.description, e.author, e.version))
                    val failure = e.failurePhase?.let { phase -> ", failure=$phase:${e.failureMessage ?: "unknown"}" }.orEmpty()
                    log(
                        "  id=${e.internalName}, api=V${e.apiVersion}, capabilities=${e.capabilities.sorted()}, " +
                            "state=${e.state}, lifecycleLastPhase=${e.lastPhase ?: "none"}, " +
                            "lastDurationUs=${e.lastPhaseDurationNanos / 1_000}$failure" +
                            e.runtimeMetrics?.let { ", ${it.summary()}" }.orEmpty()
                    )
                }
            }
            if (matched == 0) {
                log("Plugin not found: $action")
            }
        }

        handler.register("pluginlog", "[status]", "#V2 plugin log diagnostics") { args: Array<String>?, log: StrCons ->
            when (args?.firstOrNull()?.lowercase(Locale.getDefault()).orEmpty()) {
                "", "status", "diagnose" -> PluginLogDiagnosticsSupport.buildReport().forEach { log(it) }
                else -> log("Usage: pluginlog [status]")
            }
        }

        handler.register("license", "#License diagnostics") { _: Array<String>?, log: StrCons ->
            RelayLicenseSupport.summaryLines().forEach { log(it) }
        }

        handler.register("health", "[detail]", "#查看服务端健康状态、内存压力与运维建议") { arg: Array<String>?, log: StrCons ->
            val includeStorageDetails = !arg.isNullOrEmpty() && (
                arg[0].equals("detail", ignoreCase = true) ||
                    arg[0].equals("details", ignoreCase = true) ||
                    arg[0].equals("all", ignoreCase = true)
                )
            AdminHealthSupport.buildReport(includeStorageDetails).forEach { log(it) }
        }

        handler.register("api", "[status]", "#查看数据 API 缓存、限流和错误指标") { _: Array<String>?, log: StrCons ->
            RelayApiSupport.buildDiagnostics().forEach { log(it) }
        }

        handler.register("config", "[check]", "#检查主配置、授权、公开列表和 API 风险") { arg: Array<String>?, log: StrCons ->
            if (arg.isNullOrEmpty() || arg[0].equals("check", ignoreCase = true) || arg[0].equals("status", ignoreCase = true)) {
                ConfigCheckSupport.buildReport().forEach { log(it) }
            } else {
                log("Usage: config check")
            }
        }

        handler.register("configcheck", "", "#检查主配置、授权、公开列表和 API 风险") { _: Array<String>?, log: StrCons ->
            ConfigCheckSupport.buildReport().forEach { log(it) }
        }

        handler.register("maintenance", "[plan|clean-cache|clean-replays|clean-logs] [confirm] [days]", "#Maintenance cleanup diagnostics") { arg: Array<String>?, log: StrCons ->
            val action = arg?.getOrNull(0)?.lowercase(Locale.getDefault()) ?: "plan"
            val confirm = arg?.any { it.equals("confirm", ignoreCase = true) } == true
            val keepDays = arg?.mapNotNull { it.toIntOrNull() }?.firstOrNull() ?: 14
            val lines = when (action) {
                "plan", "status", "check" -> AdminMaintenanceSupport.buildPlan(keepDays)
                "clean-cache" -> AdminMaintenanceSupport.cleanCache(confirm)
                "clean-replays" -> AdminMaintenanceSupport.cleanReplays(confirm, keepDays)
                "clean-logs" -> AdminMaintenanceSupport.cleanLogs(confirm, keepDays)
                else -> listOf(
                    "Usage: maintenance [plan|clean-cache|clean-replays|clean-logs] [confirm] [days]",
                    "Example: maintenance plan 14",
                    "Example: maintenance clean-cache confirm",
                    "Example: maintenance clean-replays confirm 30"
                )
            }
            lines.forEach { log(it) }
        }

        handler.register("startmode", "[diagnose|bootcheck|pretest|nocontrol]", "#Start Mode diagnostics") { arg: Array<String>?, log: StrCons ->
            when (arg?.getOrNull(0)?.lowercase(Locale.getDefault()) ?: "diagnose") {
                "diagnose", "status" -> renderStartModeDiagnostics().forEach { log(it) }
                "bootcheck", "startup", "first", "config" -> StartModeBootCheckSupport.buildSummary().forEach { log(it) }
                "pretest", "checklist", "firsttest", "first-test", "ready" -> StartModeFirstTestChecklistSupport.buildChecklist().forEach { log(it) }
                "nocontrol", "no-control", "no_control", "triage", "incident" -> renderNoControlIncidentTriageDiagnostics().forEach { log(it) }
                else -> log("Usage: startmode [diagnose|bootcheck|pretest|nocontrol]")
            }
        }

        handler.register("lowlatency", "[action] [profile]", "#Single-room low-latency diagnostics") { arg: Array<String>?, log: StrCons ->
            when (arg?.getOrNull(0)?.lowercase(Locale.getDefault()) ?: "status") {
                "readout", "checklist", "firsttest", "first-test", "verdict" -> SingleRoomLowLatencySupport.buildFirstTestReadout().forEach { log(it) }
                "summary", "report", "result", "test" -> SingleRoomLowLatencySupport.buildTestSummary().forEach { log(it) }
                "experience", "feel", "ux" -> SingleRoomLowLatencySupport.buildExperienceSummary().forEach { log(it) }
                "status", "diagnose" -> SingleRoomLowLatencySupport.buildDiagnostics().forEach { log(it) }
                "advise", "advice", "degrade" -> SingleRoomLowLatencySupport.buildDegradeAdvice().forEach { log(it) }
                "reset", "clear" -> SingleRoomLowLatencySupport.resetMetrics().forEach { log(it) }
                "profiles", "profile", "matrix" -> SingleRoomLowLatencySupport.buildProfiles().forEach { log(it) }
                "apply", "use", "set" -> SingleRoomLowLatencySupport.applyProfile(arg?.getOrNull(1)).forEach { log(it) }
                else -> log("Usage: lowlatency [readout|summary|experience|status|advise|reset|profiles|apply <A|B|C|D|stable|recommended|aggressive|rollback|lowMemory|production|test|off|config>]")
            }
        }

        handler.register("advertising", "[status|check]", "#Advertising redirect diagnostics") { arg: Array<String>?, log: StrCons ->
            when (arg?.getOrNull(0)?.lowercase(Locale.getDefault()) ?: "status") {
                "status", "diagnose" -> StartModeAdvertisingRedirectSupport.buildDiagnostics().forEach { log(it) }
                "check", "probe" -> StartModeAdvertisingRedirectSupport.checkTargetNow().forEach { log(it) }
                else -> log("Usage: advertising [status|check]")
            }
        }

        handler.register("maplock", "[status|apply]", "#Map lock diagnostics") { arg: Array<String>?, log: StrCons ->
            if (arg.isNullOrEmpty() || arg[0].equals("status", ignoreCase = true)) {
                StartModeMapLockSupport.buildDiagnostics().forEach { log(it) }
            } else if (arg[0].equals("apply", ignoreCase = true)) {
                val changed = StartModeMapLockSupport.applyLockedMapIfNeeded()
                log("maplock.apply changed=$changed")
                StartModeMapLockSupport.buildDiagnostics().forEach { log(it) }
            } else {
                log("Usage: maplock [status|apply]")
            }
        }

        handler.register("roomperm", "[status]", "#Room permission diagnostics") { arg: Array<String>?, log: StrCons ->
            if (arg.isNullOrEmpty() || arg[0].equals("status", ignoreCase = true)) {
                StartModeRoomPermissionSupport.buildDiagnostics().forEach { log(it) }
            } else {
                log("Usage: roomperm [status]")
            }
        }

        handler.register("access", "[status|reload]", "#Persistent access diagnostics") { arg: Array<String>?, log: StrCons ->
            if (arg.isNullOrEmpty() || arg[0].equals("status", ignoreCase = true)) {
                StartModePersistentAccessSupport.buildDiagnostics().forEach { log(it) }
            } else if (arg[0].equals("reload", ignoreCase = true)) {
                Data.configPersistentAccess = BeanPersistentAccessConfig.stringToClass()
                StartModePersistentAccessSupport.applyConfiguredAccessToRuntime(migrateRuntime = false)
                log("PersistentAccessConfig.json reloaded.")
                StartModePersistentAccessSupport.buildDiagnostics().forEach { log(it) }
            } else {
                log("Usage: access [status|reload]")
            }
        }
    }

    private fun registerCorex(handler: CommandHandler) {
        handler.register("say", "<TEXT...>", "#全服广播") { arg: Array<String>, log: StrCons ->
            val args = arg.toMutableList()
            var title = ""
            val titleIndex = args.indexOf("-t")
            if (titleIndex != -1) {
                if (titleIndex + 1 >= args.size) {
                    log("Usage: say <TEXT...> [-t TITLE]")
                    return@register
                }
                title = args[titleIndex + 1].trim()
                args.removeAt(titleIndex + 1)
                args.removeAt(titleIndex)
            }

            val message = args.joinToString(" ").trim()
            if (message.isEmpty()) {
                log("Usage: say <TEXT...> [-t TITLE]")
                return@register
            }

            val broadcast = if (title.isEmpty()) message else "[$title]:$message"
            sendAllMsg(broadcast)
            log("已广播: $broadcast")
        }

        handler.register("plugin", "<TEXT...>", "serverCommands.plugin") { arg: Array<String>, log: StrCons ->
            PluginCenter.pluginCenter.command(arg[0], log)
        }

        handler.register("tryupdate", "serverCommands.tryUpdate") { _: Array<String>, log: StrCons ->
            val jsonAll = Array<BeanGithubReleasesApi>::class.java.toGson(
                    Data.core.http.doGet(Data.urlData.readString("Get.Core.Update.AllVersion"))
            )
            val nowVersion = GetVersion(Data.SERVER_API_VERSION)

            val updateFunction = fun(update: List<BeanGithubReleasesApi>, beta: Boolean) {
                val sleepUpdate = Seq<BeanGithubReleasesApi.AssetsDTO>()
                update.reversed().forEach {
                    it.assets!!.forEach {
                        if ((!beta && it.name.endsWith(".last.patch")) || (beta && it.name.endsWith(".beta.patch"))) {
                            val out = DisableSyncByteArrayOutputStream()
                            if (!DownloadManage.addDownloadTask(DownloadManage.DownloadData(it.browserDownloadUrl, it.name, out, progressFlag = true))) {
                                Log.error(localeUtil.getinput("err.network.noGithub"))
                                return
                            }
                            it.bytes = out.toByteArray()
                            sleepUpdate.add(it)
                        }
                    }
                }
                var old = FileUtils.getMyCoreJarStream().readAllBytes()

                sleepUpdate.forEach {
                    val new = DisableSyncByteArrayOutputStream()
                    DiffUpdate.patch(old, it.bytes!!, new)
                    Log.clog("Update to ${it.name}")
                    old = new.toByteArray()
                }

                Data.core.exit {
                    Log.clog("Use ctrl+c")
                    FileUtils(FileUtils.getMyFilePath()).writeFileByte(old)
                }
            }

            if (Data.config.followBetaVersion) {
                if (nowVersion.getIfVersion("< ${jsonAll[0].tagName}")) {
                    updateFunction(Seq<BeanGithubReleasesApi>().apply {
                        jsonAll.forEach {
                            if (nowVersion.getIfVersion("< ${it.tagName}")) {
                                add(it)
                            }
                        }
                    }, true)
                    return@register
                }
            } else {
                val lastVersion = jsonAll.find { !it.prerelease }!!
                if (nowVersion.getIfVersion("< ${lastVersion.tagName}")) {
                    if (nowVersion.formalEdition) {
                        updateFunction(Seq<BeanGithubReleasesApi>().apply {
                            jsonAll.forEach {
                                if (!it.prerelease && nowVersion.getIfVersion("< ${it.tagName}")) {
                                    add(it)
                                }
                            }
                        }, false)
                    } else {
                        updateFunction(Seq<BeanGithubReleasesApi>().apply {
                            jsonAll.forEach {
                                if (GetVersion(it.tagName).getIfVersion("<= ${lastVersion.tagName}") && nowVersion.getIfVersion(
                                            "< ${it.tagName}"
                                    )) {
                                    add(it)
                                }
                            }
                        }, true)
                    }
                    return@register
                }
            }
            log(localeUtil.getinput("serverCommands.tryUpdate.last"))
        }
    }

    private fun registerStartServer(handler: CommandHandler) {
        handler.register("startrelay", "serverCommands.start") { _: Array<String>?, log: StrCons ->
            if (Data.pureStartMode) {
                log("Pure Start Mode build: Relay command is disabled to reduce memory usage.")
                return@register
            }
            if (NetStaticData.netService.size > 0) {
                log("The server is not closed, please close")
                return@register
            }

            /* Register Relay Protocol Command */
            RelayCommands(handler)

            Log.set(Data.config.log.uppercase(Locale.getDefault()))

            handler.handleMessage("startnetservice ${NetService.coreID()} true 5201 5500", IRwHpsManage.addIRwHps(IRwHps.NetType.RelayProtocol)) //5200 6500
        }
        handler.register("startrelaytest", "serverCommands.start") { _: Array<String>?, log: StrCons ->
            if (Data.pureStartMode) {
                log("Pure Start Mode build: Relay command is disabled to reduce memory usage.")
                return@register
            }
            if (NetStaticData.netService.size > 0) {
                log("The server is not closed, please close")
                return@register
            }

            /* Register Relay Protocol Command */
            RelayCommands(handler)

            Log.set(Data.config.log.uppercase(Locale.getDefault()))

            handler.handleMessage("startnetservice ${NetService.coreID()} true 7000 8000", IRwHpsManage.addIRwHps(IRwHps.NetType.RelayMulticastProtocol))
        }


        handler.register("startnetservice", "<id> <isPort> [sPort] [ePort]", "HIDE") { arg: Array<String>?, net: IRwHps ->
            if (arg != null) {
                if (arg[1].toBoolean()) {

                    val netServiceTcp = NetService(arg[0], net)

                    if (net.netType == IRwHps.NetType.RelayProtocol || net.netType == IRwHps.NetType.RelayMulticastProtocol) {
                        netServiceTcp.workThreadCount = NetService.recommendedRelayWorkerThreadCount()
                    } else {
                        // 用户 CPU 并没有那么强悍
                        //netServiceTcp.workThreadCount = Data.configServer.maxPlayer
                    }

                    Threads.newThreadCoreNet {
                        if (arg.size > 3) {
                            netServiceTcp.openPort(Data.config.port, arg[2].toInt(), arg[3].toInt())
                        } else {
                            netServiceTcp.openPort(Data.config.port)
                        }
                    }
                }

                PluginManage.runGlobalEventManage(ServerStartTypeEvent(net.netType))
                if (RuntimeModeSupport.isPureStartMode() &&
                    net.netType != IRwHps.NetType.RelayProtocol &&
                    net.netType != IRwHps.NetType.RelayMulticastProtocol
                ) {
                    StartModeBootCheckSupport.buildSummary().forEach { Log.clog(it) }
                }
            } else {
                Log.clog("[Start Service] No parameter")
            }
        }
    }

    companion object {
        private val localeUtil = Data.i18NBundle
    }

    private data class ReloadCandidate(
        val core: net.rwhps.server.data.bean.BeanCoreConfig,
        val server: net.rwhps.server.data.bean.BeanServerConfig,
        val start: net.rwhps.server.data.bean.BeanStartModeConfig,
        val permission: BeanRoomPermissionConfig,
        val access: BeanPersistentAccessConfig,
        val relay: BeanRelayConfig,
        val api: BeanRelayApiConfig,
        val code: BeanRelayRoomCodeConfig,
        val text: BeanRelayTextAuditConfig,
        val license: BeanRelayLicenseConfig
    )

    @Synchronized
    private fun reloadRuntimeConfig(log: StrCons) {
        val runningPureStartMode = Data.pureStartMode
        if (HeadlessModuleManage.initHPS()) {
            val room = HeadlessModuleManage.hps.room
            if (room.isStartGame || room.isLobbyPlayerSyncBlocked() || StartModeAutoStartSupport.isStartOperationInProgress()) {
                log("Config reload rejected: wait until the room is back in a stable lobby. No runtime values changed.")
                return
            }
        }
        val previousCore = Data.config
        val previousServer = Data.configServer
        val previousStart = Data.configStartMode
        val previousPermission = Data.configRoomPermission
        val previousAccess = Data.configPersistentAccess
        val previousRelay = Data.configRelay
        val previousApi = Data.configRelayApi
        val previousCode = Data.configRelayRoomCode
        val previousText = Data.configRelayTextAudit
        val previousLicense = Data.configRelayLicense
        val previousOptions = StartModeGameOptionSupport.captureLobbyOptionState()
        // Parse every file and all nested fields before publishing a single new reference.
        val candidate = runCatching {
            val file = RuntimeConfigCandidate.main(ServerConfigRepository.fileUtils.file.readText(Charsets.UTF_8))
            val core = file.toCoreConfig().also { it.bindFile(ServerConfigRepository.fileUtils); it.readProperty() }
            val server = file.toServerConfig().also { it.bindFile(ServerConfigRepository.fileUtils); it.readProperty() }
            val start = file.toStartModeConfig().also { it.bindFile(ServerConfigRepository.fileUtils); it.readProperty() }
            val permission = RuntimeConfigCandidate.read(BeanRoomPermissionConfig.fileUtils, BeanRoomPermissionConfig::class.java, ::BeanRoomPermissionConfig, required = true)
            val access = RuntimeConfigCandidate.read(BeanPersistentAccessConfig.fileUtils, BeanPersistentAccessConfig::class.java, ::BeanPersistentAccessConfig, required = true)
            // Force validation of lists which Gson can otherwise deserialize as null.
            access.bannedUUIDs.forEach { require(it.isNotBlank()) { "Empty banned UUID" } }
            access.bannedIPs.forEach { require(it.isNotBlank()) { "Empty banned IP" } }
            access.bannedIP24.forEach { require(it.isNotBlank()) { "Empty banned subnet" } }
            listOf(access.autoHostUUIDs, access.adminUUIDs, access.superAdminUUIDs).forEach { entries ->
                entries.forEach { require(it.isNotBlank()) { "Empty administrator UUID" } }
            }
            access.admins.forEach {
                require(it.uuid.isNotBlank() && it.role.isNotBlank()) { "Empty administrator UUID/role" }
                it.name.trim()
            }
            permission.host.allowMovePlayers; permission.admin.allowMovePlayers; permission.player.allowMovePlayers; permission.moderation.superAdminBypass
            val relay = if (runningPureStartMode) previousRelay else RuntimeConfigCandidate.read(BeanRelayConfig.fileUtils, BeanRelayConfig::class.java, ::BeanRelayConfig)
            val api = if (runningPureStartMode) previousApi else RuntimeConfigCandidate.read(BeanRelayApiConfig.fileUtils, BeanRelayApiConfig::class.java, ::BeanRelayApiConfig)
            val code = if (runningPureStartMode) previousCode else RuntimeConfigCandidate.read(BeanRelayRoomCodeConfig.fileUtils, BeanRelayRoomCodeConfig::class.java, ::BeanRelayRoomCodeConfig)
            val text = if (runningPureStartMode) previousText else RuntimeConfigCandidate.read(BeanRelayTextAuditConfig.fileUtils, BeanRelayTextAuditConfig::class.java, ::BeanRelayTextAuditConfig)
            val license = RuntimeConfigCandidate.read(BeanRelayLicenseConfig.fileUtils, BeanRelayLicenseConfig::class.java, ::BeanRelayLicenseConfig)
            ReloadCandidate(core, server, start, permission, access, relay, api, code, text, license)
        }.getOrElse { error ->
            log("Config reload rejected before applying values: ${error.javaClass.simpleName}: ${error.message}")
            return
        }
        if (previousCore.port != candidate.core.port || previousCore.webPort != candidate.core.webPort ||
            previousCore.rconPort != candidate.core.rconPort || previousServer.maxPlayer != candidate.server.maxPlayer ||
            previousServer.singleRoomLowLatency.nettyWorkerThreadsOverride != candidate.server.singleRoomLowLatency.nettyWorkerThreadsOverride) {
            log("Config reload requires a restart: changed ports, maxPlayer or Netty worker count. All running values were kept; restart to apply the file.")
            return
        }
        val previousEngine = try { StartModeGameOptionSupport.captureEngineLobbyOptions() } catch (error: Exception) {
            log("Config reload rejected: cannot snapshot engine settings. No runtime references changed.")
            return
        }
        Data.config = candidate.core
        Data.configServer = candidate.server
        Data.configStartMode = candidate.start
        Data.configRoomPermission = candidate.permission
        Data.configPersistentAccess = candidate.access
        Data.configRelay = candidate.relay
        Data.configRelayApi = candidate.api
        Data.configRelayRoomCode = candidate.code
        Data.configRelayTextAudit = candidate.text
        Data.configRelayLicense = candidate.license
        try {
            check(StartModeGameOptionSupport.applyConfiguredLobbyDefaultsToCurrentHeadless()) { "lobby_game_thread_apply_failed" }
        } catch (error: Exception) {
            Data.config = previousCore; Data.configServer = previousServer; Data.configStartMode = previousStart
            Data.configRoomPermission = previousPermission; Data.configPersistentAccess = previousAccess
            Data.configRelay = previousRelay; Data.configRelayApi = previousApi; Data.configRelayRoomCode = previousCode
            Data.configRelayTextAudit = previousText; Data.configRelayLicense = previousLicense
            StartModeGameOptionSupport.restoreLobbyOptionState(previousOptions)
            val restored = runCatching { StartModeGameOptionSupport.restoreEngineLobbyOptions(previousEngine) }.getOrDefault(false)
            log("Config reload failed; runtime references rolled back. Engine rollback=${restored}: ${error.message}")
            return
        }
        StartModeSurrenderSupport.onSurrenderModeConfigReload(previousStart.enableIndividualSurrender, candidate.start.enableIndividualSurrender)
        StartModePersistentAccessSupport.applyConfiguredAccessToRuntime(migrateRuntime = false)
        Data.pureStartMode = runningPureStartMode

        if (runningPureStartMode) {
            Data.configRelay = BeanRelayConfig(
                mainID = "CNKD-START",
                mainServer = false,
                upList = Data.config.autoUpList,
                decision = net.rwhps.server.data.bean.BeanRelayDecisionConfig(
                    enableSmartPublicRoomDispatch = false,
                    fallbackToCoreWhenNoNodeAvailable = false,
                    enableNodeStatusAutoRefresh = false,
                    uplistAdvertisedMaxPlayerSize = Data.configServer.maxPlayer.coerceAtLeast(1),
                    reserveServerListSlot = false,
                    enableSinglePublicRoomPerIpLimit = false,
                    recyclePublicRoomOnStartGame = false,
                    enableRelayRoomRecycle = false
                ),
                monitoring = net.rwhps.server.data.bean.BeanRelayMonitoringConfig(
                    apiEnable = false
                ),
                self = net.rwhps.server.data.bean.BeanRelaySelfConfig(
                    displayName = Data.config.serverName,
                    shortName = "CNKD",
                    joinBaseUrl = "",
                    shareRoomNodeCode = "",
                    listOpenText = "List open"
                )
            )
            Data.configRelayApi = BeanRelayApiConfig(
                enableStatusPage = false,
                enableStatusApi = false,
                enableRoomsApi = false,
                enableAdminApi = false,
                roomsApiRequireWhitelist = true,
                adminApiRequireWhitelist = true,
                corsAllowAllOrigins = false
            )
            Data.configRelayRoomCode = BeanRelayRoomCodeConfig(
                enable = false,
                localNodeCode = ""
            )
            Data.configRelayTextAudit = BeanRelayTextAuditConfig(enable = false)
        }

        if (HeadlessModuleManage.initHPS()) {
            StartModeMapLockSupport.applyLockedMapIfNeeded()
            StartModeLobbySupport.onServerStarted()
        }
        UpListMain.onConfigReload("reloadconfig")

        log("Reloaded runtime config; lobby income/unit limit/credits/fog/nukes applied.")
        log("autoUpList=${Data.config.autoUpList}, listExperimentEnabled=${Data.config.listExperimentEnabled}, mapLock=${Data.configStartMode.enableMapLock}, lockMap=${Data.configStartMode.lockMap}")
        log("persistentAccess=${Data.configPersistentAccess.enable}, persistentBlacklist=${Data.configPersistentAccess.persistentBlacklistEnabled}, persistentAdmin=${Data.configPersistentAccess.persistentAdminEnabled}")
        if (RuntimeModeSupport.isPureStartCommand(Data.config.defStartCommand) != runningPureStartMode) {
            log("Note: defStartCommand changed, but current process mode will stay as-is until restart.")
        }
    }

    private fun renderStartModeDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== STARTMODE =========="
        lines += "pureStartMode=${Data.pureStartMode}"
        lines += "defStartCommand=${Data.config.defStartCommand}"
        lines += "port=${Data.config.port}"
        lines += "serverName=${Data.config.serverName}"
        lines += "autoUpList=${Data.config.autoUpList}"
        lines += "maxPlayer=${Data.configServer.maxPlayer}"
        lines += "saveRePlayFile=${Data.configServer.saveRePlayFile}"
        lines += "roomPermissionEnabled=${Data.configRoomPermission.enable}"
        lines += "mapLockEnabled=${Data.configStartMode.enableMapLock}"
        lines += "lockMap=${Data.configStartMode.lockMap.ifBlank { "<blank>" }}"
        lines += "survivalMode=${Data.configStartMode.enableSurvivalMapMode}"
        lines += "freeForAllMode=${Data.configStartMode.enableFreeForAllMode}"
        lines += "alternateTeamsOnJoin=${Data.configStartMode.enableAlternateTeamsOnJoin}"
        StartModeAutoStartSupport.buildAutoStartDiagnostics()
            .entries
            .forEach { lines += "autostart.${it.key}=${it.value}" }
        lines += StartModeLobbySupport.buildDiagnostics()
        lines += StartModeGameOptionSupport.buildDiagnostics()
        lines += StartModeReadinessSupport.buildDiagnostics()
        lines += StartModeGameOverReturnSupport.buildDiagnostics()
        lines += renderStartModeRoomStateDiagnostics()
        lines += StartModePlayerExperienceSupport.buildDiagnostics()
        lines += StartModeMapLockSupport.buildDiagnostics()
        lines += StartModeRoomPermissionSupport.buildDiagnostics()
        lines += StartModePersistentAccessSupport.buildDiagnostics()
        lines += renderNoControlIncidentTriageDiagnostics()
        lines += Threads.buildDiagnostics()
        return lines
    }

    private fun renderStartModeRoomStateDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== ROOM STATE =========="
        if (!HeadlessModuleManage.initHPS()) {
            lines += "room.hpsReady=false"
            lines += "flow.stateConsistency=unknown"
            lines += "flow.stateConsistencyReason=headless_not_ready"
            return lines
        }

        val room = HeadlessModuleManage.hps.room
        val roomStarted = room.isStartGame
        val resetInProgress = room.isGameOverResetInProgress()
        val gameOverState = StartModeGameOverReturnSupport.currentState
        val mapEngineReady = SingleRoomLowLatencySupport.isMapEngineReady()
        val consistencyReason = startModeRoomStateConsistencyReason(roomStarted, resetInProgress)
        val consistency = startModeRoomStateConsistency(consistencyReason)
        val presence = room.playerManage.buildPlayerPresenceSnapshot()

        lines += "room.hpsReady=true"
        lines += "room.started=$roomStarted"
        lines += "room.resetInProgress=$resetInProgress"
        lines += "room.resetInProgressAgeMs=${room.gameOverResetAgeMillis()}"
        lines += "room.battleRoomTransitionInProgress=${room.isBattleRoomTransitionInProgress()}"
        lines += "room.battleRoomTransitionAgeMs=${room.battleRoomTransitionAgeMillis()}"
        lines += "room.battleRoomTransitionPhase=${room.battleRoomTransitionPhase()}"
        lines += "room.lobbyPlayerSyncCooldownRemainingMs=${room.lobbyPlayerSyncCooldownRemainingMillis()}"
        lines += "room.lobbyLifecycleGeneration=${room.lobbyLifecycleGeneration()}"
        lines += "room.playerGroupCount=${presence.connectedOccupants}"
        lines += "room.playerAllCount=${presence.reservedOccupants}"
        lines += "room.connectedHumanCount=${presence.connectedHumanCount}"
        lines += "room.graceProtectedHumanCount=${presence.graceProtectedHumanCount}"
        lines += "room.reconnectGraceHumanCount=${presence.reconnectGraceHumanCount}"
        lines += "room.map=${room.maps.mapName}"
        lines += "room.mapEngineReady=$mapEngineReady"
        lines += "flow.gameOverState=${gameOverState.name}"
        lines += "flow.gameCommandHotPathReady=${SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(roomStarted, resetInProgress)}"
        lines += "flow.stateConsistency=$consistency"
        lines += "flow.stateConsistencyReason=$consistencyReason"
        return lines
    }

    private fun renderNoControlIncidentTriageDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== NO CONTROL TRIAGE =========="
        if (!HeadlessModuleManage.initHPS()) {
            lines += "noControl.triage.level=action"
            lines += "noControl.triage.primary=headless_not_ready"
            lines += "noControl.triage.next=startmode_bootcheck_or_startup_log"
            return lines
        }

        val room = HeadlessModuleManage.hps.room
        val roomStarted = room.isStartGame
        val resetInProgress = room.isGameOverResetInProgress()
        val gameOverState = StartModeGameOverReturnSupport.currentState
        val mapEngineReady = SingleRoomLowLatencySupport.isMapEngineReady()
        val consistencyReason = startModeRoomStateConsistencyReason(roomStarted, resetInProgress)
        val consistency = startModeRoomStateConsistency(consistencyReason)
        val hotPathReady = SingleRoomLowLatencySupport.isGameCommandHotPathRoomReady(roomStarted, resetInProgress)
        val presence = room.playerManage.buildPlayerPresenceSnapshot()
        val playerCount = presence.connectedOccupants
        val reservedPlayerCount = presence.reservedOccupants
        val players = noControlPlayerSnapshots()
        val humanCount = players.count { !it.isAi }
        val controllableHumanCount = players.count { !it.isAi && !it.isSpectator && it.connectionBound && !it.stale }
        val spectatorHumanCount = players.count { !it.isAi && it.isSpectator }
        val staleHumanCount = players.count { !it.isAi && it.stale }
        val unboundHumanCount = players.count { !it.isAi && !it.connectionBound }

        val primary = when {
            consistency == "warn" -> "room_state_mismatch"
            resetInProgress || StartModeGameOverReturnSupport.isSettling() -> "reset_or_settle_in_progress"
            roomStarted && humanCount > 0 && controllableHumanCount == 0 -> "no_controllable_human_after_start"
            roomStarted && spectatorHumanCount > 0 -> "spectator_human_after_start"
            roomStarted && (staleHumanCount > 0 || unboundHumanCount > 0) -> "stale_or_unbound_human_after_start"
            roomStarted && !mapEngineReady -> "map_engine_not_ready_in_started_room"
            roomStarted && !hotPathReady -> "hot_path_not_ready_in_started_room"
            roomStarted && playerCount > 0 -> "server_flow_ok_check_gamecommand_metrics"
            !roomStarted -> "lobby_or_not_started"
            else -> "unknown"
        }
        val level = when (primary) {
            "room_state_mismatch",
            "no_controllable_human_after_start",
            "stale_or_unbound_human_after_start",
            "map_engine_not_ready_in_started_room",
            "hot_path_not_ready_in_started_room",
            "unknown" -> "action"

            "reset_or_settle_in_progress",
            "spectator_human_after_start" -> "watch"

            else -> "observe"
        }
        val resetOrSettleRecheckSeconds = StartModeGameOverReturnSupport.recommendedSettleRecheckSeconds()
        val next = when (primary) {
            "room_state_mismatch" -> "capture_startmode_diagnose_then_restart_room_if_reproducible"
            "reset_or_settle_in_progress" -> "wait_${resetOrSettleRecheckSeconds}s_then_recheck_startmode_diagnose"
            "no_controllable_human_after_start" -> "move_at_least_one_human_out_of_spectator_or_fix_slot_before_retest"
            "spectator_human_after_start" -> "verify_affected_player_is_not_spectator_then_check_gamecommand_received"
            "stale_or_unbound_human_after_start" -> "ask_player_to_reconnect_then_check_startmode_nocontrol_again"
            "map_engine_not_ready_in_started_room" -> "capture_startmode_nocontrol_and_restart_the_instance_if_it_persists_beyond_map_load"
            "hot_path_not_ready_in_started_room" -> "run_lowlatency_status_readout_summary"
            "server_flow_ok_check_gamecommand_metrics" -> "run_lowlatency_readout_summary_advise"
            "lobby_or_not_started" -> "start_game_then_recheck_after_first_player_operation"
            else -> "collect_startmode_diagnose_lowlatency_status_readout"
        }

        lines += "noControl.triage.level=$level"
        lines += "noControl.triage.primary=$primary"
        lines += "noControl.triage.next=$next"
        lines += "noControl.room.started=$roomStarted"
        lines += "noControl.room.resetInProgress=$resetInProgress"
        lines += "noControl.room.resetInProgressAgeMs=${room.gameOverResetAgeMillis()}"
        lines += "noControl.room.recheckAfterSeconds=$resetOrSettleRecheckSeconds"
        lines += "noControl.room.playerCount=$playerCount"
        lines += "noControl.flow.gameOverState=${gameOverState.name}"
        lines += "noControl.map.engineReady=$mapEngineReady"
        lines += "noControl.flow.stateConsistency=$consistency"
        lines += "noControl.flow.stateConsistencyReason=$consistencyReason"
        lines += "noControl.flow.gameCommandHotPathReady=$hotPathReady"
        lines += "noControl.room.reservedPlayerCount=$reservedPlayerCount"
        lines += "noControl.permission.autoAdminAssignmentEffective=${StartModeRoomPermissionSupport.allowAutoAdminAssignment()}"
        lines += "noControl.permission.autoAdminTransferOnLeaveEffective=${StartModeRoomPermissionSupport.allowAutoAdminTransferOnLeave()}"
        lines += "noControl.players.human=$humanCount"
        lines += "noControl.players.controllableHuman=$controllableHumanCount"
        lines += "noControl.players.spectatorHuman=$spectatorHumanCount"
        lines += "noControl.players.staleHuman=$staleHumanCount"
        lines += "noControl.players.unboundHuman=$unboundHumanCount"
        lines += noControlLowLatencySnapshot()
        lines += StartModeGameCommandCompatibilitySupport.buildDiagnostics()
        players.take(16).forEachIndexed { index, snapshot ->
            lines += "noControl.player.$index=${snapshot.render()}"
        }
        if (players.size > 16) {
            lines += "noControl.players.truncated=${players.size - 16}"
        }
        return lines
    }

    private fun noControlLowLatencySnapshot(): List<String> {
        val prefixes = listOf(
            "singleRoomLowLatency.testSummary.commands=",
            "singleRoomLowLatency.testSummary.commandTypes=",
            "singleRoomLowLatency.testSummary.frameQueue=",
            "singleRoomLowLatency.testSummary.preTick=",
            "singleRoomLowLatency.testSummary.bridge=",
            "singleRoomLowLatency.testSummary.latency=",
            "singleRoomLowLatency.testSummary.nextRound=",
            "experience.bottleneck=",
            "experience.action=",
            "experience.command=",
            "experience.reason="
        )
        return listOf("noControl.lowLatency.embedded=true") +
            SingleRoomLowLatencySupport.buildTestSummary().filter { line ->
                prefixes.any { prefix -> line.startsWith(prefix) }
            }
    }

    private fun noControlPlayerSnapshots(): List<NoControlPlayerSnapshot> {
        if (!HeadlessModuleManage.initHPS()) {
            return emptyList()
        }

        val room = HeadlessModuleManage.hps.room
        val snapshots = mutableListOf<NoControlPlayerSnapshot>()
        room.playerManage.playerGroup.eachAll { player ->
            snapshots += NoControlPlayerSnapshot(
                name = sanitizeDiagnosticToken(runCatching { player.name }.getOrDefault("<unknown>")),
                position = runCatching { player.position }.getOrDefault(-1),
                index = runCatching { player.index }.getOrDefault(-1),
                team = runCatching { player.team }.getOrDefault(Int.MIN_VALUE),
                isSpectator = runCatching { StartModeLobbySupport.isSpectatorTeam(player.team) }.getOrDefault(false),
                isAi = runCatching { player.isAi }.getOrDefault(false),
                connectionBound = runCatching { player.con != null }.getOrDefault(false),
                stale = runCatching { room.playerManage.isStaleHumanPlayer(player) }.getOrDefault(false),
                survive = runCatching { player.survive }.getOrDefault(false),
                connectIdTail = runCatching { player.connectHexID.takeLast(6) }.getOrDefault("")
            )
        }
        return snapshots.sortedWith(compareBy<NoControlPlayerSnapshot> { it.position }.thenBy { it.name })
    }

    private fun sanitizeDiagnosticToken(raw: String): String {
        return raw.replace(Regex("\\s+"), "_").take(32).ifBlank { "<blank>" }
    }

    private data class NoControlPlayerSnapshot(
        val name: String,
        val position: Int,
        val index: Int,
        val team: Int,
        val isSpectator: Boolean,
        val isAi: Boolean,
        val connectionBound: Boolean,
        val stale: Boolean,
        val survive: Boolean,
        val connectIdTail: String
    ) {
        fun render(): String {
            return "name=$name,pos=$position,index=$index,team=$team,spectator=$isSpectator,ai=$isAi,conn=$connectionBound,stale=$stale,survive=$survive,idTail=$connectIdTail"
        }
    }

    private fun startModeRoomStateConsistencyReason(roomStarted: Boolean, resetInProgress: Boolean): String {
        val gameOverState = StartModeGameOverReturnSupport.currentState
        return when {
            resetInProgress -> "reset_in_progress"
            roomStarted && StartModeGameOverReturnSupport.isPlaying() -> "ok"
            !roomStarted && gameOverState == StartModeGameOverReturnSupport.GameState.LOBBY -> "ok"
            StartModeGameOverReturnSupport.isSettling() -> "settling"
            roomStarted && !StartModeGameOverReturnSupport.isPlaying() -> "started_not_playing"
            !roomStarted && StartModeGameOverReturnSupport.isPlaying() -> "not_started_but_playing"
            else -> "unexpected"
        }
    }

    private fun startModeRoomStateConsistency(reason: String): String {
        return when (reason) {
            "ok", "settling", "reset_in_progress" -> "ok"
            else -> "warn"
        }
    }

    init {
        registerCore(handler)
        registerCorex(handler)
        registerInfo(handler)
        registerStartServer(handler)


        // Test (孵化器）
        if (System.getenv("CNKD_STARTMODE_HOTLOAD_TEST_ENABLED")?.equals("true", ignoreCase = true) == true) {
            handler.register("log", "[a...]", "serverCommands.exit") { _: Array<String>, _: StrCons ->
                HotLoadClass().load(FileUtils.getFile("a.class").readFileByte())
            }
        }
    }
}
