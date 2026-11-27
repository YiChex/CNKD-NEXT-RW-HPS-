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
 * ONLY PRIVATE SERVER (RELAY-CN Team)
 *
 * This category does not provide major technical support
 *
 * @date  2023/8/19 8:21
 * @author Dr (dr@der.kim)
 */
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
@Target(
        AnnotationTarget.CLASS,
        AnnotationTarget.CONSTRUCTOR,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.EXPRESSION, AnnotationTarget.PROPERTY
)
annotation class PrivateMark
