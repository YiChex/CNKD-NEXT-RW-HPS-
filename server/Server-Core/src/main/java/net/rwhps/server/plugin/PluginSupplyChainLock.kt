/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.util.algorithms.digest.DigestUtils
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipFile

internal data class PluginArchiveFingerprint(
    val internalName: String,
    val apiVersion: Int,
    val version: String,
    val fileName: String,
    val sha256: String,
    val descriptorSha256: String
) {
    val normalizedId: String
        get() = internalName.lowercase(Locale.ROOT)
}

internal data class PluginLockDecision(
    val approved: Boolean,
    val reason: String
)

internal data class PluginLockAuthorization(
    val decisions: Map<String, PluginLockDecision>,
    val errors: List<String>,
    val warnings: List<String>
) {
    fun decisionFor(fingerprint: PluginArchiveFingerprint): PluginLockDecision {
        return decisions[fingerprint.normalizedId]
            ?: PluginLockDecision(false, "plugin was not evaluated against plugin-lock.json")
    }
}

internal data class PluginLockEntry(
    val internalName: String,
    val apiVersion: Int,
    val version: String,
    val fileName: String,
    val sha256: String,
    val descriptorSha256: String,
    val approvedAt: String,
    val approvedBy: String
) {
    val normalizedId: String
        get() = internalName.lowercase(Locale.ROOT)
}

/** Fail-closed approval manifest for restart-scoped external plugins. */
internal object PluginSupplyChainLock {
    const val LOCK_FILE_NAME = "plugin-lock.json"
    const val LOCK_VERSION = 1

    private const val MAX_LOCK_BYTES = 1024L * 1024L
    private const val MAX_LOCK_ENTRIES = 512
    private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
    private const val APPROVAL_COMMAND =
        "java -jar plugin-sdk/tools/cnkd-plugin-tool.jar lock data/plugins --approved-by operator-name --force"
    private val pluginIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val sha256Pattern = Regex("^[A-Fa-f0-9]{64}$")

    fun fingerprint(
        file: File,
        pluginInfo: BeanPluginInfo,
        descriptorBytes: ByteArray
    ): PluginArchiveFingerprint {
        require(descriptorBytes.size <= MAX_DESCRIPTOR_BYTES) { "plugin.json exceeds $MAX_DESCRIPTOR_BYTES bytes" }
        return PluginArchiveFingerprint(
            internalName = pluginInfo.internalName,
            apiVersion = pluginInfo.apiVersion,
            version = pluginInfo.version,
            fileName = file.name,
            sha256 = sha256(file),
            descriptorSha256 = DigestUtils.sha256Hex(descriptorBytes).lowercase(Locale.ROOT)
        )
    }

    fun authorize(
        pluginDirectory: File,
        archives: List<PluginArchiveFingerprint>,
        rejectedArchiveNames: List<String> = emptyList()
    ): PluginLockAuthorization {
        val duplicateArchiveIds = archives.groupBy { it.normalizedId }
            .filterValues { it.size > 1 }
        if (duplicateArchiveIds.isNotEmpty()) {
            val duplicates = duplicateArchiveIds.entries
                .sortedBy { it.key }
                .joinToString(", ") { (id, matches) ->
                    "${display(id)} (${matches.joinToString(", ") { display(it.fileName) }})"
                }
            val reason = "duplicate plugin internalName values were discovered before lock validation: $duplicates"
            return rejectAll(archives, reason)
        }

        if (rejectedArchiveNames.isNotEmpty()) {
            val rejected = rejectedArchiveNames
                .asSequence()
                .map(::display)
                .distinct()
                .sorted()
                .take(MAX_REPORTED_ERRORS)
                .joinToString(", ")
            val omitted = rejectedArchiveNames.distinct().size - MAX_REPORTED_ERRORS
            val suffix = if (omitted > 0) ", $omitted additional archive(s) omitted" else ""
            val reason = "plugin directory contains invalid or unreadable archives: $rejected$suffix"
            return rejectAll(archives, reason)
        }

        val lockFile = File(pluginDirectory, LOCK_FILE_NAME)
        if (!lockFile.exists()) {
            if (archives.isEmpty()) {
                return PluginLockAuthorization(emptyMap(), emptyList(), emptyList())
            }
            return rejectAll(
                archives,
                "plugin lock is missing: ${lockFile.path}; run $APPROVAL_COMMAND before restart"
            )
        }

        val parsed = load(lockFile)
        if (parsed.errors.isNotEmpty()) {
            val selectedErrors = parsed.errors.take(MAX_REPORTED_ERRORS)
            val omitted = parsed.errors.size - selectedErrors.size
            val summary = selectedErrors.joinToString("; ") +
                if (omitted > 0) "; $omitted additional error(s) omitted" else ""
            val reason = "plugin lock is invalid: $summary; run $APPROVAL_COMMAND"
            return rejectAll(archives, reason, selectedErrors.map { "${lockFile.path}: $it" })
        }

        val entriesById = parsed.entries.associateBy { it.normalizedId }
        val observedIds = archives.mapTo(HashSet()) { it.normalizedId }
        val absentApprovals = parsed.entries.filter { it.normalizedId !in observedIds }
        if (absentApprovals.isNotEmpty()) {
            val absent = absentApprovals.joinToString(", ") { "${it.internalName} (${it.fileName})" }
            val reason = "plugin directory does not exactly match plugin lock; approved archives are absent: $absent; " +
                "run $APPROVAL_COMMAND"
            return rejectAll(archives, reason)
        }
        val unknownArchives = archives.filter { it.normalizedId !in entriesById }
        if (unknownArchives.isNotEmpty()) {
            val unknown = unknownArchives.joinToString(", ") { "${it.internalName} (${it.fileName})" }
            val reason = "plugin directory does not exactly match plugin lock; unapproved archives are present: $unknown; " +
                "run $APPROVAL_COMMAND"
            return rejectAll(archives, reason)
        }

        val decisions = LinkedHashMap<String, PluginLockDecision>()
        archives.forEach { archive ->
            val entry = entriesById[archive.normalizedId]
            decisions[archive.normalizedId] = when {
                entry == null -> PluginLockDecision(
                    false,
                    "no approval entry for internalName '${archive.internalName}'; run $APPROVAL_COMMAND"
                )
                else -> compare(entry, archive).let { decision ->
                    if (decision.approved) decision
                    else decision.copy(reason = "${decision.reason}; run $APPROVAL_COMMAND")
                }
            }
        }
        return PluginLockAuthorization(decisions, emptyList(), emptyList())
    }

