# CNKD NEXT V2 插件开发指南

## 正式发布边界

本指南随 CNKD NEXT 单房间正式发布包中的 `plugin-sdk/` 一起提供。该目录包含与当前包匹配的 API、sources/Javadoc JAR、Java/Kotlin 最小模板、描述符 Schema、跨平台工具、ABI 基线/报告和批准锁工具；开发时必须使用同一发布包中的 SDK，不要混用不同服务端版本资产。

本次正式发布的是单房间服务端和受控 V2 开发接口，不是允许任意代码直接进入生产环境的公共脚本市场：

- 发布包不预装、不自动下载、也不会默认批准任何第三方插件。
- 外部插件只能在完整重启时加载，必须先通过归档校验、来源审计和 `plugin-lock.json` 批准。
- V2 只开放命令、只读事件、快照、受控消息和托管调度；不开放原始包、单位操作拦截、Headless/Hess 内部对象或热卸载。
- `2026-08-04` 的核心回归基线为 229 项测试通过。该结果验证 API、归档和生命周期的代码约束，不替代候选插件在同配置下的真实玩家、移动、建造、地图标点、投降和结算验收。

## 1. 适用范围与当前状态

本指南只适用于独立单房间 Start 服务端的 V2 Java/Kotlin 插件。当前 API 版本为 `1.3.0`，公共契约仅限 `net.rwhps.server.plugin.api.*`。

Gate B 已完成构建、锁审批和隔离装载验证。Gate C 仍缺少真实玩家的同环境性能对照，Gate D/E 尚未完成。因此可以用于受控开发、代码审计和隔离测试，不能据此宣称公共插件生态已可长期运营。

V2 插件是受信任的同 JVM 代码，不是安全沙箱。批准锁只能证明归档未在审批后变化，不能替代源码审计、依赖审计和行为测试。

开发者需要反复改包时，可以使用 [开发测试运行时](PLUGIN_DEVELOPMENT_RUNTIME_CN.md) 的显式 `development` 模式和独立插件目录。该模式只跳过 `plugin-lock.json` 审批匹配，仍保留所有归档、描述符、API、类加载边界和 JVM 字节码校验；默认生产模式和正式发布包行为不变。

## 2. 开发与构建边界

- 使用 Java 11 或更高版本工具链编译并以 Java 11 字节码为目标。Java 模板使用 `options.release = 11`，Kotlin 模板使用 `JvmTarget.JVM_11`；V2 校验器会拒绝归档内任何高于 Java 11 的 `.class`，以保证跨 JDK 11/17 运行一致。
- 只使用 `compileOnly` 依赖 `server-plugin-api-1.3.0.jar`。不得将 API JAR、`Server-Core`、`Server-All` 或 `net.rwhps.server.*` 内部类打进插件。
- 私有第三方依赖可以随插件打包，但应 shade/relocate，避免与其他插件或服务端类冲突。不要在启动时下载 Maven 依赖。
- 插件主类必须有公开无参构造器；构造器不得启动线程、打开网络、读写外部状态或注册资源。
- `plugin.json` 必须位于 JAR 根目录且只出现一次。V2 不得包含 `imports.json`。

V2 类加载器对 `java.*`、Kotlin 和 API 包使用 parent-first，对插件私有类使用 child-first；任何 `net.rwhps.server.*` 非 API 类型都会被拒绝。反射、原始游戏对象、Headless、Hess、原始包和 Relay 内部接口都不是兼容 API。

## 3. plugin.json

最小描述符示例：

```json
{
  "apiVersion": 2,
  "name": "Example Plugin",
  "internalName": "example.plugin",
  "author": "Example Developer",
  "main": "example.plugin.ExamplePlugin",
  "description": "Short description",
  "version": "1.0.0",
  "supportedVersions": ">= 1.3.0",
  "capabilities": ["player-commands", "player-events", "read-state"]
}
```

`internalName` 必须小写，长度不超过 64，且在整个插件集内唯一。`version` 使用 SemVer；`supportedVersions` 只声明已实际验证的 API 范围。完整字段约束见 [plugin-v2.schema.json](plugin-v2.schema.json)。

能力必须精确小写，不能为未来能力占位：

