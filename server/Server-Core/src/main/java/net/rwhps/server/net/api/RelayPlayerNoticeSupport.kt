package net.rwhps.server.net.api

import net.rwhps.server.data.global.Data
import net.rwhps.server.game.room.RelayRoom

internal object RelayPlayerNoticeSupport {
    private fun room(roomId: String): RelayRoom? = RelayRoom.getRelay(roomId)

    private fun roomProfile(roomId: String): RelayCustomRoomProfile? = RelayCustomRoomSupport.findAppliedProfile(roomId)

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

    private fun preferredShareHint(roomId: String): String {
        return "优先分享房号: ${RelayDeploymentSupport.roomDisplayId(roomId)}"
    }

    private fun fallbackJoinHint(roomId: String): String? {
        return RelayDeploymentSupport.roomJoinLink(roomId)?.let { "房号无法加入时，可改用: $it" }
    }

    private fun sanitizeDisplayMapName(rawMapName: String): String {
        var mapName = rawMapName.trimStart('\u0001').trim()
        if (mapName.isBlank()) {
            return "RELAY"
        }

        mapName = mapName
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .substringAfterLast('|')
            .trim()
            .trim('"', '\'')

        val dotIndex = mapName.lastIndexOf('.')
        if (dotIndex in 1 until mapName.length - 1) {
            val suffix = mapName.substring(dotIndex + 1)
            if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
                mapName = mapName.substring(0, dotIndex)
            }
        }

