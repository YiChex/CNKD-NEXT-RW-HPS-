/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.Time
import net.rwhps.server.util.log.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Removes a defeated player's connection without mutating the original engine player table mid-game.
 * The game core owns that table until normal post-game cleanup, which avoids corrupting an active match.
 */
internal object StartModeDefeatedPlayerKickSupport {
    private const val DEFEAT_CONFIRMATION_SECONDS = 3

    private data class DefeatObservation(
        val firstObservedSecond: Int,
        @Volatile var kickSent: Boolean = false
    )

    private val observations = ConcurrentHashMap<String, DefeatObservation>()

    fun onGameStart() {
        observations.clear()
    }

    fun onGameEnd() {
        observations.clear()
    }

    /** Must be called on the original game thread because [PlayerHess.survive] reads original player state. */
    fun evaluateAndKick(room: ServerRoom): Int {
        if (!Data.configStartMode.enableAutoKickPlayerWhenAllUnitsDestroyed || !room.isStartGame) {
            observations.clear()
            return 0
        }

        val now = Time.concurrentSecond()
        var kickedPlayers = 0
        val players = synchronized(room.playerManage) {
            room.playerManage.playerGroup.clone()
        }
        val activeObservationKeys = HashSet<String>(players.size)
        players.eachAll { player ->
            val observationKey = playerObservationKey(player)
            activeObservationKeys += observationKey
            val isSpectator = runCatching { player.team == -3 }.getOrDefault(true)
            val originalGameReportsAlive = runCatching { player.survive }.getOrDefault(true)
            val shouldObserve = shouldObservePlayer(
                matchStarted = room.isStartGame,
                isAi = player.isAi,
                isConnectedHuman = room.playerManage.isConnectedHumanPlayer(player),
                isSpectator = isSpectator,
                originalGameReportsAlive = originalGameReportsAlive
            )
            if (!shouldObserve) {
                clearUnconfirmedObservation(observationKey)
                return@eachAll
            }

            val observation = observations.computeIfAbsent(observationKey) {
                DefeatObservation(firstObservedSecond = now)
            }
            if (observation.kickSent || !isDefeatConfirmed(observation.firstObservedSecond, now)) {
                return@eachAll
            }

            val playerLabel = runCatching { "${player.name}#${player.position}" }.getOrDefault(observationKey)
            val message = Data.configStartMode.autoKickPlayerWhenAllUnitsDestroyedMessage
            runCatching {
                // The KICK packet itself is the native client dialog. Do not delete the engine slot here.
                player.kickPlayer(message)
            }.onSuccess {
                observation.kickSent = true
                kickedPlayers++
                Log.clog("[StartMode] Auto-kicked defeated player after ${DEFEAT_CONFIRMATION_SECONDS}s confirmation: $playerLabel")
            }.onFailure { error ->
                Log.warn("[StartMode] Failed to auto-kick defeated player: $playerLabel", error)
            }
        }
        observations.keys.forEach { observationKey ->
            clearInactiveUnconfirmedObservation(
                observationKey = observationKey,
                playerIsStillActive = observationKey in activeObservationKeys
            )
        }
        return kickedPlayers
    }

    /** Reject a reconnect only when this match already sent that identity an automatic defeat kick. */
    fun shouldRejectReconnect(room: ServerRoom, connectHexID: String): Boolean {
        val reconnectKey = reconnectObservationKey(connectHexID) ?: return false
        val observation = observations[reconnectKey]
        return shouldRejectAutoKickedReconnect(
            featureEnabled = Data.configStartMode.enableAutoKickPlayerWhenAllUnitsDestroyed,
            matchStarted = room.isStartGame,
            kickAlreadySent = observation?.kickSent == true
        )
    }

    internal fun shouldObservePlayer(
        matchStarted: Boolean,
        isAi: Boolean,
        isConnectedHuman: Boolean,
        isSpectator: Boolean,
        originalGameReportsAlive: Boolean
    ): Boolean {
        return matchStarted && !isAi && isConnectedHuman && !isSpectator && !originalGameReportsAlive
    }

    internal fun isDefeatConfirmed(firstObservedSecond: Int, currentSecond: Int): Boolean {
        return currentSecond - firstObservedSecond >= DEFEAT_CONFIRMATION_SECONDS
    }

    internal fun shouldRejectAutoKickedReconnect(
        featureEnabled: Boolean,
        matchStarted: Boolean,
        kickAlreadySent: Boolean
    ): Boolean {
        return featureEnabled && matchStarted && kickAlreadySent
    }

    /**
     * An automatic kick is a match-scoped decision. Its identity must survive the normal
     * connection teardown so the same client cannot immediately reclaim the defeated slot.
     */
    internal fun shouldDiscardObservation(
        playerIsStillActive: Boolean,
        kickAlreadySent: Boolean
    ): Boolean {
        return !playerIsStillActive && !kickAlreadySent
    }

    private fun clearUnconfirmedObservation(observationKey: String) {
        val observation = observations[observationKey] ?: return
        if (!observation.kickSent) {
            observations.remove(observationKey, observation)
        }
    }

    private fun clearInactiveUnconfirmedObservation(
        observationKey: String,
        playerIsStillActive: Boolean
    ) {
        val observation = observations[observationKey] ?: return
        if (shouldDiscardObservation(playerIsStillActive, observation.kickSent)) {
            observations.remove(observationKey, observation)
        }
    }

    private fun playerObservationKey(player: PlayerHess): String {
        val connectId = runCatching { player.connectHexID.trim() }.getOrDefault("")
        val playerIndex = runCatching { player.index }.getOrDefault(-1)
        return observationKey(connectId, playerIndex)
    }

    /** A reconnect can only be denied when the protocol supplied a stable player identity. */
    private fun reconnectObservationKey(connectHexID: String): String? {
        val normalizedConnectId = connectHexID.trim()
        return normalizedConnectId
            .takeUnless { it.isBlank() || it.equals("N/A", ignoreCase = true) }
            ?.let { "id:$it" }
    }

    private fun observationKey(connectHexID: String, playerIndex: Int?): String {
        val normalizedConnectId = connectHexID.trim()
        return if (normalizedConnectId.isBlank() || normalizedConnectId.equals("N/A", ignoreCase = true)) {
            "slot:${playerIndex ?: -1}"
        } else {
            "id:$normalizedConnectId"
        }
    }
}