| capability | 可用 API | 回调与限制 |
| --- | --- | --- |
| `console-commands` | `commands().registerConsole` | 同步控制台命令；名称不能和已有命令冲突 |
| `player-commands` | `commands().registerPlayer` | 同步玩家命令；客户端通常以 `.命令名` 调用，注册名称不带 `.` |
| `player-events` | 玩家加入、离开、聊天事件 | 只读、异步、可在队列压力下丢弃，不保证顺序或送达 |
| `game-events` | 游戏开始、结束事件 | 只读、异步、可在队列压力下丢弃，不保证顺序或送达 |
| `broadcast` | 单玩家系统消息、全房间系统消息 | 文本不能为空，最多 2000 字符；不能写入游戏状态 |
| `scheduler` | 一次性和周期任务 | 周期最小 100 ms；任务必须短小且可随时取消 |
| `read-state` | 房间、地图、模组和安全配置快照 | 同步生成轻量不可变副本；不开放路径、密码、令牌或可变引擎对象 |

未声明能力时，对应 API 会抛出 `IllegalStateException`。不要通过捕获该异常绕过描述符声明，应该修正描述符并重新校验、审批和部署。

## 4. 生命周期与资源所有权

服务端按以下顺序驱动 V2 插件：

1. 创建主类实例。
2. 调用 `onEnable(context)`。
3. 按声明能力挂接控制台命令、玩家命令和事件桥。
4. 调用 `onReady()`。
5. 正常停服或启动回滚时，先停止新注册，再调用一次 `onDisable()`，最后按注册的逆序关闭资源。

所有命令、事件订阅和调度任务都会自动归属到当前插件 scope。`Registration.close()` 可提前且幂等地取消单项注册；插件停用时会再次安全清理。需要管理额外 `AutoCloseable` 时使用 `context.manage(resource)`，不要尝试自行实现热卸载。

推荐只在 `onEnable` 注册命令、订阅、任务和托管资源；`onReady` 仅用于轻量初始化日志或已注册能力的就绪检查。`onDisable` 执行前插件已不再接受新资源，必须只做快速、幂等的本地清理。

```java
import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PlayerSnapshot;
import net.rwhps.server.plugin.api.PluginContext;

public final class ExamplePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.commands().registerPlayer("hello", "", "Send a greeting", invocation ->
            invocation.reply("Hello, " + invocation.player().map(PlayerSnapshot::name).orElse("player"))
        );
    }

    @Override
    public void onDisable() {
        // Close only resources not already handed to context.manage(...).
    }
}
```

## 5. 命令、事件与线程

### 命令

命令回调处在请求处理路径上，应立即返回。禁止在其中执行网络 I/O、阻塞磁盘 I/O、长循环、等待锁、反射扫描或批量遍历游戏状态。参数列表和 `CommandInvocation.player()` 快照不可变；控制台命令没有玩家，调用方必须处理 `Optional` 为空。

同一插件中命令名大小写不敏感且不能重复；与服务端或其他插件已有命令冲突时注册失败。回调异常会被记录并向调用方返回失败文本，但不能依赖异常作为流程控制。

### 事件

事件是不可变通知，不是拦截器：它们不改变 GAMECOMMAND、单位操作、建造、投降或网络包路径。每个 V2 插件拥有独立单线程有界事件队列，默认容量 64；同一插件内按提交顺序执行，不同插件之间互不占用事件队列。队列满时只丢弃该插件的新通知，绝不在发布者线程执行回调，也不会丢游戏操作包。

事件仍不是可靠消息总线。插件必须容忍通知缺失和延迟，不能把事件当作计费、权限、持久审计或对局裁决的唯一来源。单次回调达到 100 ms 会进入慢回调指标；持续阻塞会填满该插件自己的队列并使健康状态降级，但不会挤占其他插件的事件容量。

事件监听器异常会被记录并隔离。单个事件订阅失败不应使服务端或其他插件退出，但插件自身必须避免持续抛错和无界日志。

### 调度任务

调度器自动携带插件资源归属与类加载器上下文。周期任务间隔不得低于 100 ms；建议使用秒级周期，并将每次执行限制为轻量、无阻塞、可中断的工作。任务异常会被记录和隔离，不能用于重试风暴或后台爬取。

