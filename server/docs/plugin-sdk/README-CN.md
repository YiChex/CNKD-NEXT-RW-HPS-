# CNKD NEXT 插件 SDK

本目录是单房间 Start 服务端 V2 插件的唯一开发入口。V2 面向受信任的 Java/Kotlin 插件，插件与服务端同 JVM 运行，必须停服部署并重启加载。

当前 API 版本为 `1.3.0`。Gate B 的构建、锁审批和隔离装载已通过；真实玩家性能与完整体验门禁尚未完成，因此它是候选开发契约，不是公共生产插件平台发布承诺。

## 从这里开始

1. 阅读 [开发者指南](PLUGIN_DEVELOPMENT_GUIDE_CN.md)，确认能力边界、线程模型和不支持的接口。
2. 复制 [Java 最小模板](java-minimal) 或 [Kotlin 最小模板](kotlin-minimal)，只依赖 `net.rwhps.server.plugin.api.*`。
3. 将 API JAR 放到模板 `libs/server-plugin-api-1.3.0.jar`，或在构建时传入 `-PcnkdPluginApiJar=<绝对路径>`。SDK 同时提供 sources 与 Javadoc JAR；不要引用服务端 fat JAR。
4. 按 [V2 描述符 Schema](plugin-v2.schema.json) 编写根目录 `plugin.json`，能力声明必须与代码调用一致。
5. 构建 JAR 后优先使用 [跨平台插件工具](PLUGIN_TOOL_CLI_CN.md) 执行 `validate`；PowerShell 校验器继续作为 Windows 兼容入口。V2 不得打包 API 或服务端内部类，也不得包含 `imports.json`。
6. 反复开发时按 [开发测试运行时](PLUGIN_DEVELOPMENT_RUNTIME_CN.md) 使用独立目录和显式 development JVM 参数；生产环境必须使用跨平台工具 `lock` 或 PowerShell 审批脚本生成完整锁。启动后检查 `plugins [id]`、`plugins health [id]`、`plugins diagnose` 和 `pluginlog status`。
7. 涉及普通事件、命令或调度器的候选插件，按 [Gate C 对照手册](PLUGIN_GATE_C_PLAYBOOK_CN.md) 完成同环境玩家性能对照；未通过前不得进入发布包。

离线 V2 校验器会拒绝 API/服务端内部类、imports.json、重复 ZIP 条目和任何嵌套 plugin.json。

## 文档索引

| 文档 | 用途 |
| --- | --- |
| [PLUGIN_DEVELOPMENT_GUIDE_CN.md](PLUGIN_DEVELOPMENT_GUIDE_CN.md) | 生命周期、能力、线程、异常、测试和禁区 |
| [API-CHANGELOG-CN.md](API-CHANGELOG-CN.md) | API 版本和兼容承诺 |
| [api-baselines/server-plugin-api-1.1.0.json](api-baselines/server-plugin-api-1.1.0.json) | ABI 兼容基线 |
| [sdk-manifest.schema.json](sdk-manifest.schema.json) | 发布 SDK 清单格式 |
| `server-plugin-api-1.3.0-sources.jar` | API 源码归档 |
| `server-plugin-api-1.3.0-javadoc.jar` | API Javadoc 归档 |
| [plugin-v2.schema.json](plugin-v2.schema.json) | `plugin.json` 机器可读约束 |
| [PLUGIN_TOOL_CLI_CN.md](PLUGIN_TOOL_CLI_CN.md) | Windows/Linux/macOS 校验、迁移、矩阵和锁工具 |
| [cnkd-plugin-tool.sh](cnkd-plugin-tool.sh) / [cnkd-plugin-tool.bat](cnkd-plugin-tool.bat) | 跨平台 CLI 启动器 |
| [validate-plugin-v2.ps1](validate-plugin-v2.ps1) | V2 JAR 离线校验 |
| [PLUGIN-LOCK-OPERATIONS-CN.md](PLUGIN-LOCK-OPERATIONS-CN.md) | 锁审批、升级、回滚和故障处理 |
| [PLUGIN_DEVELOPMENT_RUNTIME_CN.md](PLUGIN_DEVELOPMENT_RUNTIME_CN.md) | 隔离开发模式、启动参数和失败诊断 |
| [approve-plugin-lock.ps1](approve-plugin-lock.ps1) | 完整插件集锁生成工具 |
| [start-plugin-development.ps1](start-plugin-development.ps1) | 启动独立开发测试实例 |
| [prepare-plugin-gatec-runtimes.ps1](prepare-plugin-gatec-runtimes.ps1) | 创建不可覆盖的空插件/五插件 Gate C 隔离副本 |
| [PLUGIN_GATE_C_PLAYBOOK_CN.md](PLUGIN_GATE_C_PLAYBOOK_CN.md) | 性能对照与 Gate C 判定 |

不要将示例 JAR、Gate C 夹具或测试锁复制到默认 `runtime-data/data/plugins`。默认锁为空清单，生产候选插件必须经过独立审核、隔离装载和完整锁审批。

正式发布包会自动生成 `sdk-manifest.json`，记录 SDK 资产的相对路径、大小和 SHA-256。发布前必须使用 `api-compare` 验证当前 API 与基线兼容，并使用 `sdk-manifest` 验证资产无缺失或哈希漂移。
