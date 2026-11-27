/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.manage

import net.rwhps.server.data.global.Data
import net.rwhps.server.util.log.Log.error
import net.rwhps.server.util.log.exp.VariableException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*


internal data class HttpCallResult(
    val statusCode: Int?,
    val body: String,
    val statusMessage: String = "",
    val errorMessage: String = ""
) {
    val successful: Boolean
        get() = statusCode != null && statusCode in 200..299

    fun diagnosticResponse(): String {
        if (body.isNotBlank()) {
            return body
        }
        if (statusCode != null) {
            return "HTTP_${statusCode}${statusMessage.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()}"
        }
        return errorMessage.takeIf { it.isNotBlank() }?.let { "NETWORK_ERROR:$it" }.orEmpty()
    }
}


/**
 * HTTP
 * @author Dr (dr@der.kim)
 */
class HttpRequestManage(
    private val client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = Data.userAgent
) {
    private val paramSplitRegex = "&".toRegex()
    private val keyValueSplitRegex = "=".toRegex()

    /**
     * P3-10: release the non-daemon resources owned by the wrapped [OkHttpClient].
     *
     * OkHttp's dispatcher runs its executor service and its connection pool on non-daemon
     * threads, so they must be shut down explicitly for a clean JVM exit. This is
     * deliberately idempotent -- `shutdown()` and `evictAll()` are both safe to call on an
     * already-stopped pool -- because both `Application.exit()` and the shutdown hook call it.
     */
    fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        runCatching { client.cache?.close() }
    }

    fun doGet(url: String): String {
        return doGet(url, logError = true)
    }

    fun doGetQuiet(url: String): String {
        return doGet(url, logError = false)
    }

    fun doGet(url: String, head: Map<String, String>): String {
        return doGet(url, head, logError = true)
    }

    fun doGetQuiet(url: String, head: Map<String, String>): String {
        return doGet(url, head, logError = false)
    }

    private fun doGet(url: String, logError: Boolean): String {
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).build()
        try {
            client.newCall(request).execute().use { response -> return response.body?.string() ?: "" }
        } catch (e: Exception) {
            if (logError) {
                error("[HttpGet] $url", e)
            }
        }
        return ""
    }

    private fun doGet(url: String, head: Map<String, String>, logError: Boolean): String {
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).also {
            head.forEach(it::header)
        }.build()
        try {
            client.newCall(request).execute().use { response -> return response.body?.string() ?: "" }
        } catch (e: Exception) {
            if (logError) {
                error("[HttpGet] $url", e)
            }
        }
        return ""
    }

    /**
     * Send a POST request with Parameter and get back
     * @param url    HTTP URL
     * @param param  Parameter (A=B&C=D)
     * @return       Data
     */
    fun doPost(url: String, param: String): String {
        val body: RequestBody = param.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull())
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).post(body).build()
        return getHttpResultString(request)
    }

    internal fun doPostResult(url: String, param: String): HttpCallResult {
        return doPostResult(url, param, logFailure = true)
    }

    /** Returns a structured failure without writing an exception stack trace. */
    internal fun doPostResultQuiet(url: String, param: String): HttpCallResult {
        return doPostResult(url, param, logFailure = false)
    }

    private fun doPostResult(url: String, param: String, logFailure: Boolean): HttpCallResult {
        val body: RequestBody = param.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull())
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).post(body).build()
        return getHttpCallResult(request, logFailure)
    }


    fun doPost(url: String, data: FormBody.Builder): String {
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).post(data.build()).build()
        return getHttpResultString(request)
    }


    fun doPostJson(url: String, param: String): String {
        val body: RequestBody = param.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).post(body).build()
        return getHttpResultString(request)
    }

    fun doPostJsonQuiet(url: String, param: String): String {
        val body: RequestBody = param.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).post(body).build()
        return getHttpResultString(request, false, logHttpError = false)
    }

    fun doPostJson(url: String, head: Map<String, String>, param: String): String {
        val body: RequestBody = param.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request: Request = Builder().url(url).addHeader("User-Agent", userAgent).also {
            head.forEach(it::header)
        }.post(body).build()
        return getHttpResultString(request)
    }

    private fun parameterConversion(param: String): FormBody.Builder {
        val formBody = FormBody.Builder()
        val paramArray = param.split(paramSplitRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pam in paramArray) {
            val keyValue = pam.split(keyValueSplitRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (param.length < 2) {
                throw VariableException.ArrayRuntimeException("""
                    Error ArrayIndexOutOfBoundsException
                    In : $pam
                    Source : $param
                """.trimIndent())
            }
            formBody.add(keyValue[0], keyValue[1])
        }
        return formBody
    }

    /**
     * Request and Return
     * @param request     Request
     * @return            Result
     */
    private fun getHttpResultString(request: Request): String {
        return try {
            getHttpResultString(request, false)
        } catch (e: Exception) {
            error("[HttpResult]", e)
            ""
        }
    }

    private fun getHttpCallResult(request: Request, logFailure: Boolean): HttpCallResult {
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && logFailure) {
                    error("[HttpResult] ${request.method} ${request.url} -> HTTP ${response.code}", IOException(response.message))
                }
                HttpCallResult(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                    statusMessage = response.message
                )
            }
        } catch (e: Exception) {
            if (logFailure) {
                error("[HttpResult]", e)
            }
            HttpCallResult(
                statusCode = null,
                body = "",
                errorMessage = compactErrorMessage(e)
            )
        }
    }

    private fun compactErrorMessage(error: Exception): String {
        val type = error.javaClass.simpleName.ifBlank { "Exception" }
        val message = error.message.orEmpty().replace(Regex("\\s+"), " ").trim().take(160)
        return if (message.isBlank()) type else "$type:$message"
    }

    /**
     * Request and Return
     * @param request     Request
     * @param resultError Print Error
     * @return            Result
     */
    @Throws(IOException::class)
    private fun getHttpResultString(request: Request, resultError: Boolean, logHttpError: Boolean = true): String {
        var result = ""
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && logHttpError) {
                    error("[HttpResult] ${request.method} ${request.url} -> HTTP ${response.code}", IOException(response.message))
                }
                result = response.body?.string() ?: ""
                response.body?.close()
            }
        } catch (e: Exception) {
            if (resultError) {
                throw e
            }
        }
        return result
    }
}
