/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

//关闭傻逼格式化
//@formatter:off

@file:JvmName("ServerPacket")
@file:JvmMultifileClass

package net.rwhps.server.net.netconnectprotocol.internal.server

import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.io.packet.GameCommandPacket
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.io.packet.type.PacketType
import java.io.IOException

/**
 * Game Tick
 * @author Dr (dr@der.kim)
 */

/**
 * Update Tick packs to players
 * @param tick Int      : TICK
 * @return Packet       : Generate a send package
 * @throws IOException  : Unknown
 */
@Throws(IOException::class)
internal fun gameTickPacketInternal(tick: Int): Packet {
    val o = GameOutputStream()
    // Game TICK
    o.writeInt(tick)
    // Readable key pack length
    // Because there is no button package, this package will only let the client update the Tick
    o.writeInt(0)
    return o.createPacket(PacketType.TICK)
}

/**
 * Sends a single key pack (unit action pack) under the specified Tick to the player
 * @param tick Int              : TICK
 * @param cmd GameCommandPacket : GameCommand Packet
 * @return Packet               : Generate a send package
 * @throws IOException          : Unknown
 */
@Throws(IOException::class)
internal fun gameTickCommandPacketInternal(tick: Int, cmd: GameCommandPacket): Packet {
    val o = GameOutputStream()
    // Game TICK
    o.writeInt(tick)
    // Readable key pack length
    o.writeInt(1)

    if (cmd.gzip) {
        val enc = CompressOutputStream.getGzipOutputStream("c", false)
        enc.writeBytes(cmd.bytes)
        o.flushEncodeData(enc)
    } else {
        o.writeBytes(cmd.bytes)
    }
    return o.createPacket(PacketType.TICK)
}

/**
 * Generate multiple key packs (unit action packs) under the specified Tick to the player
 * @param tick Int                      : TICK
 * @param cmd Seq<GameCommandPacket>    : GameCommand Packets
 * @return Packet                       : Generate a send package
 * @throws IOException                  : Unknown
 */
@Throws(IOException::class)
internal fun gameTickCommandsPacketInternal(tick: Int, cmd: Seq<GameCommandPacket>): Packet {
    val o = GameOutputStream()
    // Game TICK
    o.writeInt(tick)
    // Readable key pack length
    o.writeInt(cmd.size)
    for (c in cmd) {
        if (c.gzip) {
            val enc = CompressOutputStream.getGzipOutputStream("c", false)
            enc.writeBytes(c.bytes)
            o.flushEncodeData(enc)
        } else {
            o.writeBytes(c.bytes)
        }
    }
    return o.createPacket(PacketType.TICK)
}