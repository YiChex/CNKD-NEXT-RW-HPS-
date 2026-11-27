/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */
package net.rwhps.server.util.internal.net.rudp

import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.util.internal.net.rudp.impl.Segment
import net.rwhps.server.util.log.Log.debug
import net.rwhps.server.util.log.Log.error
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.SocketAddress

/**
 * ReliableClientSocket
 *
 * @author Dr (dr@der.kim)
 * @date 2023/7/17 8:41
 */
class ReliableClientSocket(selector: RUDPHeadProcessor, sock: DatagramSocket?, endpoint: SocketAddress?): ReliableSocket(sock) {
    private val queue = ArrayList<Segment?>()
    private val selector: RUDPHeadProcessor

    var type: TypeConnect? = null
    var needLength: Int = 0
    var needType: Int = 0

    override fun init(sock: DatagramSocket, profile: ReliableSocketProfile) {
        super.init(sock, profile)
    }

    var s: DataInputStream? = null

    init {
        this.endpoint = endpoint
        this.selector = selector
    }

    override fun handleSegment(segment: Segment) {
        super.handleSegment(segment)

        if (_in.update() > 0 && _in != null) {
            if (needLength == 0 && needType == 0) {
                if (_in.available() < 8) {
                    return
                }
                if (s == null) {
                    s = DataInputStream(_in)
                }
                try {
                    needLength = s!!.readInt()
                    needType = s!!.readInt()
                } catch (e: IOException) {
                    // ignore
                }
            }

            if (_in.available() >= needLength) {
                selector.registerRead(this)
            }
        }
    }


    override fun receiveSegmentImpl(): Segment? {
        synchronized(queue) {
            while (queue.isEmpty()) {
                try {
                    (queue as Object).wait()
                } catch (xcp: InterruptedException) {
                    error("[Reliable UDP]", xcp)
                }
            }
            return queue.removeAt(0)
        }
    }

    fun segmentReceived(s: Segment) {
        synchronized(queue) {
            queue.add(s)
            (queue as Object).notify()
        }
    }

    override fun closeSocket() {
        synchronized(queue) {
            queue.clear()
            queue.add(null)
            (queue as Object).notify()
        }
    }

    override fun log(msg: String) {
        debug("$port: $msg")
    }
}
