# CNKD NEXT Gate C 插件对照资产

本目录只生成 P8-08 Gate C 的五个普通 V2 对照插件，不是发布包输入，也不能部署到生产目录。

五个插件分别覆盖后台命令、玩家命令、玩家事件、对局事件和调度器。它们不订阅或拦截 GAMECOMMAND，不访问 Headless/网络内部对象，也不创建裸线程。

从源码根目录执行：

```powershell
& .\gradlew.bat -p .\tools\plugin-gatec-fixtures buildFixtures --no-daemon --max-workers=1 --console=plain
```

`buildFixtures` 会验证每个归档恰好有一个根 `plugin.json`，且只包含描述符声明的一个主类；归档启用可复现顺序和时间戳设置，相同输入复构应得到相同 SHA-256。产物位于 `tools/plugin-gatec-fixtures/build/libs/`。每个 JAR 都要先通过 `docs/plugin-sdk/validate-plugin-v2.ps1`，然后只复制到隔离测试目录，并由 `approve-plugin-lock.ps1` 为该完整五包集合生成锁。

Gate C 对照顺序：空插件基线一轮，然后五包同环境一轮。每轮先执行 `lowlatency apply test` 与 `lowlatency reset`，真实操作至少 60 秒且 `receiveToEnginePercentileSamples >= 100`。不得将这些 JAR 或测试锁放入默认 `runtime-data/data/plugins`。

完整的人工操作步骤和日志判定见 `docs/plugin-sdk/PLUGIN_GATE_C_PLAYBOOK_CN.md`。
