# CNKD NEXT 配置参考

主配置文件是 `data/ServerConfig.json`。下表默认值以 2026-11-27 核对的公开 2.3.1 附件为准。旧实例保留用户设定，新增字段的生效取决于实际 JAR。修改前请备份，端口、最大玩家槽位和 Netty worker 等启动期参数需要重启。对于可运行期更新的字段，在没有开局或赛后转换时执行 `reloadconfig`，再用 `config check` 和 `startmode bootcheck` 核对结果。 [发布版本与附件核对](RELEASE-VERSIONS-CN.md)

## 配置优先级

对局设置的通用优先级如下：

1. 原版客户端/房主在当前战役室明确选择。
2. 引擎当前战役室状态。
3. `ServerConfig.json` 中的默认值。

例如 `gameplay.disableNukes` 是新战役室默认值，不应覆盖房主在原版客户端中已经选择的禁核状态。模组地图、投降、返回战役室和权限操作还会受到当前房间状态与 `RoomPermissionConfig.json` 限制。

## 1. `common`

| 字段 | 默认值 | 作用 |
|:---|:---|:---|
| `defStartCommand` | `start` | 启动后默认执行的命令，纯 Start 包保持 `start` |
| `log` | `WARN` | 全局日志阈值；Legacy V1 插件日志受其影响 |
| `port` | `5123` | 游戏 TCP 端口 |
| `serverName` | `CNKD NEXT Single Room` | 房间和列表显示名称 |
| `chatSenderName` | `CNKD` | 游戏消息栏系统消息发送者名称 |
| `subtitle` | 空 | 列表辅助文本 |
| `autoUpList` | `false` | 是否自动上传公共列表 |
| `listStatusSuffixEnabled` | `true` | 是否给列表服名添加状态后缀 |
| `listNamePinnedCharacterCount` | `0` | 服务器名字段置顶字符数量 |
| `listPinnedCharacterCount` | `20` | 地图字段置顶字符数量 |
| `publicListEntries` | `[]` | 多条公共列表入口 |
| `listExperimentEnabled` | `false` | 列表实验字段开关 |
| `listPromotionModeEnabled` | `false` | 推广展示字段开关 |
| `listPromotionGameStatus` | 空 | 推广模式列表状态 |
| `listPromotionPlayerCount` | `-1` | 推广模式展示人数，-1 使用安全默认值 |
| `listPromotionMaxPlayerCount` | `-1` | 推广模式展示上限，-1 使用真实上限 |
| `listPromotionSortBoostEnabled` | `false` | 推广排序增强 |
| `listPromotionSortBoostPrefix` | 空 | 推广排序前缀 |
| `listPromotionBlankMapEnabled` | `false` | 推广模式是否隐藏列表地图字段 |

列表实验和推广字段只改变上传展示，不改变真实房间。新服先保持 `autoUpList=false`，确认公网连接后再开启。

## 2. `network`

| 字段 | 默认值 | 作用 |
|:---|:---|:---|
| `mixProtocolEnable` | `false` | 混合协议开关，纯 Start 保持关闭 |
| `rconMixEnable` | `false` | RCON 混合接口 |
| `webMixEnable` | `false` | Web 混合接口 |
| `rconPort` | `0` | RCON 端口，0 表示关闭 |
| `rconPasswd` | `RW-HPS` | RCON 密码，启用后必须修改 |
| `webPort` | `0` | Web 端口，0 表示关闭 |
| `webToken` | `RW-HPS` | Web Token，启用后必须修改 |
| `webHOST` | 空 | Web 绑定地址 |
| `sslEnable` | `false` | SSL 主链路开关 |
| `sslMixEnable` | `false` | SSL 混合链路开关 |
| `sslPasswd` | `RW-HPS` | SSL 相关密码 |

不使用 RCON/Web 时保持端口为 `0`，减少暴露面。启用接口时同时收紧防火墙和访问来源。

## 3. `gameplay`

