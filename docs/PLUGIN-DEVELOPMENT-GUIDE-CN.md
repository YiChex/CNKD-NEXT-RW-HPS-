# CNKD NEXT V2 插件开发指南

本页面向希望为 CNKD NEXT 单房间 `start` 服务端开发插件的开发者。V2 插件与服务端运行在同一个 JVM 中，属于受信任代码，不是安全沙箱；开发者必须完成源码、依赖和行为审计。

## 1. 兼容边界

- 当前 V2 API 版本：`1.1.0`。
- 插件公共依赖只允许 `net.rwhps.server.plugin.api.*`。
- 使用发布包内 `plugin-sdk/` 的 API JAR，不要混用其他版本。
- 插件只能在完整重启时加载；当前不承诺生产热卸载。
- 插件必须使用公开无参构造器，不要在构造器中启动线程或执行网络/磁盘阻塞操作。
- 插件不能访问 `Server-Core`、Headless、Hess、原始网络包、单位对象或 `net.rwhps.server.*` 内部类。

V2 插件归档必须通过发布包中的 `validate-plugin-v2.ps1`，并通过 `plugin-lock.json` 审批后才适合放入实例的 `data/plugins`。

## 2. `plugin.json`

描述符必须位于 JAR 根目录且只出现一次：

```json
{
  "apiVersion": 2,
  "name": "Example Plugin",
  "internalName": "example.plugin",
  "author": "Example Developer",
  "main": "example.plugin.ExamplePlugin",
  "description": "A small CNKD NEXT plugin",
  "version": "1.0.0",
  "supportedVersions": ">= 1.1.0",
  "capabilities": ["player-commands", "player-events"]
}
```

约束：

- `internalName` 必须全小写、全局唯一，长度不超过 64。
- `capabilities` 必须使用已支持的小写 ID，不能重复或预留未实现能力。
- `supportedVersions` 只声明实际验证过的 API 范围。
- V2 不使用 `imports.json`，不能把 API JAR 或服务端类打进插件。

## 3. 能力清单

| 能力 | 可使用的 API | 说明 |
|:---|:---|:---|
| `console-commands` | `commands().registerConsole` | 注册后台指令 |
| `player-commands` | `commands().registerPlayer` | 注册游戏内指令，名称不带 `.` |
| `player-events` | 玩家加入、离开、聊天事件 | 只读异步通知，可能延迟、乱序或丢弃 |
| `game-events` | 对局开始、结束事件 | 只读异步通知，不能裁决对局 |
| `broadcast` | 单人或全房间系统消息 | 文本最多 2000 字符 |
| `scheduler` | 一次性和周期任务 | 周期最小 100 ms，任务必须短小 |

未在描述符声明的能力，调用时会失败。不要通过捕获异常绕过能力检查；修改描述符后必须重新校验和审批。

## 4. 最小 Java 插件

```java
package example.plugin;

import net.rwhps.server.plugin.api.CnkdPlugin;
import net.rwhps.server.plugin.api.PluginContext;

public final class ExamplePlugin implements CnkdPlugin {
    @Override
    public void onEnable(PluginContext context) {
        context.commands().registerPlayer(
            "hello", "", "Send a greeting", invocation ->
                invocation.reply("Hello from CNKD NEXT")
        );
    }

    @Override
    public void onDisable() {
        // Close only resources not already handed to context.manage(...).
    }
}
```

建议只在 `onEnable` 注册命令、事件和任务；`onReady` 只做轻量就绪处理；`onDisable` 必须快速、幂等。通过 `context.manage(resource)` 托管额外的 `AutoCloseable`，不要创建无法回收的裸线程。

## 5. 线程和性能要求

命令回调处在请求路径上，禁止执行：

- 网络 I/O、阻塞磁盘 I/O 或等待锁。
- 无界循环、大量遍历玩家/地图/单位。
- 反射扫描、调用服务端内部类或直接处理原始包。
- 每帧、每个单位或每个网络包写日志。

事件是不可变观察通知，不是拦截器；插件不能依赖事件作为权限、计费或对局裁决的唯一来源。事件压力过高时允许丢弃，插件应容忍缺失、重复、延迟和乱序。

不要创建裸 `Thread`、`ExecutorService`、全局协程作用域或不受控定时器。服务端停止时无法回收这些资源，容易造成内存泄漏和下次实例启动异常。

## 6. 日志和数据

使用 `context.logger()`，不要直接调用服务端内部 `Log` 类：

```text
data/log/plugins/<internalName>.log
```

V2 日志受根配置中的 `pluginLogging` 控制：

```json
{
  "pluginLogging": {
    "consoleLevel": "INFO",
    "fileLevel": "INFO",
    "infoLinesPerMinutePerPlugin": 120,
    "maxMessageLength": 2048,
    "maxFileSizeMb": 8,
    "retainDays": 7
  }
}
```

`pluginlog status` 可查看有效阈值、限流、截断、队列和文件写入状态。Legacy V1 插件直接调用全局日志 API 时仍受 `common.log` 影响。

插件私有数据只能写入 `context.dataDirectory()`，应使用原子写入、有限文件大小和可恢复格式，不要访问其他插件或服务端配置目录。

## 7. 构建、校验和审批

发布包中的 SDK 模板默认包含 `server-plugin-api-1.1.0.jar`。源码开发时只使用 `compileOnly` 依赖：

```powershell
$apiJar = (Resolve-Path .\Server-Plugin-API\build\libs\Server-Plugin-API.jar).Path
& .\gradlew.bat -p .\plugin-sdk\java-minimal `
  "-PcnkdPluginApiJar=$apiJar" clean jar --no-daemon --max-workers=1
& .\plugin-sdk\validate-plugin-v2.ps1 `
  -PluginJar .\plugin-sdk\java-minimal\build\libs\cnkd-next-welcome-plugin-1.0.0.jar
```

在隔离实例中完成审批：

```powershell
& .\plugin-sdk\approve-plugin-lock.ps1 `
  -PluginDirectory .\data\plugins `
  -PluginArchives .\data\plugins\your-plugin.jar `
  -ApprovedBy "reviewer" -Force
```

多插件实例必须将全部启用归档一次性纳入锁文件。升级或回滚时同时替换插件归档和匹配的 `plugin-lock.json`。启动后执行：

```text
plugins
plugins your.plugin.id
pluginlog status
```

确认状态为 `ACTIVE`，并检查能力、生命周期阶段和失败摘要。

## 8. 验收门槛

进入公开发布前至少完成：

1. 描述符校验、依赖审计和完整锁审批。
2. 隔离实例启动、停服和重复启动。
3. 插件命令、事件和调度任务异常不会阻断服务端。
4. 同配置空插件/候选插件对照，确认没有明显延迟和资源回归。
5. 涉及玩家体验时覆盖进房、开局、移动、建造、地图标点、聊天、投降、结算、战役室返回、断线重连和下一局。

插件系统兼容性不等于插件业务逻辑正确。任何涉及原始引擎对象、网络包、单位操作或权限写入的插件都超出 V2 公共契约，应视为内部定制开发并单独维护。
