/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.command.relay

import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.net.netconnectprotocol.internal.server.chatUserMessagePacketInternal
import net.rwhps.server.net.netconnectprotocol.realize.GameVersionRelay
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.Time
import net.rwhps.server.util.algorithms.HexUtils
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.game.command.CommandHandler
import java.math.BigInteger

/**
 * @author Dr (dr@der.kim)
 */
@PrivateMark
internal class RelayClientCommands(handler: CommandHandler) {
    private val localeUtil = Data.i18NBundle

    private fun isAdmin(con: GameVersionRelay, sendMsg: Boolean = true): Boolean {
        if (con.room == null || con.room!!.admin == null) {
            return false
        }
        if (con.room!!.admin!!.name == con.name && con.room!!.admin!!.registerPlayerId == con.registerPlayerId) {
            return true
        }
        if (sendMsg) {
            sendMsg(con, localeUtil.getinput("err.noAdmin"))
        }
        return false
    }

    init {
        handler.register("help", "clientCommands.help") { _: Array<String>?, con: GameVersionRelay ->
            val str = StringBuilder(16)
            for (command in handler.commandList) {
                if (command.description.startsWith("#")) {
                    str.append("   ").append(command.text).append(if (command.paramText.isEmpty()) "" else " ").append(command.paramText)
                        .append(" - ").append(command.description.substring(1)).append(Data.LINE_SEPARATOR)
                } else {
                    if ("HIDE" == command.description) {
                        continue
                    }
                    str.append("   ").append(command.text).append(if (command.paramText.isEmpty()) "" else " ").append(command.paramText)
                        .append(" - ").append(localeUtil.getinput(command.description)).append(Data.LINE_SEPARATOR)
                }
            }
            sendMsg(con, str.toString())
        }

        handler.register("myid", "clientCommands.-") { _: Array<String>?, con: GameVersionRelay ->
            sendMsg(con, BigInteger(HexUtils.decodeHex(con.registerPlayerId!!)).toInt().let { if (it < 0) -it else it }.toString())
        }

        handler.register("sync", "<on/off>", "#同步") { args: Array<String>, con: GameVersionRelay ->
            if (isAdmin(con, false)) {
                con.room!!.syncFlag = "on" == args[0]
                sendMsg(con, localeUtil.getinput("server.sync", if (con.room!!.syncFlag) "启用" else "禁止"))
            }
        }

        handler.register("rp", "<Name/Position>", "#取代") { args: Array<String>, con: GameVersionRelay ->
            if (isAdmin(con)) {
                val conTg = findPlayerUUIDAll(con, args[0])
                if (conTg[0] != "") {
                    var flag = false
                    con.room!!.abstractNetConnectIntMap.forEach { _, u ->
                        if (conTg[1] == u.registerPlayerId) {
                            flag = true
                            sendMsg(con, "玩家 ${conTg[1]}, 还在线, 不能取代")
                        }
                    }

                    if (flag) {
                        return@register
                    }

                    con.room!!.replacePlayerHex = conTg[0]
                    sendMsg(con, "准备取代玩家 ${conTg[1]}")
                }
            }
        }

        handler.register("kickx", "<Name/Position>", "#Kick Player") { args: Array<String>, con: GameVersionRelay ->
            if (isAdmin(con)) {
                if (con.room!!.isStartGame && (con.room!!.startGameTime + 600) < Time.concurrentSecond()) {
                    sendMsg(con,"已经开局十分钟了 不能再BAN")
                    return@register
                }
                val conTg: GameVersionRelay? = findPlayer(con, args[0])
                conTg?.let {
                    con.room!!.relayKickData["KICK" + it.playerRelay!!.uuid] = Time.concurrentSecond() + 60

                    it.kick("你被踢出服务器")
                    sendMsg(con, "Kick : ${args[0]} OK")
                }
            }
        }

        handler.register("ban", "<Name/Position>", "#Ban Player") { args: Array<String>, con: GameVersionRelay ->
            if (isAdmin(con)) {
                val conTg: GameVersionRelay? = findPlayer(con, args[0])
                conTg?.let {
                    con.room!!.relayKickData["KICK" + it.playerRelay!!.uuid] = Int.MAX_VALUE
                    con.room!!.relayKickData["BAN" + it.ip] = Int.MAX_VALUE
                    it.kick("你被服务器 BAN")
                    sendMsg(con, "BAN : ${args[0]} OK")
                }
            }
        }

        handler.register("allmute", "#All Player mute") { _: Array<String>, con: GameVersionRelay ->
            if (isAdmin(con)) {
                con.room!!.allmute = !con.room!!.allmute
                sendMsg(con, "全局禁言状态是 :  ${if (con.room!!.allmute) "开启" else "关闭"}")
            }
        }

        PluginManage.runRegisterRelayClientCommands(handler)
    }

    private fun sendMsg(con: GameVersionRelay, msg: String) {
        con.sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(msg, "RELAY-CN", 5))
    }

    private fun findPlayer(con: GameVersionRelay, findIn: String): GameVersionRelay? {
        var conTg: GameVersionRelay? = null

        var findNameIn: String? = null
        var findPositionIn: Int? = null

        if (IsUtils.isNumeric(findIn)) {
            findPositionIn = findIn.toInt() - 1
        } else {
            findNameIn = findIn
        }

        findNameIn?.let { findName ->
            var count = 0
            con.room!!.abstractNetConnectIntMap.values.forEach {
                if (it.playerRelay!!.name.contains(findName, ignoreCase = true)) {
                    conTg = it
                    count++
                }
            }
            if (count > 1) {
                sendMsg(con, "目标不止一个, 请不要输入太短的玩家名")
                return@let
            }
            if (conTg == null) {
                sendMsg(con, "找不到玩家")
                return@let
            }
        }

        findPositionIn?.let { findPosition ->
            con.room!!.abstractNetConnectIntMap.values.forEach {
                if (it.playerRelay?.site == findPosition) {
                    conTg = it
                }
            }
            if (conTg == null) {
                sendMsg(con, "找不到玩家")
                return@let
            }
        }

        return conTg
    }

    private fun findPlayerUUIDAll(con: GameVersionRelay, findIn: String): Array<String> {
        val uuidHexTg = arrayOf("", "")

        if (!con.room!!.isStartGame) {
            sendMsg(con, "房间未开始游戏")
            return uuidHexTg
        }

        var findNameIn: String? = null
        var findPositionIn: Int? = null

        if (IsUtils.isNumeric(findIn)) {
            findPositionIn = findIn.toInt() - 1
        } else {
            findNameIn = findIn
        }

        findNameIn?.let { findName ->
            var count = 0
            con.room!!.relayPlayersData.values.forEach {
                if (it.name.contains(findName, ignoreCase = true)) {
                    uuidHexTg[0] = it.con.registerPlayerId!!
                    uuidHexTg[1] = it.con.name
                    count++
                }
            }
            if (count > 1) {
                sendMsg(con, "目标不止一个, 请不要输入太短的玩家名")
                return@let
            }
            if (uuidHexTg[0] == "") {
                sendMsg(con, "找不到玩家")
                return@let
            }
        }

        findPositionIn?.let { findPosition ->
            con.room!!.relayPlayersData.values.forEach {
                if (it.site == findPosition) {
                    uuidHexTg[0] = it.con.registerPlayerId!!
                    uuidHexTg[1] = it.con.name
                }
            }
            if (uuidHexTg[0] == "") {
                sendMsg(con, "找不到玩家")
                return@let
            }
        }

        return uuidHexTg
    }
}