不要创建裸 `Thread`、`ExecutorService`、协程全局作用域或不受控定时器。它们无法被插件 scope 回收，是内存泄漏、停服卡顿和实例无法恢复的高风险来源。

## 6. 服务端快照与消息

`PluginServer` 暴露产品/API 版本、对局是否开始、玩家快照、房间/地图/模组/安全配置快照、单玩家消息和广播。`PlayerSnapshot` 是只读副本，包含稳定 ID、名称、槽位、队伍、连接/存活状态和只读权限快照；它没有任何写入游戏、分配权限或修改单位的能力。

`players()` 返回不可变列表。调用时房间可能已变化，因此插件必须在每次操作前处理玩家离线、ID 不存在和 `sendSystemMessage` 返回 `false`。消息/广播要求 `broadcast` capability，服务端会将文本截断到 2000 字符。

`read-state` capability 提供：

- `roomSnapshot()`：`STARTING/LOBBY/PLAYING/RETURNING/RESETTING`、人数、AI、观战和返回/重置状态。
- `mapSnapshot()`：玩家可识别显示名、内置/自定义/存档类型、模组地图标记和非绝对运行标识。
- `modSnapshots()`：引擎已确认加载的模组名称；引擎没有可靠版本时不会伪造版本。
- `configSnapshot()`：服务器名、人数/单位/聊天限制、AI、房主、自动开局、队伍/位置锁、锁图、低延迟、初始资金、禁核默认值和迷雾默认值。

配置快照采用代码白名单，明确不包含密码、Token、授权字段、跳转目标、网络端口或文件路径。快照只代表调用瞬间，插件不得缓存后将其当作权限或对局裁决依据。

插件私有状态只能放在 `context.dataDirectory()` 下，并使用原子写入、有限文件大小和可恢复格式。不得假设数据目录可与其他插件共享，也不得以路径遍历访问服务端配置、地图、回放或其他插件数据。

## 7. 异常、日志与兼容

使用 `context.logger()` 记录带插件 ID 的结构化信息。正常拒绝应返回可读消息或记录 `warn`，意外异常才记录 `error(message, throwable)`。不要吞掉异常后继续写入不一致状态。

### 独立日志运行约定

V2 的 `context.logger().info/warn/error` 走服务端独立日志链路。默认情况下，`INFO`、`WARN` 和 `ERROR` 会输出到控制台，并异步写入 `data/log/plugins/<internalName>.log`。它不受 `common.log=WARN` 的普通过滤影响，因此插件作者和服主不应为了查看 V2 日志把全局日志长期提高到 `TRACK`。

服主可在 `ServerConfig.json` 根级 `pluginLogging` 中分别控制控制台和文件阈值、单插件 INFO 限流、单条长度、轮转大小和保留天数。配置在下次完整重启后生效；运行时可输入 `pluginlog status` 查看当前有效配置、限流、截断、队列和文件写入指标。`common.log=OFF` 仍会关闭普通 V2 日志，带 `Throwable` 的 `error` 保留错误输出。

日志不是高频数据通道。INFO 会按单插件每分钟上限被抑制，过长内容会截断，文件写入通过有界后台队列完成。不要在每帧、每个单位、网络包或紧密循环中写日志；需要聚合诊断时应按时间窗口汇总后再输出。

Legacy V1 插件直接调用 `Log.info/debug/track` 时，仍完全遵循全局 `common.log`，没有插件级分流或留存承诺。新开发和维护中的插件应使用 V2 `context.logger()`；不要通过服务端内部 `Log` 类、调用栈识别或反射绕过该边界。

API patch 只修复实现或文档；minor 可以新增类型/能力；major 才允许破坏性改动。插件必须根据 [API-CHANGELOG-CN.md](API-CHANGELOG-CN.md) 更新 `supportedVersions`，并在每个服务端候选版本重新执行隔离装载。Java 是首要兼容契约；Kotlin 插件还需要验证 Kotlin 标准库与服务端运行时兼容性。

