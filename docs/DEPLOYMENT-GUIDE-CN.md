# CNKD NEXT 单房间部署指南

这份指南面向实际部署服务端的服主和管理员，覆盖首次安装、模组地图、公共列表、升级、备份和故障排查。默认以纯 `start` 单房间发布包为准，不把排位核心、Relay 节点和多房间平台配置混入普通部署流程。

## 1. 发布包选择

从 [GitHub Releases](https://github.com/YiChex/CNKD-NEXT-RW-HPS-/releases) 下载完整发布包。常见包类型：

| 包类型 | 适用场景 | 公共列表 |
|:---|:---|:---|
| `NoLicense-NoUpList-SingleRoom` | 私服、局域网服、不开列表的独立房间 | 禁止上传 |
| `NoLicense-UpList-SingleRoom` | 公开列表单房间服 | 可按配置开启 |
| 平台或广告服模板 | 面板批量实例、导航和跳转场景 | 依模板声明为准 |

不要把不同包的 `Server-All.jar`、`data` 和启动脚本混合覆盖。纯服务端包的 `data/maps` 和 `data/mods` 可以为空，地图和模组由服主自行导入。

## 2. 环境要求

- Java 17 或更高版本。
- Linux 需要 `unzip` 或其他能保留 UTF-8 文件名的解压工具。
- 服务端端口必须同时放行云安全组、系统防火墙、面板映射和客户端实际连接端口。
- 模组服建议至少预留 1 GB 内存；大型模组、100 人房间或 1000 单位上限应按实际地图压测。

检查 Java：

```sh
java -version
```

## 3. Linux 首次部署

```sh
mkdir -p /opt/cnkd-next
cd /opt/cnkd-next
unzip CNKD-NEXT-<version>.zip
cd CNKD-NEXT-<version>
chmod +x start.sh start-lowmem.sh
```

编辑 `data/ServerConfig.json`，至少检查：

```json
{
  "common": {
    "port": 5123,
    "serverName": "我的单房间服务器",
    "autoUpList": false
  },
  "gameplay": {
    "maxPlayer": 100,
    "maxUnit": 1000,
    "enableAI": false
  },
  "map": {
    "enableModPackageMapDiscovery": true,
    "enableModPackageMapSupportExtraction": true,
    "enableModPackageMapAutoExtraction": true
  }
}
```

启动：

```sh
./start.sh
```

低内存实例：

```sh
./start-lowmem.sh
```

临时指定 JVM 参数：

```sh
JAVA_OPTS="-Xms256m -Xmx1536m" ./start.sh
```

启动后先在后台控制台执行：

```text
version
config check
startmode bootcheck
startmode pretest
mods
maps 1
```

看到 `服务器已启动` 后，再让客户端连接。`bootcheck` 显示的是启动期实际配置，优先于文档中的默认值。

## 4. Windows 首次部署

1. 使用支持 UTF-8 文件名的工具解压完整包。
2. 编辑 `data/ServerConfig.json`。
3. 双击 `start-windows.bat`，或在包目录运行：

```powershell
java -Dfile.encoding=UTF-8 -jar .\Server-All.jar
```

4. 观察窗口中是否出现 `模组加载完成`、`服务器已启动` 和端口信息。

## 5. 端口和连接排查

服务端只负责监听本机端口，不会自动打开云平台安全组。按下面顺序检查：

1. `data/ServerConfig.json` 的 `common.port`。
2. 云平台入方向规则是否放行同一 TCP 端口。
3. Linux 防火墙或 Windows 防火墙是否放行。
4. MCSM、Docker、NAT 或反向代理是否把外部端口映射到该端口。
5. 启动日志中的实际端口是否与配置一致。

Linux 示例：

```sh
ss -lntp | grep 5123
```

Windows 示例：

```powershell
Test-NetConnection 127.0.0.1 -Port 5123
```

## 6. 模组和模组地图

### 导入流程

1. 将 `.rwmod` 或 `.zip` 原文件放入 `data/mods`。
2. 保持以下配置为 `true`：

```json
{
  "map": {
    "enableModPackageMapDiscovery": true,
    "enableModPackageMapSupportExtraction": true,
    "enableModPackageMapAutoExtraction": true
  }
}
```

3. 重启服务端，或在未开局时执行 `reloadmaps`。
4. 执行 `mods` 确认模组已加载。
5. 执行 `maps 1` 确认模组地图已加入自定义地图列表。

### 自动提取的实际边界

- 服务端扫描模组包中的 `maps` 路径和 `.tmx` 文件。
- 地图会准备到 `data/maps/cnkd_mod_maps/`，并保留地图原始显示名。
- 同目录的 `_map.png`、`.tsx`、图片、音频、`.ini` 等支撑文件会按配置一并准备。
- 同一运行地图已经存在时会复用文件，不重复写入地图正文。
- 客户端仍需安装同一个模组，服务端不能替客户端生成缺失的模组单位或贴图。
- 模组包超过 `modPackageMapScanMaxArchiveSizeMb` 时会被跳过地图发现；默认上限为 512 MB，0 表示不限制。

### 地图选择

后台可用 `maps 1` 查看列表。管理员在游戏内使用：

```text
.maps 1
.map <地图编号>
```

选择模组地图后，服务端会准备运行文件并将地图路径写入原版地图设置链路。客户端没有对应模组时，可能无法完成地图加载或进入对局。

### 删除或替换模组

删除模组后不要立即手动删除整个 `data/maps`。服务端只会在 `data/maps/cnkd_mod_maps/` 中回收由它创建且已不再被索引的运行目录，并保留保护时间。

```text
mapgc status
mapgc dryrun
mapgc cleanup
```

确认缓存确实是孤儿目录后才使用：

```text
mapgc cleanup-force
```

当前房间正在使用的地图目录不会自动回收。升级、替换模组后建议先执行 `reloadmaps`，再观察 `mapgc status`。

## 7. 公共列表

新实例默认不上传公共列表：

```json
{
  "common": {
    "autoUpList": false
  }
}
```

允许 UPLIST 的包在确认端口公网可达后才设置：

```json
{
  "common": {
    "autoUpList": true
  }
}
```

禁 UPLIST 包即使修改 `autoUpList` 也不会上传。服务器名、地图名置顶字符和推广字段只影响列表展示，不会改变真实房间地图、人数或单位。

## 8. 升级流程

升级前：

```sh
cp -a data "data-backup-$(date +%Y%m%d-%H%M%S)"
```

Windows 可直接复制整个 `data` 目录为带日期的备份。

推荐流程：

1. 停止旧实例并确认 Java 进程已退出。
2. 解压新完整发布包到新目录。
3. 只迁移经过核对的 `data/ServerConfig.json`、`RoomPermissionConfig.json`、`PersistentAccessConfig.json`、模组和地图。
4. 不覆盖新包中的启动脚本和配置结构；缺失字段交给服务端配置迁移器补全。
5. 启动后执行 `version`、`config check`、`startmode bootcheck`、`mods` 和 `maps 1`。
6. 首局先使用保守配置，确认客户端可进入、可开始、可移动、可建造后再调整低延迟档位。

不建议在线上运行实例中直接覆盖正在使用的 JAR，也不建议把旧 Relay 目录整目录覆盖到纯 Start 包。

## 9. 首轮实测流程

```text
startmode pretest
startmode bootcheck
lowlatency apply test
lowlatency reset
```

客户端至少完成一次：进入战役室、选择地图、开始游戏、移动单位、建造、地图标点、投降或结束流程。完成后执行：

```text
lowlatency readout
lowlatency summary
lowlatency advise
startmode diagnose
```

出现回弹、掉指令、无法控制、桥接失败或多人同时卡顿时，先执行：

```text
lowlatency apply stable
```

不要只根据战役室延迟数字判断服务端同步质量。报告问题时同时提供地图、模组、人数、AI 数量、低延迟档位和上述诊断输出。

## 10. 常见故障

### 玩家进入后无法操作

先确认房间确实已开局，再执行 `startmode diagnose`。重点看：

- `room.started`
- `room.mapEngineReady`
- `flow.gameCommandHotPathReady`
- `flow.stateConsistency`
- `noControl.triage.primary`

如果 `mapEngineReady=false` 或出现 `map_engine_not_ready`，先排查地图、模组资源和大厅状态，不要立即增加网络线程。

### 模组加载了但地图列表为空

确认：

- 模组确实位于 `data/mods`，不是 `data/maps`。
- 模组内存在 `maps` 路径和 `.tmx`。
- `enableModPackageMapDiscovery=true`。
- 包体积没有超过扫描上限。
- 执行了 `reloadmaps` 或完整重启。
- `mods status` 和 `startmode diagnose` 中没有扫描错误。

### 开局后自动结束

执行 `startmode diagnose`，区分 `auto_start_disabled`、赛后重置、无人宽限、生存图保护和真正的引擎结束。生存图需要核对 `enableSurvivalMapMode`、地图脚本和 AI 槽位，普通地图不要误开生存模式。

### 游戏结束提示与配置不一致

`lobby.enableGameOverBattleRoomReturn=true` 才会进入原版战役室返回链路并显示自动返回提示。保持 `false` 时应使用服务器强制重置提示，不能把两种提示混用。

### 插件日志看不到

V2 插件日志使用 `pluginLogging.consoleLevel` 和 `pluginLogging.fileLevel`。默认 `INFO`；普通插件日志不应只依据 `common.log=WARN` 判断。Legacy V1 直接调用全局日志 API 时，仍受 `common.log` 阈值影响。

### 内存或启动时间不足

优先使用 `start-lowmem.sh`，降低模组地图扫描范围或关闭自动提取做对照。不要在还未确认地图能否加载前同时调整人数、单位、FPS、Netty worker 和低延迟提前量。
