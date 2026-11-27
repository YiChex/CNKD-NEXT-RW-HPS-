# CNKD NEXT 第三方插件 V2 接入说明

适用范围：CNKD NEXT 独立单房间 Start 服务端  
API 版本：`1.2.0`  
描述符版本：`apiVersion: 2`  
运行模式：受信任 JVM 插件、完整重启加载

> 使用者如果只需要部署服务端、配置房间或管理玩家，请先阅读 [`CNKD-NEXT-USER-GUIDE-CN.md`](CNKD-NEXT-USER-GUIDE-CN.md)。本页仅说明第三方插件开发者需要的 API、描述符、锁文件和安装流程。

## 1. 选择 V2

新插件应实现 `net.rwhps.server.plugin.api.CnkdPlugin`，只依赖独立的 `server-plugin-api-1.2.0.jar`。不要把 `Server-All.jar` 或 `Server-Core` 当作编译依赖。

Legacy V1 `Plugin` 子类仍然兼容，但会暴露服务端内部对象，二进制兼容性、资源回收和权限边界都弱于 V2。

## 2. 兼容模型

| 模型 | 入口 | 兼容承诺 | 建议用途 |
| --- | --- | --- | --- |
| Legacy V1 | 继承核心 `Plugin` | 保留现有加载能力，不承诺内部类二进制稳定 | 尚未迁移的历史插件 |
| V2 | 实现 API `CnkdPlugin` | 只对 `net.rwhps.server.plugin.api.*` 提供版本化契约 | 所有新插件 |
| 内置插件 | 与服务端一同构建 | 由服务端源码版本管理 | CNKD 核心功能 |

V2 会拒绝访问 `net.rwhps.server.*` 中 API 包以外的服务端类。插件私有依赖使用 child-first，API/JDK/Kotlin 使用 parent-first。生命周期、命令、事件和任务回调都会设置插件自己的线程上下文类加载器，兼容 `ServiceLoader` 和资源发现，回调结束后恢复服务端上下文。

这是一条 API 兼容边界，不是安全沙箱。插件仍与服务端运行在同一 JVM，并能使用 JDK 文件、网络、反射和进程能力；只能安装经过源码审计或来源可信、哈希确认的插件。

## 3. 环境

- 构建 JDK：推荐 17。
- 目标字节码：Java 11；Java 示例和 API 模块均使用 `options.release=11`。
- 服务端运行 JDK：支持当前发布要求的 Java 11/17 环境；插件归档内全部类必须以 Java 11 为目标。
- 编译 API：`net.cnkd.next:server-plugin-api:1.2.0`，或发布包 SDK 中的同版本 API JAR；sources/Javadoc JAR 可用于 IDE 跳转和离线查阅。
- 示例：`docs/plugin-sdk/java-minimal` 与 `docs/plugin-sdk/kotlin-minimal`。
- 描述符 Schema：`docs/plugin-sdk/plugin-v2.schema.json`。

Java 是稳定首选语言。Kotlin 模板固定为服务端同版本 Kotlin `2.0.0`；由于 Kotlin 运行时由服务端 parent-first 提供，Kotlin 插件必须使用随当前服务端 SDK 提供的模板编译，并在每个服务端 API 版本上做真实装载测试。不要自行升级 Kotlin 编译器后直接上线。

第三方项目最小依赖：

```kotlin
dependencies {
    compileOnly("net.cnkd.next:server-plugin-api:1.2.0")
}
```

API 必须是 `compileOnly`，不能把 CNKD API 类重复打进插件 JAR。

## 4. plugin.json

`plugin.json` 必须位于 JAR 根目录：

```json
{
  "apiVersion": 2,
  "name": "CNKD Welcome",
  "internalName": "example.welcome",
  "author": "Example Developer",
  "main": "example.cnkd.WelcomePlugin",
  "description": "Minimal V2 plugin",
  "version": "1.0.0",
  "supportedVersions": ">= 1.2.0",
  "capabilities": ["player-commands", "player-events", "read-state"]
}
```

字段规则：

