# CNKD NEXT 插件锁审批与运维

适用范围：独立单房间 Start 服务端的外部 V1/V2 插件。内置插件不进入此锁。

## 1. 安全模型

`data/plugins/plugin-lock.json` 是外部插件的完整批准清单。服务端只加载同时满足以下条件的归档：

- `internalName`、`apiVersion`、`version` 和文件名与锁条目精确一致。
- 整个 JAR/ZIP 的 SHA-256 与锁条目一致。
- 根目录 `plugin.json` 原始字节的 SHA-256 与锁条目一致。
- 插件目录与锁是同一完整发布集：不存在未知归档、同 ID 冲突包或已批准但缺失的归档。
- 加载器在解析 V1 `imports.json` 或实例化主类前再次核对文件指纹。

锁文件缺失、损坏、重复 ID、无效归档、未知包、改名、改版本、改描述符或改归档内容时，整套外部插件都不会执行，直至目录与锁恢复为同一完整发布集。无效归档包含重复 ZIP 条目或根目录之外的 plugin.json。内置服务端插件和核心启动不依赖此锁。

插件锁证明“当前文件与人工批准时一致”，不证明插件安全。V1/V2 均与服务端运行在同一 JVM，批准前仍需审核来源和源码。

## 2. 首次批准

1. 停止服务端，确认进程已退出。
2. 将待批准的 JAR/ZIP 直接放入 `data/plugins/`，不要放入子目录。
3. 使用跨平台工具对整个目录生成矩阵并确认没有 FAIL。
4. 使用同一工具为目录中的全部外部归档生成完整锁。

```text
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar matrix data/plugins --server-api 1.2.0
java -jar plugin-sdk/tools/cnkd-plugin-tool.jar lock data/plugins --approved-by operator-name --force
```

工具不会执行插件代码；写锁前会重新核对归档和描述符哈希。任意损坏、不兼容、重复 ID、缺失/循环 V1 依赖都会返回退出码 2，现有锁保持不变。

Windows PowerShell 兼容流程仍可显式列出全部归档：

```powershell
powershell -ExecutionPolicy Bypass -File .\plugin-sdk\approve-plugin-lock.ps1 `
  -PluginDirectory .\data\plugins `
  -PluginArchives .\data\plugins\welcome.jar,.\data\plugins\audit.zip `
  -ApprovedBy "operator-name"
```

脚本只读取归档和 `plugin.json`，不加载类、不执行脚本、不解析 `imports.json`，也不访问网络。确认后，它在同一目录写入临时文件并原子替换 `plugin-lock.json`；已有锁会备份为 `plugin-lock.json.bak`。

自动化环境可在外部审批已经完成后使用 `-Force` 跳过 PowerShell 确认提示。`-Force` 不会放宽字段、路径或哈希检查。

## 3. 升级与回滚

升级时必须把“插件归档 + `plugin-lock.json`”视为一组发布资产：

1. 停服。
2. 替换归档，移除旧版本和未使用归档。
3. 对目录中计划启用的全部外部归档重新运行跨平台 `lock` 或 PowerShell 审批脚本。
4. 检查锁中数量、ID、版本、文件名和 SHA-256。
5. 启动后检查 `[PluginLock]` 日志和 `plugins [id]` 状态。

回滚时同时恢复旧归档和与其匹配的旧锁。只恢复其中一项会按设计拒绝插件。

## 4. 删除所有外部插件

停服后删除所有外部 JAR/ZIP，并把锁恢复为空清单：

```json
{
  "lockVersion": 1,
  "plugins": []
}
```

不要删除插件自己的 `data/plugins/data/<internalName>/` 数据，除非已经确认不需要回滚。

## 5. 故障定位

| 日志 | 含义 | 处理 |
| --- | --- | --- |
| `plugin lock is missing` | 有外部归档但无锁 | 停服后运行跨平台 `lock` 或审批脚本 |
| `plugin lock is invalid` | JSON、字段、时间、哈希或重复项无效 | 不要手改绕过；修复目录后重新生成锁 |
| `unapproved archives are present` | 目录出现未知归档 | 审核后生成完整审批，或移除该归档；整套外部插件在修复前均拒绝 |
| `invalid or unreadable archives` | 目录中存在损坏、超限、路径不安全或描述符不合格的 JAR/ZIP | 移除该归档，或修复后重新审批整个目录；修复前所有外部插件均拒绝 |
| `duplicate plugin internalName values` | 目录中有两个或以上归档声明同一 ID | 只保留经审批的那个归档，删除冲突包后再启动；不允许选择性加载 |
| `approval mismatch` | 身份、版本、文件名或哈希变化 | 检查来源；确认升级后重新审批 |
| `approved archives are absent` | 锁与目录不是同一完整发布集 | 恢复缺失归档或重新生成完整锁 |
| `SHA-256 changed after approval check` | 启动期间文件被替换或修改 | 立即停止实例，核查部署过程和文件来源 |

不要在服务端运行期间覆盖 JAR/ZIP 或锁文件。审批与部署都应在停服状态完成。
