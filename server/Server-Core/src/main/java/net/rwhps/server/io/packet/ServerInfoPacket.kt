/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.packet

import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream

/**
 *
 *
 * @date 2024/2/6 10:03
 * @author Dr (dr@der.kim)
 */
class ServerInfoPacket(bytes: ByteArray) {
    val mapName: String
    val credits: Int
    val fog: Int
    val revealedMap: Boolean
    val aiDifficuly: Int
    val optionsVersion: Int

    val initUnit: Int
    val income: Float
    val nukes: Boolean
    val version2OptionsComplete: Boolean
    val structurallyComplete: Boolean
    init {
        var mapNameValue = ""
        var creditsValue = 0
        var fogValue = 0
        var revealedMapValue = false
        var aiDifficulyValue = 0
        var optionsVersionValue = 0
        var initUnitValue = 0
        var incomeValue = 1f
        var nukesValue = false
        var version2OptionsCompleteValue = false
        var structurallyCompleteValue = false
        GameInputStream(bytes).use { stream ->
            stream.readString()
            stream.readInt()
            stream.readInt()
            var knownOptionsComplete = runCatching {
                mapNameValue = stream.readString()
                creditsValue = stream.readInt()
                fogValue = stream.readInt()
                revealedMapValue = stream.readBoolean()
                aiDifficulyValue = stream.readInt()
                optionsVersionValue = stream.readByte().toInt()
                stream.readBoolean()
                stream.readBoolean()
            }.isSuccess
            if (knownOptionsComplete && optionsVersionValue >= 1) {
                knownOptionsComplete = runCatching {
                    stream.readInt()
                    stream.readInt()
                }.isSuccess
            }
            if (knownOptionsComplete && optionsVersionValue >= 2) {
                val version2Options = runCatching {
                    Triple(stream.readInt(), stream.readFloat(), stream.readBoolean())
                }.getOrNull()
                if (version2Options != null) {
                    initUnitValue = version2Options.first
                    incomeValue = version2Options.second
                    nukesValue = version2Options.third
                    version2OptionsCompleteValue = true
                } else {
                    knownOptionsComplete = false
                }
            }
            structurallyCompleteValue = knownOptionsComplete
        }
        mapName = mapNameValue
        credits = creditsValue
        fog = fogValue
        revealedMap = revealedMapValue
        aiDifficuly = aiDifficulyValue
        optionsVersion = optionsVersionValue
        initUnit = initUnitValue
        income = incomeValue
        nukes = nukesValue
        version2OptionsComplete = version2OptionsCompleteValue
        structurallyComplete = structurallyCompleteValue
    }

    companion object {
        @JvmStatic
        fun rewriteMapName(bytes: ByteArray, mapName: String): ByteArray {
            GameInputStream(bytes).use { stream ->
                val out = GameOutputStream()
                out.writeString(stream.readString())
                out.writeInt(stream.readInt())
                out.writeInt(stream.readInt())
                // Consume the field being replaced so subsequent protocol offsets stay unchanged.
                stream.readString()
                out.writeString(mapName)
                out.writeInt(stream.readInt())
                out.writeInt(stream.readInt())
                out.writeBoolean(stream.readBoolean())
                out.writeInt(stream.readInt())

                val readByte = stream.readByte()
                out.writeByte(readByte)
                out.writeBoolean(stream.readBoolean())
                out.writeBoolean(stream.readBoolean())

                if (readByte >= 1) {
                    out.writeInt(stream.readInt())
                    out.writeInt(stream.readInt())
                }

                if (readByte >= 2) {
                    out.writeInt(stream.readInt())
                    out.writeFloat(stream.readFloat())
                    out.writeBoolean(stream.readBoolean())
                }

                out.transferTo(stream)
                return out.getByteArray()
            }
        }
    }
}
