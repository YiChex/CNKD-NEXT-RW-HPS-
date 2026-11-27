/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.annotations.mark

/**
 * @author Dr (dr@der.kim)
 */
class AsmMark {
    /**
     * 指定类被ASM后是兼容ClassLoader的。
     * 一般来说，这并不会改变IDEA的行为——它只是一个标记，表明指定方法是未完成的。
     *
     * The specified class is compatible with Class Loader after being ASM.
     * In general, this doesn't change the behavior of the idea - it's just a token that indicates that the specified method is incomplete。
     *
     * @author Dr (dr@der.kim)
     */
    @Retention(AnnotationRetention.SOURCE)
    @MustBeDocumented
    @Target(
            AnnotationTarget.CLASS,
    )
    internal annotation class ClassLoaderCompatible
}
// I've seen it here, why don't you give me a star
// 都看到这里了 怎么还不给我来个star
