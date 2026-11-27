# CNKD NEXT 跨平台插件工具

`cnkd-plugin-tool.jar` 是 CNKD NEXT 单房间服务端的跨平台插件校验、迁移审计、兼容矩阵和生产锁工具。它只读取插件归档，不实例化主类、不执行脚本、不下载依赖，也不连接服务端。

## 1. 运行要求

- Java 11 或更高版本。
- 使用当前服务端发布包中同一份 `plugin-sdk/`，不要混用不同版本工具。
- 校验和锁生成都应在服务端停止后执行。

Linux/macOS：

```sh
chmod +x ./plugin-sdk/cnkd-plugin-tool.sh
./plugin-sdk/cnkd-plugin-tool.sh version
```

Windows：

```bat
plugin-sdk\cnkd-plugin-tool.bat version
```

也可以直接执行：

```text
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar version
```

## 2. 单包校验

```sh
./plugin-sdk/cnkd-plugin-tool.sh validate ./data/plugins/example.jar
```

校验内容包括：

- JAR/ZIP、条目数、单条目和总展开大小。
- 路径穿越、重复条目、嵌套 `plugin.json` 和 UTF-8/JSON 字段。
- 插件 ID、版本、API 范围、capability、主类和 Java 11 字节码。
- V2 打包服务端/API 类、`imports.json` 和内部类引用。
- 归档 SHA-256 和 `plugin.json` SHA-256。

`validate` 返回 PASS/WARN/FAIL。WARN 代表可继续审计但不能忽略，例如 Legacy V1 或裸线程引用。

## 3. 详细检查与迁移

```sh
./plugin-sdk/cnkd-plugin-tool.sh inspect ./data/plugins/legacy.jar
./plugin-sdk/cnkd-plugin-tool.sh migrate ./data/plugins/legacy.jar --json ./legacy-migration.json
```

迁移扫描使用 ASM 读取字节码结构，可识别：

- `Plugin`、`CommandHandler`、Legacy 事件和生命周期方法。
- 全局 `Log`、`FileUtils`、`PlayerHess` 和 `Threads`。
- `imports.json`、裸线程/执行器和反射内部类名。
- 当前 V2 尚未开放的网络、Headless、单位或内部实现引用。

迁移结果：

| 状态 | 含义 |
| --- | --- |
| `ALREADY_V2` | 已使用 V2 API |
| `MAPPABLE` | 当前稳定 API 足以逐项迁移 |
| `BLOCKED_API_GAP` | 依赖尚未开放的内部能力，应保留受审计 V1 或提出 API 需求 |
| `MANUAL_REWRITE` | JavaScript 等需要人工重写 |
| `BLOCKED_INVALID_ARCHIVE` | 归档或描述符必须先修复 |

工具不会自动重写字节码或源码，也不会用反射绕过缺失 API。

## 4. 目录兼容矩阵

```sh
./plugin-sdk/cnkd-plugin-tool.sh matrix ./data/plugins \
  --server-api 1.2.0,1.1.0 \
  --json ./plugin-matrix.json \
  --force
```

第一个 API 版本是本次部署目标，决定 PASS/FAIL；后续版本用于跨版本对照。矩阵还检查：

- 重复 `internalName`。
- V1 插件依赖缺失、同名歧义和依赖循环。
- 每个插件在各 API 版本上的兼容布尔值。
- Java/Kotlin V2、Legacy V1、损坏包和迁移阻断数量。

`plugin-lock.json`、JSON 报告和非 JAR/ZIP 文件不会被当作插件扫描。

## 5. 生成生产锁

```sh
./plugin-sdk/cnkd-plugin-tool.sh lock ./data/plugins \
  --approved-by "reviewer-name" \
  --server-api 1.2.0 \
  --force
```

`lock` 总是重新扫描目录中的全部 JAR/ZIP：

- 任意 FAIL 都返回退出码 2，不修改现有锁。
- WARN 可以写锁，但服主必须先确认风险。
- 写入前重新计算归档和描述符哈希，防止检查后替换。
- 锁只能写在所检查插件目录的直接子级。
- 使用临时文件、磁盘 flush 和原子替换；旧锁备份为 `plugin-lock.json.bak`。

自定义输出文件仍必须位于同一插件目录：

```sh
./plugin-sdk/cnkd-plugin-tool.sh lock ./data/plugins \
  --approved-by "reviewer-name" \
  --output plugin-lock.next.json
```

## 6. JSON 报告

`validate`、`inspect`、`migrate`、`matrix` 和 `lock` 都支持：

```text
--json <path>    写入机器可读报告
--force          允许替换已存在的报告或锁
--server-api     指定目标 API；matrix 可使用逗号分隔多个版本
```

报告包含文件哈希、描述符、类版本、引用集合、问题代码、修复建议、迁移动作和阻断引用。不要把报告中的插件源码结构当作公开信息直接发布。

## 7. 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 校验通过或仅有 WARN |
| `2` | 包/矩阵校验失败，锁未写入 |
| `64` | 命令或参数错误 |
| `70` | 工具内部或文件系统错误 |

生产部署后仍需执行 `plugins`、`plugins health [id]`、`pluginlog status` 和真实玩家 Gate C/D。静态工具不能证明第三方代码安全，也不能替代移动、建造、投降、结算和下一局恢复验证。

## 8. API ABI 门禁

扫描 API JAR：

```sh
./plugin-sdk/cnkd-plugin-tool.sh api-scan \
  ./plugin-sdk/server-plugin-api-1.2.0.jar \
  --json ./api-surface.json \
  --force
```

与历史基线比较：

```sh
./plugin-sdk/cnkd-plugin-tool.sh api-compare \
  ./plugin-sdk/api-baselines/server-plugin-api-1.1.0.json \
  ./plugin-sdk/server-plugin-api-1.2.0.jar \
  --expected-change minor \
  --json ./api-compatibility.json \
  --force
```

门禁规则：

- 删除类/字段/方法、收窄可见性、改变 JVM 描述符或新增抽象接口方法属于破坏性变更。
- 新增类、默认接口方法或普通方法属于增量变更。
- 泛型签名、异常声明和常量变化单独告警。
- 破坏性变更必须提升 major；新增公共 API 必须提升 minor；公共面变化但版本不变会失败。
- `ApiVersions.SERVER_API` 的正常版本常量变化记为 INFO。

报告中的 `binaryCompatible` 表示旧插件二进制是否仍能链接；`releaseAllowed` 表示候选版本号是否满足 SemVer 门禁。major 版本可以允许破坏性发布，但此时 `binaryCompatible=false`，不会被描述为向后兼容。ABI 报告绑定基线和候选 JAR 的 SHA-256，不能复用旧 PASS 报告替代新构建。

当前正式基线来自历史 1.1.0 发布 SDK，不依赖旧服务端源码。

## 9. SDK 资产清单

```sh
./plugin-sdk/cnkd-plugin-tool.sh sdk-manifest \
  ./plugin-sdk \
  --json ./plugin-sdk/sdk-manifest.json \
  --force
```

清单检查 API 主 JAR、sources/Javadoc、工具、文档、Schema、启动器、ABI 基线/报告、Java/Kotlin 模板及模板 API 副本。缺失资产返回 FAIL；API 副本哈希不一致、ABI 报告哈希过期或工具版本不一致返回 WARN。发布任务要求严格 PASS，WARN 也会阻断发布。
