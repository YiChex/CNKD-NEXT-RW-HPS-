/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class StartModePersistentAuthorityPolicyTest {
    @Test
    fun configuredSuperAdminIsAppliedImmediately() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = false,
            currentAutoAdmin = false,
            currentSuperAdmin = false,
            previouslyManaged = false,
            configured = true,
            configuredSuperAdmin = true
        )

        assertEquals(true, state.isAdmin)
        assertEquals(false, state.autoAdmin)
        assertEquals(true, state.superAdmin)
        assertEquals(true, state.managedByPersistentAccess)
    }

    @Test
    fun removedPersistentAdminLosesAdminAndSuperAdminFlags() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = true,
            currentAutoAdmin = false,
            currentSuperAdmin = true,
            previouslyManaged = true,
            configured = false
        )

        assertEquals(false, state.isAdmin)
        assertEquals(false, state.autoAdmin)
        assertEquals(false, state.superAdmin)
        assertEquals(false, state.managedByPersistentAccess)
    }

    @Test
    fun removedPersistentPrivilegeKeepsCurrentOrdinaryHostContinuity() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = true,
            currentAutoAdmin = true,
            currentSuperAdmin = true,
            previouslyManaged = true,
            configured = false
        )

        assertEquals(true, state.isAdmin)
        assertEquals(true, state.autoAdmin)
        assertEquals(false, state.superAdmin)
        assertEquals(false, state.managedByPersistentAccess)
    }

    @Test
    fun unrelatedRuntimeAdminIsNotChanged() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = true,
            currentAutoAdmin = true,
            currentSuperAdmin = false,
            previouslyManaged = false,
            configured = false
        )

        assertEquals(true, state.isAdmin)
        assertEquals(true, state.autoAdmin)
        assertEquals(false, state.superAdmin)
        assertEquals(false, state.managedByPersistentAccess)
    }

    @Test
    fun disabledOrdinaryAuthoritySuppressesConfiguredOrdinaryAdminFlags() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = true,
            currentAutoAdmin = true,
            currentSuperAdmin = false,
            previouslyManaged = true,
            configured = true,
            ordinaryAdminAuthorityEnabled = false
        )

        assertEquals(false, state.isAdmin)
        assertEquals(false, state.autoAdmin)
        assertEquals(false, state.superAdmin)
        assertEquals(true, state.managedByPersistentAccess)
    }

    @Test
    fun disabledOrdinaryAuthorityStillAppliesConfiguredSuperAdmin() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = false,
            currentAutoAdmin = false,
            currentSuperAdmin = false,
            previouslyManaged = false,
            configured = true,
            ordinaryAdminAuthorityEnabled = false,
            configuredSuperAdmin = true
        )

        assertEquals(true, state.isAdmin)
        assertEquals(false, state.autoAdmin)
        assertEquals(true, state.superAdmin)
        assertEquals(true, state.managedByPersistentAccess)
    }

    @Test
    fun disabledOrdinaryAuthorityClearsUnmanagedRuntimeAdminFlags() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = true,
            currentAutoAdmin = true,
            currentSuperAdmin = false,
            previouslyManaged = false,
            configured = false,
            ordinaryAdminAuthorityEnabled = false
        )

        assertEquals(false, state.isAdmin)
        assertEquals(false, state.autoAdmin)
        assertEquals(false, state.superAdmin)
        assertEquals(false, state.managedByPersistentAccess)
    }

    @Test
    fun disabledOrdinaryAuthorityRejectsNonSuperConfiguredHost() {
        val state = StartModePersistentAuthorityPolicy.reconcile(
            currentIsAdmin = false,
            currentAutoAdmin = false,
            currentSuperAdmin = false,
            previouslyManaged = false,
            configured = true,
            ordinaryAdminAuthorityEnabled = false,
            configuredHost = true,
            configuredSuperAdmin = false
        )

        assertEquals(false, state.isAdmin)
        assertEquals(false, state.autoAdmin)
        assertEquals(false, state.superAdmin)
        assertEquals(true, state.managedByPersistentAccess)
    }
}
