package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MapPingMessageWhitelistTest {
    @Test
    fun acceptsKnownMapPingActionEnvelope() {
        assertEquals(true, MapPingMessageWhitelist.isWhitelisted("c_6_ATTACK 100 200"))
        assertEquals(true, MapPingMessageWhitelist.isWhitelisted("c_6_normal"))
        assertEquals(true, MapPingMessageWhitelist.isWhitelisted("c_6_DEFEND|100,200"))
        assertEquals(true, MapPingMessageWhitelist.isWhitelisted("actionId=c_6_UPGRADE|100,200"))
        assertEquals(true, MapPingMessageWhitelist.isWhitelisted("actionId=c_6_PING_POINT_V2|100,200"))
    }

    @Test
    fun acceptsVersionedActionsAndRejectsMalformedOrOversizedChat() {
        assertEquals(true, MapPingMessageWhitelist.isWhitelisted("c_6_UNKNOWN 100 200"))
        assertEquals(false, MapPingMessageWhitelist.isWhitelisted("prefixc_6_ATTACK 100 200"))
        assertEquals(false, MapPingMessageWhitelist.isWhitelisted("c_6_ 100 200"))
        assertEquals(false, MapPingMessageWhitelist.isWhitelisted("c_6_ATTACK ${"x".repeat(513)}"))
        assertEquals(false, MapPingMessageWhitelist.isWhitelisted("ordinary chat"))
    }
}
