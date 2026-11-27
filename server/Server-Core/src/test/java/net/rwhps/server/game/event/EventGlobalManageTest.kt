package net.rwhps.server.game.event

import net.rwhps.server.game.event.core.EventListenerHost
import net.rwhps.server.game.event.global.TestBGlobalEvent
import net.rwhps.server.game.event.global.TestGlobalEvent
import net.rwhps.server.util.Time
import net.rwhps.server.util.annotations.core.EventListenerHandler
import org.junit.jupiter.api.Test

/**
 *
 *
 * @date 2023/10/8 16:08
 * @author Dr (dr@der.kim)
 */
class EventGlobalManageTest {
    @Test
    fun test() {
        val event = EventGlobalManage()
        event.registerListener(GlobalEventListen())

        println(Time.getMilliFormat(1))
        for (i in 0 until 1000) {
            event.fire(TestGlobalEvent())
            event.fire(TestBGlobalEvent())
        }
        event.fire(TestGlobalEvent()).await()
        println(Time.getMilliFormat(1))

        println(Time.getMilliFormat(1))
        for (i in 0 until 1000) {
            event.fire(TestGlobalEvent())
            event.fire(TestBGlobalEvent())
        }
        event.fire(TestGlobalEvent()).await()
        println(Time.getMilliFormat(1))
    }

    private class GlobalEventListen : EventListenerHost {
        @EventListenerHandler
        fun registerTestEvent(test: TestGlobalEvent) {
            Thread.sleep(1000)
        }

        @EventListenerHandler
        fun registerTestBEvent(test: TestBGlobalEvent) {
            Thread.sleep(1000)
        }
    }
}