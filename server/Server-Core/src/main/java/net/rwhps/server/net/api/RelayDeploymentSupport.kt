/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.data.bean.BeanRelayNodeConfig
import net.rwhps.server.data.bean.BeanRelayRoomCodeScheme
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.room.RelayRoom
import java.util.Locale

internal object RelayDeploymentSupport {
    private fun normalizedMainId(): String = Data.configRelay.mainID.trim()

    private fun localNode(): BeanRelayNodeConfig? {
        val code = Data.configRelay.localNodeCode.trim()
        if (code.isEmpty()) {
            return null
        }
        return RelayNodeRoutingSupport.findNode(code)
    }

    private fun roomProfile(roomId: String): RelayCustomRoomProfile? {
        return RelayCustomRoomSupport.findAppliedProfile(roomId)
    }

    private fun roomNodeCode(roomId: String): String {
        return roomProfile(roomId)?.nodeCode?.takeIf { it.isNotBlank() } ?: localNodeCode()
    }

    fun displayName(): String {
        return Data.configRelay.self.displayName.ifBlank {
            localNode()?.displayName?.takeIf { it.isNotBlank() }
                ?: localNode()?.name?.takeIf { it.isNotBlank() }
                ?: "CNKD 上海节点"
        }
    }

    fun shortName(): String {
        return Data.configRelay.self.shortName.ifBlank {
            localNode()?.shortName?.takeIf { it.isNotBlank() } ?: "CNKD"
        }
    }

    fun region(): String {
        return Data.configRelay.self.region.ifBlank {
            localNode()?.region?.takeIf { it.isNotBlank() } ?: "中国上海"
        }
    }

    fun operatorName(): String {
        return Data.configRelay.self.operatorName.ifBlank {
            localNode()?.operatorName?.takeIf { it.isNotBlank() } ?: "CNKD团队"
        }
    }

    fun qqGroup(): String {
        return Data.configRelay.self.qqGroup.ifBlank {
            localNode()?.qqGroup?.takeIf { it.isNotBlank() } ?: ""
        }
    }

    fun listOpenText(): String {
        return Data.configRelay.self.listOpenText.ifBlank {
            localNode()?.listOpenText?.takeIf { it.isNotBlank() } ?: "支持列表开放"
        }
    }

    fun joinBaseUrl(): String {
        return Data.configRelay.self.joinBaseUrl.ifBlank {
            localNode()?.joinBaseUrl?.takeIf { it.isNotBlank() } ?: ""
        }.trim().trimEnd('/')
    }

    private data class ConfiguredRoomCodeScheme(
        val id: String,
        val prefix: String,
        val withNodeCode: Boolean
    )

    private data class ParsedRoomCode(
        val scheme: ConfiguredRoomCodeScheme,
        val nodeCode: String,
        val tail: String
    )

    fun localNodeCode(): String = Data.configRelay.localNodeCode.trim()

