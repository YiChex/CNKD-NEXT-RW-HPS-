/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanPersistentAccessAdminEntry
import net.rwhps.server.data.bean.BeanPersistentAccessConfig
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.log.Log
import java.util.LinkedHashMap
import java.util.Locale

internal object StartModePersistentAccessSupport {
    private const val ROLE_HOST = "host"
    private const val ROLE_ADMIN = "admin"
    private const val ROLE_SUPER_ADMIN = "super_admin"
    private const val PERSISTENT_AUTHORITY_MARKER = "CNKD.PersistentAuthorityManaged"

    @Volatile
    private var lastOnlineAuthorityChanged = 0

    @Volatile
    private var lastOnlineAuthorityGranted = 0

    @Volatile
    private var lastOnlineAuthorityRevoked = 0

    fun applyConfiguredAccessToRuntime(migrateRuntime: Boolean = false) {
        val initialConfig = configOrNull() ?: return
        val previousManagedAdminUuids = runCatching {
            if (Data.core.admin.persistentManagedAdminOwnershipInitialized) {
                normalizeUuidList(Data.core.admin.persistentManagedAdminUUIDs.toList()).toSet()
            } else if (initialConfig.enable && initialConfig.persistentAdminEnabled) {
                // Before this ownership marker existed, playerAdminData was wholly replaced
                // by the persistent config loader. Treat that legacy state as managed once.
                Data.core.admin.playerAdminData.keys.map(::normalizeUuid).toSet()
            } else {
                emptySet()
            }
        }.getOrDefault(emptySet())

        val config = if (
            initialConfig.enable &&
            migrateRuntime &&
            initialConfig.persistRuntimeChanges &&
            !initialConfig.legacySettingsMigrationDone
        ) {
            mergeRuntimeIntoConfig(initialConfig)
                .copy(legacySettingsMigrationDone = true)
                .also { writeConfig(it) }
        } else {
            initialConfig
        }

        if (config.enable && config.persistentBlacklistEnabled) {
            replaceSeq(Data.core.admin.bannedUUIDs, normalizeUuidList(config.bannedUUIDs))
            replaceSeq(Data.core.admin.bannedIPs, normalizeExactList(config.bannedIPs))
            replaceSeq(Data.core.admin.bannedIP24, normalizeExactList(config.bannedIP24))
        }

        val configuredAdminEntries = if (config.enable && config.persistentAdminEnabled) {
            configuredAdmins(config)
        } else {
            emptyList()
        }
        previousManagedAdminUuids.forEach(::removeRuntimeAdminEntry)
        configuredAdminEntries.forEach { entry ->
            Data.core.admin.addAdmin(entry.uuid, entry.superAdmin, persist = false)
            if (entry.name.isNotBlank()) {
                Data.core.admin.playerAdminData[entry.uuid]?.name = entry.name
            }
        }
        replaceSeq(
            Data.core.admin.persistentManagedAdminUUIDs,
            configuredAdminEntries.map { it.uuid }
        )
        Data.core.admin.persistentManagedAdminOwnershipInitialized = true

        val onlineSync = synchronizeRuntimePlayers(configuredAdminEntries, previousManagedAdminUuids)
        lastOnlineAuthorityChanged = onlineSync.changed
        lastOnlineAuthorityGranted = onlineSync.granted
        lastOnlineAuthorityRevoked = onlineSync.revoked
        val authorityStateChanged = StartModeRoomPermissionSupport.reconcileOnlineAuthorityState(
            reason = "persistent_access_reload"
        )
        if (onlineSync.changed > 0 || authorityStateChanged) {
            StartModeRoomPermissionSupport.refreshOnlineAuthorityView("persistent_access_reload")
        }

        Log.clog(
            "[PersistentAccess] Loaded persistent access config: bannedUUIDs=${Data.core.admin.bannedUUIDs.size}, " +
                "bannedIPs=${Data.core.admin.bannedIPs.size}, bannedIP24=${Data.core.admin.bannedIP24.size}, " +
                "admins=${Data.core.admin.playerAdminData.size}, onlineChanged=${onlineSync.changed}, " +
                "onlineGranted=${onlineSync.granted}, onlineRevoked=${onlineSync.revoked}"
        )
    }

