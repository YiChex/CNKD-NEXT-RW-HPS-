/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.net

import net.rwhps.server.core.thread.Threads.addSavePool
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.plugin.internal.headless.support.StartModePersistentAccessSupport
import net.rwhps.server.plugin.internal.headless.support.StartModeRoomPermissionSupport
import net.rwhps.server.util.file.plugin.PluginData
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.Time.getTimeSinceMillis
import net.rwhps.server.util.Time.millis

/**
 * Server Management (Player)
 *
 * @author Dr (dr@der.kim)
 */
class Administration(pluginData: PluginData) {
    private val chatFilters = Seq<ChatFilter>()

    //@JvmField
    val bannedIPs: Seq<String>

    @JvmField
    val bannedIP24: Seq<String>
    val bannedUUIDs: Seq<String>
    val whitelist: Seq<String>
    val banQQ: Seq<Long>
    val playerDataCache = ObjectMap<String, PlayerInfo>()
    val playerAdminData: ObjectMap<String, PlayerAdminInfo>
    val persistentManagedAdminUUIDs: Seq<String>
    var persistentManagedAdminOwnershipInitialized: Boolean

    init {
        addChatFilter(object: ChatFilter {
            override fun filter(player: PlayerHess, message: String?): String? {
                if (!StartModeRoomPermissionSupport.hasEffectiveAdminAuthority(player)) {
                    //防止玩家在 30 秒内两次发送相同的消息
                    if (message == player.lastSentMessage && getTimeSinceMillis(player.lastMessageTime) < 1000 * 30) {
                        player.sendSystemMessage("You may not send the same message twice.")
                        return null
                    }
                    player.lastSentMessage = message
                    player.lastMessageTime = millis()
                }
                return message
            }
        })
        bannedIPs = pluginData.get("bannedIPs") { Seq() }
        bannedIP24 = pluginData.get("bannedIP24") { Seq() }
        bannedUUIDs = pluginData.get("bannedUUIDs") { Seq() }
        whitelist = pluginData.get("whitelist") { Seq() }
        banQQ = pluginData.get("banQQ") { Seq() }
        playerAdminData = pluginData.get("playerAdminData") { ObjectMap() }
        persistentManagedAdminUUIDs = pluginData.get("persistentManagedAdminUUIDs") { Seq() }
        persistentManagedAdminOwnershipInitialized =
            pluginData.get("persistentManagedAdminOwnershipInitialized") { false }
        addSavePool {
            pluginData.set("bannedIPs", bannedIPs)
            pluginData.set("bannedIP24", bannedIP24)
            pluginData.set("bannedUUIDs", bannedUUIDs)
            pluginData.set("whitelist", whitelist)
            pluginData.set("playerAdminData", playerAdminData)
            pluginData.set("persistentManagedAdminUUIDs", persistentManagedAdminUUIDs)
            pluginData.set(
                "persistentManagedAdminOwnershipInitialized",
                persistentManagedAdminOwnershipInitialized
            )
        }
        addSavePool {
            Data.config.save()
            Data.configServer.save()
            runCatching { Data.configPersistentAccess.save() }
            runCatching { Data.configRoomPermission.save() }
            Data.configRelay.save()
            Data.configRelayApi.save()
            Data.configRelayRoomCode.save()
            Data.configRelayTextAudit.save()
        }
    }

    /**
     * 添加聊天过滤器。这将改变每个玩家的聊天消息
     * 此功能可用于实现过滤器和特殊命令之类的功能
     * 请注意，未过滤命令
     */
    fun addChatFilter(filter: ChatFilter) {
        chatFilters.add(filter)
    }

    /**
     * 移除聊天过滤器，防止 Event 热加载/卸载时泄漏过滤器引用。
     */
    fun removeChatFilter(filter: ChatFilter) {
        chatFilters.remove(filter)
    }

    /** 过滤掉聊天消息  */
    fun filterMessage(player: PlayerHess, message: String?): String? {
        var current = message
        for (f in chatFilters) {
            current = f.filter(player, current)
            if (current == null) {
                return null
            }
        }
        return current
    }

    fun addAdmin(uuid: String, supAdmin: Boolean, persist: Boolean = true) {
        playerAdminData.put(uuid, PlayerAdminInfo(uuid, true, supAdmin))
        if (persist) {
            removePersistentManagedUuid(uuid)
            if (StartModePersistentAccessSupport.recordAdmin(uuid, supAdmin)) {
                persistentManagedAdminUUIDs.add(uuid.trim().uppercase())
            }
        }
    }

