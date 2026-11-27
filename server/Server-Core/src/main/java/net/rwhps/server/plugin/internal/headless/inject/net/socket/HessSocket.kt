/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.net.socket

import net.rwhps.server.net.core.ConnectionAgreement
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel


/**
 * @author Dr (dr@der.kim)
 */
class HessSocket(private val connect: ConnectionAgreement): Socket() {
    @Volatile
    private var close = false

    override fun bind(socketAddress: SocketAddress?) {
        throw RuntimeException("Not supported")
    }

    @Synchronized
    override fun close() {
        if (!close) {
            close = true
            connect.close(null)
        }
    }

    override fun connect(socketAddress: SocketAddress?, i: Int) {
        throw RuntimeException("Not supported")
    }

    override fun connect(socketAddress: SocketAddress?) {
        throw RuntimeException("Not supported")
    }

    override fun getChannel(): SocketChannel {
        throw RuntimeException("Not supported")
    }

    override fun getInetAddress(): InetAddress? {
        return null
    }

    override fun getLocalAddress(): InetAddress? {
        return null
    }

    override fun getLocalSocketAddress(): SocketAddress? {
        return null
    }

    override fun getRemoteSocketAddress(): SocketAddress? {
        return null
    }

    override fun getInputStream(): InputStream? {
        return null
    }

    override fun getKeepAlive(): Boolean {
        return true
    }

    override fun getLocalPort(): Int {
        return 5555
    }

    override fun getOOBInline(): Boolean {
        return false
    }

    override fun getOutputStream(): OutputStream? {
        return null
    }

    override fun getPort(): Int {
        return 5555
    }

    @Synchronized
    override fun getReceiveBufferSize(): Int {
        return 512
    }

    override fun getReuseAddress(): Boolean {
        return false
    }

    @Synchronized
    override fun getSendBufferSize(): Int {
        return 512
    }

    override fun getSoLinger(): Int {
        return 0
    }

    @Synchronized
    override fun getSoTimeout(): Int {
        return 0
    }

    override fun getTcpNoDelay(): Boolean {
        return true
    }

    override fun getTrafficClass(): Int {
        return 0
    }

    override fun isBound(): Boolean {
        return true
    }

    override fun isClosed(): Boolean {
        return close
    }

    override fun isConnected(): Boolean {
        return true
    }

    override fun isInputShutdown(): Boolean {
        return false
    }

    override fun isOutputShutdown(): Boolean {
        return false
    }

    override fun sendUrgentData(i: Int) {}

    override fun setKeepAlive(z: Boolean) {}

    override fun setOOBInline(z: Boolean) {}

    override fun setPerformancePreferences(i: Int, i2: Int, i3: Int) {}

    @Synchronized
    override fun setReceiveBufferSize(i: Int) {
    }

    override fun setReuseAddress(z: Boolean) {}

    @Synchronized
    override fun setSendBufferSize(i: Int) {
    }

    override fun setSoLinger(z: Boolean, i: Int) {}

    @Synchronized
    override fun setSoTimeout(i: Int) {
    }

    override fun setTcpNoDelay(z: Boolean) {}

    override fun setTrafficClass(i: Int) {}

    override fun shutdownInput() {}

    override fun shutdownOutput() {}

    override fun toString(): String {
        return "<HessSocket>"
    }
}