# P8-08 Gate C 对照操作手册

本手册只用于单房间 Start 服务端的插件性能门禁。它不批准插件发布，也不替代 Gate D 的完整玩家链路验收。

## 前置条件

- 使用当前候选 `Server-All.jar` 的两个新隔离副本；不要使用默认 `runtime-data`，不要改变线上实例。
- 两轮使用相同 JAR、地图、模式、人数、AI、模组、低延迟档位、服务器硬件和网络条件。
- 第一轮插件目录只保留空锁 `plugin-lock.json`；第二轮只保留 `tools/plugin-gatec-fixtures` 生成的五个普通 V2 探针及其完整锁。
- 两轮均执行 `lowlatency apply test` 后执行 `lowlatency reset`，再开局进行至少 60 秒真实操作。
- 每轮至少包含移动、建造、ping、聊天和玩家命令；记录是否每名测试玩家均可正常操作。五插件轮还必须保存四个探针的 plugins id 输出，分别覆盖玩家命令、玩家事件、对局事件与调度器。

## 准备隔离副本

从源码根目录执行以下命令。输出目录必须是一个尚不存在的新目录；脚本不会覆盖旧测试、不会启动服务，也不会修改默认 `runtime-data`。

```powershell
$runRoot = Join-Path $PWD 'build\p8-08-gatec-player-ready'
& .\docs\plugin-sdk\prepare-plugin-gatec-runtimes.ps1 `
  -ServerJar .\Server-All\build\libs\Server-All.jar `
  -RuntimeDataDirectory .\runtime-data\data `
  -FixtureDirectory .\tools\plugin-gatec-fixtures\build\libs `
  -OutputRoot $runRoot `
  -ApprovedBy 'gatec-local-review'
```

脚本创建两个相同服务端与数据副本：`baseline` 保持空插件锁，`fixtures` 仅包含五个 Gate C V2 探针和为完整集合生成的锁。它同时写入 `gatec-runtime-manifest.json`，保存服务端与五个归档的 SHA-256。两个副本默认使用相同游戏端口，必须先完整停止基线，再启动五插件副本。

顺序启动：

```powershell
Set-Location "$runRoot\baseline"
java -jar .\Server-All.jar start

# 收尾并退出基线后，再启动五插件副本。
Set-Location "$runRoot\fixtures"
java -jar .\Server-All.jar start
```

## 每轮收尾

控制台依次执行：

```text
lowlatency readout
lowlatency summary
plugins
plugins gatec.player-command-probe
plugins gatec.player-events-probe
plugins gatec.game-events-probe
plugins gatec.scheduler-probe
exit
```

保留完整标准输出日志和空的标准错误日志。不得只截取单行数值，也不得将无玩家的启动日志作为性能样本。

## 判定

在源码根目录执行。`QueueRegressionAssessment` 只可在人工比较 p50、Hess 队列峰值、bridge/preTick 结果以及玩家实际操作后填写 `verified`。

```powershell
& .\docs\plugin-sdk\evaluate-plugin-gatec.ps1 `
  -BaselineLog .\build\gatec-baseline\server.stdout.log `
  -FixtureLog .\build\gatec-fixtures\server.stdout.log `
  -BaselineDurationSeconds 60 `
  -FixtureDurationSeconds 60 `
  -BaselinePlayerOperationVerified `
  -FixturePlayerOperationVerified `
  -QueueRegressionAssessment verified `
  -OutputJson .\build\gatec-assessment.json
```

脚本输出 `PASS` 的必要条件：

- 两轮各有至少 100 个 `receiveToEnginePercentileSamples`。
- 五插件轮的 P95 增幅不超过 `max(250us, 基线 P95 的 15%)`。
- 没有新增 bridge/preTick 失败，且日志中没有 GAMECOMMAND 被停止、bridge/preTick 异常或玩家无法操作的风险信号。
- 两轮均由真人确认可操作，且队列退化完成审阅。

`FAIL` 表示已发现性能或稳定性回归，先移除五插件/回退档位再排查。`BLOCKED` 表示自动指标合格但缺少真人操作或队列审阅证据，不能进入 Gate D 或发布。

## 当前边界

五个探针只覆盖后台命令、玩家命令、玩家事件、对局事件和调度器。它们不订阅或拦截 GAMECOMMAND，不访问 Headless 或原始网络对象，不创建裸线程。五插件轮的 callbacks 输出中，调度成功数应至少为 1；其余计数必须与实际执行的玩家命令、进房和开局动作一致。计数异常、事件丢弃或回调失败均为排查信号，但不替代 P95、队列审阅和真人可操作性判定。通过 Gate C 只能说明这组普通 V2 能力在该环境下没有观察到超阈值回归，不能证明任意第三方插件安全或低延迟。
