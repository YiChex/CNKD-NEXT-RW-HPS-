# CNKD NEXT 插件开发测试运行时

本文面向需要反复编译和调试插件的开发者。它只适用于单房间 Start 服务端，不能用于排位服务端或公网生产实例。

## 1. 两种运行模式

### 生产模式

默认模式，不需要任何 JVM 参数。服务端会要求 `data/plugins/plugin-lock.json` 与当前全部插件归档完全一致：

```text
cnkd.plugins.mode=production
cnkd.plugins.lockRequired=true
```

插件文件、描述符、版本、文件名或哈希发生变化时，服务端拒绝整套外部插件，必须停服后重新生成完整锁。

### 开发测试模式

开发模式必须显式启用，只对当前进程有效：

```powershell
java `
  -Dcnkd.plugins.mode=development `
  -Dcnkd.plugins.directory="D:\CNKD-Plugin-Dev\data\plugins" `
  -jar .\Server-All.jar
```

也可以使用环境变量：

```powershell
$env:CNKD_PLUGINS_MODE = "development"
$env:CNKD_PLUGINS_DIRECTORY = "D:\CNKD-Plugin-Dev\data\plugins"
java -jar .\Server-All.jar
```

建议为每个插件项目建立独立目录。相对目录以服务端 JAR 所在目录为基准；生产模式会忽略自定义插件目录参数并始终使用 `data/plugins`。

开发模式的行为边界：

- 跳过 `plugin-lock.json` 的审批哈希匹配，修改插件后无需重复生成锁。
- 仍执行归档格式、路径穿越、重复条目、大小、根目录 `plugin.json`、版本范围、重复 ID、主类、JVM 字节码和 V2 内部类门禁。
- 仍在实例化主类前和插件加载前核对归档文件指纹，避免读取过程中替换文件。
- 仍要求完整重启；不提供生产热加载或热卸载。
- 启动日志会输出 `DEVELOPMENT mode` 警告；该模式不得用于公网服务或发布包。

开发模式不是 Java 安全沙箱。V1/V2 插件都与服务端运行在同一个 JVM 中，仍必须审阅源码和第三方依赖。V2 插件应只依赖 `Server-Plugin-API`，不要反射访问服务端内部类。

## 2. 推荐开发循环

1. 从同一服务端发布包的 `plugin-sdk/` 复制 Java 或 Kotlin 模板。
2. 用 `compileOnly` 引用同版本 `Server-Plugin-API.jar`，目标字节码保持 Java 11。
   使用房间、地图、模组或安全配置快照时，声明 `read-state` 且 `supportedVersions` 至少为 `>= 1.2.0`。
3. 编译归档并执行 `validate-plugin-v2.ps1`。
4. 将归档放入开发模式指定的插件目录，删除旧同 ID 归档。
5. 重启开发实例，执行：

```text
plugins
plugins your.plugin.id
plugins diagnose
plugins health your.plugin.id
pluginlog status
```

6. 根据 `plugins diagnose` 和插件目录中的 `plugin-diagnostics.txt` 修复问题。
7. 只有准备进入生产时，才复制到生产实例并停服生成完整 `plugin-lock.json`。

开发模式推荐目录结构：

```text
D:\CNKD-Plugin-Dev\
  Server-All.jar
  data\
    plugins\
      your-plugin.jar
      plugin-diagnostics.txt
```

不要把开发目录的 `plugin-lock.json`、示例 JAR、测试夹具或调试数据复制到正式发布包。

## 3. 诊断输出

`plugins diagnose` 展示当前进程发现阶段和加载阶段记录；可按归档文件名过滤：

```text
plugins diagnose
plugins diagnose your-plugin.jar
```

常见阶段和处理方向：

| 阶段 | 含义 | 处理方向 |
| --- | --- | --- |
| `archive` | ZIP/JAR 结构、大小或路径不合规 | 重新打包并清理重复条目 |
| `descriptor` | `plugin.json` 字段或保留 ID 不合规 | 修正根目录描述符 |
| `compatibility` | `supportedVersions` 不覆盖当前 API | 使用实际验证过的 API 范围 |
| `contents` | V2 包含 `imports.json`、内部类或缺主类 | 移除服务端/API 类并 shade/relocate 依赖 |
| `authorization` | 生产锁缺失、不匹配或集合不一致 | 开发模式可迭代；生产重新审批全集 |
| `fingerprint` | 文件在检查后发生变化 | 停服核对部署和文件来源 |
| `load` | 主类实例化或依赖加载失败 | 检查公开无参构造器、依赖和主类实现 |
| `dependency` | V1 依赖缺失或循环 | 检查 `import` 名称和顺序；V2 使用 shade/relocate |

诊断文件是有界文本报告，最多保留当前进程的 256 条记录，便于直接提交给插件开发者；它不包含密码、令牌或玩家聊天内容。

`plugins health [internalName]` 用于运行期回调健康：

- `HEALTHY`：未观察到失败、丢弃或慢回调。
- `WARN`：存在慢事件回调、命令失败或日志队列丢弃。
- `DEGRADED`：存在事件丢弃、事件回调失败、调度失败或日志文件失败。
- `FAILED`：插件生命周期已经失败。

`eventQueue=current/max/capacity` 默认容量为 64。队列峰值持续接近容量时，应缩短回调并将网络/磁盘工作移出事件处理；不要通过扩大无界队列掩盖阻塞。

## 4. 生产切换

生产部署时：

1. 停止开发实例并确认 Java 进程退出。
2. 将候选归档复制到生产 `data/plugins/`。
3. 对完整插件集合运行 `approve-plugin-lock.ps1`。
4. 启动后执行 `plugins`、`plugins <id>` 和 `pluginlog status`。
5. 保留发布时的 JAR 哈希、锁文件和启动日志。

生产模式启动示例：

```powershell
java -jar .\Server-All.jar
```

插件 API 的稳定范围仍是 `net.rwhps.server.plugin.api.*`。原始网络包、Hess/Headless 对象、单位对象、GAMECOMMAND 拦截、任意 Web 路由、任意文件系统和热卸载不属于兼容承诺。
