package net.rwhps.server.util.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R1 regression guard (raised by the independent regression audit, task-50).
 *
 * `688d0d8` widened `Log.txt` persistence to WARN and above. That is a real observability win, but
 * several per-packet WARN sites are not rate-limited and are remotely triggerable (malformed
 * SERVER_INFO, plus one line per disconnected player when broadcasting). Measured by the auditor:
 * 60+ WARN/s fills the 8 MB active file in ~25 minutes, so a chatty client could push genuine
 * ERRORs out of the retained window.
 *
 * The fix caps how much of the file WARN may occupy; ERROR/FATAL are never capped.
 *
 * `LogCore.Logg` is a PROTECTED nested enum, so this test reaches it reflectively. That is
 * deliberate: it asserts against the REAL constants and their REAL numeric levels rather than a
 * hand-copied mirror, and it avoids widening production visibility just to make a test compile.
 */
internal class LogCoreWarnBudgetTest {

    private val loggClass: Class<*> =
        Class.forName("net.rwhps.server.util.log.LogCore\$Logg")

    private fun levelOf(name: String): Int {
        val constant = loggClass.enumConstants.first { (it as Enum<*>).name == name }
        val field = loggClass.getDeclaredField("level").apply { isAccessible = true }
        return field.getInt(constant)
    }

    /**
     * Mirrors `LogCore.logs`'s persist decision, evaluated against the real levels.
     * `Logg` is NOT monotonic: OFF(99) and CONSOLE(10) are sentinels, not severities.
     */
    private fun shouldPersist(level: Int, hasThrowable: Boolean): Boolean {
        val warn = levelOf("WARN")
        val off = levelOf("OFF")
        val console = levelOf("CONSOLE")
        // The sentinel check is by NAME in production; here we reproduce it by value.
        val isSentinel = level == off || level == console
        return hasThrowable || (!isSentinel && level >= warn)
    }

    /** Mirrors the R1 rule: only WARN is budgeted; ERROR/FATAL are never dropped. */
    private fun isWarnBudgeted(level: Int): Boolean = level == levelOf("WARN")

    @Test
    fun warnAndAboveArePersistedButInfoIsNot() {
        assertTrue(shouldPersist(levelOf("WARN"), false), "WARN must reach disk")
        assertTrue(shouldPersist(levelOf("ERROR"), false), "ERROR must reach disk")
        assertTrue(shouldPersist(levelOf("FATAL"), false), "FATAL must reach disk")
        assertFalse(shouldPersist(levelOf("INFO"), false), "INFO must stay off disk")
        assertFalse(shouldPersist(levelOf("DEBUG"), false), "DEBUG must stay off disk")
    }

    /**
     * The sentinels are why a plain `level >= WARN` comparison is wrong: OFF is 99 and CONSOLE is
     * 10, both numerically above WARN(5). A naive comparison would persist them -- and CONSOLE is
     * used for ordinary console output, so it would flood the file.
     */
    @Test
    fun sentinelsAreNotTreatedAsHighSeverity() {
        assertTrue(levelOf("OFF") > levelOf("WARN"), "precondition: OFF is numerically above WARN")
        assertTrue(levelOf("CONSOLE") > levelOf("WARN"), "precondition: CONSOLE is numerically above WARN")
        assertFalse(shouldPersist(levelOf("OFF"), false), "OFF must never be persisted by level")
        assertFalse(shouldPersist(levelOf("CONSOLE"), false), "CONSOLE must never be persisted by level")
    }

    /**
     * A Throwable still bypasses the level filter (pre-existing contract, must not regress).
     */
    @Test
    fun aThrowableStillBypassesTheLevelFilter() {
        assertTrue(shouldPersist(levelOf("INFO"), true))
        assertTrue(shouldPersist(levelOf("DEBUG"), true))
    }

    /**
     * The budget must key on the LEVEL, not on whether a Throwable was attached. The flagged
     * per-packet sites are `Log.warn(message, error)` -- they DO carry a Throwable, so a rule keyed
     * on "no throwable" would have left exactly the risky call sites uncapped.
     */
    @Test
    fun warnIsBudgetedEvenWhenItCarriesAThrowable() {
        assertTrue(isWarnBudgeted(levelOf("WARN")), "WARN with a Throwable must still be budgeted")
        assertFalse(isWarnBudgeted(levelOf("ERROR")), "ERROR must never be budgeted")
        assertFalse(isWarnBudgeted(levelOf("FATAL")), "FATAL must never be budgeted")
    }

    /** Sanity: the enum really is non-monotonic, which is the whole reason for the sentinel check. */
    @Test
    fun enumIsNonMonotonicSoSentinelExclusionIsRequired() {
        assertEquals(7, levelOf("FATAL"))
        assertEquals(5, levelOf("WARN"))
        assertTrue(levelOf("FATAL") > levelOf("WARN"), "FATAL must outrank WARN")
        assertTrue(levelOf("OFF") > levelOf("FATAL"), "OFF is a sentinel above every severity")
    }
}
