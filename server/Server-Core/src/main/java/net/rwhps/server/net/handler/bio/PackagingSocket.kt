/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler.bio

import net.rwhps.server.net.core.TypeConnect
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Wrap Socket and add custom dta part like Netty
 * @property socket ReliableClientSocket
 * @property remoteSocketAddressString String
 * @property outputStream OutputStream
 * @property inputStream InputStream
 * @property localPort Int
 * @property isClosed Boolean
 * @property type TypeConnect?
 * @constructor
 *
 * @date 2023/7/17 8:23
 * @author Dr (dr@der.kim)
 */
internal class PackagingSocket(private val socket: Socket) {
    val remoteSocketAddressString = socket.remoteSocketAddress.toString()
    val outputStream: OutputStream = socket.outputStream
    val inputStream: InputStream = socket.inputStream
    val localPort = socket.localPort

    val isClosed = socket.isClosed

    var type: TypeConnect? = null

    fun close() {
        socket.close()
    }
}
