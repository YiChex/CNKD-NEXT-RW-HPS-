/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import io.netty.handler.codec.http.HttpResponseStatus
import net.rwhps.server.net.core.web.WebGet
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.inline.toJson

@PrivateMark
class WebGetRelayStatusData: WebGet() {
    override fun get(accept: AcceptWeb, send: SendWeb) {
        RelayApiSupport.recordRequest(RelayApiSupport.Scope.STATUS)
        if (!RelayApiSupport.isAllowed(RelayApiSupport.Scope.STATUS, accept.remoteIp)) {
            RelayApiSupport.deny(RelayApiSupport.Scope.STATUS, accept, send)
            return
        }
        if (!RelayApiSupport.enforceRateLimit(RelayApiSupport.Scope.STATUS, accept, send)) {
            return
        }

        try {
            val params = RelayApiSupport.queryParams(accept)
            val limit = RelayApiSupport.queryInt(
                params,
                "limit",
                RelayStatusSupport.statusApiPublicRoomLimit(),
                1,
                100
            )
            val cache = RelayApiSupport.cachedJson(
                cacheKey = "status:limit=$limit",
                ttlMillis = RelayApiSupport.statusCacheTtlMillis(),
                forceRefresh = RelayApiSupport.shouldForceRefresh(params),
                scope = RelayApiSupport.Scope.STATUS
            ) {
                mapOf(
                    "ok" to true,
                    "data" to RelayStatusSupport.buildStatus(limit)
                ).toJson()
            }
            RelayApiSupport.applyJsonHeaders(accept, send, cache)
            send.setData(cache.body)
        } catch (e: Exception) {
            RelayApiSupport.recordError(RelayApiSupport.Scope.STATUS, e)
            send.status = HttpResponseStatus.INTERNAL_SERVER_ERROR
            RelayApiSupport.applyJsonHeaders(accept, send)
            send.setData(
                mapOf(
                    "ok" to false,
                    "error" to "status_build_failed",
                    "message" to (e.message ?: "relay status build failed")
                ).toJson()
            )
        }
        send.send()
    }
}
