/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.player.PlayerHess
import java.util.concurrent.atomic.AtomicLong

/**
 * Player-facing smoothing around short disconnect/reconnect bursts.
 */
internal object StartModePlayerExperienceSupport {
    private const val DISCONNECT_AT_MILLIS = "StartMode.DisconnectAtMillis"
    private const val DISCONNECT_GRACE_UNTIL_MILLIS = "StartMode.DisconnectGraceUntilMillis"
    private const val DISCONNECT_PRUNED_AFTER_GAME_OVER = "StartMode.DisconnectPrunedAfterGameOver"
    private const val RECONNECT_RECOVERED = "StartMode.ReconnectRecovered"

    private val pendingDisconnectCount = AtomicLong(0L)
    private val recoveredReconnectCount = AtomicLong(0L)
    private val suppressedLeaveEventCount = AtomicLong(0L)
    private val firedLeaveEventCount = AtomicLong(0L)
    private val prunedAfterGameOverCount = AtomicLong(0L)
    private val lastEventAtMillis = AtomicLong(0L)

    @Volatile private var lastEvent = "none"

    fun reconnectGraceMillis(): Long {
        return (Data.configStartMode?.reconnectGraceMillis ?: 5_000).coerceIn(0, 60_000).toLong()
    }

    fun leaveDebounceMillis(wasStartGame: Boolean): Long {
        val grace = reconnectGraceMillis()
        if (grace <= 0L) {
            return 500L
        }
        // In-game reconnects are more expensive, so give them a little extra room.
        return if (wasStartGame) grace.coerceAtLeast(8_000L) else grace.coerceAtLeast(1_000L)
    }

    fun markDisconnected(player: PlayerHess, wasStartGame: Boolean) {
        val now = System.currentTimeMillis()
        player.addData(DISCONNECT_AT_MILLIS, now)
        player.addData(DISCONNECT_GRACE_UNTIL_MILLIS, now + leaveDebounceMillis(wasStartGame))
        pendingDisconnectCount.incrementAndGet()
        recordEvent(if (wasStartGame) "disconnect_marked_ingame" else "disconnect_marked_lobby")
    }

    fun markReconnectIfProtected(player: PlayerHess): Boolean {
        val disconnectedAt = player.getData<Long>(DISCONNECT_AT_MILLIS) ?: return false
        val now = System.currentTimeMillis()
        val graceUntil = player.getData<Long>(DISCONNECT_GRACE_UNTIL_MILLIS) ?: (disconnectedAt + reconnectGraceMillis())
        val recovered = now in disconnectedAt..graceUntil
        player.removeData(DISCONNECT_AT_MILLIS)
        player.removeData(DISCONNECT_GRACE_UNTIL_MILLIS)
        if (!recovered) {
            recordEvent("reconnect_expired")
            return false
        }

        player.addData(RECONNECT_RECOVERED, true)
        recoveredReconnectCount.incrementAndGet()
        recordEvent("reconnect_recovered")
        return true
    }

    fun consumeReconnectRecovered(player: PlayerHess): Boolean {
        val recovered = player.getData(RECONNECT_RECOVERED, false)
        player.removeData(RECONNECT_RECOVERED)
        return recovered
    }

    fun recordLeaveEventSuppressedByReconnect() {
        suppressedLeaveEventCount.incrementAndGet()
        decrementPendingDisconnects()
        recordEvent("leave_suppressed_by_reconnect")
    }

    fun recordLeaveEventFired() {
        firedLeaveEventCount.incrementAndGet()
        decrementPendingDisconnects()
        recordEvent("leave_event_fired")
    }

    fun markDisconnectedPlayerPrunedAfterGameOver(player: PlayerHess) {
        player.addData(DISCONNECT_PRUNED_AFTER_GAME_OVER, true)
        prunedAfterGameOverCount.incrementAndGet()
        decrementPendingDisconnects()
        recordEvent("pruned_after_gameover")
    }

    fun consumeDisconnectedPlayerPrunedAfterGameOver(player: PlayerHess): Boolean {
        val pruned = player.getData(DISCONNECT_PRUNED_AFTER_GAME_OVER, false)
        player.removeData(DISCONNECT_PRUNED_AFTER_GAME_OVER)
        return pruned
    }

    /**
     * A disconnect that happened during a match may be delivered after the
     * match has already returned to the lobby. In that case the delayed leave
     * callback must finish removing the old player reservation.
     */
    internal fun shouldRemovePlayerAllAfterDisconnect(
        wasStartGame: Boolean,
        roomStillStarted: Boolean
    ): Boolean {
        return !wasStartGame || !roomStillStarted
    }

    /** A non-null connection reference is not enough; the transport may already be closed. */
    internal fun shouldSuppressLeaveEventForReconnect(
        connectionPresent: Boolean,
        connectionDisconnected: Boolean
    ): Boolean {
        return connectionPresent && !connectionDisconnected
    }

    fun isWithinReconnectGrace(player: PlayerHess): Boolean {
        val graceUntil = player.getData<Long>(DISCONNECT_GRACE_UNTIL_MILLIS) ?: return false
        return System.currentTimeMillis() <= graceUntil
    }

    fun buildDiagnostics(): List<String> {
        val pendingDisconnects = pendingDisconnectCount.get()
        return listOf(
            "reconnect.graceMillis=${reconnectGraceMillis()}",
            "reconnect.state=${if (pendingDisconnects > 0L) "pending_grace" else "idle"}",
            "reconnect.pendingDisconnects=$pendingDisconnects",
            "reconnect.recovered=${recoveredReconnectCount.get()}",
            "reconnect.leaveSuppressed=${suppressedLeaveEventCount.get()}",
            "reconnect.leaveFired=${firedLeaveEventCount.get()}",
            "reconnect.prunedAfterGameOver=${prunedAfterGameOverCount.get()}",
            "reconnect.lastEvent=$lastEvent",
            "reconnect.lastEventAgeMs=${lastEventAgeMillis()}"
        )
    }

    private fun recordEvent(event: String) {
        lastEvent = event
        lastEventAtMillis.set(System.currentTimeMillis())
    }

    private fun lastEventAgeMillis(): Long {
        val eventAt = lastEventAtMillis.get()
        if (eventAt <= 0L) {
            return -1L
        }
        return (System.currentTimeMillis() - eventAt).coerceAtLeast(0L)
    }

    private fun decrementPendingDisconnects() {
        while (true) {
            val current = pendingDisconnectCount.get()
            if (current <= 0L) {
                return
            }
            if (pendingDisconnectCount.compareAndSet(current, current - 1L)) {
                return
            }
        }
    }
}
