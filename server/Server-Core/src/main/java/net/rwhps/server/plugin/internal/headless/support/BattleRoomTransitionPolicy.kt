/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal object BattleRoomTransitionPolicy {
    const val ENGINE_COMMIT_DELAY_MILLIS = 250
    const val ENGINE_WATCHDOG_GRACE_SECONDS = 10
    const val LOBBY_SYNC_COOLDOWN_MILLIS = 1_000L
    private const val MAX_COUNTDOWN_SECONDS = 300

    fun normalizeCountdownSeconds(value: Int): Int = value.coerceIn(0, MAX_COUNTDOWN_SECONDS)

    fun watchdogDelaySeconds(countdownSeconds: Int): Int {
        return normalizeCountdownSeconds(countdownSeconds) + ENGINE_WATCHDOG_GRACE_SECONDS
    }

    fun canSchedule(
        engineGameStarted: Boolean,
        engineCountdownPending: Boolean,
        engineCommitPending: Boolean
    ): Boolean {
        return engineGameStarted && !engineCountdownPending && !engineCommitPending
    }

    fun engineReturnComplete(
        engineGameStarted: Boolean,
        engineCountdownPending: Boolean,
        engineCommitPending: Boolean
    ): Boolean {
        return !engineGameStarted && !engineCountdownPending && !engineCommitPending
    }

    fun hasAuthoritativeReturnEvidence(
        engineReturnComplete: Boolean,
        returnPacketObserved: Boolean
    ): Boolean {
        return engineReturnComplete || returnPacketObserved
    }

    fun shouldProtectivelyResetAfterFailure(roomStillStarted: Boolean): Boolean {
        return roomStillStarted
    }

    fun shouldScheduleCommit(commitScheduled: Boolean, commitInProgress: Boolean): Boolean {
        return !commitScheduled && !commitInProgress
    }

    fun canSyncLobbyPlayers(
        transitionInProgress: Boolean,
        commitBlocking: Boolean,
        syncNotBeforeMillis: Long,
        nowMillis: Long
    ): Boolean {
        return !transitionInProgress && !commitBlocking && nowMillis >= syncNotBeforeMillis
    }

    fun isWithinLobbySyncGrace(
        nowMillis: Long,
        connectedAtMillis: Long,
        graceMillis: Long,
        graceUntilMillis: Long
    ): Boolean {
        return graceMillis > 0L &&
            (nowMillis - connectedAtMillis < graceMillis || nowMillis < graceUntilMillis)
    }

    fun canUseLobbyJoinGrace(connectedGeneration: Long, currentGeneration: Long): Boolean {
        return connectedGeneration == currentGeneration
    }
}
