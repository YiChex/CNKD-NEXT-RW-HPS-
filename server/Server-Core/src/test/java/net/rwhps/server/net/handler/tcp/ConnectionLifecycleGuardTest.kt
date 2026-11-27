package net.rwhps.server.net.handler.tcp

import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionLifecycleGuardTest {
    @Test
    fun `NAT players share one admission policy and released slots can reconnect`() {
        val channels = (0 until 30).map { EmbeddedChannel() }
        try {
            channels.forEach { assertTrue(ConnectionLifecycleGuard.onChannelRegistered(it).accepted) }
            channels.take(15).forEach { ConnectionLifecycleGuard.onChannelUnregistered(it) }
            val reconnects = (0 until 15).map { EmbeddedChannel() }
            try {
                reconnects.forEach { assertTrue(ConnectionLifecycleGuard.onChannelRegistered(it).accepted) }
            } finally {
                reconnects.forEach { ConnectionLifecycleGuard.onChannelUnregistered(it); it.close() }
            }
        } finally {
            channels.forEach { ConnectionLifecycleGuard.onChannelUnregistered(it); it.close() }
        }
    }

    @Test
    fun `uses a longer packet assembly deadline after protocol establishment`() {
        val channel = EmbeddedChannel()
        try {
            assertEquals(8_000L, ConnectionLifecycleGuard.packetAssemblyTimeoutMillis(channel))

            ConnectionLifecycleGuard.markProtocolEstablished(channel)

            assertEquals(60_000L, ConnectionLifecycleGuard.packetAssemblyTimeoutMillis(channel))
        } finally {
            channel.close()
        }
    }
}
