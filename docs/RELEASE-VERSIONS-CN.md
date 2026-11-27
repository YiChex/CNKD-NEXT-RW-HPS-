# 发布版本与附件核对

GitHub Release 标签用于识别公开发行。服务端自身版本可在控制台 `version` 及附件中的 `RELEASE-INFO.json` 查询，插件兼容范围看实际 SDK 版本。

## GitHub 2.3.2

| 项目 | 值 |
| --- | --- |
| Release 标签 | `2.3.2` |
| 扁平 ZIP | `CNKD.NEXT.2.3.2.zip`（37,031,259 字节） |
| ZIP SHA-256 | `bed26984bcc30e8defe3a95e564dbdc1e57a8213f9873c0a13de520cab9d6b74` |
| 服务端 / 构建时间 | `1.1.24` / `2026-11-19 12:26:03` |
| `Server-All.jar` SHA-256 | `92950c42de90e4aced56a12a9975f3098d689e7d1e75b14a0aeec9ad93e85007` |
| 运行形态 | 单房间 `start`，`allow_all`，公共列表默认关闭 |
| 插件 API / EIRS | `1.3.0` / `identity.reporter` `1.1.0`（V1，随包批准锁） |

新版包含普通玩家进入及离开 S 队的修复，并把手动 Start 倒计时独立为 `manualStartCountdownSeconds`。支持 0–300 秒，默认 5 秒，`0` 为立即开局。旧配置缺少该字段时使用默认 5 秒。新版 Windows 脚本自带 Java 17 短临时目录处理。API 1.3.0 提供可选的只读统计接口。战报采集插件和报告服务需要独立配套安装。 [GitHub Release 2.3.2](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/tag/2.3.2)

发布附件的 `RELEASE-INFO.json` 与 JAR Manifest 记录宿主构建的来源。GitHub 自动生成的 Source code ZIP/TAR 对应本仓库文档标签，部署请选择 Assets 中的扁平 ZIP。

## GitHub 2.3.1

| 项目 | 已核对值 |
| --- | --- |
| Release 标签 | `2.3.1` |
| 公共附件 | `CNKD.NEXT.2.3.1.zip`（平铺 ZIP） |
| ZIP SHA-256 | `c5aa0ea2231bf4f7742649052c57e1486487b2e31478dc29f946561edc5e472f` |
| 服务端构建时间 | `2026-10-22 02:50:01`（JAR Manifest 的 `Build-Jar-Time`） |
| `Server-All.jar` SHA-256 | `44dd8132a8fa92431c80c069ffd25ef6d3c11ffc20e8dd3cafaedd1bc0fbc70f` |
| 运行形态 | 单房间 `start`，`allow_all`，公共列表默认关闭 |
| 插件 API / EIRS | `1.2.0` / `identity.reporter` `1.1.0`（V1，随包提供匹配的锁） |

历史数据基于 2026-11-27 核对的原附件。 [GitHub Release 2.3.1](https://github.com/YiChex/CNKD-NEXT-RW-HPS/releases/tag/2.3.1)

**2.3.1 的发布说明与原附件有差异。** 原附件仍为 10 月 22 日的内部 1.1.22 构建，当时的 `autoStartCountdownSeconds=5` 只服务于自动开局。需要手动倒计时和普通玩家 S 队修复的服主可升级到 2.3.2，并按上表核对指纹。

## 本地核对与回报

Linux：

```sh
sha256sum -c CNKD.NEXT.2.3.2.zip.sha256
sha256sum Server-All.jar
unzip -p Server-All.jar META-INF/MANIFEST.MF
```

Windows PowerShell：

```powershell
Get-FileHash -Algorithm SHA256 .\CNKD.NEXT.2.3.2.zip
Get-FileHash -Algorithm SHA256 .\Server-All.jar
```

如果官方附件的指纹与表格不同，请记录新指纹、下载时间和 Release 链接，再确认对应功能。反馈 S 队问题时请注明客户端版本、是否普通玩家、能否进 S / 退出 S、房间是否开了临时队伍锁。反馈倒计时时请注明用的是房主 Start、聊天指令还是自动开局。

[单房间部署指南](DEPLOYMENT-GUIDE-CN.md) · [配置参考](CONFIGURATION-REFERENCE-CN.md) · [插件开发指南](PLUGIN-DEVELOPMENT-GUIDE-CN.md)
