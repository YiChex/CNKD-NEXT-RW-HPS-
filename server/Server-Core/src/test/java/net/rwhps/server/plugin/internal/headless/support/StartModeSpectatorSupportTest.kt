package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanStartModeConfig
import net.rwhps.server.data.bean.BeanRoomPermissionPlayerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class StartModeSpectatorSupportTest {
    @Test
    fun normalizesStockClientLobbyCommands() {
        assertEquals("self_move 8", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("self_move8"))
        assertEquals("self_team 2", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("self_team2"))
        assertEquals("move 1 2", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("move1 2"))
        assertEquals("team 1 2", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("team1 2"))
        assertEquals("teamlayout 3", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("teamlayout3"))
        assertEquals("teamdivide 3", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("teamdivide3"))
    }

    @Test
    fun leavesNonLobbyCommandsUntouched() {
        assertEquals("help", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("help"))
        assertEquals("move player 2", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("move player 2"))
        assertEquals("teamshuffle", StartModeSpectatorSupport.normalizeOfficialLobbyCommandBody("teamshuffle"))
    }

    @Test
    fun teamLockMatchesOnlyOfficialPlacementCommands() {
        assertTrue(StartModeSpectatorSupport.isTeamLockPositionChangeCommand("-move1 2"))
        assertTrue(StartModeSpectatorSupport.isTeamLockPositionChangeCommand(".self_team2"))
        assertTrue(StartModeSpectatorSupport.isTeamLockPositionChangeCommand("-qc -team1 2"))
        assertTrue(StartModeSpectatorSupport.isTeamLockPositionChangeCommand("-qc /self_move 8 -1"))
        assertFalse(StartModeSpectatorSupport.isTeamLockPositionChangeCommand(".movement 1"))
        assertFalse(StartModeSpectatorSupport.isTeamLockPositionChangeCommand(".teamwork"))
        assertFalse(StartModeSpectatorSupport.isTeamLockPositionChangeCommand(".help"))
    }

    @Test
    fun spectatorRequestsUseTheSameHandlerAcrossPrefixesAndQuickCommands() {
        for (prefix in listOf("/", "-", ".", "_")) {
            for (body in listOf("self_move -2", "self_move-2")) {
                assertEquals("/self_move -2", StartModeSpectatorSupport.normalizeLobbyCommand("$prefix$body"))
                assertEquals("/self_move -2", StartModeSpectatorSupport.normalizeLobbyCommand("${prefix}qc /$body"))
                assertEquals("/self_move -2", StartModeSpectatorSupport.normalizeLobbyCommand("${prefix}qc\t-$body"))
            }
            assertEquals("/self_team S", StartModeSpectatorSupport.normalizeLobbyCommand("${prefix}self_team S"))
            assertTrue(StartModeSpectatorSupport.isTeamLockPositionChangeCommand("${prefix}qc /self_team\tS"))
            assertTrue(StartModeSpectatorSupport.isTeamLockPositionChangeCommand("${prefix}self_move-2"))
        }
        assertNull(StartModeSpectatorSupport.normalizeLobbyCommand("ordinary chat"))
        assertEquals("/movement 1", StartModeSpectatorSupport.normalizeLobbyCommand("-movement 1"))
        assertEquals("/", StartModeSpectatorSupport.normalizeLobbyCommand("-qc"))
    }

    @Test
    fun spectatorAliasesAndStockAutomaticTeamHaveDifferentMeanings() {
        for (token in listOf("S", "spectator", "观战", "-2", "-3", "-4")) {
            assertTrue(StartModeSpectatorSupport.isSpectatorMoveTarget(token), token)
            assertEquals(-3, StartModeSpectatorSupport.parseTeamArgument(token, 4), token)
        }
        assertEquals(0, StartModeSpectatorSupport.parseTeamArgument("-1", 4))
        assertEquals(1, StartModeSpectatorSupport.parseTeamArgument("-1", 5))
        assertEquals(0, StartModeSpectatorSupport.parseTeamArgument("1", 4))
        assertEquals(1, StartModeSpectatorSupport.parseTeamArgument("2", 4))
        for (token in listOf("0", "-5", "101", "2147483647", "invalid")) {
            assertNull(StartModeSpectatorSupport.parseTeamArgument(token, 4), token)
        }
    }

    @Test
    fun movingBackToANumberedSlotLeavesSpectatorTeamWithoutAnOptionalTeamArgument() {
        assertEquals(0, StartModeSpectatorSupport.teamAfterPositionChange(null, 4, -3))
        assertEquals(1, StartModeSpectatorSupport.teamAfterPositionChange(null, 5, -3))
        assertEquals(2, StartModeSpectatorSupport.teamAfterPositionChange(null, 4, 2))
        assertEquals(1, StartModeSpectatorSupport.teamAfterPositionChange("-1", 5, -3))
        // Preserve the native move command's optional ally-group numbering.
        assertEquals(2, StartModeSpectatorSupport.teamAfterPositionChange("2", 4, -3))
        assertEquals(4, StartModeSpectatorSupport.teamAfterPositionChange(null, 4, -3, forcedTeam = 4))
        assertEquals(4, StartModeSpectatorSupport.teamAfterPositionChange("-1", 4, -3, forcedTeam = 4))
    }

    @Test
    fun defaultAndMapLockedRoomsAllowOrdinaryPlayersToEnterSpectatorTeam() {
        val defaults = BeanStartModeConfig()
        assertFalse(defaults.disablePositionChange)
        assertFalse(defaults.disableTeamChange)
        assertTrue(BeanRoomPermissionPlayerConfig().allowMovePlayers)
        assertTrue(BeanRoomPermissionPlayerConfig().allowChangeTeam)
        assertTrue(StartModeLobbySupport.canChangeToSpectator(defaults, false))
        assertTrue(StartModeLobbySupport.canChangeToSpectator(defaults.copy(enableMapLock = true), false))
        assertTrue(StartModeLobbySupport.canChangeToSpectator(defaults.copy(enableFreeForAllMode = true), false))
        assertTrue(StartModeLobbySupport.canChangeToSpectator(defaults.copy(enableSurvivalMapMode = true), false))
    }

    @Test
    fun explicitPlacementLocksApplyToSpectatorsAndRespectSeparateAdminOverrides() {
        assertFalse(StartModeLobbySupport.canChangeToSpectator(BeanStartModeConfig(disablePositionChange = true), false))
        assertFalse(StartModeLobbySupport.canChangeToSpectator(BeanStartModeConfig(disableTeamChange = true), false))
        val locked = BeanStartModeConfig(disablePositionChange = true, disableTeamChange = true)
        assertTrue(StartModeLobbySupport.canChangeToSpectator(locked, true))
        assertFalse(StartModeLobbySupport.canChangeToSpectator(locked.copy(adminCanOverridePositionRules = false), true))
        assertFalse(StartModeLobbySupport.canChangeToSpectator(locked.copy(adminCanOverrideTeamRules = false), true))
    }

    class NativePlayer(@JvmField var r: Int = 0)
    class NativeNetEngine(private val hasFreeSlot: Boolean) {
        @Suppress("UNUSED_PARAMETER")
        fun a(player: NativePlayer, target: Int) {
            if (hasFreeSlot) player.r = target
        }
    }

    @Test
    fun aFullSpectatorTableDoesNotReportSuccessOrOverwriteTheNativeTeam() {
        val player = NativePlayer(1)
        assertFalse(StartModeSpectatorSupport.applyNativeSpectatorMove(NativeNetEngine(false), player, NativePlayer::class.java))
        assertEquals(1, player.r)
        assertTrue(StartModeSpectatorSupport.applyNativeSpectatorMove(NativeNetEngine(true), player, NativePlayer::class.java))
        assertEquals(-3, player.r)
        val unchanged = NativePlayer(1)
        assertFalse(StartModeSpectatorSupport.applyNativeSpectatorMove(Any(), unchanged, NativePlayer::class.java))
        assertEquals(1, unchanged.r)
    }
}
