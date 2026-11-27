# Web、RCON 与数据接口

这份指南面向管理自己的 CNKD NEXT 实例的服主、监控和面板开发者。先核对目标实例的 JAR 与配置，普通单房间部署的 Web 和 RCON 默认都关。 [发布版本与附件核对](RELEASE-VERSIONS-CN.md)

| 能力 | 启用和授权 | 使用范围 |
| --- | --- | --- |
| Web 监听 | `network.webPort` | 用于已注册的 Web 路由。监听成功后仍需确认所需路由已注册 |
| RCON | `network.rconPort`，使用独立的 `network.rconPasswd` | 远程控制台，按管理员权限保护 |
| Relay 数据端点 | 读取 `data/RelayApiConfig.json` 中的开关和访问限制 | 使用 Relay 的部署按需接入 |

端口为 `0` 表示该独立入口关闭。启用时替换随包示例凭据，检查实际监听地址、防火墙和反向代理，优先仅从内网或受控 HTTPS 入口访问。保持未使用的混合协议入口关闭。修改网络监听项后完整重启，再用后台 `config check` 核对有效配置和风险提示。错误日志和问题截图中的 token、cookie、密码应删去。 [配置参考](CONFIGURATION-REFERENCE-CN.md)

启用了相应数据路由的 Relay 实例，可按以下口径接入。2.3.2 扁平包默认使用纯 `start` 模式，后台启动时跳过 Relay 专用路由注册。

| 请求 | 建议 |
| --- | --- |
| `GET /relay/status?limit=20` | 用作概览，普通面板可从 3–10 秒间隔开始 |
| `GET /relay/rooms?limit=100` | 用于房间详情。需要展示关闭房间时才加 `includeClosed=true` |
| `POST /relay/admin` | 使用独立的 `adminApiToken`，仅向管理网络开放，按部署配置启用 |

`X-Relay-Api-Cache` 可用来区分缓存命中。高频重复 `refresh=true` 会增加服务端构建数据的开销。用控制台 `api status` 观察缓存、限流与错误情况。接口返回 401/403 时，分别检查凭据及实际访问策略。超时或接口关闭时应显示数据不可用。

## 房间管理 API 的接入条件

1.1.24 宿主代码包含管理接口实现。**2.3.2 扁平包的默认插件清单没有启用 `HttpApi` 组件**，单独打开 `webPort` 后这些路由仍不可用。普通服主可在后台用 `gamestart status`、`startmode diagnose` 与 `version` 查询对应状态。

对于另行启用了 `HttpApi` 组件的兼容部署，管理工具须先获取有效会话。下表列出此宿主实现提供的接口。面向玩家的页面无需持有管理凭据。

| 请求 | 用途 |
| --- | --- |
| `GET /HttpApi/api/get/info/GameInfo` | 当前游戏选项、开局状态和玩家信息 |
| `GET /HttpApi/api/get/info/StartState` | 开局等待、剩余时间和诊断 |
| `GET /HttpApi/api/get/info/WorldState` | 房间代次、重置、返回战役室和全量快照就绪状态 |
| `GET /HttpApi/api/get/server-info` | 单房间和在线人数概要 |
| `POST /HttpApi/api/post/run/ClientCommand` | 通过 `runCommand` 提交受信任的游戏命令 |

提交 `{"runCommand":"start"}` 或 `{"runCommand":"startcancel"}` 时，HTTP 202 表示服务器受理请求。之后查询 `StartState` 并等待实际开局或取消的结果。HTTP 409 表示暂时无法执行，例如已在等待或没有可取消的倒计时。游戏线程快照暂不可取时可能返回 HTTP 503，面板应显示状态待确认并退避重试。

旧接口可能在外层状态 JSON 中包含再次编码的 JSON 字符串，客户端应按接口实际响应解析。`WorldState.clientWorldConsistency=unverified` 表示未取得所有客户端世界一致性的确认。排查重连还需记录客户端画面、房间阶段和出现问题的操作。 [指令参考](COMMAND-REFERENCE-CN.md)
