/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.temp

import net.rwhps.server.data.totalizer.TimeAndNumber

/**
 *
 *
 * @date 2023/11/25 20:28
 * @author Dr (dr@der.kim)
 */
class ConnectReceiveData {
    var receiveBigPacket = false
    var receiveBigPacketCount = TimeAndNumber(4,1)

    /**
     * Get whether you are entering a password
     * @return Boolean
     */
    var inputPassword: Boolean = false
}