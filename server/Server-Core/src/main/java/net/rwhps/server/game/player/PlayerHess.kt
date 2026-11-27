/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.player

import net.rwhps.server.func.Prov
import net.rwhps.server.game.headless.core.link.AbstractLinkPlayerData
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.server.AbstractNetConnectServer
import net.rwhps.server.net.netconnectprotocol.internal.relay.fromRelayJumpsToAnotherServerInternalPacket
import net.rwhps.server.struct.map.ObjectMap
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.Time
import net.rwhps.server.util.concurrent.lock.Synchronize
import net.rwhps.server.util.file.load.I18NBundle
import net.rwhps.server.util.file.plugin.value.Value
import net.rwhps.server.util.game.SystemMessageTextMode
import net.rwhps.server.util.game.SystemMessageTextSupport
import net.rwhps.server.util.inline.coverConnect
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.exp.ImplementedException
import net.rwhps.server.util.log.exp.NetException
import org.jetbrains.annotations.Nls
import java.util.*

/**
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
open class PlayerHess(
    conIn: AbstractNetConnectServer?,
    /**   */
    val i18NBundle: I18NBundle,
        //
    var playerPrivateData: AbstractLinkPlayerData = HeadlessModuleManage.hps.gameLinkServerData.getDefPlayerData()
) {
    var con: AbstractNetConnectServer? by Synchronize(conIn)

    /** is Admin  */
    @Volatile
    var isAdmin = false

    // 自动分配的 ADMIN 标记
    var autoAdmin: Boolean = false
    var superAdmin: Boolean = false

    var never = false

    /** Headless player index  */
    var index: Int
        get() = playerPrivateData.index
        set(value) {
            playerPrivateData.index = value
        }
    /** Server player position  */
    var position
        get() = (index + 1)
        set(value) { index = (value - 1) }

    /** Team number  */
    var team: Int
        get() = playerPrivateData.team
        set(value) {
            playerPrivateData.team = value
        }

    @Volatile
    var lastMoveTime: Int = 0

    /** Mute expiration time */
    var muteTime: Long = 0

    /** Kick expiration time */
    var kickTime: Long = 0
    var timeTemp: Long = 0
    var lastMessageTime: Long = 0
    var lastSentMessage: String? = ""
    var noSay = false

    /** */
    var credits: Int
        get() = playerPrivateData.credits
        set(value) {
            playerPrivateData.credits = value
        }
    var startUnit: Int
        get() = playerPrivateData.startUnit
        set(value) {
            playerPrivateData.startUnit = value
        }
    var color: Int
        get() = playerPrivateData.color
        set(value) {
            playerPrivateData.color = value
        }

    /** Is the player alive  */
    val survive get() = playerPrivateData.survive

    /** 单位击杀数 */
    val unitsKilled get() = playerPrivateData.unitsKilled

    /** 建筑毁灭数 */
    val buildingsKilled get() = playerPrivateData.buildingsKilled

    /** 单实验单位击杀数 */
    val experimentalsKilled get() = playerPrivateData.experimentalsKilled

    /** 单位被击杀数 */
    val unitsLost get() = playerPrivateData.unitsLost

    /** 建筑被毁灭数 */
    val buildingsLost get() = playerPrivateData.buildingsLost

    /** 单实验单位被击杀数 */
    val experimentalsLost get() = playerPrivateData.experimentalsLost


    val name get() = playerPrivateData.name
    val connectHexID get() = playerPrivateData.connectHexID

    open val isAi = false
    var aiDifficulty: Int
        get() = playerPrivateData.aiDifficulty
        set(value) {
            playerPrivateData.aiDifficulty = value
        }

    val statusData
        get() = ObjectMap<String, Int>().apply {
            put("unitsKilled", unitsKilled)
            put("buildingsKilled", buildingsKilled)
            put("experimentalsKilled", experimentalsKilled)
            put("unitsLost", unitsLost)
            put("buildingsLost", buildingsLost)
            put("experimentalsLost", experimentalsLost)
        }

    val playerInfo: String get() {
        val connection = con?.coverConnect()
        return "$name / Position: $position / IP: ${connection?.ip ?: "unknown"} / Use: ${connection?.useConnectionAgreement ?: "unknown"} / Admin: $isAdmin"
    }

    val infoObject
        get() = ObjectMap<String, Any>().apply {
            val connection = con?.coverConnect()
            put("Name", name)
            put("Position", position)
            put("ConnectHexID", connectHexID)
            put("IP", connection?.ip ?: "unknown")
            put("IPCount", connection?.ipCountry ?: "unknown")
            put("TCP/UDP", connection?.useConnectionAgreement ?: "unknown")
            put("Admin", autoAdmin)
        }

    fun updateDate() {
        playerPrivateData.updateDate()
    }

    /** */
    //abstract var startUnit: Int

    private val customData = ObjectMap<String, Value<*>>()

    /**
     * Sends a system message to the player if connected.
     * Event handlers are dispatched asynchronously via coroutines, so `con`
     * may be null by the time the handler runs. Silently discards rather
     * than throwing [ImplementedException].
     */
    open fun sendSystemMessage(
        @Nls
        text: String,
        mode: SystemMessageTextMode = SystemMessageTextMode.DEFAULT
    ) {
        val normalizedText = SystemMessageTextSupport.normalize(text, mode)
        if (normalizedText.isBlank()) {
            return
        }
        if (con?.sendSystemMessage(normalizedText, mode) == null && !noSay) {
            /*
             * FIX-LOG-NOISE: WARN → debug。
             *
             * 「对方已断线」是**正常状态**，不是故障。而 `CallManage.sendSystemMessage`
             * 会遍历 playerGroup，`Event.kt:176/178` 在玩家离开时广播 ⇒
             * 一条 WARN × 房间人数。LogCore.kt:127 已把这个模式点名为
             * "one line per disconnected player when broadcasting"。
             */
            Log.debug("[Player] sendSystemMessage dropped for ${name}: no connection")
        }
    }

    /**
     * Sends a chat message from [player] to this player if connected.
     * See [sendSystemMessage] for the async-race rationale.
     */
    fun sendMessage(
        player: PlayerHess,
        @Nls
        text: String
    ) {
        // 排位剥离：原 chatSenderColorOverride(name, fallbackTeam) 在非托管对局（本构建的全部情况）下
        // 返回 fallbackTeam，即 player.team，故此处直接使用。
        val displayColor = player.team
        if (con?.sendChatMessage(text, player.name, displayColor) == null) {
            // FIX-LOG-NOISE: 同 sendSystemMessage —— 断线是正常状态，降为 debug。
            Log.debug("[Player] sendMessage dropped for ${name}: no connection")
        }
    }

    /**
     * 调用生成一个 RELAY 输入 (一次性, 回调只会调用一次, 多次请自行解决)
     *
     * @param msg 显示的信息
     * @param run 用户输入回调
     * @throws ImplementedException
     */
    @Throws(ImplementedException.PlayerImplementedException::class)
    fun sendPopUps(
        @Nls
        msg: String, run: ((String) -> Unit)
    ) {
        con?.sendRelayServerType(msg, run) ?: throw ImplementedException.PlayerImplementedException("[Player] No Bound Connection")
    }

    @JvmOverloads
    open fun kickPlayer(
        @Nls
        text: String, time: Int = 0
    ) {
        kickTime = Time.getTimeFutureMillis(time * 1000L)
        con?.sendKick(text)
    }


    fun getinput(input: String, vararg params: Any?): String {
        return i18NBundle.getinput(input, params)
    }


    fun <T> addData(dataName: String, value: T) {
        customData[dataName] = Value(value)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getData(dataName: String): T? {
        return customData[dataName]?.value as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getData(dataName: String, defValue: T): T {
        return (customData[dataName]?.value ?: defValue) as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getData(dataName: String, defProv: Prov<T>): T {
        return (customData[dataName]?.value ?: defProv.get()) as T
    }

    fun removeData(dataName: String) {
        customData.remove(dataName)
    }

    /**
     * For [IRwHps.NetType.ServerTestProtocol] :
     *  At this time, the local server does not participate in the forwarding, and the client directly disconnects the server and joins the new server.
     *  The player will not exist in `playerManage.playerGroup` and `playerManage.playerAll`
     *  Player ⇄ NewServer
     *
     * @param ip
     * @param port
     */
    @JvmOverloads
    @Throws(NetException::class)
    fun playerJumpsToAnotherServer(ip: String, port: Int = 5123) {
        if (!IsUtils.isDomainName(ip)) {
            throw NetException("[ERROR_DOMAIN] Error Domain")
        }
        val c = con ?: throw NetException("[CONNECT_CLOSE] Connect disconnect")
        c.coverConnect().sendPacket(fromRelayJumpsToAnotherServerInternalPacket("$ip:$port"))
    }

    open fun clear() {
        con = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return if (other == null || javaClass != other.javaClass) {
            false
        } else if (other is PlayerHess) {
            connectHexID == other.connectHexID
        } else {
            connectHexID == other.toString()
        }
    }

    override fun hashCode(): Int {
        return Objects.hash(connectHexID)
    }
}
