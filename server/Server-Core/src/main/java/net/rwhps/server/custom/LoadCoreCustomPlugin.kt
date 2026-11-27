/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.custom

import net.rwhps.server.core.RuntimeModeSupport
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.bean.internal.BeanPluginInfo
import net.rwhps.server.data.global.Data
import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.net.api.RelayCustomRoomSupport
import net.rwhps.server.net.api.RelayEntryWelcomeSupport
import net.rwhps.server.net.api.RelayFriendlyShareSupport
import net.rwhps.server.net.api.RelayLicenseSupport
import net.rwhps.server.net.api.RelayNodeRuntimeSupport
import net.rwhps.server.net.api.RelayStatusSupport
import net.rwhps.server.net.api.WebGetRelayInfo
import net.rwhps.server.net.api.WebGetRelayRoomsData
import net.rwhps.server.net.api.WebGetRelayStatusData
import net.rwhps.server.net.api.WebPostRelayAdmin
import net.rwhps.server.plugin.beta.ConnectLimit
import net.rwhps.server.plugin.beta.UpListMain
import net.rwhps.server.plugin.beta.bind.PlayerBindMain
import net.rwhps.server.plugin.internal.headless.HessMain
import net.rwhps.server.util.file.plugin.PluginManage
import net.rwhps.server.util.log.Log
import java.util.concurrent.TimeUnit

/**
 * Internal plugin bootstrap.
 */
internal class LoadCoreCustomPlugin {
    private val core = "[Core Plugin]"
    private val coreEx = "[Core Plugin Extend]"

    init {
        val pureStartMode = RuntimeModeSupport.isPureStartMode()

        if (!pureStartMode) {
            RelayCustomRoomSupport.init()
            RelayFriendlyShareSupport.ensureInitialized()
            RelayEntryWelcomeSupport.ensureInitialized()

            if (RelayStatusSupport.statusPageEnabled()) {
                Data.webData.addWebGetInstance(RelayStatusSupport.statusPagePath(), WebGetRelayInfo())
            }
            if (RelayStatusSupport.statusApiEnabled()) {
                Data.webData.addWebGetInstance(RelayStatusSupport.statusApiPath(), WebGetRelayStatusData())
            }
            if (Data.configRelayApi.enableRoomsApi) {
                Data.webData.addWebGetInstance(Data.configRelayApi.roomsApiPath, WebGetRelayRoomsData())
            }
            if (Data.configRelayApi.enableAdminApi) {
                Data.webData.addWebPostInstance(Data.configRelayApi.adminApiPath, WebPostRelayAdmin())
            }
            if (Data.configRelay.decision.enableRelayRoomRecycle) {
                val interval = Data.configRelay.decision.relayRoomRecycleCheckSeconds.coerceAtLeast(15)
                Threads.newTimedTask(CallTimeTask.RelayRoom_CheckTimeTask, interval, interval, TimeUnit.SECONDS) {
                    RelayRoom.cleanRoom(Data.configRelay.decision.relayRoomInactiveTimeoutSeconds)
                }
            }
            Threads.newTimedTask(CallTimeTask.RelayCustomRoomHotReload, 5, 5, TimeUnit.SECONDS) {
                RelayCustomRoomSupport.reloadIfNeeded()
                RelayCustomRoomSupport.maintainRelayServerModeRooms()
            }
            Threads.newTimedTask(CallTimeTask.RelayEntryWelcomeHotReload, 5, 5, TimeUnit.SECONDS) {
                RelayEntryWelcomeSupport.reloadIfNeeded()
            }
            if (Data.configRelay.mainServer && Data.configRelay.decision.enableNodeStatusAutoRefresh) {
                RelayNodeRuntimeSupport.refreshAll(force = true)
                val interval = Data.configRelay.decision.nodeStatusRefreshSeconds.coerceAtLeast(5)
                Threads.newTimedTask(CallTimeTask.RelayNodeStatusRefresh, interval, interval, TimeUnit.SECONDS) {
                    RelayNodeRuntimeSupport.refreshAll()
                }
            }
        }

        if (!pureStartMode || Data.config.autoUpList || Data.configRelayLicense.licenseKey.isNotBlank()) {
            Threads.newTimedTask(CallTimeTask.RelayLicenseRefresh, 30, 30, TimeUnit.MINUTES) {
                RelayLicenseSupport.refreshIfDue()
            }
        }

        PluginManage.addPluginClass(
            returnTemplate("Headless Rusted Warfare", "$core Headless Rusted Warfare", internalName = "HessServer"),
            HessMain(),
            mkdir = false,
            skip = true
        )

        PluginManage.addPluginClass(
            // D-17: the third positional argument of returnTemplate() is `internalName`,
            // so `"1.0.0"` used to become the plugin id (shown as `id=1.0.0` in the
            // `plugins` command) instead of the version it was meant to be.
            returnTemplate("UpList", "$coreEx UpList", version = "1.0.0"),
            UpListMain(),
            mkdir = false,
            skip = true
        )
        PluginManage.addPluginClass(
            returnTemplate("ConnectLimit", "$coreEx ConnectLimit"),
            ConnectLimit(),
            mkdir = false,
            skip = true
        )
        PluginManage.addPluginClass(
            returnTemplate("BindPlayer", "$coreEx player bind"),
            PlayerBindMain(),
            mkdir = true,
            skip = true
        )
    }

    private fun returnTemplate(
        name: String,
        description: String,
        internalName: String = name,
        version: String = Data.SERVER_CORE_VERSION
    ): BeanPluginInfo {
        return BeanPluginInfo(
            name,
            internalName,
            author = "Dr (dr@der.kim)",
            description = description,
            version = version,
            supportedVersions = "= ${Data.SERVER_API_VERSION}"
        )
    }
}
