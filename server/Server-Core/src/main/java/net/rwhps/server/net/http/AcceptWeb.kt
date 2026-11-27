/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.http

import io.netty.handler.codec.http.HttpRequest
import io.netty.util.AsciiString

/**
 * 接受的数据
 * @property getUrl   请求的 URL
 * @property urlData  URL 携带的数据
 * @property data     POST 发送的数据
 * @constructor
 *
 * @author Dr (dr@der.kim)
 *
 * 请注意 在 GET 内 您获取的 data 为 空("") , 您需要使用 urlData 来得到数据
 */
class AcceptWeb(
    val getUrl: String, val urlData: String, val data: String, val remoteIp: String, request: HttpRequest
) {
    private val headers = request.headers()

    fun getHeaders(key: AsciiString): String? = getHeaders(key.toString())
    fun getHeaders(key: String): String? = headers.get(key)
}
