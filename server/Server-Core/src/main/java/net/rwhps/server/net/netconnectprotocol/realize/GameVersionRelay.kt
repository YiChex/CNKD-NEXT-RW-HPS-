/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.netconnectprotocol.realize

import com.vdurmont.emoji.EmojiManager
import net.rwhps.server.data.global.Cache
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.player.PlayerRelay
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.ServerInfoPacket
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.DataPermissionStatus.RelayStatus
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.core.server.AbstractNetConnectData
import net.rwhps.server.net.core.server.AbstractNetConnectRelay
import net.rwhps.server.net.api.RelayDeploymentSupport
import net.rwhps.server.net.api.RelayCustomRoomProfile
import net.rwhps.server.net.api.RelayCustomRoomSupport
import net.rwhps.server.net.api.RelayNodeRoutingSupport
import net.rwhps.server.net.api.RelayPlayerNoticeSupport
import net.rwhps.server.net.api.RelayPublicRoomSupport
import net.rwhps.server.net.netconnectprotocol.UniversalAnalysisOfGamePackages
import net.rwhps.server.net.netconnectprotocol.internal.relay.fromRelayJumpsToAnotherServerInternalPacket
import net.rwhps.server.net.netconnectprotocol.internal.relay.relayServerInitInfoInternalPacket
import net.rwhps.server.net.netconnectprotocol.internal.relay.relayServerTypeInternal
import net.rwhps.server.net.netconnectprotocol.internal.relay.relayServerTypeReplyInternalPacket
import net.rwhps.server.net.netconnectprotocol.internal.server.chatUserMessagePacketInternal
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.Time
import net.rwhps.server.util.algorithms.NetConnectProofOfWork
import net.rwhps.server.util.annotations.MainProtocolImplementation
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.game.GameOtherUtils.getBetaVersion
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.inline.ifNullResult
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.Log.debug
import net.rwhps.server.util.log.Log.error
import java.io.IOException
import java.net.InetAddress
import java.util.*

/**
 * Many thanks to them for providing cloud computing for the project
 * This is essential to complete the RW-HPS Relay test
 * @Thanks : [SimpFun Cloud](https://vps.tiexiu.xyz)
 * @Thanks : [Github 1dNDN](https://github.com/1dNDN)
 *
 * This test was done on :
 * Relay-CN (V. 6.1.0)
 * 2022.7.22 10:00
 */

/**
 * Relay protocol implementation
 * Direct forwarding consumes more bandwidth and the same effect as using VPN forwarding
 *
 * @property permissionStatus       Connection authentication status
 * @property netConnectAuthenticate Connection validity verification
 * @property room                  Relay instance
 * @property site                   Connect the forwarding location within the RELAY
 * @property cachePacket            Cached Package
 * @property relaySelect            Function1<String, Unit>?
 * @property name                   player's name
 * @property registerPlayerId       UUID-Hash code after player registration
 * @property betaGameVersion        Is it a beta version
 * @property clientVersion          clientVersion
 * @property version                Protocol version
 * @constructor
 *
 * @author Dr (dr@der.kim)
 */
@PrivateMark
@MainProtocolImplementation
open class GameVersionRelay(connectionAgreement: ConnectionAgreement): AbstractNetConnect(connectionAgreement), AbstractNetConnectData, AbstractNetConnectRelay {
    /** Client computing proves non-Bot */
    private var netConnectAuthenticate: NetConnectProofOfWork? = null
    private var relaySelect: ((String) -> Unit)? = null

    override var permissionStatus: RelayStatus = RelayStatus.InitialConnection
        internal set

    override var room: RelayRoom? = null
        protected set

    var site = -1
        protected set

    protected var cachePacket: Packet? = null
        private set

    override var name = "NOT NAME"
        protected set

    var playerRelay: PlayerRelay? = null
        internal set

    override var registerPlayerId: String? = null
        protected set
    override var betaGameVersion = false
        protected set
    override var clientVersion = 151
        protected set

    protected val newRelayProtocolVersion = 172
    protected var replacePlayerHex = ""

    private var startGameFlag = true
    private val joinTime = Time.concurrentSecond()
    private var joinMessage = true

    override fun setCachePacket(packet: Packet) {
        cachePacket = packet
    }

    override fun setlastSentPacket(packet: Packet) {
        /* 此协议下不被使用 */
    }

    override val version: String
        get() = "1.15 RELAY"

    override fun sendRelayServerInfo() {
        val cPacket: Packet = Cache.packetCache["sendRelayServerInfo", {
            relayServerInitInfoInternalPacket()
        }]

        try {
            sendPacket(cPacket)
        } catch (e: Exception) {
            error(e)
        }
    }

    @Throws(IOException::class)
    override fun relayDirectInspection(relayRoom: RelayRoom?) {
        GameInputStream(cachePacket!!).use { inStream ->
            inStream.readString()
            val packetVersion = inStream.readInt()
            clientVersion = inStream.readInt()
            betaGameVersion = getBetaVersion(clientVersion)

            if (packetVersion >= 1) {
                inStream.skip(4)
            }
            var queryString = ""
            if (packetVersion >= 2) {
                queryString = inStream.readIsString()
            }
            if (packetVersion >= 3) {
                // Player Name
                inStream.readString()
            }
            if (relayRoom == null) {
                fun tryJoinHintedRelayRoom(hintedRoomId: String): Boolean {
                    RelayCustomRoomSupport.findDefinition(hintedRoomId)?.let { profile ->
                        if (!RelayCustomRoomSupport.shouldHandleOnCurrentNode(profile)) {
                            val relayEndpoint = RelayCustomRoomSupport.resolveRedirectEndpoint(profile)
                            if (relayEndpoint != null) {
                                sendPacket(
                                    fromRelayJumpsToAnotherServerInternalPacket(
                                        "$relayEndpoint/${RelayCustomRoomSupport.redirectLookupId(profile)}"
                                    )
                                )
                            } else {
                                sendRelayServerType("定制房间节点未配置可用入口，请联系管理员")
                            }
                            return true
                        }
                    }

                    room = RelayRoom.getRelay(hintedRoomId)
                    if (room != null) {
                        RelayCustomRoomSupport.findDefinition(hintedRoomId)?.let { RelayCustomRoomSupport.applyProfile(room!!, it) }
                        addRelayConnect()
                        return true
                    }

                    RelayCustomRoomSupport.findDefinition(hintedRoomId)?.let {
                        createConfiguredRelayRoom(it)
                        return true
                    }

                    return false
                }

                UpListMain.resolveRoomIdFromListQuery(queryString)?.let { hintedRoomId ->
                    if (tryJoinHintedRelayRoom(hintedRoomId)) {
                        return
                    }
                }

                UpListMain.resolveRoomIdFromListPort(port)?.let { hintedRoomId ->
                    if (tryJoinHintedRelayRoom(hintedRoomId)) {
                        return
                    }
                }
                if (IsUtils.isBlank(queryString) || "FANCYCN".equals(queryString, ignoreCase = true)) {
                    if (RelayCustomRoomSupport.isRelayServerModeEnabled()) {
                        if (!tryAssignRelayServerManagedRoom(port)) {
                            sendRelayServerType("RELAY-SERVER 默认房间暂不可用，请稍后重试")
                        }
                    } else {
                        sendRelayServerType(RelayDeploymentSupport.buildEntryWelcome(Data.SERVER_CORE_VERSION))
                    }
                } else {
                    idCustom(queryString)
                }
            } else {
                this.room = relayRoom
                addRelayConnect()
            }
        }
    }

