/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RelayApiSupportTest {
    @Test
    fun parseQueryParamsDecodesValuesAndFlagParams() {
        val params = RelayApiSupport.parseQueryParams("limit=50&includeClosed=true&name=CNKD+NEXT&empty=&refresh")

        assertEquals("50", params["limit"])
        assertEquals("true", params["includeClosed"])
        assertEquals("CNKD NEXT", params["name"])
        assertEquals("", params["empty"])
        assertEquals("true", params["refresh"])
    }

    @Test
    fun queryHelpersClampAndParseBooleans() {
        val params = RelayApiSupport.parseQueryParams("limit=50000&refresh=1&includeClosed=off")

        assertEquals(100, RelayApiSupport.queryInt(params, "limit", 20, 1, 100))
        assertTrue(RelayApiSupport.shouldForceRefresh(params))
        assertFalse(RelayApiSupport.queryBoolean(params, "includeClosed", defaultValue = true))
    }

    @Test
    fun cachedJsonReturnsHitWithinTtl() {
        RelayApiSupport.resetRuntimeDiagnosticsForTests()
        var buildCount = 0
        val first = RelayApiSupport.cachedJson("test-cache-key", 60_000L, forceRefresh = true) {
            buildCount++
            """{"ok":true,"n":$buildCount}"""
        }
        val second = RelayApiSupport.cachedJson("test-cache-key", 60_000L) {
            buildCount++
            """{"ok":true,"n":$buildCount}"""
        }

        assertFalse(first.cacheHit)
        assertTrue(second.cacheHit)
        assertEquals(first.body, second.body)
        assertEquals(1, buildCount)
    }

    @Test
    fun rateLimitDecisionBlocksAfterConfiguredLimitAndResetsWindow() {
        RelayApiSupport.resetRuntimeDiagnosticsForTests()

        val first = RelayApiSupport.rateLimitDecision(RelayApiSupport.Scope.STATUS, "127.0.0.1", 1_000L, 2, true)
        val second = RelayApiSupport.rateLimitDecision(RelayApiSupport.Scope.STATUS, "127.0.0.1", 1_100L, 2, true)
        val third = RelayApiSupport.rateLimitDecision(RelayApiSupport.Scope.STATUS, "127.0.0.1", 1_200L, 2, true)
        val nextWindow = RelayApiSupport.rateLimitDecision(RelayApiSupport.Scope.STATUS, "127.0.0.1", 62_000L, 2, true)

        assertTrue(first.allowed)
        assertTrue(second.allowed)
        assertFalse(third.allowed)
        assertEquals(2, third.limit)
        assertEquals(0, third.remaining)
        assertTrue(third.retryAfterSeconds > 0)
        assertTrue(nextWindow.allowed)
        assertEquals(1, nextWindow.remaining)
    }

    @Test
    fun metricsTrackRequestsCacheAndErrors() {
        RelayApiSupport.resetRuntimeDiagnosticsForTests()
        RelayApiSupport.recordRequest(RelayApiSupport.Scope.STATUS)
        RelayApiSupport.cachedJson("metrics-cache-key", 60_000L, forceRefresh = true, scope = RelayApiSupport.Scope.STATUS) {
            """{"ok":true}"""
        }
        RelayApiSupport.cachedJson("metrics-cache-key", 60_000L, scope = RelayApiSupport.Scope.STATUS) {
            """{"ok":false}"""
        }
        RelayApiSupport.recordError(RelayApiSupport.Scope.STATUS, IllegalStateException("boom"))

        val statusMetrics = RelayApiSupport.metricsSnapshot().first { it.scope == "status" }

        assertEquals(1, statusMetrics.requestCount)
        assertEquals(1, statusMetrics.cacheMissCount)
        assertEquals(1, statusMetrics.cacheHitCount)
        assertEquals(1, statusMetrics.errorCount)
        assertEquals("boom", statusMetrics.lastError)
    }
}