    /**
     * Explicit development-only authorization. Structural and compatibility
     * validation happens before this method is called; only the approval lock
     * is bypassed so a developer can iterate without regenerating hashes.
     */
    fun authorizeDevelopment(
        pluginDirectory: File,
        archives: List<PluginArchiveFingerprint>,
        rejectedArchiveNames: List<String> = emptyList()
    ): PluginLockAuthorization {
        val duplicateArchiveIds = archives.groupBy { it.normalizedId }
            .filterValues { it.size > 1 }
        if (duplicateArchiveIds.isNotEmpty()) {
            val duplicates = duplicateArchiveIds.entries
                .sortedBy { it.key }
                .joinToString(", ") { (id, matches) ->
                    "${display(id)} (${matches.joinToString(", ") { display(it.fileName) }})"
                }
            val reason = "duplicate plugin internalName values were discovered before development loading: $duplicates"
            return rejectAll(archives, reason)
        }

        if (rejectedArchiveNames.isNotEmpty()) {
            val rejected = rejectedArchiveNames
                .asSequence()
                .map(::display)
                .distinct()
                .sorted()
                .take(MAX_REPORTED_ERRORS)
                .joinToString(", ")
            val omitted = rejectedArchiveNames.distinct().size - MAX_REPORTED_ERRORS
            val suffix = if (omitted > 0) ", $omitted additional archive(s) omitted" else ""
            return rejectAll(
                archives,
                "plugin directory contains invalid or unreadable archives: $rejected$suffix"
            )
        }

        val decisions = archives.associate { archive ->
            archive.normalizedId to PluginLockDecision(
                true,
                "development mode: plugin-lock.json bypassed for ${pluginDirectory.path}; structural validation passed"
            )
        }
        return PluginLockAuthorization(
            decisions,
            emptyList(),
            listOf("development mode bypassed plugin-lock.json; this plugin set must never be used as a production release")
        )
    }

    /** Re-reads both the archive and plugin.json immediately before plugin-controlled work. */
    fun verifyUnchanged(file: File, expected: PluginArchiveFingerprint): String? {
        if (file.name != expected.fileName) {
            return "archive file name changed: expected '${expected.fileName}', actual '${file.name}'"
        }
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return "archive is no longer a regular non-symbolic-link file"
        }

