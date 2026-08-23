# CNKD NEXT 指令参考

指令最终是否可用由当前运行模式、房间状态和 `RoomPermissionConfig.json` 决定。后台输入不带前缀；游戏内通常使用 `.` 前缀。客户端 `.help` 会强制显示 CNKD NEXT 官方 QQ 群帮助，并只列出当前可见指令。

## 1. 后台控制台指令

### 基础与生命周期

| 指令 | 作用 |
|:---|:---|
| `help` | 查看后台可见指令 |
| `version` | 查看版本、运行模式、地图和在线人数 |
| `start` | 启动服务端网络服务/房间链路 |
| `stop` | 停止网络服务 |
| `exit` | 退出服务端进程 |
| `reloadconfig` | 重新读取配置文件并刷新可热加载设置 |
| `setlanguage <语言>` | 修改服务端语言 |
| `say <文本>` | 向全服广播 |
| `say <文本> -t <标题>` | 带标题广播 |
| `gametime` | 查看当前对局时长 |
| `gameover` | 结束当前对局并进入赛后处理 |
| `save` | 保存当前状态 |

### 玩家和权限

| 指令 | 作用 |
|:---|:---|
| `players` | 查看玩家位置、状态和绑定情况 |
| `admins` | 查看当前管理员 |
| `giveadmin <位置>` | 授予管理员/房主权限，具体角色按权限链路处理 |
| `ban <位置>` | 封禁玩家并写入持久访问配置 |
| `unban <位置>` | 解除玩家封禁 |
| `clearbanall` | 清理持久封禁 |
| `mute <位置> [秒]` | 禁言玩家 |
| `clearmuteall` | 清除全体禁言 |
| `kick <位置> [时间]` | 踢出玩家；只处理有服务端绑定的玩家 |
| `kill <位置>` | 清除玩家单位 |
| `team <位置> <队伍>` | 调整指定玩家队伍，观战队伍按服务端特殊值处理 |
| `addmoney <位置> <金额>` | 增加玩家资源 |

### 模组、地图和缓存

| 指令 | 作用 |
|:---|:---|
| `mods` | 查看已加载模组 |
| `mods status` | 查看模组地图发现、提取、跳过和错误诊断 |
| `maps [页码]` | 查看自定义地图列表 |
| `reloadmods` | 重新加载模组组件，开局中不要执行 |
| `reloadmaps` | 重建地图索引并重新应用锁图 |
| `mapgc status` | 预览运行地图缓存回收结果 |
| `mapgc dryrun` | 只预览，不删除 |
| `mapgc cleanup` | 按保护时间回收孤儿运行目录 |
| `mapgc cleanup-force` | 忽略保护时间回收孤儿运行目录 |

推荐模组排查顺序：

```text
mods
mods status
maps 1
mapgc dryrun
```

### 服务诊断

| 指令 | 作用 |
|:---|:---|
| `health` | 查看服务健康、内存压力和运维建议 |
| `health detail` | 增加存储细节 |
| `config check` | 检查主配置、授权、列表和 API 风险 |
| `configcheck` | `config check` 的别名 |
| `license` | 查看免授权/授权及公共列表能力诊断 |
| `api status` | 查看 API 缓存、限流和错误指标 |
| `plugins [id]` | 查看插件信息、能力和运行态 |
| `pluginlog status` | 查看 V2 插件日志级别、限流、队列和文件写入 |
| `roomperm status` | 查看房主、管理员、玩家和超级管理员权限 |
| `access status` | 查看持久封禁和管理员状态 |
| `access reload` | 重新读取持久访问配置 |
| `maplock status` | 查看锁图状态 |
| `maplock apply` | 重新应用锁图 |
| `advertising status` | 查看广告跳转配置状态 |
| `advertising check` | 立即检查广告跳转目标 |

### Start 模式和低延迟

```text
startmode pretest
startmode bootcheck
startmode diagnose
startmode nocontrol
```

| 指令 | 重点 |
|:---|:---|
| `startmode pretest` | 首轮联机前检查清单 |
| `startmode bootcheck` | 端口、人数、单位、地图、低延迟和开局状态 |
| `startmode diagnose` | 开局、地图引擎、重连、赛后和房间状态 |
| `startmode nocontrol` | 玩家进入后无法操作的专用分诊 |

```text
lowlatency profiles
lowlatency status
lowlatency readout
lowlatency summary
lowlatency experience
lowlatency advise
lowlatency reset
lowlatency apply stable
lowlatency apply recommended
lowlatency apply aggressive
lowlatency apply lowMemory
lowlatency apply production
lowlatency apply test
lowlatency apply config
lowlatency apply off
```

`apply` 只影响当前进程，不写回 JSON。`nettyWorkerThreadsOverride` 只能重启生效。生产服不要把 `off` 当成长期优化档位；出现 bridge/preTick failed、回弹或掉指令时优先切 `stable`。

### 维护和插件

| 指令 | 作用 |
|:---|:---|
| `maintenance plan [天数]` | 预览缓存、回放和日志清理计划 |
| `maintenance clean-cache confirm` | 清理缓存 |
| `maintenance clean-replays confirm [天数]` | 按保留天数清理回放 |
| `maintenance clean-logs confirm [天数]` | 按保留天数清理日志 |
| `plugin <参数...>` | 转发到插件命令中心 |
| `tryupdate` | 尝试使用上游更新机制，生产环境前先备份 |

## 2. 游戏内通用指令

以下指令通常使用 `.` 前缀，例如 `.help`、`.maps 1`：