    fun applyPlayerAccess(player: PlayerHess) {
        val config = configOrNull() ?: return
        val entry = if (config.enable && config.persistentAdminEnabled) {
            configuredAdmins(config).firstOrNull { sameUuid(it.uuid, player.connectHexID) }
        } else {
            null
        }
        val previouslyManaged = player.getData(PERSISTENT_AUTHORITY_MARKER, false)
        val decision = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = player.isAdmin,
            currentAutoAdmin = player.autoAdmin,
            currentSuperAdmin = player.superAdmin,
            previouslyManaged = previouslyManaged,
            configured = entry != null,
            ordinaryAdminAuthorityEnabled = Data.configServer.oneAdmin,
            configuredHost = entry?.role == ROLE_HOST,
            configuredSuperAdmin = entry?.superAdmin == true
        )
        applyAuthorityDecision(player, decision)

        if (entry != null) {
            Data.core.admin.addAdmin(player.connectHexID, entry.superAdmin, persist = false)
            Data.core.admin.playerAdminData[player.connectHexID]?.name = player.name
            addPersistentManagedUuid(player.connectHexID)
        } else if (previouslyManaged) {
            Data.core.admin.removeAdmin(player.connectHexID, persist = false)
        }
    }

    fun reclassifyRuntimeAdminCommand(player: PlayerHess, granted: Boolean) {
        player.removeData(PERSISTENT_AUTHORITY_MARKER)
        removePersistentManagedUuid(player.connectHexID)
        if (!granted) {
            return
        }
        val config = configOrNull() ?: return
        if (
            config.enable &&
            config.persistentAdminEnabled &&
            configuredAdmins(config).any { sameUuid(it.uuid, player.connectHexID) }
        ) {
            player.addData(PERSISTENT_AUTHORITY_MARKER, true)
            addPersistentManagedUuid(player.connectHexID)
        }
    }

    fun recordAdmin(uuid: String, superAdmin: Boolean): Boolean {
        var recorded = false
        updateIfRuntimePersistenceEnabled { config ->
            if (!config.persistentAdminEnabled) {
                return@updateIfRuntimePersistenceEnabled config
            }
            val normalized = normalizeUuid(uuid)
            if (normalized.isBlank()) {
                return@updateIfRuntimePersistenceEnabled config
            }
            recorded = true
            val without = removeAdminUuid(config, normalized)
            if (superAdmin) {
                without.copy(superAdminUUIDs = appendDistinctUuid(without.superAdminUUIDs, normalized))
            } else {
                without.copy(adminUUIDs = appendDistinctUuid(without.adminUUIDs, normalized))
            }
        }
        return recorded
    }

    fun removeAdmin(uuid: String) {
        updateIfRuntimePersistenceEnabled {
            if (it.persistentAdminEnabled) {
                removeAdminUuid(it, normalizeUuid(uuid))
            } else {
                it
            }
        }
    }

    fun recordBanUuid(uuid: String) {
        updateIfRuntimePersistenceEnabled { config ->
            if (!config.persistentBlacklistEnabled) {
                return@updateIfRuntimePersistenceEnabled config
            }
            config.copy(bannedUUIDs = appendDistinctUuid(config.bannedUUIDs, normalizeUuid(uuid)))
        }
    }

    fun removeBanUuid(uuid: String) {
        updateIfRuntimePersistenceEnabled { config ->
            if (config.persistentBlacklistEnabled) {
                config.copy(bannedUUIDs = removeMatchingUuid(config.bannedUUIDs, normalizeUuid(uuid)))
            } else {
                config
            }
        }
    }

    fun recordBanIp(ip: String) {
        updateIfRuntimePersistenceEnabled { config ->
            if (!config.persistentBlacklistEnabled) {
                return@updateIfRuntimePersistenceEnabled config
            }
            config.copy(bannedIPs = appendDistinctExact(config.bannedIPs, ip.trim()))
        }
    }

    fun removeBanIp(ip: String) {
        updateIfRuntimePersistenceEnabled { config ->
            if (config.persistentBlacklistEnabled) {
                config.copy(bannedIPs = removeMatchingExact(config.bannedIPs, ip.trim()))
            } else {
                config
            }
        }
    }

    fun recordBanIp24(ip24: String) {
        updateIfRuntimePersistenceEnabled { config ->
            if (!config.persistentBlacklistEnabled) {
                return@updateIfRuntimePersistenceEnabled config
            }
            config.copy(bannedIP24 = appendDistinctExact(config.bannedIP24, ip24.trim()))
        }
    }

    fun removeBanIp24(ip24: String) {
        updateIfRuntimePersistenceEnabled { config ->
            if (config.persistentBlacklistEnabled) {
                config.copy(bannedIP24 = removeMatchingExact(config.bannedIP24, ip24.trim()))
            } else {
                config
            }
        }
    }

    fun clearBlacklist() {
        updateIfRuntimePersistenceEnabled {
            if (it.persistentBlacklistEnabled) {
                it.copy(bannedUUIDs = emptyList(), bannedIPs = emptyList(), bannedIP24 = emptyList())
            } else {
                it
            }
        }
    }

    fun buildDiagnostics(): List<String> {
        val config = configOrNull() ?: BeanPersistentAccessConfig()
        val admins = configuredAdmins(config)
        return listOf(
            "========== PERSISTENT ACCESS ==========",
            "enabled=${config.enable}",
            "persistRuntimeChanges=${config.persistRuntimeChanges}",
            "legacySettingsMigrationDone=${config.legacySettingsMigrationDone}",
            "persistentBlacklistEnabled=${config.persistentBlacklistEnabled}",
            "persistentAdminEnabled=${config.persistentAdminEnabled}",
            "config.bannedUUIDs=${normalizeUuidList(config.bannedUUIDs).size}",
            "config.bannedIPs=${normalizeExactList(config.bannedIPs).size}",
            "config.bannedIP24=${normalizeExactList(config.bannedIP24).size}",
            "config.admins=${admins.size}",
            "runtime.bannedUUIDs=${runCatching { Data.core.admin.bannedUUIDs.size }.getOrDefault(0)}",
            "runtime.bannedIPs=${runCatching { Data.core.admin.bannedIPs.size }.getOrDefault(0)}",
            "runtime.bannedIP24=${runCatching { Data.core.admin.bannedIP24.size }.getOrDefault(0)}",
            "runtime.admins=${runCatching { Data.core.admin.playerAdminData.size }.getOrDefault(0)}",
            "runtime.persistentManagedAdmins=${runCatching { Data.core.admin.persistentManagedAdminUUIDs.size }.getOrDefault(0)}",
            "runtime.persistentOwnershipInitialized=${runCatching { Data.core.admin.persistentManagedAdminOwnershipInitialized }.getOrDefault(false)}",
            "runtime.onlineAuthorityChanged=$lastOnlineAuthorityChanged",
            "runtime.onlineAuthorityGranted=$lastOnlineAuthorityGranted",
            "runtime.onlineAuthorityRevoked=$lastOnlineAuthorityRevoked",
            "admin.roles=${admins.joinToString(",") { "${it.uuid}:${it.role}" }.ifBlank { "<none>" }}"
        )
    }

    private fun synchronizeRuntimePlayers(
        configuredEntries: List<RuntimeAdminEntry>,
        previousManagedAdminUuids: Set<String>
    ): OnlineAuthoritySyncResult {
        if (!HeadlessModuleManage.initHPS()) {
            return OnlineAuthoritySyncResult()
        }
        val configuredByUuid = configuredEntries.associateBy { normalizeUuid(it.uuid) }
        var changed = 0
        var granted = 0
        var revoked = 0
        HeadlessModuleManage.hps.room.playerManage.playerAll.eachAll { player ->
            if (player.isAi) {
                return@eachAll
            }
            val normalizedUuid = normalizeUuid(player.connectHexID)
            val entry = configuredByUuid[normalizedUuid]
            val previouslyManaged = player.getData(PERSISTENT_AUTHORITY_MARKER, false) ||
                normalizedUuid in previousManagedAdminUuids
            val decision = StartModePersistentAuthorityPolicy.reconcile(
                currentIsAdmin = player.isAdmin,
                currentAutoAdmin = player.autoAdmin,
                currentSuperAdmin = player.superAdmin,
                previouslyManaged = previouslyManaged,
                configured = entry != null,
                ordinaryAdminAuthorityEnabled = Data.configServer.oneAdmin,
                configuredHost = entry?.role == ROLE_HOST,
                configuredSuperAdmin = entry?.superAdmin == true
            )
            if (applyAuthorityDecision(player, decision)) {
                changed++
                if (entry != null && !previouslyManaged) {
                    granted++
                } else if (entry == null && previouslyManaged) {
                    revoked++
                }
            }
        }
        return OnlineAuthoritySyncResult(changed, granted, revoked)
    }

    private fun applyAuthorityDecision(
        player: PlayerHess,
        decision: StartModePersistentAuthorityPolicy.State
    ): Boolean {
        val changed = player.isAdmin != decision.isAdmin ||
            player.autoAdmin != decision.autoAdmin ||
            player.superAdmin != decision.superAdmin ||
            player.getData(PERSISTENT_AUTHORITY_MARKER, false) != decision.managedByPersistentAccess
        player.isAdmin = decision.isAdmin
        player.autoAdmin = decision.autoAdmin
        player.superAdmin = decision.superAdmin
        if (decision.managedByPersistentAccess) {
            player.addData(PERSISTENT_AUTHORITY_MARKER, true)
        } else {
            player.removeData(PERSISTENT_AUTHORITY_MARKER)
        }
        return changed
    }

    private fun mergeRuntimeIntoConfig(config: BeanPersistentAccessConfig): BeanPersistentAccessConfig {
        val runtimeAdmins = Data.core.admin.playerAdminData.values.map {
            BeanPersistentAccessAdminEntry(
                uuid = normalizeUuid(it.uuid),
                name = it.name,
                role = if (it.superAdmin) ROLE_SUPER_ADMIN else ROLE_ADMIN,
                superAdmin = it.superAdmin
            )
        }
        val mergedAdmins = configuredAdmins(config).map {
            BeanPersistentAccessAdminEntry(
                uuid = it.uuid,
                name = it.name,
                role = it.role,
                superAdmin = it.superAdmin
            )
        } + runtimeAdmins

        return config.copy(
            bannedUUIDs = mergeUuidLists(config.bannedUUIDs, Data.core.admin.bannedUUIDs.toList()),
            bannedIPs = mergeExactLists(config.bannedIPs, Data.core.admin.bannedIPs.toList()),
            bannedIP24 = mergeExactLists(config.bannedIP24, Data.core.admin.bannedIP24.toList()),
            admins = normalizeAdminEntries(mergedAdmins)
        )
    }

    private fun configuredAdmins(config: BeanPersistentAccessConfig): List<RuntimeAdminEntry> {
        val entries = LinkedHashMap<String, RuntimeAdminEntry>()

        fun put(uuid: String, role: String, superAdmin: Boolean, name: String = "") {
            val normalizedUuid = normalizeUuid(uuid)
            if (normalizedUuid.isBlank()) {
                return
            }
            val normalizedRole = normalizeRole(role)
            val current = entries[normalizedUuid]
            val next = RuntimeAdminEntry(
                uuid = normalizedUuid,
                role = normalizedRole,
                superAdmin = superAdmin || normalizedRole == ROLE_HOST || normalizedRole == ROLE_SUPER_ADMIN,
                name = name.trim()
            )
            if (current == null || rolePriority(next.role) >= rolePriority(current.role)) {
                entries[normalizedUuid] = next
            }
        }

        config.adminUUIDs.forEach { put(it, ROLE_ADMIN, superAdmin = false) }
        config.superAdminUUIDs.forEach { put(it, ROLE_SUPER_ADMIN, superAdmin = true) }
        config.autoHostUUIDs.forEach { put(it, ROLE_HOST, superAdmin = true) }
        config.admins.forEach { put(it.uuid, it.role, it.superAdmin, it.name) }

        return entries.values.toList()
    }

    private fun normalizeAdminEntries(entries: List<BeanPersistentAccessAdminEntry>): List<BeanPersistentAccessAdminEntry> {
        return entries
            .mapNotNull {
                val uuid = normalizeUuid(it.uuid)
                if (uuid.isBlank()) {
                    null
                } else {
                    val role = normalizeRole(it.role)
                    it.copy(
                        uuid = uuid,
                        role = role,
                        superAdmin = it.superAdmin || role == ROLE_HOST || role == ROLE_SUPER_ADMIN,
                        name = it.name.trim()
                    )
                }
            }
            .distinctBy { it.uuid }
    }

    private fun removeAdminUuid(config: BeanPersistentAccessConfig, normalizedUuid: String): BeanPersistentAccessConfig {
        if (normalizedUuid.isBlank()) {
            return config
        }
        return config.copy(
            autoHostUUIDs = removeMatchingUuid(config.autoHostUUIDs, normalizedUuid),
            adminUUIDs = removeMatchingUuid(config.adminUUIDs, normalizedUuid),
            superAdminUUIDs = removeMatchingUuid(config.superAdminUUIDs, normalizedUuid),
            admins = config.admins.filterNot { sameUuid(it.uuid, normalizedUuid) }
        )
    }

    private fun updateIfRuntimePersistenceEnabled(update: (BeanPersistentAccessConfig) -> BeanPersistentAccessConfig) {
        val config = configOrNull() ?: return
        if (!config.enable || !config.persistRuntimeChanges) {
            return
        }
        writeConfig(update(config))
    }

    private fun writeConfig(config: BeanPersistentAccessConfig) {
        val normalized = config.copy(
            legacySettingsMigrationDone = config.legacySettingsMigrationDone,
            bannedUUIDs = normalizeUuidList(config.bannedUUIDs),
            bannedIPs = normalizeExactList(config.bannedIPs),
            bannedIP24 = normalizeExactList(config.bannedIP24),
            autoHostUUIDs = normalizeUuidList(config.autoHostUUIDs),
            adminUUIDs = normalizeUuidList(config.adminUUIDs),
            superAdminUUIDs = normalizeUuidList(config.superAdminUUIDs),
            admins = normalizeAdminEntries(config.admins)
        )
        Data.configPersistentAccess = normalized
        normalized.save()
    }

    private fun configOrNull(): BeanPersistentAccessConfig? {
        return runCatching { Data.configPersistentAccess }.getOrNull()
    }

    private fun replaceSeq(seq: Seq<String>, values: List<String>) {
        seq.clear()
        values.forEach {
            if (!seq.contains(it)) {
                seq.add(it)
            }
        }
    }

    private fun removeRuntimeAdminEntry(normalizedUuid: String) {
        val matchingKeys = Data.core.admin.playerAdminData.keys
            .filter { sameUuid(it, normalizedUuid) }
            .toList()
        matchingKeys.forEach { Data.core.admin.playerAdminData.remove(it) }
    }

    private fun addPersistentManagedUuid(uuid: String) {
        val normalizedUuid = normalizeUuid(uuid)
        if (normalizedUuid.isBlank()) {
            return
        }
        val managed = Data.core.admin.persistentManagedAdminUUIDs
        if (!managed.contains(normalizedUuid)) {
            managed.add(normalizedUuid)
        }
    }

    private fun removePersistentManagedUuid(uuid: String) {
        Data.core.admin.persistentManagedAdminUUIDs.toList()
            .filter { sameUuid(it, uuid) }
            .forEach { Data.core.admin.persistentManagedAdminUUIDs.remove(it) }
    }

    private fun normalizeUuid(value: String): String {
        return value.trim().uppercase(Locale.US)
    }

    private fun sameUuid(left: String, right: String): Boolean {
        return normalizeUuid(left) == normalizeUuid(right)
    }

    private fun normalizeRole(role: String): String {
        return when (role.trim().lowercase(Locale.US).replace("-", "_")) {
            ROLE_HOST, "owner", "room_owner", "room_host" -> ROLE_HOST
            ROLE_SUPER_ADMIN, "superadmin", "super" -> ROLE_SUPER_ADMIN
            else -> ROLE_ADMIN
        }
    }

    private fun rolePriority(role: String): Int {
        return when (normalizeRole(role)) {
            ROLE_HOST -> 3
            ROLE_SUPER_ADMIN -> 2
            else -> 1
        }
    }

    private fun normalizeUuidList(values: List<String>): List<String> {
        return values.map(::normalizeUuid).filter { it.isNotBlank() }.distinct()
    }

    private fun normalizeExactList(values: List<String>): List<String> {
        return values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun mergeUuidLists(first: List<String>, second: List<String>): List<String> {
        return normalizeUuidList(first + second)
    }

    private fun mergeExactLists(first: List<String>, second: List<String>): List<String> {
        return normalizeExactList(first + second)
    }

    private fun appendDistinctUuid(values: List<String>, value: String): List<String> {
        return normalizeUuidList(values + value)
    }

    private fun appendDistinctExact(values: List<String>, value: String): List<String> {
        return normalizeExactList(values + value)
    }

    private fun removeMatchingUuid(values: List<String>, value: String): List<String> {
        return values.filterNot { sameUuid(it, value) }
    }

    private fun removeMatchingExact(values: List<String>, value: String): List<String> {
        return values.filterNot { it.trim() == value.trim() }
    }

    private data class RuntimeAdminEntry(
        val uuid: String,
        val role: String,
        val superAdmin: Boolean,
        val name: String
    )

    private data class OnlineAuthoritySyncResult(
        val changed: Int = 0,
        val granted: Int = 0,
        val revoked: Int = 0
    )
}
