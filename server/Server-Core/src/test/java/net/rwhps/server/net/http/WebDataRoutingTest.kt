package net.rwhps.server.net.http

import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import net.rwhps.server.data.global.Data
import net.rwhps.server.net.core.web.WebGet
import net.rwhps.server.net.core.web.WebPost
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WebDataRoutingTest {
    @Test
    fun successfulWildcardGetDoesNotAppendASecond404Response() {
        Data.core // Initialize the shared configuration before HTTP diagnostics/logging.
        val routes = WebData()
        routes.addWebGetInstance("/api/get/**", object: WebGet() {
            override fun get(accept: AcceptWeb, send: SendWeb) { send.setData("one response"); send.send() }
        })
        val channel = EmbeddedChannel()
        try {
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/get/status?x=1")
            routes.runWebGetInstance(request.uri(), request, SendWeb(channel, request), "127.0.0.1")
            val response = channel.readOutbound<FullHttpResponse>()
            assertEquals(HttpResponseStatus.OK, response.status())
            response.release()
            assertNull(channel.readOutbound<Any>())
        } finally { channel.finishAndReleaseAll() }
    }

    @Test
    fun successfulWildcardPostAndUnknownPathsHaveExactlyOneResponse() {
        Data.core
        val routes = WebData()
        routes.addWebPostInstance("/api/post/**", object: WebPost() {
            override fun post(accept: AcceptWeb, send: SendWeb) { send.setData("accepted"); send.send() }
        })
        for ((path, expected) in listOf("/api/post/run" to HttpResponseStatus.OK, "/missing" to HttpResponseStatus.NOT_FOUND)) {
            val channel = EmbeddedChannel()
            try {
                val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path)
                routes.runWebPostInstance(path, "{}", request, SendWeb(channel, request), "127.0.0.1")
                val response = channel.readOutbound<FullHttpResponse>()
                assertEquals(expected, response.status())
                response.release()
                assertNull(channel.readOutbound<Any>())
            } finally { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun invalidManagementCommandBodyIsSentOnceByTheOwningRoute() {
        Data.core
        val forwarding = net.rwhps.server.plugin.beta.http.MessageForwardingCenter()
        val routes = WebData()
        routes.addWebPostInstance("/HttpApi/api/post/**", forwarding.postCategorize)
        val channel = EmbeddedChannel()
        try {
            val path = "/HttpApi/api/post/run/ServerCommand"
            val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path)
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
            routes.runWebPostInstance(path, "{}", request, SendWeb(channel, request), "127.0.0.1")
            val response = channel.readOutbound<FullHttpResponse>()
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
            response.release()
            assertNull(channel.readOutbound<Any>())
        } finally { channel.finishAndReleaseAll() }
    }
}
