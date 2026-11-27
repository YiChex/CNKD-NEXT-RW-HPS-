/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.io.packet

import net.rwhps.server.data.unit.ActionIdData
import net.rwhps.server.game.enums.GameUnitActions
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.io.GameInputStream
import net.rwhps.server.io.packet.block.GameCommandBlockOperationUnitPacket
import net.rwhps.server.struct.list.Seq

/**
 *
 *
 * @date 2024/1/28 10:29
 * @author Dr (dr@der.kim)
 */
class GameCommandOnePacket(bytes: ByteArray) {
    val sendBy: PlayerHess?
    var operationUnit: GameCommandBlockOperationUnitPacket? = null
    val N_Boolean_1: Boolean
    val N_Boolean_2: Boolean
    val N_Int_1: Int
    val unitAction: GameUnitActions?
    var rallyPoint: FloatArray? = null
    val N_removeUnit: Boolean
    val opsUnitList: Seq<Long> = Seq()
    var N_player: PlayerHess? = null
    var mapPoint: FloatArray? = null
    val actionIdData: ActionIdData
    val N_Boolean_3: Boolean
    val sharedControlCheck: Short
    val N_Boolean_4: Boolean
    val stepRate: Float
    val N_Float_1: Float
    val systemAction: Int


    init {
        GameInputStream(bytes).use {
            sendBy = if (HeadlessModuleManage.initHPS()) {
                HeadlessModuleManage.hps.room.playerManage.getPlayer(it.readByte())!!
            } else {
                it.readByte()
                null
            }
            // 建造移动等
            if (it.readBoolean()) {
                operationUnit = GameCommandBlockOperationUnitPacket(it)
            }
            N_Boolean_1 = it.readBoolean()
            N_Boolean_2 = it.readBoolean()
            N_Int_1 = it.readInt()
            unitAction = it.readEnum(GameUnitActions::class.java)
            if (it.readBoolean()) {
                rallyPoint = floatArrayOf(it.readFloat(), it.readFloat())
            }
            N_removeUnit = it.readBoolean()
            val opsQty = it.readInt()
            for (i in 0 until opsQty) {
                opsUnitList.add(it.readLong())
            }
            if (it.readBoolean()) {
                it.readByte()
                N_player = null//playerManage.getPlayer(it.readByte())!!
            }
            if (it.readBoolean()) {
                mapPoint = floatArrayOf(it.readFloat(), it.readFloat())
            }
            // 一个 String, 可能是单位名称, 目前无法实现 [C(I)]
            it.skip(8)
            actionIdData = ActionIdData.getAction(it.readString())
            N_Boolean_3 = it.readBoolean()
            sharedControlCheck = it.readShort()
            N_Boolean_4 = it.readBoolean()
            if (N_Boolean_4) {
                it.readByte()
                stepRate = it.readFloat()
                N_Float_1 = it.readFloat()
                systemAction = it.readInt()
            } else {
                stepRate = 0F
                N_Float_1 = 0F
                systemAction = 0
            }
            // 以后为单位数据, 意义不大
         }
    }

    override fun toString(): String {
        return "GameCommandOnePacket(sendBy=$sendBy, operationUnit=$operationUnit, N_Boolean_1=$N_Boolean_1, N_Boolean_2=$N_Boolean_2, N_Int_1=$N_Int_1, unitAction=$unitAction, rallyPoint=${rallyPoint?.contentToString()}, N_removeUnit=$N_removeUnit, opsUnitList=$opsUnitList, N_player=$N_player, mapPoint=${mapPoint?.contentToString()}, actionIdData=$actionIdData, N_Boolean_3=$N_Boolean_3, sharedControlCheck=$sharedControlCheck, N_Boolean_4=$N_Boolean_4, stepRate=$stepRate, N_Float_1=$N_Float_1, systemAction=$systemAction)"
    }
}