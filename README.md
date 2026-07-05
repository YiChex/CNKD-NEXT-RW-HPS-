# CNKD NEXT

CNKD商用版本的铁锈战争服务端  
基于 `RW-HPS` 长期独立优化  
面向 `Rusted Warfare` Headless `start` 模式  
现已提供免授权发布包，建议下载最新版本或 `2.0.5` 以上版本
如需反馈或寻求帮助请加入QQ群组910085695
可免费试用使用本服务端运行的铁锈战争云实例
试用地址：https://cloud.cnkd.fun/

`CNKD NEXT` 的目标不是简单“能启动一个房间”，而是提供一套更适合正式开服、长期维护、多人运营和低门槛部署的独立服务端方案。

它重点服务以下场景：

- 公开列表服
- 单房间娱乐服
- 标准团战服
- FFA 混战服
- 生存地图服
- 固定地图活动服
- 低内存 Linux 云服务器
- 希望长期稳定维护的个人服或小团队（游戏战队）服务器

## 核心优势

### 纯 `start` 模式独立优化

CNKD NEXT 将长期维护重点放在 Headless `start` 模式上，减少 Relay 运行形态对普通房间服的干扰。

- 默认启动模式为 `start`
- 更适合公开服、娱乐服、团战服、混战服和生存服
- 降低旧 Relay 配置、旧插件、旧授权链路对单房间部署的理解成本
- 代码结构按 `Server-Core` / `Server-All` / `runtime-data` 分层，便于继续维护

### 免授权发布包

当前发布包已提供免授权模式。

- `data/RelayLicenseConfig.json` 通常可以保持空白
- 不需要服主额外申请授权码即可启动
- 可按发布包类型选择是否允许公共列表 `UPLIST`
- 保留旧授权配置文件是为了兼容旧配置结构和管理面板

注意：免授权指 CNKD NEXT 发布包不再要求服主配置授权码，不代表可以忽略上游开源许可、署名和合规要求。

### 配置集中化

主要配置集中在少量文件中，便于新服主理解和维护。

- `data/ServerConfig.json`
  - 端口
  - 服务器名称
  - 公共列表
  - 玩家人数
  - 自动开局
  - 默认资金
  - 默认迷雾
  - 是否禁用核弹
  - 地图锁定
  - 大厅规则
  - 单房间低延迟参数
- `data/RoomPermissionConfig.json`
  - 房主权限
  - 管理员权限
  - 普通玩家可用命令
- `data/PersistentAccessConfig.json`
  - UUID / IP / IP 段封禁
  - 持久管理员
  - 持久超级管理员
- `data/RelayLicenseConfig.json`
  - 免授权包通常保持空白
  - 保留用于兼容旧授权字段

发布包内还包含 `CONFIG-HELP-CN.txt`、`OPERATIONS-CHECKLIST-CN.txt`、`DATA-API-GUIDE-CN.txt` 等说明文件，初次使用请查看。

### 三种玩法完整支持

CNKD NEXT 对常见房间模式做了专门适配。

- 标准团战
  - 支持普通 AB 队对战
  - 支持按位置/加入顺序做基础队伍分配
  - 可禁止玩家自行换队或换位
- FFA 混战
  - 每位真人玩家独立队伍
  - 适合 8P、10P、14P、16P、100P 等大图混战
  - 可配合自动开局和锁图使用
- 生存地图（尚未完善 对地图有特定要求）
  - 可保留系统 AI 位置
  - 真人玩家固定为同一阵营
  - 加入生存图结算保护，降低地图脚本尚未刷怪时误判结束的概率

### 自动开局链路统一

当前版本统一了多条开局路径，减少“表面开局、实际未开始”的问题。

- 控制台命令开始
- 玩家命令开始
- 房间按钮开始
- 自动倒计时开始

这些路径会共用开始前校验逻辑，包括人数、地图、房间状态和对局规则。

### 公共列表能力强化

对于允许 `UPLIST` 的免授权发布包，CNKD NEXT 支持更完整的公共列表展示控制。

- 启动后按配置自动上列表
- 支持关闭服务器名状态后缀
- 支持服务器名置顶字符数量
- 支持地图名置顶字符数量（广告服功能）
- 支持多条公共列表入口（广告服功能 支持跳转到其他服务器）
- 支持覆盖列表展示地图名（广告服功能）
- 支持展示人数上限覆盖（广告服功能）
- 支持列表推广模式（广告服功能）
- 支持游戏开始后保留列表展示
- 支持回到大厅后恢复等待展示