| 指令 | 参数 | 权限/作用 |
|:---|:---|:---|
| `.help` | 无 | 输出当前可见指令和官方 QQ 群帮助 |
| `.start` | 无 | 请求开始游戏，受最低人数和权限限制 |
| `.maps` | `[页码]` | 查看地图列表 |
| `.map` | `<编号>` | 房主/管理员选择地图 |
| `.status` | 无 | 查看当前房间和玩家状态 |
| `.myid` | 无 | 查看持久权限匹配所需 ID |
| `.afk` | 无 | AFK 接管房主，受权限限制 |
| `.vote gameover` | 无 | 发起结束对局投票 |
| `.battleroom` | 无 | 请求按原版链路返回战役室 |
| `.pause` | 无 | 管理员暂停对局 |
| `.unpause` | 无 | 管理员解除暂停 |
| `.bind <验证码>` | 验证码 | QQ 绑定流程，需对应平台服务 |
| `.surrenderstatus` | 无 | 查看团队投降申请进度 |

投降相关别名仍受支持：`.surrender`、`.ff`、`.forfeit`、`.giveup`、`.resign`、`.投降`、`.放弃`。默认团队模式下优先走原版投降动作并显示团队进度；开启 `enableIndividualSurrender` 后，投降按钮和这些指令按单人投降模式处理。

## 3. 房主/管理员对局设置

| 指令 | 参数 | 作用 |
|:---|:---|:---|
| `.income` | `<倍率>` | 修改收入倍率 |
| `.credits` | `<金额>` | 修改开局资金 |
| `.fog` | `<off/basic/lineOfSight>` | 修改迷雾 |
| `.startingunits` | `<类型>` | 修改初始单位 |
| `.nukes` | `<true/false>` | 修改当前大厅核弹选项 |
| `.sharedcontrol` | `<true/false>` | 修改共享控制 |
| `.addai` | 无 | 添加 AI，要求 `gameplay.enableAI=true` |
| `.ai` | `<难度> [位置]` | 修改 AI 难度 |
| `.kick` | `<位置>` | 踢出大厅玩家 |
| `.give` | `<位置>` | 转移房主权限 |
| `.am` | `<on/off>` | 切换混合队伍 |
| `.teamlock` | `<on/off>` | 锁定玩家槽位移动 |

`.addai` 和 `.ai` 仍受 `RoomPermissionConfig.json` 的 `allowAddAi`、`allowSetAiDifficulty` 等字段限制。

## 4. 快捷队伍和位置布局

这些命令只在战役室、且执行者有 `MOVE_PLAYERS` 与 `CHANGE_TEAM` 权限时生效：

| 指令 | 作用 |
|:---|:---|
| `.t2` | 分为两队并刷新连续玩家位置 |
| `.t3` | 分为三队并刷新连续玩家位置，C 队使用与 A/B 相邻的有效槽位 |
| `.tffa` | 混战，每名真人独立队伍并刷新位置 |
| `.tv` | 全员观战，保留已有观战槽位规则 |
| `.teamlayout 2` | `.t2` 的通用形式 |
| `.teamlayout 3` | `.t3` 的通用形式 |
| `.teamlayout ffa` | `.tffa` 的通用形式 |
| `.teamlayout spectators` | `.tv` 的通用形式 |
| `.teamshuffle` | 重新打乱位置并按奇偶位分两队 |
| `.ffa` | 混战布局别名 |

队伍布局不会在对局开始后修改玩家。若存在未绑定的引擎槽位，服务端会拒绝调整并提示先刷新战役室或重置房间，以避免只清理引擎槽位造成玩家绑定错位。

## 5. 精确管理员操作

下列兼容命令主要用于房主/管理员，部分在 `.help` 中隐藏，但仍受权限校验：

```text
.move <玩家位置> <目标位置> [队伍]
.team <玩家位置> <队伍>
.self_move <目标位置> [队伍]
.self_team <队伍>
```

观战目标可使用观战队伍语义。不要在对局中使用；管理员操作必须经过游戏主线程，若看到 `Lobby state changed` 或 `map_engine_not_ready`，等待房间状态稳定后再重试。

## 6. 地图标点和消息长度

地图标点不是普通聊天消息，不应被 `gameplay.maxMessageLen` 当作消息过长拦截。普通聊天仍按消息长度限制处理。出现标点异常时，使用 `lowlatency readout` 和 `lowlatency summary` 观察 `commandTypes.mapPing`，不要通过提高普通聊天长度来掩盖协议问题。

## 7. 权限配置对应关系

| 能力 | 配置字段 |
|:---|:---|
| 开始游戏 | `host/admin/player.allowStartGame` |
| 换图/查看地图 | `allowChangeMap`、`allowViewMaps` |
| 加 AI/改 AI | `allowAddAi`、`allowSetAiDifficulty` |
| 踢人 | `allowKickPlayers` |
| 调队/调位 | `allowChangeTeam`、`allowMovePlayers` |
| 转移房主 | `allowTransferHost` |
| 暂停/解除暂停 | `allowPauseGame`、`allowUnpauseGame` |
| 投降投票 | `player.allowVoteGameOver` |
| 全单位死亡自动移除 | `lobby.enableAutoKickPlayerWhenAllUnitsDestroyed` |

关闭 `gameplay.oneAdmin` 时，普通客户端房主 UI 操作会移除，普通玩家不能通过手动命令继续伪造房主权限；持久超级管理员按 `moderation.superAdminBypass` 规则处理。

## 8. 指令问题回传格式

请提供：

```text
服务端版本/发布包名:
运行模式:
地图与模组:
在线人数/AI 数量:
执行指令:
执行者权限:
客户端类型:
原始提示文本:
startmode bootcheck:
startmode diagnose:
lowlatency readout:
lowlatency summary:
相关日志前后各约 100 行:
```

不要只提供“指令无效”一句话；同一指令可能是权限拒绝、房间已开局、地图引擎未就绪、客户端未发送原版动作或配置禁用造成的不同问题。
