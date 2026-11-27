# CNKD NEXT Plugin API 变更日志

## 1.3.0 - 赛后统计只读桥

- 增加 read-statistics capability、PluginContext.statistics() 默认扩展、PluginStatistics及不可变StatisticsRecord。
- 单一采集者通过有界批次拉取，写盘后ACK；无插件时不采集。游戏线程只运行宿主原生复制，不执行第三方回调或I/O。
- SESSION固定名单；FRAME保存原生公式/六计数；TERMINAL在结算/清理前冻结，对局代次和序号不复用。
- 1.2.0→1.3.0兼容门禁：breaking=0，additive=3。产品版本与插件API版本分轴。

## 兼容规则

- API patch：允许修复实现和文档，不删除或改变现有公共成员语义。
- API minor：允许添加公共类型、方法、事件和 capability；旧插件应继续装载。
- API major：允许不兼容变更，必须由插件通过 `supportedVersions` 明确适配。
- `net.rwhps.server.plugin.api.*` 之外的服务端类型不属于兼容承诺。
- 当前所有 V2 插件均为受信任、同 JVM、完整重启加载，不提供安全沙箱或生产热卸载。
- Java 是首要稳定契约；Kotlin 模板使用服务端同版本编译器，并需要逐服务端版本装载验证。

## 1.1.0 - 源码候选

首次 V2 API 候选：

- `CnkdPlugin` 与 `PluginContext` 生命周期。
- 控制台命令、玩家命令和精确注销句柄。
- 玩家加入、离开、聊天以及游戏开始、结束的不可变异步通知。
- 不可变 `PlayerSnapshot`、房间玩家快照、单玩家消息和全房间广播。
- 插件私有数据目录、结构化日志、受控一次性/周期调度。
- capability 声明、V2 独立类加载、TCCL 回调边界和资源逆序回收。
- Java/Kotlin 最小模板、JSON Schema 和 PowerShell 离线校验器。

验证状态（2026-08-04）：

- `Server-Plugin-API:build`、`Server-Core:test` 和 `Server-All:jar` 已完成；`Server-Core:test` 报告为 218 tests、0 failures、0 errors、16 skipped。
- Legacy V1、Java V2、Kotlin V2 示例均已在隔离副本完成锁审批、装载和 `ACTIVE` 状态验证。
- SDK 中的 Java/Kotlin 最小模板已使用显式 `-PcnkdPluginApiJar` 路径实际构建，两个产物均通过 V2 归档校验且各自只包含一个根 `plugin.json`。
- 五个不接触 GAMECOMMAND 的普通 V2 Gate C 对照插件已完成可复现构建、离线校验和隔离装载；具体证据见 `../PLUGIN_SYSTEM_DEVELOPMENT_WORKFLOW_CN.md` 第 17 节。
- 当前仍是源码候选契约，不是公开稳定发布：Gate C 尚缺真实玩家同环境性能对照，Gate D 玩家全链路和 Gate E 发布验收尚未完成。不得据此宣称任意第三方插件可安全上线或可长期运营。

## 1.1.0 - 2026-08-30 兼容性与开发测试增量

- 新增显式 development 运行模式和独立插件目录参数，生产模式的完整锁校验保持不变。
- 新增 `plugins diagnose [文件名]` 与 `plugin-diagnostics.txt` 的有界阶段化诊断。
- V2 归档内全部类的字节码上限统一为 Java 11，离线校验器与服务端运行时规则一致。
- 新增开发运行时说明和启动脚本；这些内容不扩大稳定 API，不提供热加载、原始包或内部引擎访问。
- 验证：当时 `Server-Core:test` 为 336 tests、0 failures、0 errors、16 skipped；真实第三方插件与玩家链路仍需独立验收。

## 1.2.0 - 只读状态快照

