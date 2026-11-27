/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.netconnectprotocol

import net.rwhps.server.core.ServiceLoader
import net.rwhps.server.core.ServiceLoader.ServiceType
import net.rwhps.server.io.packet.Packet
import net.rwhps.server.net.core.ConnectionAgreement
import net.rwhps.server.net.core.IRwHps
import net.rwhps.server.net.core.TypeConnect
import net.rwhps.server.net.core.server.AbstractNetConnect
import net.rwhps.server.net.core.server.packet.AbstractNetPacket
import net.rwhps.server.util.log.Log
import net.rwhps.server.util.log.exp.ImplementedException

/**
 * 核心协议实现
 * @property netType NetType                        : 使用的Net协议类型
 * @property typeConnect TypeConnect                : 连接解析器
 * @property abstractNetPacket AbstractNetPacket    : NetPacket
 * @author Dr (dr@der.kim)
 */
open class RwHps(
    private val classLoader: ClassLoader,
    override val netType: IRwHps.NetType
): IRwHps {
    override val typeConnect: TypeConnect = implementedException {
        try {
            val protocolClass = ServiceLoader.getServiceClass(classLoader, ServiceType.Protocol, netType.name)
            ServiceLoader.getService(classLoader, ServiceType.ProtocolType, netType.name, Class::class.java).newInstance(protocolClass) as TypeConnect
        } catch (e: ImplementedException) {
            /*
             * D-06 follow-up: this placeholder used to throw NotImplementedError from every
             * member. NotImplementedError is an Error, so it escaped `catch (Exception)`
             * handlers and killed the calling thread. The project's own catchable
             * ImplementedException is thrown instead, with the protocol name included.
             */
            object: TypeConnect {
                override fun getTypeConnect(connectionAgreement: ConnectionAgreement): TypeConnect {
                    throw ImplementedException("ProtocolType not registered for netType=${netType.name}")
                }

                override fun processConnect(packet: Packet) {
                    throw ImplementedException("ProtocolType not registered for netType=${netType.name}")
                }

                override val abstractNetConnect: AbstractNetConnect
                    get() = throw ImplementedException("ProtocolType not registered for netType=${netType.name}")
                override val version: String
                    get() = throw ImplementedException("ProtocolType not registered for netType=${netType.name}")
            }
        }
    }

    override val abstractNetPacket: AbstractNetPacket by lazy {
        implementedException {
            try {
                ServiceLoader.getService(ServiceType.ProtocolPacket, netType.name)
            } catch (e: ImplementedException) {
                ServiceLoader.getService(ServiceType.ProtocolPacket, IRwHps.NetType.GlobalProtocol.name)
            }.newInstance() as AbstractNetPacket
        }
    }

    @Throws(ImplementedException::class)
    private fun <T> implementedException(run: ()->T): T {
        try {
            return run()
        } catch (e: ImplementedException) {
            throw ImplementedException("Not yet implemented")
        } catch (e: Exception) {
            Log.fatal(e)
            throw ImplementedException("Implemented has Error")
        }
    }
}