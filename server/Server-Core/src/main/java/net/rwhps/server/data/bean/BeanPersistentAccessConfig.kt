/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.inline.toGson
import net.rwhps.server.util.inline.toPrettyPrintingJson

data class BeanPersistentAccessAdminEntry(
    /** Player UUID / connectHexID shown by the myid command. */
    val uuid: String = "",
    /** Optional human-readable note. It does not participate in matching. */
    val name: String = "",
    /** host / admin / super_admin. host is a visible room owner normalized to super-admin identity. */
    val role: String = "host",
    /** Super admins bypass RoomPermissionConfig when superAdminBypass=true. */
    val superAdmin: Boolean = true
)

data class BeanPersistentAccessConfig(
    val enable: Boolean = true,
    /** Persist runtime ban/admin command changes back to this JSON file immediately. */
    val persistRuntimeChanges: Boolean = true,
    /** Internal one-time guard: legacy Settings.bin access data has already been migrated into this file. */
    val legacySettingsMigrationDone: Boolean = false,
    val persistentBlacklistEnabled: Boolean = true,
    val persistentAdminEnabled: Boolean = true,
    val bannedUUIDs: List<String> = emptyList(),
    val bannedIPs: List<String> = emptyList(),
    val bannedIP24: List<String> = emptyList(),
    /** UUIDs that become visible room owners and are normalized to super-admin identity when they join. */
    val autoHostUUIDs: List<String> = emptyList(),
    /** UUIDs that should receive normal admin permissions when they join. */
    val adminUUIDs: List<String> = emptyList(),
    /** UUIDs that should receive super-admin permissions when they join. */
    val superAdminUUIDs: List<String> = emptyList(),
    /** Rich admin entries for operators who prefer one list with roles and notes. */
    val admins: List<BeanPersistentAccessAdminEntry> = emptyList()
): AbstractBeanConfig(
    this::class.java, "rwhps.config.persistentAccess"
) {
    override fun save() {
        fileUtils.writeFile(this.toPrettyPrintingJson())
    }

    companion object {
        val fileUtils = FileUtils.getFolder(Data.ServerDataPath).toFile("PersistentAccessConfig.json")

        @JvmStatic
        fun stringToClass(): BeanPersistentAccessConfig {
            val raw = fileUtils.readFileStringData()
            val config = if (raw.isBlank()) {
                BeanPersistentAccessConfig()
            } else {
                BeanPersistentAccessConfig::class.java.toGson(raw)
            }
            config.bindFile(fileUtils)
            config.readProperty()
            if (raw.isBlank()) {
                config.save()
            }
            return config
        }
    }
}
