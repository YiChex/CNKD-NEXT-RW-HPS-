# 发布版本与附件核对

GitHub Release 标签、附件里的服务端构建和插件 API 各有自己的版本。下载、升级和反馈问题时，建议记录 Release 标签、附件文件名及 SHA-256。旧构建的控制台 `version` 可能只显示 `CNKD NEXT S`。

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

以上数据基于 2026-11-27 下载的附件。下载前可查看该版本的发布记录。 [GitHub Releases 2.3.1](https://github.com/YiChex/CNKD-NEXT-RW-HPS-/releases/tag/2.3.1)

**发布说明与附件有差异。** 发布说明列出了手动 Start 默认 5 秒和普通玩家自助观战修复，当前附件的 JAR 与 10 月 22 日的内部 1.1.22 构建一致。附件没有 `gameplay.manualStartCountdownSeconds` 的运行时实现，内置的 `autoStartCountdownSeconds=5` 只服务于自动开局。此附件也未包含随后统一 S 队进出及锁定处理的修复。若升级目标是这两项修复，请先核对目标附件的实际构建。

内部 1.1.23 构建包含手动倒计时（默认 5 秒，`manualStartCountdownSeconds` 支持 0–300 秒）、普通玩家 S 队进出修复和 Windows 启动脚本兼容修正。内部 1.1.24 候选在此基础上增加可选的赛后统计，宿主 SDK 升到 1.3.0。请使用明确配套的宿主、插件及 SDK。

## 本地核对与回报

Linux：

```sh
sha256sum CNKD.NEXT.2.3.1.zip
sha256sum Server-All.jar
unzip -p Server-All.jar META-INF/MANIFEST.MF
```

Windows PowerShell：

```powershell
Get-FileHash -Algorithm SHA256 .\CNKD.NEXT.2.3.1.zip
Get-FileHash -Algorithm SHA256 .\Server-All.jar
```

如果官方附件的指纹与表格不同，请记录新指纹、下载时间和 Release 链接，再确认对应功能。反馈 S 队问题时请注明客户端版本、是否普通玩家、能否进 S / 退出 S、房间是否开了临时队伍锁。反馈倒计时时请注明用的是房主 Start、聊天指令还是自动开局。

[单房间部署指南](DEPLOYMENT-GUIDE-CN.md) · [配置参考](CONFIGURATION-REFERENCE-CN.md) · [插件开发指南](PLUGIN-DEVELOPMENT-GUIDE-CN.md)
