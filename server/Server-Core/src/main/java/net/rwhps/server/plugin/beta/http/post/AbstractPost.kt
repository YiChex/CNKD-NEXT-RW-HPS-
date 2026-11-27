/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.beta.http.post

import net.rwhps.server.net.core.web.WebPost
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.plugin.beta.http.WebStatus.Companion.toWebStatusJson

/**
 *
 *
 * @date 2024/2/16 17:15
 * @author Dr (dr@der.kim)
 */
abstract class AbstractPost : WebPost() {
    abstract val prefixURL: String

    protected fun checkStringPost(accept: AcceptWeb, send: SendWeb, type: String): String? {
        val json = stringPostDataResolveToJson(accept)
        if (json.getString(type, "").isBlank()) {
            send.status = io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST
            send.setData("".toWebStatusJson("Unknown parameter"))
            // The authenticated route owns response transmission, including errors.
            return null
        }
        return json.getString(type)
    }
}
