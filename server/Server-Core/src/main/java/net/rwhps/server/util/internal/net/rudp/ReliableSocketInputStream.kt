/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util.internal.net.rudp

import net.rwhps.server.io.input.ClearableAndReusableByteArrayInputStream

/**
 * This class extends InputStream to implement a ReliableSocketInputStream.
 * Note that this class should **NOT** be public.
 *
 * @author Dr (dr@der.kim)
 */
internal class ReliableSocketInputStream(
    private val socket: ReliableSocket
): ClearableAndReusableByteArrayInputStream() {
    private val bytesCache = ByteArray(socket.receiveBufferSize)

    /**
     * 尝试读取数据
     *
     * @return Int
     */
    fun update(): Int {
        try {
            val readLength = socket.read(bytesCache, 0, bytesCache.size)

            if (readLength > 0) {
                addBytes(bytesCache, readLength)
            }

            return readLength
        } catch (e: Exception) {
            // ignore, This part is indifferent
        }

        return 0
    }

}
