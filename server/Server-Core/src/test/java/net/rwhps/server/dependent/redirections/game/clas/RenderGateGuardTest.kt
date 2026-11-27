package net.rwhps.server.dependent.redirections.game.clas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BL-NULL (task-45): `game.i.f()V` sets `bG = false` only AFTER `b(Z)` has nulled `bL` and
 * released monitor `aj`. In between, `aj` is free while the gate is still open, so a render
 * thread can pass the gate and then dereference a null `bL` -- the live NPE that killed the
 * game loop on instance b013.
 *
 * RenderGateGuard closes the gate at f() entry instead. These tests use a stand-in object with
 * the same field shape as the real `gameFramework/l` (a public volatile boolean named bG),
 * because the real engine class is not on the unit-test classpath.
 */
internal class RenderGateGuardTest {

    /** Mirrors `gameFramework/l`: `public volatile boolean bG`. */
    open class FakeEngine {
        @JvmField
        @Volatile
        var bG: Boolean = true
    }

    @Test
    fun closesTheRenderGateAtEntry() {
        RenderGateGuard.resetForTest()
        val engine = FakeEngine()
        assertTrue(engine.bG, "precondition: the gate starts open")

        RenderGateGuard.invoke(engine, "Lcom/corrodinggames/rts/game/i;f()V")

        assertFalse(engine.bG, "the gate must be closed before b(Z) nulls bL")
        assertEquals(1L, RenderGateGuard.gateCloseCount())
    }

    @Test
    fun isIdempotentAcrossRepeatedCalls() {
        RenderGateGuard.resetForTest()
        val engine = FakeEngine()

        repeat(5) { RenderGateGuard.invoke(engine, "Lcom/corrodinggames/rts/game/i;f()V") }

        assertFalse(engine.bG)
        assertEquals(5L, RenderGateGuard.gateCloseCount())
        assertEquals(0L, RenderGateGuard.gateCloseFailureCount())
    }

    /**
     * The guard sits on the retraction path, so it must never throw -- a throw here would abort
     * the retraction sequence mid-way, which is worse than the race it prevents.
     */
    @Test
    fun neverThrowsWhenTheFieldIsMissing() {
        RenderGateGuard.resetForTest()
        val wrongShape = Object() // has no `bG`

        RenderGateGuard.invoke(wrongShape, "Lcom/corrodinggames/rts/game/i;f()V")

        assertEquals(0L, RenderGateGuard.gateCloseCount())
        assertEquals(1L, RenderGateGuard.gateCloseFailureCount(), "the failure must be counted")
    }

    @Test
    fun resolvesTheFieldThroughTheSuperclassLikeTheRealEngine() {
        RenderGateGuard.resetForTest()
        // The real bG is declared on the SUPERCLASS gameFramework/l and read via getField,
        // so a subclass instance must still resolve it.
        val engine: FakeEngine = SubclassEngine()

        RenderGateGuard.invoke(engine, "Lcom/corrodinggames/rts/game/i;f()V")

        assertFalse(engine.bG)
        assertEquals(1L, RenderGateGuard.gateCloseCount())
    }

    /**
     * A cached failure would disable the guard for the rest of the process, and this guard exists
     * to make a rare race impossible -- so a resolution failure must not be permanent. Once a
     * well-shaped object appears, the gate must close again.
     */
    @Test
    fun recoversAfterAnEarlierResolutionFailure() {
        RenderGateGuard.resetForTest()
        RenderGateGuard.invoke(Object(), "Lcom/corrodinggames/rts/game/i;f()V")
        assertEquals(1L, RenderGateGuard.gateCloseFailureCount())

        val engine = FakeEngine()
        RenderGateGuard.invoke(engine, "Lcom/corrodinggames/rts/game/i;f()V")

        assertFalse(engine.bG, "the guard must not stay disabled after a transient failure")
        assertEquals(1L, RenderGateGuard.gateCloseCount())
    }

    /**
     * Once resolved successfully the field is cached, so repeated calls keep working even if a
     * later object is of a different shape.
     */
    @Test
    fun keepsWorkingFromCacheAfterASuccessfulResolution() {
        RenderGateGuard.resetForTest()
        RenderGateGuard.invoke(FakeEngine(), "Lcom/corrodinggames/rts/game/i;f()V")

        val second = FakeEngine()
        RenderGateGuard.invoke(second, "Lcom/corrodinggames/rts/game/i;f()V")

        assertFalse(second.bG)
        assertEquals(2L, RenderGateGuard.gateCloseCount())
    }

    class SubclassEngine : FakeEngine()
}
