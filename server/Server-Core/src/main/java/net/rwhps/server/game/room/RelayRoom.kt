/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.room

import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.Data.LINE_SEPARATOR
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.data.temp.RelayPacketCache
import net.rwhps.server.game.player.PlayerRelay
import net.rwhps.server.net.GroupNet
import net.rwhps.server.net.api.RelayCustomRoomProfile
import net.rwhps.server.net.api.RelayCustomRoomSupport
import net.rwhps.server.net.api.RelayDeploymentSupport
import net.rwhps.server.net.core.DataPermissionStatus
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.struct.map.IntMap
import net.rwhps.server.struct.map.ObjectIntMap
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.IsUtils.isNumeric
import net.rwhps.server.util.Time
import net.rwhps.server.util.Time.concurrentSecond
import net.rwhps.server.util.algorithms.NetConnectProofOfWork
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.annotations.mark.SynchronizeMark
import net.rwhps.server.util.concurrent.lock.ReadWriteConcurrentSynchronize
import net.rwhps.server.util.concurrent.lock.Synchronize
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.debug
import net.rwhps.server.util.math.Rand
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * @author Dr (dr@der.kim)
 */
@PrivateMark
class RelayRoom {
    /**
     * 玩家群发池, 通过这个来快速群发数据包
     */
    @JvmField
    val groupNet: GroupNet

    /**
     * 房间ID对应的玩家
     */
    val abstractNetConnectIntMap = IntMap<GameVersionRelay>(16, true)


    /**
     * 房间的管理员(HOST)
     */
    @SynchronizeMark
    var admin: GameVersionRelay? by ReadWriteConcurrentSynchronize(null, set = {
        it?.permissionStatus = DataPermissionStatus.RelayStatus.HostPermission
        return@ReadWriteConcurrentSynchronize it
    })

    /** The server kicks the player's time data */
    val relayKickData: ObjectMap<String, Int> = ObjectMap()

    /** Server exits player data */
    val relayPlayersData: ObjectMap<String, PlayerRelay> = ObjectMap()
    var replacePlayerHex = ""

    val banUnit = Seq<String>()

    /**
     * 房间是否关闭
     */
    @SynchronizeMark
    var closeRoom: Boolean by Synchronize(false)

    val roomCreateTime = concurrentSecond()

    val serverUuid = Data.SERVER_RELAY_UUID
    val internalID: Int
    val id: String
    var isMod = false
    var minSize = 0
        private set

    val gamePacket = RelayPacketCache()

    @SynchronizeMark
    var isStartGame: Boolean by Synchronize(false, set = {
        // Check via startGameTime to avoid self-reference during property initialization
        if (startGameTime > 0 && it) {
            return@Synchronize it
        }

        startGameTime = if (it) {
            concurrentSecond() + 300
        } else {
            0
        }

        return@Synchronize it
    })

    /**
     * 开始游戏的时间
     */
    var startGameTime = 0
        private set

    var allmute = false
    // 代号: Lone Star
    //var playerTeamSize = -1
    var syncFlag = true
    @Volatile
    var publicRoom = false
        private set
    @Volatile
    var publicTopTime = concurrentSecond()
        private set
    @Volatile
    var publicPriority = 0
        private set
    @Volatile
    var lastActivityTime = concurrentSecond()
        private set
    @Volatile
    var customRoomProfile: RelayCustomRoomProfile? = null
        private set
    @Volatile
    var currentMapName = "RELAY"
        private set
    @Volatile
    var currentMaxPlayerSize = 50
        private set
    @Volatile
    var currentMaxUnitSize = 200
        private set
    @Volatile
    var currentIncome = 1f
        private set
    @Volatile
    var manualMaxPlayerOverride = false
        private set
    @Volatile
    var suppressPopulationNotice = false
        private set

    private val site = AtomicInteger(0)
    private val size = AtomicInteger()

