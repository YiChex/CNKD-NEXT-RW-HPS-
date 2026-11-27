/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.core

import net.rwhps.server.io.packet.type.AbstractPacketType
import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.core.server.packet.AbstractNetPacket
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.core.DependsClassLoader
import net.rwhps.server.util.inline.ifNullResult
import net.rwhps.server.util.log.exp.ImplementedException
import net.rwhps.server.util.log.exp.VariableException
import java.lang.reflect.Constructor

/**
 * Service loader module for RW-HPS
 * SIP without Java
 * @author Dr (dr@der.kim)
 */
/**
 * TODO
 *
 * 1 使其通过扫描注解来判断继承的主类
 *
 * 无限可能
 */
object ServiceLoader {
    private val ServiceLoaderData: MutableMap<String, Class<*>> = HashMap()
    private val ServiceObjectData: MutableMap<String, Any> = HashMap()

    /**
     * Get service instance
     * @param serviceType ServiceType            : Service type
     * @param serviceName String                 : Name
     * @param parameterTypes Array<out Class<*>> : Construction Parameters
     * @return Constructor<*>                    : Constructor
     */
    fun getService(serviceType: ServiceType, serviceName: String, vararg parameterTypes: Class<*>): Constructor<*> {
        return getService(null, serviceType, serviceName, *parameterTypes)
    }

    /**
     * Get service instance
     *
     * @param classLoader ClassLoader            : ClassLoader
     * @param serviceType ServiceType            : Service type
     * @param serviceName String                 : Name
     * @param parameterTypes Array<out Class<*>> : Construction Parameters
     * @return Constructor<*>                    : Constructor
     */
    fun getService(classLoader: ClassLoader?, serviceType: ServiceType, serviceName: String, vararg parameterTypes: Class<*>): Constructor<*> {
        val serviceClass = ServiceLoaderData[serviceType.name + serviceName + classLoader.ifNullResult("") { it.name }]
        if (serviceClass != null) {
            return ReflectionUtils.accessibleConstructor(serviceClass, *parameterTypes)
        } else {
            throw ImplementedException("${serviceType.name}:$serviceName")
        }
    }


    /**
     * 获取服务 Class
     * @param serviceType ServiceType : 服务类型
     * @param serviceName String      : 名称
     * @return Class<*>               : Class
     */
    fun getServiceClass(serviceType: ServiceType, serviceName: String): Class<*> {
        return getServiceClass(null, serviceType, serviceName)
    }

    /**
     * 获取服务 Class
     * @param serviceType ServiceType : 服务类型
     * @param serviceName String      : 名称
     * @return Class<*>               : Class
     */
    fun getServiceClass(classLoader: ClassLoader?, serviceType: ServiceType, serviceName: String): Class<*> {
        val serviceClass = ServiceLoaderData[serviceType.name + serviceName + classLoader.ifNullResult("") { it.name }]
        if (serviceClass != null) {
            return serviceClass
        } else {
            throw ImplementedException("${serviceType.name}:$serviceName")
        }
    }

    /**
     * 获取服务 Class
     * @param serviceType ServiceType : 服务类型
     * @param serviceName String      : 名称
     * @return Class<*>               : Class
     */
    fun getServiceObject(serviceType: ServiceType, serviceName: String): Any {
        val serviceClass = ServiceObjectData[serviceType.name + serviceName]
        if (serviceClass != null) {
            return serviceClass
        } else {
            throw ImplementedException("${serviceType.name}:$serviceName")
        }
    }

    /**
     * Add a new service based on service type
     * @param serviceType  ServiceType
     * @param serviceName  Service Name
     * @param serviceClass Class of service
     * @param cover        Whether to overwrite existing
     * @throws VariableException
     */
    @JvmOverloads
    @Throws(VariableException.TypeMismatchException::class)
    fun addService(serviceType: ServiceType, serviceName: String, serviceClass: Class<*>, cover: Boolean = false) {
        if (ReflectionUtils.findSuperClass(serviceClass, serviceType.classType)) {
            throw VariableException.TypeMismatchException("[AddService] ${serviceType.classType} : ${serviceClass.name}")
        }
        // 如果 [DependsClassLoader] 存在, 那么就绑定到对应类加载器
        val name = if (serviceType.classType.getAnnotation(DependsClassLoader::class.java) != null) {
            serviceType.name + serviceName + serviceClass.classLoader.name
        } else {
            serviceType.name + serviceName
        }
        // 跳过已经存在的
        if (ServiceLoaderData.containsKey(name) && !cover) {
            return
        }

        ServiceLoaderData[name] = serviceClass
    }

    /**
     * Add a new service based on service type
     * @param serviceType  ServiceType
     * @param serviceName  Service Name
     * @param serviceObject Object of service
     * @param cover        Whether to overwrite existing
     * @throws VariableException
     */
    @JvmOverloads
    @Throws(VariableException.TypeMismatchException::class)
    fun addServiceObject(serviceType: ServiceType, serviceName: String, serviceObject: Any, cover: Boolean = false) {
        if (ReflectionUtils.findSuperClass(serviceObject.javaClass, serviceType.classType)) {
            throw VariableException.TypeMismatchException("[AddService] ${serviceType.classType} : ${serviceObject.javaClass.name}")
        }
        // 跳过已经存在的
        if (ServiceObjectData.containsKey(serviceType.name + serviceName) && !cover) {
            return
        }
        ServiceObjectData[serviceType.name + serviceName] = serviceObject
    }

    /**
     * 这是 RW-HPS 使用的服务类
     * @property classType 父类
     * @constructor
     */
    enum class ServiceType(val classType: Class<*>) {
        Core(Object::class.java),
        IRwHps(net.rwhps.server.net.core.IRwHps::class.java),

        // 协议处理器
        ProtocolType(TypeConnect::class.java),
        // 协议实现
        Protocol(AbstractNetConnect::class.java),

        // 全局共用包解析器
        ProtocolPacket(AbstractNetPacket::class.java),

        // 包类型解析器
        PacketType(AbstractPacketType::class.java);
    }
}