- 新增 `read-state` capability。
- 新增 `RoomSnapshot`/`RoomPhase`，提供房间阶段、人数、AI、观战和返回/重置状态。
- 新增 `MapSnapshot`/`MapKind`，提供地图显示名、类型、模组来源标记和非绝对运行标识。
- 新增 `ModSnapshot`，只返回引擎已确认加载的模组名称，不伪造缺失版本。
- 新增 `ServerConfigSnapshot`，通过类型化白名单返回非敏感配置，不包含密码、令牌、授权、网络目标或路径。
- 所有快照按调用即时复制，不进入 GAMECOMMAND、不暂停游戏线程、不提供任何状态写入。
- 验证：`Server-Core:test` 为 341 tests、0 failures、0 errors、16 skipped；API build/Javadoc/sources JAR 与 `Server-All.jar` 均成功生成。
- Java/Kotlin 1.2.0 最小模板均真实编译为 Java 11 字节码并通过 V2 归档校验。

### 2026-08-31 事件隔离运行时增量

- V2 事件从共享 `OTHER_THREAD` 迁移为每插件独立单线程执行器和 64 条有界队列。
- 移除队列满时 caller-runs 行为；只丢当前插件通知，不在网络/游戏发布线程执行插件代码。
- 同一插件保持事件提交顺序，不同插件的慢回调互不占用事件队列。
- 新增队列深度/峰值/容量、事件耗时、慢回调和健康级别指标，以及 `plugins health [id]`。
- Scope 关闭时中断事件工作线程并丢弃/计数尚未执行的通知，不提供生产热卸载承诺。
- 完整回归为 348 tests、0 failures、0 errors、16 skipped；双插件隔离启动中 `plugins health` 均为 `ACTIVE/HEALTHY`。

### 2026-08-31 SDK Tool 1.0.0

- 新增 Java 11 跨平台 `cnkd-plugin-tool.jar`，公共 API 版本仍为 1.2.0。
- 提供单包校验/检查、V1 迁移扫描、多版本目录矩阵和完整生产锁生成。
- 使用 Gson 解析结构化 JSON、ASM 扫描类引用，不加载或执行插件代码。
- PowerShell 校验/审批脚本继续保留，Windows/Linux/macOS 推荐统一使用 CLI。
- P8-18 阶段工具 10 项测试和服务端 348 项核心测试全部通过；CLI 生产锁已完成 V1/Java V2/Kotlin V2 三包实际加载互操作。
- P8-18 阶段工具 JAR 快照为 Java 11、484619 bytes，SHA-256 `39DF7CB609951F2B592C5A7149EE17186FD4D59A0CEA4E9D8FF0E5F746E8C794`。

### 2026-08-31 P8-19 ABI 与发布元数据

- 新增 `api-scan` 和 `api-compare`，以 ASM 建立公开/受保护 JVM API 表面和 SemVer 变更门禁。
- 仓库保存真实历史 1.1.0 API 基线，当前 1.2.0 比较结果为 0 breaking、10 additive、0 warning、1 info。
- 新增 `sdk-manifest` 和 Schema；发布任务自动记录 SDK 资产路径、大小与 SHA-256，并验证 API/工具/模板/文档/基线完整性。
- 1.1.0→1.2.0 使用 `--expected-change minor` 通过；故意声明 `patch` 返回退出码 2。
- ABI 报告绑定基线/候选 SHA-256，并区分 `binaryCompatible` 与 `releaseAllowed`；SDK 同时发布 API、sources 和 Javadoc JAR。
- 策略违规计数独立于最多 4096 条的报告明细，截断边界之后的违规也会阻止发布；对应回归测试覆盖 4097 条变更。
- SDK 清单要求 26 项资产完整，并拒绝模板 `.gradle/.kotlin/build` 开发缓存；最终结果为 PASS、0 缺失、0 警告。
- 最终 `Plugin-Tool:test` 为 18/0/0/0，`Server-Core:test` 为 348 tests、0 failures、0 errors、16 skipped。
- 最终工具 JAR 为 520739 bytes，SHA-256 `894245BBB3E7E41676A76B23CFC7984BCCFB908B93E1D969D8FD4CDA587D837C`；API JAR SHA-256 为 `63EE07D6D65262B4CD24CE41368C38A0A0963F4DD5FFBAD5D54219E0E549AE9F`。
- 历史 1.1.0 Java/Kotlin V2 示例在当前生产锁模式中均为 ACTIVE/HEALTHY，证明 1.1.0→1.2.0 的实际二进制加载兼容。基础能力至此收口；第三方插件仍需逐包完成准入和玩家链路验证。