| 字段 | 默认值 | 作用 |
|:---|:---|:---|
| `serverID` | `CNKD-NEXT` | 内部实例标识 |
| `enterAd` | `欢迎加入本服务器` | 玩家入房提示 |
| `startAd` | `服务器已就绪！` | 开局提示 |
| `maxPlayerJoinAd` | 见默认文件 | 满员提示 |
| `startPlayerJoinAd` | 见默认文件 | 对局中拒绝进入提示 |
| `passwd` | 空 | 房间密码 |
| `maxPlayer` | `100` | 人数上限，当前最大归一化到 100 |
| `oneAdmin` | `true` | 是否启用普通房主链路 |
| `startMinPlayerSize` | `-1` | 手动开始最低人数，-1 不限制 |
| `autoStartMinPlayerSize` | `-1` | 自动开局人数，-1 关闭 |
| `autoStartCountdownSeconds` | `5` | 自动开局倒计时 |
| `maxGameIngTime` | `7200` | 单局最长时间，秒，-1 不限 |
| `maxOnlyAIGameIngTime` | `3600` | 只剩 AI/无人时兜底时间，秒，-1 不限 |
| `enableAI` | `false` | 是否允许房主/管理员添加 AI |
| `maxMessageLen` | `100` | 普通消息长度限制 |
| `maxUnit` | `1000` | 单位上限，当前归一化范围为 5-5000 |
| `defIncome` | `1.0` | 默认收入倍率 |
| `defaultStartingCreditsAmount` | `4000` | 默认开局资金 |
| `disableNukes` | `false` | 新战役室默认是否禁核 |
| `defaultFogMode` | `lineOfSight` | 默认迷雾：`off`、`basic`、`lineOfSight` |
| `isAfk` | `true` | AFK 管理权转移 |
| `muteAll` | `false` | 是否全体禁言 |
| `turnStoneIntoGold` | `false` | 旧兼容玩法开关，快路径默认不拦截 |
| `modsLoadErrorPrint` | `false` | 是否打印更多模组错误信息 |
| `saveRePlayFile` | `true` | 是否保存回放 |

自动开局须满足 `autoStartMinPlayerSize`，默认人数门槛为 `-1`，即关闭自动开局。新版构建另有 `manualStartCountdownSeconds`，默认 5 秒，支持 0–300 秒，`0` 表示立即开局。当前公开 2.3.1 附件只读取自动开局字段。 [发布版本与附件核对](RELEASE-VERSIONS-CN.md)

`map ping` 不按普通聊天长度拦截；普通消息仍受 `maxMessageLen` 约束。

## 4. `lobby`

| 字段 | 默认值 | 作用 |
|:---|:---|:---|
| `disableTeamChange` | `false` | 是否禁止玩家自行换队 |
| `disablePositionChange` | `false` | 是否禁止玩家自行换位 |
| `adminCanOverrideTeamRules` | `true` | 房主/管理员是否可覆盖换队限制 |
| `adminCanOverridePositionRules` | `true` | 房主/管理员是否可覆盖换位限制 |
| `enableFreeForAllMode` | `false` | 是否启用 FFA 默认布局 |
| `enableAlternateTeamsOnJoin` | `false` | 是否按加入位置交替分队 |
| `enableSurvivalMapMode` | `false` | 是否启用生存图规则 |
| `survivalGameOverGraceSeconds` | `180` | 生存图开局保护时间 |
| `survivalEnemyClearConfirmSeconds` | `45` | 生存图敌人清空确认时间 |
| `enableLobbySyncOptimization` | `true` | 大厅同步优化 |
| `lobbyRefreshHeartbeatMillis` | `30000` | 大厅刷新心跳 |
| `lobbyPacketHeartbeatMillis` | `30000` | 大厅数据包心跳 |
| `latencyHeartbeatMillis` | `1000` | 延迟采样心跳 |
| `enableLobbyGameSyncPacketFilter` | `true` | 游戏同步包合并/过滤 |
| `lobbyGameSyncPacketHeartbeatMillis` | `3000` | 游戏同步包心跳 |
| `lobbyGameSyncPacketJoinGraceMillis` | `180000` | 入房同步宽限时间 |
| `reconnectGraceMillis` | `5000` | 短线重连保留时间 |
| `enableGameOverBattleRoomReturn` | `false` | 是否走原版战役室返回链路 |
| `gameOverReturnCountdownSeconds` | `30` | 返回或重置前等待时间 |
| `pruneDisconnectedPlayersOnGameOver` | `true` | 赛后清理掉线占位 |
| `enableJoinReadinessTips` | `false` | 是否发送一次性入房轻提示 |
| `joinTipModWarningThreshold` | `1` | 模组提示阈值 |
| `enableIndividualSurrender` | `false` | 是否开启单人投降模式 |
| `enableAutoKickPlayerWhenAllUnitsDestroyed` | `false` | 全单位死亡后自动移除真人玩家 |
| `autoKickPlayerWhenAllUnitsDestroyedMessage` | 默认中文提示 | 自动移除时客户端提示文本 |

