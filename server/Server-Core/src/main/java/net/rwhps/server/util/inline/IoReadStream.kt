/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */


@file:JvmName("InlineUtils") @file:JvmMultifileClass

package net.rwhps.server.util.inline

import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.file.FileUtils
import net.rwhps.server.util.io.IoRead
import java.io.InputStream

/**
 * IoReadStream
 * @author Dr (dr@der.kim)
 */
fun InputStream.readBytes(): ByteArray {
    return IoRead.readInputStreamBytes(this)
}

fun InputStream.readString(): String {
    return FileUtils.readFileString(this)
}

fun InputStream.readFileListString(): Seq<String> {
    return FileUtils.readFileListString(this)
}

fun Class<*>.readBytes(): ByteArray {
    return this.javaClass.toString().readAsClassBytes()
}

fun String.readAsClassBytes(): ByteArray {
    return FileUtils.getInternalFileStream(
            "/" + this.replace("class ", "").replace(".", "/") + ".class"
    ).readBytes()
}