/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.handler

import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.util.Time.concurrentMillis

/**
 * @author Dr (dr@der.kim)
 */
internal object TimeoutDetection {
    internal fun checkTimeoutDetection(typeConnect: TypeConnect?): Boolean {
        if (typeConnect == null) {
            return true
        }

        return checkTimeoutDetection(typeConnect.abstractNetConnect)
    }

    private fun checkTimeoutDetection(abstractNetConnect: AbstractNetConnect?): Boolean {
        if (abstractNetConnect == null) {
            return true
        }

        if (abstractNetConnect.connectReceiveData.receiveBigPacket) {
            return false
        }

        return if (abstractNetConnect.connectReceiveData.inputPassword) {
            /* 5min No response judgment close */
            concurrentMillis() > (abstractNetConnect.lastReceivedTime + 300 * 1000L)
        } else {
            /* 3min No response judgment close */
            concurrentMillis() > (abstractNetConnect.lastReceivedTime + 180 * 1000L)
        }

    }
}