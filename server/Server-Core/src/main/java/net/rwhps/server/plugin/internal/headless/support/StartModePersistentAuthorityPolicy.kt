/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal object StartModePersistentAuthorityPolicy {
    data class State(
        val isAdmin: Boolean,
        val autoAdmin: Boolean,
        val superAdmin: Boolean,
        val managedByPersistentAccess: Boolean
    )

    fun reconcile(
        currentIsAdmin: Boolean,
        currentAutoAdmin: Boolean,
        currentSuperAdmin: Boolean,
        previouslyManaged: Boolean,
        configured: Boolean,
        ordinaryAdminAuthorityEnabled: Boolean = true,
        configuredHost: Boolean = false,
        configuredSuperAdmin: Boolean = false
    ): State {
        if (configured) {
            val effectiveAdmin = configuredSuperAdmin || ordinaryAdminAuthorityEnabled
            val effectiveOrdinaryHost = ordinaryAdminAuthorityEnabled && currentAutoAdmin
            return State(
                isAdmin = effectiveAdmin,
                autoAdmin = (configuredHost && effectiveAdmin) || effectiveOrdinaryHost,
                superAdmin = configuredSuperAdmin,
                managedByPersistentAccess = true
            )
        }
        if (previouslyManaged) {
            val preserveOrdinaryHost = ordinaryAdminAuthorityEnabled && currentAutoAdmin
            return State(
                isAdmin = preserveOrdinaryHost,
                autoAdmin = preserveOrdinaryHost,
                superAdmin = false,
                managedByPersistentAccess = false
            )
        }
        if (!ordinaryAdminAuthorityEnabled && !currentSuperAdmin) {
            return State(
                isAdmin = false,
                autoAdmin = false,
                superAdmin = false,
                managedByPersistentAccess = false
            )
        }
        return State(
            isAdmin = currentIsAdmin,
            autoAdmin = currentAutoAdmin,
            superAdmin = currentSuperAdmin,
            managedByPersistentAccess = false
        )
    }
}
