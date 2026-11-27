package net.rwhps.server.plugin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * @date 2023/7/27 8:56
 * @author Dr (dr@der.kim)
 */
class GetVersionTest {
    @Test
    fun test() {
        testIn(GetVersion("1.0.0"))
        testIn(GetVersion("1.0.0-M1"))
        testIn(GetVersion("1.0.0-M1+DEV1"))
        testIn(GetVersion("1.0.0-RC"))
        testIn(GetVersion("1.0.0-M"))
    }

    private fun testIn(version: GetVersion) {
        println(version.toString())
    }
}