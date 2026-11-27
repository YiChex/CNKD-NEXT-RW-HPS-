/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.support

internal object OriginalSurrenderPacketProbe {
    private const val SURRENDER_ACTION = 100
    private const val TRAILING_COMMAND_BYTES = 9

    fun matchesEnvelopeSuffix(bytes: ByteArray): Boolean {
        if (bytes.size < 2 + Int.SIZE_BYTES + TRAILING_COMMAND_BYTES) {
            return false
        }
        val wrapperNameLength = readUnsignedShort(bytes, 0)
        val payloadLengthOffset = 2 + wrapperNameLength
        if (payloadLengthOffset + Int.SIZE_BYTES > bytes.size) {
            return false
        }
        val payloadLength = readInt(bytes, payloadLengthOffset)
        if (payloadLength < TRAILING_COMMAND_BYTES) {
            return false
        }
        val payloadEnd = payloadLengthOffset.toLong() + Int.SIZE_BYTES + payloadLength.toLong()
        if (payloadEnd != bytes.size.toLong()) {
            return false
        }

        val actionOffset = payloadEnd.toInt() - TRAILING_COMMAND_BYTES
        if (readInt(bytes, actionOffset) != SURRENDER_ACTION) {
            return false
        }
        var index = actionOffset + Int.SIZE_BYTES
        while (index < payloadEnd.toInt()) {
            if (bytes[index].toInt() != 0) {
                return false
            }
            index++
        }
        return true
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
