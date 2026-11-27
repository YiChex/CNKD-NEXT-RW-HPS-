/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 */

package net.rwhps.server.net

import io.netty.channel.ChannelException
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import java.net.BindException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetServiceBindSupportTest {
    @Test
    fun returnsChannelOnlyAfterSuccessfulBind() {
        val channel = EmbeddedChannel()
        try {
            val promise = DefaultChannelPromise(channel).setSuccess()
            assertSame(channel, NetServiceBindSupport.awaitSuccessfulBind(promise))
        } finally {
            channel.close()
        }
    }

    @Test
    fun recognizesWrappedBindFailure() {
        val error = ChannelException("bind failed", BindException("Address already in use"))
        assertTrue(NetServiceBindSupport.isBindFailure(error))
    }

    @Test
    fun doesNotClassifyUnrelatedNetworkFailureAsBindFailure() {
        assertEquals(false, NetServiceBindSupport.isBindFailure(IllegalStateException("network failure")))
    }
}