| 字段 | 规则 |
| --- | --- |
| `apiVersion` | V2 固定为 `2` |
| `internalName` | 小写唯一 ID，匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`；发布后不要修改 |
| `main` | 实现 `CnkdPlugin` 的完整 Java 类名，必须有公共无参构造器 |
| `version` | SemVer，例如 `1.0.0` |
| `supportedVersions` | 服务端 API 范围，例如 `>= 1.2.0` 或 `[1.2.0,2.0.0)` |
| `capabilities` | 插件实际使用的能力，未声明时调用会失败 |

保留 ID：`hessserver`、`uplist`、`connectlimit`、`bindplayer`。V2 不允许 `import` 或 `imports.json`，ID 与 capability 都必须小写。

## 5. Capability

| capability | 允许能力 | 对低延迟链路的影响 |
| --- | --- | --- |
| `console-commands` | 注册后台控制台命令 | 无 GAMECOMMAND 热路径影响 |
| `player-commands` | 注册游戏内文本命令 | 仅处理聊天命令 |
| `player-events` | 玩家加入、离开、聊天只读通知 | 异步通知，不拦截操作 |
| `game-events` | 开局、结束只读通知 | 异步通知，不改变原版链路 |
| `broadcast` | 向单个玩家或全房间发送系统消息 | 消息最长 2000 字符 |
| `scheduler` | 一次性或周期任务 | 周期最小 100 ms，关闭时自动取消 |
| `read-state` | 房间、地图、模组和非敏感配置快照 | 同步复制轻量状态，不暂停游戏线程 |

首版不提供单位操作拦截、原始 Packet、Headless 引擎对象、管理员字段写入、Web 路由和裸线程 API。这样可以保持当前移动/建造 GAMECOMMAND 快路径，不重新引入“地图加载后无法操作”的风险。

## 6. 生命周期

```java
public final class MyPlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        // 注册命令、事件、任务和托管资源
    }

    @Override
    public void onReady() {
        // 全部启动注册阶段完成
    }

    @Override
    public void onDisable() {
        // 保存插件自身数据；所有 API 注册随后自动逆序清理
    }
}
```

- `onEnable`：可读取 `context.dataDirectory()`，注册能力。
- `onReady`：服务端插件启动阶段完成后调用。
- `onDisable`：正常关闭或插件启动失败回滚时最多调用一次。
- 任一启动阶段抛异常：插件进入 `FAILED`，命令、事件、任务和 `manage()` 资源逆序回收，其他第三方插件继续启动。
- 插件通过 `context.manage(AutoCloseable)` 注册的资源会自动关闭。
- 停用开始后，命令/事件/任务不再进入插件代码；此时新增托管资源会被立即关闭并抛出异常。
- 所有插件回调运行时，当前线程的 context classloader 指向插件 classloader；不要永久修改它。

不要在构造器中启动线程、打开端口或写入全局状态，因为构造器失败前还没有 PluginContext。

## 7. 命令

参数格式沿用服务端规则：

- 必填：`<player>`
- 可选：`[message]`
- 可变：`<message...>` 或 `[message...]`
- 可选参数后不能再出现必填参数。

```java
context.commands().registerPlayer(
    "hello",
    "[name]",
    "Greeting",
    invocation -> invocation.reply("Hello")
);
```

命令名大小写不敏感。与核心命令或其他插件命令冲突时，本插件注册失败，不覆盖既有命令。注册失败不会留下半注册定义；释放句柄只删除本插件实际安装的命令，不会误删后来的同名命令。回调异常会记录日志并向调用者返回简短失败信息。

## 8. 事件与线程

V2 事件对象和 `PlayerSnapshot` 都是只读快照。每个 V2 插件使用独立单线程事件执行器和默认 64 条有界队列；同一插件按提交顺序执行，慢插件不会占用其他插件的事件队列。队列满时只丢该插件通知，不执行 caller-runs，也绝不丢弃游戏操作包。

事件内禁止：

- 长时间阻塞或同步 HTTP 请求。
- `Thread.sleep`。
- 修改服务端内部对象。
- 假设回调线程就是引擎线程。

需要延迟或周期工作时使用 `context.scheduler()`。不要创建裸线程。

单次事件回调达到 100 ms 会计入慢回调。后台执行 `plugins health <internalName>` 可查看健康级别、队列深度/峰值/容量、丢弃、慢回调及命令/事件/调度失败。事件可能丢失，不能作为付费、权限、审计或胜负判定的唯一来源。

## 9. 玩家、消息与权限

`PlayerSnapshot` 提供：ID、名称、槽位、队伍、连接状态、存活状态、有效管理员权限、有效房主权限和超级管理员标记。

`adminAuthority()`/`hostAuthority()` 使用服务端统一策略计算，遵循“关闭普通房主权限后只有超级管理员保留权限”的 P7-34 规则。插件不得缓存并写回裸 `isAdmin`，也不得自行授予房主权限。

```java
String playerId = event.player().id();
boolean delivered = context.server().sendSystemMessage(playerId, "Welcome");
context.server().broadcastSystemMessage("Server notice");
```

以上两个消息 API 都要求 `broadcast` capability。`sendSystemMessage` 在玩家已离线或 Headless 尚未就绪时返回 `false`。

### 只读状态快照

声明 `read-state` 后可以读取：

```java
RoomSnapshot room = context.server().roomSnapshot();
MapSnapshot map = context.server().mapSnapshot();
List<ModSnapshot> mods = context.server().modSnapshots();
ServerConfigSnapshot config = context.server().configSnapshot();
```

- 房间阶段固定为 `STARTING/LOBBY/PLAYING/RETURNING/RESETTING`。
- 地图显示名保持原始玩家可识别名称；不返回地图源文件绝对路径。
- 模组只返回引擎已加载名称；没有可靠版本时不返回猜测版本。
- 配置只包含服务器名、人数/单位/聊天限制、AI、房主、自动开局、队伍/位置锁、锁图、低延迟和默认游戏选项。
- 密码、Token、授权、网络目标、端口和路径不会进入配置快照。

快照是调用瞬间的不可变副本，不是订阅事件，也不能用于修改房间。频繁调用仍会产生对象分配，建议在命令、秒级调度或事件处理中按需读取，不要每帧轮询。

## 10. 依赖打包

V2 使用插件私有 child-first 类加载，JDK、Kotlin 和 `net.rwhps.server.plugin.api.*` 保持 parent-first；其他 `net.rwhps.server.*` 核心类不可见。

- 第三方库应 shade 进插件 JAR，并建议 relocate 到插件自己的命名空间。
- 不得打包 `server-plugin-api` 或服务端核心类。Kotlin 插件默认使用服务端提供的 Kotlin 运行时；如第三方库冲突，应 shade/relocate 到插件私有包。
- 不支持 `imports.json` 或启动时 Maven 下载。
- 同一插件包上限 64 MB，单条目 32 MB，总展开 128 MB，最多 2048 个条目。
- `net.rwhps.server.plugin.api.*` 之外的核心类在 V2 运行时不可见；不要通过反射、字符串类名或复制核心类绕过。

## 11. 构建、离线校验和安装

开发者项目：

```powershell
.\gradlew.bat clean jar
```

检查 JAR 根目录：

```powershell
jar tf .\build\libs\your-plugin.jar | Select-String "plugin.json|YourPlugin.class"
```

优先运行跨平台校验器：

```text
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar validate build/libs/your-plugin.jar
```

Windows PowerShell 兼容入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\plugin-sdk\validate-plugin-v2.ps1 .\build\libs\your-plugin.jar
```

