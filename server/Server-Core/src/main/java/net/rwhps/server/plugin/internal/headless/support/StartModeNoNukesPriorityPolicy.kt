/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal enum class StartModeNoNukesSource(val diagnosticValue: String) {
    HOST_SELECTION("host_selection"),
    ENGINE_LOBBY("engine_lobby"),
    CONFIG_DEFAULT("config_default")
}

internal data class StartModeNoNukesDecision(
    val noNukes: Boolean,
    val source: StartModeNoNukesSource
)

internal data class StartModeNoNukesHostSelection(
    val noNukes: Boolean,
    val hostIdentity: String
)

internal object StartModeNoNukesPriorityPolicy {
    fun resolve(
        hostSelectedNoNukes: Boolean?,
        engineNoNukes: Boolean?,
        configuredNoNukes: Boolean
    ): StartModeNoNukesDecision {
        if (hostSelectedNoNukes != null) {
            return StartModeNoNukesDecision(hostSelectedNoNukes, StartModeNoNukesSource.HOST_SELECTION)
        }
        if (engineNoNukes != null) {
            return StartModeNoNukesDecision(engineNoNukes, StartModeNoNukesSource.ENGINE_LOBBY)
        }
        return StartModeNoNukesDecision(configuredNoNukes, StartModeNoNukesSource.CONFIG_DEFAULT)
    }
}

internal class StartModeNoNukesSelectionState {
    @Volatile
    private var hostSelection: StartModeNoNukesHostSelection? = null

    fun recordHostSelection(noNukes: Boolean, hostIdentity: String) {
        require(hostIdentity.isNotBlank()) { "hostIdentity must not be blank" }
        hostSelection = StartModeNoNukesHostSelection(noNukes, hostIdentity)
    }

    fun clearForNewLobby() {
        hostSelection = null
    }

    fun recordedHostSelection(): StartModeNoNukesHostSelection? = hostSelection

    fun hostSelection(currentHostIdentity: String?): Boolean? {
        val recorded = hostSelection ?: return null
        return if (currentHostIdentity != null && recorded.hostIdentity == currentHostIdentity) {
            recorded.noNukes
        } else {
            null
        }
    }

    fun resolve(
        currentHostIdentity: String?,
        engineNoNukes: Boolean?,
        configuredNoNukes: Boolean
    ): StartModeNoNukesDecision {
        return StartModeNoNukesPriorityPolicy.resolve(
            hostSelection(currentHostIdentity),
            engineNoNukes,
            configuredNoNukes
        )
    }
}