位置锁、队伍锁和 `.teamlock` 独立于地图选择。新版统一修复普通玩家 S 队进出路径后，位置或队伍锁都可限制相应的自助观战操作，管理员例外分别由两项 `adminCanOverride…Rules` 控制。旧构建在部分客户端入口和退出 S 队时可能表现不同。 [版本对应关系](RELEASE-VERSIONS-CN.md)

自动返回和服务器强制重置是两个不同的结束链路。只有 `enableGameOverBattleRoomReturn=true` 才会发出自动返回战役室提示并调用原版链路；保持 `false` 时使用强制重置链路。

全单位死亡自动移除默认关闭。开启后只针对服务端确认失去全部单位的真人玩家，AI、观战者和断线玩家不会触发；提示文本最长 256 个字符。

## 5. `map`

| 字段 | 默认值 | 作用 |
|:---|:---|:---|
| `enableMapLock` | `false` | 是否锁定地图选择。位置和队伍另由大厅规则管理 |
| `lockMap` | 空 | 锁定地图名，不含 `.tmx` |
| `enableModPackageMapDiscovery` | `true` | 扫描模组包地图 |
| `modPackageMapScanMaxArchiveSizeMb` | `512` | 模组扫描体积上限，0 不限 |
| `enableModPackageMapSupportExtraction` | `true` | 提取地图附属资源 |
| `enableModPackageMapAutoExtraction` | `true` | 启动扫描时准备地图运行文件 |
| `enableModPackageMapRuntimeGc` | `true` | 回收孤儿模组地图缓存 |
| `modPackageMapRuntimeGcGraceHours` | `24` | 回收保护时间 |
| `modPackageMapRuntimeGcMaxDeletePerRun` | `20` | 单次最多回收目录数 |

模组地图运行缓存位于 `data/maps/cnkd_mod_maps/`，由服务端生成。删除模组后先 `mapgc dryrun`，确认后再 `mapgc cleanup`。

## 6. `console`

`console` 用于控制后台普通信息密度，不会吞掉 WARN/ERROR：

| 字段 | 说明 |
|:---|:---|
| `verbosity` | `quiet`、`normal`、`verbose`、`debug` |
| `showPlayerJoinLeave` | 玩家进出日志 |
| `showPlayerChat` | 普通聊天日志 |
| `showLowLatencySummary` | 低延迟周期摘要 |
| `showSurvivalRepair` | 生存/战役室修复提示 |
| `showRankedCompatibility` | 排位兼容层普通提示 |

推荐日常使用 `verbosity=normal`，只在排查问题时临时打开 `verbose` 或 `debug`。

## 7. `pluginLogging`

V2 插件日志使用独立链路，不应只看 `common.log`：

| 字段 | 默认值 | 范围 |
|:---|:---|:---|
| `consoleLevel` | `INFO` | `OFF`、`ERROR`、`WARN`、`INFO` |
| `fileLevel` | `INFO` | `OFF`、`ERROR`、`WARN`、`INFO` |
| `infoLinesPerMinutePerPlugin` | `120` | 10-600 |
| `maxMessageLength` | `2048` | 256-8192 |
| `maxFileSizeMb` | `8` | 1-64 |
| `retainDays` | `7` | 1-30 |

日志文件位于 `data/log/plugins/`。`pluginlog status` 可查看限流、截断、队列和文件写入诊断。Legacy V1 直接调用全局日志 API 时仍受 `common.log` 影响。

## 8. `singleRoomLowLatency`

该段只面向单房间服务端。它是可测量、可回退的优化路径，不是 Relay 转发，也不保证固定倍数的延迟下降。