    override fun sendVerifyClientValidity() {
        netConnectAuthenticate = RelayRoom.randPow
        val netConnectAuthenticate: NetConnectProofOfWork = netConnectAuthenticate!!
        val authenticateType = netConnectAuthenticate.authenticateType.toInt()
        try {
            val o = GameOutputStream()
            // 返回相同
            o.writeInt(netConnectAuthenticate.resultInt)
            o.writeInt(authenticateType)
            if (authenticateType == 0 || netConnectAuthenticate.authenticateType in 2 .. 4 || authenticateType == 6) {
                o.writeBoolean(true)
                o.writeInt(netConnectAuthenticate.initInt_1)
            } else {
                o.writeBoolean(false)
            }
            if (authenticateType == 1 || netConnectAuthenticate.authenticateType in 2 .. 4) {
                o.writeBoolean(true)
                o.writeInt(netConnectAuthenticate.initInt_2)
            } else {
                o.writeBoolean(false)
            }
            if (netConnectAuthenticate.authenticateType in 5 .. 6) {
                o.writeString(netConnectAuthenticate.outcome)
                o.writeString(netConnectAuthenticate.fixedInitial)
                o.writeInt(netConnectAuthenticate.maximumNumberOfCalculations)
            }

            o.writeBoolean(false)

            sendPacket(o.createPacket(PacketType.RELAY_POW))
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun receiveVerifyClientValidity(packet: Packet): Boolean {
        try {
            GameInputStream(packet).use { inStream ->
                if (netConnectAuthenticate != null) {
                    if (netConnectAuthenticate!!.verifyPOWResult(inStream.readInt(), inStream.readInt(), inStream.readString())) {
                        netConnectAuthenticate = null
                        return true
                    }
                } else {
                    // Ignore, Under normal circumstances, it should not reach here, and the processor will handle it
                }
            }
        } catch (_: Exception) {
            // Ignore, There should be no errors in this part, errors will only come from constructing false error packets
        }
        return false
    }

    override fun sendRelayServerType(msg: String, run: ((String) -> Unit)?) {
        try {
            sendPacket(relayServerTypeInternal(msg))

            relaySelect = run

            connectReceiveData.inputPassword = true
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun sendRelayServerTypeReply(packet: Packet) {
        try {
            connectReceiveData.inputPassword = false

            val id = relayServerTypeReplyInternalPacket(packet)
            if (relaySelect == null) {
                idCustom(id)
            } else {
                relaySelect!!(id)
            }
        } catch (e: Exception) {
            error(e)
        }
    }

    @Throws(IOException::class)
    override fun receiveChat(packet: Packet) {
        GameInputStream(packet).use { inStream ->
            if (permissionStatus.ordinal < RelayStatus.PlayerJoinPermission.ordinal) {
                return
            }

            val message: String = inStream.readString()

            if (showChatInConsole) {
                Log.info("[Room ${RelayDeploymentSupport.roomDisplayId(room!!.id)}][${name}]: $message")
            }

            if (room!!.allmute || playerRelay!!.mute > Time.concurrentSecond()) {
                return
            }

            room!!.touchActivity()
            playerRelay!!.lastSentMessage = message

            run command@{
                if (message.startsWith(".") || message.startsWith("-")) {
                    val response = Data.RELAY_COMMAND.handleMessage(message, this) ?: return@command

                    when (response.type) {
                        CommandHandler.ResponseType.noCommand, CommandHandler.ResponseType.valid -> {
                            // Ignore
                        }
                        CommandHandler.ResponseType.manyArguments -> {
                            val msg = "Too many arguments. Usage: " + response.command.text + " " + response.command.paramText
                            sendPacket(rwHps.abstractNetPacket.getSystemMessagePacket(msg))
                        }
                        CommandHandler.ResponseType.fewArguments -> {
                            val msg = "Too few arguments. Usage: " + response.command.text + " " + response.command.paramText
                            sendPacket(rwHps.abstractNetPacket.getSystemMessagePacket(msg))
                        }
                        else -> {
                            sendPackageToHOST(packet)
                        }
                    }
                }
            }

            sendPackageToHOST(packet)
        }
    }

    @Throws(IOException::class)
    override fun receiveCommand(packet: Packet) {
        sendPackageToHOST(packet)
    }

    private fun sendRelayBecomeServerPacket(multicast: Boolean = false) {
        val currentRoom = room ?: return
        val public = false
        val o = GameOutputStream()
        if (clientVersion >= newRelayProtocolVersion) {
            o.writeByte(2)
            o.writeBoolean(true)
            o.writeBoolean(true)
            o.writeBoolean(true)
            o.writeString(currentRoom.serverUuid)
            o.writeBoolean(currentRoom.isMod)
            o.writeBoolean(public)
            o.writeBoolean(true)
            o.writeString(RelayPlayerNoticeSupport.buildRoomWelcome(currentRoom.id))
            o.writeBoolean(multicast)
            o.writeIsString(registerPlayerId)
        } else {
            o.writeByte(1)
            o.writeBoolean(true)
            o.writeBoolean(true)
            o.writeIsString(currentRoom.serverUuid)
            o.writeBoolean(currentRoom.isMod)
            o.writeBoolean(public)
            o.writeString(RelayPlayerNoticeSupport.buildRoomWelcome(currentRoom.id))
            o.writeBoolean(multicast)
        }
        sendPacket(o.createPacket(PacketType.RELAY_BECOME_SERVER))
    }

    override fun sendRelayServerId(multicast: Boolean) {
        try {
            connectReceiveData.inputPassword = false
            if (room == null) {
                Log.clog("sendRelayServerId -> relay : null")
                room = NetStaticData.relayRoom
            }

            val hostTransferred = site != -1
            if (site != -1) {
                room!!.removeAbstractNetConnect(site)
                // 这个代表是第二任 (后妈)
                site = -2
            }

            room!!.admin = this
            UpListMain.requestRefresh()

            sendRelayBecomeServerPacket(multicast)
            sendPacket(
                    rwHps.abstractNetPacket.getChatMessagePacket(
                            RelayPlayerNoticeSupport.buildHostChatNotice(
                                room!!.id,
                                Data.configRelay.mainID + room!!.internalID.toString()
                            ), RelayDeploymentSupport.chatSender(room!!.id), 5
                    )
            )
            RelayPlayerNoticeSupport.buildRelayServerManagedHostNotice(room!!.id)?.let {
                sendPacket(
                    rwHps.abstractNetPacket.getChatMessagePacket(
                        it,
                        RelayDeploymentSupport.chatSender(room!!.id),
                        5
                    )
                )
            }
            if (room!!.customRoomProfile?.relayServerManaged != true) {
                sendRoomStatusNoticeToSelf(isHostView = true)
            }
            if (hostTransferred) {
                broadcastRoomNotice(RelayPlayerNoticeSupport.buildRoomHostTransferredNotice(room!!.id))
            }

            // 人即像树，树枝越向往光明的天空，树根越伸向阴暗的地底
            /**
             * 禁止玩家使用 Server/Relay 做玩家名
             */
            if (name.equals("SERVER", ignoreCase = true) || name.equals("RELAY", ignoreCase = true)) {
                room!!.re() // Close Room
            }
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun getRelayT4(msg: String) {
        try {
            sendPacket(chatUserMessagePacketInternal(msg))
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun getPingData(packet: Packet) {
        try {
            GameInputStream(packet).use { inStream ->
                val out = GameOutputStream()
                out.transferToFixedLength(inStream, 8)
                // 将协议版本降低 (0), 来减少发送一个字节
                out.writeByte(0)
                sendPacket(out.createPacket(PacketType.HEART_BEAT_RESPONSE))
            }
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    override fun addGroup(packet: Packet) {
        room!!.groupNet.broadcastAndUDP(packet)
    }

    override fun addGroupPing(packet: Packet) {
        try {
            room!!.groupNet.broadcastAndUDP(packet)
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    override fun addRelayConnect() {
        try {
            permissionStatus = RelayStatus.PlayerPermission

            connectReceiveData.inputPassword = false
            if (room == null) {
                Log.clog("?????")
                room = NetStaticData.relayRoom
            }


            site = room!!.setAddPosition()
            room!!.setAbstractNetConnect(this)

            val o = GameOutputStream()
            if (clientVersion >= newRelayProtocolVersion) {
                o.writeByte(1)
                o.writeInt(site)
                // ?
                o.writeString(registerPlayerId!!)
                //o.writeBoolean(false)
                // User UUID
                o.writeIsString(null)
                o.writeIsString(ip)
                room!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            } else {
                o.writeByte(0)
                o.writeInt(site)
                o.writeString(registerPlayerId!!)
                o.writeIsString(null)
                room!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            }

            sendPackageToHOST(cachePacket!!)
            connectionAgreement.add(room!!.groupNet)
            sendPacket(
                    rwHps.abstractNetPacket.getChatMessagePacket(
                            RelayPlayerNoticeSupport.buildRoomChatNotice(room!!.id), RelayDeploymentSupport.chatSender(room!!.id), 5
                    )
            )
            this.room!!.setAddSize()
            this.room!!.touchActivity()
            sendRoomStatusNoticeToSelf()
            if (this.room!!.getSize() > 1 && !this.room!!.suppressPopulationNotice) {
                broadcastRoomNotice(RelayPlayerNoticeSupport.buildRoomPopulationChangedNotice(room!!.id))
            }
        } catch (e: Exception) {
            permissionStatus = RelayStatus.CertifiedEnd

            connectionAgreement.remove(room!!.groupNet)

            error("[Relay] addRelayConnect", e)

            relayDirectInspection()
            return
        } finally {
            //cachePacket = null;
        }
    }

    override fun relayRegisterConnection(packet: Packet) {
        var sendPacket = packet

        if (registerPlayerId.isNullOrBlank()) {
            try {
                GameInputStream(packet).use { stream ->
                    stream.readString()
                    stream.skip(12)
                    name = stream.readString()
                    stream.readIsString()
                    stream.readString()
                    registerPlayerId = stream.readString()
                }
            } catch (e: Exception) {
                error("[No UUID-Hex]", e)
            }
        } else if (permissionStatus.ordinal >= RelayStatus.PlayerPermission.ordinal) {
            if (permissionStatus == RelayStatus.PlayerPermission) {
                if (!room!!.isStartGame && room!!.abstractNetConnectIntMap.find { _, player ->
                        /**
                         * 通过检测房间已有的 UUID-Hex, 来激进的解决一些问题
                         *
                         * Luke 对此的回答 :
                         * True might make more sense, but a connection might be stale for a bit need to be careful with that.
                         * example just after a crash
                         */
                        if (player.permissionStatus == RelayStatus.PlayerJoinPermission && player.registerPlayerId == registerPlayerId) {
                            kick("[UUID Check] HEX 重复, 换个房间试试")
                            return@find true
                        }
                        return@find false
                    } != null) {
                    return
                }

                // Relay-EX
                if (playerRelay == null) {
                    playerRelay = room!!.relayPlayersData[registerPlayerId] ?: PlayerRelay(this, registerPlayerId!!, name).also {
                        room!!.relayPlayersData[registerPlayerId!!] = it
                    }
                    playerRelay!!.nowName = name
                    playerRelay!!.disconnect = false
                    playerRelay!!.con = this
                }

                if (room!!.relayKickData.containsKey("BAN$ip")) {
                    kick("[BAN] 您被这个房间BAN了 请换一个房间")
                    return
                }

                val time: Int? = room!!.relayKickData["KICK$registerPlayerId"].ifNullResult(
                        room!!.relayKickData["KICK${connectionAgreement.ipLong24}"]
                ) { null }

                if (time != null) {
                    if (time > Time.concurrentSecond()) {
                        kick("[踢出等待] 您被这个房间踢出了 请稍等一段时间 或者换一个房间")
                        return
                    } else {
                        room!!.relayKickData.remove("KICK$registerPlayerId")
                        room!!.relayKickData.remove("KICK${connectionAgreement.ipLong24}")
                    }
                }

                if (room!!.isStartGame) {
                    if (!room!!.syncFlag) {
                        kick("[Sync Lock] 这个房间拒绝重连")
                        return
                    }

                    if (playerRelay!!.playerSyncCount.checkStatus()) {
                        playerRelay!!.playerSyncCount.count++
                    } else {
                        room!!.relayKickData["KICK$registerPlayerId"] = 300
                        kick("[同步检测] 您同步次数太多 请稍等一段时间 或者换一个房间")
                        return
                    }
                }
            }

            if (room!!.isStartGame) {
                if (room!!.replacePlayerHex != "") {
                    replacePlayerHex = room!!.replacePlayerHex
                    room!!.replacePlayerHex = ""
                    room!!.sendMsg("玩家 $name, 取代了旧玩家")
                }
                if (replacePlayerHex != "") {
                    val out = GameOutputStream()
                    GameInputStream(packet).use { stream ->
                        out.writeString(stream.readString())
                        out.transferToFixedLength(stream, 12)
                        out.writeString(stream.readString())
                        out.writeIsString(stream)
                        out.writeString(stream.readString())
                        out.writeString(replacePlayerHex)
                        stream.readString()
                        out.transferTo(stream)
                    }
                    sendPacket = out.createPacket(PacketType.REGISTER_PLAYER)

                }
            }

            permissionStatus = RelayStatus.PlayerJoinPermission
            room!!.touchActivity()
            sendPackageToHOST(sendPacket)
        }
    }

    override fun addReRelayConnect() {
        try {
            val o = GameOutputStream()
            if (clientVersion >= newRelayProtocolVersion) {
                o.writeByte(1)
                o.writeInt(site)
                // ?
                o.writeString(registerPlayerId!!)
                // User UUID
                o.writeIsString(registerPlayerId!!)
                o.writeIsString(ip)
                room!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            } else {
                o.writeByte(0)
                o.writeInt(site)
                o.writeString(registerPlayerId!!)
                o.writeIsString(registerPlayerId!!)
                room!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            }
            room!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            val o1 = GameOutputStream()
            o1.writeInt(site)
            o1.writeInt(cachePacket!!.bytes.size + 8)
            o1.writeInt(cachePacket!!.bytes.size)
            o1.writeInt(160)
            o1.writeBytes(cachePacket!!.bytes)
            room!!.admin!!.sendPacket(o1.createPacket(PacketType.PACKET_FORWARD_CLIENT_FROM))
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        } finally {
            //cachePacket = null;
        }
    }

    override fun addRelaySend(packet: Packet) {
        try {
            GameInputStream(packet).use { inStream ->
                val target = inStream.readInt()

                val type = inStream.readInt()
                val bytes = inStream.readStreamBytes()

                val abstractNetConnect = room!!.getAbstractNetConnect(target)

                Packet(type, bytes).let { sendPacketData ->
                    abstractNetConnect?.sendPacket(sendPacketData)
                    sendPacketExtractInformation(sendPacketData, abstractNetConnect)
                    room!!.touchActivity()
                }
            }
        } catch (e: Exception) {
            error("[RELAY NormalForwarding Error]", e)
        }
    }

    override fun sendPackageToHOST(packet: Packet) {
        try {
            val o = GameOutputStream()
            o.writeInt(site)
            o.writeInt(packet.bytes.size + 8)
            o.writeInt(packet.bytes.size)
            o.writeBytes(packet.type.typeIntBytes)
            o.writeBytes(packet.bytes)
            room!!.admin?.sendPacket(o.createPacket(PacketType.PACKET_FORWARD_CLIENT_FROM))
            room!!.touchActivity()
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    override fun relayPlayerDisconnect() {
        try {
            val out = GameOutputStream()
            out.writeByte(0)
            out.writeInt(site)
            sendPackageToHOST(out.createPacket(PacketType.FORWARD_CLIENT_REMOVE))
        } catch (e: IOException) {
            Log.error("[D-30] Unexpected exception", e)
        }
    }

    override fun multicastAnalysis(packet: Packet) {
        // Protocol not supported
    }

    internal fun kick(msg: String) {
        val o = GameOutputStream()
        o.writeString(msg)
        sendPacket(o.createPacket(PacketType.KICK))
        disconnect()
    }

    private fun sendRoomStatusNoticeToSelf(isHostView: Boolean = false) {
        room?.let { relayRoom ->
            sendPacket(
                rwHps.abstractNetPacket.getChatMessagePacket(
                    RelayPlayerNoticeSupport.buildRoomStatusNotice(relayRoom.id, name, isHostView),
                    RelayDeploymentSupport.chatSender(relayRoom.id),
                    5
                )
            )
        }
    }

    private fun broadcastRoomNotice(message: String) {
        room?.let { relayRoom ->
            relayRoom.broadcastChatNotice(message, RelayDeploymentSupport.chatSender(relayRoom.id), 5)
        }
    }

    private fun kickRelayPlayersForModeSwitch(currentRoom: RelayRoom): Int {
        val currentPlayers = mutableListOf<GameVersionRelay>().also { players ->
            currentRoom.abstractNetConnectIntMap.values.forEach { player ->
                players += player
            }
        }
        if (currentPlayers.isEmpty()) {
            return 0
        }

        val kickReason = RelayPlayerNoticeSupport.buildRoomModModeKickReason(currentRoom.id)
        currentRoom.setSuppressPopulationNotice(true)
        try {
            currentPlayers.forEach { player ->
                try {
                    player.kick(kickReason)
                } catch (_: Exception) {
                    player.disconnect()
                }
            }
        } finally {
            currentRoom.setSuppressPopulationNotice(false)
        }
        return currentPlayers.size
    }

    private fun forceRefreshRelayRoomPublicState(currentRoom: RelayRoom) {
        val shouldKeepPublic = currentRoom.customRoomProfile?.relayServerManaged == true || currentRoom.publicRoom
        if (shouldKeepPublic) {
            currentRoom.setPublicRoom(true, top = true)
            RelayPublicRoomSupport.scheduleSuccessNotice(currentRoom)
        }
        UpListMain.requestRefresh(force = true)
    }

    override fun disconnect() {
        if (super.isDis) {
            return
        }
        super.isDis = true

        connectReceiveData.receiveBigPacket = false

        if (permissionStatus.ordinal >= RelayStatus.PlayerPermission.ordinal) {
            room!!.setRemoveSize()

            // 避免多个玩家断开导致 NPE
            synchronized(room!!) {
                try {
                    if (permissionStatus == RelayStatus.HostPermission) {
                        RelayRoom.serverRelayIpData.remove(ip)
                        if (room!!.getSize() > 0) {
                                // Move Room Admin
                                adminMoveNew()
                                if (room!!.admin == null && room!!.publicRoom) {
                                    room!!.setPublicRoom(false, top = false)
                                    room!!.broadcastChatNotice("当前房间暂无房主接任，已自动从列表下线", RelayDeploymentSupport.chatSender(room!!.id), 5)
                                }
                        } else {
                            // Close Room
                            room!!.re()
                        }
                    } else {
                        room!!.removeAbstractNetConnect(site)

                        // 解决一些问题, 让 HOST 删掉无用转发ID
                        relayPlayerDisconnect()

                        if (permissionStatus >= RelayStatus.PlayerJoinPermission) {
                            if (!room!!.isStartGame) {
                                room!!.relayPlayersData.remove(registerPlayerId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!room!!.closeRoom) {
                        debug("[Relay Close Error]", e)
                    }
                }
                if ((room!!.getSize() <= 0 && !room!!.closeRoom)) {
                    debug("[Relay] Game over")
                    room!!.re()
                } else if (!room!!.closeRoom && !room!!.suppressPopulationNotice) {
                    broadcastRoomNotice(RelayPlayerNoticeSupport.buildRoomPopulationChangedNotice(room!!.id))
                }
                room!!.touchActivity()
                super.close(room!!.groupNet)
            }
        } else {
            super.close(null)
        }
    }

    open fun sendPacketExtractInformation(packet: Packet, abstractNetConnect: GameVersionRelay?) {
        when (packet.type) {
            PacketType.SERVER_INFO -> {
                if (room?.admin === this) {
                    try {
                        val oldMapName = room!!.currentMapName.trim().trimStart('\u0001')
                        val newMapName = ServerInfoPacket(packet.bytes).mapName.trim()
                        if (newMapName.isNotBlank()) {
                            room!!.setCurrentMapName(newMapName)
                            tryAutoAdjustRelayRoomMaxPlayer(newMapName)
                        }
                        if (newMapName.isNotBlank() && !oldMapName.equals(newMapName.trimStart('\u0001'), ignoreCase = true)) {
                            broadcastRoomNotice(RelayPlayerNoticeSupport.buildRoomMapUpdatedNotice(room!!.id))
                        }
                    } catch (_: Exception) {
                        // Ignore invalid packet content
                    }
                }
            }
            PacketType.TEAM_LIST -> {
                if (!room!!.isStartGame) {
                    abstractNetConnect?.let {
                        UniversalAnalysisOfGamePackages.getPacketTeamData(GameInputStream(packet.bytes, it.clientVersion), it.playerRelay!!)
                    }
                }
                if (abstractNetConnect != null && abstractNetConnect.startGameFlag && site == -2 && room!!.gamePacket.startGamePacket != null) {
                    abstractNetConnect.sendPacket(room!!.gamePacket.startGamePacket!!)
                    abstractNetConnect.startGameFlag = false
                }
            }
            PacketType.RETURN_TO_BATTLEROOM -> {
                if (room!!.isStartGame) {
                    room!!.isStartGame = false
                    room!!.gamePacket.startGamePacket = null
                    room!!.touchActivity()
                    var restoredToList = false
                    if (room!!.customRoomProfile?.forcePublicRoom == true && room!!.customRoomProfile?.relayServerManaged == true) {
                        room!!.setPublicRoom(true, top = false)
                        restoredToList = true
                    }
                    broadcastRoomNotice(RelayPlayerNoticeSupport.buildRoomReturnedNotice(room!!.id, restoredToList))
                }
            }
            PacketType.START_GAME -> {
                val shouldNotify = room!!.publicRoom || room!!.customRoomProfile?.relayServerManaged == true
                if (
                    Data.configRelay.decision.recyclePublicRoomOnStartGame &&
                    room!!.publicRoom
                ) {
                    room!!.setPublicRoom(false, top = false)
                }
                room!!.isStartGame = true
                room!!.touchActivity()
                if (shouldNotify) {
                    broadcastRoomNotice(RelayPlayerNoticeSupport.buildRoomStartedNotice(room!!.id))
                }
                if (site == -1 && room!!.gamePacket.startGamePacket == null) {
                    GameInputStream(packet).use {
                        it.skip(1)
                        if (it.readInt() != 0) {
                            room!!.gamePacket.startGamePacket = packet
                        }
                    }
                }
            }
            else -> {
                // 没其他必要
            }
        }
    }

    private fun idCustom(inId: String) {
        val rawRequestedId = inId.trim()
        var id = normalizeRelayInputId(rawRequestedId)

        if ("old".equals(id, ignoreCase = true)) {
            id = RelayRoom.serverRelayOld[registerPlayerId!!, ""]
        } else {
            RelayRoom.serverRelayOld[registerPlayerId!!] = id
        }
        val normalizedRequestedId = id

        if (id.isEmpty()) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.server.error.id", "什么都没输入就点击确认"))
            return
        }
        if (EmojiManager.containsEmoji(id)) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.server.error.id", "不能使用Emoji"))
            return
        }

        val requestedNodeCode = RelayNodeRoutingSupport.requestedNodeCode(id)
        val relayTarget = resolveRelayRedirectTarget(id)
        if (requestedNodeCode != null) {
            if (Data.configRelay.mainServer && relayTarget != null) {
                sendPacket(fromRelayJumpsToAnotherServerInternalPacket("$relayTarget/$id"))
                return
            }

            if (!Data.configRelay.mainServer && RelayNodeRoutingSupport.shouldHandleOnLocalNode(requestedNodeCode)) {
                id = RelayDeploymentSupport.stripRelayLookupPrefix(id, requestedNodeCode)
            } else if (relayTarget != null) {
                sendPacket(fromRelayJumpsToAnotherServerInternalPacket("$relayTarget/$id"))
                return
            } else {
                id = RelayDeploymentSupport.stripRelayLookupPrefix(id, requestedNodeCode)
            }
        } else if ("R".equals(id[0].toString(), ignoreCase = true)) {
            id = id.substring(1)
        }

        if (id.isEmpty()) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.server.no", "R"))
            return
        }

        RelayCustomRoomSupport.findDefinition(id)?.let { profile ->
            if (!RelayCustomRoomSupport.shouldHandleOnCurrentNode(profile)) {
                val relayEndpoint = RelayCustomRoomSupport.resolveRedirectEndpoint(profile)
                if (relayEndpoint != null) {
                    sendPacket(
                        fromRelayJumpsToAnotherServerInternalPacket(
                            "$relayEndpoint/${RelayCustomRoomSupport.redirectLookupId(profile)}"
                        )
                    )
                } else {
                    sendRelayServerType("定制房间节点未配置可用入口，请联系管理员")
                }
                return
            }

            room = RelayRoom.getRelay(profile.roomId)
            if (room != null) {
                RelayCustomRoomSupport.applyProfile(room!!, profile)
                addRelayConnect()
            } else {
                if ((profile.autoPublicRoom || profile.forcePublicRoom) && Data.configRelay.decision.enableSinglePublicRoomPerIpLimit) {
                    RelayPublicRoomSupport.findConflict(ip)?.let {
                        sendRelayServerType(RelayPublicRoomSupport.buildConflictMessage(it))
                        return
                    }
                }
                createConfiguredRelayRoom(profile)
            }
            return
        }

        var uplist = false
        var mods = id.contains("mod", ignoreCase = true)
        var customId = ""
        var newRoom = true

        if ("C".equals(id[0].toString(), ignoreCase = true)) {
            id = id.substring(1)
            if (id.isEmpty()) {
                sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
                return
            }

            val ary = id.split("@")
            id = if (ary.size > 1) {
                ary[1]
            } else {
                ""
            }
            customId = ary[0]

            if ("M".equals(customId[0].toString(), ignoreCase = true)) {
                customId = customId.substring(1)
                if (!checkLength(customId)) {
                    return
                }
                if (RelayRoom.getCheckRelay(customId) || resolveRelayRedirectTarget("R$customId") != null || RelayCustomRoomSupport.isReservedRoomId(customId)) {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
                    return
                }
                mods = true
            } else {
                if (!checkLength(customId)) {
                    return
                }
                if (RelayRoom.getCheckRelay(customId) || resolveRelayRedirectTarget("R$customId") != null || RelayCustomRoomSupport.isReservedRoomId(customId)) {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
                    return
                }
            }
        } else {
            when {
                id.startsWith("news", ignoreCase = true) || id.startsWith("mods", ignoreCase = true) -> {
                    uplist = true
                    id = id.substring(4)
                }
                id.startsWith("new", ignoreCase = true) || id.startsWith("mod", ignoreCase = true) -> {
                    id = id.substring(3)
                }
                else -> {
                    newRoom = false
                }
            }
        }

        if (newRoom) {
            if (uplist) {
                RelayPublicRoomSupport.findConflict(ip)?.let {
                    sendRelayServerType(RelayPublicRoomSupport.buildConflictMessage(it))
                    return
                }
            }
            if (uplist && Data.configRelay.mainServer && Data.configRelay.decision.enableSmartPublicRoomDispatch) {
                val selectedNode = RelayNodeRoutingSupport.selectNodeForPublicRoom()
                if (selectedNode != null) {
                    val relayEndpoint = RelayNodeRoutingSupport.redirectEndpoint(selectedNode.code)
                    if (relayEndpoint != null) {
                        sendPacket(
                            fromRelayJumpsToAnotherServerInternalPacket(
                                "$relayEndpoint/R${selectedNode.code}$normalizedRequestedId"
                            )
                        )
                        return
                    }
                } else if (!Data.configRelay.decision.fallbackToCoreWhenNoNodeAvailable) {
                    sendRelayServerType("当前没有可用的公开房子节点，请稍后重试")
                    return
                }
            }

            if (uplist && !Data.configRelay.mainServer && !RelayNodeRoutingSupport.canOpenPublicRoomLocally()) {
                if (redirectToCoreForPublicRoom(normalizedRequestedId)) {
                    return
                }
                sendRelayServerType(RelayNodeRoutingSupport.localPublicRoomLimitMessage())
                return
            }
            if (uplist && Data.configRelay.mainServer && !RelayNodeRoutingSupport.canOpenPublicRoomLocally()) {
                sendRelayServerType(RelayNodeRoutingSupport.localPublicRoomLimitMessage())
                return
            }

            val custom = CustomRelayData()

            try {
                if (id.startsWith("P", ignoreCase = true)) {
                    id = id.substring(1)
                    val arry = if (id.contains("，")) id.split("，") else id.split(",")
                    custom.maxPlayerSize = arry[0].toInt()
                    if (custom.maxPlayerSize !in 0 .. 100) {
                        sendRelayServerType(Data.i18NBundle.getinput("relay.id.maxPlayer.re"))
                        return
                    }
                    if (arry.size > 1) {
                        if (arry[1].contains("I", ignoreCase = true)) {
                            val ay = arry[1].split("I", ignoreCase = true)
                            if (ay.size > 1) {
                                custom.maxUnitSizt = ay[0].toInt()
                                custom.income = ay[1].toFloat()
                            } else {
                                custom.income = ay[0].toFloat()
                            }
                        } else {
                            custom.maxUnitSizt = arry[1].toInt()
                        }
                        if (custom.maxUnitSizt !in 0 .. Int.MAX_VALUE) {
                            sendRelayServerType(Data.i18NBundle.getinput("relay.id.maxUnit.re"))
                            return
                        }
                    }
                }
                if (id.startsWith("I", ignoreCase = true)) {
                    id = id.substring(1)
                    val arry = if (id.contains("，")) id.split("，") else id.split(",")
                    custom.income = arry[0].toFloat()
                    if (custom.income !in 0F .. Float.MAX_VALUE) {
                        sendRelayServerType(Data.i18NBundle.getinput("relay.id.income.re"))
                        return
                    }
                }
            } catch (e: NumberFormatException) {
                sendRelayServerType(Data.i18NBundle.getinput("relay.server.error", e.message))
                return
            }

            newRelayId(customId, mod = mods, customRelayData = custom, publicRoom = uplist)
            if (custom.maxPlayerSize != -1 || custom.maxUnitSizt != 200) {
                sendPacket(
                        rwHps.abstractNetPacket.getChatMessagePacket(
                                "自定义人: ${custom.maxPlayerSize} 自定义单位: ${custom.maxUnitSizt}", "RELAY_CN-Custom", 5
                        )
                )
            }
        } else {
            try {
                if (id.contains(".")) {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.server.error", "不能包含 [ . ]"))
                    return
                }
                room = RelayRoom.getRelay(id)
                if (room == null) {
                    RelayDeploymentSupport.resolveDisplayRoomIdWithLocalFallback(normalizedRequestedId)?.let { resolvedRoomId ->
                        room = RelayRoom.getRelay(resolvedRoomId)
                    }
                }
                if (room == null) {
                    RelayDeploymentSupport.resolveDisplayRoomIdWithLocalFallback(rawRequestedId)?.let { resolvedRoomId ->
                        room = RelayRoom.getRelay(resolvedRoomId)
                    }
                }
                if (room == null) {
                    resolveRelayRoomByFallbackCandidates(id, rawRequestedId)?.let { resolvedRoom ->
                        room = resolvedRoom
                    }
                }
                if (room != null) {
                    addRelayConnect()
                } else {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.server.no", id))
                }
            } catch (e: Exception) {
                debug(e)
                sendRelayServerType(Data.i18NBundle.getinput("relay.server.error", e.message))
            }
        }
    }

    private fun resolveRelayRedirectTarget(id: String): String? {
        val nodeCode = RelayNodeRoutingSupport.requestedNodeCode(id) ?: return null
        return RelayNodeRoutingSupport.redirectEndpoint(nodeCode)
    }

    private fun resolveRelayRoomByFallbackCandidates(vararg currentIds: String): RelayRoom? {
        val candidates = linkedSetOf<String>()

        currentIds.forEach { currentId ->
            val normalizedId = currentId.trim().uppercase(Locale.ROOT)
            if (normalizedId.isBlank()) {
                return@forEach
            }

            candidates += normalizedId
            RelayDeploymentSupport.resolveDisplayRoomIdWithLocalFallback(normalizedId)?.let { candidates += it }
        }

        candidates.forEach { candidate ->
            RelayRoom.getRelay(candidate)?.let { return it }
            RelayDeploymentSupport.resolveDisplayRoomIdWithLocalFallback(candidate)?.let { resolvedRoomId ->
                RelayRoom.getRelay(resolvedRoomId)?.let { return it }
            }
        }

        return null
    }

    private fun tryAssignRelayServerManagedRoom(preferredPort: Int = 0): Boolean {
        if (!RelayCustomRoomSupport.isRelayServerModeEnabled()) {
            return false
        }

        val managedProfiles = RelayCustomRoomSupport.relayServerManagedProfiles()
        if (managedProfiles.isEmpty()) {
            return false
        }

        if (preferredPort > 0) {
            RelayCustomRoomSupport.findRelayServerManagedProfileByPort(preferredPort)?.let { profile ->
                return assignRelayServerManagedProfile(profile)
            }
        }

        val emptyProfile = managedProfiles.firstOrNull { profile ->
            val relayRoom = RelayRoom.getRelay(profile.roomId)
            relayRoom == null ||
                relayRoom.closeRoom ||
                relayRoom.isStartGame ||
                relayRoom.admin == null ||
                relayRoom.getSize() <= 0
        }

        if (emptyProfile != null) {
            return assignRelayServerManagedProfile(emptyProfile)
        }

        val activeTarget = managedProfiles
            .mapNotNull { profile ->
                RelayRoom.getRelay(profile.roomId)
                    ?.takeIf { !it.closeRoom && !it.isStartGame && it.admin != null && it.getSize() > 0 }
                    ?.let { profile to it }
            }
            .minByOrNull { (_, relayRoom) -> relayRoom.getSize() }
            ?: return false

        return assignRelayServerManagedProfile(activeTarget.first)
    }

    private fun assignRelayServerManagedProfile(profile: RelayCustomRoomProfile): Boolean {
        val relayRoom = RelayRoom.getRelay(profile.roomId)
        if (relayRoom == null || relayRoom.closeRoom || relayRoom.isStartGame) {
            createConfiguredRelayRoom(profile)
            return true
        }

        room = relayRoom
        RelayCustomRoomSupport.applyProfile(room!!, profile)
        return if (relayRoom.admin == null || relayRoom.getSize() <= 0) {
            room!!.setAddSize()
            sendRelayServerId()
            true
        } else {
            addRelayConnect()
            true
        }
    }

    private fun redirectToCoreForPublicRoom(requestedId: String): Boolean {
        val coreEndpoint = RelayNodeRoutingSupport.coreEndpoint() ?: return false
        sendPacket(fromRelayJumpsToAnotherServerInternalPacket("$coreEndpoint/$requestedId"))
        return true
    }

    private fun normalizeRelayInputId(inId: String): String {
        RelayDeploymentSupport.resolveDisplayRoomIdWithLocalFallback(inId)?.let { return it }

        var id = inId.trim()
            .trim('"', '\'')
            .removePrefix("https://")
            .removePrefix("http://")

        if (id.contains('/')) {
            id = id.substringAfterLast('/')
        }

        id = id.replace("\\s".toRegex(), "")

        if (id.startsWith("K", ignoreCase = true) && RelayDeploymentSupport.parseClusterRoomNodeCode(id) == null) {
            id = id.substring(1)
        }

        val mainId = Data.configRelay.mainID.trim()
        if (mainId.isBlank() || !id.startsWith(mainId, ignoreCase = true) || id.length <= mainId.length) {
            return id
        }

        val candidate = id.substring(mainId.length)
        if (candidate.isNotBlank() && candidate.all { it.isDigit() }) {
            return candidate
        }

        val nodeCode = RelayNodeRoutingSupport.requestedNodeCode(candidate)
        if (nodeCode != null && RelayNodeRoutingSupport.findNode(nodeCode) != null) {
            return candidate
        }

        return id
    }

    private fun checkLength(str: String): Boolean {
        if (str.length > 7 || str.length < 2) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
            return false
        }
        if (str.contains(".")) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.server.error", "不能包含 [ . ]"))
            return false
        }
        return true
    }

    private fun adminMoveNew() {
        // 更新最小玩家
        room!!.updateMinSize()
        room!!.getAbstractNetConnect(room!!.minSize)?.let {
            it.sendRelayServerId()
            room!!.abstractNetConnectIntMap.values.forEach { obj: GameVersionRelay -> obj.addReRelayConnect() }
            this.addReRelayConnect()
            sendPackageToHOST(rwHps.abstractNetPacket.getExitPacket())
        }
    }

    private fun newRelayId(
        id: String? = null,
        mod: Boolean,
        customRelayData: CustomRelayData = CustomRelayData(),
        publicRoom: Boolean = false,
        profile: RelayCustomRoomProfile? = null
    ) {
        val resolvedCustomData = resolveCustomRelayData(customRelayData, profile)
        val maxPlayer = resolvedCustomData.maxPlayerSize

        room = if (IsUtils.isBlank(id)) {
            RelayRoom.getRelay(
                    playerName = name, isMod = mod, betaGameVersion = betaGameVersion, version = clientVersion, maxPlayer = maxPlayer
            )
        } else {
            RelayRoom.getRelay(id!!, name, mod, betaGameVersion, clientVersion, maxPlayer)
        }

        room!!.isMod = mod
        if (profile != null) {
            RelayCustomRoomSupport.applyProfile(room!!, profile)
        }
        room!!.setCustomRoomSettings(
            maxPlayerSize = resolvedCustomData.maxPlayerSize,
            maxUnitSize = resolvedCustomData.maxUnitSizt,
            income = resolvedCustomData.income,
            manualOverride = false
        )
        if (publicRoom) {
            room!!.setPublicRoom(true, top = true)
            RelayPublicRoomSupport.scheduleSuccessNotice(room!!)
        }

        if (shouldApplyCustomRelaySettings(profile, resolvedCustomData)) {
            customModePlayerSize(resolvedCustomData)
        }

        room!!.setAddSize()
        sendRelayServerId()
    }

    private fun createConfiguredRelayRoom(profile: RelayCustomRoomProfile) {
        newRelayId(
            id = profile.roomId,
            mod = profile.isMod,
            customRelayData = CustomRelayData(
                maxPlayerSize = profile.maxPlayerSize,
                maxUnitSizt = profile.maxUnitSize,
                income = profile.income
            ),
            publicRoom = profile.autoPublicRoom || profile.forcePublicRoom,
            profile = profile
        )
    }

    internal fun enableRelayRoomModMode() {
        val currentRoom = room ?: return
        currentRoom.isMod = true
        currentRoom.setCurrentMapName(currentRoom.currentMapName)

        val resolvedData = resolveCustomRelayData(
            CustomRelayData(
                maxPlayerSize = currentRoom.currentMaxPlayerSize,
                maxUnitSizt = currentRoom.currentMaxUnitSize,
                income = currentRoom.currentIncome
            ),
            currentRoom.customRoomProfile,
            currentRoom
        )
        currentRoom.setCustomRoomSettings(
            maxPlayerSize = resolvedData.maxPlayerSize,
            maxUnitSize = resolvedData.maxUnitSizt,
            income = resolvedData.income,
            manualOverride = currentRoom.manualMaxPlayerOverride
        )
        sendRelayBecomeServerPacket()
        customModePlayerSize(resolvedData)
        if (currentRoom.publicRoom || currentRoom.customRoomProfile?.relayServerManaged == true) {
            currentRoom.setPublicRoom(true, top = true)
            RelayPublicRoomSupport.scheduleSuccessNotice(currentRoom)
        }
        UpListMain.requestRefresh(force = true)
        sendPacket(
            rwHps.abstractNetPacket.getChatMessagePacket(
                RelayDeploymentSupport.buildRoomModModeEnabledNotice(currentRoom.id),
                RelayDeploymentSupport.chatSender(currentRoom.id),
                5
            )
        )
    }

    internal fun updateRelayRoomMaxPlayerLimit(targetSize: Int, manualOverride: Boolean) {
        val currentRoom = room ?: return
        val resolvedData = resolveCustomRelayData(
            CustomRelayData(
                maxPlayerSize = targetSize,
                maxUnitSizt = currentRoom.currentMaxUnitSize,
                income = currentRoom.currentIncome
            ),
            currentRoom.customRoomProfile,
            currentRoom
        )
        currentRoom.setCustomRoomSettings(
            maxPlayerSize = resolvedData.maxPlayerSize,
            maxUnitSize = resolvedData.maxUnitSizt,
            income = resolvedData.income,
            manualOverride = manualOverride
        )
        customModePlayerSize(resolvedData)
        broadcastRoomNotice(
            RelayPlayerNoticeSupport.buildRoomPlayerLimitUpdatedNotice(
                currentRoom.id,
                autoDetected = !manualOverride
            )
        )
    }

    private fun tryAutoAdjustRelayRoomMaxPlayer(mapName: String) {
        val currentRoom = room ?: return
        val profile = currentRoom.customRoomProfile ?: return
        if (!profile.relayServerManaged || currentRoom.manualMaxPlayerOverride) {
            return
        }

        val modeConfig = RelayCustomRoomSupport.relayServerModeConfig()
        if (!modeConfig.enableAutoAdjustMaxPlayer) {
            return
        }

        val detectedPlayerLimit = detectRelayMapPlayerLimit(mapName) ?: return
        val minPlayerSize = modeConfig.autoAdjustMaxPlayerMin.coerceAtLeast(2)
        val maxPlayerSize = modeConfig.autoAdjustMaxPlayerMax.coerceAtLeast(minPlayerSize)
        val targetSize = detectedPlayerLimit.coerceIn(minPlayerSize, maxPlayerSize)
        if (targetSize == currentRoom.currentMaxPlayerSize) {
            return
        }

        updateRelayRoomMaxPlayerLimit(targetSize, manualOverride = false)
    }

    private fun resolveCustomRelayData(
        customRelayData: CustomRelayData,
        profile: RelayCustomRoomProfile? = null,
        relayRoom: RelayRoom? = room
    ): CustomRelayData {
        val defaultMaxPlayerSize = relayRoom?.currentMaxPlayerSize?.takeIf { it > 0 }
            ?: profile?.maxPlayerSize?.takeIf { it > 0 }
            ?: 50
        val defaultMaxUnitSize = relayRoom?.currentMaxUnitSize?.takeIf { it > 0 }
            ?: profile?.maxUnitSize?.takeIf { it > 0 }
            ?: 200
        val defaultIncome = relayRoom?.currentIncome?.takeIf { it > 0f }
            ?: profile?.income?.takeIf { it > 0f }
            ?: 1f

        return CustomRelayData(
            maxPlayerSize = (customRelayData.maxPlayerSize.takeIf { it > 0 } ?: defaultMaxPlayerSize).coerceIn(2, 100),
            maxUnitSizt = (customRelayData.maxUnitSizt.takeIf { it > 0 } ?: defaultMaxUnitSize).coerceAtLeast(1),
            income = customRelayData.income.takeIf { it > 0f } ?: defaultIncome
        )
    }

    private fun shouldApplyCustomRelaySettings(profile: RelayCustomRoomProfile?, customRelayData: CustomRelayData): Boolean {
        return profile?.relayServerManaged == true ||
            customRelayData.maxPlayerSize != 50 ||
            customRelayData.maxUnitSizt != 200 ||
            customRelayData.income != 1f
    }

    private fun customModePlayerSize(customRelayData: CustomRelayData) {
        val registerServer = GameOutputStream()
        registerServer.writeString(Data.SERVER_ID)
        registerServer.writeInt(1)
        registerServer.writeInt(clientVersion)
        registerServer.writeInt(clientVersion)
        registerServer.writeString("com.corrodinggames.rts.server")
        registerServer.writeString(room!!.serverUuid)
        registerServer.writeInt("Dr @ 2022".hashCode())
        sendPacket(registerServer.createPacket(PacketType.PREREGISTER_INFO))

        val o2 = GameOutputStream()
        o2.writeString(Data.SERVER_ID_RELAY)
        o2.writeInt(clientVersion)
        o2.writeInt(GameMaps.MapType.CustomMap.ordinal)
        o2.writeString("RW-HPS RELAY Custom Mode")
        // credits
        o2.writeInt(0)
        // mist
        o2.writeInt(2)
        o2.writeBoolean(true)
        o2.writeInt(1)
        o2.writeByte(0)
        o2.writeBoolean(false)
        o2.writeBoolean(false)
        sendPacket(o2.createPacket(PacketType.SERVER_INFO))

        val o = GameOutputStream()
        o.writeInt(0)
        o.writeBoolean(false)/* RELAY Custom MaxPlayer */
        o.writeInt(customRelayData.maxPlayerSize)
        o.flushEncodeData(CompressOutputStream.getGzipOutputStream("teams", true)
            .also { for (i in 0 until customRelayData.maxPlayerSize) it.writeBoolean(false) })
        // mist
        o.writeInt(2)
        // credits
        o.writeInt(0)
        o.writeBoolean(true)
        o.writeInt(1)
        o.writeByte(5)
        // RELAY Custom MaxUnit
        o.writeInt(customRelayData.maxUnitSizt)
        o.writeInt(customRelayData.maxUnitSizt)
        // initUnit
        o.writeInt(1)
        // RELAY Custom income
        o.writeFloat(customRelayData.income)
        // Ban nuke
        o.writeBoolean(true)
        o.writeBoolean(false)
        o.writeBoolean(false)
        // sharedControl
        o.writeBoolean(false)
        // gamePaused
        o.writeBoolean(false)
        sendPacket(o.createPacket(PacketType.TEAM_LIST))
    }

    private data class CustomRelayData(
        var maxPlayerSize: Int = -1,
        var maxUnitSizt: Int = 200,
        var income: Float = 1f,
    )

    companion object {
        private val urlMath = "\\b\\w+(?:\\.\\w+)+\\b".toPattern()
        private val relayMapPlayerPattern = "(?i)(?:^|[^a-z0-9])(?:p\\s*([1-9][0-9]?)|([1-9][0-9]?)\\s*p)(?=$|[^a-z0-9])".toPattern()

        private fun detectRelayMapPlayerLimit(mapName: String): Int? {
            val matcher = relayMapPlayerPattern.matcher(mapName)
            while (matcher.find()) {
                val numericText = matcher.group(1) ?: matcher.group(2) ?: continue
                val playerLimit = numericText.toIntOrNull() ?: continue
                if (playerLimit in 2..100) {
                    return playerLimit
                }
            }
            return null
        }

        private fun findMapURL(mapName: String): Boolean {
            val find = urlMath.matcher(mapName)
            if (find.find()) {
                try {
                    var url = find.group()
                    // 过滤1.1.1这种, 防误杀
                    if (IsUtils.isNumeric(url.replace(".", ""))) {
                        return false
                    }
                    if (url.startsWith(".")) {
                        url = url.substring(1)
                    }
                    url = url.removeSuffix(".")
                    // 直接查解析
                    InetAddress.getAllByName(url)
                    return true
                } catch (_: Exception) {
                    return false
                }
            } else {
                return false
            }
        }

        // 是否显示聊天信息到后台
        @JvmStatic
        var showChatInConsole = false
    }
}