    fun removeAdmin(uuid: String, persist: Boolean = true) {
        playerAdminData.remove(uuid)
        removePersistentManagedUuid(uuid)
        if (persist) {
            StartModePersistentAccessSupport.removeAdmin(uuid)
        }
    }

    private fun removePersistentManagedUuid(uuid: String) {
        persistentManagedAdminUUIDs.toList()
            .filter { it.equals(uuid.trim(), ignoreCase = true) }
            .forEach { persistentManagedAdminUUIDs.remove(it) }
    }

    fun banUuid(uuid: String): Boolean {
        val normalizedUuid = uuid.trim().uppercase()
        if (normalizedUuid.isEmpty()) {
            return false
        }
        if (!bannedUUIDs.contains(normalizedUuid)) {
            bannedUUIDs.add(normalizedUuid)
        }
        StartModePersistentAccessSupport.recordBanUuid(normalizedUuid)
        return true
    }

    fun unbanUuid(uuid: String): Boolean {
        val normalizedUuid = uuid.trim().uppercase()
        val removed = bannedUUIDs.remove(normalizedUuid)
        StartModePersistentAccessSupport.removeBanUuid(uuid)
        return removed
    }

    fun isUuidBanned(uuid: String): Boolean {
        return bannedUUIDs.contains(uuid.trim().uppercase())
    }

    fun banIp(ip: String): Boolean {
        val normalizedIp = ip.trim()
        if (normalizedIp.isEmpty()) {
            return false
        }
        if (!bannedIPs.contains(normalizedIp)) {
            bannedIPs.add(normalizedIp)
        }
        StartModePersistentAccessSupport.recordBanIp(normalizedIp)
        return true
    }

    fun unbanIp(ip: String): Boolean {
        val removed = bannedIPs.remove(ip.trim())
        StartModePersistentAccessSupport.removeBanIp(ip)
        return removed
    }

    fun banIp24(ip24: String): Boolean {
        val normalizedIp24 = ip24.trim()
        if (normalizedIp24.isEmpty()) {
            return false
        }
        if (!bannedIP24.contains(normalizedIp24)) {
            bannedIP24.add(normalizedIp24)
        }
        StartModePersistentAccessSupport.recordBanIp24(normalizedIp24)
        return true
    }

    fun unbanIp24(ip24: String): Boolean {
        val removed = bannedIP24.remove(ip24.trim())
        StartModePersistentAccessSupport.removeBanIp24(ip24)
        return removed
    }

    fun clearBans() {
        bannedIPs.clear()
        bannedIP24.clear()
        bannedUUIDs.clear()
        StartModePersistentAccessSupport.clearBlacklist()
    }

    fun isIpBanned(ip: String, ip24: String = ""): Boolean {
        val normalizedIp = ip.trim()
        val normalizedIp24 = ip24.trim()
        return bannedIPs.contains(normalizedIp) || (normalizedIp24.isNotEmpty() && bannedIP24.contains(normalizedIp24))
    }

    fun isAdmin(player: PlayerHess): Boolean {
        if (playerAdminData.containsKey(player.connectHexID)) {
            playerAdminData[player.connectHexID]!!.name = player.name
            return true
        }
        return false
    }

    interface ChatFilter {
        /**
         * 过滤消息
         * @param player Player
         * @param message Message
         * @return 过滤后的消息 空字符串表示不应发送该消息
         */
        fun filter(player: PlayerHess, message: String?): String?
    }

    class PlayerInfo {
        val uuid: String
        var timesKicked: Long = 0
        var timesJoined: Long = 0
        var timeMute: Long = 0
        var admin = false
        var superAdmin = false

        constructor(uuid: String) {
            this.uuid = uuid
        }

        constructor(uuid: String, admin: Boolean) {
            this.uuid = uuid
            this.admin = admin
        }

        constructor(uuid: String, timesKicked: Long, timeMute: Long) {
            this.uuid = uuid
            this.timesKicked = timesKicked
            this.timeMute = timeMute
        }

        constructor(uuid: String, timesKicked: Long, timeMute: Long, admin: Boolean) {
            this.uuid = uuid
            this.admin = admin
            this.timesKicked = timesKicked
            this.timeMute = timeMute
        }
    }

    class PlayerAdminInfo(val uuid: String, admin: Boolean, superAdmin: Boolean) {
        var name = ""
        var admin = false
        var superAdmin = false

        init {
            this.admin = admin
            this.superAdmin = superAdmin
        }

        override fun toString(): String {
            return "uuid: " + uuid + "admin: " + admin + "supAdmin: " + superAdmin
        }
    }
}
