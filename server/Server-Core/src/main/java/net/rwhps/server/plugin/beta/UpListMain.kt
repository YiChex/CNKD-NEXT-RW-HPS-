/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.bean.BeanPublicListEntry
import net.rwhps.server.data.bean.BeanRelayNodeConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.event.EventManage
import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.game.event.game.ServerGameOverEvent
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.net.NetService
import net.rwhps.server.net.api.RelayDeploymentSupport
import net.rwhps.server.net.api.RelayCustomRoomSupport
import net.rwhps.server.net.api.RelayLicenseSupport
import net.rwhps.server.net.api.RelayNodeRoutingSupport
import net.rwhps.server.net.api.RelayPlayerNoticeSupport
import net.rwhps.server.net.api.RelayPublicRoomSupport
import net.rwhps.server.net.api.RelayTextAuditSupport
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.plugin.Plugin
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.util.IpUtils
import net.rwhps.server.util.algorithms.Base64
import net.rwhps.server.util.algorithms.digest.DigestUtils
import net.rwhps.server.util.annotations.core.EventListenerHandler
import net.rwhps.server.util.file.json.Json
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.inline.ifEmptyResult
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.str.StringFilteringUtil.cutting
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread


internal class UpListMain: Plugin() {
    companion object {
        private const val AUTO_UPLIST_BOOTSTRAP_TASK = "AutoUpListBootstrap"
        private const val AUTO_UPLIST_HEADLESS_WAIT_TASK = "AutoUpListHeadlessWait"
        private const val UPLIST_TASK_GROUP = "UpList"
        private const val MASTER_SERVER_PRIVATE_TOKEN = "rs67nMZSgL8czc3uCQ7riylkpSKkUzbl7GqEjEbo"
        private const val MASTER_SERVER_PRIVATE_TOKEN_2 = "a16d57a220efb3edcfcca5111729226a"
        private const val MASTER_SERVER_CONFIRM = "f112b4b91e2d2444f7302066a79bee8e"
        private const val GS1_MASTER_SERVER_URL = "http://gs1.corrodinggames.com/masterserver/1.4/interface"
        private const val GS4_MASTER_SERVER_URL = "http://gs4.corrodinggames.net/masterserver/1.4/interface"
        private const val UPLIST_VISIBILITY_CHECK_INTERVAL_MILLIS = 60_000L
        private const val UPLIST_VISIBILITY_GRACE_MILLIS = 90_000L
        private const val UPLIST_VISIBILITY_LOG_INTERVAL_MILLIS = 120_000L
        private const val UPLIST_VISIBILITY_UNKNOWN_WARN_COUNT = 3
        private const val UPLIST_VISIBILITY_MISSING_CONFIRMATIONS = 3
        private const val UPLIST_ENDPOINT_REPAIR_INTERVAL_MILLIS = 180_000L
        private const val UPLIST_DEFAULT_UPDATE_INTERVAL_SECONDS = 50
        private const val UPLIST_RELAY_MIN_UPDATE_INTERVAL_SECONDS = 45
        private const val UPLIST_MASTER_REQUEST_MIN_INTERVAL_MILLIS = 350L
        @Volatile
        private var instance: UpListMain? = null
        @Volatile
        private var pendingAutoStart = false
        private val headlessInitTimeoutFallbackScheduled = AtomicBoolean(false)

        @JvmStatic
        fun requestRefresh(force: Boolean = false) {
            instance?.requestRefreshInternal(force)
        }

        @JvmStatic
        fun onConfigReload(reason: String = "configReload") {
            instance?.onConfigReloadInternal(reason)
        }

        @JvmStatic
        fun resolveRoomIdFromListQuery(query: String): String? {
            return instance?.resolveRoomIdFromListQueryInternal(query)
        }

        @JvmStatic
        fun resolveRoomIdFromListPort(port: Int): String? {
            return instance?.resolveRoomIdFromListPortInternal(port)
        }

        @JvmStatic
        fun forceReopenRoomEntry(roomId: String? = null) {
            instance?.forceReopenRoomEntryInternal(roomId)
        }

        @JvmStatic
        fun disableForLicense() {
            instance?.disableForLicenseInternal()
        }

        @JvmStatic
        fun ensureAutoUpListStarted(reason: String = "autoUpList") {
            if (!Data.config.autoUpList) {
                pendingAutoStart = false
                return
            }

            if (instance == null) {
                /*
                 * D-22: this used to construct a fallback `UpListMain()` directly, which
                 * bypassed PluginManage.init()'s archive validation, the fail-closed
                 * plugin-lock supply-chain check, descriptor/ABI validation and the
                 * plugin lifecycle state machine -- and could leave two live UPLIST
                 * instances. Wait for the managed instance instead.
                 */
                pendingAutoStart = true
                Log.clog("[AutoUpList] Plugin instance is not initialized yet; deferring UPLIST start ($reason)")
                scheduleHeadlessWaitRetry(reason)
                return
            }

            if (!HeadlessModuleManage.initHPS()) {
                pendingAutoStart = true
                Log.clog("[AutoUpList] Waiting for headless initialization before starting UPLIST ($reason)")
                scheduleHeadlessWaitRetry(reason)
                scheduleHeadlessInitTimeoutFallback(reason)
                return
            }

            Threads.closeTimeTask(AUTO_UPLIST_HEADLESS_WAIT_TASK, UPLIST_TASK_GROUP)
            if (!RelayLicenseSupport.allowUpList()) {
                pendingAutoStart = true
                val status = RelayLicenseSupport.runtimeStatus()
                if (RelayLicenseSupport.localNoLicenseModeDisablesUpList()) {
                    Log.clog("[AutoUpList] UPLIST is disabled by this no-license/no-uplist build. Current state: ${status.lastMessage}.")
                } else {
                    Log.clog("[AutoUpList] Waiting for uplist authorization. Current state: ${status.lastMessage}. Please configure data/RelayLicenseConfig.json or contact your distribution/operator for authorization.")
                }
                return
            }

            val current = instance
            if (current == null) {
                pendingAutoStart = true
                return
            }

            pendingAutoStart = false
            current.ensureAutoUpListStartedInternal(reason)
        }

        private fun scheduleHeadlessWaitRetry(reason: String) {
            if (Threads.containsTimeTask(AUTO_UPLIST_HEADLESS_WAIT_TASK, UPLIST_TASK_GROUP)) {
                return
            }
            Threads.newTimedTask(
                AUTO_UPLIST_HEADLESS_WAIT_TASK,
                UPLIST_TASK_GROUP,
                "Retry auto UPLIST until headless initialization is ready",
                5,
                5,
                TimeUnit.SECONDS
            ) {
                if (!Data.config.autoUpList) {
                    pendingAutoStart = false
                    Threads.closeTimeTask(AUTO_UPLIST_HEADLESS_WAIT_TASK, UPLIST_TASK_GROUP)
                    return@newTimedTask
                }
                if (!HeadlessModuleManage.initHPS()) {
                    return@newTimedTask
                }
                Threads.closeTimeTask(AUTO_UPLIST_HEADLESS_WAIT_TASK, UPLIST_TASK_GROUP)
                ensureAutoUpListStarted("headlessReadyRetry:$reason")
            }
        }

        private fun scheduleHeadlessInitTimeoutFallback(reason: String) {
            if (!headlessInitTimeoutFallbackScheduled.compareAndSet(false, true)) {
                return
            }
            thread(start = true, isDaemon = true, name = "AutoUpList-headless-timeout") {
                try {
                    TimeUnit.SECONDS.sleep(20)
                    Log.clog("[AutoUpList] Headless timeout fallback check ($reason)")
                    if (!Data.config.autoUpList || !RelayLicenseSupport.allowUpList()) {
                        return@thread
                    }
                    // D-22: never build an unmanaged instance; give up explicitly instead.
                    val current = instance
                    if (current == null) {
                        Log.warn(
                            "[AutoUpList] Headless init timeout fallback reached, but the plugin instance is still " +
                                "not initialized. Refusing to create an unmanaged instance; UPLIST was not started ($reason)."
                        )
                        return@thread
                    }
                    if (!HeadlessModuleManage.initHPS()) {
                        Log.clog("[AutoUpList] Headless initialization flag is still not ready; forcing UPLIST startup after timeout ($reason)")
                    }
                    pendingAutoStart = false
                    current.ensureAutoUpListStartedInternal("headlessInitTimeout:$reason")
                } finally {
                    headlessInitTimeoutFallbackScheduled.set(false)
                }
            }
        }

        @JvmStatic
        fun notifyHeadlessLobbyStateChanged(reason: String = "stateChange") {
            instance?.notifyHeadlessLobbyStateChangedInternal(reason)
        }
    }

    private val version = "Version=HPS#1"
    private val listNameMaxLength = 15
    private val listNamePinnedPrefix: String
        get() = "\u0001".repeat(Data.config.listNamePinnedCharacterCount.coerceIn(0, 64))
    private val listMapPinnedPrefix: String
        get() = "\u0001".repeat(Data.config.listPinnedCharacterCount.coerceIn(0, 64))
    private val defaultContextKey = "__default__"
    private val defaultContextKeyPrefix = "$defaultContextKey:"
    private val maxPublicListEntryCount = 8
    private val privateIp: String
        get() {
            var privateIpTemp = IpUtils.getPrivateIp()
            if (privateIpTemp.isNullOrBlank()) {
                privateIpTemp = "10.0.0.1"
            }
            return privateIpTemp
        }
    @Volatile
    private var port = Data.config.port.toString()

    private var versionBeta = false
    private var versionGame = "1.15"
    private var versionGameInt = 176

    @Volatile
    private var upServerList = false
    private val uplistAttemptInProgress = AtomicBoolean(false)
    private val pendingDeactivation = AtomicReference<PendingDeactivation?>()

    private val uplistContexts = ConcurrentHashMap<String, UplistEntryContext>()
    private val lastRefreshRequestAt = AtomicLong(0L)
    @Volatile
    private var scheduledUpdateIntervalSeconds = 0

    @Volatile
    private var uplistFlag = 0
    @Volatile
    private var persistentUplistDesired = false
    private val gs1RequestThrottle = UpListRequestThrottle(UPLIST_MASTER_REQUEST_MIN_INTERVAL_MILLIS)
    private val gs4RequestThrottle = UpListRequestThrottle(UPLIST_MASTER_REQUEST_MIN_INTERVAL_MILLIS)
    @Volatile
    private var gs1Health = MasterServerHealthState("gs1")
    @Volatile
    private var gs4Health = MasterServerHealthState("gs4")
    @Volatile
    private var lastBootstrapRetryLogAt = 0L
    @Volatile
    private var lastPartialRetryLogAt = 0L

    init {
        instance = this
        thread(start = true, isDaemon = true, name = "AutoUpList-init-safety") {
            TimeUnit.SECONDS.sleep(12)
            ensureAutoUpListStarted("pluginConstructSafety")
        }
    }

    override fun init() {
        instance = this
        AddLang(this)
        if (Data.config.listExperimentEnabled) {
            Log.warn(
                "[ListExperiment] Public list upload field experiment is enabled. game_name='${Data.config.listExperimentGameName}', created_by='${Data.config.listExperimentCreatedBy}', game_map='${Data.config.listExperimentMapName}', game_status='${Data.config.listExperimentGameStatus}', player_count=${Data.config.listExperimentPlayerCount}, max_player_count=${Data.config.listExperimentMaxPlayerCount}"
            )
        }
        if (Data.config.listPromotionModeEnabled) {
            Log.warn(
                "[ListPromotion] Public list promotion mode is enabled. game_status=${resolvePromotionGameStatus()}, player_count=${Data.config.listPromotionPlayerCount}, max_player_count=${Data.config.listPromotionMaxPlayerCount}"
            )
            if (Data.config.listPromotionSortBoostEnabled) {
                Log.warn(
                    "[ListPromotion] Safe sort boost is enabled. It changes only public-list display fields and does not create fake mod mismatch rooms."
                )
            }
        }
        if (pendingAutoStart && Data.config.autoUpList && RelayLicenseSupport.allowUpList() && HeadlessModuleManage.initHPS()) {
            pendingAutoStart = false
            ensureAutoUpListStartedInternal("pluginInit")
        }
        if (Data.config.autoUpList) {
            Threads.newThreadOther {
                TimeUnit.SECONDS.sleep(8)
                ensureAutoUpListStarted("pluginInitSafety")
            }
        }
    }

    override fun registerEvents(eventManage: EventManage) {
        eventManage.registerListener(object: EventListenerHost {
            @EventListenerHandler
            fun gameover(gameOverEvent: ServerGameOverEvent) {
                notifyHeadlessLobbyStateChangedInternal("pluginGameOver")
            }
        })
    }

