/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 */

package net.rwhps.server.plugin.beta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

internal class UpListIdentitySupportTest {
    @Test
    fun sameDeploymentInputsProduceStableIdentity() {
        val first = UpListIdentitySupport.buildServerId(
            "connect-1", "/srv/cnkd/room-a", null, 30001
        )
        val second = UpListIdentitySupport.buildServerId(
            "connect-1", "/srv/cnkd/room-a", null, 30001
        )

        assertEquals(first, second)
    }

    @Test
    fun copiedSettingsInDifferentDeploymentDirectoriesProduceDifferentIdentities() {
        val first = UpListIdentitySupport.buildServerId(
            "copied-connect", "/srv/cnkd/room-a", null, 30001
        )
        val second = UpListIdentitySupport.buildServerId(
            "copied-connect", "/srv/cnkd/room-b", null, 30001
        )

        assertNotEquals(first, second)
    }

    @Test
    fun legacyIdentityRemainsAvailableForMigrationDiagnostics() {
        val legacy = UpListIdentitySupport.buildLegacyServerId(
            "connect-1", null, 30001
        )
        val scoped = UpListIdentitySupport.buildServerId(
            "connect-1", "/srv/cnkd/room-a", null, 30001
        )

        assertNotEquals(legacy, scoped)
        assertEquals(legacy, UpListIdentitySupport.buildLegacyServerId("connect-1", null, 30001))
    }
}
