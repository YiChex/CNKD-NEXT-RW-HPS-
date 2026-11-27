/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class UpListSnapshotSupportTest {
    @Test
    fun parsesCurrentTwentyTwoColumnSnapshot() {
        val result = UpListSnapshotSupport.parse(
            """CORRODINGGAMES[1.0]
u_test,Unnamed,176,203.0.113.10,10.0.0.2,5123,false,Test Server,false,Test Map,skirmishMap,battleroom,1.15,false,Test Map,4,100,false,u_test,true,,123"""
        )

        assertNotNull(result)
        assertEquals("22-column", result?.schema)
        assertEquals(1, result?.rawEntryCount)
        assertEquals(1, result?.parsedEntryCount)
        assertTrue(result?.isComplete == true)
        assertEquals(4, result?.entries?.single()?.playerCount)
        assertEquals(100, result?.entries?.single()?.maxPlayerCount)
        assertEquals("u_test", result?.entries?.single()?.id)
    }

    @Test
    fun keepsLegacyEighteenColumnCompatibility() {
        val result = UpListSnapshotSupport.parse(
            """CORRODINGGAMES[1.0]
u_legacy,Unnamed,176,203.0.113.10,10.0.0.2,5123,false,Server,false,Map,skirmishMap,battleroom,1.15,false,Map,legacy,2,8"""
        )

        assertNotNull(result)
        assertEquals("18-column", result?.schema)
        assertTrue(result?.isComplete == true)
        assertEquals(2, result?.entries?.single()?.playerCount)
        assertEquals(8, result?.entries?.single()?.maxPlayerCount)
    }

    @Test
    fun acceptsRelayRowWithBlankCurrentPlayerCount() {
        val result = UpListSnapshotSupport.parse(
            """CORRODINGGAMES[1.0]
u_relay,Unnamed,151,203.0.113.10,0.0.0.0,5123,true,RELAY,false,Relay Room,skirmishMap,battleroom,1.14+,false,Relay Room,,1000,true,u_relay,true,,123"""
        )

        assertNotNull(result)
        assertEquals("22-column", result?.schema)
        assertTrue(result?.isComplete == true)
        assertEquals(0, result?.entries?.single()?.playerCount)
        assertEquals(1000, result?.entries?.single()?.maxPlayerCount)
    }

    @Test
    fun incompleteSnapshotCannotBeUsedForMissingEntryDecisions() {
        val result = UpListSnapshotSupport.parse(
            """CORRODINGGAMES[1.0]
u_test,Unnamed,176,203.0.113.10,10.0.0.2,5123,false,Test Server,false,Test Map,skirmishMap,battleroom,1.15,false,Test Map,4,100,false,u_test,true,,123
malformed"""
        )

        assertNotNull(result)
        assertEquals(2, result?.rawEntryCount)
        assertEquals(1, result?.parsedEntryCount)
        assertFalse(result?.isComplete == true)
    }

    @Test
    fun rejectsUnknownHeaderAndRows() {
        assertNull(UpListSnapshotSupport.parse("not-a-master-server-response"))

        val result = UpListSnapshotSupport.parse("CORRODINGGAMES[1.0]\nmalformed")
        assertNotNull(result)
        assertEquals("unknown", result?.schema)
        assertFalse(result?.isComplete == true)
    }

    @Test
    fun toleratesLeadingBlankLinesAndUtf8BomBeforeHeader() {
        val result = UpListSnapshotSupport.parse(
            "\n\uFEFFCORRODINGGAMES[1.0]\n" +
                "u_test,Unnamed,176,203.0.113.10,10.0.0.2,5123,false,Test Server,false,Test Map,skirmishMap,battleroom,1.15,false,Test Map,4,100,false,u_test,true,,123"
        )

        assertEquals("22-column", result?.schema)
        assertTrue(result?.isComplete == true)
        assertEquals("u_test", result?.entries?.single()?.id)
    }

    @Test
    fun detectsPortAndPrivateAddressMismatch() {
        val result = UpListSnapshotSupport.parse(
            """CORRODINGGAMES[1.0]
u_test,Unnamed,176,203.0.113.10,10.0.0.2,5123,false,Test Server,false,Test Map,skirmishMap,battleroom,1.15,false,Test Map,4,100,false,u_test,true,,123"""
        )
        val entry = result!!.entries.single()

        assertTrue(UpListSnapshotSupport.endpointMatches(entry.privateIp, entry.port, "10.0.0.2", 5123))
        assertFalse(UpListSnapshotSupport.endpointMatches(entry.privateIp, entry.port, "10.0.0.3", 5123))
        assertFalse(UpListSnapshotSupport.endpointMatches(entry.privateIp, entry.port, "10.0.0.2", 5124))
    }

    @Test
    fun acceptsWildcardPrivateAddressWhenPortMatches() {
        val result = UpListSnapshotSupport.parse(
            """CORRODINGGAMES[1.0]
u_relay,Unnamed,151,203.0.113.10,0.0.0.0,5123,true,RELAY,false,Relay Room,skirmishMap,battleroom,1.14+,false,Relay Room,,1000,true,u_relay,true,,123"""
        )
        val entry = result!!.entries.single()

        assertTrue(UpListSnapshotSupport.endpointMatches(entry.privateIp, entry.port, "10.0.0.3", 5123))
        assertFalse(UpListSnapshotSupport.endpointMatches(entry.privateIp, entry.port, "10.0.0.3", 5124))
    }
}
