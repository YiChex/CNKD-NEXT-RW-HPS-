/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class StartModeNoNukesPriorityPolicyTest {
    @Test
    fun hostDisableNukesOverridesConfiguredAllowNukes() {
        val decision = StartModeNoNukesPriorityPolicy.resolve(
            hostSelectedNoNukes = true,
            engineNoNukes = false,
            configuredNoNukes = false
        )

        assertEquals(true, decision.noNukes)
        assertEquals(StartModeNoNukesSource.HOST_SELECTION, decision.source)
    }

    @Test
    fun hostAllowNukesOverridesConfiguredDisableNukes() {
        val decision = StartModeNoNukesPriorityPolicy.resolve(
            hostSelectedNoNukes = false,
            engineNoNukes = true,
            configuredNoNukes = true
        )

        assertEquals(false, decision.noNukes)
        assertEquals(StartModeNoNukesSource.HOST_SELECTION, decision.source)
    }

    @Test
    fun engineLobbyValuePrecedesConfigWhenHostHasNotSelected() {
        val decision = StartModeNoNukesPriorityPolicy.resolve(
            hostSelectedNoNukes = null,
            engineNoNukes = false,
            configuredNoNukes = true
        )

        assertEquals(false, decision.noNukes)
        assertEquals(StartModeNoNukesSource.ENGINE_LOBBY, decision.source)
    }

    @Test
    fun configIsUsedOnlyWhenHostAndEngineValuesAreUnavailable() {
        val decision = StartModeNoNukesPriorityPolicy.resolve(
            hostSelectedNoNukes = null,
            engineNoNukes = null,
            configuredNoNukes = true
        )

        assertEquals(true, decision.noNukes)
        assertEquals(StartModeNoNukesSource.CONFIG_DEFAULT, decision.source)
    }

    @Test
    fun newLobbyClearsPreviousHostSelection() {
        val state = StartModeNoNukesSelectionState()
        state.recordHostSelection(true, "host-a")

        state.clearForNewLobby()

        assertNull(state.recordedHostSelection())
        assertEquals(
            StartModeNoNukesSource.CONFIG_DEFAULT,
            state.resolve(currentHostIdentity = "host-a", engineNoNukes = null, configuredNoNukes = true).source
        )
    }

    @Test
    fun recordedSelectionOnlyAppliesWhileItsOwnerIsCurrentHost() {
        val state = StartModeNoNukesSelectionState()
        state.recordHostSelection(true, "host-a")

        val decision = state.resolve(
            currentHostIdentity = "host-a",
            engineNoNukes = false,
            configuredNoNukes = false
        )

        assertEquals(true, decision.noNukes)
        assertEquals(StartModeNoNukesSource.HOST_SELECTION, decision.source)
    }

    @Test
    fun transferredHostInvalidatesPreviousOwnersPriority() {
        val state = StartModeNoNukesSelectionState()
        state.recordHostSelection(true, "host-a")

        val decision = state.resolve(
            currentHostIdentity = "host-b",
            engineNoNukes = false,
            configuredNoNukes = true
        )

        assertNull(state.hostSelection("host-b"))
        assertEquals(false, decision.noNukes)
        assertEquals(StartModeNoNukesSource.ENGINE_LOBBY, decision.source)
    }
}
