/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.packet

/**
 * Class that represents a incoming rcon request
 */
data class RconPacket(
    /**
     * The request ID
     */
    val id: Int,

    /**
     * The request type
     *
     * @see RconConstant for the rcon type
     */
    val type: Int,

    /**
     * The body
     */
    val body: String
) {
    companion object {
        object RconConstant {
            const val SERVERDATA_AUTH: Int = 3
            const val SERVERDATA_AUTH_RESPONSE: Int = 2
            const val SERVERDATA_EXECCOMMAND: Int = 2
            const val SERVERDATA_RESPONSE_VALUE: Int = 0
        }
    }
}
