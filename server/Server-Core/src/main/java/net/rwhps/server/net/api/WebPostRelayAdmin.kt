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
import net.rwhps.server.net.core.web.WebPost
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.util.annotations.mark.PrivateMark
import net.rwhps.server.util.inline.toJson

@PrivateMark
class WebPostRelayAdmin: WebPost() {
    companion object {
        private val supportedActions = listOf(
            "status",
            "rooms",
            "advertising_status",
            "advertising_check",
            "ban_ip",
            "unban_ip",
            "ban_ip24",
            "unban_ip24"
        )
    }

    override fun post(accept: AcceptWeb, send: SendWeb) {
        RelayApiSupport.recordRequest(RelayApiSupport.Scope.ADMIN)
        if (!RelayApiSupport.isScopeEnabled(RelayApiSupport.Scope.ADMIN)) {
            RelayApiSupport.denyDisabled(RelayApiSupport.Scope.ADMIN, accept, send)
            return
        }
        if (!RelayApiSupport.isAllowed(RelayApiSupport.Scope.ADMIN, accept.remoteIp)) {
            RelayApiSupport.deny(RelayApiSupport.Scope.ADMIN, accept, send)
            return
        }
        if (!RelayApiSupport.enforceRateLimit(RelayApiSupport.Scope.ADMIN, accept, send)) {
            return
        }
        if (!RelayApiSupport.isAuthorized(RelayApiSupport.Scope.ADMIN, accept)) {
            RelayApiSupport.denyUnauthorized(RelayApiSupport.Scope.ADMIN, accept, send)
            return
        }

        RelayApiSupport.applyJsonHeaders(accept, send)

        try {
            val body = stringPostDataResolveToJson(accept)
            val action = body.getString("action").trim().lowercase()
            val result = when (action) {
                "status" -> RelayAdminApiSupport.buildStatus()
                "rooms" -> RelayAdminApiSupport.buildRooms(
                    includeClosed = body.getBoolean("includeClosed", Data.configRelayApi.roomsApiIncludeClosed) == true,
                    limit = body.getInt("limit", Data.configRelayApi.roomsApiLimit) ?: Data.configRelayApi.roomsApiLimit
                )
                "advertising_status" -> RelayAdminApiSupport.advertisingStatus()
                "advertising_check" -> RelayAdminApiSupport.advertisingCheck()
                "ban_ip" -> RelayAdminApiSupport.banIp(body.getString("ip"), body.getBoolean("disconnectNow", true) == true)
                "unban_ip" -> RelayAdminApiSupport.unbanIp(body.getString("ip"))
                "ban_ip24" -> RelayAdminApiSupport.banIp24(body.getString("ip24"))
                "unban_ip24" -> RelayAdminApiSupport.unbanIp24(body.getString("ip24"))
                else -> throw IllegalArgumentException("unsupported action: $action, supportedActions=$supportedActions")
            }
            if (action.startsWith("ban_") || action.startsWith("unban_") || action == "advertising_check") {
                RelayApiSupport.invalidateCachedResponses()
            }

            send.setData(
                mapOf(
                    "ok" to true,
                    "action" to action,
                    "data" to result
                ).toJson()
            )
        } catch (e: Exception) {
            RelayApiSupport.recordError(RelayApiSupport.Scope.ADMIN, e)
            send.status = HttpResponseStatus.BAD_REQUEST
            RelayApiSupport.applyJsonHeaders(accept, send)
            send.setData(
                mapOf(
                    "ok" to false,
                    "error" to "bad_request",
                    "message" to (e.message ?: "invalid admin request"),
                    "supportedActions" to supportedActions
                ).toJson()
            )
        }
        send.send()
    }
}
