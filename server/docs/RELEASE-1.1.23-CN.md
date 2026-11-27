# CNKD NEXT 1.1.23 发布说明

本版本包含截至 2026-11-11 的全部主线修复（基线提交 `5098dc9`）。2026-11-13 重新构建为完整发布包；新增版本标识和 Windows 启动兼容处理。程序显示版本、JAR Implementation-Version 与包名统一为 1.1.23。插件 API 仍为 1.2.0；预装 EIRS `identity.reporter` 1.1.0，两者不是服务端产品版本号。

## 玩家和房主

- 默认普通玩家可在未开局大厅自行调整位置、队伍和 S 观战状态，修复 S 返回参战槽位、不同命令入口和观战槽位查找问题。
- `lobby.disablePositionChange=false`、`lobby.disableTeamChange=false` 时允许自行操作；显式位置锁、队伍锁和 `.teamlock on` 仍按设计限制操作。`map.enableMapLock` 只锁地图。
- 手动 Start 默认等待 5 秒，通过 `gameplay.manualStartCountdownSeconds` 配置（0..300；0 为立即开始）。旧配置缺少此字段时同样默认 5 秒。自动开局倒计时独立，不重复叠加。
- 修复房主交接权限、管理员观战/参战转换、观战者管理、投票清理、离线解封和参战人数计算等已确认问题。

## API 和运行状态

- 修复管理 WebSocket 会话过期/改密后的撤权，以及 HTTP 路由重复响应。
- 增加真实开局/世界状态查询，完善配置校验和大厅热重载回滚；跨局世界同步增加对局代次检查。
- Windows 启动脚本自动创建并传递短 UDS 临时目录，处理本地 Java 17 的 selector/UnixDomainSockets 启动错误。默认 `%SystemDrive%\tmp\cnkd-hps-uds`；无写权限时可预先设置 `CNKD_UDS_TMP` 为可写短路径。`JAVA_OPTS` 仍可自定义。

## 安装和升级

新安装可解压平铺版，按需编辑 `data/ServerConfig.json`，运行对应系统启动脚本。默认 UPLIST 关闭，支持在确认公开后开启；本包免授权。

现有实例升级：停服并备份旧 JAR 和配置后替换 `Server-All.jar`，保留自己的 `data`、地图、模组、插件及插件锁，再重启。Windows 同时更新 `start-windows.bat`。不要用新包的空白配置覆盖已有实例的配置和玩家数据。原有显式锁不会被升级自动关闭。

## 预装插件

EIRS `identity.reporter` 1.1.0 与匹配插件锁随包提供。首次正常启动使用插件默认配置；需要关闭上传时，在 `data/plugins/data/identity.reporter/config.json` 设置 `uploadEnabled=false`，需要关闭封禁同步时设置 `banSyncEnabled=false`，随后重启。实例的 token 和身份文件不随包分发。

## 版本识别和验证边界

包根目录 `RELEASE-INFO.json` 记录产品版本、源码提交、构建工作区状态、JAR SHA-256 和插件锁。JAR Manifest 记录 Implementation-Version、Build-Jar-Time、CNKD-Source-Revision、CNKD-Source-Dirty；不要只凭文件名或文件修改时间判断版本。

隔离引擎/协议回归不等于真实客户端验收。完整客户端开局、多人连续对局、中途重连世界一致性和 EIRS 云端联调仍需在目标环境验收。构建与启动检查的最终结果见本版本外部 MANIFEST 和验证日志。
