# CNKD NEXT

**简体中文** · [English (US)](docs/i18n/README.en-US.md) · [English (UK)](docs/i18n/README.en-GB.md) · [Français](docs/i18n/README.fr.md) · [Русский](docs/i18n/README.ru.md)

**基于 RW-HPS 的铁锈战争单房间服务端** · [GNU AGPL v3](LICENSE)

[下载服务端](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/latest) · [部署指南](docs/DEPLOYMENT-GUIDE-CN.md) · [插件目录](docs/PLUGINS-CN.md) · [最小插件示例](https://github.com/YiChex/cnkd-next-plugin-examples) · [服务端源码](server/README.md)

## 写给铁锈战争社区

首先感谢 **RW-HPS 原作者 Dr.（deng-rui）及上游贡献者**。无论上游后来如何调整开源与维护方式，CNKD NEXT 都站在巨人的肩膀之上。 [RW-HPS](https://github.com/RW-HPS/RW-HPS)

从 2026 年 2 月开始，CNKD 团队一直在 RW-HPS 的基础上维护这个项目。经过审慎评估，我们认为单房间服务端已经足够成熟，可以交给更多人一起维护，团队也将探索更多项目。我们始终相信社区的无限可能。

欢迎任何人提交 Pull Request。改动经审核后即可合并，团队也可根据持续贡献授予相应的维护权限。随着贡献者增多，我们会考虑成立 GitHub 组织，让项目逐步交由社区共同维护。团队也计划尽快开源下一代基于 CNKD NEXT 的排位服务系统，作为铁锈战争可扩展的服务端中枢。 [提交贡献](CONTRIBUTING.md)

铁锈战争战队圈是项目一路迭代的最大动力。感谢玩家 **灰太狼**，CNKD 团队最早的支持者，也感谢 **七安战队**，最早使用这套服务端的团队。向每一个试用、反馈和帮助改进服务端的社区团队致意。也真诚感谢 CNKD 云实例平台的用户和生态合作团队。千言万语，感谢有你。

其他战略合作方 [OpenAI Group PBC](https://openai.com/) · [杭州深度求索人工智能基础技术研究有限公司](https://www.deepseek.com/)

> 此去山高水长，你我热爱皆有归途
>
> 愿星火仍照后来人

**CNKD 薯条 🍟** · [加入 QQ 交流群 910085695](https://qm.qq.com/q/YHtlMJwzUm)

<p align="center">
  <a href="docs/assets/CNKD-LOGO.png"><img src="docs/assets/CNKD-LOGO.png" width="360" alt="CNKD 标志"></a>
</p>

## 从这里找到插件

| 插件 | 用途 | 源码与下载 |
| --- | --- | --- |
| 最小插件示例 | 用一个 `.hello` 指令学会构建、安装和审批，另附 Java/Kotlin 模板 | [源码](https://github.com/YiChex/cnkd-next-plugin-examples) · [下载](https://github.com/YiChex/cnkd-next-plugin-examples/releases/latest) |
| EIRS | 玩家身份上报与封禁同步，标准服务端包已预装现代适配器 | [源码](https://github.com/YiChex/cnkd-plugin-identity-reporter) · [下载](https://github.com/YiChex/cnkd-plugin-identity-reporter/releases/latest) |
| 战报与数据采集 | 对局结算数据采集，提供独立报告服务 | [源码](https://github.com/YiChex/cnkd-match-analytics) · [下载](https://github.com/YiChex/cnkd-match-analytics/releases/latest) |
| 称号与颜色 | 战役室昵称、称号、名单与聊天配色，需要同版 Release 的外观宿主 | [源码](https://github.com/YiChex/cnkd-next-single-room-titles) · [下载](https://github.com/YiChex/cnkd-next-single-room-titles/releases/latest) |

安装前请查看插件要求的宿主能力和批准锁更新方式。 [插件兼容与安装](docs/PLUGINS-CN.md)

## CNKD 铁锈战争生态

- 快速试用服务端能力，并用可视化面板管理配置。 [CNKD 云实例服务平台](https://cloud.cnkd.fun/)
- 查看铁锈战争赛事和战队社区活动。 [CNKD 铁锈战争赛事系统](https://esports.cnkd.fun/)
- 浏览地图。资源库会持续更新。 [CNKD 铁锈战争资源站](https://rw.cnkd.fun/maps)

## 当前发布

**当前发布版 `2.3.2`** 使用服务端 `1.1.24`，含手动 Start 默认 5 秒倒计时、普通玩家进出 S 队修复及 EIRS。下载 Assets 中的 `CNKD.NEXT.2.3.2.zip` 并解压到独立实例目录。 [下载 2.3.2](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/tag/2.3.2) · [核对版本与 SHA-256](docs/RELEASE-VERSIONS-CN.md)

## 快速入口

| 目标 | 文档 |
|:---|:---|
| 第一次部署、升级、端口和故障处理 | [单房间部署指南](docs/DEPLOYMENT-GUIDE-CN.md) |
| 判断 ZIP 与 JAR 对应版本 | [发布版本与附件核对](docs/RELEASE-VERSIONS-CN.md) |
| 查看控制台和游戏内指令 | [指令参考](docs/COMMAND-REFERENCE-CN.md) |
| 修改完整配置、权限和低延迟参数 | [配置参考](docs/CONFIGURATION-REFERENCE-CN.md) |
| 管理面板、RCON 和监控接入 | [接口运维指南](docs/API-OPERATIONS-CN.md) |
| 插件开发与兼容规则 | [插件开发指南](docs/PLUGIN-DEVELOPMENT-GUIDE-CN.md) |
| 服务端源码、API 与构建工具 | [源码构建](server/BUILDING-CN.md) |
| 最小示例、EIRS、战报与称号 | [插件目录](docs/PLUGINS-CN.md) |
| 上游来源与许可证 | [RW-HPS](https://github.com/RW-HPS/RW-HPS) · [AGPL v3 正文](LICENSE) |
| 官方帮助 | [加入 QQ 交流群 910085695](https://qm.qq.com/q/YHtlMJwzUm) |

## 当前定位

| 项目 | 2.3.2 附件默认配置 |
|:---|:---|
| 运行形态 | 纯 `start` 单房间，无需 Relay 才能运行 |
| Java | Java 17 或更高版本 |
| 默认游戏端口 | `5123` |
| 默认人数上限 | `100` |
| 默认单位上限 | `1000` |
| 添加 AI | `gameplay.enableAI=false`，按玩法显式开启 |
| 自动开局 | 关闭，`autoStartMinPlayerSize=-1` |
| 手动开局 | 默认倒计时 5 秒，可通过 `gameplay.manualStartCountdownSeconds` 修改 |
| 普通玩家调位 | 可在战役室调整自己的位置和队伍，比赛开始或启用相应大厅锁后受限 |
| 自动返回战役室 | 关闭，保持原版结束/重置边界清晰 |
| 全单位死亡自动移除 | 关闭，保持原版玩家存留行为 |
| 模组地图发现与自动提取 | 开启 |
| 单房间低延迟快路径 | 开启，诊断采样默认关闭 |
| 公共列表 | 默认关闭 `common.autoUpList=false` |
| 远程管理 | Web 与 RCON 的独立监听端口默认均为 `0` |
| 插件 | 随包提供 EIRS `identity.reporter` 1.1.0 与批准锁。SDK 为 1.3.0 |

## 主要能力

### 原版大厅与对局链路

- 房主按钮和有权限玩家的 `.start` 进入手动倒计时。后台用 `gamestart status` 查询开局进度，`start` 用于启动服务端。自动开局按人数门槛和自己的倒计时运行。
- 观战玩家在有空闲参战槽位时可以返回普通位置。地图锁只限制换图，位置锁和队伍锁各自配置。
- 尊重房主在原版大厅中对核弹、资金、收入、迷雾和初始单位的选择；服务端配置作为新房间默认值。
- 保留原版投降按钮链路。团队模式可显示投降申请进度，`.surrenderstatus` 可查询当前进度；单人投降模式可配置开启。
- `.battleroom` 使用原版战役室返回路径；自动返回默认关闭，避免与服务器强制重置提示混淆。
- 管理员可使用 `.t2`、`.t3`、`.tffa`、`.tv` 快速刷新队伍和位置；观战槽位会被保留。

### 模组与地图

- 自动扫描 `data/mods` 下的 `.rwmod` 和 `.zip` 模组包。
- 识别模组包 `maps` 路径下的 `.tmx` 地图并加入 `.maps` 列表。
- 启动扫描时可自动提取地图、`_map.png`、`.tsx`、图片、音频和其他地图支撑文件。
- 运行地图使用带稳定身份的 `data/maps/cnkd_mod_maps/` 缓存目录，地图名本身不被哈希前缀改写。
- 模组被删除或替换后，可按保护时间回收孤儿运行目录；当前房间正在使用的地图不会被回收。
- 服务端能准备地图不等于客户端拥有地图。玩家要在官方客户端中看到并进入模组地图，仍需安装相同模组。

### 房间权限与长期运营

- 房主、管理员、普通玩家权限分层配置，后台超级管理员可按配置绕过普通房间权限限制。
- 支持 UUID、IP、IP 段封禁和持久管理员；运行中的权限变化可写回 `PersistentAccessConfig.json`。
- 支持后台健康、配置、权限、插件日志、地图缓存和低延迟诊断命令。
- 默认控制台输出分级并支持按类别降噪；V2 插件日志有独立的控制台/文件级别、限流、截断和轮转配置。

### 单房间低延迟诊断

低延迟快路径可按单房间实测调整。重点看指令延迟、队列和客户端操作是否正常：

- 游戏操作包快速路径和 Tick 前队列 drain。
- Hess 出站命令提前量、Headless FPS、Netty worker 和同 eventLoop 直接写出参数。
- `lowlatency readout/summary/experience/status/advise` 诊断输出。
- `startmode pretest/bootcheck/diagnose` 开局、地图、重连和赛后状态检查。
- 稳健、推荐、激进、低内存和生产指标档位的运行期切换。

建议先用默认或 `recommended` 档完成真实联机测试；出现回弹、掉指令、桥接失败或无法操作时，先切换 `stable`，不要直接把问题归因于客户端延迟数字。

### 公共列表与广告跳转

允许 UPLIST 的免授权包支持：

- `common.autoUpList` 开关。
- 服务器名/地图名置顶字符数量。
- 实验展示字段和推广展示字段。
- 多条公共列表入口和广告服跳转配置。

公共列表默认关闭。禁 UPLIST 包即使修改配置也不会上传列表；允许 UPLIST 包也应先确认公网端口、安全组和防火墙，再开启 `common.autoUpList`。

## 最小部署流程

1. 下载 Releases 中的扁平 ZIP。解压后在同一目录看到 `Server-All.jar`、`data/` 和启动脚本。 [下载入口](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/latest)
2. 解压到独立目录，不要把多个实例混用同一个 `data` 目录。
3. 编辑 `data/ServerConfig.json`，至少确认端口、服名、人数和 AI 开关。
4. 安装 Java 17。Linux 在包目录运行 `sh ./start.sh`，Windows 运行 `.\start-windows.bat`。
5. 启动后先执行 `startmode bootcheck` 和 `config check`。
6. 添加模组后执行 `mods`、`maps 1`，确认模组和地图已被发现。
7. 需要开放列表时，再确认包类型、端口公网可达和 `common.autoUpList=true`。

已部署的实例可按备份、迁移、启动检查、真实客户端回归的顺序升级。 [单房间部署指南](docs/DEPLOYMENT-GUIDE-CN.md)

## 许可证与使用边界

CNKD NEXT 服务端源码与上游 RW-HPS 同样采用 **GNU Affero General Public License v3（AGPL v3）**。修改、分发和通过网络提供修改版本时，请按许可证条款保留著作权与许可声明，并履行相应的源码提供义务。第三方依赖与游戏资源各自适用其原有许可。 [项目许可证](LICENSE) · [上游许可证](https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE)

本项目面向合法、合规的服务器部署与开发测试。文档中的低延迟、模组兼容和资源占用说明是功能边界与诊断口径，不替代在目标地图、模组、人数和网络环境下的实际验收。