    override fun registerCoreCommands(handler: CommandHandler) {
        handler.removeCommand("upserverlist")
        handler.removeCommand("upserverlistnew")

        handler.register("uplist", "[action] [value]", "serverCommands.upserverlist") { args: Array<String>?, log: StrCons ->
            if (!args.isNullOrEmpty()) {
                when (args[0]) {
                    "add" -> NetStaticData.checkServerStartNet {
                        if (!RelayLicenseSupport.allowUpList()) {
                            log(RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.UPLIST))
                            return@checkServerStartNet
                        }
                        if (args.size > 1) add(log, args[1]) else add(log)
                    }
                    "update" -> NetStaticData.checkServerStartNet {
                        if (!RelayLicenseSupport.allowUpList()) {
                            log(RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.UPLIST))
                            return@checkServerStartNet
                        }
                        update()
                    }
                    "status" -> NetStaticData.checkServerStartNet {
                        renderUpListStatus().forEach { log(it) }
                    }
                    "diagnose" -> NetStaticData.checkServerStartNet {
                        renderUpListDiagnostics().forEach { log(it) }
                    }
                    "remove" -> NetStaticData.checkServerStartNet { remove(log) }
                    "help" -> log(Data.i18NBundle.getinput("uplist.help"))
                    else -> log("Check UpList Command ! use 'uplist help'")
                }
            } else {
                log("Check UpList Command ! use 'uplist help'")
            }
        }

        handler.register("listnow", "", "#显示当前所有 Relay 房间与统计信息") { _: Array<String>?, log: StrCons ->
            renderRelayRoomSummaryFixed().forEach { log(it) }
        }
    }

    override fun registerServerClientCommands(handler: CommandHandler) {
        fun isAdmin(player: PlayerHess): Boolean {
            if (StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                return true
            }
            player.sendSystemMessage(player.i18NBundle.getinput("err.noAdmin"))
            return false
        }

        handler.register("toup", "#up") { _: Array<String>, player: PlayerHess ->
            if (isAdmin(player)) {
                if (!RelayLicenseSupport.allowUpList()) {
                    player.sendSystemMessage(RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.UPLIST))
                    return@register
                }
                when (uplistFlag) {
                    0 -> {
                        thread {
                            player.sendSystemMessage("耐心等待，错误信息不会直接刷给玩家，请结合控制台进行排查。")
                            if (initUpListData()) {
                                persistentUplistDesired = true
                                this.port = port.ifBlank { Data.config.port.toString() }
                                if (!shouldAdvertiseHeadlessLobbyNow()) {
                                    player.sendSystemMessage("当前对局正在进行，已记录持续上列表需求，回到大厅后会自动重新开放。")
                                } else {
                                    startUplistAsync("playerToup", emit = player::sendSystemMessage)
                                }
                            }
                        }
                    }
                    else -> player.sendSystemMessage("Already on the list")
                }

            }
        }
        handler.register("tonp", "#up") { _: Array<String>, player: PlayerHess ->
            if (isAdmin(player)) {
                when (uplistFlag) {
                    1 -> {
                        thread {
                            player.sendSystemMessage("耐心等待")
                            remove(player::sendSystemMessage)
                        }
                    }
                    else -> player.sendSystemMessage("no uplist")
                }
            }
        }
    }

    override fun registerRelayClientCommands(handler: CommandHandler) {
        fun sendMsg(con: GameVersionRelay, msg: String) {
            val sender = con.room?.id?.let { RelayDeploymentSupport.chatSender(it) } ?: "RELAY-CN"
            con.sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(msg, sender, 5))
        }

        fun isRelayHost(con: GameVersionRelay): Boolean {
            val room = con.room ?: return false
            val admin = room.admin ?: return false
            return admin.name == con.name && admin.registerPlayerId == con.registerPlayerId
        }

        fun isRelayAdmin(con: GameVersionRelay): Boolean {
            if (isRelayHost(con)) {
                return true
            }
            sendMsg(con, "你不是房主，无法操作公开列表")
            return false
        }

        handler.register("state", "#查看当前房间状态") { _: Array<String>, con: GameVersionRelay ->
            val room = con.room ?: return@register
            sendMsg(con, RelayPlayerNoticeSupport.buildRoomStatusNotice(room.id, con.name, isRelayHost(con)))
        }

        handler.register("help", "#查看当前房间可用命令") { _: Array<String>, con: GameVersionRelay ->
            val room = con.room ?: return@register
            sendMsg(con, RelayPlayerNoticeSupport.buildRoomCommandHelpNotice(room.id, con.name, isRelayHost(con)))
        }

        handler.register("toup", "#公开房间到列表") { _: Array<String>, con: GameVersionRelay ->
            if (!RelayLicenseSupport.allowUpList()) {
                sendMsg(con, RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.UPLIST))
                return@register
            }
            if (relayServerModeEnabled()) {
                sendMsg(con, "RELAY-SERVER 模式由服务器自动维护列表入口，无需手动 .toup")
                return@register
            }
            if (!isRelayAdmin(con)) {
                return@register
            }

            val room = con.room ?: return@register
            val auditDecision = RelayTextAuditSupport.auditPublicText("room_host", room.admin?.name ?: con.name)
            val mapDecision = RelayTextAuditSupport.auditPublicText("room_map", room.currentMapName)
            if (Data.configRelayTextAudit.denyPublicRoomWhenRejected && (!auditDecision.allowed || !mapDecision.allowed)) {
                sendMsg(con, "公开房间内容未通过文本审核，请调整房主名或地图名后重试")
                return@register
            }
            if (!room.publicRoom && Data.configRelay.decision.enableSinglePublicRoomPerIpLimit) {
                RelayPublicRoomSupport.findConflict(room.admin?.ip ?: con.ip, room.id)?.let {
                    sendMsg(con, RelayPublicRoomSupport.buildConflictMessage(it))
                    return@register
                }
            }
            val ignoreLimit = room.customRoomProfile?.forcePublicRoom == true
            if (!RelayNodeRoutingSupport.canOpenPublicRoomLocally(room.publicRoom, ignoreLimit)) {
                val rebuildGuide = buildRelayPublicRoomRebuildGuide(room)
                if (rebuildGuide != null) {
                    sendMsg(con, renderRelayPublicRoomRebuildGuide(room, rebuildGuide))
                } else {
                    sendMsg(con, RelayNodeRoutingSupport.localPublicRoomLimitMessage())
                }
                return@register
            }
            val wasPublic = room.publicRoom
            room.setPublicRoom(true, top = true)
            if (!wasPublic) {
                RelayPublicRoomSupport.scheduleSuccessNotice(room)
            }
            sendMsg(con, "房间已开放到列表并置顶，开局或房间无人时会自动下列表；返回战备室后可再次使用 .toup")
        }

        handler.register("tonp", "#从列表移除房间") { _: Array<String>, con: GameVersionRelay ->
            if (relayServerModeEnabled()) {
                sendMsg(con, "RELAY-SERVER 模式由服务器自动维护列表入口，无需手动 .tonp")
                return@register
            }
            if (!isRelayAdmin(con)) {
                return@register
            }

            val room = con.room ?: return@register
            if (!RelayCustomRoomSupport.canDisablePublic(room)) {
                sendMsg(con, "此定制房间固定公开，请修改定制房间配置文件后再调整")
                return@register
            }
            room.setPublicRoom(false, top = false)
            sendMsg(con, "房间已从列表移除，如需重新开放请再次使用 .toup")
        }

        handler.register("p", "<size>", "#设置房间人数上限") { args: Array<String>, con: GameVersionRelay ->
            if (!isRelayAdmin(con)) {
                return@register
            }

            val room = con.room ?: return@register
            val targetSize = args.firstOrNull()?.toIntOrNull()
            if (targetSize == null || targetSize !in 2..100) {
                sendMsg(con, "人数上限格式错误，请使用 .p 15 这样的格式，范围 2-100")
                return@register
            }

            con.updateRelayRoomMaxPlayerLimit(targetSize, manualOverride = true)
        }
    }

    private fun initUpListData(): Boolean {
        versionBeta = false
        versionGame = "1.15"
        versionGameInt = 176
        return true
    }

    private fun relayServerModeEnabled(): Boolean {
        return isRelay && RelayCustomRoomSupport.isRelayServerModeEnabled()
    }

    private fun shouldAdvertiseHeadlessLobbyNow(): Boolean {
        if (isRelay) {
            return true
        }
        if (!HeadlessModuleManage.initHPS()) {
            return false
        }
        return true
    }

    private fun notifyHeadlessLobbyStateChangedInternal(reason: String) {
        if (isRelay) {
            return
        }

        Threads.newThreadCore {
            if (!persistentUplistDesired) {
                return@newThreadCore
            }
            if (!RelayLicenseSupport.allowUpList()) {
                disableForLicenseInternal()
                return@newThreadCore
            }
            ensureAutoUpListStartedInternal(reason)
            requestRefreshInternal(force = true)
        }
    }

    private fun deactivateListing(preserveDesired: Boolean, reason: String, retryCount: Int = 5) {
        if (!uplistAttemptInProgress.compareAndSet(false, true)) {
            pendingDeactivation.set(PendingDeactivation(preserveDesired, reason))
            if (retryCount > 0) {
                Threads.newThreadCore {
                    Thread.sleep(1000L)
                    deactivateListing(preserveDesired, reason, retryCount = retryCount - 1)
                }
            } else {
                Log.clog("[AutoUpList] UPLIST attempt already in progress ($reason)")
            }
            return
        }

        try {
            deactivateListingUnlocked(preserveDesired, reason)
        } finally {
            releaseUplistAttempt()
        }
    }

    private fun releaseUplistAttempt() {
        uplistAttemptInProgress.set(false)
        pendingDeactivation.getAndSet(null)?.let { pending ->
            deactivateListing(
                preserveDesired = pending.preserveDesired,
                reason = pending.reason,
                retryCount = 0
            )
        }
    }

    private fun deactivateListingUnlocked(preserveDesired: Boolean, reason: String) {
        val hadActiveListing = upServerList || uplistContexts.isNotEmpty()
        Threads.closeTimeTask(AUTO_UPLIST_BOOTSTRAP_TASK, UPLIST_TASK_GROUP)
        Threads.closeTimeTask(CallTimeTask.CustomUpServerListTask)
        scheduledUpdateIntervalSeconds = 0
        uplistContexts.values.toList().forEach(::removeContext)
        uplistContexts.clear()
        upServerList = false
        uplistFlag = 0
        if (!preserveDesired) {
            persistentUplistDesired = false
        }
        if (hadActiveListing) {
            Log.clog("[AutoUpList] UPLIST paused ($reason)")
        }
    }

    private fun onConfigReloadInternal(reason: String) {
        port = Data.config.port.toString()
        if (!Data.config.autoUpList) {
            deactivateListing(preserveDesired = false, reason = reason)
            pendingAutoStart = false
            return
        }
        if (!HeadlessModuleManage.initHPS()) {
            pendingAutoStart = true
            return
        }
        if (!RelayLicenseSupport.allowUpList()) {
            pendingAutoStart = true
            deactivateListing(preserveDesired = true, reason = "$reason-license")
            return
        }

        persistentUplistDesired = true
        ensureAutoUpListStartedInternal(reason)
        requestRefreshInternal(force = true)
    }

    private fun requestRefreshInternal(force: Boolean = false) {
        if (!upServerList) {
            return
        }
        val now = System.currentTimeMillis()
        val previous = lastRefreshRequestAt.get()
        if (now - previous < 1000L || !lastRefreshRequestAt.compareAndSet(previous, now)) {
            return
        }
        Threads.newThreadCore { update() }
    }

    private fun forceReopenRoomEntryInternal(roomId: String? = null, retryCount: Int = 5) {
        if (!upServerList) {
            return
        }

        Threads.newThreadCore {
            if (!uplistAttemptInProgress.compareAndSet(false, true)) {
                if (retryCount > 0) {
                    Threads.newThreadCore {
                        Thread.sleep(1000L)
                        forceReopenRoomEntryInternal(roomId, retryCount = retryCount - 1)
                    }
                } else {
                    Log.clog("[AutoUpList] UPLIST attempt already in progress (forceReopen)")
                }
                return@newThreadCore
            }

            try {
                if (relayServerModeEnabled()) {
                    val normalizedRoomId = roomId?.takeIf { it.isNotBlank() }
                    val context = normalizedRoomId?.let { uplistContexts[it] }
                        ?: roomId?.takeIf { it.isNotBlank() }?.let {
                            createUpListContext(it).also { newContext -> uplistContexts[it] = newContext }
                        }

                    if (context != null) {
                        removeContext(context)
                        uploadContext(context, Log::clog)
                        updateContext(context)
                    } else {
                        syncRelayServerModeEntries(Log::clog)
                    }
                } else {
                    val normalizedRoomId = roomId?.takeIf { it.isNotBlank() }
                    if (normalizedRoomId != null) {
                        uplistContexts[normalizedRoomId]?.let { context ->
                            removeContext(context)
                            uploadContext(context, Log::clog)
                            updateContext(context)
                        } ?: syncStandardRelayPublicEntries(Log::clog)
                    } else {
                        val contexts = ensureDefaultContexts(Log::clog)
                        if (contexts.isNotEmpty()) {
                            contexts.forEach { context ->
                                removeContext(context)
                                uploadContext(context, Log::clog)
                                updateContext(context)
                            }
                        } else {
                            syncStandardRelayPublicEntries(Log::clog)
                        }
                    }
                }
            } finally {
                releaseUplistAttempt()
            }
        }
    }

    private fun createUpListContext(
        roomId: String? = null,
        listenPortOverride: Int? = null,
        listEntry: ResolvedPublicListEntry? = null
    ): UplistEntryContext {
        val listenPort = listenPortOverride?.takeIf { it > 0 } ?: contextListenPort(roomId)
        val uuid = buildStableServerId(roomId, listenPort, listEntry?.id)
        val timestamp = System.currentTimeMillis().toString()
        val jsonData = """
        {
            "id": "$uuid",
            "add": "action=add&user_id=$uuid&game_name={RW-HPS.LIST.GAME.NAME}&_1=$timestamp&tx2=A7CA&tx3=8142&game_version={RW-HPS.RW.VERSION.INT}&game_version_string={RW-HPS.RW.VERSION}&game_version_beta={RW-HPS.RW.IS.VERSION}&private_token=$MASTER_SERVER_PRIVATE_TOKEN&private_token_2=$MASTER_SERVER_PRIVATE_TOKEN_2&confirm=$MASTER_SERVER_CONFIRM&password_required={RW-HPS.RW.IS.PASSWD}&created_by={RW-HPS.S.NAME}&private_ip={RW-HPS.S.PRIVATE.IP}&port_number={RW-HPS.S.PORT}&game_map={RW-HPS.RW.MAP.NAME}&game_mode=skirmishMap&game_status={RW-HPS.S.STATUS}&player_count={RW-HPS.PLAYER.SIZE}&max_player_count={RW-HPS.PLAYER.SIZE.MAX}",
            "open": "action=self_info&port={RW-HPS.S.PORT}&id=$uuid&tx3=7F5F",
            "update": "action=update&id=$uuid&private_token=$MASTER_SERVER_PRIVATE_TOKEN&game_name={RW-HPS.LIST.GAME.NAME}&password_required={RW-HPS.RW.IS.PASSWD}&created_by={RW-HPS.S.NAME}&private_ip={RW-HPS.S.PRIVATE.IP}&port_number={RW-HPS.S.PORT}&game_map={RW-HPS.RW.MAP.NAME}&game_mode=skirmishMap&game_status={RW-HPS.S.STATUS}&player_count={RW-HPS.PLAYER.SIZE}&max_player_count={RW-HPS.PLAYER.SIZE.MAX}",
            "remove": "action=remove&id=$uuid&private_token=$MASTER_SERVER_PRIVATE_TOKEN"
        }
        """.trimIndent()
        val json = Json(jsonData)
        return UplistEntryContext(
            roomId = roomId,
            listEntry = listEntry,
            listenPort = listenPort,
            serverID = json.getString("id"),
            addData = json.getString("add"),
            openData = json.getString("open"),
            updateData = json.getString("update"),
            removeData = json.getString("remove")
        )
    }

    private fun add(log: StrCons, port: String = "") {
        if (!RelayLicenseSupport.allowUpList()) {
            log(RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.UPLIST))
            return
        }
        persistentUplistDesired = true
        if (upServerList) {
            if (hasActiveUploadedContext()) {
                log("Already on the list")
            } else {
                log("UPLIST is pending, retrying upload")
                Threads.newThreadCore { uplist(Log::clog) }
            }
            return
        }

        this.port = port.ifBlank { Data.config.port.toString() }
        if (!shouldAdvertiseHeadlessLobbyNow()) {
            log("当前对局正在进行，已记录持续上列表需求，回到大厅后会自动重新开放。")
            return
        }
        startUplistAsync("manualAdd")
    }

    private fun ensureAutoUpListStartedInternal(reason: String) {
        persistentUplistDesired = true
        if (!shouldAdvertiseHeadlessLobbyNow()) {
            Log.clog("[AutoUpList] Waiting for lobby state before starting UPLIST ($reason)")
            return
        }
        if (upServerList) {
            if (uplistAttemptInProgress.get()) {
                Log.clog("[AutoUpList] UPLIST attempt already in progress ($reason)")
            } else if (hasPendingUploadContext()) {
                logPartialRetry(reason)
                Threads.newThreadCore { update() }
            } else if (hasActiveUploadedContext()) {
                Log.clog("[AutoUpList] UPLIST already active ($reason)")
            } else {
                Log.clog("[AutoUpList] Retrying pending UPLIST ($reason)")
                Threads.newThreadCore { uplist(Log::clog) }
            }
            return
        }

        port = port.ifBlank { Data.config.port.toString() }
        startUplistAsync(reason)
        scheduleAutoUpListBootstrap()
    }

    private fun startUplistAsync(
        reason: String,
        retryCount: Int = 5,
        emit: (String) -> Unit = Log::clog
    ) {
        Threads.newThreadCore {
            if (!uplistAttemptInProgress.compareAndSet(false, true)) {
                if (retryCount > 0) {
                    Thread.sleep(1000L)
                    startUplistAsync(reason, retryCount - 1, emit)
                } else {
                    Log.clog("[AutoUpList] UPLIST start is waiting for the current task ($reason)")
                }
                return@newThreadCore
            }

            try {
                if (upServerList) {
                    return@newThreadCore
                }
                upServerList = true
                uplistFlag = 1
                uplistContexts.clear()
                Log.clog("[AutoUpList] Starting UPLIST ($reason)")
                uplistUnlocked(emit)
            } finally {
                releaseUplistAttempt()
            }
        }
    }

    private fun uplist(print: (String) -> Unit) {
        if (!uplistAttemptInProgress.compareAndSet(false, true)) {
            return
        }
        try {
            uplistUnlocked(print)
        } finally {
            releaseUplistAttempt()
        }
    }

    private fun uplistUnlocked(print: (String) -> Unit) {
        if (!RelayLicenseSupport.allowUpList()) {
            print(RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.UPLIST))
            disableForLicenseUnlocked()
            return
        }
        if (relayServerModeEnabled()) {
            syncRelayServerModeEntries(print)
        } else {
            ensureDefaultContexts(print)
            syncStandardRelayPublicEntries(print)
        }
        scheduleUpdateTask()
    }

    private fun update() {
        if (!uplistAttemptInProgress.compareAndSet(false, true)) {
            return
        }
        try {
            if (!RelayLicenseSupport.allowUpList()) {
                disableForLicenseUnlocked()
                return
            }
            if (!upServerList) {
                return
            }
            scheduleUpdateTask()
            if (relayServerModeEnabled()) {
                syncRelayServerModeEntries(Log::clog)
            } else {
                ensureDefaultContexts(Log::clog).forEach(::updateContext)
                syncStandardRelayPublicEntries(Log::clog)
            }
            verifyUploadedContextsVisibility()
        } finally {
            releaseUplistAttempt()
        }
    }

    private fun remove(log: StrCons) {
        if (!upServerList && !persistentUplistDesired) {
            log("Not uploaded No deletion is required")
            return
        }

        deactivateListing(preserveDesired = false, reason = "manualRemove")
        log("Deleted UPLIST")
    }

    private fun disableForLicenseInternal(retryCount: Int = 5) {
        if (!uplistAttemptInProgress.compareAndSet(false, true)) {
            pendingDeactivation.set(PendingDeactivation(preserveDesired = true, reason = "licenseDisabled"))
            if (retryCount > 0) {
                Threads.newThreadCore {
                    Thread.sleep(1000L)
                    disableForLicenseInternal(retryCount = retryCount - 1)
                }
            } else {
                Log.clog("[RelayLicense] UPLIST feature disable is waiting for the current list attempt to finish")
            }
            return
        }

        try {
            disableForLicenseUnlocked()
        } finally {
            releaseUplistAttempt()
        }
    }

    private fun disableForLicenseUnlocked() {
        if (!upServerList && uplistContexts.isEmpty()) {
            return
        }
        Threads.closeTimeTask(AUTO_UPLIST_BOOTSTRAP_TASK, UPLIST_TASK_GROUP)
        Threads.closeTimeTask(CallTimeTask.CustomUpServerListTask)
        scheduledUpdateIntervalSeconds = 0
        uplistContexts.values.toList().forEach(::removeContext)
        uplistContexts.clear()
        upServerList = false
        uplistFlag = 0
        Log.clog("[RelayLicense] UPLIST feature disabled by authorization state")
    }

    private fun syncRelayServerModeEntries(print: (String) -> Unit) {
        RelayCustomRoomSupport.maintainRelayServerModeRooms()
        val managedProfiles = RelayCustomRoomSupport.relayServerManagedProfiles()
        val activeRoomIds = managedProfiles.map { it.roomId }.toSet()

        uplistContexts.keys
            .filter { it !in activeRoomIds }
            .toList()
            .forEach { key ->
                uplistContexts.remove(key)?.let(::removeContext)
            }

        managedProfiles.forEach { profile ->
            val key = profile.roomId
            val context = uplistContexts[key]
            val desiredPort = profile.connectPort.takeIf { it > 0 } ?: contextListenPort(profile.roomId)
            if (context == null) {
                createUpListContext(profile.roomId, desiredPort).also {
                    uplistContexts[key] = it
                    uploadContext(it, print)
                }
            } else if (context.listenPort != desiredPort) {
                removeContext(context)
                createUpListContext(profile.roomId, desiredPort).also {
                    uplistContexts[key] = it
                    uploadContext(it, print)
                    updateContext(it)
                }
            } else {
                updateContext(context)
            }
        }
    }

    private fun hasConfiguredPublicListEntries(): Boolean = Data.config.publicListEntries.orEmpty().isNotEmpty()

    private fun isDefaultContextKey(key: String): Boolean =
        key == defaultContextKey || key.startsWith(defaultContextKeyPrefix)

    private fun defaultContextKeyFor(entryId: String): String = "$defaultContextKeyPrefix$entryId"

    private fun normalizedPublicListEntries(): List<ResolvedPublicListEntry> {
        val rawEntries = Data.config.publicListEntries.orEmpty()
        if (rawEntries.isEmpty()) {
            return emptyList()
        }
        if (rawEntries.size > maxPublicListEntryCount) {
            Log.warn("[UPLIST] publicListEntries has ${rawEntries.size} entries; only the first $maxPublicListEntryCount entries will be used")
        }

        val seenIds = linkedSetOf<String>()
        return rawEntries.asSequence()
            .mapIndexedNotNull { index, entry -> normalizePublicListEntry(index, entry) }
            .filter { entry ->
                if (!seenIds.add(entry.id)) {
                    Log.warn("[UPLIST] Ignoring duplicate publicListEntries id=${entry.id}")
                    false
                } else {
                    true
                }
            }
            .filter { it.enabled }
            .take(maxPublicListEntryCount)
            .toList()
    }

    private fun normalizePublicListEntry(index: Int, entry: BeanPublicListEntry): ResolvedPublicListEntry? {
        val id = entry.id.trim().lowercase()
        if (!Regex("^[a-z0-9][a-z0-9_-]{0,39}$").matches(id)) {
            Log.warn("[UPLIST] Ignoring invalid publicListEntries[$index].id=${entry.id}")
            return null
        }
        val serverName = entry.serverName.trim().ifEmpty { Data.config.serverName.trim().ifEmpty { "CNKD节点" } }
        if (serverName.length > 80) {
            Log.warn("[UPLIST] Ignoring publicListEntries[$index] because serverName is too long")
            return null
        }
        val subtitle = entry.subtitle.trim().take(160)
        val mapName = entry.mapName.trim().take(160)
        val maxPlayerCount = entry.maxPlayerCount?.takeIf { it in 1..200 }
        return ResolvedPublicListEntry(
            id = id,
            enabled = entry.enabled,
            serverName = serverName,
            subtitle = subtitle,
            mapName = mapName,
            statusSuffixEnabled = entry.statusSuffixEnabled,
            listNamePinnedCharacterCount = entry.listNamePinnedCharacterCount.coerceIn(0, 64),
            listPinnedCharacterCount = entry.listPinnedCharacterCount.coerceIn(0, 64),
            maxPlayerCount = maxPlayerCount
        )
    }

    private fun ensureDefaultContexts(print: (String) -> Unit): List<UplistEntryContext> {
        val configuredEntries = normalizedPublicListEntries()
        val desiredKeys = linkedSetOf<String>()

        if (configuredEntries.isEmpty()) {
            if (hasConfiguredPublicListEntries()) {
                removeDefaultContextsExcept(emptySet())
                return emptyList()
            }

            desiredKeys += defaultContextKey
            removeDefaultContextsExcept(desiredKeys)
            val existing = uplistContexts[defaultContextKey]
            if (existing != null) {
                existing.listEntry = null
                return listOf(existing)
            }
            return listOf(createUpListContext().also {
                uplistContexts[defaultContextKey] = it
                uploadContext(it, print)
            })
        }

        configuredEntries.forEach { entry -> desiredKeys += defaultContextKeyFor(entry.id) }
        removeDefaultContextsExcept(desiredKeys)
        return configuredEntries.map { entry ->
            val key = defaultContextKeyFor(entry.id)
            val existing = uplistContexts[key]
            if (existing == null) {
                createUpListContext(listEntry = entry).also {
                    uplistContexts[key] = it
                    uploadContext(it, print)
                }
            } else {
                existing.listEntry = entry
                existing
            }
        }
    }

    private fun removeDefaultContextsExcept(desiredKeys: Set<String>) {
        uplistContexts.keys
            .filter(::isDefaultContextKey)
            .filter { it !in desiredKeys }
            .toList()
            .forEach { key ->
                uplistContexts.remove(key)?.let(::removeContext)
            }
    }

    private fun syncStandardRelayPublicEntries(print: (String) -> Unit) {
        val limit = nonRelayDynamicPublicRoomLimit()
        val activeRoomIds = RelayRoom.getPublicRoomSnapshots(Int.MAX_VALUE)
            .filter { !it.started }
            .map { it.roomId }
            .take(limit)

        val assignedPorts = assignStandardRelayPublicPorts(activeRoomIds)

        uplistContexts.keys
            .filter { !isDefaultContextKey(it) && it !in activeRoomIds }
            .toList()
            .forEach { key ->
                uplistContexts.remove(key)?.let(::removeContext)
            }

        activeRoomIds.forEach { roomId ->
            val desiredPort = assignedPorts[roomId] ?: contextListenPort(roomId)
            val existing = uplistContexts[roomId]
            if (existing == null) {
                createUpListContext(roomId, desiredPort).also {
                    uplistContexts[roomId] = it
                    uploadContext(it, print)
                }
            } else if (existing.listenPort != desiredPort) {
                removeContext(existing)
                createUpListContext(roomId, desiredPort).also {
                    uplistContexts[roomId] = it
                    uploadContext(it, print)
                    updateContext(it)
                }
            } else {
                updateContext(existing)
            }
        }
    }

    private fun nonRelayDynamicPublicRoomLimit(): Int {
        return RelayNodeRoutingSupport.localUserPublicRoomLimit().coerceAtLeast(0)
    }

    private fun nonRelayPublicSlotPorts(): List<Int> {
        val limit = nonRelayDynamicPublicRoomLimit()
        if (limit <= 0) {
            return emptyList()
        }

        val mainPort = port.toIntOrNull()?.takeIf { it > 0 } ?: Data.config.port
        val configuredPorts = RelayCustomRoomSupport.relayServerModeConfig().publicPorts
            .map { it.coerceAtLeast(0) }
            .filter { it > 0 && it != mainPort }
        val fallbackPorts = generateSequence(5201) { it + 1 }
            .filter { it != mainPort }
            .take(limit + 8)
            .toList()

        return (configuredPorts + fallbackPorts)
            .distinct()
            .take(limit)
    }

    private fun assignStandardRelayPublicPorts(roomIds: List<String>): Map<String, Int> {
        if (roomIds.isEmpty()) {
            return emptyMap()
        }

        val slotPorts = nonRelayPublicSlotPorts()
        if (slotPorts.isEmpty()) {
            return emptyMap()
        }

        val assigned = linkedMapOf<String, Int>()
        val availablePorts = slotPorts.toMutableList()

        roomIds.forEach { roomId ->
            uplistContexts[roomId]?.listenPort?.takeIf { it in availablePorts }?.let { currentPort ->
                assigned[roomId] = currentPort
                availablePorts.remove(currentPort)
            }
        }

        roomIds.forEach { roomId ->
            if (roomId !in assigned) {
                val nextPort = availablePorts.removeFirstOrNull() ?: return@forEach
                assigned[roomId] = nextPort
            }
        }

        return assigned
    }

    private fun scheduleUpdateTask() {
        val interval = if (relayServerModeEnabled()) {
            RelayCustomRoomSupport.relayServerModeConfig().listUpdateSeconds
                .coerceIn(UPLIST_RELAY_MIN_UPDATE_INTERVAL_SECONDS, 300)
        } else {
            UPLIST_DEFAULT_UPDATE_INTERVAL_SECONDS
        }
        if (
            Threads.containsTimeTask(CallTimeTask.CustomUpServerListTask) &&
            scheduledUpdateIntervalSeconds == interval
        ) {
            return
        }
        if (Threads.containsTimeTask(CallTimeTask.CustomUpServerListTask)) {
            Threads.closeTimeTask(CallTimeTask.CustomUpServerListTask)
        }
        Threads.newTimedTask(CallTimeTask.CustomUpServerListTask, interval, interval, TimeUnit.SECONDS) { update() }
        scheduledUpdateIntervalSeconds = interval
    }

    private fun scheduleAutoUpListBootstrap() {
        if (Threads.containsTimeTask(AUTO_UPLIST_BOOTSTRAP_TASK, UPLIST_TASK_GROUP)) {
            return
        }

        Threads.newTimedTask(
            AUTO_UPLIST_BOOTSTRAP_TASK,
            UPLIST_TASK_GROUP,
            "Retry startup uplist until upload succeeds",
            5,
            10,
            TimeUnit.SECONDS
        ) {
            if (!Data.config.autoUpList || !RelayLicenseSupport.allowUpList() || !HeadlessModuleManage.initHPS()) {
                return@newTimedTask
            }
            if (!hasPendingUploadContext()) {
                Threads.closeTimeTask(AUTO_UPLIST_BOOTSTRAP_TASK, UPLIST_TASK_GROUP)
                return@newTimedTask
            }
            if (!hasRetryablePendingUploadContext()) {
                return@newTimedTask
            }
            if (uplistAttemptInProgress.get()) {
                return@newTimedTask
            }
            if (!canAttemptMasterServerBootstrap()) {
                return@newTimedTask
            }
            logBootstrapRetry()
            ensureAutoUpListStartedInternal("bootstrapRetry")
        }
    }

    private fun uploadContext(context: UplistEntryContext, emit: (String) -> Unit) {
        if (!hasRetryableUploadOpportunity(context)) {
            return
        }

        fun printUpload(stateKey: String, message: String) {
            announceUploadState(context, stateKey, message, emit)
        }

        fun printPort(stateKey: String, message: String) {
            announcePortState(context, stateKey, message, emit)
        }

        if (!ensureLocalGamePortReady(context, emit)) {
            return
        }

        val previousGs1Uploaded = context.gs1Uploaded
        val previousGs4Uploaded = context.gs4Uploaded

        val addGs1Ref = AtomicReference<UploadAttemptResult>()
        val addGs4Ref = AtomicReference<UploadAttemptResult>()
        val addGs1Thread = thread(start = true, isDaemon = true, name = "UpList-gs1-add") {
            addGs1Ref.set(
                if (previousGs1Uploaded) {
                    tryUpdateWithHealth("gs1", GS1_MASTER_SERVER_URL, buildUpdateData(context, "gs1"))
                } else {
                    tryRecoverExistingOrAddWithHealth("gs1", GS1_MASTER_SERVER_URL, context)
                }
            )
        }
        val addGs4Thread = thread(start = true, isDaemon = true, name = "UpList-gs4-add") {
            addGs4Ref.set(
                if (previousGs4Uploaded) {
                    tryUpdateWithHealth("gs4", GS4_MASTER_SERVER_URL, buildUpdateData(context, "gs4"))
                } else {
                    tryRecoverExistingOrAddWithHealth("gs4", GS4_MASTER_SERVER_URL, context)
                }
            )
        }
        addGs1Thread.join()
        addGs4Thread.join()
        val addGs1 = addGs1Ref.get() ?: UploadAttemptResult(false, "")
        val addGs4 = addGs4Ref.get() ?: UploadAttemptResult(false, "")
        if (addGs1.recoveredExisting) {
            Log.clog("[UPLIST] Recovered existing gs1 list entry for ${contextLabel(context)} using id=${context.effectiveServerId("gs1")}")
        }
        if (addGs4.recoveredExisting) {
            Log.clog("[UPLIST] Recovered existing gs4 list entry for ${contextLabel(context)} using id=${context.effectiveServerId("gs4")}")
        }

        val gs1PortOpenRef = AtomicReference<Boolean>()
        val gs4PortOpenRef = AtomicReference<Boolean>()
        val gs1OpenThread = thread(start = true, isDaemon = true, name = "UpList-gs1-open") {
            gs1PortOpenRef.set(queryPortOpen("gs1", GS1_MASTER_SERVER_URL, buildOpenData(context, "gs1")))
        }
        val gs4OpenThread = thread(start = true, isDaemon = true, name = "UpList-gs4-open") {
            gs4PortOpenRef.set(queryPortOpen("gs4", GS4_MASTER_SERVER_URL, buildOpenData(context, "gs4")))
        }
        gs1OpenThread.join()
        gs4OpenThread.join()
        context.gs1PortOpen = gs1PortOpenRef.get() ?: false
        context.gs4PortOpen = gs4PortOpenRef.get() ?: false
        context.portOpen = context.gs1PortOpen || context.gs4PortOpen

        context.gs1Uploaded = when {
            addGs1.success -> true
            previousGs1Uploaded && context.gs1PortOpen -> true
            else -> false
        }
        context.gs4Uploaded = when {
            addGs4.success -> true
            previousGs4Uploaded && context.gs4PortOpen -> true
            else -> false
        }
        val now = System.currentTimeMillis()
        if (addGs1.success && context.gs1Uploaded && (!previousGs1Uploaded || addGs1.recoveredExisting)) {
            context.gs1LastUploadAcceptedAtMillis = now
            context.gs1Visible = null
            context.gs1ConsecutiveVisibilityMissing = 0
        } else {
            context.gs1Visible = if (context.gs1Uploaded) context.gs1Visible else false
        }
        if (addGs4.success && context.gs4Uploaded && (!previousGs4Uploaded || addGs4.recoveredExisting)) {
            context.gs4LastUploadAcceptedAtMillis = now
            context.gs4Visible = null
            context.gs4ConsecutiveVisibilityMissing = 0
        } else {
            context.gs4Visible = if (context.gs4Uploaded) context.gs4Visible else false
        }
        context.uploaded = context.gs1Uploaded || context.gs4Uploaded

        val gs1FailureKind = classifyMasterServerFailure(addGs1.success, addGs1.response)
        val gs4FailureKind = classifyMasterServerFailure(addGs4.success, addGs4.response)

        if (context.isFullyUploaded()) {
            Threads.closeTimeTask(AUTO_UPLIST_BOOTSTRAP_TASK, UPLIST_TASK_GROUP)
            printUpload("full", "${contextLabel(context)} ${Data.i18NBundle.getinput("err.yesList")}")
        } else if (context.hasAnyUploadedServer()) {
            if (shouldAnnounceState(context.lastUploadState, "partial", context.lastUploadStateAtMillis, 60_000L)) {
                val containsRateLimit = gs1FailureKind == MasterServerFailureKind.RATE_LIMITED || gs4FailureKind == MasterServerFailureKind.RATE_LIMITED
                val containsCooldown = gs1FailureKind == MasterServerFailureKind.COOLDOWN_ACTIVE || gs4FailureKind == MasterServerFailureKind.COOLDOWN_ACTIVE
                if (containsRateLimit || containsCooldown) {
                    Log.clog("[UPLIST] Partial upload is active. Missing list servers are rate-limited or cooling down and will retry later.")
                } else {
                    Log.warn("[UPLIST] Partial upload detected. Missing list servers will keep retrying.")
                }
                Log.warn("[UPLIST] gs1 add success=${context.gs1Uploaded}, portOpen=${context.gs1PortOpen}, kind=$gs1FailureKind, response=${addGs1.response.ifBlank { "<empty>" }}")
                Log.warn("[UPLIST] gs4 add success=${context.gs4Uploaded}, portOpen=${context.gs4PortOpen}, kind=$gs4FailureKind, response=${addGs4.response.ifBlank { "<empty>" }}")
            }
            printUpload("partial", "${contextLabel(context)} ${Data.i18NBundle.getinput("err.ynList.retry")}")
        } else {
            if (shouldAnnounceState(context.lastUploadState, "failed", context.lastUploadStateAtMillis, 60_000L)) {
                val onlyRateLimitOrCooldown = listOf(gs1FailureKind, gs4FailureKind).all {
                    it == MasterServerFailureKind.RATE_LIMITED || it == MasterServerFailureKind.COOLDOWN_ACTIVE || it == MasterServerFailureKind.EMPTY_RESPONSE
                }
                if (onlyRateLimitOrCooldown) {
                    Log.warn("[UPLIST] Upload was not accepted yet. Master servers appear to be rate-limiting or temporarily unavailable.")
                } else {
                    Log.error("Failed to upload server list to both gs1 and gs4")
                }
                Log.warn("[UPLIST] gs1 kind=$gs1FailureKind, response=${addGs1.response.ifBlank { "<empty>" }}")
                Log.warn("[UPLIST] gs4 kind=$gs4FailureKind, response=${addGs4.response.ifBlank { "<empty>" }}")
                if (context.portOpen) {
                    if (gs1FailureKind == MasterServerFailureKind.RATE_LIMITED || gs4FailureKind == MasterServerFailureKind.RATE_LIMITED) {
                        Log.warn("[UPLIST] Port is reachable. At least one master server reported rate limiting, so the server will retry after cooldown.")
                    } else {
                        Log.warn("[UPLIST] Port is reachable, but add was not accepted by any list server. The server will stay in retry mode.")
                    }
                }
            }
            printUpload("failed", "${contextLabel(context)} ${Data.i18NBundle.getinput("err.noList")}")
        }

        if (context.portOpen) {
            printPort("open", "${contextLabel(context)} ${Data.i18NBundle.getinput("err.yesOpen")}")
        } else {
            printPort("closed", "${contextLabel(context)} ${Data.i18NBundle.getinput("err.noOpen")}")
        }
        if (context.uploaded) {
            context.lastAppliedFieldSignature = currentContextFieldSignature(context)
        }
    }
    private fun updateContext(context: UplistEntryContext) {
        if (!ensureLocalGamePortReady(context, Log::clog)) {
            return
        }
        if (!context.isFullyUploaded()) {
            if (hasRetryableUploadOpportunity(context)) {
                uploadContext(context, Log::clog)
            } else {
                keepAliveUploadedServers(context)
            }
            return
        }
        keepAliveUploadedServers(context)
    }

    private fun keepAliveUploadedServers(context: UplistEntryContext) {
        val gs1Result = AtomicReference<UploadAttemptResult?>()
        val gs4Result = AtomicReference<UploadAttemptResult?>()
        val gs1Thread = if (context.gs1Uploaded) {
            thread(start = true, isDaemon = true, name = "UpList-gs1-update") {
                gs1Result.set(tryUpdateWithHealth("gs1", GS1_MASTER_SERVER_URL, buildUpdateData(context, "gs1")))
            }
        } else {
            null
        }
        val gs4Thread = if (context.gs4Uploaded) {
            thread(start = true, isDaemon = true, name = "UpList-gs4-update") {
                gs4Result.set(tryUpdateWithHealth("gs4", GS4_MASTER_SERVER_URL, buildUpdateData(context, "gs4")))
            }
        } else {
            null
        }
        gs1Thread?.join()
        gs4Thread?.join()
        gs1Result.get()?.let { handleKeepAliveResult(context, "gs1", it) }
        gs4Result.get()?.let { handleKeepAliveResult(context, "gs4", it) }
        if (context.hasAnyUploadedServer()) {
            context.lastAppliedFieldSignature = currentContextFieldSignature(context)
        }
    }

    private fun noteAcceptedMasterServerState(
        context: UplistEntryContext,
        serverKey: String,
        result: UploadAttemptResult
    ) {
        if (!result.success) {
            return
        }
        val now = System.currentTimeMillis()
        if (serverKey == "gs1") {
            context.gs1LastKeepAliveAcceptedAtMillis = now
        } else {
            context.gs4LastKeepAliveAcceptedAtMillis = now
        }
    }

    private fun handleKeepAliveResult(
        context: UplistEntryContext,
        serverKey: String,
        result: UploadAttemptResult
    ) {
        if (result.success) {
            noteAcceptedMasterServerState(context, serverKey, result)
            return
        }
        if (!UpListResponseSupport.isExplicitNotFound(result.response)) {
            return
        }

        markMasterServerEntryForRecovery(context, serverKey)
        val url = if (serverKey == "gs1") GS1_MASTER_SERVER_URL else GS4_MASTER_SERVER_URL
        val addResult = tryAddWithHealth(
            serverKey,
            url,
            buildAddData(context, serverKey),
            context.effectiveServerId(serverKey)
        )
        if (addResult.success) {
            markMasterServerEntryAccepted(context, serverKey)
            Log.clog("[UPLIST] Re-added $serverKey entry after explicit GAME NOT FOUND for ${contextLabel(context)}")
        }
    }

    private fun markMasterServerEntryForRecovery(
        context: UplistEntryContext,
        serverKey: String,
        endpointMismatch: Boolean = false
    ) {
        if (serverKey == "gs1") {
            context.gs1Uploaded = false
            context.gs1PortOpen = false
            context.gs1Visible = false
            context.gs1EndpointMismatch = endpointMismatch
        } else {
            context.gs4Uploaded = false
            context.gs4PortOpen = false
            context.gs4Visible = false
            context.gs4EndpointMismatch = endpointMismatch
        }
        context.uploaded = context.gs1Uploaded || context.gs4Uploaded
    }

    private fun markMasterServerEntryAccepted(context: UplistEntryContext, serverKey: String) {
        val now = System.currentTimeMillis()
        if (serverKey == "gs1") {
            context.gs1Uploaded = true
            context.gs1LastUploadAcceptedAtMillis = now
            context.gs1EndpointMismatch = false
            context.gs1Visible = null
            context.gs1ConsecutiveVisibilityMissing = 0
        } else {
            context.gs4Uploaded = true
            context.gs4LastUploadAcceptedAtMillis = now
            context.gs4EndpointMismatch = false
            context.gs4Visible = null
            context.gs4ConsecutiveVisibilityMissing = 0
        }
        context.uploaded = context.gs1Uploaded || context.gs4Uploaded
    }

    private fun removeContext(context: UplistEntryContext) {
        // A context that never reached a master server has no remote entry to remove.
        // Skipping those calls also avoids turning a failed multi-entry bootstrap into
        // another burst of removal requests.
        if (context.gs1Uploaded || context.gs1PortOpen) {
            postMasterServerBestEffort("remove gs1 ${contextLabel(context)}", GS1_MASTER_SERVER_URL, buildRemoveData(context, "gs1"))
        }
        if (context.gs4Uploaded || context.gs4PortOpen) {
            postMasterServerBestEffort("remove gs4 ${contextLabel(context)}", GS4_MASTER_SERVER_URL, buildRemoveData(context, "gs4"))
        }
        context.gs1Uploaded = false
        context.gs4Uploaded = false
        context.uploaded = false
        context.gs1PortOpen = false
        context.gs4PortOpen = false
        context.portOpen = false
        context.localPortReady = false
        context.gs1Visible = false
        context.gs4Visible = false
        context.gs1LastUploadAcceptedAtMillis = 0L
        context.gs4LastUploadAcceptedAtMillis = 0L
        context.gs1LastKeepAliveAcceptedAtMillis = 0L
        context.gs4LastKeepAliveAcceptedAtMillis = 0L
        context.gs1EndpointMismatch = false
        context.gs4EndpointMismatch = false
        context.gs1LastEndpointRepairAtMillis = 0L
        context.gs4LastEndpointRepairAtMillis = 0L
        context.lastVisibilityCheckAtMillis = 0L
        context.lastVisibilityState = ""
        context.lastVisibilityStateAtMillis = 0L
        context.consecutiveVisibilityUnknownCount = 0
        context.firstVisibilityUnknownAtMillis = 0L
        context.gs1ConsecutiveVisibilityMissing = 0
        context.gs4ConsecutiveVisibilityMissing = 0
        context.lastUploadState = ""
        context.lastUploadStateAtMillis = 0L
        context.lastPortState = ""
        context.lastPortStateAtMillis = 0L
        context.lastAppliedFieldSignature = ""
    }

    private fun buildAddData(context: UplistEntryContext, serverKey: String): String {
        return buildPayload(rewriteServerId(context.addData, context, serverKey), context)
    }

    private fun buildUpdateData(context: UplistEntryContext, serverKey: String): String {
        return buildPayload(rewriteServerId(context.updateData, context, serverKey), context)
    }

    private fun buildOpenData(context: UplistEntryContext, serverKey: String): String {
        return rewriteServerId(context.openData, context, serverKey)
            .replace("{RW-HPS.S.PORT}", context.listenPort.toString())
    }

    private fun buildRemoveData(context: UplistEntryContext, serverKey: String): String {
        return rewriteServerId(context.removeData, context, serverKey)
    }

    private fun rewriteServerId(template: String, context: UplistEntryContext, serverKey: String): String {
        val effectiveServerId = context.effectiveServerId(serverKey)
        if (effectiveServerId == context.serverID) {
            return template
        }
        return template.replace(context.serverID, effectiveServerId)
    }

    private fun buildPayload(template: String, context: UplistEntryContext): String {
        val displayName = contextListDisplayNameFixed(context)
        val mapName = contextMapNameFixed(context)
        val runtimeGameStatus = contextGameStatus(context.roomId)
        val runtimePlayerCount = contextPlayerSize(context.roomId)
        val runtimeMaxPlayerCount = contextMaxPlayerSize(context)
        val effectiveGameStatus = resolveExperimentalGameStatus(runtimeGameStatus)
        val effectiveMaxPlayerCount = resolveExperimentalMaxPlayerCount(runtimeMaxPlayerCount)
        val effectivePlayerCount = resolveExperimentalPlayerCount(runtimePlayerCount, effectiveMaxPlayerCount)
        var data = template.replace("{RW-HPS.RW.VERSION}", versionGame)
        data = data.replace("{RW-HPS.RW.VERSION.INT}", versionGameInt.toString())
        data = data.replace("{RW-HPS.RW.IS.VERSION}", versionBeta.toString())
        data = data.replace("{RW-HPS.RW.IS.PASSWD}", Data.configServer.passwd.isNotBlank().toString())
        data = data.replace("{RW-HPS.LIST.GAME.NAME}", encodeFormValue(resolveExperimentalListGameName()))
        data = data.replace("{RW-HPS.S.NAME}", encodeFormValue(resolveExperimentalListCreatedBy(displayName)))
        data = data.replace("{RW-HPS.S.PRIVATE.IP}", privateIp)
        data = data.replace("{RW-HPS.S.PORT}", context.listenPort.toString())
        data = data.replace("{RW-HPS.RW.MAP.NAME}", encodeFormValue(resolveExperimentalListMapName(mapName)))
        data = data.replace("{RW-HPS.S.STATUS}", effectiveGameStatus)
        data = data.replace("{RW-HPS.PLAYER.SIZE}", effectivePlayerCount.toString())
        data = data.replace("{RW-HPS.PLAYER.SIZE.MAX}", effectiveMaxPlayerCount.toString())
        return data.replace("\n", "").replace("\r", "")
    }

    private fun currentContextFieldSignature(context: UplistEntryContext): String {
        val displayName = contextListDisplayNameFixed(context)
        val mapName = contextMapNameFixed(context)
        val runtimeGameStatus = contextGameStatus(context.roomId)
        val runtimePlayerCount = contextPlayerSize(context.roomId)
        val runtimeMaxPlayerCount = contextMaxPlayerSize(context)
        val effectiveGameStatus = resolveExperimentalGameStatus(runtimeGameStatus)
        val effectiveMaxPlayerCount = resolveExperimentalMaxPlayerCount(runtimeMaxPlayerCount)
        val effectivePlayerCount = resolveExperimentalPlayerCount(runtimePlayerCount, effectiveMaxPlayerCount)
        return listOf(
            resolveExperimentalListGameName(),
            resolveExperimentalListCreatedBy(displayName),
            resolveExperimentalListMapName(mapName),
            effectiveGameStatus,
            effectivePlayerCount.toString(),
            effectiveMaxPlayerCount.toString(),
            context.listenPort.toString(),
            Data.configServer.passwd.isNotBlank().toString(),
            context.listEntry?.id ?: ""
        ).joinToString("\u0001")
    }

    private fun encodeFormValue(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun listExperimentEnabled(): Boolean = Data.config.listExperimentEnabled || Data.config.listPromotionModeEnabled

    private fun rawListExperimentEnabled(): Boolean = Data.config.listExperimentEnabled

    private fun listPromotionModeEnabled(): Boolean = Data.config.listPromotionModeEnabled

    private fun listPromotionSortBoostEnabled(): Boolean =
        Data.config.listPromotionModeEnabled && Data.config.listPromotionSortBoostEnabled

    private fun resolveExperimentalListGameName(): String {
        if (listPromotionSortBoostEnabled()) {
            return Data.config.listExperimentGameName.trim().ifEmpty { "Unnamed" }
        }
        if (!listExperimentEnabled()) {
            return "RW-HPS"
        }
        return Data.config.listExperimentGameName.trim().ifEmpty { "RW-HPS" }
    }

    private fun resolveExperimentalListCreatedBy(defaultValue: String): String {
        if (listPromotionSortBoostEnabled()) {
            return Data.config.listExperimentCreatedBy.trim().ifEmpty {
                val prefix = Data.config.listPromotionSortBoostPrefix.ifBlank { "\uD808\uDC31" }
                prefix + defaultValue.trimStart('\u0001').trim()
            }
        }
        if (!listExperimentEnabled()) {
            return defaultValue
        }
        return Data.config.listExperimentCreatedBy.trim().ifEmpty { defaultValue }
    }

    private fun resolveExperimentalListMapName(defaultValue: String): String {
        /*
         * 空地图模式（listPromotionBlankMapEnabled）。
         *
         * 原实现要求 `listPromotionSortBoostEnabled()` 同时为真，即：
         *   listPromotionModeEnabled && listPromotionSortBoostEnabled && listPromotionBlankMapEnabled
         * 三个开关全开才生效。这使得"只想让地图栏留空"也必须连带打开推广模式与排序前缀，
         * 耦合过强，且排序前缀会额外改写 created_by。
         *
         * 现改为：`listPromotionBlankMapEnabled` 单独即可生效。
         * 依据（官方主列表实测）：第三方条目中已有 57 行 game_map 为空串，
         * 说明主服务器**接受**空地图值，不需要其他字段配合。
         *
         * 注意：这里必须**先**返回空串，早于 `sanitizeDisplayMapName` 的 "RELAY" 兜底生效位置，
         * 因为 defaultValue 已经是填过 RELAY 的结果（见 getMapName / sanitizeDisplayMapName
         * 的 `ifBlank { "RELAY" }`）。空串在此直接覆盖它，从而实现"大厅未选图 = 地图栏空白"。
         */
        if (Data.config.listPromotionBlankMapEnabled) {
            return ""
        }
        if (!listExperimentEnabled()) {
            return defaultValue
        }
        val overrideMapName = Data.config.listExperimentMapName.trim()
        if (overrideMapName.isEmpty()) {
            return defaultValue
        }
        return decorateListMapName(overrideMapName)
    }

    private fun resolveExperimentalGameStatus(defaultValue: String): String {
        if (listPromotionModeEnabled()) {
            return resolvePromotionGameStatus()
        }
        if (!rawListExperimentEnabled()) {
            return defaultValue
        }
        val overrideStatus = Data.config.listExperimentGameStatus.trim().lowercase()
        return when (overrideStatus) {
            "", "default" -> defaultValue
            "battleroom", "ingame", "chat", "locked" -> overrideStatus
            else -> defaultValue
        }
    }

    private fun resolvePromotionGameStatus(): String {
        return when (Data.config.listPromotionGameStatus.trim().lowercase()) {
            "", "default", "chat", "chatroom", "chat_room", "chat-room" -> "chat"
            "battleroom", "battle_room", "battle-room" -> "battleroom"
            else -> "chat"
        }
    }

    private fun resolveExperimentalPlayerCount(defaultValue: Int, effectiveMaxPlayerCount: Int): Int {
        if (listPromotionModeEnabled()) {
            return resolvePromotionPlayerCount(effectiveMaxPlayerCount)
        }
        if (!rawListExperimentEnabled()) {
            return defaultValue
        }
        return Data.config.listExperimentPlayerCount.takeIf { it >= 0 } ?: defaultValue
    }

    private fun resolveExperimentalMaxPlayerCount(defaultValue: Int): Int {
        if (listPromotionModeEnabled()) {
            return resolvePromotionMaxPlayerCount(defaultValue)
        }
        if (!rawListExperimentEnabled()) {
            return defaultValue
        }
        return Data.config.listExperimentMaxPlayerCount.takeIf { it >= 0 } ?: defaultValue
    }

    private fun resolvePromotionPlayerCount(effectiveMaxPlayerCount: Int): Int {
        val configured = Data.config.listPromotionPlayerCount.takeIf { it >= 0 }
            ?: Data.config.listExperimentPlayerCount.takeIf { it >= 0 }
            ?: 1
        val upperBound = (effectiveMaxPlayerCount - 1).coerceAtLeast(0)
        return configured.coerceAtLeast(0).coerceAtMost(upperBound)
    }

    private fun resolvePromotionMaxPlayerCount(defaultValue: Int): Int {
        val configuredPlayer = Data.config.listPromotionPlayerCount.takeIf { it >= 0 }
            ?: Data.config.listExperimentPlayerCount.takeIf { it >= 0 }
            ?: 1
        val configuredMax = Data.config.listPromotionMaxPlayerCount.takeIf { it >= 0 }
            ?: Data.config.listExperimentMaxPlayerCount.takeIf { it >= 0 }
            ?: defaultValue.coerceAtLeast(100)
        return configuredMax
            .coerceAtLeast(2)
            .coerceAtLeast(configuredPlayer.coerceAtLeast(0) + 1)
    }

    private fun tryUpload(serverKey: String, url: String, payload: String, serverId: String): UploadAttemptResult {
        awaitMasterServerRequest(serverKey)
        val result = Data.core.uplistHttp.doPostResultQuiet(url, payload)
        val response = result.diagnosticResponse()
        Log.debug("UPLIST Response: $response")
        if (!result.successful) {
            Log.error("[UPLIST] Add request failed for id=$serverId; request payload omitted")
        }
        return UploadAttemptResult(
            result.successful && UpListResponseSupport.isAddAccepted(result.body, serverId),
            response
        )
    }

    private fun tryAddWithHealth(
        serverKey: String,
        url: String,
        payload: String,
        serverId: String
    ): UploadAttemptResult {
        val currentHealth = healthState(serverKey)
        if (currentHealth.cooldownUntilMillis > System.currentTimeMillis()) {
            return UploadAttemptResult(false, "COOLDOWN_ACTIVE:${currentHealth.lastMessage.ifBlank { currentHealth.lastFailureKind }}")
        }

        val result = tryUpload(serverKey, url, payload, serverId)
        updateHealthState(serverKey, result.success, result.response)
        return result
    }

    private fun tryUpdateWithHealth(
        serverKey: String,
        url: String,
        payload: String
    ): UploadAttemptResult {
        val currentHealth = healthState(serverKey)
        if (currentHealth.cooldownUntilMillis > System.currentTimeMillis()) {
            return UploadAttemptResult(false, "COOLDOWN_ACTIVE:${currentHealth.lastMessage.ifBlank { currentHealth.lastFailureKind }}")
        }
        val result = tryUpdate(serverKey, url, payload)
        val response = result.diagnosticResponse()
        val success = result.successful && isUpdateAcceptedResponse(response)
        updateHealthState(serverKey, success, response)
        return UploadAttemptResult(success, response)
    }

    private fun tryRecoverExistingOrAddWithHealth(
        serverKey: String,
        url: String,
        context: UplistEntryContext
    ): UploadAttemptResult {
        val currentHealth = healthState(serverKey)
        if (currentHealth.cooldownUntilMillis > System.currentTimeMillis()) {
            return UploadAttemptResult(false, "COOLDOWN_ACTIVE:${currentHealth.lastMessage.ifBlank { currentHealth.lastFailureKind }}")
        }

        val initialUpdateResult = tryUpdate(serverKey, url, buildUpdateData(context, serverKey))
        val initialUpdateResponse = initialUpdateResult.diagnosticResponse()
        if (initialUpdateResult.successful && isUpdateAcceptedResponse(initialUpdateResponse)) {
            updateHealthState(serverKey, true, initialUpdateResponse)
            return UploadAttemptResult(true, initialUpdateResponse, recoveredExisting = true)
        }

        // HPS returns GAME NOT FOUND with HTTP 200. At this point the update
        // was not protocol-accepted, so classify the body rather than HTTP status.
        val initialFailureKind = classifyMasterServerFailure(false, initialUpdateResponse)
        if (initialFailureKind != MasterServerFailureKind.NOT_FOUND) {
            updateHealthState(serverKey, false, initialUpdateResponse)
            return UploadAttemptResult(false, initialUpdateResponse)
        }

        // Only an explicit missing-ID response justifies an add. Network errors,
        // timeouts, malformed responses, and rate limits must not create duplicates.
        return tryAddWithHealth(
            serverKey,
            url,
            buildAddData(context, serverKey),
            context.effectiveServerId(serverKey)
        )
    }

    private fun queryPortOpen(serverKey: String, url: String, payload: String): Boolean {
        awaitMasterServerRequest(serverKey)
        val result = Data.core.uplistHttp.doPostResultQuiet(url, payload)
        if (!result.successful) {
            Log.warn("[UPLIST] self_info failed for $url: ${result.diagnosticResponse()}")
        }
        return result.successful && UpListResponseSupport.isPortOpenResponse(result.body)
    }

    private fun tryUpdate(serverKey: String, url: String, payload: String): net.rwhps.server.net.manage.HttpCallResult {
        awaitMasterServerRequest(serverKey)
        return Data.core.uplistHttp.doPostResultQuiet(url, payload)
    }

    private fun postMasterServerBestEffort(
        action: String,
        url: String,
        payload: String
    ): net.rwhps.server.net.manage.HttpCallResult {
        val serverKey = if (url == GS1_MASTER_SERVER_URL) "gs1" else "gs4"
        awaitMasterServerRequest(serverKey)
        val result = Data.core.uplistHttp.doPostResultQuiet(url, payload)
        if (!result.successful) {
            Log.warn("[UPLIST] $action failed on $url: ${result.diagnosticResponse()}")
        }
        return result
    }

    private fun isUpdateAcceptedResponse(response: String): Boolean {
        return UpListResponseSupport.isUpdateAccepted(response)
    }

    private fun verifyUploadedContextsVisibility(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val dueContexts = uplistContexts.values
            .filter { it.hasAnyUploadedServer() }
            .filter {
                force ||
                    it.lastVisibilityCheckAtMillis == 0L ||
                    now - it.lastVisibilityCheckAtMillis >= UPLIST_VISIBILITY_CHECK_INTERVAL_MILLIS
            }
        if (dueContexts.isEmpty()) {
            return
        }

        val gs1Snapshot: MasterServerListSnapshot? = if (dueContexts.any { it.gs1Uploaded }) {
            fetchMasterServerSnapshot(GS1_MASTER_SERVER_URL)
        } else {
            MasterServerListSnapshot.empty()
        }
        val gs4Snapshot: MasterServerListSnapshot? = if (dueContexts.any { it.gs4Uploaded }) {
            fetchMasterServerSnapshot(GS4_MASTER_SERVER_URL)
        } else {
            MasterServerListSnapshot.empty()
        }

        dueContexts.forEach { context ->
            context.lastVisibilityCheckAtMillis = now
            val missingServers = mutableListOf<String>()
            val gs1EndpointMismatch = if (context.gs1Uploaded) {
                hasEndpointMismatch(gs1Snapshot, context, "gs1")
            } else {
                false
            }
            val gs4EndpointMismatch = if (context.gs4Uploaded) {
                hasEndpointMismatch(gs4Snapshot, context, "gs4")
            } else {
                false
            }
            if (gs1EndpointMismatch) {
                repairEndpointMismatch(context, "gs1")
            }
            if (gs4EndpointMismatch) {
                repairEndpointMismatch(context, "gs4")
            }
            val gs1Visible = if (context.gs1Uploaded) {
                if (gs1EndpointMismatch) null else isContextVisibleInSnapshot("gs1", gs1Snapshot, context)
            } else {
                false
            }
            val gs4Visible = if (context.gs4Uploaded) {
                if (gs4EndpointMismatch) null else isContextVisibleInSnapshot("gs4", gs4Snapshot, context)
            } else {
                false
            }

            if (context.gs1Uploaded && gs1Visible == false) {
                context.gs1Visible = false
                context.gs1ConsecutiveVisibilityMissing += 1
                if (context.gs1ConsecutiveVisibilityMissing >= UPLIST_VISIBILITY_MISSING_CONFIRMATIONS) {
                    context.gs1Uploaded = false
                    missingServers += "gs1"
                }
            } else if (context.gs1Uploaded && gs1Visible == null) {
                context.gs1Visible = null
                context.gs1ConsecutiveVisibilityMissing = 0
            } else if (gs1Visible != null) {
                context.gs1Visible = gs1Visible
                context.gs1ConsecutiveVisibilityMissing = 0
            }

            if (context.gs4Uploaded && gs4Visible == false) {
                context.gs4Visible = false
                context.gs4ConsecutiveVisibilityMissing += 1
                if (context.gs4ConsecutiveVisibilityMissing >= UPLIST_VISIBILITY_MISSING_CONFIRMATIONS) {
                    context.gs4Uploaded = false
                    missingServers += "gs4"
                }
            } else if (context.gs4Uploaded && gs4Visible == null) {
                context.gs4Visible = null
                context.gs4ConsecutiveVisibilityMissing = 0
            } else if (gs4Visible != null) {
                context.gs4Visible = gs4Visible
                context.gs4ConsecutiveVisibilityMissing = 0
            }

            context.uploaded = context.gs1Uploaded || context.gs4Uploaded
            val gs1MissingPending = context.gs1Uploaded && gs1Visible == false
            val gs4MissingPending = context.gs4Uploaded && gs4Visible == false
            val stateKey = when {
                missingServers.isNotEmpty() -> "visibility_missing_confirmed:${missingServers.joinToString("+")}"
                gs1MissingPending || gs4MissingPending -> "visibility_missing_pending"
                (context.gs1Uploaded && gs1Visible == null) || (context.gs4Uploaded && gs4Visible == null) -> "visibility_unknown"
                context.gs1Visible == true && context.gs4Visible == true -> "visibility_full"
                context.gs1Visible == true || context.gs4Visible == true -> "visibility_partial"
                else -> "visibility_missing:all"
            }
            val unknownDurationMillis = updateVisibilityUnknownCounters(context, stateKey, now)
            val visibleSummary = "gs1=${formatVisibility(context.gs1Visible)}, gs4=${formatVisibility(context.gs4Visible)}"
            val label = contextLabel(context)
            when {
                stateKey == "visibility_full" -> announceVisibilityState(
                    context,
                    stateKey,
                    "[UPLIST] Public list visibility confirmed for $label ($visibleSummary)",
                    warning = false
                )
                stateKey == "visibility_partial" -> announceVisibilityState(
                    context,
                    stateKey,
                    "[UPLIST] Public list visibility is partial for $label ($visibleSummary). Missing nodes will keep retrying.",
                    warning = true
                )
                stateKey == "visibility_unknown" -> announceVisibilityState(
                    context,
                    stateKey,
                    visibilityUnknownMessage(context, label, visibleSummary, unknownDurationMillis),
                    warning = shouldWarnVisibilityUnknown(context)
                )
                stateKey == "visibility_missing_pending" -> announceVisibilityState(
                    context,
                    stateKey,
                    "[UPLIST] Public list visibility is temporarily missing for $label ($visibleSummary). Confirmation ${maxOf(context.gs1ConsecutiveVisibilityMissing, context.gs4ConsecutiveVisibilityMissing)}/$UPLIST_VISIBILITY_MISSING_CONFIRMATIONS; upload state is preserved.",
                    warning = true
                )
                else -> announceVisibilityState(
                    context,
                    stateKey,
                    "[UPLIST] Public list entry is confirmed missing for $label ($visibleSummary). Re-uploading only the confirmed nodes.",
                    warning = true
                )
            }

            if (missingServers.isNotEmpty() && hasRetryableUploadOpportunity(context)) {
                uploadContext(context, Log::clog)
            }
        }
    }

    private fun updateVisibilityUnknownCounters(context: UplistEntryContext, stateKey: String, now: Long): Long {
        if (stateKey != "visibility_unknown") {
            context.consecutiveVisibilityUnknownCount = 0
            context.firstVisibilityUnknownAtMillis = 0L
            return 0L
        }
        if (context.consecutiveVisibilityUnknownCount <= 0 || context.firstVisibilityUnknownAtMillis <= 0L) {
            context.firstVisibilityUnknownAtMillis = now
        }
        context.consecutiveVisibilityUnknownCount += 1
        return now - context.firstVisibilityUnknownAtMillis
    }

    private fun visibilityUnknownMessage(
        context: UplistEntryContext,
        label: String,
        visibleSummary: String,
        unknownDurationMillis: Long
    ): String {
        val unknownSeconds = (unknownDurationMillis / 1000L).coerceAtLeast(0L)
        if (hasRecentAcceptedMasterServerState(context)) {
            return "[UPLIST] Master server accepted keep-alive for $label, but public list snapshot is still propagating or temporarily unverifiable ($visibleSummary). Upload state is preserved."
        }
        return if (context.consecutiveVisibilityUnknownCount >= UPLIST_VISIBILITY_UNKNOWN_WARN_COUNT) {
            "[UPLIST] Public list visibility probe is still unavailable for $label after ${context.consecutiveVisibilityUnknownCount} checks (${unknownSeconds}s, $visibleSummary). Upload remains active; run 'uplist diagnose' if players cannot find the room."
        } else {
            "[UPLIST] Public list visibility is still propagating or temporarily unverifiable for $label ($visibleSummary). Upload remains active and will be checked again automatically."
        }
    }

    private fun shouldWarnVisibilityUnknown(context: UplistEntryContext): Boolean {
        return context.consecutiveVisibilityUnknownCount >= UPLIST_VISIBILITY_UNKNOWN_WARN_COUNT &&
            !hasRecentAcceptedMasterServerState(context)
    }

    private fun hasRecentAcceptedMasterServerState(context: UplistEntryContext): Boolean {
        return isWithinVisibilityGrace(context, "gs1") || isWithinVisibilityGrace(context, "gs4")
    }

    private fun isContextVisibleInSnapshot(
        serverKey: String,
        snapshot: MasterServerListSnapshot?,
        context: UplistEntryContext
    ): Boolean? {
        if (snapshot == null) {
            return null
        }
        val effectiveServerId = context.effectiveServerId(serverKey)
        if (effectiveServerId in snapshot.ids) {
            return true
        }
        if (!snapshot.isComplete) {
            return null
        }
        if (isWithinVisibilityGrace(context, serverKey)) {
            return null
        }
        return false
    }

    private fun hasEndpointMismatch(
        snapshot: MasterServerListSnapshot?,
        context: UplistEntryContext,
        serverKey: String
    ): Boolean {
        if (snapshot == null || !snapshot.isComplete) {
            return false
        }
        val entry = snapshot?.entries?.firstOrNull { it.id == context.effectiveServerId(serverKey) }
            ?: return false
        return !UpListSnapshotSupport.endpointMatches(
            publishedPrivateIp = entry.privateIp,
            publishedPort = entry.port,
            privateIp = privateIp,
            listenPort = context.listenPort
        )
    }

    private fun repairEndpointMismatch(context: UplistEntryContext, serverKey: String) {
        val now = System.currentTimeMillis()
        val lastRepairAt = if (serverKey == "gs1") {
            context.gs1LastEndpointRepairAtMillis
        } else {
            context.gs4LastEndpointRepairAtMillis
        }
        if (now - lastRepairAt < UPLIST_ENDPOINT_REPAIR_INTERVAL_MILLIS) {
            return
        }
        if (serverKey == "gs1") {
            context.gs1LastEndpointRepairAtMillis = now
            context.gs1EndpointMismatch = true
        } else {
            context.gs4LastEndpointRepairAtMillis = now
            context.gs4EndpointMismatch = true
        }

        val url = if (serverKey == "gs1") GS1_MASTER_SERVER_URL else GS4_MASTER_SERVER_URL
        Log.warn(
            "[UPLIST] $serverKey entry endpoint does not match local port ${context.listenPort} for " +
                    "${contextLabel(context)}; rebuilding the entry."
        )
        val removeResult = postMasterServerBestEffort(
            "remove stale $serverKey endpoint ${contextLabel(context)}",
            url,
            buildRemoveData(context, serverKey)
        )
        val removeResponse = removeResult.diagnosticResponse()
        if (!removeResult.successful || UpListResponseSupport.hasExplicitFailureMarker(removeResponse)) {
            Log.warn(
                "[UPLIST] Cannot rebuild $serverKey endpoint for ${contextLabel(context)} because stale entry removal was not accepted: " +
                        removeResponse
            )
            markMasterServerEntryForRecovery(context, serverKey, endpointMismatch = true)
            return
        }
        val addResult = tryAddWithHealth(
            serverKey,
            url,
            buildAddData(context, serverKey),
            context.effectiveServerId(serverKey)
        )
        if (addResult.success) {
            markMasterServerEntryAccepted(context, serverKey)
            Log.clog("[UPLIST] Rebuilt $serverKey entry with local endpoint for ${contextLabel(context)}")
        } else {
            markMasterServerEntryForRecovery(context, serverKey, endpointMismatch = true)
        }
    }

    private fun isWithinVisibilityGrace(context: UplistEntryContext, serverKey: String): Boolean {
        val acceptedAt = if (serverKey == "gs1") {
            context.gs1LastUploadAcceptedAtMillis
        } else {
            context.gs4LastUploadAcceptedAtMillis
        }
        return acceptedAt > 0L && System.currentTimeMillis() - acceptedAt < UPLIST_VISIBILITY_GRACE_MILLIS
    }

    private fun renderUpListStatus(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== UPLIST 状态 =========="
        lines += "期望持续开放: $persistentUplistDesired"
        lines += "当前上传开关: $upServerList"
        lines += "上传任务进行中: ${uplistAttemptInProgress.get()}"
        lines += "master server 最小请求间隔: ${UPLIST_MASTER_REQUEST_MIN_INTERVAL_MILLIS}ms"
        lines += "待处理下线请求: ${pendingDeactivation.get() != null}"
        lines += "待补传上下文: ${uplistContexts.values.count { !it.isFullyUploaded() }}"
        lines += formatHealthState(gs1Health)
        lines += formatHealthState(gs4Health)
        uplistContexts.forEach { (_, context) ->
            val label = contextLabel(context)
            lines += "$label uploaded=${context.uploaded}, localPortReady=${context.localPortReady}, gs1Uploaded=${context.gs1Uploaded}, gs4Uploaded=${context.gs4Uploaded}, endpointMismatch=gs1:${context.gs1EndpointMismatch} gs4:${context.gs4EndpointMismatch}, visible=gs1:${formatVisibility(context.gs1Visible)} gs4:${formatVisibility(context.gs4Visible)}, missingConfirmations=gs1:${context.gs1ConsecutiveVisibilityMissing} gs4:${context.gs4ConsecutiveVisibilityMissing}, lastVisibilityCheck=${formatTime(context.lastVisibilityCheckAtMillis)}, portOpen=${context.portOpen}, ids=gs1:${context.effectiveServerId("gs1")} gs4:${context.effectiveServerId("gs4")}"
        }
        return lines
    }

    private fun renderUpListDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== UPLIST Diagnose =========="
        lines += probeMasterServer("gs1", GS1_MASTER_SERVER_URL)
        lines += probeMasterServer("gs4", GS4_MASTER_SERVER_URL)
        lines += "listExperimentEnabled=${Data.config.listExperimentEnabled}"
        lines += "publicListEntriesConfigured=${Data.config.publicListEntries.orEmpty().size}"
        lines += "publicListEntriesActive=${normalizedPublicListEntries().joinToString(",") { it.id }.ifBlank { "-" }}"
        lines += "publicListEntriesLimit=$maxPublicListEntryCount"
        lines += "activeContextCount=${uplistContexts.size}"
        val gs1Snapshot = fetchMasterServerSnapshot(GS1_MASTER_SERVER_URL)
        val gs4Snapshot = fetchMasterServerSnapshot(GS4_MASTER_SERVER_URL)
        lines += formatMasterServerSnapshot("gs1", gs1Snapshot)
        lines += formatMasterServerSnapshot("gs4", gs4Snapshot)
        val contexts = uplistContexts.values
            .sortedBy(::contextLabel)
            .ifEmpty { listOf(createUpListContext()) }
        appendPublicListAddressDiagnostics(lines, contexts, gs1Snapshot, gs4Snapshot)
        contexts.forEach { context ->
            appendUpListContextDiagnostics(lines, context, gs1Snapshot, gs4Snapshot)
        }
        return lines
    }

    private fun appendPublicListAddressDiagnostics(
        lines: MutableList<String>,
        contexts: List<UplistEntryContext>,
        gs1Snapshot: MasterServerListSnapshot?,
        gs4Snapshot: MasterServerListSnapshot?
    ) {
        val sharedPortGroups = contexts
            .groupBy { it.listenPort }
            .filterValues { it.size > 1 }
            .toSortedMap()
        if (sharedPortGroups.isEmpty()) {
            return
        }

        lines += "---------- multi-entry address check ----------"
        lines += "address.private_ip=$privateIp"
        sharedPortGroups.forEach { (listenPort, group) ->
            val labels = group.sortedBy(::contextLabel).joinToString(",") { contextLabel(it) }
            lines += "address.port=$listenPort contexts=${group.size} labels=$labels"
            lines += formatAddressDedupeCheck("gs1", gs1Snapshot, listenPort, group)
            lines += formatAddressDedupeCheck("gs4", gs4Snapshot, listenPort, group)
        }
    }

    private fun formatAddressDedupeCheck(
        serverKey: String,
        snapshot: MasterServerListSnapshot?,
        listenPort: Int,
        contexts: List<UplistEntryContext>
    ): String {
        if (snapshot == null) {
            return "address.$serverKey port=$listenPort snapshot=unavailable diagnosis=snapshot_unavailable"
        }
        if (!snapshot.isComplete) {
            return "address.$serverKey port=$listenPort snapshotRows=${snapshot.rawEntryCount} parsedRows=${snapshot.parsedEntryCount} schema=${snapshot.schema} diagnosis=snapshot_incomplete"
        }

        val uploadedCount = contexts.count { context ->
            if (serverKey == "gs1") context.gs1Uploaded else context.gs4Uploaded
        }
        val visibleCount = contexts.count { context ->
            isContextVisibleInSnapshot(serverKey, snapshot, context) == true
        }
        val snapshotAddressCount = snapshot.entries.count { entry ->
            entry.privateIp == privateIp && entry.port == listenPort
        }
        val diagnosis = when {
            uploadedCount <= 1 -> "not_enough_uploaded_contexts"
            snapshotAddressCount >= contexts.size && visibleCount >= contexts.size -> "multi_entries_visible"
            snapshotAddressCount in 1 until contexts.size -> "partial_address_visibility"
            visibleCount in 1 until uploadedCount -> "partial_visibility"
            snapshotAddressCount == 0 -> "not_visible_or_snapshot_stale"
            else -> "needs_manual_review"
        }
        return "address.$serverKey port=$listenPort snapshotEntries=$snapshotAddressCount uploadedContexts=$uploadedCount visibleContexts=$visibleCount diagnosis=$diagnosis"
    }

    private fun formatMasterServerSnapshot(serverKey: String, snapshot: MasterServerListSnapshot?): String {
        if (snapshot == null) {
            return "snapshot.$serverKey=unavailable"
        }
        return "snapshot.$serverKey rows=${snapshot.rawEntryCount} parsed=${snapshot.parsedEntryCount} schema=${snapshot.schema} complete=${snapshot.isComplete}"
    }

    private fun appendUpListContextDiagnostics(
        lines: MutableList<String>,
        context: UplistEntryContext,
        gs1Snapshot: MasterServerListSnapshot?,
        gs4Snapshot: MasterServerListSnapshot?
    ) {
        lines += "---------- ${contextLabel(context)} ----------"
        context.listEntry?.let { entry ->
            lines += "entry.id=${entry.id}, serverName=${entry.serverName}, mapName=${entry.mapName.ifBlank { "-" }}, statusSuffix=${entry.statusSuffixEnabled}, pin=${entry.listNamePinnedCharacterCount}/${entry.listPinnedCharacterCount}, maxPlayer=${entry.maxPlayerCount ?: "-"}"
        } ?: run {
            lines += "entry.id=legacy-default"
        }
        lines += "listenPort=${context.listenPort}, uploaded=${context.uploaded}, localPortReady=${context.localPortReady}, gs1Uploaded=${context.gs1Uploaded}, gs4Uploaded=${context.gs4Uploaded}, endpointMismatch=gs1:${context.gs1EndpointMismatch} gs4:${context.gs4EndpointMismatch}, portOpen=${context.portOpen}"
        val displayName = contextListDisplayNameFixed(context)
        val mapName = contextMapNameFixed(context)
        val runtimeGameStatus = contextGameStatus(context.roomId)
        val runtimePlayerCount = contextPlayerSize(context.roomId)
        val runtimeMaxPlayerCount = contextMaxPlayerSize(context)
        val effectiveGameStatus = resolveExperimentalGameStatus(runtimeGameStatus)
        val effectiveMaxPlayerCount = resolveExperimentalMaxPlayerCount(runtimeMaxPlayerCount)
        val effectivePlayerCount = resolveExperimentalPlayerCount(runtimePlayerCount, effectiveMaxPlayerCount)
        lines += "listPromotionModeEnabled=${Data.config.listPromotionModeEnabled}"
        lines += "effective.game_name=${resolveExperimentalListGameName()}"
        lines += "effective.created_by=${resolveExperimentalListCreatedBy(displayName)}"
        lines += "effective.game_map=${resolveExperimentalListMapName(mapName)}"
        lines += "effective.game_status=$effectiveGameStatus"
        lines += "effective.player_count=$effectivePlayerCount"
        lines += "effective.max_player_count=$effectiveMaxPlayerCount"
        lines += "effective.signature=${currentContextFieldSignature(context)}"
        lines += "effective.gs1_id=${context.effectiveServerId("gs1")}"
        lines += "effective.gs4_id=${context.effectiveServerId("gs4")}"
        val legacyId = buildLegacyServerId(context)
        lines += "legacy_id=$legacyId"
        lines += "legacy_present.gs1=${gs1Snapshot?.ids?.contains(legacyId) ?: "unknown"}"
        lines += "legacy_present.gs4=${gs4Snapshot?.ids?.contains(legacyId) ?: "unknown"}"
        val gs1Visible = formatVisibility(isContextVisibleInSnapshot("gs1", gs1Snapshot, context))
        val gs4Visible = formatVisibility(isContextVisibleInSnapshot("gs4", gs4Snapshot, context))
        lines += "visibility.gs1=$gs1Visible"
        lines += "visibility.gs4=$gs4Visible"
    }

    private fun formatHealthState(state: MasterServerHealthState): String {
        val cooldownRemainingSeconds = ((state.cooldownUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
        val lastSuccess = if (state.lastSuccessAtMillis > 0L) java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(state.lastSuccessAtMillis)) else "-"
        return "[${state.name}] success=${state.lastSuccess}, consecutiveFailures=${state.consecutiveFailures}, cooldownRemaining=${cooldownRemainingSeconds}s, lastSuccessAt=$lastSuccess, lastFailureKind=${state.lastFailureKind.ifBlank { "-" }}, lastMessage=${state.lastMessage.ifBlank { "-" }}"
    }

    private fun formatVisibility(value: Boolean?): String {
        return when (value) {
            true -> "yes"
            false -> "no"
            null -> "unknown"
        }
    }

    private fun formatTime(timestampMillis: Long): String {
        return if (timestampMillis > 0L) {
            java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(timestampMillis))
        } else {
            "-"
        }
    }

    private fun canAttemptMasterServerBootstrap(): Boolean {
        val now = System.currentTimeMillis()
        val gs1Ready = gs1Health.cooldownUntilMillis <= now
        val gs4Ready = gs4Health.cooldownUntilMillis <= now
        if (!gs1Ready && !gs4Ready) {
            val nextWake = listOf(gs1Health.cooldownUntilMillis, gs4Health.cooldownUntilMillis)
                .filter { it > now }
                .minOrNull()
            if (nextWake != null) {
                val waitSeconds = ((nextWake - now) / 1000L).coerceAtLeast(1L)
                Log.clog("[AutoUpList] All master servers are cooling down, next retry in ${waitSeconds}s")
            }
            return false
        }
        return true
    }

    private fun hasRetryablePendingUploadContext(): Boolean {
        return uplistContexts.values.any { !it.isFullyUploaded() && hasRetryableUploadOpportunity(it) }
    }

    private fun ensureLocalGamePortReady(context: UplistEntryContext, emit: (String) -> Unit): Boolean {
        val ready = isRelay || NetStaticData.netService.find(
            { it.netType == NetService.Companion.NetTypeEnum.HeadlessNet },
            { it.isPortBound(context.listenPort) }
        ) != null
        val wasReady = context.localPortReady
        context.localPortReady = ready
        if (ready) {
            return true
        }

        val hadAdvertisedState = context.uploaded || context.gs1Uploaded || context.gs4Uploaded
        markLocalGamePortUnavailable(context)
        if (wasReady || hadAdvertisedState) {
            Log.warn("[UPLIST] Local game port ${context.listenPort} is no longer bound for ${contextLabel(context)}; upload recovery is deferred.")
        }
        announcePortState(
            context,
            "local-closed",
            "[UPLIST] Local game port ${context.listenPort} is not bound for ${contextLabel(context)}; upload is deferred.",
            emit
        )
        return false
    }

    private fun markLocalGamePortUnavailable(context: UplistEntryContext) {
        context.uploaded = false
        context.gs1Uploaded = false
        context.gs4Uploaded = false
        context.gs1PortOpen = false
        context.gs4PortOpen = false
        context.portOpen = false
        context.gs1Visible = null
        context.gs4Visible = null
        context.gs1LastUploadAcceptedAtMillis = 0L
        context.gs4LastUploadAcceptedAtMillis = 0L
        context.gs1ConsecutiveVisibilityMissing = 0
        context.gs4ConsecutiveVisibilityMissing = 0
    }

    private fun hasRetryableUploadOpportunity(context: UplistEntryContext): Boolean {
        val now = System.currentTimeMillis()
        val retryGs1 = !context.gs1Uploaded && gs1Health.cooldownUntilMillis <= now
        val retryGs4 = !context.gs4Uploaded && gs4Health.cooldownUntilMillis <= now
        return retryGs1 || retryGs4
    }

    private fun announceVisibilityState(
        context: UplistEntryContext,
        stateKey: String,
        message: String,
        warning: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (!shouldAnnounceState(context.lastVisibilityState, stateKey, context.lastVisibilityStateAtMillis, UPLIST_VISIBILITY_LOG_INTERVAL_MILLIS)) {
            return
        }
        context.lastVisibilityState = stateKey
        context.lastVisibilityStateAtMillis = now
        if (warning) {
            Log.warn(message)
        } else {
            Log.clog(message)
        }
    }

    private fun announceUploadState(
        context: UplistEntryContext,
        stateKey: String,
        message: String,
        print: (String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (!shouldAnnounceState(context.lastUploadState, stateKey, context.lastUploadStateAtMillis, 60_000L)) {
            return
        }
        context.lastUploadState = stateKey
        context.lastUploadStateAtMillis = now
        print(message)
    }

    private fun announcePortState(
        context: UplistEntryContext,
        stateKey: String,
        message: String,
        print: (String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (!shouldAnnounceState(context.lastPortState, stateKey, context.lastPortStateAtMillis, 120_000L)) {
            return
        }
        context.lastPortState = stateKey
        context.lastPortStateAtMillis = now
        print(message)
    }

    private fun shouldAnnounceState(
        previousState: String,
        newState: String,
        lastAnnounceAtMillis: Long,
        minIntervalMillis: Long
    ): Boolean {
        if (previousState != newState) {
            return true
        }
        if (lastAnnounceAtMillis == 0L) {
            return true
        }
        return System.currentTimeMillis() - lastAnnounceAtMillis >= minIntervalMillis
    }

    private fun logBootstrapRetry() {
        val now = System.currentTimeMillis()
        if (now - lastBootstrapRetryLogAt < 60_000L) {
            return
        }
        lastBootstrapRetryLogAt = now
        Log.clog("[AutoUpList] Bootstrap retry")
    }

    private fun logPartialRetry(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastPartialRetryLogAt < 60_000L) {
            return
        }
        lastPartialRetryLogAt = now
        Log.clog("[AutoUpList] UPLIST partially active, retrying missing list servers ($reason)")
    }

    private fun probeMasterServer(name: String, baseUrl: String): String {
        val start = System.currentTimeMillis()
        awaitMasterServerRequest(name)
        val response = try {
            Data.core.uplistHttp.doGetQuiet("$baseUrl?action=list")
        } catch (_: Exception) {
            ""
        }
        val cost = System.currentTimeMillis() - start
        val head = response.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "<empty>" }
        val ok = head.contains("CORRODINGGAMES")
        return "[$name] listProbeOk=$ok, latency=${cost}ms, head=$head"
    }

    private fun healthState(serverKey: String): MasterServerHealthState {
        return if (serverKey == "gs1") gs1Health else gs4Health
    }

    private fun updateHealthState(serverKey: String, success: Boolean, response: String) {
        val current = healthState(serverKey)
        val now = System.currentTimeMillis()
        val failureKind = classifyMasterServerFailure(success, response)
        val next = if (success) {
            current.copy(
                lastSuccess = true,
                consecutiveFailures = 0,
                cooldownUntilMillis = 0L,
                lastSuccessAtMillis = now,
                lastAttemptAtMillis = now,
                lastMessage = summarizeResponse(response),
                lastFailureKind = ""
            )
        } else {
            val failures = current.consecutiveFailures + 1
            val cooldownSeconds = when {
                failureKind == MasterServerFailureKind.RATE_LIMITED -> 15 * 60L
                failureKind == MasterServerFailureKind.COOLDOWN_ACTIVE -> ((current.cooldownUntilMillis - now) / 1000L).coerceAtLeast(60L)
                failureKind == MasterServerFailureKind.EMPTY_RESPONSE && failures >= 3 -> 45L
                failures >= 10 -> 180L
                failures >= 6 -> 60L
                failures >= 3 -> 20L
                else -> 0L
            }
            current.copy(
                lastSuccess = false,
                consecutiveFailures = failures,
                cooldownUntilMillis = now + cooldownSeconds * 1000L,
                lastAttemptAtMillis = now,
                lastMessage = summarizeResponse(response),
                lastFailureKind = failureKind.name
            )
        }

        if (serverKey == "gs1") {
            gs1Health = next
        } else {
            gs4Health = next
        }

        if (!success && next.consecutiveFailures == 3 && failureKind != MasterServerFailureKind.RATE_LIMITED) {
            Log.warn("[UPLIST] ${next.name} has failed 3 consecutive times. Temporary cooldown enabled.")
        }
        if (!success && failureKind == MasterServerFailureKind.RATE_LIMITED) {
            Log.warn("[UPLIST] ${next.name} reported rate limiting. Extended cooldown enabled to reduce noisy retries.")
        }
        if (success && current.consecutiveFailures >= 3) {
            Log.clog("[UPLIST] ${next.name} recovered after ${current.consecutiveFailures} consecutive failures")
        }
    }

    private fun classifyMasterServerFailure(success: Boolean, response: String): MasterServerFailureKind {
        if (success) {
            return MasterServerFailureKind.NONE
        }
        val normalized = response.trim()
        if (normalized.isBlank() || normalized == "<empty>") {
            return MasterServerFailureKind.EMPTY_RESPONSE
        }
        if (normalized.startsWith("COOLDOWN_ACTIVE")) {
            return MasterServerFailureKind.COOLDOWN_ACTIVE
        }
        if (UpListResponseSupport.isExplicitNotFound(normalized)) {
            return MasterServerFailureKind.NOT_FOUND
        }
        if (normalized.contains("ERROR_TOO_MANY", ignoreCase = true) || normalized.contains("try again later", ignoreCase = true)) {
            return MasterServerFailureKind.RATE_LIMITED
        }
        if (normalized.startsWith("ERROR_", ignoreCase = true)) {
            return MasterServerFailureKind.REJECTED
        }
        if (UpListResponseSupport.hasExplicitFailureMarker(normalized)) {
            return MasterServerFailureKind.REJECTED
        }
        return MasterServerFailureKind.UNKNOWN
    }

    private fun summarizeResponse(response: String): String {
        return response
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.take(80)
            ?: "<empty>"
    }

    private fun buildStableServerId(roomId: String?, listenPort: Int, listEntryId: String? = null): String {
        val dataPath = runCatching {
            FileUtils.getFolder(Data.ServerDataPath).file.canonicalPath
        }.getOrDefault(Data.ServerDataPath)
        return UpListIdentitySupport.buildServerId(
            serverConnectUuid = Data.core.serverConnectUuid,
            dataPath = dataPath,
            roomId = roomId,
            listenPort = listenPort,
            listEntryId = listEntryId
        )
    }

    private fun buildLegacyServerId(context: UplistEntryContext): String {
        return UpListIdentitySupport.buildLegacyServerId(
            serverConnectUuid = Data.core.serverConnectUuid,
            roomId = context.roomId,
            listenPort = context.listenPort,
            listEntryId = context.listEntry?.id
        )
    }

    private fun fetchMasterServerSnapshot(url: String): MasterServerListSnapshot? {
        return try {
            awaitMasterServerRequest(if (url == GS1_MASTER_SERVER_URL) "gs1" else "gs4")
            val parsed = UpListSnapshotSupport.parse(
                Data.core.uplistHttp.doGetQuiet("$url?action=list")
            ) ?: return null
            MasterServerListSnapshot(
                entries = parsed.entries.map { entry ->
                    MasterServerListEntry(
                        id = entry.id,
                        gameName = entry.gameName,
                        privateIp = entry.privateIp,
                        port = entry.port,
                        createdBy = entry.createdBy,
                        gameMap = entry.gameMap,
                        gameStatus = entry.gameStatus,
                        playerCount = entry.playerCount,
                        maxPlayerCount = entry.maxPlayerCount
                    )
                },
                ids = parsed.ids,
                rawEntryCount = parsed.rawEntryCount,
                parsedEntryCount = parsed.parsedEntryCount,
                schema = parsed.schema
            )
        } catch (e: Exception) {
            Log.debug("[UPLIST] Failed to fetch master server list from $url: ${e.message}")
            null
        }
    }

    private fun awaitMasterServerRequest(serverKey: String) {
        when (serverKey) {
            "gs1" -> gs1RequestThrottle.awaitPermit()
            "gs4" -> gs4RequestThrottle.awaitPermit()
        }
    }

    private fun hasActiveUploadedContext(): Boolean {
        return uplistContexts.values.any { it.uploaded }
    }

    private fun hasPendingUploadContext(): Boolean {
        return uplistContexts.isNotEmpty() && uplistContexts.values.any { !it.isFullyUploaded() }
    }

    private fun contextLabel(context: UplistEntryContext): String {
        context.roomId?.let { return RelayDeploymentSupport.roomDisplayId(it) }
        context.listEntry?.let { return "[GLOBAL:${it.id}]" }
        return "[GLOBAL]"
    }

    private fun contextRoom(roomId: String?): RelayRoom? {
        return roomId?.let(RelayRoom::getRelay)
    }

    private fun contextProfile(roomId: String?): net.rwhps.server.net.api.RelayCustomRoomProfile? {
        return roomId?.let { RelayCustomRoomSupport.findAppliedProfile(it) ?: RelayCustomRoomSupport.findDefinition(it) }
    }

    private fun contextListenPort(roomId: String?): Int {
        contextProfile(roomId)?.connectPort?.takeIf { it > 0 }?.let {
            return it
        }
        return port.toIntOrNull()?.takeIf { it > 0 } ?: Data.config.port
    }

    private fun renderRelayRoomSummary(): List<String> {
        if (!isRelay) {
            return listOf("当前实例不是 Relay 模式，listnow 仅用于 Relay 房间统计")
        }

        val snapshots = RelayRoom.getAllRoomSnapshots(includeClosed = false)
        val waitingRooms = snapshots.count { !it.started }
        val startedRooms = snapshots.count { it.started }
        val publicRooms = snapshots.count { it.publicRoom && !it.started }
        val customRooms = snapshots.count { it.customRoom }

        val lines = mutableListOf<String>()
        lines += "========== Relay 当前统计 =========="
        lines += "当前总人数: ${RelayRoom.allSize}"
        lines += "当前房间数: ${RelayRoom.roomAllSize} | 等待中: $waitingRooms | 游戏中: $startedRooms | 公开房: $publicRooms | 定制房: $customRooms"
        lines += "累计创建房间: ${RelayRoom.totalRoomCreatedSize} | 累计关闭房间: ${RelayRoom.totalRoomClosedSize}"
        lines += "累计加入连接: ${RelayRoom.totalJoinConnectionSize} | 累计断开连接: ${RelayRoom.totalDisconnectedConnectionSize} | 历史峰值在线: ${RelayRoom.peakOnlineConnectionSize}"

        if (snapshots.isEmpty()) {
            lines += "房间列表: 当前没有活跃房间"
            return lines
        }

        lines += "---------- 房间列表 ----------"
        snapshots.forEachIndexed { index, snapshot ->
            val displayId = RelayDeploymentSupport.roomDisplayId(snapshot.roomId)
            val roomState = when {
                snapshot.started -> "游戏中"
                snapshot.publicRoom -> "公开等待"
                else -> "等待中"
            }
            val hostName = snapshot.hostName.trim().ifEmpty { "暂无房主" }
            val mapName = buildListDisplayMapName(snapshot.mapName, snapshot.isMod)
            lines += "${index + 1}. $displayId | ${snapshot.playerSize}人 | $roomState | 房主=$hostName | 地图=$mapName"
        }

        return lines
    }

    private fun resolveRoomIdFromListQueryInternal(query: String): String? {
        val candidates = buildListQueryCandidates(query)
        if (candidates.isEmpty()) {
            return null
        }

        val matchedRoomIds = linkedSetOf<String>()

        uplistContexts.values.forEach { context ->
            val roomId = context.roomId ?: return@forEach
            val room = contextRoom(roomId)
            val profile = contextProfile(roomId)
            val displayId = RelayDeploymentSupport.roomDisplayId(roomId)
            val lookupId = RelayDeploymentSupport.roomLookupId(roomId)

            val exactMarkers = linkedSetOf<String>().apply {
                add(context.serverID)
                add(context.effectiveServerId("gs1"))
                add(context.effectiveServerId("gs4"))
                add(roomId)
                add(displayId)
                add(lookupId)
                add(contextListDisplayNameFixed(roomId))
                add(contextMapNameFixed(roomId))
                room?.admin?.name?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                room?.currentMapName?.trimStart('\u0001')?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                profile?.defaultListName?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                profile?.defaultMapName?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }

            if (candidates.any { candidate ->
                    exactMarkers.any { marker -> candidate.equals(marker.trim(), ignoreCase = true) }
                }
            ) {
                matchedRoomIds += roomId
            }
        }

        return matchedRoomIds.singleOrNull()
    }

    private fun resolveRoomIdFromListPortInternal(localPort: Int): String? {
        if (localPort <= 0) {
            return null
        }

        val matchedRoomIds = uplistContexts.values
            .asSequence()
            .filter { it.roomId != null && it.listenPort == localPort }
            .mapNotNull { it.roomId }
            .filter(::contextMatchesActiveRoom)
            .distinct()
            .toList()

        return matchedRoomIds.singleOrNull()
    }

    private fun buildListQueryCandidates(query: String): Set<String> {
        val raw = query.trim()
        if (raw.isBlank()) {
            return emptySet()
        }

        val candidates = linkedSetOf<String>()

        fun addCandidate(value: String) {
            val trimmed = value.trim().trimEnd('/').trim()
            if (trimmed.isBlank()) {
                return
            }
            candidates += trimmed
            if (trimmed.startsWith("K", ignoreCase = true) && trimmed.length > 1) {
                candidates += trimmed.substring(1)
            }
        }

        addCandidate(raw)
        addCandidate(raw.substringAfterLast('/'))
        val normalizedHost = raw
            .removePrefix("https://")
            .removePrefix("http://")
            .trim()
        addCandidate(normalizedHost)
        addCandidate(normalizedHost.substringAfter('/'))
        addCandidate(normalizedHost.substringAfterLast('/'))
        return candidates
    }

    private fun renderRelayRoomSummaryFixed(): List<String> {
        if (!isRelay) {
            return listOf("当前实例不是 Relay 模式，listnow 仅用于 Relay 房间统计")
        }

        val snapshots = RelayRoom.getAllRoomSnapshots(includeClosed = false)
        val waitingRooms = snapshots.count { !it.started }
        val startedRooms = snapshots.count { it.started }
        val publicRooms = snapshots.count { it.publicRoom && !it.started }
        val customRooms = snapshots.count { it.customRoom }

        val lines = mutableListOf<String>()
        lines += "========== Relay 当前统计 =========="
        lines += "当前总人数: ${RelayRoom.allSize}"
        lines += "当前房间数: ${RelayRoom.roomAllSize} | 等待中: $waitingRooms | 游戏中: $startedRooms | 公开房: $publicRooms | 定制房: $customRooms"
        lines += "累计创建房间: ${RelayRoom.totalRoomCreatedSize} | 累计关闭房间: ${RelayRoom.totalRoomClosedSize}"
        lines += "累计加入连接: ${RelayRoom.totalJoinConnectionSize} | 累计断开连接: ${RelayRoom.totalDisconnectedConnectionSize} | 历史峰值在线: ${RelayRoom.peakOnlineConnectionSize}"

        if (snapshots.isEmpty()) {
            lines += "房间列表: 当前没有活跃房间"
            return lines
        }

        lines += "---------- 房间列表 ----------"
        snapshots.forEachIndexed { index, snapshot ->
            val displayId = RelayDeploymentSupport.roomDisplayId(snapshot.roomId)
            val roomState = when {
                snapshot.started -> "游戏中"
                snapshot.publicRoom -> "公开等待"
                else -> "等待中"
            }
            val hostName = snapshot.hostName.trim().ifEmpty { "暂无房主" }
            val mapName = buildListDisplayMapNameCompat(snapshot.mapName, snapshot.isMod)
            lines += "${index + 1}. $displayId | ${snapshot.playerSize}人 | $roomState | 房主=$hostName | 地图=$mapName"
        }

        return lines
    }

    private fun contextListDisplayNameFixed(context: UplistEntryContext): String {
        val entry = context.listEntry
        if (context.roomId.isNullOrBlank() && entry != null) {
            val safeName = RelayTextAuditSupport.auditPublicText("room_host", entry.serverName).text
            val suffix = currentListStatusSuffixFixed(entry)
            val keepLength = (listNameMaxLength - suffix.length).coerceAtLeast(1)
            return decorateListDisplayName("${cutting(safeName, keepLength)}$suffix", entry)
        }
        return contextListDisplayNameFixed(context.roomId)
    }

    private fun contextMapNameFixed(context: UplistEntryContext): String {
        val entry = context.listEntry
        if (context.roomId.isNullOrBlank() && entry != null) {
            val configuredMap = entry.mapName.takeIf { it.isNotBlank() } ?: entry.subtitle.takeIf { it.isNotBlank() }
            val displayMapName = buildListDisplayMapNameCompat(configuredMap ?: getMapName, false)
            val safeMapName = RelayTextAuditSupport.auditPublicText("room_map", displayMapName).text
            return decorateListMapName(safeMapName, entry)
        }
        return contextMapNameFixed(context.roomId)
    }

    private fun contextMaxPlayerSize(context: UplistEntryContext): Int {
        context.listEntry?.maxPlayerCount?.takeIf { context.roomId.isNullOrBlank() && it > 0 }?.let {
            return it
        }
        return contextMaxPlayerSize(context.roomId)
    }

    private fun contextListDisplayNameFixed(roomId: String?): String {
        if (roomId.isNullOrBlank()) {
            return currentListDisplayNameFixed()
        }

        val room = contextRoom(roomId)
        val profile = contextProfile(roomId)
        val useDefaultIdentity = room == null || room.closeRoom || room.isStartGame || room.admin == null || room.getSize() <= 0
        val rawName = ((if (!useDefaultIdentity) room.admin?.name?.trim()?.takeIf { it.isNotBlank() } else null)
            ?: profile?.defaultListName?.trim()?.takeIf { it.isNotBlank() }
            ?: Data.config.serverName.trim().ifEmpty { "CNKD节点" })
        val safeName = RelayTextAuditSupport.auditPublicText("room_host", rawName).text
        val suffix = contextListStatusSuffixFixed(roomId)
        val keepLength = (listNameMaxLength - suffix.length).coerceAtLeast(1)
        return decorateListDisplayName("${cutting(safeName, keepLength)}$suffix")
    }

    private fun currentListDisplayNameFixed(): String {
        val baseName = Data.config.serverName.trim().ifEmpty { "CNKD节点" }
        val suffix = currentListStatusSuffixFixed()
        val keepLength = (listNameMaxLength - suffix.length).coerceAtLeast(1)
        return decorateListDisplayName("${cutting(baseName, keepLength)}$suffix")
    }

    private fun currentListStatusSuffixFixed(): String {
        if (!Data.config.listStatusSuffixEnabled) {
            return ""
        }
        if (!isRelay && HeadlessModuleManage.initHPS() && HeadlessModuleManage.hps.room.isStartGame) {
            return "\uFF08\u6E38\u620F\u4E2D\uFF09"
        }
        return when {
            serverPlayerSize > 100 -> "（繁忙）"
            serverPlayerSize >= 20 -> "（正常）"
            else -> "（空闲）"
        }
    }

    private fun contextListStatusSuffixFixed(roomId: String?): String {
        if (!Data.config.listStatusSuffixEnabled) {
            return ""
        }
        if (roomId.isNullOrBlank()) {
            return currentListStatusSuffixFixed()
        }

        val room = contextRoom(roomId)
        if (room?.isStartGame == true) {
            return "\uFF08\u6E38\u620F\u4E2D\uFF09"
        }
        return when {
            room == null || room.closeRoom || room.isStartGame || room.admin == null || room.getSize() <= 0 -> "（空闲）"
            room.getSize() >= 8 -> "（即将开始）"
            else -> "（等待中）"
        }
    }

    private fun contextMapNameFixed(roomId: String?): String {
        if (roomId.isNullOrBlank()) {
            return currentListMapNameFixed()
        }

        val room = contextRoom(roomId)
        val profile = contextProfile(roomId)
        val modEnabled = room?.isMod == true || profile?.isMod == true
        val allowLiveMap = room != null && !room.closeRoom && !room.isStartGame && room.admin != null && room.getSize() > 0
        val liveMapName = if (allowLiveMap) {
            room.currentMapName?.trim()?.takeIf { it.isNotBlank() && !it.equals("RELAY", ignoreCase = true) }
        } else {
            null
        }
        val fallbackMapName = profile?.defaultMapName?.trim()?.takeIf { it.isNotBlank() } ?: getMapName
        val displayMapName = buildListDisplayMapNameCompat(liveMapName ?: fallbackMapName, modEnabled)
        val safeMapName = RelayTextAuditSupport.auditPublicText("room_map", displayMapName).text
        return decorateListMapName(safeMapName)
    }

    private fun currentListMapNameFixed(): String {
        val mapName = buildListDisplayMapNameCompat(getMapName, false)
        val safeMapName = RelayTextAuditSupport.auditPublicText("room_map", mapName).text
        return decorateListMapName(safeMapName)
    }

    private fun buildListDisplayMapNameCompat(rawMapName: String, modEnabled: Boolean): String {
        val displayMapName = sanitizeDisplayMapName(rawMapName)
        if (!modEnabled) {
            return displayMapName
        }

        val modLabel = extractDisplayModLabelFixed(rawMapName)
            ?: extractDisplayModLabel(rawMapName)
            ?: "妯＄粍"
        return "[${cutting(modLabel, 24)}] $displayMapName"
    }

    private fun contextListDisplayName(roomId: String?): String {
        if (roomId.isNullOrBlank()) {
            return currentListDisplayName()
        }

        val room = contextRoom(roomId)
        val profile = contextProfile(roomId)
        val useDefaultIdentity = room == null || room.closeRoom || room.isStartGame || room.admin == null || room.getSize() <= 0
        val rawName = ((if (!useDefaultIdentity) room.admin?.name?.trim()?.takeIf { it.isNotBlank() } else null)
            ?: profile?.defaultListName?.trim()?.takeIf { it.isNotBlank() }
            ?: Data.config.serverName.trim().ifEmpty { "CNKD服务器" })
        val safeName = RelayTextAuditSupport.auditPublicText("room_host", rawName).text
        val suffix = contextListStatusSuffix(roomId)
        val keepLength = (listNameMaxLength - suffix.length).coerceAtLeast(1)
        return decorateListDisplayName("${cutting(safeName, keepLength)}$suffix")
    }

    private fun contextMapName(roomId: String?): String {
        if (roomId.isNullOrBlank()) {
            return currentListMapName()
        }

        val room = contextRoom(roomId)
        val profile = contextProfile(roomId)
        val modEnabled = room?.isMod == true || profile?.isMod == true
        val allowLiveMap = room != null && !room.closeRoom && !room.isStartGame && room.admin != null && room.getSize() > 0
        val liveMapName = if (allowLiveMap) room.currentMapName
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("RELAY", ignoreCase = true) }
        else null
        val fallbackMapName = profile?.defaultMapName?.trim()?.takeIf { it.isNotBlank() } ?: getMapName
        val displayMapName = buildListDisplayMapName(liveMapName ?: fallbackMapName, modEnabled)
        val safeMapName = RelayTextAuditSupport.auditPublicText("room_map", displayMapName).text
        return decorateListMapName(safeMapName)
    }

    private fun contextGameStatus(roomId: String?): String {
        if (roomId.isNullOrBlank()) {
            return currentListGameStatus()
        }
        return when {
            contextRoom(roomId)?.isStartGame == true -> "ingame"
            else -> "battleroom"
        }
    }

    private fun contextPlayerSize(roomId: String?): Int {
        if (roomId.isNullOrBlank()) {
            return serverPlayerSize
        }
        return contextRoom(roomId)?.getSize() ?: 0
    }

    private fun contextMaxPlayerSize(roomId: String?): Int {
        if (isRelay) {
            val configuredAdvertisedMaxPlayer = Data.configRelay.decision.uplistAdvertisedMaxPlayerSize
            if (configuredAdvertisedMaxPlayer > 0) {
                return configuredAdvertisedMaxPlayer
            }
        }

        if (roomId.isNullOrBlank()) {
            return if (isRelay) 50 else Data.configServer.maxPlayer.coerceAtLeast(1)
        }

        contextRoom(roomId)?.currentMaxPlayerSize?.takeIf { it > 0 }?.let {
            return it
        }

        val profileMaxPlayer = contextProfile(roomId)?.maxPlayerSize?.takeIf { it > 0 }
        if (profileMaxPlayer != null) {
            return profileMaxPlayer
        }

        val currentSize = contextRoom(roomId)?.getSize() ?: 0
        return currentSize.coerceAtLeast(if (isRelay) 50 else Data.configServer.maxPlayer.coerceAtLeast(1))
    }

    private fun currentListDisplayName(): String {
        val baseName = Data.config.serverName.trim().ifEmpty { "CNKD节点" }
        val suffix = currentListStatusSuffix()
        val keepLength = (listNameMaxLength - suffix.length).coerceAtLeast(1)
        return decorateListDisplayName("${cutting(baseName, keepLength)}$suffix")
    }

    private fun currentListStatusSuffix(): String {
        if (!Data.config.listStatusSuffixEnabled) {
            return ""
        }
        if (!isRelay && HeadlessModuleManage.initHPS() && HeadlessModuleManage.hps.room.isStartGame) {
            return "\uFF08\u6E38\u620F\u4E2D\uFF09"
        }
        return when {
            serverPlayerSize > 100 -> "（繁忙）"
            serverPlayerSize >= 20 -> "（正常）"
            else -> "（空闲）"
        }
    }

    private fun contextListStatusSuffix(roomId: String?): String {
        if (!Data.config.listStatusSuffixEnabled) {
            return ""
        }
        if (roomId.isNullOrBlank()) {
            return currentListStatusSuffix()
        }

        val room = contextRoom(roomId)
        if (room?.isStartGame == true) {
            return "\uFF08\u6E38\u620F\u4E2D\uFF09"
        }
        return when {
            room == null || room.closeRoom || room.isStartGame || room.admin == null || room.getSize() <= 0 -> "（空闲）"
            room.getSize() >= 8 -> "（即将开始）"
            else -> "（等待中）"
        }
    }

    private fun currentListMapName(): String {
        val mapName = buildListDisplayMapName(getMapName, false)
        val safeMapName = RelayTextAuditSupport.auditPublicText("room_map", mapName).text
        return decorateListMapName(safeMapName)
    }

    private fun decorateListDisplayName(rawName: String): String {
        return decorateListDisplayName(rawName, null)
    }

    private fun decorateListDisplayName(rawName: String, entry: ResolvedPublicListEntry?): String {
        val normalizedName = cutting(rawName.trimStart('\u0001'), 1000000)
        if (shouldPinListEntry()) {
            val prefix = entry?.let { "\u0001".repeat(it.listNamePinnedCharacterCount.coerceIn(0, 64)) }
                ?: listNamePinnedPrefix
            return "$prefix$normalizedName"
        }
        return normalizedName
    }

    private fun decorateListMapName(rawMapName: String): String {
        return decorateListMapName(rawMapName, null)
    }

    private fun decorateListMapName(rawMapName: String, entry: ResolvedPublicListEntry?): String {
        val normalizedMapName = cutting(rawMapName.trimStart('\u0001'), 1000000)
        if (shouldPinListEntry()) {
            val prefix = entry?.let { "\u0001".repeat(it.listPinnedCharacterCount.coerceIn(0, 64)) }
                ?: listMapPinnedPrefix
            return "$prefix$normalizedMapName"
        }
        return normalizedMapName
    }

    private fun shouldPinListEntry(): Boolean {
        if (isRelay) {
            return true
        }
        if (!HeadlessModuleManage.initHPS()) {
            return true
        }
        return !HeadlessModuleManage.hps.room.isStartGame
    }

    private fun sanitizeDisplayMapName(rawMapName: String): String {
        var mapName = rawMapName.trimStart('\u0001').trim()
        if (mapName.isBlank()) {
            return "RELAY"
        }

        mapName = mapName.substringAfterLast('\\')
        mapName = mapName.substringAfterLast('/')
        mapName = mapName.substringAfterLast('|')
        mapName = mapName.trim().trim('"', '\'')

        val dotIndex = mapName.lastIndexOf('.')
        if (dotIndex in 1 until mapName.length - 1) {
            val suffix = mapName.substring(dotIndex + 1)
            if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
                mapName = mapName.substring(0, dotIndex)
            }
        }

        return mapName.replace("\\s+".toRegex(), " ").trim().ifBlank { "RELAY" }
    }

    private fun buildListDisplayMapName(rawMapName: String, modEnabled: Boolean): String {
        val displayMapName = sanitizeDisplayMapName(rawMapName)
        if (!modEnabled) {
            return displayMapName
        }

        val modLabel = extractDisplayModLabelFixed(rawMapName) ?: return displayMapName
        return "[${cutting(modLabel, 24)}] $displayMapName"
    }

    private fun extractDisplayModLabel(rawMapName: String): String? {
        val normalized = rawMapName
            .trimStart('\u0001')
            .replace('\\', '/')
            .trim()
        if (normalized.isBlank()) {
            return null
        }

        val inlineMarker = Regex("(?i)(?:required\\s+mods?|mod)\\s*[:：]\\s*\"?([^\"\\r\\n]+)")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::sanitizeDisplayModLabel)
        if (!inlineMarker.isNullOrBlank()) {
            return inlineMarker
        }

        val pathMarker = Regex("(?i)(?:^|/)(?:mods|builtin_mods|mods-enabled|builtin_mods_enabled|workshop)/([^/]+)/")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::sanitizeDisplayModLabel)
        if (!pathMarker.isNullOrBlank()) {
            return pathMarker
        }

        return null
    }

    private fun sanitizeDisplayModLabel(rawLabel: String): String? {
        val cleaned = rawLabel
            .substringBefore('|')
            .substringBefore('/')
            .trim()
            .trim('"', '\'', '[', ']', '(', ')')
            .replace('_', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
        if (cleaned.isBlank()) {
            return null
        }
        val normalized = cleaned.lowercase()
        if (normalized == "mod" || normalized == "mods" || normalized == "required mods") {
            return null
        }
        return cleaned
    }

    private fun extractDisplayModLabelFixed(rawMapName: String): String? {
        val normalized = rawMapName
            .trimStart('\u0001')
            .replace('\\', '/')
            .trim()
        if (normalized.isBlank()) {
            return null
        }

        val inlinePatterns = listOf(
            Regex("(?i)(?:required\\s+mods?|mod)\\s*[:\\uFF1A]\\s*\"?([^\"\\r\\n]+)"),
            Regex("(?i)\\[mod\\]\\s*([^\\r\\n]+)")
        )
        inlinePatterns.forEach { pattern ->
            val inlineMarker = pattern.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::sanitizeDisplayModLabelFixed)
            if (!inlineMarker.isNullOrBlank()) {
                return inlineMarker
            }
        }

        val pathMarker = Regex("(?i)(?:^|/)(?:mods|builtin_mods|mods-enabled|builtin_mods_enabled|workshop)/([^/]+)/")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::sanitizeDisplayModLabelFixed)
        if (!pathMarker.isNullOrBlank()) {
            return pathMarker
        }

        return null
    }

    private fun sanitizeDisplayModLabelFixed(rawLabel: String): String? {
        val cleaned = rawLabel
            .substringBefore(',')
            .substringBefore(';')
            .substringBefore('|')
            .substringBefore('/')
            .trim()
            .trim('"', '\'', '[', ']', '(', ')')
            .replace('_', ' ')
            .replace("\\s+".toRegex(), " ")
            .trim()
        if (cleaned.isBlank()) {
            return null
        }
        val normalized = cleaned.lowercase()
        if (normalized == "mod" || normalized == "mods" || normalized == "required mods") {
            return null
        }
        return cleaned
    }

    private fun currentListGameStatus(): String {
        return when {
            !isRelay && HeadlessModuleManage.hps.room.isStartGame -> "ingame"
            else -> "battleroom"
        }
    }

    private fun contextMatchesActiveRoom(roomId: String): Boolean {
        val room = contextRoom(roomId) ?: return false
        if (room.closeRoom) {
            return false
        }
        if (relayServerModeEnabled()) {
            return true
        }
        return room.publicRoom && !room.isStartGame
    }

    private fun preferredPublicRoom(): RelayRoom? {
        return if (isRelay) RelayRoom.getPreferredPublicRoom() else null
    }

    private fun buildRelayPublicRoomRebuildGuide(room: RelayRoom): RelayPublicRoomRebuildGuide? {
        val targetNode = RelayNodeRoutingSupport.selectNodeForPublicRoom(Data.configRelay.localNodeCode) ?: return null
        val preserveRoomId = canReuseRoomId(room.id)
        val createInput = if (preserveRoomId) {
            "R${targetNode.code}C${if (room.isMod) "M" else ""}${room.id}@"
        } else {
            "R${targetNode.code}${if (room.isMod) "mods" else "news"}"
        }
        val fallbackInput = "R${targetNode.code}${if (room.isMod) "mods" else "news"}"
        val targetDisplayId = if (preserveRoomId) {
            RelayDeploymentSupport.roomDisplayId(room.id, targetNode.code)
        } else {
            ""
        }
        return RelayPublicRoomRebuildGuide(
            node = targetNode,
            preserveRoomId = preserveRoomId,
            targetDisplayId = targetDisplayId,
            createInput = createInput,
            fallbackInput = fallbackInput,
            joinLink = buildNodeRoomJoinLink(targetNode, targetDisplayId)
        )
    }

    private fun renderRelayPublicRoomRebuildGuide(room: RelayRoom, guide: RelayPublicRoomRebuildGuide): String {
        val targetName = guide.node.displayName.ifBlank { guide.node.name.ifBlank { guide.node.code } }
        val roomHint = if (guide.preserveRoomId) {
            "建议迁移房号: ${guide.targetDisplayId}"
        } else {
            "当前房号 ${room.id} 不适合直接保留，请在目标节点重新创建公开房。"
        }
        val joinHint = guide.joinLink?.takeIf { it.isNotBlank() }?.let { "目标入口: $it" }
        val backupHint = if (guide.fallbackInput != guide.createInput) {
            "若原房号在目标节点已被占用，请改用 ${guide.fallbackInput}"
        } else {
            "若原房号无法保留，重建后的新房号以目标节点返回结果为准。"
        }
        val playerHint = if (guide.preserveRoomId) {
            "其他玩家随后输入 ${guide.targetDisplayId} 即可重新加入"
        } else {
            "其他玩家按重建后的新房号加入即可"
        }
        return listOfNotNull(
            "当前节点公开额度已满，已为你生成跨节点重建指引：",
            "目标节点: $targetName",
            roomHint,
            joinHint,
            "房主退出后在欢迎页输入: ${guide.createInput}",
            backupHint,
            playerHint
        ).joinToString("\n")
    }

    private fun buildNodeRoomJoinLink(node: BeanRelayNodeConfig, displayId: String): String? {
        if (displayId.isBlank()) {
            return null
        }
        node.joinBaseUrl.trim().takeIf { it.isNotBlank() }?.let {
            return "${it.trimEnd('/')}/$displayId"
        }
        node.domain.trim().takeIf { it.isNotBlank() }?.let {
            return "$it:${node.port}/$displayId"
        }
        node.ip.trim().takeIf { it.isNotBlank() }?.let {
            return "$it:${node.port}/$displayId"
        }
        return null
    }

    private fun canReuseRoomId(roomId: String): Boolean {
        return roomId.length in 3..7 && !roomId.contains(".")
    }

    private val isRelay get() = (NetStaticData.RwHps.netType == IRwHps.NetType.RelayProtocol || NetStaticData.RwHps.netType == IRwHps.NetType.RelayMulticastProtocol)

    private val getMapName
        get() = if (isRelay) {
            Data.config.subtitle.ifEmptyResult({ "RELAY" }) { cutting(it, 1000000) }
        } else {
            val mapName = if (HeadlessModuleManage.initHPS()) {
                HeadlessModuleManage.hps.room.maps.mapName
            } else {
                Data.config.subtitle
            }
            cutting(mapName.ifBlank { "RELAY" }, 1000000)
        }

    private val serverPlayerSize get() = AtomicInteger().apply {
        if (isRelay) {
            NetStaticData.netService.eachAllFind( { it.netType == NetService.Companion.NetTypeEnum.HeadlessNet }) { addAndGet(it.getConnectSize()) }
        } else {
            addAndGet(if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps.room.playerManage.countConnectedOccupants() else 0)
        }
    } .get()

    private data class ResolvedPublicListEntry(
        val id: String,
        val enabled: Boolean,
        val serverName: String,
        val subtitle: String,
        val mapName: String,
        val statusSuffixEnabled: Boolean,
        val listNamePinnedCharacterCount: Int,
        val listPinnedCharacterCount: Int,
        val maxPlayerCount: Int?
    )

    private data class UplistEntryContext(
        val roomId: String?,
        var listEntry: ResolvedPublicListEntry? = null,
        val listenPort: Int,
        val serverID: String,
        val addData: String,
        val openData: String,
        val updateData: String,
        val removeData: String,
        var uploaded: Boolean = false,
        var localPortReady: Boolean = false,
        var portOpen: Boolean = false,
        var gs1Uploaded: Boolean = false,
        var gs4Uploaded: Boolean = false,
        var gs1PortOpen: Boolean = false,
        var gs4PortOpen: Boolean = false,
        var lastUploadState: String = "",
        var lastPortState: String = "",
        var lastUploadStateAtMillis: Long = 0L,
        var lastPortStateAtMillis: Long = 0L,
        var gs1LastUploadAcceptedAtMillis: Long = 0L,
        var gs4LastUploadAcceptedAtMillis: Long = 0L,
        var gs1LastKeepAliveAcceptedAtMillis: Long = 0L,
        var gs4LastKeepAliveAcceptedAtMillis: Long = 0L,
        var gs1EndpointMismatch: Boolean = false,
        var gs4EndpointMismatch: Boolean = false,
        var gs1LastEndpointRepairAtMillis: Long = 0L,
        var gs4LastEndpointRepairAtMillis: Long = 0L,
        var gs1Visible: Boolean? = null,
        var gs4Visible: Boolean? = null,
        var lastVisibilityCheckAtMillis: Long = 0L,
        var lastVisibilityState: String = "",
        var lastVisibilityStateAtMillis: Long = 0L,
        var consecutiveVisibilityUnknownCount: Int = 0,
        var firstVisibilityUnknownAtMillis: Long = 0L,
        var gs1ConsecutiveVisibilityMissing: Int = 0,
        var gs4ConsecutiveVisibilityMissing: Int = 0,
        var lastAppliedFieldSignature: String = ""
    ) {
        fun isFullyUploaded(): Boolean = gs1Uploaded && gs4Uploaded

        fun hasAnyUploadedServer(): Boolean = gs1Uploaded || gs4Uploaded

        fun effectiveServerId(serverKey: String): String = serverID
    }

    private fun currentListStatusSuffixFixed(entry: ResolvedPublicListEntry): String {
        if (!entry.statusSuffixEnabled) {
            return ""
        }
        if (!isRelay && HeadlessModuleManage.initHPS() && HeadlessModuleManage.hps.room.isStartGame) {
            return "\uFF08\u6E38\u620F\u4E2D\uFF09"
        }
        return when {
            serverPlayerSize > 100 -> "（繁忙）"
            serverPlayerSize >= 20 -> "（正常）"
            else -> "（空闲）"
        }
    }

    private data class UploadAttemptResult(
        val success: Boolean,
        val response: String,
        val recoveredExisting: Boolean = false
    )

    private data class MasterServerListEntry(
        val id: String,
        val gameName: String,
        val privateIp: String,
        val port: Int,
        val createdBy: String,
        val gameMap: String,
        val gameStatus: String,
        val playerCount: Int,
        val maxPlayerCount: Int
    )

    private data class MasterServerListSnapshot(
        val entries: List<MasterServerListEntry>,
        val ids: Set<String>,
        val rawEntryCount: Int = entries.size,
        val parsedEntryCount: Int = entries.size,
        val schema: String = "unknown"
    ) {
        val isComplete: Boolean
            get() = rawEntryCount == parsedEntryCount &&
                (schema == "empty" || schema == "22-column" || schema == "18-column")

        companion object {
            fun empty(): MasterServerListSnapshot = MasterServerListSnapshot(
                entries = emptyList(),
                ids = emptySet(),
                rawEntryCount = 0,
                parsedEntryCount = 0,
                schema = "not-requested"
            )
        }
    }

    private data class MasterServerHealthState(
        val name: String,
        val lastSuccess: Boolean = false,
        val consecutiveFailures: Int = 0,
        val cooldownUntilMillis: Long = 0L,
        val lastAttemptAtMillis: Long = 0L,
        val lastSuccessAtMillis: Long = 0L,
        val lastMessage: String = "",
        val lastFailureKind: String = ""
    )

    private data class PendingDeactivation(
        val preserveDesired: Boolean,
        val reason: String
    )

    private enum class MasterServerFailureKind {
        NONE,
        NOT_FOUND,
        RATE_LIMITED,
        COOLDOWN_ACTIVE,
        EMPTY_RESPONSE,
        REJECTED,
        UNKNOWN
    }

    private data class RelayPublicRoomRebuildGuide(
        val node: BeanRelayNodeConfig,
        val preserveRoomId: Boolean,
        val targetDisplayId: String,
        val createInput: String,
        val fallbackInput: String,
        val joinLink: String?
    )

    /**
     * Inject multiple languages into the server
     * @author Dr (dr@der.kim)
     */
    private class AddLang(val plugin: Plugin) {
        init {
            help()
        }

        private fun help() {
            loadCN(
                    "uplist.help", """       
        [uplist add] 上传服务器到列表，显示配置文件中的端口
        [uplist add (port)] 上传服务器到列表，使用自定义显示端口
        [uplist update] 立刻更新列表上的服务器信息
        [uplist status] 查看当前 uplist 健康状态与补传状态
        [uplist remove] 取消服务器列表上传
        [uplist help] 获取帮助
        """.trimIndent()
            )
            loadEN(
                    "uplist.help", """
        [uplist add] Server upload to list Show profile port
        [uplist add (port)] Server upload to list Server running profile port Display custom port
        [uplist update] Update list server information immediately
        [uplist status] Show current uplist health and retry state
        [uplist remove] Cancel server upload list
        [uplist help] Get Help
        """.trimIndent()
            )
            loadHK(
                    "uplist.help", """        
        [uplist add] 上傳伺服器到列表，顯示配置檔中的連接埠
        [uplist add (port)] 上傳伺服器到列表，使用自訂顯示連接埠
        [uplist update] 立即更新列表上的伺服器資訊
        [uplist status] 查看目前 uplist 健康狀態與補傳狀態
        [uplist remove] 取消伺服器列表上傳
        [uplist help] 取得幫助
        """.trimIndent()
            )
            loadRU(
                    "uplist.help", """
        [uplist add] 袟邪谐褉褍蟹泻邪 褋械褉胁械褉邪 胁 褋锌懈褋芯泻 袩芯泻邪蟹邪褌褜 锌芯褉褌 锌褉芯褎懈谢褟
        [uplist add (port)] 袟邪谐褉褍蟹泻邪 褋械褉胁械褉邪 胁 褋锌懈褋芯泻 袩芯褉褌 蟹邪锌褍褖械薪薪芯谐芯 锌褉芯褎懈谢褟 褋械褉胁械褉邪 袩芯泻邪蟹邪褌褜 锌芯谢褜蟹芯胁邪褌械谢褜褋泻懈械 锌芯褉褌褘
        [uplist update] 袧械屑械写谢械薪薪芯械 芯斜薪芯胁谢械薪懈械 懈薪褎芯褉屑邪褑懈懈 褋械褉胁械褉邪 褋锌懈褋泻邪
        [uplist status] 袩芯泻邪蟹邪褌褜 褌械泻褍褖械械 褋芯褋褌芯褟薪懈械 uplist 懈 锌芯胁褌芯褉薪褘褏 锌芯锌褘褌芯泻
        [uplist remove] 袨褌屑械薪邪 蟹邪谐褉褍蟹泻懈 褋械褉胁械褉邪 胁 褋锌懈褋芯泻
        [uplist help] 袩芯谢褍褔懈褌褜 锌芯屑芯褖褜
        """.trimIndent()
            )
        }

        private fun loadCN(k: String, v: String) {
            plugin.loadLang("CN", k, v)
        }

        private fun loadEN(k: String, v: String) {
            plugin.loadLang("EN", k, v)
        }

        private fun loadHK(k: String, v: String) {
            plugin.loadLang("HK", k, v)
        }

        private fun loadRU(k: String, v: String) {
            plugin.loadLang("RU", k, v)
        }
    }
}
// 给岁月以文明，而不是给文明以岁月
