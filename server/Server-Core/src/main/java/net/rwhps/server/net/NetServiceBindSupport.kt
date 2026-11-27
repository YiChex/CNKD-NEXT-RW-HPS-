/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3.
 */

package net.rwhps.server.net

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import java.net.BindException

internal object NetServiceBindSupport {
    fun awaitSuccessfulBind(future: ChannelFuture): Channel {
        future.sync()
        if (!future.isSuccess) {
            throw future.cause() ?: IllegalStateException("Netty bind completed without success or failure cause")
        }
        return future.channel()
    }

    fun isBindFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is BindException) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
