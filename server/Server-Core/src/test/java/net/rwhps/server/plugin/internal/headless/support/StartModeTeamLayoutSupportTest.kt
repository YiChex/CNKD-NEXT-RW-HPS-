package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class StartModeTeamLayoutSupportTest {
    @Test
    fun parsesStockLayoutNamesOnly() {
        assertEquals(StartModeTeamLayoutSupport.Layout.TWO_SIDES, StartModeTeamLayoutSupport.parseLayout("layout_2sides"))
        assertEquals(StartModeTeamLayoutSupport.Layout.THREE_SIDES, StartModeTeamLayoutSupport.parseLayout("3"))
        assertEquals(StartModeTeamLayoutSupport.Layout.FFA, StartModeTeamLayoutSupport.parseLayout("mixed"))
        assertEquals(StartModeTeamLayoutSupport.Layout.SPECTATORS, StartModeTeamLayoutSupport.parseLayout("all_spectators"))
        assertEquals(null, StartModeTeamLayoutSupport.parseLayout("4"))
        assertEquals(null, StartModeTeamLayoutSupport.parseLayout("layout_4sides"))
        assertEquals(null, StartModeTeamLayoutSupport.parseLayout("5"))
    }

    @Test
    fun filteredTwoSideLayoutKeepsSpectatorSlotsReserved() {
        val assignments = StartModeTeamLayoutSupport.planAssignments(
            activePlayerSlots = listOf(0, 2, 3),
            spectatorSlots = setOf(1),
            maxSlots = 4,
            layout = StartModeTeamLayoutSupport.Layout.TWO_SIDES,
            random = Random(7)
        )

        assertEquals(3, assignments.size)
        assertEquals(3, assignments.map { it.playerSlot }.toSet().size)
        assertEquals(3, assignments.map { it.targetPosition }.toSet().size)
        assertTrue(assignments.all { it.targetPosition != 1 })
        assertTrue(assignments.all { it.team == 0 || it.team == 1 })
    }

    @Test
    fun filteredThreeSideLayoutUsesStockDistribution() {
        val assignments = StartModeTeamLayoutSupport.planAssignments(
            activePlayerSlots = (0 until 6).toList(),
            spectatorSlots = setOf(6),
            maxSlots = 8,
            layout = StartModeTeamLayoutSupport.Layout.THREE_SIDES,
            random = Random(11)
        )

        assertEquals(6, assignments.size)
        assertEquals(2, assignments.count { it.team == 0 })
        assertEquals(2, assignments.count { it.team == 1 })
        assertEquals(2, assignments.count { it.team == 2 })
        assertTrue(assignments.all { it.targetPosition != 6 })
    }

    @Test
    fun threeSideLayoutUsesCompactPositionsWhenServerCapacityIsLarger() {
        val assignments = StartModeTeamLayoutSupport.planAssignments(
            activePlayerSlots = (0 until 5).toList(),
            spectatorSlots = emptySet(),
            maxSlots = 100,
            layout = StartModeTeamLayoutSupport.Layout.THREE_SIDES,
            random = Random(11)
        )

        assertEquals((0 until 5).toSet(), assignments.map { it.targetPosition }.toSet())
        assertTrue(assignments.all { it.targetPosition < 5 })
        assertTrue(assignments.none { it.team == 2 && it.targetPosition >= 5 })
    }

    @Test
    fun threeSideLayoutDoesNotPlaceCOnCapacityBoundary() {
        val assignments = StartModeTeamLayoutSupport.planAssignments(
            activePlayerSlots = (0 until 100).toList(),
            spectatorSlots = emptySet(),
            maxSlots = 100,
            layout = StartModeTeamLayoutSupport.Layout.THREE_SIDES,
            random = Random(11)
        )

        assertEquals((0 until 100).toSet(), assignments.map { it.targetPosition }.toSet())
        assertEquals(0, assignments.single { it.targetPosition == 99 }.team)
        assertTrue(assignments.filter { it.team == 2 }.all { it.targetPosition < 99 })
    }

    @Test
    fun filteredFfaAssignsUniqueTeamsAndPreservesSpectatorSlots() {
        val assignments = StartModeTeamLayoutSupport.planAssignments(
            activePlayerSlots = listOf(0, 1, 3, 4),
            spectatorSlots = setOf(2),
            maxSlots = 5,
            layout = StartModeTeamLayoutSupport.Layout.FFA,
            random = Random(13)
        )

        assertEquals(4, assignments.size)
        assertEquals(setOf(0, 1, 2, 3), assignments.map { it.team }.toSet())
        assertTrue(assignments.all { it.targetPosition != 2 })
    }
}
