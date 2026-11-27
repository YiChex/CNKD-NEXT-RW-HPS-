package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.bean.BeanCoreConfig
import net.rwhps.server.data.bean.BeanServerConfig
import net.rwhps.server.data.bean.BeanServerConfigFile
import net.rwhps.server.data.bean.BeanStartModeConfig
import net.rwhps.server.util.inline.toGson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StartCountdownPolicyTest {
    @Test
    fun missingConfigurationUsesFiveSecondsAndRoundTripsIndependentlyFromAutomaticStart() {
        val oldConfig = BeanServerConfigFile::class.java.toGson("{\"gameplay\":{\"autoStartCountdownSeconds\":9}}")
        assertEquals(5, oldConfig.toServerConfig().manualStartCountdownSeconds)
        assertEquals(9, oldConfig.toServerConfig().autoStartCountdownSeconds)
        val roundTrip = BeanServerConfigFile.fromCurrent(BeanCoreConfig(),
            BeanServerConfig(manualStartCountdownSeconds = 2, autoStartCountdownSeconds = 7), BeanStartModeConfig())
        assertEquals(2, roundTrip.gameplay.manualStartCountdownSeconds)
        assertEquals(7, roundTrip.toServerConfig().autoStartCountdownSeconds)
        assertEquals(5, BeanServerConfig().manualStartCountdownSeconds)
    }

    @Test
    fun boundsIncludeImmediateAndFiveMinutes() {
        fun normalized(value: Int) = BeanServerConfigFile(gameplay = BeanServerConfigFile.GameplaySection(
            manualStartCountdownSeconds = value)).toServerConfig().manualStartCountdownSeconds
        assertEquals(0, normalized(-1))
        assertEquals(0, normalized(0))
        assertEquals(300, normalized(Int.MAX_VALUE))
    }

    @Test
    fun elapsedTimeNeverStartsBeforeTheMonotonicDeadline() {
        val start = 1_000_000_000L
        val deadline = start + 5_000_000_000L
        assertEquals(5, StartCountdownPolicy.remainingSeconds(deadline, start))
        assertEquals(5, StartCountdownPolicy.remainingSeconds(deadline, start + 1L))
        assertEquals(4, StartCountdownPolicy.remainingSeconds(deadline, start + 1_000_000_000L))
        assertEquals(1, StartCountdownPolicy.remainingSeconds(deadline, deadline - 1L))
        assertEquals(0, StartCountdownPolicy.remainingSeconds(deadline, deadline))
        assertEquals(0, StartCountdownPolicy.remainingSeconds(deadline, deadline + 1L))
    }
}
