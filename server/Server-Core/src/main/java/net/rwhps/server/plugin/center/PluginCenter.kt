/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.center

import net.rwhps.server.data.global.Data
import net.rwhps.server.func.StrCons
import net.rwhps.server.net.manage.DownloadManage
import net.rwhps.server.plugin.GetVersion
import net.rwhps.server.plugin.PluginRuntimeSettings
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.annotations.NeedToRefactor
import net.rwhps.server.util.file.FileUtils.Companion.getFolder
import net.rwhps.server.util.file.json.Json
import net.rwhps.server.util.game.command.CommandHandler

/**
 * 插件中心, 好像没什么用
 *
 * @author Dr (dr@der.kim)
 */
@NeedToRefactor
class PluginCenter {
    private val pluginCommand = CommandHandler("")
    private var pluginCenterData: PluginCenterData
    private val url: String = Data.urlData.readString("Get.Plugin.Core")


    fun command(str: String?, log: StrCons) {
        val response = pluginCommand.handleMessage(str, log)
        if (response.type != CommandHandler.ResponseType.valid) {
            val text: String = when (response.type) {
                CommandHandler.ResponseType.manyArguments -> {
                    "Too many arguments. Usage: " + response.command.text + " " + response.command.paramText
                }
                CommandHandler.ResponseType.fewArguments -> {
                    "Too few arguments. Usage: " + response.command.text + " " + response.command.paramText
                }
                else -> {
                    "Unknown command. use [plugin help]"
                }
            }
            log(text)
        }
    }

    private fun register() {
        pluginCommand.register("help", "") { _: Array<String?>?, log: StrCons ->
            log("plugin list  查看插件列表")
            log("plugin updatalist  更新插件列表")
            log("plugin install PluginID  安装指定id的插件")
        }
        pluginCommand.register("updatelist", "") { _: Array<String?>?, log: StrCons ->
            pluginCenterData = PluginCenterData(url + "PluginData")
            log("更新插件列表完成")
        }
        pluginCommand.register("list", "") { _: Array<String?>?, log: StrCons ->
            log(pluginCenterData.pluginData)
        }
        pluginCommand.register("install", "<PluginID>", "") { arg: Array<String>, log: StrCons ->
            val json = pluginCenterData.getJson(arg[0].toInt())
            if (!GetVersion(Data.SERVER_API_VERSION).getIfVersion(json.getString("supportedVersions"))) {
                log("Plugin version is not compatible Plugin name is: {0}", json.getString("name"))
            } else {
                DownloadManage.addDownloadTask(
                    DownloadManage.DownloadData(
                        url + json.getString("name") + ".jar",
                        PluginRuntimeSettings.pluginDirectoryUtils(true).toFile(json.getString("name") + ".jar")
                    )
                )
                log("Installation is complete, please restart the server")
            }
        }
    }

    private class PluginCenterData(url: String) {
        val pluginCenterData: Seq<Json>

        val pluginData: String
            get() {
                val stringBuilder = StringBuilder()
                var json: Json
                for (i in 0 until pluginCenterData.size) {
                    json = pluginCenterData[i]
                    stringBuilder.append("ID: ").append(i).append("  ").append("Name: ").append(json.getString("name")).append("  ")
                        .append("Description: ").append(json.getString("description")).append(Data.LINE_SEPARATOR)
                }
                return stringBuilder.toString()
            }

        fun getJson(i: Int): Json {
            return pluginCenterData[i]
        }

        init {
            pluginCenterData = Json(Data.core.http.doGet(url)).getArraySeqData("result")
        }
    }

    companion object {
        val pluginCenter = PluginCenter()
    }

    init {
        pluginCenterData = PluginCenterData(url + "PluginData")
        register()
    }
}
