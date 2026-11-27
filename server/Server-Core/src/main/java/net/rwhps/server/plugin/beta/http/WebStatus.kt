/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http

import net.rwhps.server.util.inline.toJson

/**
 * @date  2023/7/1 16:30
 * @author Dr (dr@der.kim)
 */
data class WebStatus(
    val status: String, val data: String
) {
    companion object {
        fun String.toWebStatus(status: String = "OK"): WebStatus {
            return WebStatus(status, this)
        }

        fun String.toWebStatusJson(status: String = "OK"): String {
            return WebStatus(status, this).toJson()
        }
    }
}