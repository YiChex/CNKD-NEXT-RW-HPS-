/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.manage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HttpCallResultTest {
    @Test
    fun preservesHttpFailureStatusForCallers() {
        val result = HttpCallResult(
            statusCode = 500,
            body = "",
            statusMessage = "Internal Server Error"
        )

        assertFalse(result.successful)
        assertEquals("HTTP_500:Internal Server Error", result.diagnosticResponse())
    }

    @Test
    fun bodyTakesPriorityForProtocolResponses() {
        val result = HttpCallResult(
            statusCode = 200,
            body = "CORRODINGGAMES[1.0][FAILED]",
            statusMessage = "OK"
        )

        assertTrue(result.successful)
        assertEquals("CORRODINGGAMES[1.0][FAILED]", result.diagnosticResponse())
    }

    @Test
    fun reportsTransportErrorsSeparately() {
        val result = HttpCallResult(
            statusCode = null,
            body = "",
            errorMessage = "timeout"
        )

        assertFalse(result.successful)
        assertEquals("NETWORK_ERROR:timeout", result.diagnosticResponse())
    }
}
