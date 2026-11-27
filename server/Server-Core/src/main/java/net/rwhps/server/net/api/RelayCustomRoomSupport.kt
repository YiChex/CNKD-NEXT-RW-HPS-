/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * This source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.data.bean.BeanRelayCustomRoomConfig
import net.rwhps.server.data.bean.BeanRelayCustomRoomDefinition
import net.rwhps.server.data.bean.BeanRelayServerModeConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.util.log.Log
import java.util.Locale
import kotlin.random.Random

data class RelayCustomRoomProfile(
    val roomId: String,
    val nodeCode: String,
    val isMod: Boolean,
    val autoPublicRoom: Boolean,
    val forcePublicRoom: Boolean,
    val publicPriority: Int,
    val joinBaseUrl: String,
    val connectHost: String,
    val connectPort: Int,
    val entryNotice: String,
    val roomWelcome: String,
    val roomChatNotice: String,
    val hostChatNotice: String,
    val chatSender: String,
    val maxPlayerSize: Int,
    val maxUnitSize: Int,
    val income: Float,
    val relayServerManaged: Boolean = false,
    val defaultListName: String = "",
    val defaultMapName: String = "",
    val hostClaimNotice: String = ""
)

internal object RelayCustomRoomSupport {
    private val lock = Any()

    @Volatile
    private var config = BeanRelayCustomRoomConfig()

    @Volatile
    private var roomProfiles: Map<String, RelayCustomRoomProfile> = emptyMap()

    @Volatile
    private var relayServerProfiles: Map<String, RelayCustomRoomProfile> = emptyMap()

    @Volatile
    private var lastModified = -1L

    @Volatile
    private var lastCheckedAt = 0L

    @Volatile
    private var initialized = false

    fun init() {
        synchronized(lock) {
            if (initialized) {
                return
            }
            reload(force = true)
            initialized = true
        }
    }

    fun reloadIfNeeded() {
        ensureInitialized()

        val now = System.currentTimeMillis()
        val intervalMillis = config.hotReloadSeconds.coerceIn(1, 60) * 1000L
        if (now - lastCheckedAt < intervalMillis) {
            return
        }
        lastCheckedAt = now

        val modified = BeanRelayCustomRoomConfig.fileUtils.file.lastModified()
        if (modified == lastModified) {
            return
        }

        reload(force = true)
    }

    fun findDefinition(roomId: String): RelayCustomRoomProfile? {
        ensureInitialized()
        if (!RelayLicenseSupport.allowCustomRoom()) {
            return null
        }
        val normalizedId = normalizeRoomId(roomId)
        return roomProfiles[normalizedId] ?: relayServerProfiles[normalizedId]
    }

    fun findAppliedProfile(roomId: String): RelayCustomRoomProfile? {
        ensureInitialized()
        if (!RelayLicenseSupport.allowCustomRoom()) {
            return null
        }
        val normalizedId = normalizeRoomId(roomId)
        return RelayRoom.getRelay(normalizedId)?.customRoomProfile
            ?: roomProfiles[normalizedId]
            ?: relayServerProfiles[normalizedId]
    }

    fun isReservedRoomId(roomId: String): Boolean {
        ensureInitialized()
        if (!RelayLicenseSupport.allowCustomRoom()) {
            return false
        }
        val normalizedId = normalizeRoomId(roomId)
        return roomProfiles.containsKey(normalizedId) || relayServerProfiles.containsKey(normalizedId)
    }

    fun shouldHandleOnCurrentNode(profile: RelayCustomRoomProfile): Boolean {
        if (profile.nodeCode.isBlank()) {
            return true
        }
        return RelayNodeRoutingSupport.shouldHandleOnLocalNode(profile.nodeCode)
    }

    fun resolveRedirectEndpoint(profile: RelayCustomRoomProfile): String? {
        if (profile.nodeCode.isBlank()) {
            return null
        }
        return RelayNodeRoutingSupport.redirectEndpoint(profile.nodeCode)
    }

    fun redirectLookupId(profile: RelayCustomRoomProfile): String {
        return RelayDeploymentSupport.roomLookupId(profile.roomId, profile.nodeCode)
    }

    fun canDisablePublic(room: RelayRoom): Boolean {
        val profile = findAppliedProfile(room.id)
        return profile?.forcePublicRoom != true && profile?.relayServerManaged != true
    }

    fun applyProfile(room: RelayRoom, profile: RelayCustomRoomProfile) {
        room.applyCustomRoomProfile(profile)
    }

    fun isRelayServerModeEnabled(): Boolean {
        ensureInitialized()
        return config.relayServerMode.enable && RelayLicenseSupport.allowRelayServerMode()
    }

    fun relayServerModeConfig(): BeanRelayServerModeConfig {
        ensureInitialized()
        return config.relayServerMode
    }

    fun relayServerModePublicPorts(): List<Int> {
        ensureInitialized()
        return resolveRelayServerModePorts(config.relayServerMode)
    }