    /**
     * 实例化一个房间
     * @param internalID 房间内部ID
     * @param id 房间ID
     * @param playerName 房主名字
     * @param isMod 是否启用mod
     * @param betaGameVersion 是否是测试版本
     * @param version 版本号
     * @param maxPlayer 最大玩家
     * @constructor
     */
    private constructor(
        internalID: Int, id: String, playerName: String, isMod: Boolean, betaGameVersion: Boolean, version: Int, maxPlayer: Int
    ) {
        serverRelayDataRoom[internalID] = this
        this.internalID = internalID
        this.id = id
        groupNet = GroupNet()
        this.isMod = isMod
        currentMaxPlayerSize = maxPlayer.coerceIn(1, 100)
        touchActivity()
    }

    /**
     * 仅供内部使用
     * 作为单房间实例化
     * @param id String
     * @param groupNet GroupNet
     * @constructor
     */
    internal constructor(id: String, groupNet: GroupNet) {
        this.internalID = 0
        this.id = id
        this.groupNet = groupNet
    }

    val allIP: String
        get() {
            val str = StringBuilder(10)
            val roomAdmin = admin
            if (roomAdmin != null) {
                str.append(LINE_SEPARATOR).append(roomAdmin.name).append(" / ").append("IP: ").append(roomAdmin.ip).append(" / ")
                    .append("Protocol: ").append(roomAdmin.useConnectionAgreement).append(" / ").append("Admin: true")
            }
            abstractNetConnectIntMap.values.forEach(Consumer { e: GameVersionRelay ->
                str.append(LINE_SEPARATOR).append(e.name).append(" / ").append("IP: ").append(e.ip).append(" / ").append("Protocol: ")
                    .append(e.useConnectionAgreement).append(" / ").append("Admin: false")
            })
            return str.toString()
        }

    fun getAbstractNetConnect(site: Int): GameVersionRelay? {
        return abstractNetConnectIntMap[site]
    }

    fun setAbstractNetConnect(abstractNetConnect: GameVersionRelay) {
        abstractNetConnectIntMap[abstractNetConnect.site] = abstractNetConnect
        touchActivity()
    }