    private fun roomCodeConfigLoaded(): Boolean {
        return try {
            Data.configRelayRoomCode
            true
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
    }

    fun shareRoomDigits(): Int {
        return if (roomCodeConfigLoaded()) {
            Data.configRelayRoomCode.suffixDigits.coerceIn(2, 6)
        } else {
            5
        }
    }

    private fun normalizeRoomCodeScheme(scheme: BeanRelayRoomCodeScheme): ConfiguredRoomCodeScheme? {
        if (!scheme.enabled) {
            return null
        }

        val prefix = scheme.prefix.trim()
            .uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(2)
        if (prefix.length < 2) {
            return null
        }

        val id = scheme.id.trim()
            .uppercase(Locale.ROOT)
            .ifBlank { prefix }

        return ConfiguredRoomCodeScheme(
            id = id,
            prefix = prefix,
            withNodeCode = scheme.withNodeCode
        )
    }

    private fun configuredRoomCodeSchemes(): List<ConfiguredRoomCodeScheme> {
        if (!roomCodeConfigLoaded()) {
            return listOf(
                ConfiguredRoomCodeScheme(id = "RK", prefix = "RK", withNodeCode = true),
                ConfiguredRoomCodeScheme(id = "OK", prefix = "OK", withNodeCode = false)
            )
        }

        val configuredSchemes = Data.configRelayRoomCode.schemes.mapNotNull(::normalizeRoomCodeScheme)
        if (Data.configRelayRoomCode.enable && configuredSchemes.isNotEmpty()) {
            return configuredSchemes
        }

        return listOf(
            ConfiguredRoomCodeScheme(id = "RK", prefix = "RK", withNodeCode = true),
            ConfiguredRoomCodeScheme(id = "OK", prefix = "OK", withNodeCode = false)
        )
    }

    private fun primaryRoomCodeScheme(): ConfiguredRoomCodeScheme {
        val schemes = configuredRoomCodeSchemes()
        val preferredId = if (roomCodeConfigLoaded()) {
            Data.configRelayRoomCode.primarySchemeId.trim().uppercase(Locale.ROOT)
        } else {
            "RK"
        }
        return schemes.firstOrNull {
            preferredId.isNotBlank() && (it.id == preferredId || it.prefix == preferredId)
        } ?: schemes.first()
    }

    fun shareRoomPrefix(): String = primaryRoomCodeScheme().prefix

    private fun shareRoomUsesNodeCode(): Boolean = primaryRoomCodeScheme().withNodeCode

    private fun formatShareRoomTail(rawTail: String): String {
        val digits = shareRoomDigits()
        return rawTail.takeLast(digits).padStart(digits, '0')
    }

    private fun isConfiguredRoomTail(rawTail: String): Boolean {
        return rawTail.length >= 2 && rawTail.all(Char::isDigit)
    }

    private fun matchConfiguredRoomCode(input: String): ParsedRoomCode? {
        val normalized = input.trim().uppercase(Locale.ROOT)
        configuredRoomCodeSchemes().forEach { scheme ->
            if (!normalized.startsWith(scheme.prefix, ignoreCase = true)) {
                return@forEach
            }

            if (scheme.withNodeCode) {
                val prefixLength = scheme.prefix.length + 1
                if (normalized.length <= prefixLength) {
                    return@forEach
                }
                val nodeCode = normalized.substring(scheme.prefix.length, prefixLength)
                val tail = normalized.substring(prefixLength)
                if (nodeCode.all(Char::isLetterOrDigit) && isConfiguredRoomTail(tail)) {
                    return ParsedRoomCode(scheme, nodeCode, tail)
                }
            } else {
                val prefixLength = scheme.prefix.length
                if (normalized.length <= prefixLength) {
                    return@forEach
                }
                val tail = normalized.substring(prefixLength)
                if (isConfiguredRoomTail(tail)) {
                    return ParsedRoomCode(scheme, "", tail)
                }
            }
        }
        return null
    }

    private fun isClusterForwardedLookupId(input: String): Boolean {
        val normalized = input.trim().uppercase(Locale.ROOT)
        if (!normalized.startsWith("K", ignoreCase = true) || normalized.length <= 3) {
            return false
        }

        val candidate = normalized.substring(1, 2)
        if (!candidate[0].isLetter()) {
            return false
        }

        val tail = normalized.substring(2)
        return isConfiguredRoomTail(tail)
    }

    fun parseShareRoomNodeCode(input: String): String? {
        return matchConfiguredRoomCode(input)?.takeIf { it.scheme.withNodeCode }?.nodeCode
    }

    fun parseClusterRoomNodeCode(input: String): String? {
        val normalized = input.trim().uppercase(Locale.ROOT)
        if (!isClusterForwardedLookupId(normalized)) {
            return null
        }
        return normalized.substring(1, 2)
    }

    fun parseBareForwardedNodeCode(input: String): String? {
        val normalized = normalizeRoomLookupInput(input)
        if (normalized.length < 3) {
            return null
        }

        val nodeCode = normalized.substring(0, 1)
        val tail = normalized.substring(1)
        if (!nodeCode[0].isLetter() || !isConfiguredRoomTail(tail)) {
            return null
        }

        return nodeCode
    }

    fun hasConfiguredRoomCode(input: String): Boolean {
        return matchConfiguredRoomCode(normalizeRoomLookupInput(input)) != null
    }

    fun shareRoomNodeCode(nodeCode: String = ""): String {
        if (!shareRoomUsesNodeCode()) {
            return ""
        }

        val configured = if (roomCodeConfigLoaded()) {
            Data.configRelayRoomCode.localNodeCode.trim()
                .uppercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() }
                .take(1)
        } else {
            ""
        }.ifBlank {
            Data.configRelay.self.shareRoomNodeCode.trim()
                .uppercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() }
                .take(1)
        }
        if (configured.isNotBlank()) {
            return configured
        }

        val preferredNodeCode = nodeCode.trim()
            .uppercase(Locale.ROOT)
            .takeIf { it.length == 1 }
        if (preferredNodeCode != null) {
            return preferredNodeCode
        }