| 字段 | 默认值 | 作用 |
|:---|:---|:---|
| `enabled` | `true` | 启用低延迟路径 |
| `metricsEnabled` | `false` | 是否采样诊断指标 |
| `summaryIntervalSeconds` | `30` | 摘要周期 |
| `fastGameCommand` | `true` | 游戏操作快速路径 |
| `preTickGameCommandDrain` | `true` | Tick 前处理操作队列 |
| `preTickGameCommandDrainRequiresStartedRoom` | `true` | 仅开局后启用前置处理 |
| `preTickGameCommandDrainLimit` | `128` | 每次前置处理最多取出的操作包数 |
| `inlineGameCommandProcessing` | `false` | 实验性直处理，默认关闭 |
| `inlineGameCommandRequiresStartedRoom` | `true` | 直处理仅限对局中 |
| `skipPlayerOperationEvents` | `true` | 快路径跳过部分玩家事件 |
| `skipPingActionIntercept` | `true` | 快路径跳过 map ping 拦截 |
| `disableTurnStoneIntoGoldOnFastPath` | `true` | 快路径关闭该拦截 |
| `serverInjectedCommandLeadTicks` | `8` | 服务端注入提前量 |
| `hessOutboundCommandLeadTicks` | `8` | Hess 出站提前量 |
| `hessFrameInvariantGuardEnabled` | `true` | 启用引擎帧边界检查 |
| `headlessFpsOverride` | `60` | 0 表示沿用引擎帧率 |
| `nettyWorkerThreadsOverride` | `1` | 0 表示沿用默认线程数 |
| `directEventLoopWrites` | `true` | 同 eventLoop 时直接写出 |

运行期切换不会写回配置：

```text
lowlatency profiles
lowlatency apply stable
lowlatency apply recommended
lowlatency apply aggressive
lowlatency apply lowMemory
lowlatency apply production
lowlatency apply test
lowlatency apply config
lowlatency apply off
```

`nettyWorkerThreadsOverride` 是启动期参数，修改后必须重启。建议实测顺序为 `pretest -> bootcheck -> apply test -> reset -> 实际对局 -> readout/summary/advise`。出现回弹、掉指令、bridge failed 或无法控制时先切 `stable`。

## 9. `RoomPermissionConfig.json`

权限分为 `host`、`admin`、`player` 和 `moderation`：

- `host`：房主可开始、换图、踢人、加 AI、改资金/收入/迷雾、改队伍、转移房主、暂停和使用调试操作。
- `admin`：后台授予的管理员，默认能力接近房主，可逐项收紧。
- `player`：普通玩家可查看状态、地图、ID、AFK 接管和发起结束投票；管理型开局设置默认关闭。
- 普通玩家自身调位与换队还受大厅锁、玩法和房间状态约束。涉及他人的权限需要单独审查 `allowMovePlayers` 与 `allowChangeTeam`。保留同一构建的配置模板，尤其在升级 S 队修复时回归普通玩家进入和离开观战。
- `moderation.superAdminBypass`：超级管理员是否绕过具体房间权限限制。
- `moderation.allowAutoAdminAssignment`：是否允许首位真人自动成为房主。
- `moderation.allowAutoAdminTransferOnLeave`：房主离开时是否自动转移。

关闭 `gameplay.oneAdmin` 时，客户端房主操作项会移除，普通玩家不能只靠手动命令伪造房主权限；后台配置的超级管理员不受普通房主限制，但仍受最终动作权限控制。

## 10. `PersistentAccessConfig.json`

该文件保存持久封禁和管理员身份。管理员条目按 UUID 匹配，`name` 只是备注：

```json
{
  "admins": [
    {
      "uuid": "玩家 UUID",
      "name": "管理员备注",
      "role": "host",
      "superAdmin": true
    }
  ]
}
```

玩家可在游戏内输入 `.myid` 获取匹配所需 ID。运行中的 `ban`、`clearbanall`、管理员授予/移除等变更默认会写回该文件。

## 11. 插件独立配置

公开附件预装 `identity.reporter` 1.1.0。实例首次启动后检查 `data/plugins/data/identity.reporter/config.json`。上传、自动注册、封禁同步及应用到引擎的设置分别由 `uploadEnabled`、`autoRegister`、`banSyncEnabled`、`banApplyToEngine` 控制。插件私有配置与身份随实例备份，插件 JAR 与对应批准锁一起更新。 [EIRS 启动与排查](DEPLOYMENT-GUIDE-CN.md#eirs-是否已启用)
