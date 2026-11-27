/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.io.packet.type.PacketType
import net.rwhps.server.util.log.Log
import java.util.concurrent.atomic.AtomicLong

internal object StartModeGameCommandCompatibilitySupport {
    private val sharedControlRewriteAppliedCount = AtomicLong(0L)
    private val sharedControlRewriteSkippedCount = AtomicLong(0L)
    private val sharedControlRewriteFailedCount = AtomicLong(0L)

    @Volatile
    private var sharedControlRewriteLastReason = "none"
    @Volatile
    private var sharedControlRewriteLastSenderIndex = -1
    @Volatile
    private var sharedControlRewriteLastOriginalCheck = 0
    @Volatile
    private var sharedControlRewriteLastTargetCheck = 0

    fun rewriteSharedControlCheck(packet: Packet): Packet {
        if (packet.type != PacketType.GAMECOMMAND_RECEIVE) {
            return packet
        }
        if (!HeadlessModuleManage.initHPS()) {
            recordSkipped("headless_not_ready", -1, 0, 0)
            return packet
        }

        return try {
            val packetInput = GameInputStream(packet)
            val head = packetInput.readString()
            val commandBytes = packetInput.readStreamBytes()
            val rewrite = rewriteCommandBytes(commandBytes) ?: return packet

            val output = GameOutputStream()
            output.writeString(head)
            output.writeInt(rewrite.bytes.size)
            output.writeBytes(rewrite.bytes)

            Packet(packet.type, output.getByteArray()).also { rewrittenPacket ->
                rewrittenPacket.status = packet.status
                recordApplied(rewrite)
            }
        } catch (error: Exception) {
            sharedControlRewriteFailedCount.incrementAndGet()
            sharedControlRewriteLastReason = error.javaClass.simpleName.ifBlank { "rewrite_failed" }
            Log.warn("[StartMode] Failed to rewrite GAMECOMMAND shared-control check; forwarding original packet.", error)
            packet
        }
    }

    fun buildDiagnostics(): List<String> {
        return listOf(
            "gameCommandCompatibility.sharedControlRewriteApplied=${sharedControlRewriteAppliedCount.get()}",
            "gameCommandCompatibility.sharedControlRewriteSkipped=${sharedControlRewriteSkippedCount.get()}",
            "gameCommandCompatibility.sharedControlRewriteFailed=${sharedControlRewriteFailedCount.get()}",
            "gameCommandCompatibility.sharedControlRewriteLastReason=$sharedControlRewriteLastReason",
            "gameCommandCompatibility.sharedControlRewriteLastSenderIndex=$sharedControlRewriteLastSenderIndex",
            "gameCommandCompatibility.sharedControlRewriteLastOriginalCheck=$sharedControlRewriteLastOriginalCheck",
            "gameCommandCompatibility.sharedControlRewriteLastTargetCheck=$sharedControlRewriteLastTargetCheck"
        )
    }

    private fun rewriteCommandBytes(commandBytes: ByteArray): RewriteResult? {
        val input = GameInputStream(commandBytes)
        val output = GameOutputStream()

        val senderIndex = input.readByte()
        output.writeByte(senderIndex)

        val hasOperationUnit = input.readBoolean()
        output.writeBoolean(hasOperationUnit)
        if (hasOperationUnit) {
            copyOperationUnitBlock(input, output)
        }

        output.writeBoolean(input.readBoolean())
        output.writeBoolean(input.readBoolean())
        output.writeInt(input.readInt())
        output.writeInt(input.readInt())

        val hasRallyPoint = input.readBoolean()
        output.writeBoolean(hasRallyPoint)
        if (hasRallyPoint) {
            output.writeFloat(input.readFloat())
            output.writeFloat(input.readFloat())
        }

        output.writeBoolean(input.readBoolean())
        val opsQty = input.readInt()
        output.writeInt(opsQty)
        if (opsQty > 0) {
            output.writeBytes(input.readNBytes(opsQty * Long.SIZE_BYTES))
        }

        val hasPlayer = input.readBoolean()
        output.writeBoolean(hasPlayer)
        if (hasPlayer) {
            output.writeByte(input.readByte())
        }

        val hasMapPoint = input.readBoolean()
        output.writeBoolean(hasMapPoint)
        if (hasMapPoint) {
            output.writeFloat(input.readFloat())
            output.writeFloat(input.readFloat())
        }

        output.writeBytes(input.readNBytes(8))
        output.writeString(input.readString())
        output.writeBoolean(input.readBoolean())

        val originalCheck = input.readShort().toInt()
        val targetCheck = currentSharedControlCheck()
        if (originalCheck == targetCheck) {
            recordSkipped("same_value", senderIndex, originalCheck, targetCheck)
            return null
        }

        output.writeShort(targetCheck.toShort())
        output.writeBytes(input.readAllBytes())
        return RewriteResult(
            bytes = output.getByteArray(),
            senderIndex = senderIndex,
            originalCheck = originalCheck,
            targetCheck = targetCheck
        )
    }

    private fun copyOperationUnitBlock(input: GameInputStream, output: GameOutputStream) {
        output.writeInt(input.readInt())
        val unitType = input.readInt()
        output.writeInt(unitType)
        if (unitType == -2) {
            output.writeString(input.readString())
        }
        output.writeFloat(input.readFloat())
        output.writeFloat(input.readFloat())
        output.writeBytes(input.readNBytes(20))
        val hasActionId = input.readBoolean()
        output.writeBoolean(hasActionId)
        if (hasActionId) {
            output.writeString(input.readString())
        }
    }

    private fun currentSharedControlCheck(): Int {
        val module = HeadlessModuleManage.hps
        if (!module.gameLinkServerData.sharedcontrol) {
            return 0
        }

        var mask = 0
        val playerManage = module.room.playerManage
        playerManage.playerGroup.eachAll { player ->
            val index = runCatching { player.index }.getOrDefault(-1)
            val team = runCatching { player.team }.getOrDefault(Int.MIN_VALUE)
            val activeOccupant = player.isAi || playerManage.isConnectedHumanPlayer(player)
            if (activeOccupant && index in 0..15 && !StartModeLobbySupport.isSpectatorTeam(team)) {
                mask = mask or (1 shl index)
            }
        }
        return mask.toShort().toInt()
    }

    private fun recordApplied(result: RewriteResult) {
        sharedControlRewriteAppliedCount.incrementAndGet()
        sharedControlRewriteLastReason = "applied"
        sharedControlRewriteLastSenderIndex = result.senderIndex
        sharedControlRewriteLastOriginalCheck = result.originalCheck
        sharedControlRewriteLastTargetCheck = result.targetCheck
    }

    private fun recordSkipped(reason: String, senderIndex: Int, originalCheck: Int, targetCheck: Int) {
        sharedControlRewriteSkippedCount.incrementAndGet()
        sharedControlRewriteLastReason = reason
        sharedControlRewriteLastSenderIndex = senderIndex
        sharedControlRewriteLastOriginalCheck = originalCheck
        sharedControlRewriteLastTargetCheck = targetCheck
    }

    private data class RewriteResult(
        val bytes: ByteArray,
        val senderIndex: Int,
        val originalCheck: Int,
        val targetCheck: Int
    )
}
