/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.api

import net.rwhps.server.game.room.RelayRoom
import net.rwhps.server.core.thread.CallTimeTask
import net.rwhps.server.core.thread.Threads
import net.rwhps.server.data.global.Data
import net.rwhps.server.net.core.web.WebGet
import net.rwhps.server.net.http.AcceptWeb
import net.rwhps.server.net.http.SendWeb
import net.rwhps.server.util.SystemUtils
import net.rwhps.server.util.Time
import net.rwhps.server.util.annotations.mark.PrivateMark
import java.util.concurrent.TimeUnit

/**
 * @author Dr (dr@der.kim)
 */
@PrivateMark
class WebGetRelayInfo: WebGet() {

    @Volatile
    var relayInfo: String = "NOT DATA"

    init {
        Threads.newTimedTask(CallTimeTask.ServerStatusUpdate, 10, 30, TimeUnit.SECONDS) {
            try {
                val status = RelayStatusSupport.buildStatus()
                val warningsHtml = if (status.warnings.isEmpty()) {
                    "<p>无</p>"
                } else {
                    status.warnings.joinToString(
                        separator = "",
                        prefix = "<ul>",
                        postfix = "</ul>"
                    ) { "<li>$it</li>" }
                }
                val auditTime = status.textAudit.lastTimestamp.takeIf { it > 0L }?.let { Time.format(it, 1) } ?: "未触发"

                relayInfo = """
                            <!--
                              ~ Copyright 2020-2024 RW-HPS Team and contributors.
                              ~
                              ~ 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
                              ~ Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
                              ~
                              ~ https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
                              -->
            
                            <!DOCTYPE html>
                            <html lang="zh-cn">
                            <head>
                                <meta charset="UTF-8">
                                <title>${RelayDeploymentSupport.displayName()}运行状态</title>
                            </head>
                            <body>
                                
                                <h1> ${RelayDeploymentSupport.displayName()} </h1>
                                <p> ${RelayDeploymentSupport.operatorName()}运营 · ${RelayDeploymentSupport.listOpenText()} · QQ群 ${RelayDeploymentSupport.qqGroup()} </p>
                                <h2> 节点状态 </h2>
                                <p> 房间总个数 : ${status.roomAllSize}  </p>
                                <p> (未开始)游戏房间总个数 : ${status.roomNoStartSize}  </p>
                                <p> (未开始)在列表房间总个数 : ${status.roomPublicSize}  </p>
                                <p> (未开始)不在列表房间总个数 : ${status.roomPrivateSize}  </p>
                                <p> 目前在线人数 : ${status.onlineSize}  </p>
                                <p> 服务器版本 : ${status.serverVersion}  </p>
                                <br/>
                                <p> Java堆大小(MB) : ${status.javaHeapMb}  </p>
                                <p> Java总内存(MB) : ${status.javaTotalMemoryMb}  </p>
                                <p> Java可用内存(MB) : ${status.javaFreeMemoryMb}  </p>
                                <p> Java供应商 : ${status.javaVendor}  </p>
                                <p> Java版本 : ${status.javaVersion}  </p>
                                <p> 系统 : ${status.osName}  </p>
                                <p> 数据最后更新 : ${status.updateTime}</p>
                                <br/>
                                <h2>运行告警</h2>
                                $warningsHtml
                                <br/>
                                <h2>文本审核</h2>
                                <p> 启用状态 : ${status.textAudit.enabled} </p>
                                <p> 提供方 : ${status.textAudit.providerName} </p>
                                <p> 接口已配置 : ${status.textAudit.endpointConfigured} </p>
                                <p> 失败兜底放行 : ${status.textAudit.allowOnError} </p>
                                <p> 审核拒绝即阻止公开 : ${status.textAudit.denyPublicRoomWhenRejected} </p>
                                <p> 最近结果 : ${status.textAudit.lastReason} </p>
                                <p> 最近说明 : ${status.textAudit.lastMessage} </p>
                                <p> 最近触发时间 : $auditTime </p>
                                <br/>
                                <br/>
                                <h2>公开房间列表</h2>
                                ${RelayStatusSupport.renderPublicRoomsHtml()}
                                <br/>
                                
                                <br/>
                                
                                节点信息:
                                <blockquote>
                                    <p dir="auto">地区: ${RelayDeploymentSupport.region()}<br>
                                        运营: ${RelayDeploymentSupport.operatorName()}<br>
                                        列表开放: ${RelayDeploymentSupport.listOpenText()}<br>
                                        联机QQ群: ${RelayDeploymentSupport.qqGroup()}
                                        <br>
                                        Github: <a href="https://github.com/RW-HPS/RW-HPS" rel="nofollow">RW-HPS</a></p>
                                </blockquote>
                            </body>
                            </html>
                        """.trimIndent()
            } catch (e: Exception) {
                relayInfo = """
                            <!DOCTYPE html>
                            <html lang="zh-cn">
                            <head>
                                <meta charset="UTF-8">
                                <title>${RelayDeploymentSupport.displayName()}运行状态</title>
                            </head>
                            <body>
                                <h1>${RelayDeploymentSupport.displayName()}</h1>
                                <h2>状态页生成失败</h2>
                                <p>${e::class.java.simpleName}: ${e.message ?: "无额外异常信息"}</p>
                                <p>请把这段报错反馈给维护者继续排查。</p>
                            </body>
                            </html>
                        """.trimIndent()
            }
        }
    }

    override fun get(accept: AcceptWeb, send: SendWeb) {
        send.setData(relayInfo)
        send.send()
    }
}
