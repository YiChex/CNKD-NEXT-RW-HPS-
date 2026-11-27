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
import net.rwhps.server.data.global.Data
import net.rwhps.server.net.core.web.WebGet
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.util.inline.toJson

class WebGetRelayRoomsData: WebGet() {
    override fun get(accept: AcceptWeb, send: SendWeb) {
        RelayApiSupport.recordRequest(RelayApiSupport.Scope.ROOMS)
        if (!RelayApiSupport.isScopeEnabled(RelayApiSupport.Scope.ROOMS)) {
            RelayApiSupport.denyDisabled(RelayApiSupport.Scope.ROOMS, accept, send)
            return
        }
        if (!RelayApiSupport.isAllowed(RelayApiSupport.Scope.ROOMS, accept.remoteIp)) {
            RelayApiSupport.deny(RelayApiSupport.Scope.ROOMS, accept, send)
            return
        }
        if (!RelayApiSupport.enforceRateLimit(RelayApiSupport.Scope.ROOMS, accept, send)) {
            return
        }

        try {
            val params = RelayApiSupport.queryParams(accept)
            val limit = RelayApiSupport.queryInt(
                params,
                "limit",
                Data.configRelayApi.roomsApiLimit,
                1,
                10000
            )
            val includeClosed = RelayApiSupport.queryBoolean(
                params,
                "includeClosed",
                Data.configRelayApi.roomsApiIncludeClosed
            )
            val cache = RelayApiSupport.cachedJson(
                cacheKey = "rooms:includeClosed=$includeClosed:limit=$limit",
                ttlMillis = RelayApiSupport.roomsCacheTtlMillis(),
                forceRefresh = RelayApiSupport.shouldForceRefresh(params),
                scope = RelayApiSupport.Scope.ROOMS
            ) {
                mapOf(
                    "ok" to true,
                    "data" to RelayStatusSupport.buildRooms(includeClosed, limit)
                ).toJson()
            }
            RelayApiSupport.applyJsonHeaders(accept, send, cache)
            send.setData(cache.body)
        } catch (e: Exception) {
            RelayApiSupport.recordError(RelayApiSupport.Scope.ROOMS, e)
            send.status = HttpResponseStatus.INTERNAL_SERVER_ERROR
            RelayApiSupport.applyJsonHeaders(accept, send)
            send.setData(
                mapOf(
                    "ok" to false,
                    "error" to "rooms_build_failed",
                    "message" to (e.message ?: "relay rooms build failed")
                ).toJson()
            )
        }
        send.send()
    }
}
