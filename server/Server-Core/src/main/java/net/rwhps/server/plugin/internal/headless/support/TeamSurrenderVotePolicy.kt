/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal data class TeamSurrenderVoteProgress(
    val votedCount: Int,
    val requiredCount: Int,
    val eliminatedExemptCount: Int,
    val disconnectedExemptCount: Int,
    val reconnectPendingCount: Int
) {
    val remainingCount: Int = (requiredCount - votedCount).coerceAtLeast(0)
    val complete: Boolean = requiredCount > 0 && remainingCount == 0 && reconnectPendingCount == 0
}

internal object TeamSurrenderVotePolicy {
    private const val ORIGINAL_SURRENDER_SYSTEM_ACTION = 100

    fun isOriginalSurrenderAction(isSystemAction: Boolean, systemAction: Int): Boolean {
        return isSystemAction && systemAction == ORIGINAL_SURRENDER_SYSTEM_ACTION
    }

    fun shouldInterceptOriginalSurrenderAction(
        isSystemAction: Boolean,
        systemAction: Int,
        individualSurrenderEnabled: Boolean
    ): Boolean {
        return isOriginalSurrenderAction(isSystemAction, systemAction) && !individualSurrenderEnabled
    }

    fun shouldUseTeamVote(requiredVoterCount: Int): Boolean {
        return requiredVoterCount > 1
    }

    fun calculate(
        requiredVoterKeys: Set<String>,
        recordedVoteKeys: Set<String>,
        eliminatedExemptCount: Int,
        disconnectedExemptCount: Int,
        reconnectPendingCount: Int = 0
    ): TeamSurrenderVoteProgress {
        return TeamSurrenderVoteProgress(
            votedCount = recordedVoteKeys.count { it in requiredVoterKeys },
            requiredCount = requiredVoterKeys.size,
            eliminatedExemptCount = eliminatedExemptCount.coerceAtLeast(0),
            disconnectedExemptCount = disconnectedExemptCount.coerceAtLeast(0),
            reconnectPendingCount = reconnectPendingCount.coerceAtLeast(0)
        )
    }

    fun pendingSubmissionKeys(
        requiredVoterKeys: Set<String>,
        submittedVoterKeys: Set<String>
    ): Set<String> {
        return requiredVoterKeys.filterTo(linkedSetOf()) { it !in submittedVoterKeys }
    }
}