默认 `autoUpList=false`，避免新服主刚解压就误公开服务器。  
如果需要开放到公共列表，请确认使用的是允许 `UPLIST` 的发布包，并将 `data/ServerConfig.json` 中的 `common.autoUpList` 改为 `true`。

### 房间默认参数可配置

常用开局参数不再需要依赖玩家手动设置。

- 默认开局资金
- 默认收入倍数
- 默认最大单位数
- 是否允许 AI
- 是否禁用核弹
- 默认迷雾模式：`off` / `basic` / `lineOfSight`
- 自动开局人数
- 自动开局倒计时
- 单局最长时间
- 仅剩 AI 或无人时的兜底时间

### 地图与模组部署更直接

自定义地图和模组保持普通服主容易理解的目录结构。

- 地图目录：`data/maps`
- 模组目录：`data/mods`
- 支持 `.tmx` 地图
- 支持常见 `.rwmod` / `.zip` 模组包
- 支持锁定指定地图
- 支持模组包地图发现与提取

### 结算与重置链路强化

CNKD NEXT 针对长期公开服常见的房间卡死、无人占位、N/A 残留和游戏结束后无法进入下一局等问题做了强化。

- 游戏结束后可自动返回战役室（此功能尚未完善 请保持false）
- 可配置返回倒计时
- 可在游戏结束时清理掉线玩家占位
- 生存模式避免保留 AI 导致房间无法重置
- 支持最大对局时长兜底
- 支持只剩 AI 或无人时的兜底处理

### 单房间低延迟优化（使用此功能请保持谨慎）

当前版本包含面向单房间服的低延迟配置组。

- 游戏命令快速路径
- Tick 前命令队列 drain
- 低延迟指标输出
- Hess 出站命令提前量
- Headless FPS 覆盖
- Netty worker 线程数覆盖
- 直接事件循环写出

默认配置偏保守，适合大多数普通服。高并发或大型模组服建议逐项压测后再调整。

### 更克制的资源占用

CNKD NEXT 针对纯 `start` 模式做了资源占用优化。

- 默认关闭内置 Web 端口：`webPort=0`
- 默认低内存启动脚本
- 更克制的 JVM 启动参数
- JavaScript 插件不存在时跳过 Graal / Wasm 大依赖
- 发布包清理旧 Relay 配置的辅助脚本
- 发布包内日志、回放、玩家缓存目录按用途分离

## Licenses

本项目基于 `RW-HPS` 相关能力整理与扩展。  
源代码许可请以发布仓库附带的 `LICENSE` 文件、源码头部声明以及上游 `RW-HPS` 许可为准。

上游项目：

- `RW-HPS`: <https://github.com/deng-rui/RW-HPS>

请遵守以下原则：

- 不隐藏、不扭曲上游 `RW-HPS` 免费且开源的事实
- 修改、分发或发布衍生版本时保留必要署名
- 若你的项目直接修改、参考或复用 RW-HPS 内部实现，请在项目说明中明确声明来源
- 请仅在合法、合规、合理的场景中使用本项目

**一切开发旨在学习与服务器运维实践，请勿用于非法用途。**

## 开始

- 配置说明：`runtime-data/CONFIG-HELP-CN.txt`
- 运维检查：`runtime-data/OPERATIONS-CHECKLIST-CN.txt`
- 数据接口：`runtime-data/DATA-API-GUIDE-CN.txt`
- 玩家体验说明：`runtime-data/PLAYER-EXPERIENCE-CN.txt`
- 单房间说明：`runtime-data/SINGLE-ROOM-USER-GUIDE-CN.txt`
- 源码索引：`docs/STARTMODE_SOURCE_INDEX_CN.md`

## 仓库结构（完整源码开发需开源 可加入群聊后联系群主获取 仓库仅构建后的发布包）

