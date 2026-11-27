/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.alone

import io.sigpipe.jbsdiff.Diff
import io.sigpipe.jbsdiff.InvalidHeaderException
import io.sigpipe.jbsdiff.Patch
import org.apache.commons.compress.compressors.CompressorException
import java.io.IOException
import java.io.OutputStream

/**
 * @date 2023/7/26 13:25
 * @author Dr (dr@der.kim)
 */
object DiffUpdate {
    @JvmStatic
    @Throws(CompressorException::class, InvalidHeaderException::class, IOException::class)
    fun diff(oldBytes: ByteArray, newBytes: ByteArray, out: OutputStream) {
        Diff.diff(oldBytes, newBytes, out)
    }

    @JvmStatic
    @Throws(CompressorException::class, InvalidHeaderException::class, IOException::class)
    fun patch(oldBytes: ByteArray, newBytes: ByteArray, out: OutputStream) {
        Patch.patch(oldBytes, newBytes, out)
    }
}