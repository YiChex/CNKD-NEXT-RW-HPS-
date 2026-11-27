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
internal class GameSimulationLayer {
    @Retention(AnnotationRetention.SOURCE)
    @MustBeDocumented
    @Target(
            AnnotationTarget.ANNOTATION_CLASS,
            AnnotationTarget.CLASS,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.EXPRESSION
    )
    internal annotation class GameSimulationLayer_Processing

    @Retention(AnnotationRetention.SOURCE)
    @MustBeDocumented
    @Target(
            AnnotationTarget.ANNOTATION_CLASS,
            AnnotationTarget.CLASS,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.EXPRESSION
    )
    internal annotation class GameSimulationLayer_SameAsOldVersion_NeedToMigrateNewVersion

    @Retention(AnnotationRetention.SOURCE)
    @MustBeDocumented
    @Target(
            AnnotationTarget.ANNOTATION_CLASS,
            AnnotationTarget.CLASS,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.EXPRESSION
    )
    internal annotation class GameSimulationLayer_ModuleBasicallyTheSame

    @Retention(AnnotationRetention.SOURCE)
    @MustBeDocumented
    @Target(
            AnnotationTarget.ANNOTATION_CLASS,
            AnnotationTarget.CLASS,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.EXPRESSION
    )
    internal annotation class GameSimulationLayer_DidNotFinish

    @Retention(AnnotationRetention.SOURCE)
    @MustBeDocumented
    @Target(
            AnnotationTarget.ANNOTATION_CLASS,
            AnnotationTarget.CLASS,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.EXPRESSION,
            AnnotationTarget.PROPERTY
    )
    internal annotation class GameSimulationLayer_KeyWords(val keyWords: String)
}
