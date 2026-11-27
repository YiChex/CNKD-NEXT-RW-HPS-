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
 * 指定变量是同步的。一般来说，这并不会改变IDEA的行为——它只是一个标记，表明指定变量是同步的。
 * Specifies that the variables are synchronous.
 * In general, this does not change the behavior of IDEA - it is just a flag indicating that the specified variables are synchronous.
 *
 * 表面使用了 [net.rwhps.server.util.Synchronize] 来通过 Kotlin 的委托代理完成 Get/Set 的同步方案
 *
 * @date  2023/6/23 9:55
 * @author Dr (dr@der.kim)
 */
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
@Target(
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.PROPERTY
)
annotation class SynchronizeMark
