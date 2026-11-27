/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util.internal.net.rudp

/**
 * The listener interface for receiving socket events.
 * The class that is interested in processing a socket
 * event implements this interface.
 *
 * @author Adrian Granados
 * @author Dr (dr@der.kim)
 */
interface ReliableSocketStateListener {
    /**
     * Invoked when the connection is opened.
     */
    fun connectionOpened(sock: ReliableSocket?) {
        // ignored, optionally use
    }

    /**
     * Invoked when the attempt to establish a connection is refused.
     */
    fun connectionRefused(sock: ReliableSocket?) {
        // ignored, optionally use
    }

    /**
     * Invoked when the connection is closed.
     */
    fun connectionClosed(sock: ReliableSocket?) {
        // ignored, optionally use
    }

    /**
     * Invoked when the (established) connection fails.
     */
    fun connectionFailure(sock: ReliableSocket?) {
        // ignored, optionally use
    }

    /**
     * Invoked when the connection is reset.
     */
    fun connectionReset(sock: ReliableSocket?) {
        // ignored, optionally use
    }
}
