/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.headless.core.link

/** Optional internal capability for the active-match full game snapshot path. */
internal interface FullGameSyncLink {
    fun canSyncFullGameState(): Boolean

    fun syncFullGameState(): FullGameSyncResult

    /** Internal callers with delayed work must retain the match they originally observed. */
    fun syncFullGameStateForGeneration(expectedGeneration: Long): FullGameSyncResult
}

internal data class FullGameSyncResult(
    val success: Boolean,
    val error: Throwable? = null
)
