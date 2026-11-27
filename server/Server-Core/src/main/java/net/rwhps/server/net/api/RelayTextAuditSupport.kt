/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.log.Log.error
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal data class RelayTextAuditDecision(
    val allowed: Boolean,
    val text: String,
    val reason: String
)

internal data class RelayTextAuditRuntimeStatus(
    val enabled: Boolean,
    val providerName: String,
    val endpointConfigured: Boolean,
    val allowOnError: Boolean,
    val denyPublicRoomWhenRejected: Boolean,
    val lastReason: String,
    val lastMessage: String,
    val lastTimestamp: Long
)

internal object RelayTextAuditSupport {
    private const val MAX_CACHE_ENTRIES = 512

    private data class CacheEntry(
        val expiresAt: Long,
        val decision: RelayTextAuditDecision
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    @Volatile
    private var lastReason = "not_started"
    @Volatile
    private var lastMessage = "文本审核尚未触发"
    @Volatile
    private var lastTimestamp = 0L

    fun runtimeStatus(): RelayTextAuditRuntimeStatus {
        val config = Data.configRelayTextAudit
        return RelayTextAuditRuntimeStatus(
            enabled = config.enable,
            providerName = config.providerName,
            endpointConfigured = config.endpoint.isNotBlank(),
            allowOnError = config.allowOnError,
            denyPublicRoomWhenRejected = config.denyPublicRoomWhenRejected,
            lastReason = lastReason,
            lastMessage = lastMessage,
            lastTimestamp = lastTimestamp
        )
    }

    fun auditPublicText(field: String, text: String): RelayTextAuditDecision {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            remember("blank", "文本为空，已跳过审核")
            return RelayTextAuditDecision(true, "", "blank")
        }

        val config = Data.configRelayTextAudit
        if (!config.enable) {
            remember("disabled", "文本审核未启用")
            return RelayTextAuditDecision(true, normalized, "disabled")
        }
        if (config.endpoint.isBlank()) {
            remember("endpoint_not_configured", "文本审核已启用，但 endpoint 为空")
            return RelayTextAuditDecision(true, normalized, "endpoint_not_configured")
        }

        val cacheKey = "$field::$normalized"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.takeIf { now <= it.expiresAt }?.let {
            remember("cache_hit:${it.decision.reason}", "命中缓存字段: $field")
            return it.decision
        }

        val fallbackDecision = RelayTextAuditDecision(
            allowed = config.allowOnError,
            text = if (config.allowOnError) normalized else config.rejectFallbackText,
            reason = "error"
        )

        return try {
            val requestBody = config.requestTemplate
                .replace("{text_json}", JSONObject.quote(normalized))
                .replace("{field_json}", JSONObject.quote(field))
                .replace("{provider_json}", JSONObject.quote(config.providerName))
                .replace("{text}", normalized)
                .replace("{field}", field)
                .replace("{provider}", config.providerName)
            val response = if (config.headers.isEmpty()) {
                Data.core.http.doPostJson(config.endpoint, requestBody)
            } else {
                Data.core.http.doPostJson(config.endpoint, config.headers, requestBody)
            }
            if (response.isBlank()) {
                cacheResult(cacheKey, fallbackDecision)
                remember(
                    "empty_response",
                    "文本审核接口未返回内容，已按 allowOnError=${config.allowOnError} 兜底"
                )
                fallbackDecision
            } else {
                val decision = parseDecision(normalized, response)
                cacheResult(cacheKey, decision)
                remember(
                    decision.reason,
                    "文本审核完成，字段=$field，allowed=${decision.allowed}"
                )
                decision
            }
        } catch (e: Exception) {
            error("[RelayTextAudit] endpoint=${config.endpoint}", e)
            cacheResult(cacheKey, fallbackDecision)
            remember(
                "request_error",
                buildExceptionMessage("文本审核请求失败", e)
            )
            fallbackDecision
        }
    }

    private fun parseDecision(originalText: String, response: String): RelayTextAuditDecision {
        val config = Data.configRelayTextAudit
        val root = JSONObject(response)

        if (config.responseBlockPath.isNotBlank()) {
            val blockValue = readPath(root, config.responseBlockPath)
            if (matchesValue(blockValue, config.responseBlockValues)) {
                return RelayTextAuditDecision(false, config.rejectFallbackText, "rejected")
            }
        }

        if (config.responseAllowPath.isNotBlank()) {
            val allowValue = readPath(root, config.responseAllowPath)
            if (!matchesValue(allowValue, config.responseAllowValues)) {
                return RelayTextAuditDecision(false, config.rejectFallbackText, "rejected")
            }
        }

        val auditedText = config.responseTextPath
            .takeIf { it.isNotBlank() }
            ?.let { readPath(root, it)?.toString()?.trim() }
            ?.takeIf { it.isNotBlank() }
            ?: originalText

        return RelayTextAuditDecision(true, auditedText, "allowed")
    }

    private fun remember(reason: String, message: String) {
        lastReason = reason
        lastMessage = message.take(300)
        lastTimestamp = System.currentTimeMillis()
    }

    private fun buildExceptionMessage(prefix: String, e: Exception): String {
        val exceptionName = e::class.java.simpleName.ifBlank { "Exception" }
        val suffix = e.message?.trim().takeIf { !it.isNullOrBlank() } ?: "无额外异常信息"
        return "$prefix: $exceptionName - $suffix"
    }

    private fun cacheResult(key: String, decision: RelayTextAuditDecision) {
        val seconds = Data.configRelayTextAudit.cacheSeconds.coerceIn(0, 3600)
        if (seconds <= 0) {
            return
        }
        pruneCacheIfNeeded(System.currentTimeMillis())
        cache[key] = CacheEntry(System.currentTimeMillis() + seconds * 1000L, decision)
    }

    private fun pruneCacheIfNeeded(now: Long) {
        if (cache.size < MAX_CACHE_ENTRIES) {
            return
        }

        cache.entries.removeIf { (_, entry) -> now > entry.expiresAt }
        while (cache.size >= MAX_CACHE_ENTRIES) {
            val oldestKey = cache.entries.minByOrNull { it.value.expiresAt }?.key ?: return
            cache.remove(oldestKey)
        }
    }

    private fun matchesValue(value: Any?, allowValues: String): Boolean {
        val normalizedAllowValues = allowValues
            .split(',', ';', '\n', '\r')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedAllowValues.isEmpty()) {
            return false
        }
        return value?.toString()?.trim()?.lowercase() in normalizedAllowValues
    }

    private fun readPath(root: Any?, path: String): Any? {
        var current: Any? = root
        for (segment in path.split('.').map { it.trim() }.filter { it.isNotEmpty() }) {
            current = when (current) {
                is JSONObject -> current.opt(segment)
                is JSONArray -> segment.toIntOrNull()?.let { index ->
                    if (index in 0 until current.length()) current.opt(index) else null
                }
                else -> null
            }
            if (current == null || current == JSONObject.NULL) {
                return null
            }
        }
        return current
    }
}