```text
cnkd-next-source/
├─ README.md
├─ README-CN.md
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradlew
├─ gradlew.bat
├─ package-no-license-variants.ps1
├─ package-single-room-fixed.ps1
├─ ASM-Framework/
├─ TimeTaskQuartz/
├─ Server-Core/
├─ Server-All/
├─ buildSrc/
├─ docs/
└─ runtime-data/
   ├─ CONFIG-HELP-CN.txt
   ├─ OPERATIONS-CHECKLIST-CN.txt
   ├─ DATA-API-GUIDE-CN.txt
   ├─ PLAYER-EXPERIENCE-CN.txt
   ├─ SINGLE-ROOM-USER-GUIDE-CN.txt
   └─ data/
      ├─ ServerConfig.json
      ├─ RelayLicenseConfig.json
      ├─ RoomPermissionConfig.json
      ├─ PersistentAccessConfig.json
      ├─ maps/
      ├─ mods/
      ├─ plugins/
      ├─ replays/
      └─ saves/
```

## 构建

### 环境要求

| 项目 | 建议 |
|:---|:---|
| Java | Java 21 或更高版本 |
| Gradle | 使用仓库内 `gradlew` / `gradlew.bat` |
| 系统 | Windows / Linux |
| 编码 | UTF-8 |

### Windows 构建

```powershell
./gradlew.bat :Server-Core:compileKotlin
./gradlew.bat test
./gradlew.bat :Server-All:jar
./gradlew.bat :Server-All:packageStartModeDeployZip
```

### Linux 构建

```sh
chmod +x ./gradlew
./gradlew :Server-Core:compileKotlin
./gradlew test
./gradlew :Server-All:jar
./gradlew :Server-All:packageStartModeDeployZip
```

### 发布包输出

构建完成后，部署包会输出到上级目录：

```text
../releases/
```

通常会生成：

- 一个可直接部署的目录
- 一个对应的 `.zip` 压缩包

## 快速部署

### Linux

1. 安装 Java 21 或更高版本
2. 解压发布包到全新目录
3. 修改 `data/ServerConfig.json`
4. 按需修改 `data/RoomPermissionConfig.json`
5. 如果需要公共列表，确认发布包允许 `UPLIST`，再设置 `common.autoUpList=true`
6. 赋予脚本执行权限
7. 启动服务端

```sh
chmod +x start.sh start-lowmem.sh cleanup-pure-start.sh
./start.sh
```

低内存启动：

```sh
./start-lowmem.sh
```

自定义 JVM 参数：

```sh
JAVA_OPTS="-Xms256m -Xmx1536m" ./start.sh
```

### Windows

1. 安装 Java 21 或更高版本
2. 解压发布包到路径稳定的目录
3. 修改 `data/ServerConfig.json`
4. 双击启动脚本，或在终端中运行启动命令

如果从源码目录测试，可使用：

```powershell
java -jar .\build\libs\CNKD-Start-Mode-Standalone.jar
```

实际发布部署建议使用 `packageStartModeDeployZip` 生成的完整部署包，不建议只替换单个 Jar。

## 关键配置示例

### 普通 4 人娱乐房

```json
{
  "gameplay": {
    "maxPlayer": 4,
    "enableAI": false,
    "startMinPlayerSize": -1,
    "autoStartMinPlayerSize": -1
  },
  "lobby": {
    "enableFreeForAllMode": false,
    "enableSurvivalMapMode": false
  },
  "map": {
    "enableMapLock": false
  }
}
```

### 14 人 FFA 混战房

```json
{
  "gameplay": {
    "maxPlayer": 14,
    "enableAI": false,
    "autoStartMinPlayerSize": 14
  },
  "lobby": {
    "enableFreeForAllMode": true,
    "enableSurvivalMapMode": false
  },
  "map": {
    "enableMapLock": true,
    "lockMap": "你的14P地图名"
  }
}
```

### 生存地图房

```json
{
  "gameplay": {
    "maxPlayer": 10,
    "enableAI": false
  },
  "lobby": {
    "enableSurvivalMapMode": true,
    "survivalGameOverGraceSeconds": 180,
    "survivalEnemyClearConfirmSeconds": 45
  },
  "map": {
    "enableMapLock": true,
    "lockMap": "你的生存地图名"
  }
}
```

### 公开列表服

```json
{
  "common": {
    "serverName": "我的铁锈战争服务器",
    "autoUpList": true,
    "listStatusSuffixEnabled": false,
    "listNamePinnedCharacterCount": 0,
    "listPinnedCharacterCount": 0
  }
}
```

