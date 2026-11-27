/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.core

/**
 * 安全认证
 * Permission Connect Status
 *
 * @author Dr (dr@der.kim)
 */
object DataPermissionStatus {
    enum class RelayStatus {
        /** 链接初始化 */
        InitialConnection,

        /** 获取链接UUID-Hex */
        GetPlayerInfo,

        /** 等待认证(Pow) */
        WaitCertified,

        /** 认证(Pow)结束 */
        CertifiedEnd,

        /** 向对应房主注册, 但还未进行游戏注册 */
        PlayerPermission,

        /** 向对应房主完成注册, 且游戏完成注册 */
        PlayerJoinPermission,

        /** 该链接为房间 HOST */
        HostPermission,
        Debug;
    }

    enum class ServerStatus {
        InitialConnection,
        CertifiedEnd;
    }

    enum class DevConnectStatus {
        InitialConnection,
        WaitCertified,
        CertifiedEnd,
        Master;
    }
}