    fun relayServerManagedProfiles(): List<RelayCustomRoomProfile> {
        ensureInitialized()
        if (!RelayLicenseSupport.allowRelayServerMode()) {
            return emptyList()
        }
        maintainRelayServerModeRooms()
        return relayServerProfiles.values.sortedWith(compareBy<RelayCustomRoomProfile>({ it.connectPort }, { it.roomId }))
    }

    fun configuredProfiles(): List<RelayCustomRoomProfile> {
        ensureInitialized()
        if (!RelayLicenseSupport.allowCustomRoom()) {
            return emptyList()
        }
        maintainRelayServerModeRooms()
        return (roomProfiles.values + relayServerProfiles.values)
            .distinctBy { it.roomId }
            .sortedBy { it.roomId }
    }

    fun findRelayServerManagedProfileByPort(localPort: Int): RelayCustomRoomProfile? {
        ensureInitialized()
        maintainRelayServerModeRooms()
        return relayServerProfiles.values.firstOrNull { it.connectPort == localPort }
    }

    fun maintainRelayServerModeRooms() {
        ensureInitialized()
        synchronized(lock) {
            relayServerProfiles = buildRelayServerModeProfiles(config.relayServerMode)
        }
    }

    private fun reload(force: Boolean): Boolean {
        return try {
            val loaded = BeanRelayCustomRoomConfig.stringToClass()
            if (BeanRelayCustomRoomConfig.fileUtils.length() == 0L) {
                loaded.save()
            }

            val normalizedProfiles = if (loaded.enable) {
                loaded.rooms.mapNotNull(::normalizeDefinition).associateBy { it.roomId }
            } else {
                emptyMap()
            }

            synchronized(lock) {
                config = loaded
                roomProfiles = normalizedProfiles
                relayServerProfiles = buildRelayServerModeProfiles(loaded.relayServerMode)
                lastModified = BeanRelayCustomRoomConfig.fileUtils.file.lastModified()
            }

            RelayRoom.forEachActiveRoom { room ->
                (normalizedProfiles[normalizeRoomId(room.id)] ?: relayServerProfiles[normalizeRoomId(room.id)])
                    ?.let { room.applyCustomRoomProfile(it) }
            }

            if (force) {
                Log.clog("[RelayCustomRoom] Loaded ${normalizedProfiles.size} custom room definitions")
            }
            true
        } catch (e: Exception) {
            Log.error("[RelayCustomRoom] Reload failed", e)
            false
        }
    }

    private fun normalizeDefinition(definition: BeanRelayCustomRoomDefinition): RelayCustomRoomProfile? {
        if (!definition.enabled) {
            return null
        }

        val nodeCode = definition.nodeCode.trim().uppercase(Locale.ROOT).takeIf { it.length == 1 } ?: ""
        val roomId = normalizeRoomId(definition.roomId, nodeCode)
        if (roomId.isBlank()) {
            return null
        }

        return RelayCustomRoomProfile(
            roomId = roomId,
            nodeCode = nodeCode,
            isMod = definition.isMod,
            autoPublicRoom = definition.autoPublicRoom,
            forcePublicRoom = definition.forcePublicRoom,
            publicPriority = definition.publicPriority.coerceAtLeast(0),
            joinBaseUrl = definition.joinBaseUrl.trim().trimEnd('/'),
            connectHost = definition.connectHost.trim().trimEnd('/'),
            connectPort = definition.connectPort.coerceAtLeast(0),
            entryNotice = definition.entryNotice.trim(),
            roomWelcome = definition.roomWelcome,
            roomChatNotice = definition.roomChatNotice,
            hostChatNotice = definition.hostChatNotice,
            chatSender = definition.chatSender.trim(),
            maxPlayerSize = definition.maxPlayerSize,
            maxUnitSize = definition.maxUnitSize.takeIf { it > 0 } ?: 200,
            income = definition.income.takeIf { it > 0f } ?: 1f
        )
    }

    private fun buildRelayServerModeProfiles(mode: BeanRelayServerModeConfig): Map<String, RelayCustomRoomProfile> {
        if (!mode.enable) {
            return emptyMap()
        }

        val desiredPorts = resolveRelayServerModePorts(mode)
        if (desiredPorts.isEmpty()) {
            return emptyMap()
        }

        val activeProfiles = linkedMapOf<String, RelayCustomRoomProfile>()
        desiredPorts.forEach { publicPort ->
            val previousProfile = relayServerProfiles.values.firstOrNull { it.connectPort == publicPort }
            val previousRoom = previousProfile?.let { RelayRoom.getRelay(it.roomId) }
            val shouldReuse = previousProfile != null && (
                previousRoom == null ||
                    (!previousRoom.closeRoom && previousRoom.publicRoom && !previousRoom.isStartGame)
                )

            val roomId = if (shouldReuse) {
                previousProfile!!.roomId
            } else {
                generateRelayServerModeRoomId(mode, activeProfiles)
            }

            activeProfiles[roomId] = buildRelayServerProfile(mode, roomId, publicPort)
        }
        return activeProfiles
    }

