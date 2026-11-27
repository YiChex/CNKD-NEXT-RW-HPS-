/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.room.ServerRoom
import net.rwhps.server.util.file.FileUtils
import java.io.File
import java.util.Locale
import com.corrodinggames.rts.gameFramework.l as GameCore

internal object StartModeSurvivalMapSupport {
    private const val DEFAULT_HUMAN_INDEX = 0
    private const val DEFAULT_SURVIVAL_AI_INDEX = 1
    private const val DEFAULT_HUMAN_ALLY_GROUP = 0

    private val objectRegex = Regex(
        """<object\b([^>]*)>(.*?)</object>|<object\b([^>]*)/>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val attributeRegex = Regex("([A-Za-z_:][\\w:.-]*)=\"([^\"]*)\"")
    private val propertyRegex = Regex(
        """<property\b([^>]*?)(?:/>|>(.*?)</property>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    @Volatile
    private var selectedMapName = ""

    @Volatile
    private var cachedKey = ""

    @Volatile
    private var cachedProfile = Profile()

    fun recordSelectedMapName(rawMapName: String) {
        val normalized = cleanRawMapName(rawMapName)
        if (normalized.isBlank()) {
            return
        }
        if (selectedMapName != normalized) {
            selectedMapName = normalized
            cachedKey = ""
        }
    }

    fun currentProfile(room: ServerRoom? = currentRoom()): Profile {
        val candidate = resolveCandidate(room)
        if (candidate.key.isNotBlank() && candidate.key == cachedKey) {
            return cachedProfile
        }

        val profile = candidate.file
            ?.takeIf { it.isFile && it.length() > 0 }
            ?.let { parseProfile(it, candidate.displayName, candidate.rawName) }
            ?: Profile(
                displayName = candidate.displayName,
                rawMapName = candidate.rawName,
                sourcePath = candidate.file?.absolutePath.orEmpty(),
                parseError = candidate.error
            )

        cachedKey = candidate.key
        cachedProfile = profile
        return profile
    }

    fun currentLayout(room: ServerRoom? = currentRoom()): SurvivalLayout {
        val profile = currentProfile(room)
        return SurvivalLayout(
            humanAllyGroup = profile.humanAllyGroup,
            humanSlots = profile.humanSlots.ifEmpty {
                listOf(SurvivalHumanSlot(DEFAULT_HUMAN_INDEX, DEFAULT_HUMAN_ALLY_GROUP))
            },
            hasDeclaredHumanSlots = profile.humanSlots.isNotEmpty(),
            aiSlots = profile.survivalAiSlots.ifEmpty {
                listOf(SurvivalAiSlot(DEFAULT_SURVIVAL_AI_INDEX, DEFAULT_SURVIVAL_AI_INDEX))
            },
            profile = profile
        )
    }

    fun buildDiagnostics(room: ServerRoom? = currentRoom()): List<String> {
        val profile = currentProfile(room)
        val layout = currentLayout(room)
        return listOf(
            "survival.map.raw=${profile.rawMapName.ifBlank { "<blank>" }}",
            "survival.map.display=${profile.displayName.ifBlank { "<blank>" }}",
            "survival.map.source=${profile.sourcePath.ifBlank { "<unresolved>" }}",
            "survival.map.typeSurvival=${profile.mapTypeSurvival}",
            "survival.map.winCondition=${profile.winCondition.ifBlank { "<default>" }}",
            "survival.map.winConditionNone=${profile.winConditionNone}",
            "survival.map.survivalLike=${profile.survivalLike}",
            "survival.map.attackPoints=${profile.attackPointCount}",
            "survival.map.referencedTeams=${profile.referencedTeams.joinToString(",").ifBlank { "<none>" }}",
            "survival.map.unitAddTeams=${profile.unitAddTeams.joinToString(",").ifBlank { "<none>" }}",
            "survival.map.inferredHumanTeams=${profile.inferredHumanTeamIndexes.joinToString(",").ifBlank { "<none>" }}",
            "survival.map.requiredTeamSlots=${profile.requiredTeamSlots}",
            "survival.map.customWaves=${profile.customSurvivalWaves}",
            "survival.map.classicWaves=${profile.classicSurvivalWaves}",
            "survival.map.teamInfos=${profile.teamInfos.joinToString(",") { it.toDiagnosticString() }.ifBlank { "<none>" }}",
            "survival.layout.humanAllyGroup=${layout.humanAllyGroup}",
            "survival.layout.humanSlots=${layout.humanSlots.joinToString(",") { "${it.playerIndex}:${it.allyGroup}" }}",
            "survival.layout.primaryHumanSlot=${layout.primaryHumanSlot.playerIndex}:${layout.primaryHumanSlot.allyGroup}",
            "survival.layout.declaredHumanSlots=${layout.hasDeclaredHumanSlots}",
            "survival.layout.aiSlots=${layout.aiSlots.joinToString(",") { "${it.playerIndex}:${it.allyGroup}" }}",
            "survival.map.parseError=${profile.parseError.ifBlank { "<none>" }}"
        )
    }

    private fun resolveCandidate(room: ServerRoom?): Candidate {
        val rawNames = mutableListOf<String>()
        rawNames += selectedMapName
        rawNames += runCatching { GameCore.B().bX.az }.getOrDefault("")
        rawNames += runCatching { GameCore.B().bX.ay.b }.getOrDefault("")
        room?.maps?.mapName?.let { rawNames += it }

        for (rawName in rawNames.map { cleanRawMapName(it) }.filter { it.isNotBlank() }.distinct()) {
            resolveFile(rawName)?.let { file ->
                return Candidate(
                    key = "raw:${file.absolutePath}:${file.lastModified()}:${file.length()}",
                    file = file,
                    displayName = rawName.substringAfterLast('/').substringBeforeLast(".", rawName.substringAfterLast('/')),
                    rawName = rawName
                )
            }
        }

        room?.maps?.mapData?.let { mapData ->
            val file = runCatching {
                mapData.readMap(cleanOnRelease = false)
                File(FileUtils.getFolder(Data.ServerMapsPath, true).file, mapData.runtimeFileName)
            }.getOrNull()
            if (file != null) {
                return Candidate(
                    key = "mapData:${file.absolutePath}:${file.lastModified()}:${file.length()}",
                    file = file,
                    displayName = room.maps.mapName,
                    rawName = mapData.runtimeFileName
                )
            }
        }

        val rawName = rawNames.firstOrNull { it.isNotBlank() }.orEmpty()
        return Candidate(
            key = "missing:$rawName",
            displayName = rawName,
            rawName = rawName,
            error = if (rawName.isBlank()) "map_not_selected" else "map_file_not_found"
        )
    }

    private fun resolveFile(rawName: String): File? {
        MapManage.resolveCustomMap(rawName)?.let { target ->
            val file = runCatching {
                target.mapData.readMap(cleanOnRelease = false)
                File(FileUtils.getFolder(Data.ServerMapsPath, true).file, target.mapData.runtimeFileName)
            }.getOrNull()
            if (file != null && file.isFile) {
                return file
            }
        }

        val normalized = cleanRawMapName(rawName)
        val candidates = ArrayList<File>()
        val mapsRoot = FileUtils.getFolder(Data.ServerMapsPath, true).file
        val gameDataRoot = FileUtils.getFolder(Data.Plugin_GameCore_Data_Path, true).file

        if (normalized.startsWith("/SD/rusted_warfare_maps/", ignoreCase = true)) {
            candidates += File(mapsRoot, normalized.substringAfter("/SD/rusted_warfare_maps/"))
        }
        if (normalized.startsWith("rusted_warfare_maps/", ignoreCase = true)) {
            candidates += File(mapsRoot, normalized.substringAfter("rusted_warfare_maps/"))
        }
        if (normalized.startsWith("maps/", ignoreCase = true)) {
            candidates += File(gameDataRoot, "assets/$normalized")
        }
        if (normalized.startsWith("assets/maps/", ignoreCase = true)) {
            candidates += File(gameDataRoot, normalized)
        }
        if (normalized.endsWith(".tmx", ignoreCase = true)) {
            candidates += File(mapsRoot, normalized.substringAfterLast('/'))
            candidates += File(mapsRoot, normalized)
        }

        return candidates.firstOrNull { it.isFile && it.length() > 0 }
    }

    private fun parseProfile(file: File, displayName: String, rawMapName: String): Profile {
        return runCatching {
            val text = file.readText(Data.UTF_8)
            var mapTypeSurvival = false
            var winCondition = ""
            var customSurvivalWaves = false
            var classicSurvivalWaves = false
            var attackPoints = 0
            val teamInfos = ArrayList<TeamInfo>()
            val referencedTeams = linkedSetOf<Int>()
            val unitAddTeams = linkedSetOf<Int>()

            for (match in objectRegex.findAll(text)) {
                val attrs = parseAttributes(match.groups[1]?.value ?: match.groups[3]?.value.orEmpty())
                val body = match.groups[2]?.value.orEmpty()
                val name = attrs["name"].orEmpty()
                val type = attrs["type"].orEmpty()
                val properties = parseProperties(body)
                properties["team"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { referencedTeams += it }
                if (type.equals("unitadd", ignoreCase = true)) {
                    properties["team"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { unitAddTeams += it }
                }

                if (name.equals("map_info", ignoreCase = true)) {
                    mapTypeSurvival = properties["type"].equals("survival", ignoreCase = true)
                    winCondition = properties["wincondition"].orEmpty()
                    customSurvivalWaves = !properties["survivalwaves"].isNullOrBlank()
                    classicSurvivalWaves = properties["survivalwavesclassic"].equals("true", ignoreCase = true)
                }

                if (name.equals("attack_point", ignoreCase = true)) {
                    attackPoints++
                }

                if (type.equals("team_info", ignoreCase = true) || name.matches(Regex("""team_\d+_info""", RegexOption.IGNORE_CASE))) {
                    val team = properties["team"]?.toIntOrNull() ?: name
                        .substringAfter("team_", "")
                        .substringBefore("_info", "")
                        .toIntOrNull()
                    if (team != null && team >= 0) {
                        teamInfos += TeamInfo(
                            team = team,
                            allyGroup = properties["allygroup"]?.toIntOrNull(),
                            ai = properties["ai"].orEmpty()
                        )
                    }
                }
            }

            Profile(
                displayName = displayName,
                rawMapName = rawMapName,
                sourcePath = file.absolutePath,
                mapTypeSurvival = mapTypeSurvival,
                winCondition = winCondition,
                customSurvivalWaves = customSurvivalWaves,
                classicSurvivalWaves = classicSurvivalWaves,
                attackPointCount = attackPoints,
                referencedTeams = referencedTeams.sorted(),
                unitAddTeams = unitAddTeams.sorted(),
                teamInfos = teamInfos.sortedBy { it.team }
            )
        }.getOrElse { error ->
            Profile(
                displayName = displayName,
                rawMapName = rawMapName,
                sourcePath = file.absolutePath,
                parseError = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun parseProperties(body: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in propertyRegex.findAll(body)) {
            val attrs = parseAttributes(match.groups[1]?.value.orEmpty())
            val name = attrs["name"]?.lowercase(Locale.ROOT) ?: continue
            result[name] = htmlUnescape(attrs["value"] ?: match.groups[2]?.value.orEmpty())
        }
        return result
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in attributeRegex.findAll(value)) {
            result[match.groupValues[1].lowercase(Locale.ROOT)] = htmlUnescape(match.groupValues[2])
        }
        return result
    }

    private fun cleanRawMapName(rawName: String): String {
        var value = rawName
            .trim()
            .trim('"', '\'')
            .trimStart('\u0001')
            .replace("\\", "/")
            .trim()
        value = when {
            value.startsWith("MOD|", ignoreCase = true) -> value.substringAfter("//", value.substringAfter("|", value))
            value.startsWith("NEW_PATH|", ignoreCase = true) -> value.substringAfter("|", value)
            else -> value
        }
        return value.trim()
    }

    private fun htmlUnescape(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun currentRoom(): ServerRoom? {
        return if (HeadlessModuleManage.initHPS()) HeadlessModuleManage.hps.room else null
    }

    data class SurvivalLayout(
        val humanAllyGroup: Int,
        val humanSlots: List<SurvivalHumanSlot>,
        val hasDeclaredHumanSlots: Boolean,
        val aiSlots: List<SurvivalAiSlot>,
        val profile: Profile
    ) {
        val primaryHumanSlot: SurvivalHumanSlot get() = humanSlots.first()
        val primaryAiSlot: SurvivalAiSlot get() = aiSlots.first()
        val humanPlayerIndexes: Set<Int> get() = humanSlots.map { it.playerIndex }.toSet()
        val reservedPlayerIndexes: Set<Int> get() = aiSlots.map { it.playerIndex }.toSet()
    }

    data class SurvivalHumanSlot(
        val playerIndex: Int,
        val allyGroup: Int
    )

    data class SurvivalAiSlot(
        val playerIndex: Int,
        val allyGroup: Int
    )

    data class Profile(
        val displayName: String = "",
        val rawMapName: String = "",
        val sourcePath: String = "",
        val mapTypeSurvival: Boolean = false,
        val winCondition: String = "",
        val customSurvivalWaves: Boolean = false,
        val classicSurvivalWaves: Boolean = false,
        val attackPointCount: Int = 0,
        val referencedTeams: List<Int> = emptyList(),
        val unitAddTeams: List<Int> = emptyList(),
        val teamInfos: List<TeamInfo> = emptyList(),
        val parseError: String = ""
    ) {
        val survivalLike: Boolean
            get() = mapTypeSurvival || survivalTeams.isNotEmpty() || customSurvivalWaves || classicSurvivalWaves

        val winConditionNone: Boolean
            get() = winCondition.equals("none", ignoreCase = true)

        val maxReferencedTeamIndex: Int
            get() = referencedTeams.maxOrNull() ?: -1

        val requiredTeamSlots: Int
            get() = (maxReferencedTeamIndex + 1).coerceAtLeast(0)

        val survivalTeams: List<TeamInfo>
            get() = teamInfos.filter { it.isSurvivalAi }

        val survivalTeamIndexes: Set<Int>
            get() = survivalTeams.map { it.team }.toSet()

        val humanTeams: List<TeamInfo>
            get() = teamInfos.filter { it.isPlayableHuman }

        val inferredHumanTeamIndexes: List<Int>
            get() {
                if (humanTeams.isNotEmpty()) {
                    return emptyList()
                }
                val candidateTeams = unitAddTeams.ifEmpty { referencedTeams }
                val survivalIndexes = survivalTeamIndexes
                return candidateTeams
                    .filter { it >= 0 && it !in survivalIndexes }
                    .distinct()
                    .sorted()
            }

        val humanAllyGroup: Int
            get() = humanTeams
                .firstOrNull()
                ?.allyGroup
                ?: DEFAULT_HUMAN_ALLY_GROUP

        val humanSlots: List<SurvivalHumanSlot>
            get() = humanTeams
                .map { SurvivalHumanSlot(it.team, it.allyGroup ?: humanAllyGroup) }
                .ifEmpty {
                    inferredHumanTeamIndexes.map { SurvivalHumanSlot(it, humanAllyGroup) }
                }
                .distinctBy { it.playerIndex }

        val survivalAiSlots: List<SurvivalAiSlot>
            get() = survivalTeams
                .map { SurvivalAiSlot(it.team, it.allyGroup ?: it.team) }
                .distinctBy { it.playerIndex }
    }

    data class TeamInfo(
        val team: Int,
        val allyGroup: Int?,
        val ai: String
    ) {
        val isSurvivalAi: Boolean
            get() = ai.equals("survival", ignoreCase = true)

        val isPlayableHuman: Boolean
            get() = ai.isBlank() ||
                ai.equals("none", ignoreCase = true) ||
                ai.equals("false", ignoreCase = true) ||
                ai.equals("human", ignoreCase = true)

        fun toDiagnosticString(): String {
            return "team=$team/ally=${allyGroup ?: "<default>"}/ai=${ai.ifBlank { "none" }}"
        }
    }

    private data class Candidate(
        val key: String,
        val file: File? = null,
        val displayName: String = "",
        val rawName: String = "",
        val error: String = ""
    )
}
