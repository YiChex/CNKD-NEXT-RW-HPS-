/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.AsciiString
import net.rwhps.server.data.global.Data
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.util.inline.toJson
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal object RelayApiSupport {
    private const val MAX_API_CACHE_ENTRIES = 64
    private const val RATE_LIMIT_WINDOW_MILLIS = 60_000L
    private const val MAX_RATE_LIMIT_STATES = 4096
    private val jsonResponseCache = ConcurrentHashMap<String, CachedJsonResponse>()
    private val rateLimitStates = ConcurrentHashMap<String, RateLimitState>()
    private val scopeMetrics = ConcurrentHashMap<Scope, ScopeMetrics>()

    enum class Scope {
        STATUS,
        ROOMS,
        ADMIN
    }

    data class CachedJsonResult(
        val body: String,
        val cacheHit: Boolean,
        val ageMillis: Long,
        val ttlMillis: Long
    )

    data class RateLimitDecision(
        val allowed: Boolean,
        val limit: Int,
        val remaining: Int,
        val retryAfterSeconds: Long
    )

    data class ApiMetricsSnapshot(
        val scope: String,
        val requestCount: Long,
        val deniedCount: Long,
        val rateLimitedCount: Long,
        val cacheHitCount: Long,
        val cacheMissCount: Long,
        val errorCount: Long,
        val buildCount: Long,
        val averageBuildMillis: Long,
        val maxBuildMillis: Long,
        val lastRequestAtMillis: Long,
        val lastErrorAtMillis: Long,
        val lastError: String
    )

    fun applyCors(accept: AcceptWeb, send: SendWeb) {
        if (!Data.configRelayApi.corsEnable) {
            return
        }

        val requestOrigin = accept.getHeaders(HttpHeaderNames.ORIGIN) ?: ""
        val allowOrigin = when {
            Data.configRelayApi.corsAllowAllOrigins && !Data.configRelayApi.corsAllowCredentials -> "*"
            Data.configRelayApi.corsAllowAllOrigins && requestOrigin.isNotBlank() -> requestOrigin
            else -> {
                val origins = Data.configRelayApi.corsAllowOrigins
                    .split(',', ';', '\n', '\r')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (requestOrigin.isNotBlank() && requestOrigin in origins) requestOrigin else null
            }
        }

        if (allowOrigin != null) {
            send.setHead(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowOrigin)
            send.setHead(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, Data.configRelayApi.corsAllowMethods)
            send.setHead(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, Data.configRelayApi.corsAllowHeaders)
            send.setHead(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, Data.configRelayApi.corsMaxAgeSeconds.toString())
            send.setHead(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
            send.setHead(HttpHeaderNames.VARY, "Origin")
            if (Data.configRelayApi.corsAllowCredentials) {
                send.setHead(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
            }
        }
    }

    fun applyJsonHeaders(accept: AcceptWeb, send: SendWeb, cache: CachedJsonResult? = null) {
        applyCors(accept, send)
        send.setConnectType("application/json")
        send.setHead(HttpHeaderNames.CACHE_CONTROL, cacheControlHeader(cache?.ttlMillis ?: 0L))
        send.setHead(HttpHeaderNames.VARY, "Origin")
        if (cache != null) {
            send.setHead(AsciiString.cached("X-Relay-Api-Cache"), if (cache.cacheHit) "HIT" else "MISS")
            send.setHead(AsciiString.cached("X-Relay-Api-Cache-Age-Millis"), cache.ageMillis.toString())
            send.setHead(AsciiString.cached("X-Relay-Api-Cache-Ttl-Millis"), cache.ttlMillis.toString())
        }
    }

    fun statusCacheTtlMillis(): Long = Data.configRelayApi.statusApiCacheMillis.coerceIn(0, 60_000).toLong()

    fun roomsCacheTtlMillis(): Long = Data.configRelayApi.roomsApiCacheMillis.coerceIn(0, 60_000).toLong()

    fun cachedJson(
        cacheKey: String,
        ttlMillis: Long,
        forceRefresh: Boolean = false,
        scope: Scope? = null,
        build: () -> String
    ): CachedJsonResult {
        val now = System.currentTimeMillis()
        if (!forceRefresh && ttlMillis > 0L) {
            jsonResponseCache[cacheKey]?.let { cached ->
                val ageMillis = now - cached.createdAtMillis
                if (ageMillis in 0 until ttlMillis) {
                    recordCache(scope, cacheHit = true)
                    return CachedJsonResult(cached.body, cacheHit = true, ageMillis = ageMillis, ttlMillis = ttlMillis)
                }
            }
        }

        synchronized(jsonResponseCache) {
            val lockedNow = System.currentTimeMillis()
            if (!forceRefresh && ttlMillis > 0L) {
                jsonResponseCache[cacheKey]?.let { cached ->
                    val ageMillis = lockedNow - cached.createdAtMillis
                    if (ageMillis in 0 until ttlMillis) {
                        recordCache(scope, cacheHit = true)
                        return CachedJsonResult(cached.body, cacheHit = true, ageMillis = ageMillis, ttlMillis = ttlMillis)
                    }
                }
            }

            val buildStartedAt = System.nanoTime()
            val body = build()
            recordBuildMillis(scope, (System.nanoTime() - buildStartedAt) / 1_000_000L)
            if (ttlMillis > 0L) {
                if (jsonResponseCache.size >= MAX_API_CACHE_ENTRIES) {
                    pruneExpiredCache(lockedNow)
                }
                if (jsonResponseCache.size >= MAX_API_CACHE_ENTRIES) {
                    jsonResponseCache.clear()
                }
                jsonResponseCache[cacheKey] = CachedJsonResponse(body, lockedNow)
            }
            recordCache(scope, cacheHit = false)
            return CachedJsonResult(body, cacheHit = false, ageMillis = 0L, ttlMillis = ttlMillis)
        }
    }

    fun invalidateCachedResponses() {
        jsonResponseCache.clear()
    }

    fun queryParams(accept: AcceptWeb): Map<String, String> = parseQueryParams(accept.urlData)

    fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) {
            return emptyMap()
        }

        val params = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { part ->
            if (part.isBlank()) {
                return@forEach
            }
            val separatorIndex = part.indexOf('=')
            val rawKey = if (separatorIndex >= 0) part.substring(0, separatorIndex) else part
            val rawValue = if (separatorIndex >= 0) part.substring(separatorIndex + 1) else "true"
            val key = decodeQueryComponent(rawKey).trim()
            if (key.isBlank()) {
                return@forEach
            }
            params[key] = decodeQueryComponent(rawValue).trim()
        }
        return params
    }

    fun queryInt(params: Map<String, String>, key: String, defaultValue: Int, min: Int, max: Int): Int {
        return params[key]
            ?.toIntOrNull()
            ?.coerceIn(min, max)
            ?: defaultValue.coerceIn(min, max)
    }

    fun queryBoolean(params: Map<String, String>, key: String, defaultValue: Boolean = false): Boolean {
        val raw = params[key]?.trim()?.lowercase(Locale.ROOT) ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> defaultValue
        }
    }

    fun shouldForceRefresh(params: Map<String, String>): Boolean {
        return queryBoolean(params, "refresh") || queryBoolean(params, "force") || queryBoolean(params, "nocache")
    }

    fun recordRequest(scope: Scope) {
        metrics(scope).requestCount.incrementAndGet()
        metrics(scope).lastRequestAtMillis.set(System.currentTimeMillis())
    }

    fun recordError(scope: Scope, throwable: Throwable) {
        val metric = metrics(scope)
        metric.errorCount.incrementAndGet()
        metric.lastErrorAtMillis.set(System.currentTimeMillis())
        metric.lastError.set(throwable.message ?: throwable::class.java.simpleName)
    }

    fun enforceRateLimit(scope: Scope, accept: AcceptWeb, send: SendWeb): Boolean {
        val decision = rateLimitDecision(
            scope = scope,
            remoteIp = accept.remoteIp,
            nowMillis = System.currentTimeMillis(),
            enabled = Data.configRelayApi?.apiRateLimitEnabled ?: true,
            limitPerMinute = rateLimitPerMinute(scope)
        )
        applyRateLimitHeaders(send, decision)
        if (decision.allowed) {
            return true
        }

        metrics(scope).rateLimitedCount.incrementAndGet()
        send.status = HttpResponseStatus.TOO_MANY_REQUESTS
        applyJsonHeaders(accept, send)
        send.setData(
            mapOf(
                "ok" to false,
                "scope" to scope.name.lowercase(),
                "error" to "rate_limited",
                "message" to "too many requests, please retry later",
                "retryAfterSeconds" to decision.retryAfterSeconds
            ).toJson()
        )
        send.send()
        return false
    }

    fun metricsSnapshot(): List<ApiMetricsSnapshot> {
        return Scope.values().map { scope ->
            val metric = metrics(scope)
            val buildCount = metric.buildCount.get()
            val totalBuildMillis = metric.totalBuildMillis.get()
            ApiMetricsSnapshot(
                scope = scope.name.lowercase(),
                requestCount = metric.requestCount.get(),
                deniedCount = metric.deniedCount.get(),
                rateLimitedCount = metric.rateLimitedCount.get(),
                cacheHitCount = metric.cacheHitCount.get(),
                cacheMissCount = metric.cacheMissCount.get(),
                errorCount = metric.errorCount.get(),
                buildCount = buildCount,
                averageBuildMillis = if (buildCount > 0L) totalBuildMillis / buildCount else 0L,
                maxBuildMillis = metric.maxBuildMillis.get(),
                lastRequestAtMillis = metric.lastRequestAtMillis.get(),
                lastErrorAtMillis = metric.lastErrorAtMillis.get(),
                lastError = metric.lastError.get()
            )
        }
    }

    fun buildDiagnostics(): List<String> {
        val lines = mutableListOf<String>()
        lines += "========== RELAY API =========="
        lines += "cache.entries=${jsonResponseCache.size}, rateLimit.states=${rateLimitStates.size}"
        lines += "rateLimit.enabled=${runCatching { Data.configRelayApi.apiRateLimitEnabled }.getOrDefault(true)}"
        lines += "rateLimit.statusPerMinute=${rateLimitPerMinute(Scope.STATUS)}"
        lines += "rateLimit.roomsPerMinute=${rateLimitPerMinute(Scope.ROOMS)}"
        lines += "rateLimit.adminPerMinute=${rateLimitPerMinute(Scope.ADMIN)}"
        metricsSnapshot().forEach { metric ->
            lines += "scope.${metric.scope}.requests=${metric.requestCount}, denied=${metric.deniedCount}, rateLimited=${metric.rateLimitedCount}, errors=${metric.errorCount}"
            lines += "scope.${metric.scope}.cache.hit=${metric.cacheHitCount}, cache.miss=${metric.cacheMissCount}, build.avgMs=${metric.averageBuildMillis}, build.maxMs=${metric.maxBuildMillis}"
            if (metric.lastErrorAtMillis > 0L) {
                lines += "scope.${metric.scope}.lastErrorAt=${metric.lastErrorAtMillis}, lastError=${metric.lastError}"
            }
        }
        return lines
    }

    internal fun rateLimitDecision(
        scope: Scope,
        remoteIp: String,
        nowMillis: Long,
        limitPerMinute: Int,
        enabled: Boolean
    ): RateLimitDecision {
        val safeLimit = limitPerMinute.coerceIn(1, 100_000)
        if (!enabled) {
            return RateLimitDecision(allowed = true, limit = safeLimit, remaining = safeLimit, retryAfterSeconds = 0L)
        }

        if (rateLimitStates.size > MAX_RATE_LIMIT_STATES) {
            pruneRateLimitStates(nowMillis)
        }

        val key = "${scope.name}:${remoteIp.ifBlank { "unknown" }}"
        val state = rateLimitStates.computeIfAbsent(key) {
            RateLimitState(AtomicLong(nowMillis), AtomicInteger(0))
        }
        resetRateLimitWindowIfNeeded(state, nowMillis)

        val count = state.count.incrementAndGet()
        val allowed = count <= safeLimit
        val remaining = max(safeLimit - count, 0)
        val windowStart = state.windowStartMillis.get()
        val retryAfterMillis = max(windowStart + RATE_LIMIT_WINDOW_MILLIS - nowMillis, 0L)
        return RateLimitDecision(
            allowed = allowed,
            limit = safeLimit,
            remaining = remaining,
            retryAfterSeconds = if (allowed) 0L else ((retryAfterMillis + 999L) / 1000L).coerceAtLeast(1L)
        )
    }

    internal fun resetRuntimeDiagnosticsForTests() {
        jsonResponseCache.clear()
        rateLimitStates.clear()
        scopeMetrics.clear()
    }

    fun isAllowed(scope: Scope, remoteIp: String): Boolean {
        if (!isScopeEnabled(scope)) {
            return false
        }

        val requireWhitelist = when (scope) {
            Scope.STATUS -> Data.configRelayApi.statusApiRequireWhitelist
            Scope.ROOMS -> Data.configRelayApi.roomsApiRequireWhitelist
            Scope.ADMIN -> Data.configRelayApi.adminApiRequireWhitelist
        }

        if (!requireWhitelist) {
            return true
        }

        val whitelist = when (scope) {
            Scope.STATUS -> Data.configRelayApi.statusApiIpWhitelist
            Scope.ROOMS -> Data.configRelayApi.roomsApiIpWhitelist
            Scope.ADMIN -> Data.configRelayApi.adminApiIpWhitelist
        }

        val normalized = whitelist
            .split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return normalized.isNotEmpty() && remoteIp in normalized
    }

    fun isScopeEnabled(scope: Scope): Boolean {
        return when (scope) {
            Scope.STATUS -> true
            Scope.ROOMS -> RelayLicenseSupport.allowRoomsApi()
            Scope.ADMIN -> RelayLicenseSupport.allowAdminApi()
        }
    }

    fun deny(scope: Scope, accept: AcceptWeb, send: SendWeb) {
        metrics(scope).deniedCount.incrementAndGet()
        send.status = HttpResponseStatus.FORBIDDEN
        applyJsonHeaders(accept, send)
        send.setData(
            mapOf(
                "ok" to false,
                "scope" to scope.name.lowercase(),
                "error" to "forbidden",
                "message" to "IP ${accept.remoteIp} is not allowed"
            ).toJson()
        )
        send.send()
    }

    fun denyDisabled(scope: Scope, accept: AcceptWeb, send: SendWeb) {
        metrics(scope).deniedCount.incrementAndGet()
        send.status = HttpResponseStatus.FORBIDDEN
        applyJsonHeaders(accept, send)
        val message = when (scope) {
            Scope.STATUS -> "status api is always available"
            Scope.ROOMS -> RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.ROOMS_API)
            Scope.ADMIN -> RelayLicenseSupport.friendlyDenyMessage(RelayLicenseSupport.Feature.ADMIN_API)
        }
        send.setData(
            mapOf(
                "ok" to false,
                "scope" to scope.name.lowercase(),
                "error" to "feature_disabled",
                "message" to message
            ).toJson()
        )
        send.send()
    }

    fun isAuthorized(scope: Scope, accept: AcceptWeb): Boolean {
        if (scope != Scope.ADMIN) {
            return true
        }

        val configuredToken = Data.configRelayApi.adminApiToken.trim()
        if (configuredToken.isBlank()) {
            return false
        }

        val authorization = accept.getHeaders(HttpHeaderNames.AUTHORIZATION).orEmpty().trim()
        val bearerToken = authorization.removePrefix("Bearer").trim()
        val headerToken = accept.getHeaders("X-Relay-Admin-Token").orEmpty().trim()
        return configuredToken == bearerToken || configuredToken == headerToken
    }

    fun denyUnauthorized(scope: Scope, accept: AcceptWeb, send: SendWeb) {
        metrics(scope).deniedCount.incrementAndGet()
        send.status = HttpResponseStatus.UNAUTHORIZED
        applyJsonHeaders(accept, send)
        send.setData(
            mapOf(
                "ok" to false,
                "scope" to scope.name.lowercase(),
                "error" to "unauthorized",
                "message" to "admin token is invalid or not configured"
            ).toJson()
        )
        send.send()
    }

    private fun cacheControlHeader(ttlMillis: Long): String {
        if (ttlMillis <= 0L) {
            return "no-store, no-cache, must-revalidate, max-age=0"
        }
        return "private, max-age=${(ttlMillis / 1000L).coerceAtLeast(1L)}"
    }

    private fun pruneExpiredCache(now: Long) {
        jsonResponseCache.entries.removeIf { (_, cached) ->
            now - cached.createdAtMillis > 60_000L
        }
    }

    private fun rateLimitPerMinute(scope: Scope): Int {
        return runCatching {
            when (scope) {
                Scope.STATUS -> Data.configRelayApi.statusApiRateLimitPerMinute
                Scope.ROOMS -> Data.configRelayApi.roomsApiRateLimitPerMinute
                Scope.ADMIN -> Data.configRelayApi.adminApiRateLimitPerMinute
            }
        }.getOrDefault(
            when (scope) {
                Scope.STATUS -> 120
                Scope.ROOMS -> 60
                Scope.ADMIN -> 30
            }
        ).coerceIn(1, 100_000)
    }

    private fun applyRateLimitHeaders(send: SendWeb, decision: RateLimitDecision) {
        send.setHead(AsciiString.cached("X-Relay-Api-RateLimit-Limit"), decision.limit.toString())
        send.setHead(AsciiString.cached("X-Relay-Api-RateLimit-Remaining"), decision.remaining.toString())
        if (!decision.allowed) {
            send.setHead(AsciiString.cached("Retry-After"), decision.retryAfterSeconds.toString())
        }
    }

    private fun resetRateLimitWindowIfNeeded(state: RateLimitState, nowMillis: Long) {
        while (true) {
            val windowStart = state.windowStartMillis.get()
            if (nowMillis >= windowStart && nowMillis - windowStart < RATE_LIMIT_WINDOW_MILLIS) {
                return
            }
            if (state.windowStartMillis.compareAndSet(windowStart, nowMillis)) {
                state.count.set(0)
                return
            }
        }
    }

    private fun pruneRateLimitStates(nowMillis: Long) {
        rateLimitStates.entries.removeIf { (_, state) ->
            nowMillis - state.windowStartMillis.get() > RATE_LIMIT_WINDOW_MILLIS * 5
        }
        if (rateLimitStates.size > MAX_RATE_LIMIT_STATES) {
            rateLimitStates.clear()
        }
    }

    private fun recordCache(scope: Scope?, cacheHit: Boolean) {
        val metric = scope?.let(::metrics) ?: return
        if (cacheHit) {
            metric.cacheHitCount.incrementAndGet()
        } else {
            metric.cacheMissCount.incrementAndGet()
        }
    }

    private fun recordBuildMillis(scope: Scope?, millis: Long) {
        val metric = scope?.let(::metrics) ?: return
        val safeMillis = millis.coerceAtLeast(0L)
        metric.buildCount.incrementAndGet()
        metric.totalBuildMillis.addAndGet(safeMillis)
        metric.maxBuildMillis.updateAndGet { current -> max(current, safeMillis) }
    }

    private fun metrics(scope: Scope): ScopeMetrics {
        return scopeMetrics.computeIfAbsent(scope) { ScopeMetrics() }
    }

    private fun decodeQueryComponent(value: String): String {
        return try {
            URLDecoder.decode(value, Data.UTF_8)
        } catch (_: Exception) {
            value
        }
    }

    private data class CachedJsonResponse(
        val body: String,
        val createdAtMillis: Long
    )

    private data class RateLimitState(
        val windowStartMillis: AtomicLong,
        val count: AtomicInteger
    )

    private class ScopeMetrics {
        val requestCount = AtomicLong(0L)
        val deniedCount = AtomicLong(0L)
        val rateLimitedCount = AtomicLong(0L)
        val cacheHitCount = AtomicLong(0L)
        val cacheMissCount = AtomicLong(0L)
        val errorCount = AtomicLong(0L)
        val buildCount = AtomicLong(0L)
        val totalBuildMillis = AtomicLong(0L)
        val maxBuildMillis = AtomicLong(0L)
        val lastRequestAtMillis = AtomicLong(0L)
        val lastErrorAtMillis = AtomicLong(0L)
        val lastError = java.util.concurrent.atomic.AtomicReference("")
    }
}
