package net.rwhps.server.struct.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeqDrainTest {
    @Test
    fun `drain returns queued entries and clears source`() {
        val source = Seq<String>(16, true)
        source.add("first")
        source.add("second")

        val drained = source.drain()

        assertEquals(listOf("first", "second"), drained.toList())
        assertTrue(source.isEmpty())
    }
}
