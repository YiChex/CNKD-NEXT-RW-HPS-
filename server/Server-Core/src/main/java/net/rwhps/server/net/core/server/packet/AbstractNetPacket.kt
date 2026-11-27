/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core.server.packet

import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.io.packet.GameCommandPacket
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.ServerInfoPacket
import net.rwhps.server.struct.list.Seq
import java.io.IOException

/**
 * 获取包 转换包 理论上全版本通用 但是部分版本需要特殊覆盖实现
 * @author Dr (dr@der.kim)
 */
interface AbstractNetPacket {
    /**
     * 获取系统命名的消息包
     * SERVER: ...
     * @param msg The message
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getSystemMessagePacket(msg: String): Packet

    /**
     * 发送用户名命名的消息
     * @param      msg     The message
     * @param      sendBy  The sendBy
     * @param      team    The team
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getChatMessagePacket(msg: String, sendBy: String, team: Int): Packet

    /**
     * Ping
     * @param player Player
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getPingPacket(player: PlayerHess): Packet

    /**
     * 获取时刻包
     * @param tick Tick
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getTickPacket(tick: Int): Packet

    /**
     * 获取时刻包
     * @param tick Tick
     * @param cmd 位移
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getGameTickCommandPacket(tick: Int, cmd: GameCommandPacket): Packet

    /**
     * 获取时刻包
     * @param tick Tick
     * @param cmd 多位移
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getGameTickCommandsPacket(tick: Int, cmd: Seq<GameCommandPacket>): Packet

    /**
     * 获取包中的服务器数据
     * @param bytes Packet.bytes
     * @return 地图名
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getPacketServerInfo(bytes: ByteArray): ServerInfoPacket

    /**
     * 欺骗客户端获取同步包
     * @return Packet
     */
    fun getDeceiveGameSave(): Packet

    /** Build a START_GAME packet that carries a custom map's TMX bytes. */
    @Throws(IOException::class)
    fun getCustomStartGamePacket(maps: GameMaps): Packet

    /**
     * 获取单位生成包
     * @param index Byte
     * @param unit String
     * @param x Float
     * @param y Float
     */
    fun gameSummonPacket(index: Int, unit: String, x: Float, y: Float, size: Int = 0): GameCommandPacket

    /**
     * 退出
     * @return Packet
     * @throws IOException err
     */
    @Throws(IOException::class)
    fun getExitPacket(): Packet
}