不支持或未承诺的能力包括：Web 路由、保存回调、服务注册、生产热卸载、JavaScript 公共插件、原始包观察/拦截、GAMECOMMAND 观察/拦截、Headless/Hess/单位对象访问、直接管理员字段写入和原始线程。

## 8. 构建、校验与部署

模板默认读取 `libs/server-plugin-api-1.3.0.jar`。在当前源码树中可不复制二进制，改为传入 API JAR 的绝对路径；打包后的独立 SDK 则应把同版本 API JAR 放入模板 `libs/`。构建后优先按以下跨平台流程执行：

```powershell
$apiJar = (Resolve-Path .\Server-Plugin-API\build\libs\Server-Plugin-API.jar).Path
& .\gradlew.bat "-PcnkdPluginApiJar=$apiJar" -p .\docs\plugin-sdk\java-minimal `
  clean jar --no-daemon --max-workers=1
java -jar .\Plugin-Tool\build\libs\cnkd-plugin-tool.jar validate `
  .\docs\plugin-sdk\java-minimal\build\libs\cnkd-next-welcome-plugin-1.0.0.jar
```

发布包中的工具固定为 `plugin-sdk/tools/cnkd-plugin-tool.jar`。完整的 `inspect/migrate/matrix/lock` 用法见 [PLUGIN_TOOL_CLI_CN.md](PLUGIN_TOOL_CLI_CN.md)。

部署必须在停服状态完成。将所有计划启用的外部归档直接放入隔离副本的 `data/plugins/`，为整个集合生成锁，不要只为新增包生成局部锁：

```text
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar lock data/plugins --approved-by reviewer --force
```

Windows PowerShell 兼容流程：

```powershell
& .\docs\plugin-sdk\approve-plugin-lock.ps1 `
  -PluginDirectory .\data\plugins `
  -PluginArchives .\data\plugins\your-plugin.jar `
  -ApprovedBy "reviewer" `
  -Force
```

多插件场景必须在 `-PluginArchives` 中列出全部启用归档。启动后执行 `plugins` 和 `plugins your.plugin.id`，确认 `ACTIVE`、capability、最后阶段和失败摘要。升级或回滚时必须同时替换归档和匹配的 `plugin-lock.json`，详见 [PLUGIN-LOCK-OPERATIONS-CN.md](PLUGIN-LOCK-OPERATIONS-CN.md)。

启动后的 V2 plugins id 输出同时包含生命周期最后阶段和服务端持有的 callbacks 计数。后者用于证明命令、事件或调度回调是否发生及是否失败/丢弃；它不是性能结论，也不替代 Gate C 的玩家操作和延迟对照。

发现或加载失败的归档不会因为未进入运行态列表而丢失诊断。使用 `plugins diagnose` 或查看插件目录下的 `plugin-diagnostics.txt`，可按 `archive`、`descriptor`、`compatibility`、`contents`、`authorization`、`fingerprint`、`load` 和 `dependency` 阶段定位修复方向。

运行期使用 `plugins health [internalName]` 查看 V2 插件健康。输出包含 `HEALTHY/WARN/DEGRADED/FAILED`、队列当前/峰值/容量、事件丢弃、慢回调、命令/事件/调度失败及最后回调；`ACTIVE` 只说明生命周期已启动，不等于运行健康。

## 9. 验收清单

1. 编译目标为 Java 11；JAR 没有 API/服务端内部类、`imports.json` 或额外根 `plugin.json`。
2. 描述符通过校验，声明能力与代码调用逐项一致。
3. 全部依赖已审计并在隔离副本生成完整锁。
4. 隔离启动后插件为 `ACTIVE`；测试命令、事件和任务异常不会阻断服务端退出。
5. 涉及玩家命令、事件或周期任务时，完成空插件与候选插件的 Gate C 同环境对照，保存日志和 `gatec-assessment.json`。
6. Gate D 真实玩家验证覆盖进房、开局、移动、建造、ping、聊天、玩家命令、投降、结算、返回战役室、断线重连和下一局恢复。
7. Gate C/D 未通过前，插件只能作为隔离候选，不得进入公共发布包。

当前可复现的普通能力对照夹具位于 `tools/plugin-gatec-fixtures`；它们只用于 Gate C 测量，不应作为生产插件示例或默认部署内容。
