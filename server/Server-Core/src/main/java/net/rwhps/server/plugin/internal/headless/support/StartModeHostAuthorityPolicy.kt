/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal object StartModeHostAuthorityPolicy {
    data class RuntimeFlags(
        val isAdmin: Boolean,
        val autoAdmin: Boolean,
        val superAdmin: Boolean
    )

    fun hasAdminAuthority(
        ordinaryHostAuthorityEnabled: Boolean,
        isAdmin: Boolean,
        superAdmin: Boolean
    ): Boolean {
        return superAdmin || (ordinaryHostAuthorityEnabled && isAdmin)
    }

    fun hasHostAuthority(
        ordinaryHostAuthorityEnabled: Boolean,
        isAdmin: Boolean,
        autoAdmin: Boolean,
        superAdmin: Boolean
    ): Boolean {
        if (!hasAdminAuthority(ordinaryHostAuthorityEnabled, isAdmin, superAdmin)) {
            return false
        }
        return superAdmin || autoAdmin
    }

    fun hasOrdinaryHostAuthority(
        ordinaryHostAuthorityEnabled: Boolean,
        isAdmin: Boolean,
        autoAdmin: Boolean,
        superAdmin: Boolean
    ): Boolean {
        return ordinaryHostAuthorityEnabled &&
            !superAdmin &&
            isAdmin &&
            autoAdmin
    }

    fun allowOrdinaryHostGrant(ordinaryHostAuthorityEnabled: Boolean): Boolean {
        return ordinaryHostAuthorityEnabled
    }

    fun releaseTemporaryHost(
        isAdmin: Boolean,
        autoAdmin: Boolean,
        superAdmin: Boolean,
        registeredAdmin: Boolean
    ): RuntimeFlags {
        return RuntimeFlags(
            isAdmin = if (autoAdmin) superAdmin || registeredAdmin else isAdmin,
            autoAdmin = false,
            superAdmin = superAdmin
        )
    }

    fun disableOrdinaryAuthority(
        isAdmin: Boolean,
        autoAdmin: Boolean,
        superAdmin: Boolean
    ): RuntimeFlags {
        if (superAdmin) {
            return RuntimeFlags(isAdmin, autoAdmin, true)
        }
        return RuntimeFlags(isAdmin = false, autoAdmin = false, superAdmin = false)
    }
}
