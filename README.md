# CNKD NEXT

面向 Rusted Warfare `Headless start` 模式的单房间服务端发行项目。

CNKD NEXT 基于 RW-HPS 能力持续维护，重点解决独立房间部署中的配置可理解性、原版大厅链路兼容、模组地图加载、长期运维和低延迟诊断问题。项目适合个人服主、战队活动服、公开列表服、团战服、FFA 混战服、生存地图服和固定地图活动服。

本仓库包含服务端源码、部署文档和插件目录。首次安装请选用完整 ZIP，并以附件中的配置、启动脚本和 SDK 为准。 [GitHub Releases](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases)

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
| 上游来源与许可证 | [RW-HPS](https://github.com/deng-rui/RW-HPS) |
| 官方帮助 | QQ 群 `910085695` |

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

本项目基于 RW-HPS 相关能力整理与扩展。“免授权”指服务端启动无需填写授权码。分发和修改时请保留必要署名，并遵守对应源码和组件的许可要求。 [上游 RW-HPS](https://github.com/deng-rui/RW-HPS)

本项目面向合法、合规的服务器部署与开发测试。文档中的低延迟、模组兼容和资源占用说明是功能边界与诊断口径，不替代在目标地图、模组、人数和网络环境下的实际验收。
