package net.rwhps.server.util.game

import net.rwhps.server.game.event.core.AbstractEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.jvm.functions.Function1

class EventsRegistrationTest {
    @Test
    fun `scoped event registration removes only its listener and is idempotent`() {
        val events = Events()
        var calls = 0
        val registration = events.addEventScoped(TestEvent::class.java) { calls++ }

        events.fire(TestEvent::class.java, TestEvent)
        registration.close()
        registration.close()
        events.fire(TestEvent::class.java, TestEvent)

        assertEquals(1, calls)
    }

    @Test
    fun `legacy add event keeps its void jvm descriptor`() {
        val legacy = Events::class.java.getDeclaredMethod("addEvent", Class::class.java, Function1::class.java)
        val scoped = Events::class.java.getDeclaredMethod("addEventScoped", Class::class.java, Function1::class.java)

        assertEquals(Void.TYPE, legacy.returnType)
        assertEquals(AutoCloseable::class.java, scoped.returnType)
    }

    private object TestEvent: AbstractEvent
}
