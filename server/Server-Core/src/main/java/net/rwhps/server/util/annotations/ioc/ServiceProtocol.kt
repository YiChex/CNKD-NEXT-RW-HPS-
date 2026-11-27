/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.annotations.ioc

import net.rwhps.server.core.ServiceLoader.ServiceType
import net.rwhps.server.util.annotations.DidNotFinish

/**
 * 自动扫描注解-扫描Jar的Service服务
 * @property serviceType ServiceType
 * @property serviceName ServiceName
 * @property defClassLoaderSkip 跳过默认类加载器, 即如果 [defClassLoaderSkip] 为 true, 那么将只接受手动加载, 将跳过自动加载
 *
 * @date 2024/4/22 下午8:58
 * @author Dr (dr@der.kim)
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.ANNOTATION_CLASS)
@DidNotFinish
annotation class ServiceProtocol(
    val serviceType: ServiceType, val serviceName: String,
    val defClassLoaderSkip: Boolean = false
)