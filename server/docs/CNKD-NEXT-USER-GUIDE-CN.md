# CNKD NEXT 单房间服务端用户手册

> 面向对象：服主、房主、管理员和普通玩家  
> 适用范围：Rusted Warfare 官方客户端 + CNKD NEXT Headless `start` 模式  
> 当前基线：单房间 Start Mode `1.1.6-BattleRoom-Test`

## 目录

- [先确认三件事](#先确认三件事)
- [三分钟启动](#三分钟启动)
- [配置文件](#配置文件)
- [公共列表](#公共列表)
- [地图和模组](#地图和模组)
- [控制台命令](#控制台命令)
- [游戏内命令](#游戏内命令)
- [房主和管理员](#房主和管理员)
- [战役室返回和第二局](#战役室返回和第二局)
- [低延迟设置](#低延迟设置)
- [故障排查](#故障排查)
- [插件](#插件)

## 先确认三件事

1. 使用完整部署包，不要只替换一个 `Server-All.jar`。
2. 玩家客户端安装与服务端相同的模组。服务端能识别模组地图，但不会替客户端安装模组。
3. 控制台命令和游戏内命令是两套入口：控制台直接输入，例如 `players`；游戏内命令必须带点号，例如 `.maps`。

位置号从 `1` 开始，地图编号从 `0` 开始。执行按位置操作前，先执行 `players`。

## 三分钟启动

### Linux

```sh
chmod +x start.sh start-lowmem.sh
./start.sh
```

内存较小的实例可以使用 `./start-lowmem.sh`。自定义内存示例：

```sh
JAVA_OPTS="-Xms256m -Xmx1536m" ./start.sh
```

### Windows

需要 Java 17 或更高版本：

```powershell
java -version
java -jar .\Server-All.jar
```

如果发布包附带 `start-windows.bat`，也可以直接运行该脚本。

### 首次检查

```text
version
players
configcheck
startmode bootcheck
```

确认 `boot.status=ok`、端口与云平台映射一致，并确认 `boot.lowLatency.enabled=true`。默认端口是 `5123`，实际以 `data/ServerConfig.json` 的 `common.port` 为准。

## 配置文件

| 文件 | 作用 |
| --- | --- |
| `data/ServerConfig.json` | 端口、服务器名、人数、AI、地图、低延迟和列表 |
| `data/RoomPermissionConfig.json` | 房主、管理员和普通玩家的操作权限 |
| `data/PersistentAccessConfig.json` | 持久管理员、UUID/IP 封禁和访问策略 |
| `data/RelayLicenseConfig.json` | 免授权和 UPLIST 兼容字段 |

### 高频配置项

| 配置项 | 作用 | 默认值或建议 |
| --- | --- | --- |
| `common.port` | 游戏端口 | `5123` |
| `common.serverName` | 房间和列表名称 | 按需修改 |
| `common.chatSenderName` | 消息栏系统消息名称 | `CNKD` |
| `common.autoUpList` | 自动上传公共列表 | 默认 `false` |
| `common.listPinnedCharacterCount` | 地图名栏置顶字符数 | 按列表模板设置 |
| `gameplay.maxPlayer` | 人数上限 | 默认 `100` |
| `gameplay.maxUnit` | 单名玩家单位上限 | 默认 `1000` |
| `gameplay.enableAI` | 是否允许添加 AI | 默认 `false` |
| `gameplay.startMinPlayerSize` | 手动开局最低人数 | `-1` 表示不限制 |
| `gameplay.autoStartMinPlayerSize` | 自动开局人数 | `-1` 表示关闭 |
| `gameplay.manualStartCountdownSeconds` | 点击 Start / 手动命令后倒计时 | 默认 `5`，`0` 为立即开始，范围 `0-300` |
| `gameplay.autoStartCountdownSeconds` | 满足自动开局人数后倒计时 | 默认 `5`，与手动倒计时独立，不叠加 |
| `gameplay.disableNukes` | 配置文件默认禁核 | 默认 `false` |
| `lobby.enableGameOverBattleRoomReturn` | 游戏结束后自动返回战役室 | 默认 `false` |
| `lobby.gameOverReturnCountdownSeconds` | 返回或重置等待时间 | `5-300` |
| `lobby.pruneDisconnectedPlayersOnGameOver` | 赛后清理断线玩家 | 默认 `true` |
| `map.enableMapLock` | 是否锁图 | 默认 `false` |
| `map.lockMap` | 锁定地图名 | 锁图时填写 |
| `singleRoomLowLatency.enabled` | 单房间低延迟链路 | 默认 `true` |
| `singleRoomLowLatency.headlessFpsOverride` | Headless 帧率 | 默认 `120` |

普通配置可尝试 `reloadconfig`。端口、模组、地图、Netty worker、帧率和启动参数必须完整停止后再次开启。

`reloadconfig` 在稳定大厅执行，先校验主配置、权限和持久访问配置，再应用运行值。任一文件格式错误、权限/访问文件缺失或为空时保留原运行值；对局中或返回同步时拒绝重载。大厅收入倍率、单位上限、初始资金、迷雾和禁核会同步到游戏引擎。修改端口、人数槽位上限或 Netty worker 数量必须重启，此时重载会明确拒绝，避免配置与监听器/玩家表不一致。

### 房主交接与管理员操作

房主或有开局权限的人员点击原版 Start 按钮、输入 `.start` 后，默认全房广播 5 秒倒计时。倒计时期间暂时冻结位置、队伍、地图与大厅设置；重复点击不会重置时间。输入 `.startcancel` 可取消，取消者需要开局权限。发起人断线、失去开局权限，或人数/地图/配置/房间代次不再满足条件时自动取消；到期仍要通过引擎开局确认。

```json
{
  "gameplay": {
    "manualStartCountdownSeconds": 5,
    "autoStartCountdownSeconds": 5
  }
}
```

在稳定大厅执行 `reloadconfig` 可调整该配置；正在倒计时需先取消。旧配置没有此字段时采用 5 秒。控制台 `start` 仍表示启动服务端；开始当前对局使用 `gamestart`，查询/取消分别为 `gamestart status`、`gamestart cancel`。

转移房主、AFK 接管后，旧临时房主的管理权限同时撤回；独立登记的持久管理员和超级管理员保留相应管理权限。控制台 `team <位置> S` 与普通队伍选择使用相同槽位转换，额外观战位置也可以被游戏内管理员踢出。

召唤、核弹、复制等“输入命令后点击地图”操作在默认低延迟下可用；登记后 60 秒有效，切换对局、断线换连接或被撤权后取消。资金调整、暂停、存档操作失败时返回失败信息，不将调度拒绝当作成功。

玩家离线后可定点解封，无需清空全部黑名单：

```text
unban uuid <玩家UUID>
unban ip 203.0.113.45
unban subnet 203.0.113.0/24
```

UUID、IP 与网段黑名单分别存在，解除其中一类不自动删除其他类。`unban <在线玩家位置或名字>` 的旧用法保留，也支持匹配已有封禁键。自动开局和最低开局人数按参战玩家/AI 计算，S 队不凑开局人数；在线统计仍包含观战者。

TCP 准入统一由 NetGuard 管理，默认同 IP 64 个活跃连接、10 秒 128 次新连接，超限仍使用临时防护。原内置 ConnectLimit 的重复计数和自动永久封 `/24` 已取消；人工维护的黑名单保留。现有 JVM 参数 `cnkd.netguard.maxPerIpActive`、`cnkd.netguard.maxPerIpConnectsPerWindow` 可覆盖默认值，需重启。

V2 插件的玩家命令转到插件独立队列执行；慢插件只能占用自己的单线程和 32 个排队位置。控制台命令最多等 2 秒，超时表示完成状态未知，重试前先查插件日志。插件代码如果忽略中断，无法保证强制停止，但不会在网络线程执行。

### 管理 API 的开局与状态查询

管理 API 使用 `network.webToken` 登录取得会话；会话期限 24 小时，改密后原会话不能继续执行 HTTP 或 WebSocket 命令，也不能继续接收控制台日志。需要重新登录。默认管理 Web 端口仍为 0（关闭）。

- `POST /HttpApi/api/post/run/ClientCommand`，JSON `{"runCommand":"start"}`：请求手动倒计时。HTTP 202 / `ACCEPTED` 只表示请求排队，不能当作已开局。
- 同一路径传 `{"runCommand":"startcancel"}`：取消待执行手动倒计时。
- `GET /HttpApi/api/get/info/StartState`：查看手动配置、剩余秒数、开局事务/确认状态和自动开局条件。
- `GET /HttpApi/api/get/info/WorldState`：查看房间代次、生命周期与全快照发送前提；`clientWorldConsistency=unverified` 表示没有声称客户端世界已校验一致。
- `GET /HttpApi/api/get/info/GameInfo`：在游戏线程采集配置与玩家快照；不可用时返回 HTTP 503，不能将其当作零玩家。
- `GET /HttpApi/api/get/server-info`：返回实际单房间与在线人数。

这些路径都需要登录 Cookie。建议通过 POST 登录及受保护的管理网络使用；兼容旧版 GET 登录，但其查询参数可能进入代理访问日志。延迟资金同步和完整快照请求均绑定原对局代次，跨局任务拒绝执行；快照已请求或已发送不等于真实客户端已确认状态一致。

### 玩家自行选位置、换队与 S 队

默认情况下，每名玩家都能在战役室调整**自己**的位置和队伍，也能进入 S 队观战。使用客户端的位置/队伍选择框即可；也可输入 `.self_move 3` 移到 3 号位、`.self_team 2` 选第 2 队、`.self_team S` 进入观战。S 队选择一个数字位置即可重新参战；参战位全满时，需等待空位。

| 服主配置 | 玩家看到的行为 |
| --- | --- |
| `lobby.disablePositionChange=false`、`lobby.disableTeamChange=false`（默认） | 自由调整自身位置、队伍及 S 队 |
| `map.enableMapLock=true` | 仅锁定地图，仍可自行调整位置、队伍及 S 队 |
| `lobby.disablePositionChange=true` | 锁定位置，也限制 S 队进出（观战会分配额外槽位） |
| `lobby.disableTeamChange=true` | 锁定队伍，也限制 S 队进出；仅移动位置且不换队仍可用 |
| `.teamlock on` | 临时锁定位置和队伍；`.teamlock off` 解锁 |
| FFA / 按位置分队 / 生存图 | 参战队伍遵守相应模式，S 队仍可用；生存图的 AI 保留位置不可占用 |

位置锁/队伍锁的管理员例外分别由 `adminCanOverridePositionRules` / `adminCanOverrideTeamRules` 控制。`.teamlock` 允许有效房主或管理员调整。开局、对局中及返回大厅的同步阶段不能调位。

`RoomPermissionConfig.json` 中普通玩家的 `allowMovePlayers`、`allowChangeTeam` 是代为调整**其他玩家**的授权（默认均为 `true`），不能代替上表中的自身位置/队伍锁。旧配置如需限制玩家自调位置，应设置 `lobby.disablePositionChange` 或 `lobby.disableTeamChange`，不要依赖角色授权开关。

## 公共列表

免授权发布包分为允许 UPLIST 和禁止 UPLIST 的构建，以启动日志中的 `[RelayLicense]` 结果和发布包说明为准。

允许 UPLIST 的包可以这样配置：

```json
{
  "common": {
    "autoUpList": true,
    "serverName": "我的单房间服务器",
    "listStatusSuffixEnabled": false,
    "listPinnedCharacterCount": 20
  }
}
```

推荐先保持 `autoUpList=false`，确认端口和对局正常后再开启。禁止 UPLIST 的包即使配置为 `true` 也不会上传列表。

多广告入口会使用各自稳定的列表 ID。服务端会按保守周期持续更新；master server 暂时超时或列表快照格式异常时，会保留当前上传状态并等待重试，不会因为一次探测异常主动删除并重建广告条目。出现列表消失时，先在控制台执行：

```text
uplist status
uplist diagnose
```

重点查看 `snapshot.*` 的 `rows`、`parsed`、`schema`、`complete`，以及每个入口的 `gs1_id`、`gs4_id` 和 `missingConfirmations`。如果 `complete=false` 或 master server 为 `unavailable`，应视为列表探测暂时不可用，不要重复执行 remove。

可视化配置描述文件为 `CNKD-NEXT-PublicList-VisualConfig.json`。它只是配置编辑器的字段描述，真正生效的文件仍是 `data/ServerConfig.json`，不能上传到 `data/maps` 或 `data/mods`。

## 地图和模组

### 普通地图

通过实例控制台的“文件管理”上传到：

```text
data/maps
```

推荐使用 `.tmx`。上传后在没有对局时执行：

```text
reloadmaps
```

### 模组

将 `.rwmod`、模组 `.zip` 或已解压模组目录放入：

```text
data/mods
```

最稳定的安装流程是：终止实例，上传或替换模组，再开启实例。无对局时也可以依次执行：

```text
reloadmods
reloadmaps
```

服务端会自动扫描模组内 `.tmx` 地图，并准备到 `data/maps/cnkd_mod_maps`。这个目录是服务端运行缓存，不要在加载或对局期间手动删除。更换或删除模组后，完整重启或执行 `reloadmaps` 会按安全策略回收失效缓存。

> [!IMPORTANT]
> 玩家客户端必须安装相同模组，否则即使服务端地图识别成功，客户端仍可能无法加载单位、贴图或音效。

### 选择地图

```text
.maps
.maps 2
.map 0
.map def
```

`.map` 只在大厅使用，需要换图权限。启用 `map.enableMapLock=true` 后锁图优先。

## 控制台命令

控制台命令不加点号。

### 玩家和权限

```text
players
admin add <位置>
admin remove <位置>
giveadmin <位置>
```

`players` 用于确认当前槽位。`admin add` 授予持久超级管理员并写入持久访问配置，只能在大厅使用：

```text
players
admin add 1
```

`giveadmin` 只转移普通房主权限，不等同于 `admin add`。

### 地图、模组和缓存

```text
maps
mods
mods status
reloadmaps
reloadmods
mapgc status
mapgc dryrun
mapgc cleanup
```

`mapgc status` 和 `dryrun` 只预览；`cleanup` 只应在大厅执行。对局、返回、恢复、重置或开局确认期间，地图和模组维护命令会被拒绝。

### 诊断和低延迟

```text
version
configcheck
health detail
startmode bootcheck
startmode pretest
startmode diagnose
startmode nocontrol
lowlatency profiles
lowlatency readout
lowlatency summary
lowlatency experience
lowlatency advise
lowlatency reset
lowlatency apply stable
lowlatency apply recommended
lowlatency apply production
```

### 其他命令

| 命令 | 作用 |
| --- | --- |
| `say <内容>` | 全服公告 |
| `whisper <位置> <内容>` | 私聊玩家 |
| `gametime` | 查看对局时长 |
| `gameover` | 请求强制重置房间 |
| `save` | 保存当前对局 |
| `ban <位置>` | 封禁玩家 |
| `unban <位置>` | 解除在线玩家封禁 |
| `mute <位置> [秒]` | 禁言玩家 |
| `kick <位置>` | 大厅踢出玩家 |
| `kill <位置>` | 对局中提交指定玩家的清除操作 |
| `clearmuteall` | 清理全局禁言 |

## 游戏内命令

游戏内命令都以 `.` 开头，实际可用命令受角色和权限配置影响。

```text
.help
.status
.myid
.start
.maps
.map 0
.map def
```

`.help` 会输出当前服务端命令、CNKD NEXT 服务声明和官方帮助群信息：`当前服务端为 CNKD NEXT 官方QQ群组帮助 910085695`。

### 快捷队伍

```text
.t2
.t3
.tffa
.tv
```

| 命令 | 作用 |
| --- | --- |
| `.t2` | 按官方队伍逻辑分为两队并刷新玩家位置 |
| `.t3` | 按官方队伍逻辑分为三队并刷新玩家位置 |
| `.tffa` | 设置混战并刷新玩家位置 |
| `.tv` | 将全员设为观战 |

其他队伍命令：

```text
.teamlayout 2
.teamlayout 3
.teamlayout ffa
.teamlayout spectators
.teamlock on
.teamlock off
.teamshuffle
.ffa
```

### 投降与返回

```text
.surrender
.ff
.forfeit
.surrenderstatus
.battleroom
```

原版投降按钮仍走原版系统动作包。默认团队投降模式显示申请进度，已淘汰成员不要求再次投票；启用 `lobby.enableIndividualSurrender=true` 后按单人模式处理。

`.battleroom` 沿用原版返回链路。返回期间服务端会阻断上一局残留同步和单位操作，清理失效断线槽位，修复玩家队伍，并在两轮轻量大厅刷新完成前阻止再次开局。

## 房主和管理员

权限文件是 `data/RoomPermissionConfig.json`。持久权限使用 UUID，不要只记录昵称；玩家可以使用 `.myid` 查看身份信息。

`admin add` 授予持久超级管理员；`giveadmin` 转移普通房主。`oneAdmin=false` 时普通房主操作入口会被隐藏并由服务端拒绝，后台设置的超级管理员不受该普通房主限制，但仍受超级管理员权限策略控制。

## 战役室返回和第二局

### 自动返回关闭

`lobby.enableGameOverBattleRoomReturn=false` 时，结束提示对应服务器强制重置，不应显示自动返回战役室提示。重置完成后玩家按大厅状态重新开始。

### 自动返回开启

设置为 `true` 时，游戏结束后使用原版战役室返回链路，等待时间由 `lobby.gameOverReturnCountdownSeconds` 控制。

### 连续两局建议

1. 第一局正常开始并操作几十秒。
2. 使用原版结束流程或 `.battleroom` 返回。
3. 等待大厅玩家列表、地图和房主标识稳定。
4. 再次执行 `.start` 或原版开始按钮。
5. 确认玩家有单位并可移动、建造和发送地图标点。

## 低延迟设置

默认低延迟模式已开启，包含游戏命令快速路径、Tick 前队列处理、Hess 命令提前量、120 FPS 和 direct event-loop writes。它不会绕过地图未就绪、返回恢复、重置和玩家生命周期校验。

出现回弹、掉指令或多人操作异常时先执行：

```text
lowlatency apply stable
lowlatency reset
```

`lowlatency apply off` 只用于故障对照，不建议长期使用。长期稳定运行可以使用 `lowlatency apply production`，该模式保留低延迟行为并降低指标采样开销。

## 故障排查

### 玩家进不来

检查 `common.port`、云安全组、系统防火墙、面板映射和启动日志。必须看到游戏端口已开启。

### `.battleroom` 后第二局无单位

等待恢复完成后执行：

```text
startmode diagnose
players
```

不要在恢复期间执行 `reloadmods`、`reloadmaps` 或缓存清理。

### 进入游戏后不能移动或建造

```text
startmode nocontrol
lowlatency readout
lowlatency summary
```

多人同时复现时先切换 `lowlatency apply stable`，再重新开始一局，并保留地图、模组、人数、AI 和日志。

### 模组地图没有出现

确认模组在 `data/mods`，客户端也安装了模组；完整重启后执行 `mods status` 和 `maps`。扫描诊断会显示大型、损坏或不兼容归档的跳过原因。

### 日志占用增长

```text
health detail
maintenance plan
mapgc status
```

核心错误日志按文件大小轮转；实际磁盘占用还应检查回放、插件日志和模组地图缓存。不要在对局中直接删除当前使用的文件。

## 插件

新插件优先使用 V2 API：

- `docs/THIRD_PARTY_PLUGIN_INTEGRATION_CN.md`
- `docs/plugin-sdk/PLUGIN_DEVELOPMENT_GUIDE_CN.md`

安装流程：停止服务端，将插件 JAR 放入 `data/plugins/` 根目录，使用同一发布包的 SDK 校验并更新 `plugin-lock.json`，再完整启动。执行 `plugins` 或 `plugins <id>` 检查状态。

插件必须使用随服务端提供的 API JAR，不要把 `Server-All.jar` 或 `Server-Core` 当作编译依赖，也不要将 API 类重复打进插件包。插件事件是只读异步通知，不能替代原版游戏操作链路。
