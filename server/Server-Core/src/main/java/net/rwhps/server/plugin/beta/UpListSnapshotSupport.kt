/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta

internal data class UpListSnapshotEntry(
    val id: String,
    val gameName: String,
    val privateIp: String,
    val port: Int,
    val createdBy: String,
    val gameMap: String,
    val gameStatus: String,
    val playerCount: Int,
    val maxPlayerCount: Int
)

internal data class UpListSnapshotParseResult(
    val entries: List<UpListSnapshotEntry>,
    val ids: Set<String>,
    val rawEntryCount: Int,
    val parsedEntryCount: Int,
    val schema: String
) {
    val isComplete: Boolean
        get() = rawEntryCount == parsedEntryCount &&
            (schema == "empty" || schema == "22-column" || schema == "18-column")
}

internal object UpListSnapshotSupport {
    /**
     * Compare the endpoint fields that are actually controlled by this process.
     * Master servers may publish a wildcard private address for relay rows, so
     * that value must not be treated as a mismatch.
     */
    fun endpointMatches(
        publishedPrivateIp: String,
        publishedPort: Int,
        privateIp: String,
        listenPort: Int
    ): Boolean {
        if (publishedPort != listenPort) {
            return false
        }
        val publishedPrivateIp = publishedPrivateIp.trim()
        val localPrivateIp = privateIp.trim()
        if (publishedPrivateIp.isBlank() || isWildcardPrivateIp(publishedPrivateIp) || localPrivateIp.isBlank()) {
            return true
        }
        return publishedPrivateIp.equals(localPrivateIp, ignoreCase = true)
    }

    private fun isWildcardPrivateIp(value: String): Boolean {
        return value == "0.0.0.0" || value == "::" || value == "::0" || value == "0:0:0:0:0:0:0:0"
    }

    fun parse(response: String): UpListSnapshotParseResult? {
        val lines = response.lineSequence().toList()
        val headerIndex = lines.indexOfFirst {
            it.trim().removePrefix("\uFEFF").contains("CORRODINGGAMES", ignoreCase = true)
        }
        if (headerIndex < 0) {
            return null
        }
        val header = lines[headerIndex].trim().removePrefix("\uFEFF")
        if (!header.contains("CORRODINGGAMES", ignoreCase = true)) {
            return null
        }

        val bodyLines = lines.drop(headerIndex + 1).filter { it.isNotBlank() }
        val entries = bodyLines.mapNotNull(::parseEntry)
        val rowSchemas = bodyLines.mapNotNull(::detectRowSchema).toSet()
        val schema = when {
            bodyLines.isEmpty() -> "empty"
            rowSchemas.size == 1 -> rowSchemas.first()
            rowSchemas.isEmpty() -> "unknown"
            else -> "mixed"
        }
        val ids = bodyLines.asSequence()
            .mapNotNull { line -> line.substringBefore(",").trim().takeIf { it.isNotBlank() } }
            .toSet()

        return UpListSnapshotParseResult(
            entries = entries,
            ids = ids,
            rawEntryCount = bodyLines.size,
            parsedEntryCount = entries.size,
            schema = schema
        )
    }

    private fun parseEntry(line: String): UpListSnapshotEntry? {
        val parts = line.split(",")
        val schema = detectRowSchema(parts) ?: return null
        val playerCountIndex = if (schema == "22-column") 15 else 16
        val maxPlayerCountIndex = if (schema == "22-column") 16 else 17
        val id = parts[0].trim()
        val privateIp = parts[4].trim()
        val port = parts[5].trim().toIntOrNull() ?: return null
        // Relay rows may omit the current player count while still being valid.
        // Visibility checks only need the stable ID; use zero for the display field.
        val playerCount = parts[playerCountIndex].trim().toIntOrNull() ?: 0
        val maxPlayerCount = parts[maxPlayerCountIndex].trim().toIntOrNull() ?: return null
        if (id.isBlank() || privateIp.isBlank() || port <= 0) {
            return null
        }

        return UpListSnapshotEntry(
            id = id,
            gameName = parts[1].trim(),
            privateIp = privateIp,
            port = port,
            createdBy = parts[7].trim(),
            gameMap = parts[9].trim(),
            gameStatus = parts[11].trim(),
            playerCount = playerCount,
            maxPlayerCount = maxPlayerCount
        )
    }

    private fun detectRowSchema(line: String): String? {
        return detectRowSchema(line.split(","))
    }

    private fun detectRowSchema(parts: List<String>): String? {
        return when {
            parts.size >= 22 &&
                parts[16].trim().toIntOrNull() != null -> "22-column"
            parts.size >= 18 &&
                parts[17].trim().toIntOrNull() != null -> "18-column"
            else -> null
        }
    }
}