    private fun buildRelayServerProfile(
        mode: BeanRelayServerModeConfig,
        roomId: String,
        publicPort: Int
    ): RelayCustomRoomProfile {
        return RelayCustomRoomProfile(
            roomId = roomId,
            nodeCode = Data.configRelay.localNodeCode.trim().uppercase(Locale.ROOT).takeIf { it.length == 1 } ?: "",
            isMod = mode.isMod,
            autoPublicRoom = true,
            forcePublicRoom = true,
            publicPriority = mode.publicPriority.coerceAtLeast(0),
            joinBaseUrl = mode.joinBaseUrl.trim().trimEnd('/'),
            connectHost = mode.connectHost.trim().trimEnd('/'),
            connectPort = publicPort,
            entryNotice = "",
            roomWelcome = mode.roomWelcome,
            roomChatNotice = mode.roomChatNotice,
            hostChatNotice = mode.hostChatNotice,
            chatSender = mode.chatSender.trim(),
            maxPlayerSize = mode.maxPlayerSize,
            maxUnitSize = mode.maxUnitSize.takeIf { it > 0 } ?: 200,
            income = mode.income.takeIf { it > 0f } ?: 1f,
            relayServerManaged = true,
            defaultListName = mode.defaultListName.trim().ifBlank {
                Data.config.serverName.trim().ifBlank { "CNKD服务器" }
            },
            defaultMapName = mode.defaultMapName.trim().ifBlank { "RELAY-CN 自定义房间" },
            hostClaimNotice = mode.hostClaimNotice.trim()
        )
    }

    private fun resolveRelayServerModePorts(mode: BeanRelayServerModeConfig): List<Int> {
        val desiredCount = mode.roomCount.coerceIn(1, 4)
        val configuredPorts = mode.publicPorts
            .map { it.coerceAtLeast(0) }
            .filter { it > 0 }
            .distinct()
            .take(desiredCount)
        if (configuredPorts.isNotEmpty()) {
            return configuredPorts
        }

        val basePort = mode.connectPort.coerceAtLeast(0)
        if (basePort > 0) {
            return (0 until desiredCount).map { basePort + it }
        }

        return listOf(5201, 5202, 5203, 5204).take(desiredCount)
    }

    private fun generateRelayServerModeRoomId(
        mode: BeanRelayServerModeConfig,
        pendingProfiles: Map<String, RelayCustomRoomProfile>
    ): String {
        val prefix = mode.roomIdPrefix.trim().ifBlank { "RS" }.take(3).uppercase(Locale.ROOT)
        val digits = RelayDeploymentSupport.shareRoomDigits().coerceIn(2, (7 - prefix.length).coerceAtLeast(2))
        repeat(512) {
            val min = Math.pow(10.0, (digits - 1).toDouble()).toInt()
            val max = Math.pow(10.0, digits.toDouble()).toInt()
            val roomId = "$prefix${Random.nextInt(min, max)}"
            if (!roomProfiles.containsKey(roomId) &&
                !pendingProfiles.containsKey(roomId) &&
                !relayServerProfiles.containsKey(roomId) &&
                !RelayRoom.getCheckRelay(roomId)
            ) {
                return roomId
            }
        }
        return "${prefix}${System.currentTimeMillis().toString().takeLast(digits)}"
    }

    private fun normalizeRoomId(roomId: String, nodeCode: String = ""): String {
        var normalized = roomId.replace("\\s".toRegex(), "")
        if (normalized.startsWith("K", ignoreCase = true)) {
            normalized = normalized.substring(1)
        }

        if (RelayDeploymentSupport.hasConfiguredRoomCode(normalized) || RelayDeploymentSupport.parseClusterRoomNodeCode(normalized) != null) {
            normalized = RelayDeploymentSupport.stripRelayLookupPrefix(normalized, nodeCode)
        }

        val mainId = Data.configRelay.mainID.trim()
        if (mainId.isNotBlank() && normalized.startsWith(mainId, ignoreCase = true) && normalized.length > mainId.length) {
            val candidate = normalized.substring(mainId.length)
            if (candidate.all { it.isDigit() } || candidate.startsWith("R", ignoreCase = true)) {
                normalized = candidate
            }
        }

        if (nodeCode.isNotBlank()) {
            val routePrefix = "R${nodeCode.uppercase(Locale.ROOT)}"
            if (normalized.startsWith(routePrefix, ignoreCase = true) && normalized.length > routePrefix.length) {
                normalized = normalized.substring(routePrefix.length)
            }
        }

        return normalized
    }

    private fun ensureInitialized() {
        if (!initialized) {
            init()
        }
    }
}
