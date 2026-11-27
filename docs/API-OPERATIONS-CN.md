# Web、RCON 与数据接口

这份指南面向管理自己的 CNKD NEXT 实例的服主、监控和面板开发者。先核对目标实例的 JAR 与配置，普通单房间部署的 Web 和 RCON 默认都关。 [发布版本与附件核对](RELEASE-VERSIONS-CN.md)

| 能力 | 启用和授权 | 使用范围 |
| --- | --- | --- |
| Web 与管理 API | `network.webPort`，使用独立的 `network.webToken` | 管理面板、已认证接口 |
| RCON | `network.rconPort`，使用独立的 `network.rconPasswd` | 远程控制台，按管理员权限保护 |
| Relay 兼容数据端点 | 读取 `data/RelayApiConfig.json` 中的开关和访问限制 | 支持此能力的部署按需接入 |

端口为 `0` 表示该独立入口关闭。启用时替换随包示例凭据，检查实际监听地址、防火墙和反向代理，优先仅从内网或受控 HTTPS 入口访问。保持未使用的混合协议入口关闭。修改网络监听项后完整重启，再用后台 `config check` 核对有效配置和风险提示。错误日志和问题截图中的 token、cookie、密码应删去。 [配置参考](CONFIGURATION-REFERENCE-CN.md)

启用了相应数据路由的实例，可按以下口径接入。路由名中的 `relay` 来自兼容接口，具体可读数据取决于实例运行模式和配置。

| 请求 | 建议 |
| --- | --- |
| `GET /relay/status?limit=20` | 用作概览，普通面板可从 3–10 秒间隔开始 |
| `GET /relay/rooms?limit=100` | 用于房间详情。需要展示关闭房间时才加 `includeClosed=true` |
| `POST /relay/admin` | 使用独立的 `adminApiToken`，仅向管理网络开放，按部署配置启用 |

`X-Relay-Api-Cache` 可用来区分缓存命中。高频重复 `refresh=true` 会增加服务端构建数据的开销。用控制台 `api status` 观察缓存、限流与错误情况。接口返回 401/403 时，分别检查凭据及实际访问策略。超时或接口关闭时应显示数据不可用。

当前公开附件没有新版 `StartState` 和 `WorldState` 查询契约。对接管理工具时以目标构建实际提供的路由和响应为准。提交开局请求后，以房间运行状态和游戏客户端确认开局结果。 [指令参考](COMMAND-REFERENCE-CN.md)