## 服务器命令列表

具体命令以服务端控制台 `help` 输出为准。常用命令如下：

| 命令 | 参数 | 信息 |
|:---|:---|:---|
| `help` | | 查看帮助 |
| `start` | | 启动 Headless 房间 |
| `stop` | | 停止服务端 |
| `say` | `<文字>` | 以服务器名义发送消息 |
| `gameover` | | 结束当前对局 |
| `admin` | `<add/remove> <玩家位置>` | 设置或移除管理员 |
| `ban` | `<玩家位置>` | 封禁玩家 |
| `unban` | `<玩家位置>` | 解除玩家封禁 |
| `mute` | `<玩家位置> [秒]` | 禁言玩家 |
| `kick` | `<玩家位置> [秒]` | 踢出玩家 |
| `kill` | `<玩家位置>` | 清除指定玩家单位 |
| `players` | | 查看玩家列表 |
| `maps` | `[页码]` | 查看地图列表 |
| `plugins` | | 查看插件列表 |

## 游戏内命令列表

具体命令以游戏内 `help` 输出和 `RoomPermissionConfig.json` 权限配置为准。

| 命令 | 参数 | 信息 |
|:---|:---|:---|
| `help` | | 查看可用命令 |
| `start` | | 请求开始游戏 |
| `maps` | `[页码]` | 查看地图列表 |
| `vote gameover` | | 发起结束对局投票 |
| `kick` | `<玩家位置>` | 按权限踢出玩家 |

## 运维建议

- 公开服请固定端口，并在云安全组、防火墙、面板端口映射中保持一致
- 新服主先保持 `autoUpList=false`，确认端口可连接后再开放公共列表
- 不要只替换 `Server-All.jar` 升级生产服，优先使用完整发布包迁移
- 修改 JSON 前先备份配置文件
- 公共服不要随意开放刷钱、刷单位、改图、加 AI 等高风险权限
- 大型模组服建议保留默认大厅同步优化，不要盲目降低心跳间隔
- 出现异常时优先保留 `data/log/Log.txt`、配置文件和启动命令

## 常见问题

### 玩家进不来

检查 `common.port`、云服务器安全组、系统防火墙、面板端口映射和服务端启动日志。

### 公共列表看不到

先确认当前发布包是否允许 `UPLIST`。  
禁 `UPLIST` 版本即使设置 `autoUpList=true` 也不会上传公共列表。  
允许 `UPLIST` 的版本需要设置 `common.autoUpList=true` 后重启。

### 游戏结束后房间没有恢复

确认：

- `lobby.enableGameOverBattleRoomReturn=true`
- `lobby.gameOverReturnCountdownSeconds` 不要过大
- `lobby.pruneDisconnectedPlayersOnGameOver=true`
- 单局是否达到 `gameplay.maxGameIngTime`

### 地图名锁定失败

确认地图文件在 `data/maps` 中，`map.lockMap` 填写的是服务端能识别到的地图名。  
如果使用模组地图，确认模组包地图发现和提取配置已开启。（推荐手动上传到maps文件夹更为稳定）

### 内存不足

优先使用：

```sh
./start-lowmem.sh
```

或自定义：

```sh
JAVA_OPTS="-Xms128m -Xmx768m" ./start.sh
```

## 不建议的使用方式

- 不建议把 CNKD NEXT 当作 Relay 项目的直接替代品混用
- 不建议在生产服只替换 Jar 而不迁移 `data` 和启动脚本
- 不建议在不了解字段含义时开启公共列表实验字段
- 不建议在公开服给普通玩家开放高风险管理命令
- 不建议将本项目用于违法、侵权或破坏社区秩序的用途

## 鸣谢

- `RW-HPS` 项目提供基础能力与长期社区积累
- `Rusted Warfare` 社区玩家和服主提供真实运行反馈
- 所有参与测试、反馈、部署和问题定位的 CNKD 用户

## 免责声明

本项目基于 `RW-HPS` 相关能力进行整理与扩展。  
请仅在合法、合规、合理的使用场景中使用本项目及相关发布包。  
项目文档中的配置建议面向常见部署场景，实际生产服仍应结合服务器性能、地图规模、模组复杂度和玩家数量进行测试。
