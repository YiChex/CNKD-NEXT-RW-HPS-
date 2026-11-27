/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 排位剥离后（决策 4 选项 A），本策略对象不再有 `managedMatch` 参数：原来的
 * `managedMatch = true` 分支用于"排位托管对局锁死房主权限"，而
 * `RankedShardAdapterSupport.isManagedMatch()` 在 `webPort = 0`（发布默认）下恒为 `false`，
 * 因此该分支从未被触发，删除后非排位行为逐表达式等价。
 *
 * 本测试文件已同步删除 `managedMatchRejectsAllStandaloneRoomAuthority` 用例，
 * 并把 `allowOrdinaryHostGrant` 的断言收敛为"只看开关本身"。
 */
internal class StartModeHostAuthorityPolicyTest {
    @Test
    fun disablingOrdinaryHostAuthorityRejectsStaleAdminFlags() {
        assertEquals(
            false,
            StartModeHostAuthorityPolicy.hasAdminAuthority(
                ordinaryHostAuthorityEnabled = false,
                isAdmin = true,
                superAdmin = false
            )
        )
        assertEquals(
            false,
            StartModeHostAuthorityPolicy.hasHostAuthority(
                ordinaryHostAuthorityEnabled = false,
                isAdmin = true,
                autoAdmin = true,
                superAdmin = false
            )
        )
    }

    @Test
    fun superAdminRemainsEffectiveWhenOrdinaryHostAuthorityIsDisabled() {
        assertEquals(
            true,
            StartModeHostAuthorityPolicy.hasAdminAuthority(
                ordinaryHostAuthorityEnabled = false,
                isAdmin = false,
                superAdmin = true
            )
        )
        assertEquals(
            true,
            StartModeHostAuthorityPolicy.hasHostAuthority(
                ordinaryHostAuthorityEnabled = false,
                isAdmin = false,
                autoAdmin = false,
                superAdmin = true
            )
        )
    }

    @Test
    fun enabledOrdinaryAdminAndHostRolesRemainDistinct() {
        assertEquals(
            true,
            StartModeHostAuthorityPolicy.hasAdminAuthority(
                ordinaryHostAuthorityEnabled = true,
                isAdmin = true,
                superAdmin = false
            )
        )
        assertEquals(
            false,
            StartModeHostAuthorityPolicy.hasHostAuthority(
                ordinaryHostAuthorityEnabled = true,
                isAdmin = true,
                autoAdmin = false,
                superAdmin = false
            )
        )
        assertEquals(
            true,
            StartModeHostAuthorityPolicy.hasHostAuthority(
                ordinaryHostAuthorityEnabled = true,
                isAdmin = true,
                autoAdmin = true,
                superAdmin = false
            )
        )
    }

    @Test
    fun superAdminDoesNotConsumeOrdinaryHostAllocation() {
        assertEquals(
            false,
            StartModeHostAuthorityPolicy.hasOrdinaryHostAuthority(
                ordinaryHostAuthorityEnabled = true,
                isAdmin = true,
                autoAdmin = true,
                superAdmin = true
            )
        )
        assertEquals(
            true,
            StartModeHostAuthorityPolicy.hasOrdinaryHostAuthority(
                ordinaryHostAuthorityEnabled = true,
                isAdmin = true,
                autoAdmin = true,
                superAdmin = false
            )
        )
    }

    @Test
    fun ordinaryHostGrantFollowsTheFeatureSwitchOnly() {
        // 排位剥离前还需要叠加 !managedMatch；该维度已消失，开关本身即唯一判据。
        assertEquals(true, StartModeHostAuthorityPolicy.allowOrdinaryHostGrant(true))
        assertEquals(false, StartModeHostAuthorityPolicy.allowOrdinaryHostGrant(false))
    }

    @Test
    fun disablingOrdinaryAuthorityClearsOrdinaryFlagsOnly() {
        assertEquals(
            StartModeHostAuthorityPolicy.RuntimeFlags(false, false, false),
            StartModeHostAuthorityPolicy.disableOrdinaryAuthority(true, true, false)
        )
        assertEquals(
            StartModeHostAuthorityPolicy.RuntimeFlags(true, true, true),
            StartModeHostAuthorityPolicy.disableOrdinaryAuthority(true, true, true)
        )
    }
}