跨平台工具会检查归档边界、`plugin.json`、主类位置/字节码、ID、capability、`imports.json`、重复打包类和 ASM 字节码内部引用；也可生成 V1 迁移报告与多版本矩阵。它不会执行插件，不能代替源码审计、真实装载和玩家链路测试。完整用法见 `plugin-sdk/PLUGIN_TOOL_CLI_CN.md`。

安装：

1. 停止服务端。
2. 将 JAR 放入 `data/plugins/` 根目录。
3. 对完整目录生成兼容矩阵与批准锁：

```text
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar matrix data/plugins --server-api 1.2.0
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar lock data/plugins --approved-by operator-name --force
```

Windows PowerShell 兼容流程可以显式列出目录中本次允许的全部外部 JAR/ZIP：

```powershell
powershell -ExecutionPolicy Bypass -File .\plugin-sdk\approve-plugin-lock.ps1 `
  -PluginDirectory .\data\plugins `
  -PluginArchives .\data\plugins\your-plugin.jar `
  -ApprovedBy "operator-name"
```

4. 检查 `data/plugins/plugin-lock.json` 中的 ID、版本、文件名和 SHA-256。
5. 完整启动服务端。
6. 检查是否存在 `[PluginLock]` 拒绝日志；在后台执行 `plugins <internalName>`，确认插件为 `ACTIVE` 且 `failure` 为空。

锁文件缺失、损坏、未知包、已批准包缺失、文件改名或哈希变化都会在任何插件代码执行前拒绝外部插件。跨平台工具与审批脚本都只读归档、不执行插件代码；详细规则与回滚步骤见 `plugin-sdk/PLUGIN-LOCK-OPERATIONS-CN.md`，结构定义见 `plugin-sdk/plugin-lock.schema.json`。

插件数据位于 `data/plugins/data/<internalName>/`。不要把运行数据写进插件 JAR 或 `data/plugins/` 根目录。

## 12. API 版本、升级和卸载

- `supportedVersions` 使用服务端 API SemVer，不是产品显示名称。
- 精确版本：`= 1.2.0`；下限：`>= 1.2.0`；区间：`[1.2.0,2.0.0)`。未使用 1.2 新能力的旧插件可继续声明经过验证的 `>= 1.1.0`。
- API patch 版本只做兼容修复；minor 可添加成员；不兼容删除需要新的 API major。
- 插件应尽量声明经过验证的最小区间，不能直接写一个未实测的宽范围。

- 当前只支持完整重启升级/卸载，不支持生产热卸载。
- 升级前备份插件 JAR 和 `data/plugins/data/<internalName>/`。
- 升级和回滚时，插件 JAR/ZIP 与匹配的 `plugin-lock.json` 必须成对恢复。
- 保持 `internalName` 不变，通过 `version` 进行数据迁移。
- 回退时同时恢复兼容的数据目录备份。
- 不要在进程运行中覆盖正在加载的 JAR。

## 13. Legacy V1 迁移到 V2

| V1 写法 | V2 写法 |
| --- | --- |
| `class X : Plugin()` | `class X implements CnkdPlugin` |
| `pluginDataFileUtils` | `context.dataDirectory()` |
| 直接 `Log` | `context.logger()` |
| 直接 `PlayerHess` | `PlayerSnapshot` 与稳定 player ID |
| `registerCoreCommands` | `context.commands().registerConsole` |
| `registerServerClientCommands` | `context.commands().registerPlayer` |
| 注解式核心事件 | `context.events().subscribe` |
| `Threads.newTimedTask` | `context.scheduler()` |
| 手动清理资源 | `Registration.close()` 或 `context.manage()` |

迁移时先列出插件实际使用能力。若依赖单位对象、原始包、Web 路由、管理员写入或 GAMECOMMAND 拦截，当前 V2 没有对应稳定 API，不要退回反射绕过；应保留为已知 V1 插件或等待受控 API。

## 14. 故障排查

| 日志/状态 | 原因 | 处理 |
| --- | --- | --- |
| `Invalid plugin descriptor` | 字段、版本、capability 或入口格式错误 | 使用 JSON Schema 校验 |
| `must implement CnkdPlugin` | V2 主类接口错误 | 实现 `CnkdPlugin` 并提供公共无参构造器 |
| `must declare capability` | 代码使用了未声明能力 | 在 `plugin.json` 精确增加 capability |
| `conflicts with an existing command` | 命令重名 | 修改插件命令名，不覆盖核心命令 |
| `state=FAILED` | 某生命周期抛异常 | 查看 `failurePhase` 和 `failureMessage` |
| `notification dropped` | 插件后台通知队列饱和 | 缩短事件回调，迁移 IO 到外部队列 |
| `pluginHealth.level=WARN` | 存在慢回调或非致命问题 | 查看 `reasons` 和 `eventDurationUs`，缩短回调 |
| `pluginHealth.level=DEGRADED` | 事件丢弃、回调/调度或日志文件失败 | 暂停上线，按失败计数逐项修复 |
| `cannot use imports.json` | V2 使用旧依赖机制 | shade/relocate 依赖 |
| `cannot access CNKD server internals` | V2 引用了 API 之外的核心类 | 改用稳定 API 或暂时保留 V1 |
| `UnsupportedClassVersionError` | 插件字节码高于运行 Java | 设置 `options.release=11`/`jvmTarget=11` |
| `[PluginLock] plugin lock is missing` | 有外部归档但尚未批准 | 停服后显式运行 `approve-plugin-lock.ps1` |
| `[PluginLock] approval mismatch` | 文件名、版本、描述符或归档哈希变化 | 核查来源；确认变更后重新生成完整锁 |
| `[PluginLock] approved archives are absent` | 锁与插件目录不是同一发布集 | 恢复缺失文件或重新生成完整锁 |
| `pluginDiagnostics` | 归档未进入运行态列表 | 执行 `plugins diagnose` 或查看 `plugin-diagnostics.txt` |

开发迭代不必每次重新生成锁。停服后使用 `-Dcnkd.plugins.mode=development` 和独立 `-Dcnkd.plugins.directory=<目录>` 启动开发实例；该模式只绕过审批锁匹配，结构、描述符、版本、类加载和 Java 11 字节码校验仍然有效。完整命令、隔离目录和生产切换流程见 `plugin-sdk/PLUGIN_DEVELOPMENT_RUNTIME_CN.md`。

## 15. 发布前自检

- 只依赖 `Server-Plugin-API`。
- `plugin.json` 通过 Schema。
- API 使用的每项 capability 均已声明。
- JAR 不包含 `net/rwhps/server/plugin/api/` 类。
- JAR 不引用 API 包之外的 `net.rwhps.server.*` 类型。
- 发布集中的全部外部插件都在 `plugin-lock.json` 中精确批准，且锁中没有缺失归档。
- 无裸线程、无限队列、同步网络请求和静态服务端对象引用。
- `onDisable` 可重复面对部分初始化状态。
- 开局后移动、建造、ping、聊天、投降、GameOver 和返回战役室链路均实测正常。
- 后台 `plugins` 显示 `ACTIVE`，退出无资源清理错误。

当前 V2 已完成服务端源码编译、核心回归测试和 Java 最小模板归档校验；本轮未重建 `Server-All.jar`，也未完成新的真实第三方插件装载和玩家全链路验收。正式发布前仍需完成对应 P8-08/Gate C/D/E 门禁。
