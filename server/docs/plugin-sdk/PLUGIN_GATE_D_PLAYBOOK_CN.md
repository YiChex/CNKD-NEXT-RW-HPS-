# P8-08 Gate D 故障恢复实测手册

本手册只验证独立单房间 Start 服务端中受控 V2 插件异常后的恢复能力。它不开放热卸载、未知第三方插件、GAMECOMMAND 拦截或生产发布。

## 输入边界

- 使用 `prepare-plugin-gated-runtime.ps1` 创建的全新隔离副本，不得修改默认 `runtime-data` 或 Gate C 证据目录。
- 副本只允许包含 `gated.fault-recovery-probe` 一个经锁批准的外部插件。
- 故障探针不会读取或写入游戏状态；它只在玩家命令和首次 `GameStartedEvent` 中各抛出一次预期异常。

## 操作步骤

1. 启动副本后执行 `lowlatency apply test`、`lowlatency reset`、`plugins gated.fault-recovery-probe`。
2. 两名客户端进入同一房间，完成房主、地图选择和开局。首次 `GameStartedEvent` 必须被记录为一次预期事件回调失败。
3. 在第一局输入 `.gatecfaultprobe`。客户端应收到命令失败反馈；随后双方立即移动、建造、生产和聊天，操作不得停止。
4. 完成团队投降和结算，等待强制重置或按当前配置返回战役室。两名客户端再次进入并开始第二局，无需重启实例。
5. 第二局继续完成移动、建造、地图 ping、聊天和玩家命令；随后执行 `plugins gated.fault-recovery-probe`、`lowlatency readout`、`lowlatency summary`。

## 通过条件

- 日志中的预期异常只来自 `gate-d expected player command failure` 和 `gate-d expected first game-start listener failure`，且均由 V2 插件隔离层记录，不得形成未捕获线程错误、bridge/preTick 失败或 GAMECOMMAND 停止。
- `plugins gated.fault-recovery-probe` 最终显示 `ACTIVE`，命令回调为 `command=1/1`，事件回调至少为 `event=2/1/1`，事件丢弃为 0。
- 第一局异常后和第二局均由真实玩家确认可移动、建造和生产；第二局 `GameStartedEvent` 必须成功回调，证明事件投递没有因首次异常永久失效。
- 完整 stdout/stderr、插件回调读数和 `startmode diagnose` 必须保留。任何非预期异常、无法操作、下一局无法开局或需要重启实例恢复均为 Gate D 失败。

## 结论边界

Gate D 通过只能证明当前受控 V2 回调故障在该单房间环境中被隔离和恢复。它不构成对任意第三方代码的安全承诺，也不解除 Gate E 发布门禁。
