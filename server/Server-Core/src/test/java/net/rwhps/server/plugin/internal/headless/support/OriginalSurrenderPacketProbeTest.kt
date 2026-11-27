package net.rwhps.server.plugin.internal.headless.support

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class OriginalSurrenderPacketProbeTest {
    @Test
    fun `matches exact surrender envelope suffix`() {
        assertTrue(OriginalSurrenderPacketProbe.matchesEnvelopeSuffix(envelope(action = 100)))
        assertFalse(OriginalSurrenderPacketProbe.matchesEnvelopeSuffix(envelope(action = 5)))
    }

    @Test
    fun `rejects trailing bytes outside declared payload`() {
        val valid = envelope(action = 100)
        assertFalse(OriginalSurrenderPacketProbe.matchesEnvelopeSuffix(valid + byteArrayOf(0)))
    }

    @Test
    fun `rejects truncated and inconsistent envelopes`() {
        assertFalse(OriginalSurrenderPacketProbe.matchesEnvelopeSuffix(byteArrayOf()))
        assertFalse(OriginalSurrenderPacketProbe.matchesEnvelopeSuffix(envelope(action = 100).copyOf(8)))
    }

    private fun envelope(action: Int): ByteArray {
        val payload = ByteArrayOutputStream().use { payloadBytes ->
            DataOutputStream(payloadBytes).use { output ->
                output.writeInt(action)
                output.writeInt(0)
                output.writeBoolean(false)
            }
            payloadBytes.toByteArray()
        }
        return ByteArrayOutputStream().use { envelopeBytes ->
            DataOutputStream(envelopeBytes).use { output ->
                output.writeUTF("c")
                output.writeInt(payload.size)
                output.write(payload)
            }
            envelopeBytes.toByteArray()
        }
    }
}
