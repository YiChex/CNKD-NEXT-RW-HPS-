/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.netconnectprotocol

import net.rwhps.server.game.player.PlayerRelay
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.log.exp.ParseException
import java.io.IOException

/**
 * General parsing package
 * @author Dr (dr@der.kim)
 */
object UniversalAnalysisOfGamePackages {
    @Throws(IOException::class)
    fun getPacketUnitName(bytes: ByteArray): Seq<String> {
        val result = Seq<String>()
        GameInputStream(bytes).use { stream ->
            stream.readString()
            stream.skip(8)
            stream.readString()
            stream.skip(34)
            if (stream.readBoolean()) {
                stream.getDecodeStream(false).use { unit ->
                    unit.skip(4)
                    for (i in 0 until unit.readInt()) {
                        unit.readString()
                        unit.readInt()
                        unit.readBoolean()
                        val cacheA = unit.readIsString()
                        val cache = if (IsUtils.isBlank(cacheA)) "Default" else cacheA
                        if (!result.contains(cache)) {
                            result.add(cache)
                        }
                        unit.skip(16)
                    }
                }
            }
        }
        return result
    }

    @Throws(IOException::class)
    fun getPacketTeamData(parse: GameInputStream, playerRelay: PlayerRelay) {
        if (parse.parseVersion == 0) {
            throw ParseException("need To Parse The Version")
        }

        parse.use { inStream ->
            playerRelay.site = inStream.readInt()
            if (inStream.readBoolean()) return
            val playerSize = inStream.readInt()
            inStream.getDecodeStream(true).use {
                for (count in 0 until playerSize) {
                    if (it.readBoolean()) {
                        it.readInt()
                        val site = it.readByte()
                        if (site == playerRelay.site) {
                            it.readInt()
                            playerRelay.team = it.readInt()
                            playerRelay.name = it.readIsString()
                            it.readBoolean()
                        } else {
                            it.skip(8)// Int+Int
                            it.readIsString()
                            it.skip(1)
                        }
                        it.skip(12) // Int+Long
                        if (parse.parseVersion >= 55) it.skip(5)  // Boolean+Int
                        if (parse.parseVersion >= 91) it.skip(5)  // Int+Byte
                        if (parse.parseVersion >= 97) it.skip(2)  // Boolean *2
                        if (parse.parseVersion >= 125) it.skip(6) // Boolean+Boolean+Int
                        if (parse.parseVersion >= 149) {
                            it.readIsString(); it.skip(4)
                        }// Int
                        if (parse.parseVersion >= 156) {
                            it.readIsInt(); it.readIsInt(); it.readIsInt(); it.readIsInt(); it.skip(4)
                        }// Int
                    }
                }
            }
        }
    }
}