/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.game.enums

import net.rwhps.server.util.EnumUtils

/**
 * @date 2023/8/19 12:06
 * @author Dr (dr@der.kim)
 */
enum class GameInternalUnits {
    extractor,
    landFactory,
    airFactory,
    seaFactory,
    commandCenter,
    turret,
    antiAirTurret,
    builder,
    tank,
    hoverTank,
    artillery,
    helicopter,
    airShip,
    gunShip,
    missileShip,
    gunBoat,
    megaTank,
    laserTank,
    hovercraft,
    ladybug,
    battleShip,
    tankDestroyer,
    heavyTank,
    heavyHoverTank,
    laserDefence,
    dropship,
    tree,
    repairbay,
    NukeLaucher,
    AntiNukeLaucher,
    mammothTank,
    experimentalTank,
    experimentalLandFactory,
    crystalResource,
    wall_v,
    fabricator,
    attackSubmarine,
    builderShip,
    amphibiousJet,
    supplyDepot,
    experimentalHoverTank,
    turret_artillery,
    turret_flamethrower,
    fogRevealer,
    spreadingFire,
    antiAirTurretT2,
    turretT2,
    turretT3,
    damagingBorder,
    zoneMarker,
    editorOrBuilder,
    //modularSpider,
    UNKNOWN;

    companion object {
        fun from(name: String): GameInternalUnits? = EnumUtils.from(entries, name)

        fun from(index: Int): GameInternalUnits = EnumUtils.from(entries, index, UNKNOWN)
    }
}