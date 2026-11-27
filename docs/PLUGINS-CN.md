# CNKD NEXT 插件目录

从对应插件仓库的 Releases 下载 JAR 和 SHA-256 文件。使用前核对插件 ID、宿主版本和所需能力。新装或换版时停服备份，对实例里全部外部插件重新生成批准锁。 [插件安装与开发](PLUGIN-DEVELOPMENT-GUIDE-CN.md)

| 插件 | 功能 | 宿主 | 源码与下载 |
| --- | --- | --- | --- |
| 最小示例 `example.minimal` | 一个玩家命令 `.hello`，另含 Java 和 Kotlin 模板 | 公开 2.3.2，API 1.3.0 | [源码](https://github.com/YiChex/cnkd-next-plugin-examples) · [Releases](https://github.com/YiChex/cnkd-next-plugin-examples/releases) |
| EIRS `identity.reporter` | 联机身份上报与封禁同步 | 公开 2.3.2 已预装新版适配器 | [源码](https://github.com/YiChex/cnkd-plugin-identity-reporter) · [Releases](https://github.com/YiChex/cnkd-plugin-identity-reporter/releases) |
| 战报 `cnkd.match.analytics` | 采集结算数据，独立报告服务提供战报 | 公开 2.3.2，要求 `read-statistics` | [源码](https://github.com/YiChex/cnkd-match-analytics) · [Releases](https://github.com/YiChex/cnkd-match-analytics/releases) |
| 称号与颜色 `cnkd.single-room-titles` | 自定义昵称、称号、名单和聊天颜色 | 11 月 23 日配套宿主，要求 `player-presentation` | [源码](https://github.com/YiChex/cnkd-next-single-room-titles) · [Releases](https://github.com/YiChex/cnkd-next-single-room-titles/releases) |

战报宿主和称号宿主的历史 SDK 都标为 1.3.0，请按表格核对能力。称号 Release 提供配套宿主、插件和工具，适合独立实例测试。两种宿主能力的合并与联合验收属于后续开发。
