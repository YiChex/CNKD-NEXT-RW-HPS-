/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

/** Classifies the last observed game-loop tick without performing recovery side effects. */
internal object GameLoopLivenessPolicy {
    enum class State {
        NOT_OBSERVED,
        HEALTHY,
        STALE
    }

    fun evaluate(lastTickAtMillis: Long, nowMillis: Long, staleAfterMillis: Long): State {
        require(staleAfterMillis > 0L) { "staleAfterMillis must be positive" }
        if (lastTickAtMillis <= 0L) {
            return State.NOT_OBSERVED
        }
        val ageMillis = (nowMillis - lastTickAtMillis).coerceAtLeast(0L)
        return if (ageMillis >= staleAfterMillis) State.STALE else State.HEALTHY
    }

    fun ageMillis(lastTickAtMillis: Long, nowMillis: Long): Long? {
        if (lastTickAtMillis <= 0L) {
            return null
        }
        return (nowMillis - lastTickAtMillis).coerceAtLeast(0L)
    }

    fun shouldRestart(
        state: State,
        staleSinceMillis: Long,
        nowMillis: Long,
        recoveryGraceMillis: Long
    ): Boolean {
        require(recoveryGraceMillis > 0L) { "recoveryGraceMillis must be positive" }
        return state == State.STALE &&
            staleSinceMillis > 0L &&
            nowMillis - staleSinceMillis >= recoveryGraceMillis
    }
}
