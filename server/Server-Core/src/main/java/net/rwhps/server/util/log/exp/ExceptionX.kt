/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log.exp

import net.rwhps.server.util.SystemUtils
import java.io.PrintWriter
import java.io.StringWriter

/**
 * @date  2023/6/1 12:46
 * @author Dr (dr@der.kim)
 */
object ExceptionX {
    /**
     * 解析 Throwable
     * @param traceIn Throwable
     * @return String
     */
    fun resolveTrace(traceIn: Throwable): String {
        val trace = when (traceIn) {
            is NullPointerException -> Java14NullPointerExceptionUsefulMessageSupport.maybeReplaceUsefulNullPointerMessage(traceIn)
            else -> traceIn
        }

        val stringWriter = StringWriter()
        return PrintWriter(stringWriter).use {
            trace.printStackTrace(it)
            return@use stringWriter.buffer.toString()
        }
    }

    /**
     * A support utility which will replace the message of NullPointerException
     * thrown in Java 14+ with the "useful" one when it's not using a custom
     * message.
     *
     * We have to do this because Java 14 will not serialize the required context
     * and therefore when the exception is sent back to the daemon, it loses
     * information required to create a "useful message".
     */
    private object Java14NullPointerExceptionUsefulMessageSupport {
        fun maybeReplaceUsefulNullPointerMessage(throwableIn: Throwable): Throwable {
            if (throwableIn is NullPointerException && SystemUtils.isJavaVersionAtLeast(14F)) {
                var throwable: NullPointerException = throwableIn
                val stackTrace = throwable.stackTrace
                throwable = try {
                    NullPointerException(throwable.message)
                } catch (e: Exception) {
                    // if calling `getMessage()` fails for whatever reason, just ignore
                    // the replacement
                    return throwable
                }
                throwable.stackTrace = stackTrace
                return throwable
            }
            return throwableIn
        }
    }
}