        return mapName.replace("\\s+".toRegex(), " ").trim().ifBlank { "RELAY" }
    }

    private fun normalizedRoomMapName(roomId: String): String {
        val profile = roomProfile(roomId)
        val mapName = room(roomId)?.currentMapName
            ?.trimStart('\u0001')
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("RELAY", ignoreCase = true) }
            ?: profile?.defaultMapName?.trim()?.takeIf { it.isNotBlank() }
            ?: "RELAY"
        return sanitizeDisplayMapName(mapName)
    }

    private fun roomIsModEnabled(roomId: String): Boolean {
        val profile = roomProfile(roomId)
        return room(roomId)?.isMod == true || profile?.isMod == true
    }

    private fun roomListRefreshSeconds(roomId: String): Int {
        return if (roomProfile(roomId)?.relayServerManaged == true) {
            RelayCustomRoomSupport.relayServerModeConfig().listUpdateSeconds.coerceIn(45, 300)
        } else {
            60
        }
    }

    private fun roomCommandHint(roomId: String, isHostView: Boolean): String {
        val relayRoom = room(roomId)
        val profile = roomProfile(roomId)
        val commands = linkedSetOf(".state", ".help")
        if (isHostView) {
            commands += ".p 15"
            if (profile?.relayServerManaged != true) {
                commands += if (relayRoom?.publicRoom == true) ".tonp" else ".toup"
            }
        }
        return commands.joinToString(" / ")
    }

    private fun roomStatusContext(roomId: String, viewerName: String = "", isHostView: Boolean = false): Map<String, String> {
        val relayRoom = room(roomId)
        val profile = roomProfile(roomId)
        val hostName = relayRoom?.admin?.name?.trim()?.takeIf { it.isNotBlank() } ?: "暂无房主"
        val mapName = normalizedRoomMapName(roomId)
        val playerSize = relayRoom?.getSize() ?: 0
        val modEnabled = roomIsModEnabled(roomId)
        val maxPlayerSize = relayRoom?.currentMaxPlayerSize?.takeIf { it > 0 }
            ?: profile?.maxPlayerSize?.takeIf { it > 0 }
            ?: playerSize.coerceAtLeast(20)
        val refreshSeconds = roomListRefreshSeconds(roomId)
        val joinAddress = RelayDeploymentSupport.roomJoinLink(roomId).orEmpty()
        val commandHint = roomCommandHint(roomId, isHostView)
        val roomMode = when {
            profile?.relayServerManaged == true && modEnabled -> "RELAY-SERVER 模组房"
            profile?.relayServerManaged == true -> "RELAY-SERVER 房间"
            modEnabled -> "模组房"
            profile != null -> "定制房间"
            else -> "标准房间"
        }
        val statusText = when {
            relayRoom?.closeRoom == true -> "已关闭"
            relayRoom?.isStartGame == true -> "游戏中"
            playerSize >= 8 -> "即将开始"
            playerSize > 0 -> "等待中"
            else -> "空闲"
        }
        val publicText = when {
            profile?.relayServerManaged == true && relayRoom?.isStartGame == true -> "已下列表"
            profile?.relayServerManaged == true && relayRoom?.publicRoom == true -> "常驻列表中"
            relayRoom?.publicRoom == true -> "公开中"
            else -> "未公开"
        }
        val viewerRole = when {
            isHostView -> "你已接管房主"
            viewerName.isNotBlank() && viewerName == hostName -> "你已接管房主"
            else -> "当前房主: $hostName"
        }
        val extraText = when {
            profile?.relayServerManaged == true && relayRoom?.isStartGame == true -> "服务器已补充新的空闲入口"
            modEnabled -> "当前为模组房，未加载模组的玩家可能无法正常游玩"
            profile?.relayServerManaged == true && isHostView -> "列表由服务器自动维护，地图与人数会自动同步"
            profile?.relayServerManaged == true -> "房主退出后，房间会自动重置为空闲入口"
            relayRoom?.publicRoom == true -> "当前房间已公开到列表，开局或房间无人时会自动下线"
            isHostView -> "如需公开到列表，请输入 .toup"
            else -> "当前房间未公开到列表"
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

    private fun formatTemplate(
        template: String,
        roomId: String,
        internalId: String = "",
        extraValues: Map<String, String> = emptyMap()
    ): String {
        var formatted = decodeConfiguredText(template)
            .replace("{displayName}", RelayDeploymentSupport.displayName())
            .replace("{shortName}", RelayDeploymentSupport.shortName())
            .replace("{region}", RelayDeploymentSupport.region())
            .replace("{operatorName}", RelayDeploymentSupport.operatorName())
            .replace("{qqGroup}", RelayDeploymentSupport.qqGroup())
            .replace("{listOpenText}", RelayDeploymentSupport.listOpenText())
            .replace("{roomId}", roomId)
            .replace("{displayId}", RelayDeploymentSupport.roomDisplayId(roomId))
            .replace("{lookupId}", RelayDeploymentSupport.roomLookupId(roomId))
            .replace("{legacyDisplayId}", RelayDeploymentSupport.roomLegacyDisplayId(roomId))
            .replace("{shareCode}", RelayDeploymentSupport.roomDisplayId(roomId))
            .replace("{joinLink}", RelayDeploymentSupport.roomJoinLink(roomId).orEmpty())
            .replace("{backupJoinLink}", RelayDeploymentSupport.roomJoinLink(roomId).orEmpty())
            .replace("{shareHint}", preferredShareHint(roomId))
            .replace("{fallbackJoinHint}", fallbackJoinHint(roomId).orEmpty())
            .replace("{nodeCode}", RelayDeploymentSupport.localNodeCode())
            .replace("{internalId}", internalId)
            .replace("{serverVersion}", Data.SERVER_CORE_VERSION)

        extraValues.forEach { (key, value) ->
            formatted = formatted.replace("{$key}", value)
        }
        return formatted
    }

    fun buildRoomWelcome(roomId: String): String {
        val profile = roomProfile(roomId)
        if (profile?.roomWelcome?.isNotBlank() == true) {
            return withFriendlyShare(formatTemplate(profile.roomWelcome, roomId))
        }

        return withFriendlyShare(
            RelayDeploymentSupport.displayName(),
            preferredShareHint(roomId),
            fallbackJoinHint(roomId),
            RelayDeploymentSupport.qqGroup().takeIf { it.isNotBlank() }?.let { "联机QQ群: $it" },
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
            "欢迎加入 ${RelayDeploymentSupport.displayName()}",
            preferredShareHint(roomId),
            fallbackJoinHint(roomId),
            "输入 .state 查看状态，输入 .help 查看命令",
            room(roomId)?.takeIf { it.isMod }?.let { "当前为模组房，未加载模组的玩家可能无法正常游玩" },
            RelayDeploymentSupport.qqGroup().takeIf { it.isNotBlank() }?.let { "联机QQ群: $it" },
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
            preferredShareHint(roomId),
            fallbackJoinHint(roomId),
            "命令: ${roomCommandHint(roomId, true)}",
            room(roomId)?.publicRoom?.let {
                if (it) "当前房间已公开到列表，开局或房间无人时会自动下线"
                else "如需公开到列表，请输入 .toup"
            },
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
        val shareHint = preferredShareHint(roomId)
        val fallbackHint = fallbackJoinHint(roomId)

        configuredTemplate?.let {
            val rendered = formatTemplate(it, roomId, extraValues = context)
            val tail = mutableListOf<String>()
            if (!it.contains("{modeText}")) tail += "模式: ${context.getValue("modeText")}"
            if (!it.contains("{shareCode}") && !it.contains("{shareHint}")) tail += shareHint
            if (!it.contains("{joinAddress}") && joinAddress != null) tail += joinAddress
            if (!it.contains("{fallbackJoinHint}") && fallbackHint != null) tail += fallbackHint
            if (!it.contains("{listRefreshSeconds}")) tail += refreshHint
            if (!it.contains("{commandHint}") && commandHint != null) tail += commandHint
            if (!it.contains("{extraText}")) context.getValue("extraText").takeIf { value -> value.isNotBlank() }?.let(tail::add)
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

    fun buildRelayServerManagedHostNotice(roomId: String): String? {
        val profile = roomProfile(roomId) ?: return null
        if (!profile.relayServerManaged) {
            return null
        }

        val modEnabled = roomIsModEnabled(roomId)
        val defaultNotice = listOfNotNull(
            preferredShareHint(roomId),
            fallbackJoinHint(roomId),
            "当前房间由服务器自动维护",
            "列表由服务器自动维护，无需手动 .toup",
            if (modEnabled) "当前为模组房，未加载模组的玩家可能无法正常游玩" else "当前为标准房",
            "人数上限可使用 .p 15 调整"
        ).joinToString("\n")

        profile.hostClaimNotice.takeIf { it.isNotBlank() }?.let {
            return listOf(
                formatTemplate(it, roomId),
                "命令: ${roomCommandHint(roomId, true)}",
                "列表刷新: 约 ${roomListRefreshSeconds(roomId)} 秒"
            ).joinToString("\n")
        }

        return defaultNotice
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
            "地图已变更: ${context.getValue("mapName")}",
            "人数: ${context.getValue("playerSize")}/${context.getValue("maxPlayerSize")}",
            "模式: ${context.getValue("modeText")}",
            "列表将在约 ${context.getValue("listRefreshSeconds")} 秒后刷新",
            "如需调整人数上限，请输入 .p 15"
        ).joinToString("\n")
    }

    fun buildRoomPlayerLimitUpdatedNotice(roomId: String, autoDetected: Boolean): String {
        val context = roomStatusContext(roomId)
        val title = if (autoDetected) "已根据地图自动调整人数上限" else "房间人数上限已更新"
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
        return if (roomProfile(roomId)?.relayServerManaged == true) {
            listOf(
                "对局已开始，当前房间已从列表下架",
                "服务器已补充新的空闲入口"
            ).joinToString("\n")
        } else {
            listOf(
                "对局已开始，当前房间已从列表下线",
                "回到战役室后可重新使用 .toup 开放"
            ).joinToString("\n")
        }
    }

    fun buildRoomReturnedNotice(roomId: String, restoredToList: Boolean): String {
        return when {
            roomProfile(roomId)?.relayServerManaged == true -> listOf(
                "房间已回到等待状态",
                "当前房间不再占用常驻入口",
                "如需继续公开，请进入新的空闲入口"
            ).joinToString("\n")
            restoredToList -> "房间已回到等待状态，并已恢复到公开列表"
            else -> listOf(
                "房间已回到等待状态",
                "如需重新开放到列表，请输入 .toup"
            ).joinToString("\n")
        }
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
                commands += if (room(roomId)?.publicRoom == true) {
                    ".tonp 从列表移除房间"
                } else {
                    ".toup 公开到列表"
                }
                commands += "公开房会在开局或房间无人时自动下线"
            }
        }
        commands += "列表刷新: 约 ${context.getValue("listRefreshSeconds")} 秒"
        context.getValue("joinAddress").takeIf { it.isNotBlank() }?.let {
            commands += "备用加入: $it"
        }
        return commands.joinToString("\n")
    }

    fun buildRoomPopulationChangedNotice(roomId: String): String {
        val context = roomStatusContext(roomId)
        return listOf(
            "当前人数: ${context.getValue("playerSize")}/${context.getValue("maxPlayerSize")}",
            "状态: ${context.getValue("statusText")}"
        ).joinToString("\n")
    }

    fun buildRoomModModeKickReason(roomId: String): String {
        return listOf(
            "房间已切换为模组模式",
            "请加载模组后重新加入",
            "房间ID: ${RelayDeploymentSupport.roomDisplayId(roomId)}"
        ).joinToString("\n")
    }
}