        return try {
            val archiveHash = sha256(file)
            if (!archiveHash.equals(expected.sha256, ignoreCase = true)) {
                return "archive SHA-256 changed after approval check"
            }
            val descriptorHash = DigestUtils.sha256Hex(readDescriptorBytes(file)).lowercase(Locale.ROOT)
            if (!descriptorHash.equals(expected.descriptorSha256, ignoreCase = true)) {
                return "plugin.json SHA-256 changed after approval check"
            }
            null
        } catch (error: Throwable) {
            "failed to recheck plugin fingerprint: ${safeMessage(error)}"
        }
    }

    fun readDescriptorBytes(file: File): ByteArray {
        ZipFile(file).use { zip ->
            val descriptor = zip.getEntry("plugin.json")
                ?: throw IllegalArgumentException("missing plugin.json")
            val bytes = zip.getInputStream(descriptor).use { it.readNBytes(MAX_DESCRIPTOR_BYTES + 1) }
            require(bytes.size <= MAX_DESCRIPTOR_BYTES) { "plugin.json exceeds $MAX_DESCRIPTOR_BYTES bytes" }
            return bytes
        }
    }

    fun decodeDescriptor(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun compare(entry: PluginLockEntry, archive: PluginArchiveFingerprint): PluginLockDecision {
        val mismatches = ArrayList<String>()
        if (entry.internalName != archive.internalName) mismatches.add("internalName")
        if (entry.apiVersion != archive.apiVersion) mismatches.add("apiVersion")
        if (entry.version != archive.version) mismatches.add("version")
        if (entry.fileName != archive.fileName) mismatches.add("fileName")
        if (!entry.sha256.equals(archive.sha256, ignoreCase = true)) mismatches.add("sha256")
        if (!entry.descriptorSha256.equals(archive.descriptorSha256, ignoreCase = true)) {
            mismatches.add("descriptorSha256")
        }
        return if (mismatches.isEmpty()) {
            PluginLockDecision(true, "approved by ${entry.approvedBy} at ${entry.approvedAt}")
        } else {
            PluginLockDecision(false, "approval mismatch: ${mismatches.joinToString(",")}")
        }
    }

    private fun rejectAll(
        archives: List<PluginArchiveFingerprint>,
        reason: String,
        errors: List<String> = listOf(reason)
    ): PluginLockAuthorization {
        val decisions = archives.associate { archive ->
            archive.normalizedId to PluginLockDecision(false, reason)
        }
        return PluginLockAuthorization(decisions, errors, emptyList())
    }

    private data class ParsedLock(
        val entries: List<PluginLockEntry>,
        val errors: List<String>
    )

    private fun load(file: File): ParsedLock {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return ParsedLock(emptyList(), listOf("lock must be a regular non-symbolic-link file"))
        }
        if (file.length() <= 0L || file.length() > MAX_LOCK_BYTES) {
            return ParsedLock(emptyList(), listOf("lock size must be within 1..$MAX_LOCK_BYTES bytes"))
        }

        return try {
            val bytes = Files.readAllBytes(file.toPath())
            val raw = decodeUtf8(bytes)
            parse(raw)
        } catch (error: Throwable) {
            ParsedLock(emptyList(), listOf("cannot read lock: ${safeMessage(error)}"))
        }
    }

    private fun parse(raw: String): ParsedLock {
        val errors = ArrayList<String>()
        val entries = ArrayList<PluginLockEntry>()
        var lockVersion: Int? = null
        var pluginsSeen = false
        val rootFields = HashSet<String>()

        try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    return ParsedLock(emptyList(), listOf("root must be a JSON object"))
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!rootFields.add(name)) {
                        errors.add("duplicate root field '${display(name)}'")
                        reader.skipValue()
                        continue
                    }
                    when (name) {
                        "lockVersion" -> lockVersion = readInt(reader, "lockVersion", errors)
                        "plugins" -> {
                            pluginsSeen = true
                            readEntries(reader, entries, errors)
                        }
                        else -> {
                            errors.add("unsupported root field '${display(name)}'")
                            reader.skipValue()
                        }
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    errors.add("content exists after the root object")
                }
            }
        } catch (error: Throwable) {
            errors.add("malformed JSON: ${safeMessage(error)}")
        }

        if (lockVersion == null) errors.add("missing required field 'lockVersion'")
        if (!pluginsSeen) errors.add("missing required field 'plugins'")
        if (lockVersion != null && lockVersion != LOCK_VERSION) {
            errors.add("unsupported lockVersion $lockVersion (expected $LOCK_VERSION)")
        }
        validateEntries(entries, errors)
        return ParsedLock(entries, errors)
    }

    private fun readEntries(
        reader: JsonReader,
        entries: MutableList<PluginLockEntry>,
        errors: MutableList<String>
    ) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            errors.add("plugins must be an array")
            reader.skipValue()
            return
        }
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            if (index >= MAX_LOCK_ENTRIES) {
                if (index == MAX_LOCK_ENTRIES) {
                    errors.add("plugins cannot contain more than $MAX_LOCK_ENTRIES entries")
                }
                reader.skipValue()
                index++
                continue
            }
            readEntry(reader, index, errors)?.let(entries::add)
            index++
        }
        reader.endArray()
    }

    private fun readEntry(reader: JsonReader, index: Int, errors: MutableList<String>): PluginLockEntry? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            errors.add("plugins[$index] must be an object")
            reader.skipValue()
            return null
        }

        val fields = HashSet<String>()
        val values = HashMap<String, String>()
        var apiVersion: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!fields.add(name)) {
                errors.add("plugins[$index] contains duplicate field '${display(name)}'")
                reader.skipValue()
                continue
            }
            when (name) {
                "apiVersion" -> apiVersion = readInt(reader, "plugins[$index].apiVersion", errors)
                "internalName", "version", "fileName", "sha256", "descriptorSha256", "approvedAt", "approvedBy" -> {
                    readString(reader, "plugins[$index].$name", errors)?.let { values[name] = it }
                }
                else -> {
                    errors.add("plugins[$index] contains unsupported field '${display(name)}'")
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        val required = setOf(
            "internalName", "apiVersion", "version", "fileName", "sha256",
            "descriptorSha256", "approvedAt", "approvedBy"
        )
        (required - fields).sorted().forEach { errors.add("plugins[$index] missing required field '$it'") }
        if (!fields.containsAll(required) || apiVersion == null || values.size < 7) {
            return null
        }
        return PluginLockEntry(
            internalName = values.getValue("internalName"),
            apiVersion = apiVersion,
            version = values.getValue("version"),
            fileName = values.getValue("fileName"),
            sha256 = values.getValue("sha256"),
            descriptorSha256 = values.getValue("descriptorSha256"),
            approvedAt = values.getValue("approvedAt"),
            approvedBy = values.getValue("approvedBy")
        )
    }

    private fun validateEntries(entries: List<PluginLockEntry>, errors: MutableList<String>) {
        val ids = HashSet<String>()
        val fileNames = HashSet<String>()
        entries.forEachIndexed { index, entry ->
            if (!pluginIdPattern.matches(entry.internalName)) {
                errors.add("plugins[$index].internalName has invalid syntax")
            }
            if (!ids.add(entry.normalizedId)) {
                errors.add("duplicate plugin internalName '${display(entry.internalName)}'")
            }
            if (entry.apiVersion !in 1..2) {
                errors.add("plugins[$index].apiVersion must be 1 or 2")
            }
            if (entry.version.isBlank() || runCatching { GetVersion(entry.version) }.isFailure) {
                errors.add("plugins[$index].version must be valid SemVer")
            }
            val lowerFileName = entry.fileName.lowercase(Locale.ROOT)
            if (entry.fileName.length !in 1..255 || entry.fileName.contains('/') || entry.fileName.contains('\\') ||
                (!lowerFileName.endsWith(".jar") && !lowerFileName.endsWith(".zip"))
            ) {
                errors.add("plugins[$index].fileName must be a JAR/ZIP base name")
            }
            if (!fileNames.add(lowerFileName)) {
                errors.add("duplicate plugin fileName '${display(entry.fileName)}'")
            }
            if (!sha256Pattern.matches(entry.sha256)) {
                errors.add("plugins[$index].sha256 must contain 64 hexadecimal characters")
            }
            if (!sha256Pattern.matches(entry.descriptorSha256)) {
                errors.add("plugins[$index].descriptorSha256 must contain 64 hexadecimal characters")
            }
            if (runCatching { Instant.parse(entry.approvedAt) }.isFailure) {
                errors.add("plugins[$index].approvedAt must be an ISO-8601 UTC instant")
            }
            if (entry.approvedBy.length !in 1..128 || entry.approvedBy != entry.approvedBy.trim() ||
                entry.approvedBy.any { it.code < 0x20 || it.code == 0x7f }
            ) {
                errors.add("plugins[$index].approvedBy must contain 1..128 printable characters")
            }
        }
    }

    private fun readInt(reader: JsonReader, path: String, errors: MutableList<String>): Int? {
        if (reader.peek() != JsonToken.NUMBER) {
            errors.add("$path must be an integer")
            reader.skipValue()
            return null
        }
        return try {
            reader.nextInt()
        } catch (error: Throwable) {
            errors.add("$path must be an integer")
            null
        }
    }

    private fun readString(reader: JsonReader, path: String, errors: MutableList<String>): String? {
        if (reader.peek() != JsonToken.STRING) {
            errors.add("$path must be a string")
            reader.skipValue()
            return null
        }
        return reader.nextString()
    }

    private fun sha256(file: File): String {
        return file.inputStream().buffered().use { input ->
            DigestUtils.sha256Hex(input).lowercase(Locale.ROOT)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun safeMessage(error: Throwable): String {
        return error.message?.let(::display)?.ifBlank { error.javaClass.simpleName } ?: error.javaClass.simpleName
    }

    private fun display(value: String): String {
        return value.asSequence()
            .map { character -> if (character.code < 0x20 || character.code == 0x7f) '?' else character }
            .take(120)
            .joinToString("")
    }

    private const val MAX_REPORTED_ERRORS = 20
}
