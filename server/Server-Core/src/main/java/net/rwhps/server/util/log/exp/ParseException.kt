/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.log.exp

import java.io.IOException

class ParseException(type: String): IOException(type) {
    /**
     * Constructs a new json exception with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to [.initCause].
     *
     * @param json     the json text which cause JSONParseException
     * @param position the position of illegal escape char at json text;
     * @param message  the detail message. The detail message is saved for
     * later retrieval by the [.getMessage] method.
     */
    class ParseJsonException(val json: String, val position: Int, message: String?): RuntimeException(message) {

        /**
         * Get message about error when parsing illegal json
         *
         * @return error message
         */
        override val message: String
            get() {
                val maxTipLength = 10
                var end = position + 1
                var start = end - maxTipLength
                if (start < 0) start = 0
                if (end > json.length) end = json.length
                return String.format("%s  (%d):%s", json.substring(start, end), position, super.message)
            }

        companion object {
            private const val serialVersionUID = 3674125742687171239L
        }
    }
}