        return localNodeCode()
            .trim()
            .uppercase(Locale.ROOT)
            .takeIf { it.length == 1 }
            ?: "A"
    }

    private fun roomShareTail(roomId: String): String {
        val normalized = roomId.trim().uppercase(Locale.ROOT)
        if (normalized.isBlank()) {
            return ""
        }

        matchConfiguredRoomCode(normalized)?.let { return formatShareRoomTail(it.tail) }

        val shortPrefix = shareRoomPrefix() + shareRoomNodeCode()
        if (normalized.startsWith(shortPrefix, ignoreCase = true) && normalized.length > shortPrefix.length) {
            val sharedTail = normalized.substring(shortPrefix.length)
            if (isConfiguredRoomTail(sharedTail)) {
                return formatShareRoomTail(sharedTail)
            }
            return sharedTail
        }

        val trailingDigits = normalized.takeLastWhile { it.isDigit() }
        if (trailingDigits.isNotEmpty()) {
            return formatShareRoomTail(trailingDigits)
        }

        return normalized.filter { it.isLetterOrDigit() }
    }

    private fun useMainIdPrefix(roomId: String): Boolean {
        return normalizedMainId().isNotBlank() && roomId.isNotBlank() && roomId.all { it.isDigit() }
    }

    private fun roomLegacyLookupId(roomId: String, nodeCode: String = roomNodeCode(roomId)): String {
        val mainPrefix = if (useMainIdPrefix(roomId)) normalizedMainId() else ""
        val routePrefix = nodeCode.takeIf { it.isNotBlank() }?.let { "R${it.uppercase()}" } ?: ""
        return "$mainPrefix$routePrefix$roomId"
    }

    fun roomLookupId(roomId: String, nodeCode: String = roomNodeCode(roomId)): String {
        val scheme = primaryRoomCodeScheme()
        val sharedTail = roomShareTail(roomId)
        return if (scheme.withNodeCode) {
            "${scheme.prefix}${shareRoomNodeCode(nodeCode)}$sharedTail"
        } else {
            "${scheme.prefix}$sharedTail"
        }
    }

    fun roomDisplayId(roomId: String, nodeCode: String = roomNodeCode(roomId)): String = roomLookupId(roomId, nodeCode)

    fun roomLegacyDisplayId(roomId: String, nodeCode: String = roomNodeCode(roomId)): String = "K${roomLegacyLookupId(roomId, nodeCode)}"

    fun roomJoinLink(roomId: String): String? {
        val profile = roomProfile(roomId)
        val nodeCode = roomNodeCode(roomId)
        val displayId = roomDisplayId(roomId, nodeCode)

        profile?.joinBaseUrl?.takeIf { it.isNotBlank() }?.let {
            return "${it.trimEnd('/')}/$displayId"
        }
        if (profile?.connectHost?.isNotBlank() == true && profile.connectPort > 0) {
            return "${profile.connectHost}:${profile.connectPort}/$displayId"
        }

        val baseUrl = joinBaseUrl()
        if (baseUrl.isBlank()) {
            return null
        }
        return "$baseUrl/$displayId"
    }

    private fun normalizeConnectHost(value: String): String {
        return value.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim()
            .trimEnd('/')
            .substringBefore('/')
            .trim()
    }

    fun entryConnectHost(): String? {
        return normalizeConnectHost(joinBaseUrl()).takeIf { it.isNotBlank() }
    }

    fun roomConnectAddress(roomId: String): String? {
        val profile = roomProfile(roomId)
        val profileHost = profile?.connectHost?.let(::normalizeConnectHost).orEmpty()
        if (profileHost.isNotBlank()) {
            return if ((profile?.connectPort ?: 0) > 0) {
                "$profileHost:${profile!!.connectPort}"
            } else {
                profileHost
            }
        }

        return entryConnectHost()
    }

    fun roleName(): String = if (Data.configRelay.mainServer) "core" else "child"

    fun chatSender(roomId: String? = null): String {
        return roomId?.let { roomProfile(it)?.chatSender?.takeIf { sender -> sender.isNotBlank() } } ?: shortName()
    }

    private fun normalizeRoomLookupInput(input: String): String {
        var normalized = input.trim()
            .trim('"', '\'')
            .replace("\\s".toRegex(), "")
            .removePrefix("https://")
            .removePrefix("http://")

        if (normalized.contains('/')) {
            normalized = normalized.substringAfterLast('/')
        }

        if (normalized.startsWith("K", ignoreCase = true) &&
            normalized.length > 1 &&
            configuredRoomCodeSchemes().any { scheme ->
                normalized.substring(1).startsWith(scheme.prefix, ignoreCase = true)
            }
        ) {
            normalized = normalized.substring(1)
        }

        return normalized.uppercase(Locale.ROOT)
    }

    private fun extractShareRoomTail(input: String): String? {
        val normalized = normalizeRoomLookupInput(input)
        if (normalized.all(Char::isDigit) && normalized.length in 1..shareRoomDigits()) {
            return formatShareRoomTail(normalized).takeIf { tail -> tail.isNotBlank() }
        }
        matchConfiguredRoomCode(normalized)?.let { return formatShareRoomTail(it.tail).takeIf { tail -> tail.isNotBlank() } }
        parseClusterRoomNodeCode(normalized)?.let {
            if (normalized.length > 2) {
                return formatShareRoomTail(normalized.substring(2)).takeIf { tail -> tail.isNotBlank() }
            }
        }
        parseBareForwardedNodeCode(normalized)?.let {
            if (normalized.length > 1) {
                return formatShareRoomTail(normalized.substring(1)).takeIf { tail -> tail.isNotBlank() }
            }
        }
        return null
    }

    private fun preferExactTailRoomId(roomIds: List<String>, shareTail: String): String? {
        if (roomIds.isEmpty()) {
            return null
        }

        val normalizedTail = formatShareRoomTail(shareTail)
        val numericTail = normalizedTail.toIntOrNull()?.toString()

        return roomIds.firstOrNull { it.equals(normalizedTail, ignoreCase = true) }
            ?: numericTail?.let { tail ->
                roomIds.firstOrNull { it.equals(tail, ignoreCase = true) }
            }
            ?: roomIds.firstOrNull { roomId ->
                roomId.all(Char::isDigit) && roomShareTail(roomId).equals(normalizedTail, ignoreCase = true)
            }
    }

    private fun roomLookupAliases(roomId: String, nodeCode: String = roomNodeCode(roomId)): Set<String> {
        val aliases = linkedSetOf<String>()
        val displayId = roomDisplayId(roomId, nodeCode).uppercase(Locale.ROOT)
        val tail = roomShareTail(roomId).uppercase(Locale.ROOT)
        val naturalTail = tail.toIntOrNull()?.toString()?.uppercase(Locale.ROOT) ?: tail
        val sharedNodeCode = shareRoomNodeCode(nodeCode).uppercase(Locale.ROOT)

        if (displayId.isNotBlank()) {
            aliases += displayId
            aliases += "K$displayId"
        }

        if (tail.isNotBlank()) {
            aliases += tail
            aliases += naturalTail
            aliases += "K$tail"
            aliases += "K$naturalTail"
        }

        if (sharedNodeCode.isNotBlank()) {
            aliases += "$sharedNodeCode$tail"
            aliases += "$sharedNodeCode$naturalTail"
            aliases += "K$sharedNodeCode$tail"
            aliases += "K$sharedNodeCode$naturalTail"
        }

        return aliases
    }

    fun resolveDisplayRoomId(input: String): String? {
        val normalized = normalizeRoomLookupInput(input)
        if (normalized.isBlank()) {
            return null
        }

        var resolvedRoomId: String? = null
        RelayRoom.forEachActiveRoom { relayRoom ->
            if (resolvedRoomId == null && roomDisplayId(relayRoom.id).equals(normalized, ignoreCase = true)) {
                resolvedRoomId = relayRoom.id
            }
        }
        if (resolvedRoomId != null) {
            return resolvedRoomId
        }

        val shareTail = extractShareRoomTail(normalized)
        if (shareTail != null) {
            val activeTailMatches = mutableListOf<String>()
            RelayRoom.forEachActiveRoom { relayRoom ->
                if (roomShareTail(relayRoom.id).equals(shareTail, ignoreCase = true)) {
                    activeTailMatches += relayRoom.id
                }
            }
            if (activeTailMatches.size == 1) {
                return activeTailMatches.first()
            }
            preferExactTailRoomId(activeTailMatches, shareTail)?.let { return it }

            val configuredTailMatches = RelayCustomRoomSupport.configuredProfiles()
                .map { it.roomId }
                .distinct()
                .filter { roomShareTail(it).equals(shareTail, ignoreCase = true) }
            if (configuredTailMatches.size == 1) {
                return configuredTailMatches.first()
            }
            preferExactTailRoomId(configuredTailMatches, shareTail)?.let { return it }
        }

        return RelayCustomRoomSupport.configuredProfiles()
            .firstOrNull { roomDisplayId(it.roomId, it.nodeCode).equals(normalized, ignoreCase = true) }
            ?.roomId
    }

    fun resolveDisplayRoomIdWithLocalFallback(input: String): String? {
        resolveDisplayRoomId(input)?.let { return it }

        val normalized = normalizeRoomLookupInput(input)
        if (normalized.isBlank()) {
            return null
        }

        var aliasResolvedRoomId: String? = null
        RelayRoom.forEachActiveRoom { relayRoom ->
            if (aliasResolvedRoomId == null && roomLookupAliases(relayRoom.id).contains(normalized)) {
                aliasResolvedRoomId = relayRoom.id
            }
        }
        if (aliasResolvedRoomId != null) {
            return aliasResolvedRoomId
        }

        RelayCustomRoomSupport.configuredProfiles().firstOrNull { profile ->
            roomLookupAliases(profile.roomId, profile.nodeCode).contains(normalized)
        }?.let { return it.roomId }

        val candidates = linkedSetOf<String>()
        candidates += normalized

        val shareTail = extractShareRoomTail(normalized)
        if (shareTail != null) {
            val formattedTail = formatShareRoomTail(shareTail)
            val naturalTail = formattedTail.toIntOrNull()?.toString() ?: formattedTail
            val nodeCandidates = linkedSetOf<String>()

            parseShareRoomNodeCode(normalized)?.let(nodeCandidates::add)
            parseClusterRoomNodeCode(normalized)?.let(nodeCandidates::add)
            parseBareForwardedNodeCode(normalized)?.let(nodeCandidates::add)
            shareRoomNodeCode().takeIf { it.isNotBlank() }?.let(nodeCandidates::add)
            localNodeCode()
                .trim()
                .uppercase(Locale.ROOT)
                .takeIf { it.length == 1 }
                ?.let(nodeCandidates::add)

            candidates += formattedTail
            candidates += naturalTail
            candidates += "K$naturalTail"

            configuredRoomCodeSchemes().forEach { scheme ->
                if (scheme.withNodeCode) {
                    nodeCandidates.forEach { nodeCode ->
                        candidates += "${scheme.prefix}$nodeCode$formattedTail"
                        candidates += "${scheme.prefix}$nodeCode$naturalTail"
                    }
                } else {
                    candidates += "${scheme.prefix}$formattedTail"
                    candidates += "${scheme.prefix}$naturalTail"
                }
            }

            nodeCandidates.forEach { nodeCode ->
                candidates += "$nodeCode$formattedTail"
                candidates += "$nodeCode$naturalTail"
            }
        }

        candidates.forEach { candidate ->
            resolveDisplayRoomId(candidate)?.let { return it }
        }

        return null
    }

    fun stripRelayLookupPrefix(input: String, nodeCode: String = ""): String {
        var normalized = input.trim()
        matchConfiguredRoomCode(normalized)?.let { parsed ->
            val prefixLength = parsed.scheme.prefix.length + parsed.nodeCode.length
            if (normalized.length > prefixLength &&
                (nodeCode.isBlank() || parsed.nodeCode.isBlank() || parsed.nodeCode.equals(nodeCode, ignoreCase = true))
            ) {
                return normalized.substring(prefixLength)
            }
        }

        val clusteredNodeCode = parseClusterRoomNodeCode(normalized)
        if (clusteredNodeCode != null) {
            val prefixLength = 2
            if (normalized.length > prefixLength && (nodeCode.isBlank() || clusteredNodeCode.equals(nodeCode, ignoreCase = true))) {
                return normalized.substring(prefixLength)
            }
        }

        val bareNodeCode = parseBareForwardedNodeCode(normalized)
        if (bareNodeCode != null) {
            val prefixLength = 1
            if (normalized.length > prefixLength && (nodeCode.isBlank() || bareNodeCode.equals(nodeCode, ignoreCase = true))) {
                return normalized.substring(prefixLength)
            }
        }

        if (nodeCode.isNotBlank()) {
            val legacyPrefix = "R${nodeCode.uppercase(Locale.ROOT)}"
            if (normalized.startsWith(legacyPrefix, ignoreCase = true) && normalized.length > legacyPrefix.length) {
                return normalized.substring(legacyPrefix.length)
            }
        }

        if (normalized.startsWith("R", ignoreCase = true) && normalized.length > 1) {
            normalized = normalized.substring(1)
        }
        return normalized
    }

    fun preferredShareHint(roomId: String): String = preferredShareHintText(roomId)

    fun fallbackJoinHint(roomId: String): String? {
        return fallbackJoinHintText(roomId)
    }

    private fun preferredShareHintText(roomId: String): String {
        return "\u4f18\u5148\u5206\u4eab\u623f\u53f7: ${roomDisplayId(roomId)}"
    }

    private fun fallbackJoinHintText(roomId: String): String? {
        return roomJoinLink(roomId)?.let { "\u623f\u53f7\u65e0\u6cd5\u52a0\u5165\u65f6\uff0c\u53ef\u6539\u7528: $it" }
    }

    private fun decodeConfiguredText(text: String): String {
        return text
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "")
    }

    private fun extraNotice(text: String): String? {
        return decodeConfiguredText(text).trim().takeIf { it.isNotBlank() }
    }

    private fun friendlyShareLines(): List<String> {
        return RelayFriendlyShareSupport.lines()
    }

    private fun withFriendlyShare(vararg lines: String?): String {
        return (listOfNotNull(*lines) + friendlyShareLines()).joinToString("\n")
    }

    private fun formatEntryTemplate(template: String, serverVersion: String): String {
        return decodeConfiguredText(template)
            .replace("{displayName}", displayName())
            .replace("{shortName}", shortName())
            .replace("{region}", region())
            .replace("{operatorName}", operatorName())
            .replace("{qqGroup}", qqGroup())
            .replace("{listOpenText}", listOpenText())
            .replace("{joinBaseUrl}", joinBaseUrl())
            .replace("{shareRoomPrefix}", shareRoomPrefix())
            .replace("{shareCodeExample}", shareRoomExampleId())
            .replace("{serverVersion}", serverVersion)
    }

    private fun shareRoomExampleId(): String {
        val sampleTail = "1".padStart(shareRoomDigits(), '0')
        val scheme = primaryRoomCodeScheme()
        return if (scheme.withNodeCode) {
            "${scheme.prefix}${shareRoomNodeCode()}$sampleTail"
        } else {
            "${scheme.prefix}$sampleTail"
        }
    }

    private fun formatTemplate(
        template: String,
        roomId: String,
        internalId: String = "",
        extraValues: Map<String, String> = emptyMap()
    ): String {
        val nodeCode = roomNodeCode(roomId)
        val joinLink = roomJoinLink(roomId) ?: ""
        var formatted = decodeConfiguredText(template)
            .replace("{displayName}", displayName())
            .replace("{shortName}", shortName())
            .replace("{region}", region())
            .replace("{operatorName}", operatorName())
            .replace("{qqGroup}", qqGroup())
            .replace("{listOpenText}", listOpenText())
            .replace("{roomId}", roomId)
            .replace("{displayId}", roomDisplayId(roomId, nodeCode))
            .replace("{lookupId}", roomLookupId(roomId, nodeCode))
            .replace("{legacyDisplayId}", roomLegacyDisplayId(roomId, nodeCode))
            .replace("{shareCode}", roomDisplayId(roomId, nodeCode))
            .replace("{joinLink}", joinLink)
            .replace("{backupJoinLink}", joinLink)
            .replace("{shareHint}", preferredShareHintText(roomId))
            .replace("{fallbackJoinHint}", fallbackJoinHintText(roomId).orEmpty())
            .replace("{nodeCode}", nodeCode)
            .replace("{internalId}", internalId)
            .replace("{serverVersion}", Data.SERVER_CORE_VERSION)

        extraValues.forEach { (key, value) ->
            formatted = formatted.replace("{$key}", value)
        }
        return formatted
    }

    private fun normalizedRoomMapName(roomId: String): String {
        val profile = roomProfile(roomId)
        val room = RelayRoom.getRelay(roomId)
        val mapName = room?.currentMapName
            ?.trimStart('\u0001')
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("RELAY", ignoreCase = true) }
            ?: profile?.defaultMapName?.trim()?.takeIf { it.isNotBlank() }
            ?: "RELAY"
        return sanitizeDisplayMapName(mapName)
    }

    private fun sanitizeDisplayMapName(rawMapName: String): String {
        var mapName = rawMapName.trimStart('\u0001').trim()
        if (mapName.isBlank()) {
            return "RELAY"
        }

        mapName = mapName.substringAfterLast('\\')
        mapName = mapName.substringAfterLast('/')
        mapName = mapName.substringAfterLast('|')
        mapName = mapName.trim().trim('"', '\'')

        val dotIndex = mapName.lastIndexOf('.')
        if (dotIndex in 1 until mapName.length - 1) {
            val suffix = mapName.substring(dotIndex + 1)
            if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
                mapName = mapName.substring(0, dotIndex)
            }
        }

        return mapName.replace("\\s+".toRegex(), " ").trim().ifBlank { "RELAY" }
    }

    private fun roomIsModEnabled(roomId: String): Boolean {
        val room = RelayRoom.getRelay(roomId)
        val profile = roomProfile(roomId)
        return room?.isMod == true || profile?.isMod == true
    }

    private fun roomListRefreshSeconds(roomId: String): Int {
        return if (roomProfile(roomId)?.relayServerManaged == true) {
            RelayCustomRoomSupport.relayServerModeConfig().listUpdateSeconds.coerceIn(45, 300)
        } else {
            60
        }
    }

    private fun roomCommandHint(roomId: String, isHostView: Boolean): String {
        val room = RelayRoom.getRelay(roomId)
        val profile = roomProfile(roomId)
        val commands = linkedSetOf(".state", ".help")
        if (isHostView) {
            commands += ".p 15"
            if (profile?.relayServerManaged != true) {
                commands += if (room?.publicRoom == true) ".tonp" else ".toup"
            }
        }
        return commands.joinToString(" / ")
    }

    private fun roomStatusContext(roomId: String, viewerName: String = "", isHostView: Boolean = false): Map<String, String> {
        val room = RelayRoom.getRelay(roomId)
        val profile = roomProfile(roomId)
        val hostName = room?.admin?.name?.trim()?.takeIf { it.isNotBlank() } ?: "暂无房主"
        val mapName = normalizedRoomMapName(roomId)
        val playerSize = room?.getSize() ?: 0
        val modEnabled = roomIsModEnabled(roomId)
        val maxPlayerSize = room?.currentMaxPlayerSize?.takeIf { it > 0 }
            ?: profile?.maxPlayerSize?.takeIf { it > 0 }
            ?: playerSize.coerceAtLeast(20)
        val refreshSeconds = roomListRefreshSeconds(roomId)
        val joinAddress = roomJoinLink(roomId).orEmpty()
        val commandHint = roomCommandHint(roomId, isHostView)
        val roomMode = when {
            profile?.relayServerManaged == true && modEnabled -> "RELAY-SERVER 模组房"
            profile?.relayServerManaged == true -> "RELAY-SERVER 房间"
            modEnabled -> "模组房"
            profile != null -> "定制房"
            else -> "标准房"
        }
        val statusText = when {
            room?.closeRoom == true -> "已关闭"
            room?.isStartGame == true -> "游戏中"
            playerSize >= 8 -> "即将开始"
            else -> "等待中"
        }
        val publicText = when {
            profile?.relayServerManaged == true && room?.isStartGame == true -> "已下列表"
            profile?.relayServerManaged == true && room?.publicRoom == true -> "常驻公开中"
            room?.publicRoom == true -> "公开中"
            else -> "未公开到列表"
        }
        val viewerRole = when {
            isHostView -> "你已接管房主"
            viewerName.isNotBlank() && viewerName == hostName -> "你已接管房主"
            else -> "当前房主: $hostName"
        }
        val extraText = when {
            profile?.relayServerManaged == true && room?.isStartGame == true -> "服务器已补充新的空闲入口"
            profile?.relayServerManaged == true && modEnabled -> "当前为模组房，未加载模组的玩家可能无法正常游玩"
            modEnabled -> "当前为模组房，未加载模组的玩家可能无法正常游玩"
            profile?.relayServerManaged == true && isHostView -> "列表由服务器自动维护，地图与人数会自动同步"
            profile?.relayServerManaged == true -> "房主退出后，房间会自动重置为空入口"
            room?.publicRoom == true -> "列表信息会自动刷新"
            else -> "当前房间未公开"
        }

        return mapOf(
            "hostName" to hostName,
            "mapName" to mapName,
            "playerSize" to playerSize.toString(),
            "maxPlayerSize" to maxPlayerSize.toString(),
            "roomMode" to roomMode,
            "modeText" to if (modEnabled) "模组房" else "标准房",
            "modEnabled" to modEnabled.toString(),
            "statusText" to statusText,
            "publicText" to publicText,
            "viewerRole" to viewerRole,
            "extraText" to extraText,
            "joinAddress" to joinAddress,
            "listRefreshSeconds" to refreshSeconds.toString(),
            "commandHint" to commandHint
        )
    }

    fun buildEntryWelcome(serverVersion: String): String {
        RelayEntryWelcomeSupport.lines().takeIf { it.isNotEmpty() }?.let { configuredLines ->
            return withFriendlyShare(*configuredLines.map { formatEntryTemplate(it, serverVersion) }.toTypedArray())
        }

        Data.configRelay.self.entryPopupTemplate.trim().takeIf { it.isNotBlank() }?.let {
            return withFriendlyShare(formatEntryTemplate(it, serverVersion))
        }

        val linkHint = entryConnectHost()?.let { "入口域名: $it\n" } ?: ""
        val qqHint = qqGroup().takeIf { it.isNotBlank() }?.let { "联机QQ群: $it\n" } ?: ""
        return withFriendlyShare(
            "【欢迎页】欢迎加入 ${displayName()}",
            listOpenText(),
            "\u4f18\u5148\u4f7f\u7528 ${shareRoomPrefix()} \u7cfb\u5217\u623f\u53f7\u52a0\u5165\u4e0e\u5206\u4eab",
            extraNotice(Data.configRelay.self.entryNotice),
            "${linkHint}${qqHint}版本: $serverVersion".trim()
        )
    }

    fun buildRoomWelcome(roomId: String): String {
        val profile = roomProfile(roomId)
        if (profile?.roomWelcome?.isNotBlank() == true) {
            return withFriendlyShare(formatTemplate(profile.roomWelcome, roomId))
        }

        val roomLine = preferredShareHintText(roomId)
        val connectLine = fallbackJoinHintText(roomId)
        val qqLine = qqGroup().takeIf { it.isNotBlank() }?.let { "联机QQ群: $it" }

        return withFriendlyShare(
            displayName(),
            roomLine,
            connectLine,
            qqLine,
            extraNotice(profile?.entryNotice ?: ""),
            extraNotice(Data.configRelay.self.roomNotice)
        )
    }

    fun buildRoomChatNotice(roomId: String): String {
        val profile = roomProfile(roomId)
        if (profile?.roomChatNotice?.isNotBlank() == true) {
            return withFriendlyShare(formatTemplate(profile.roomChatNotice, roomId))
        }

        return withFriendlyShare(
            "欢迎加入 ${displayName()}",
            preferredShareHintText(roomId),
            fallbackJoinHintText(roomId),
            "输入 .state 查看状态，输入 .help 查看命令",
            RelayRoom.getRelay(roomId)?.takeIf { it.isMod }?.let {
                "当前为模组房，未加载模组的玩家可能无法正常游玩"
            },
            qqGroup().takeIf { it.isNotBlank() }?.let { "联机QQ群: $it" },
            extraNotice(profile?.entryNotice ?: ""),
            extraNotice(Data.configRelay.self.roomNotice)
        )
    }

    fun buildHostChatNotice(roomId: String, internalId: String): String {
        val profile = roomProfile(roomId)
        if (profile?.hostChatNotice?.isNotBlank() == true) {
            return withFriendlyShare(formatTemplate(profile.hostChatNotice, roomId, internalId))
        }

        return withFriendlyShare(
            "你已接管房主",
            preferredShareHintText(roomId),
            fallbackJoinHintText(roomId),
            "命令: ${roomCommandHint(roomId, true)}",
            internalId.takeIf { it.isNotBlank() }?.let { "内部编号: $it" },
            extraNotice(profile?.entryNotice ?: ""),
            extraNotice(Data.configRelay.self.hostNotice)
        )
    }

    fun buildRoomStatusNotice(roomId: String, viewerName: String = "", isHostView: Boolean = false): String {
        val profile = roomProfile(roomId)
        val context = roomStatusContext(roomId, viewerName, isHostView)
        val configuredTemplate = if (profile?.relayServerManaged == true) {
            Data.configRelay.self.relayServerStatusNoticeTemplate.trim()
                .takeIf { it.isNotBlank() }
                ?: Data.configRelay.self.roomStatusNoticeTemplate.trim().takeIf { it.isNotBlank() }
        } else {
            Data.configRelay.self.roomStatusNoticeTemplate.trim().takeIf { it.isNotBlank() }
        }

        val joinAddress = context.getValue("joinAddress").takeIf { it.isNotBlank() }?.let { "备用加入: $it" }
        val refreshHint = "列表刷新: 约 ${context.getValue("listRefreshSeconds")} 秒"
        val commandHint = context.getValue("commandHint").takeIf { it.isNotBlank() }?.let { "命令: $it" }
        val shareHint = preferredShareHintText(roomId)
        val fallbackHint = fallbackJoinHintText(roomId)

        configuredTemplate?.let {
            val rendered = formatTemplate(it, roomId, extraValues = context)
            val tail = mutableListOf<String>()
            if (!it.contains("{modeText}")) {
                tail += "模式: ${context.getValue("modeText")}"
            }
            if (!it.contains("{shareCode}") && !it.contains("{shareHint}")) {
                tail += shareHint
            }
            if (!it.contains("{joinAddress}") && joinAddress != null) {
                tail += joinAddress
            }
            if (!it.contains("{fallbackJoinHint}") && fallbackHint != null) {
                tail += fallbackHint
            }
            if (!it.contains("{listRefreshSeconds}")) {
                tail += refreshHint
            }
            if (!it.contains("{commandHint}") && commandHint != null) {
                tail += commandHint
            }
            if (!it.contains("{extraText}")) {
                context.getValue("extraText").takeIf { value -> value.isNotBlank() }?.let(tail::add)
            }
            return listOf(rendered, *tail.toTypedArray()).joinToString("\n")
        }

        return listOfNotNull(
            context.getValue("viewerRole"),
            "模式: ${context.getValue("modeText")}",
            "状态: ${context.getValue("statusText")} | 列表: ${context.getValue("publicText")}",
            "地图: ${context.getValue("mapName")}",
            "人数: ${context.getValue("playerSize")}/${context.getValue("maxPlayerSize")}",
            shareHint,
            joinAddress,
            fallbackHint,
            refreshHint,
            commandHint,
            context.getValue("extraText")
        ).joinToString("\n")
    }

    fun buildRoomHostTransferredNotice(roomId: String): String {
        val context = roomStatusContext(roomId)
        return listOf(
            "房主已变更: ${context.getValue("hostName")}",
            "房间信息与列表展示已同步"
        ).joinToString("\n")
    }

    fun buildRoomMapUpdatedNotice(roomId: String): String {
        val context = roomStatusContext(roomId)
        return listOf(
            "地图已更新: ${context.getValue("mapName")}",
            "人数: ${context.getValue("playerSize")}/${context.getValue("maxPlayerSize")}",
            "模式: ${context.getValue("modeText")}",
            "列表将在约 ${context.getValue("listRefreshSeconds")} 秒后刷新",
            "如需调整人数上限，请输入 .p 15"
        ).joinToString("\n")
    }

    fun buildRoomPlayerLimitUpdatedNotice(roomId: String, autoDetected: Boolean): String {
        val context = roomStatusContext(roomId)
        val title = if (autoDetected) {
            "已根据地图自动调整人数上限"
        } else {
            "房间人数上限已更新"
        }
        val tail = if (autoDetected) {
            "如需手动覆盖，请输入 .p 15"
        } else {
            "列表人数会在约 ${context.getValue("listRefreshSeconds")} 秒内同步"
        }
        return listOf(
            title,
            "当前上限: ${context.getValue("maxPlayerSize")}",
            "地图: ${context.getValue("mapName")}",
            tail
        ).joinToString("\n")
    }

    fun buildRoomStartedNotice(roomId: String): String {
        val profile = roomProfile(roomId)
        return if (profile?.relayServerManaged == true) {
            listOf(
                "对局已开始，当前房间已从列表下架",
                "服务器已补充新的空闲入口"
            ).joinToString("\n")
        } else {
            "对局已开始，当前房间状态已切换为游戏中"
        }
    }

    fun buildRoomReturnedNotice(roomId: String, restoredToList: Boolean): String {
        val profile = roomProfile(roomId)
        return when {
            profile?.relayServerManaged == true -> listOf(
                "房间已回到等待状态",
                "当前房间不再占用常驻入口",
                "如需继续公开，请进入新的空闲入口"
            ).joinToString("\n")
            restoredToList -> "房间已回到等待状态，并已恢复到公开列表"
            else -> "房间已回到等待状态"
        }
    }

    fun buildRelayServerManagedHostNotice(roomId: String): String? {
        val profile = roomProfile(roomId) ?: return null
        if (!profile.relayServerManaged) {
            return null
        }

        val modEnabled = roomIsModEnabled(roomId)
        val defaultNotice = listOfNotNull(
            preferredShareHintText(roomId),
            fallbackJoinHintText(roomId),
            "当前房间由服务器自动维护",
            "列表由服务器自动维护，无需手动 .toup",
            if (modEnabled) "当前为模组房，未加载模组的玩家可能无法正常游玩" else "当前为标准房",
            "人数上限可使用 .p 15 调整"
        ).joinToString("\n")

        profile.hostClaimNotice.takeIf { it.isNotBlank() }?.let {
            val rendered = formatTemplate(it, roomId)
            return listOf(
                rendered,
                "命令: ${roomCommandHint(roomId, true)}",
                "列表刷新: 约 ${roomListRefreshSeconds(roomId)} 秒"
            ).joinToString("\n")
        }

        return decodeConfiguredText(defaultNotice)
    }

    fun buildRoomModModeKickReason(roomId: String): String {
        return listOf(
            "房间已切换为模组模式",
            "请加载模组后重新加入",
            "房间ID: ${roomDisplayId(roomId)}"
        ).joinToString("\n")
    }

    fun buildRoomModModeEnabledNotice(roomId: String, kickedPlayers: Int, refreshedOnly: Boolean): String {
        val context = roomStatusContext(roomId, isHostView = true)
        val title = if (refreshedOnly) {
            "模组房状态已重新刷新"
        } else {
            "房间已切换为模组模式"
        }
        val clearedText = kickedPlayers.takeIf { it > 0 }?.let { "已清退切换前的 $it 名玩家" }
        return listOfNotNull(
            title,
            "未加载模组的玩家可能无法正常加入或游玩",
            clearedText,
            "地图: ${context.getValue("mapName")}",
            "列表将在约 ${context.getValue("listRefreshSeconds")} 秒后刷新"
        ).joinToString("\n")
    }

    fun buildRoomCommandHelpNotice(roomId: String, viewerName: String = "", isHostView: Boolean = false): String {
        val context = roomStatusContext(roomId, viewerName, isHostView)
        val commands = mutableListOf(
            "当前可用命令:",
            ".state 查看当前房间状态",
            ".help 查看当前可用命令"
        )
        if (isHostView) {
            commands += ".p 15 调整房间人数上限"
            if (roomProfile(roomId)?.relayServerManaged != true) {
                commands += if (RelayRoom.getRelay(roomId)?.publicRoom == true) {
                    ".tonp 从列表移除房间"
                } else {
                    ".toup 公开到列表"
                }
            }
        }
        commands += "列表刷新: 约 ${context.getValue("listRefreshSeconds")} 秒"
        context.getValue("joinAddress").takeIf { it.isNotBlank() }?.let {
            commands += "直连: $it"
        }
        return commands.joinToString("\n")
    }

    fun buildRoomModModeEnabledNotice(roomId: String): String {
        val context = roomStatusContext(roomId, isHostView = true)
        return listOf(
            "房间已切换为模组房",
            "未加载模组的玩家可能无法正常游玩",
            "地图: ${context.getValue("mapName")}",
            "列表将在约 ${context.getValue("listRefreshSeconds")} 秒后刷新",
            "输入 .state / .help 可查看当前状态与命令"
        ).joinToString("\n")
    }

    fun buildRoomPopulationChangedNotice(roomId: String): String {
        val context = roomStatusContext(roomId)
        return listOf(
            "当前人数: ${context.getValue("playerSize")}/${context.getValue("maxPlayerSize")}",
            "状态: ${context.getValue("statusText")}"
        ).joinToString("\n")
    }
}
