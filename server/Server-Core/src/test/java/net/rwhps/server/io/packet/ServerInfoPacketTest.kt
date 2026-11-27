package net.rwhps.server.io.packet

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerInfoPacketTest {
    @Test
    fun `rewriting a map preserves starting units and newer option bytes`() {
        val original = buildServerInfoPacket()
        val originalParsed = ServerInfoPacket(original)
        val rewritten = ServerInfoPacket.rewriteMapName(original, "maps/skirmish/[p2]Big Island (2p).tmx")
        val parsed = ServerInfoPacket(rewritten)

        assertEquals(1, originalParsed.initUnit)
        assertEquals(1f, originalParsed.income)
        assertEquals(false, originalParsed.nukes)
        assertTrue(originalParsed.version2OptionsComplete)
        assertTrue(originalParsed.structurallyComplete)
        assertEquals("maps/skirmish/[p2]Big Island (2p).tmx", parsed.mapName)
        assertEquals(1, parsed.initUnit)
        assertEquals(1f, parsed.income)
        assertEquals(false, parsed.nukes)
        assertTrue(parsed.version2OptionsComplete)
        assertTrue(parsed.structurallyComplete)
        assertEquals(0x78000000, readExtensionMarker(rewritten))
    }

    @Test
    fun `truncated version 2 options are marked incomplete`() {
        val original = buildServerInfoPacket()
        val truncatedBeforeNoNukes = original.copyOf(original.size - 5)

        val parsed = ServerInfoPacket(truncatedBeforeNoNukes)

        assertEquals(8, parsed.optionsVersion)
        assertFalse(parsed.version2OptionsComplete)
        assertFalse(parsed.structurallyComplete)
    }

    private fun buildServerInfoPacket(): ByteArray {
        val output = GameOutputStream()
        output.writeString("com.corrodinggames.rts")
        output.writeInt(123)
        output.writeInt(0)
        output.writeString("maps/skirmish/old-map.tmx")
        output.writeInt(0)
        output.writeInt(2)
        output.writeBoolean(true)
        output.writeInt(1)
        output.writeByte(8)
        output.writeBoolean(false)
        output.writeBoolean(true)
        output.writeInt(1000)
        output.writeInt(1000)
        output.writeInt(1)
        output.writeFloat(1f)
        output.writeBoolean(false)
        output.writeInt(0x78000000)
        return output.getByteArray()
    }

    private fun readExtensionMarker(bytes: ByteArray): Int {
        GameInputStream(bytes).use { input ->
            input.readString()
            input.readInt()
            input.readInt()
            input.readString()
            input.readInt()
            input.readInt()
            input.readBoolean()
            input.readInt()
            val optionsVersion = input.readByte()
            input.readBoolean()
            input.readBoolean()
            if (optionsVersion >= 1) {
                input.readInt()
                input.readInt()
            }
            if (optionsVersion >= 2) {
                input.readInt()
                input.readFloat()
                input.readBoolean()
            }
            return input.readInt()
        }
    }
}
