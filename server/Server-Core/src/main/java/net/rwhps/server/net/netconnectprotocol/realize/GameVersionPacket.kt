/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.netconnectprotocol.realize

import net.rwhps.server.data.global.Cache
import net.rwhps.server.game.GameMaps
import net.rwhps.server.game.enums.GameCommandActions
import net.rwhps.server.game.enums.GameInternalUnits
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.packet.GameCommandPacket
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.ServerInfoPacket
import net.rwhps.server.net.core.server.packet.AbstractNetPacket
import net.rwhps.server.net.netconnectprotocol.internal.server.*
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.IsUtils
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.util.annotations.MainProtocolImplementation
import java.io.IOException

/**
 * Provides support for most common packages for the server
 *
 * @author Dr (dr@der.kim)
 */
@MainProtocolImplementation
open class GameVersionPacket: AbstractNetPacket {
    @Throws(IOException::class)
    override fun getSystemMessagePacket(msg: String): Packet = chatMessagePacketInternal(msg, "CNKD", 5)

    @Throws(IOException::class)
    override fun getChatMessagePacket(msg: String, sendBy: String, team: Int): Packet = chatMessagePacketInternal(msg, sendBy, team)

    @Throws(IOException::class)
    override fun getPingPacket(player: PlayerHess): Packet {
        val now = System.currentTimeMillis()
        player.timeTemp = now
        val o = GameOutputStream()
        o.writeLong(now)
        o.writeByte(0)
        return o.createPacket(PacketType.HEART_BEAT)
    }

    @Throws(IOException::class)
    override fun getTickPacket(tick: Int): Packet = gameTickPacketInternal(tick)

    @Throws(IOException::class)
    override fun getGameTickCommandPacket(tick: Int, cmd: GameCommandPacket): Packet = gameTickCommandPacketInternal(tick, cmd)

    @Throws(IOException::class)
    override fun getGameTickCommandsPacket(tick: Int, cmd: Seq<GameCommandPacket>): Packet = gameTickCommandsPacketInternal(tick, cmd)

    @Throws(IOException::class)
    override fun getPacketServerInfo(bytes: ByteArray): ServerInfoPacket {
        return ServerInfoPacket(bytes)
    }

    override fun getDeceiveGameSave(): Packet {
        val cPacket: Packet? = Cache.packetCache["getDeceiveGameSave"]
        if (IsUtils.notIsBlank(cPacket)) {
            return cPacket!!
        }

        val o = GameOutputStream()
        o.writeByte(0)
        o.writeInt(0)
        o.writeInt(0)
        o.writeFloat(0)
        o.writeFloat(0)
        o.writeBoolean(true)
        o.writeBoolean(false)
        val gzipEncoder = CompressOutputStream.getGzipOutputStream("gameSave", false)
        gzipEncoder.writeString("This is RW-HPS [Deceive Get GameSave]!")
        o.flushEncodeData(gzipEncoder)

        val cachePacket = o.createPacket(PacketType.SYNC)
        Cache.packetCache.put("getDeceiveGameSave", cachePacket)

        return cachePacket
    }

    @Throws(IOException::class)
    override fun getCustomStartGamePacket(maps: GameMaps): Packet {
        val mapData = maps.mapData
            ?: throw IllegalStateException("自定义地图数据为空: ${maps.mapName}")
        val mapBytes = mapData.readMapBytes(cleanOnRelease = false)
        require(mapBytes.isNotEmpty()) { "自定义地图数据为空: ${mapData.runtimeFileName}" }

        val out = GameOutputStream()
        out.writeByte(0)
        // 0 -> built-in, 1 -> custom, 2 -> saved game
        out.writeInt(maps.mapType.ordinal)
        out.flushMapData(mapBytes.size, mapBytes)
        out.writeString("SAVE:${maps.mapName}.tmx")
        out.writeBoolean(false)
        return out.createPacket(PacketType.START_GAME)
    }

    override fun gameSummonPacket(index: Int, unit: String, x: Float, y: Float, size: Int): GameCommandPacket {
        val outStream = GameOutputStream()
        outStream.writeByte(index)
        outStream.writeBoolean(true)
        // 建造
        outStream.writeInt(GameCommandActions.BUILD.ordinal)


        var unitID = -2
        if (IsUtils.notIsNumeric(unit)) {
            GameInternalUnits.values().forEach {
                if (it.name.equals(unit, ignoreCase = true)) {
                    unitID = it.ordinal
                    return@forEach
                }
            }
        } else {
            unitID = unit.toInt()
        }

        outStream.writeInt(unitID)

        if (unitID == -2) {
            outStream.writeString(unit)
        }


        // X
        outStream.writeFloat(x)
        // Y
        outStream.writeFloat(y)
        // Tager
        outStream.writeLong(-1L)
        // 如果生成圆 那么这就是半径
        outStream.writeByte(size)
        outStream.writeFloat(1)
        outStream.writeFloat(1)
        outStream.writeBoolean(false)
        outStream.writeBoolean(false)
        outStream.writeBoolean(false)
        outStream.writeBoolean(false)
        outStream.writeBoolean(false)
        outStream.writeBoolean(false)

        //
        outStream.writeInt(-1)
        outStream.writeInt(-1)

        outStream.writeBoolean(false)
        outStream.writeBoolean(false)

        outStream.writeInt(0)

        outStream.writeBoolean(false)
        outStream.writeBoolean(false)


        outStream.writeLong(-1)
        outStream.writeString("-1")
        outStream.writeBoolean(false)
        outStream.writeShort(0)
        // System action
        outStream.writeBoolean(true)
        outStream.writeByte(0)
        outStream.writeFloat(0f)
        outStream.writeFloat(0f)
        //action type
        outStream.writeInt(5)
        outStream.writeInt(0)
        outStream.writeBoolean(false)
        return GameCommandPacket(index, outStream.getByteArray())
    }

    @Throws(IOException::class)
    override fun getExitPacket(): Packet {
        return Cache.packetCache["getExitPacket", {
            playerExitInternalPacket()
        }]
    }
}