    fun sendMsg(msg: String) {
        try {
            admin?.sendPacket(NetStaticData.RwHps.abstractNetPacket.getSystemMessagePacket(msg))
            //groupNet.broadcastAndUDP(NetStaticData.RwHps.abstractNetPacket.getSystemMessagePacket(msg))
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    fun sendMsg(msg: String, msgName: String, team: Int) {
        try {
            admin?.sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(msg, msgName, team))
            //groupNet.broadcastAndUDP(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(msg, msgName, team))
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    fun broadcastChatNotice(msg: String, msgName: String, team: Int = 5) {
        try {
            admin?.sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(msg, msgName, team))
            abstractNetConnectIntMap.values.forEach { player ->
                player.sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(msg, msgName, team))
            }
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    fun removeAbstractNetConnect(site: Int) {
        abstractNetConnectIntMap.remove(site)
    }

    fun getActiveConnectionSize(): Int {
        return abstractNetConnectIntMap.size
    }

    fun getPosition(): Int {
        return site.get()
    }

    fun setAddPosition(): Int {
        return site.incrementAndGet()
    }

    fun getSize(): Int {
        return size.get()
    }

    fun setAddSize() {
        size.incrementAndGet()
        touchActivity()
        recordJoinConnection()
    }

    fun setRemoveSize() {
        size.decrementAndGet()
        touchActivity()
        recordDisconnectConnection()
    }

    fun setPublicRoom(value: Boolean, top: Boolean = value) {
        publicRoom = value
        if (value && top) {
            publicTopTime = concurrentSecond()
        }
        touchActivity()
        UpListMain.requestRefresh(force = true)
    }

    fun setPublicPriority(value: Int) {
        publicPriority = value.coerceAtLeast(0)
        touchActivity()
    }

    fun applyCustomRoomProfile(profile: RelayCustomRoomProfile) {
        customRoomProfile = profile
        setPublicPriority(profile.publicPriority)
        if (profile.maxPlayerSize > 0 || profile.maxUnitSize > 0 || profile.income > 0f) {
            setCustomRoomSettings(
                maxPlayerSize = profile.maxPlayerSize.takeIf { it > 0 } ?: currentMaxPlayerSize,
                maxUnitSize = profile.maxUnitSize.takeIf { it > 0 } ?: currentMaxUnitSize,
                income = profile.income.takeIf { it > 0f } ?: currentIncome,
                manualOverride = false
            )
        }
        if (profile.autoPublicRoom || profile.forcePublicRoom) {
            setPublicRoom(true, top = true)
        }
    }

    fun topPublicRoom() {
        publicTopTime = concurrentSecond()
        touchActivity()
    }

    fun touchActivity() {
        lastActivityTime = concurrentSecond()
    }

    fun setSuppressPopulationNotice(value: Boolean) {
        suppressPopulationNotice = value
    }

    fun setCurrentMapName(value: String) {
        val normalizedMapName = value.trim().ifBlank { "RELAY" }
        if (!currentMapName.equals(normalizedMapName, ignoreCase = true)) {
            manualMaxPlayerOverride = false
        }
        currentMapName = normalizedMapName
        touchActivity()
        UpListMain.requestRefresh(force = true)
    }

    fun setCustomRoomSettings(maxPlayerSize: Int, maxUnitSize: Int = currentMaxUnitSize, income: Float = currentIncome, manualOverride: Boolean = false) {
        currentMaxPlayerSize = maxPlayerSize.coerceIn(1, 100)
        currentMaxUnitSize = maxUnitSize.coerceAtLeast(1)
        currentIncome = income.takeIf { it > 0f } ?: 1f
        manualMaxPlayerOverride = manualOverride
        touchActivity()
        UpListMain.requestRefresh(force = true)
    }

    fun updateMinSize() {
        try {
            if (abstractNetConnectIntMap.size < 1) {
                return
            }

            val time = Time.concurrentMillis()
            ObjectIntMap<Int>().also { map ->
                abstractNetConnectIntMap.forEach {
                    map[(time - it.value.lastReceivedTime).toInt()] = it.key
                }
            }.let {
                minSize = it[it.toArrayKey().toIntArray().min()]!!
            }
        } catch (e: Exception) {
            Log.error("[RELAY updateMinSize]", e)
        }
    }

    fun re() {
        removeRoom()
    }

    fun removeRoom(run: () -> Unit = {}) {
        val firstClose = !closeRoom
        try {
            groupNet.disconnect()
            admin?.disconnect()
            abstractNetConnectIntMap.values.forEach { it.disconnect() }
            closeRoom = true
            abstractNetConnectIntMap.clear()
            relayPlayersData.clear()
            relayKickData.clear()
            banUnit.clear()
            site.set(0)
            admin = null
            isStartGame = false
            publicRoom = false
            publicPriority = 0
            customRoomProfile = null
            currentMapName = "RELAY"
            currentMaxPlayerSize = 50
            currentMaxUnitSize = 200
            currentIncome = 1f
            manualMaxPlayerOverride = false
            UpListMain.requestRefresh(force = true)
        } catch (e: Exception) {
            // Ignore
        } finally {
            if (firstClose) {
                recordRoomClosed()
            }
            serverRelayDataRoom.remove(internalID)
            run()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val relayRoom = other as RelayRoom
        return internalID == coverRelayID(relayRoom.id)
    }

    override fun hashCode(): Int {
        return Objects.hash(id)
    }

    companion object {
        val serverRelayOld = ObjectMap<String, String>()
        val serverRelayIpData = Seq<String>(true)

        private val serverRelayDataRoom = IntMap<RelayRoom>(128, true)
        private val rand = Rand()
        private val totalRoomCreated = AtomicInteger()
        private val totalRoomClosed = AtomicInteger()
        private val totalJoinConnections = AtomicInteger()
        private val totalDisconnectedConnections = AtomicInteger()
        private val peakOnlineConnections = AtomicInteger()

        @JvmStatic
        val relayAllIP: String
            get() {
                val str = StringBuilder(10)
                serverRelayDataRoom.values.forEach(Consumer { e: RelayRoom ->
                    str.append(LINE_SEPARATOR).append(e.id).append(e.allIP)
                })
                return str.toString()
            }

        val allSize: Int
            get() {
                val size = AtomicInteger()
                serverRelayDataRoom.values.forEach(Consumer { e: RelayRoom -> size.getAndAdd(e.getSize()) })
                return size.get()
            }
        val roomAllSize: Int
            get() {
                return serverRelayDataRoom.size
            }
        val roomNoStartSize: Int
            get() {
                val size = AtomicInteger()
                serverRelayDataRoom.values.forEach(Consumer { e: RelayRoom -> if (!e.isStartGame) size.incrementAndGet() })
                return size.get()
            }
        val roomPublicSize: Int
            get() {
                val size = AtomicInteger()
                serverRelayDataRoom.values.forEach(Consumer { e: RelayRoom ->
                    if (e.publicRoom && !e.isStartGame && !e.closeRoom) {
                        size.incrementAndGet()
                    }
                })
                return size.get()
            }
        val totalRoomCreatedSize: Int
            get() = totalRoomCreated.get()
        val totalRoomClosedSize: Int
            get() = totalRoomClosed.get()
        val totalJoinConnectionSize: Int
            get() = totalJoinConnections.get()
        val totalDisconnectedConnectionSize: Int
            get() = totalDisconnectedConnections.get()
        val peakOnlineConnectionSize: Int
            get() = peakOnlineConnections.get()

        data class PublicRoomSnapshot(
            val roomId: String,
            val playerSize: Int,
            val started: Boolean,
            val customRoom: Boolean,
            val publicPriority: Int,
            val publicTopTime: Int,
            val lastActivityTime: Int,
            val hostName: String,
            val hostIp: String,
            val mapName: String,
            val isMod: Boolean,
        )

        data class RoomSnapshot(
            val roomId: String,
            val playerSize: Int,
            val activeConnectionSize: Int,
            val started: Boolean,
            val publicRoom: Boolean,
            val closeRoom: Boolean,
            val customRoom: Boolean,
            val publicPriority: Int,
            val publicTopTime: Int,
            val lastActivityTime: Int,
            val roomCreateTime: Int,
            val isMod: Boolean,
            val hostName: String,
            val hostIp: String,
            val mapName: String,
        )

        @get:Synchronized
        internal val randPow: NetConnectProofOfWork get() = NetConnectProofOfWork()

        @JvmStatic
        fun getRelay(id: String): RelayRoom? = serverRelayDataRoom[coverRelayID(id)]

        @JvmStatic
        fun getCheckRelay(id: String): Boolean = serverRelayDataRoom.containsKey(coverRelayID(id))

        @JvmStatic
        fun forEachActiveRoom(action: (RelayRoom) -> Unit) {
            serverRelayDataRoom.values.forEach(action)
        }

        @JvmStatic
        fun sendAllMsg(msg: String) {
            Threads.newThreadOther {
                serverRelayDataRoom.values.forEach(Consumer { e: RelayRoom -> e.sendMsg(msg) })
            }
        }

        @JvmStatic
        fun getAllRelayIpCountry(): Map<String, Int> {
            return mutableMapOf<String, Int>().also {
                mutableMapOf<String, AtomicInteger>().also { temp ->
                    serverRelayDataRoom.values.forEach { relay ->
                        relay.admin?.let { admin ->
                            temp.computeIfAbsent(admin.ipCountry) { AtomicInteger(0) }.incrementAndGet()
                        }
                        relay.abstractNetConnectIntMap.values.forEach { connect ->
                            temp.computeIfAbsent(connect.ipCountry) { AtomicInteger(0) }.incrementAndGet()
                        }
                    }
                }.forEach { (country, count) ->
                    it[country] = count.get()
                }
            }
        }

        @JvmStatic
        fun getAllRelayVersion(): Map<Int, Int> {
            return mutableMapOf<Int, Int>().also {
                mutableMapOf<Int, AtomicInteger>().also { temp ->
                    serverRelayDataRoom.values.forEach { relay ->
                        relay.admin?.let { admin ->
                            temp.computeIfAbsent(admin.clientVersion) { AtomicInteger(0) }.incrementAndGet()
                        }
                        relay.abstractNetConnectIntMap.values.forEach { connect ->
                            temp.computeIfAbsent(connect.clientVersion) { AtomicInteger(0) }.incrementAndGet()
                        }
                    }
                }.forEach { (version, count) ->
                    it[version] = count.get()
                }
            }
        }

        @JvmStatic
        fun getPublicRoomSnapshots(limit: Int = 20): List<PublicRoomSnapshot> {
            return serverRelayDataRoom.values
                .filter { it.publicRoom && !it.closeRoom }
                .map {
                    PublicRoomSnapshot(
                        roomId = it.id,
                        playerSize = it.getSize(),
                        started = it.isStartGame,
                        customRoom = it.customRoomProfile != null,
                        publicPriority = it.publicPriority,
                        publicTopTime = it.publicTopTime,
                        lastActivityTime = it.lastActivityTime,
                        hostName = it.admin?.name ?: "",
                        hostIp = it.admin?.ip ?: "",
                        mapName = it.currentMapName,
                        isMod = it.isMod
                    )
                }
                .sortedWith(
                    compareByDescending<PublicRoomSnapshot> { it.publicPriority }
                        .thenByDescending { it.publicTopTime }
                        .thenByDescending { it.lastActivityTime }
                        .thenByDescending { it.playerSize }
                )
                .take(limit)
        }

        @JvmStatic
        fun getPublicRoomHostIps(): List<String> {
            return serverRelayDataRoom.values
                .filter { it.publicRoom && !it.closeRoom }
                .mapNotNull { it.admin?.ip?.trim()?.takeIf { ip -> ip.isNotBlank() } }
                .distinct()
        }

        @JvmStatic
        fun getAllRoomSnapshots(limit: Int = Int.MAX_VALUE, includeClosed: Boolean = false): List<RoomSnapshot> {
            return serverRelayDataRoom.values
                .filter { includeClosed || !it.closeRoom }
                .map {
                    RoomSnapshot(
                        roomId = it.id,
                        playerSize = it.getSize(),
                        activeConnectionSize = it.getActiveConnectionSize(),
                        started = it.isStartGame,
                        publicRoom = it.publicRoom,
                        closeRoom = it.closeRoom,
                        customRoom = it.customRoomProfile != null,
                        publicPriority = it.publicPriority,
                        publicTopTime = it.publicTopTime,
                        lastActivityTime = it.lastActivityTime,
                        roomCreateTime = it.roomCreateTime,
                        isMod = it.isMod,
                        hostName = it.admin?.name ?: "",
                        hostIp = it.admin?.ip ?: "",
                        mapName = it.currentMapName
                    )
                }
                .sortedWith(
                    compareByDescending<RoomSnapshot> { it.publicRoom }
                        .thenByDescending { it.publicPriority }
                        .thenByDescending { it.publicTopTime }
                        .thenByDescending { it.lastActivityTime }
                        .thenByDescending { it.playerSize }
                )
                .take(limit.coerceAtLeast(1))
        }

        @JvmStatic
        fun getPreferredPublicRoom(): RelayRoom? {
            return serverRelayDataRoom.values
                .filter { it.publicRoom && !it.closeRoom }
                .sortedWith(
                    compareByDescending<RelayRoom> { it.publicPriority }
                        .thenByDescending { it.publicTopTime }
                        .thenByDescending { it.lastActivityTime }
                        .thenByDescending { it.getSize() }
                )
                .firstOrNull()
        }

        @JvmStatic
        fun findOtherPublicRoomByAdminIp(ip: String, excludeRoomId: String = ""): RelayRoom? {
            val normalizedIp = ip.trim()
            if (normalizedIp.isEmpty()) {
                return null
            }

            val excludedRoomId = excludeRoomId.trim()
            return serverRelayDataRoom.values.firstOrNull {
                it.publicRoom &&
                    !it.closeRoom &&
                    (excludedRoomId.isEmpty() || it.id != excludedRoomId) &&
                    it.admin?.ip?.trim()?.equals(normalizedIp, ignoreCase = true) == true
            }
        }

        internal fun cleanRoom(inactiveTimeoutSeconds: Int = Data.configRelay.decision.relayRoomInactiveTimeoutSeconds) {
            val timeout = inactiveTimeoutSeconds.coerceAtLeast(60)
            val hostOnlyTimeout = Data.configRelay.decision.relayRoomHostOnlyTimeoutSeconds.coerceAtLeast(60)
            val startedTimeout = Data.configRelay.decision.relayRoomStartedInactiveTimeoutSeconds.coerceAtLeast(60)
            val now = concurrentSecond()
            val generalLimit = now - timeout
            val hostOnlyLimit = now - hostOnlyTimeout
            val startedLimit = now - startedTimeout
            val cleanSeq = Seq<RelayRoom>()
            serverRelayDataRoom.eachAll { _, value ->
                val shouldClean = when {
                    value.closeRoom -> true
                    value.getSize() <= 0 -> true
                    value.isStartGame && value.lastActivityTime < startedLimit -> true
                    value.getSize() <= 1 && value.lastActivityTime < hostOnlyLimit -> true
                    value.lastActivityTime < generalLimit -> true
                    else -> false
                }
                if (shouldClean) {
                    cleanSeq.add(value)
                }
            }
            cleanSeq.eachAll(RelayRoom::re)
        }

        internal fun coverRelayID(id: String): Int {
            return if (isNumeric(id) && id.length < 10) {
                id.toInt()
            } else {
                id.hashCode().let {
                    return if (it > 0) {
                        -it
                    } else {
                        it
                    }
                }
            }
        }

        private fun recordRoomCreated() {
            totalRoomCreated.incrementAndGet()
        }

        private fun recordRoomClosed() {
            totalRoomClosed.incrementAndGet()
        }

        private fun recordJoinConnection() {
            totalJoinConnections.incrementAndGet()
            updatePeakOnline(allSize)
        }

        private fun recordDisconnectConnection() {
            totalDisconnectedConnections.incrementAndGet()
        }

        private fun updatePeakOnline(currentOnline: Int) {
            while (true) {
                val oldValue = peakOnlineConnections.get()
                if (currentOnline <= oldValue) {
                    return
                }
                if (peakOnlineConnections.compareAndSet(oldValue, currentOnline)) {
                    return
                }
            }
        }

        /**
         * This method should not be open to the public for internal use only
         * @param id                Custom ID left blank self generated
         * @param playerName        Player name data
         * @param isMod             Whether mods room
         * @param betaGameVersion   Is it a beta version
         * @param maxPlayer         Maximum number of people
         * @return Relay
         */
        @Synchronized
        internal fun getRelay(
            id: String = "", playerName: String, isMod: Boolean, betaGameVersion: Boolean, version: Int, maxPlayer: Int
        ): RelayRoom {
                var idRelay = id
            val relayRoom: RelayRoom = if (id.isBlank()) {
                val digits = RelayDeploymentSupport.shareRoomDigits().coerceIn(2, 6)
                val min = Math.pow(10.0, (digits - 1).toDouble()).toInt()
                val max = Math.pow(10.0, digits.toDouble()).toInt()
                var attempts = 0
                while (attempts < 200) {
                    val intId = rand.random(min, max)
                    if (!getCheckRelay(intId.toString()) && !RelayCustomRoomSupport.isReservedRoomId(intId.toString())) {
                        idRelay = intId.toString()
                        debug(intId)
                        break
                    }
                    attempts++
                }
                RelayRoom(coverRelayID(idRelay), idRelay, playerName, isMod, betaGameVersion, version, maxPlayer)
            } else {
                RelayRoom(coverRelayID(idRelay), idRelay, playerName, isMod, betaGameVersion, version, maxPlayer)
            }
            recordRoomCreated()
            return relayRoom
        }
    }
}
