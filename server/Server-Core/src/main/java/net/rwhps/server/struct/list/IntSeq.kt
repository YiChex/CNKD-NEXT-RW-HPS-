/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.struct.list

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntLists
import java.util.List

/**
 * @date  2023/5/26 14:28
 * @author Dr (dr@der.kim)
 */
class IntSeq: BaseSeq<Int> {
    private val listFastUtil = listObject as IntArrayList

    @JvmOverloads
    constructor(threadSafety: Boolean = false): this(16, threadSafety)

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(capacity: Int, threadSafety: Boolean = false): super(
            IntArrayList(capacity) as List<Int>,
            { i -> IntLists.synchronize(i as IntArrayList,i) as List<Int> },
            threadSafety
    )

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(array: IntArray, threadSafety: Boolean = false): super(
            IntArrayList(array) as List<Int>,
            { i -> IntLists.synchronize(i as IntArrayList,i) as List<Int> },
            threadSafety
    )

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @JvmOverloads
    constructor(array: IntArray, length: Int, threadSafety: Boolean = false): super(
            IntArrayList(array, 0, length) as List<Int>,
            { i -> IntLists.synchronize(i as IntArrayList,i) as List<Int> },
            threadSafety
    )

    override fun elements(): IntArray = listFastUtil.elements()

    override fun <E> toArray(classJava: Class<E>): IntArray = listFastUtil.toArray(IntArray(size))

    override fun clone(): IntSeq = IntSeq(listFastUtil.elements(), threadSafety)
}