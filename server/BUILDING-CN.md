# 构建 CNKD NEXT 服务端

本目录提供 1.1.24 单房间宿主、插件 API、插件工具、构建脚本、测试和配置模板。公开 2.3.2 下载包使用 11 月 19 日的已验收构建，源码来源和版本记录见 [SOURCE-ORIGIN](SOURCE-ORIGIN.json)。

使用 JDK 17。在本地补齐有权使用的编译依赖，放到 `Server-Core/libs/`。宿主编译需要对应版本的 `game-lib.jar` 和 `slick.jar`，引擎相关测试另用 `wasm.jar`。游戏资源及引擎字节码由开发者从获授权的同版本环境提供。需要直接开服时可从 GitHub Release 下载完整包。

~~~sh
cd server
sh ./gradlew :Server-Plugin-API:jar :Plugin-Tool:jar :Server-All:jar
sh ./gradlew :Server-Core:test :Plugin-Tool:test
~~~

Windows 将 `sh ./gradlew` 改为 `.\gradlew.bat`。首次构建需要 Gradle 与依赖仓库可达。构建结果分别位于 `Server-Plugin-API/build/libs`、`Plugin-Tool/build/libs`、`Server-All/build/libs`。

运行核心完整测试前，将授权运行环境里的 `data/gameData/GameModularReusableClass.bin` 放到源码目录下的 `runtime-data/data/gameData/GameModularReusableClass.bin`。两项 Headless 类加载测试需要该文件。

要用自己构建的 JAR 联机验证，先准备已备份的隔离实例及配套运行资源，停服后替换 JAR，保留实例配置和插件锁，再按 [部署指南](../docs/DEPLOYMENT-GUIDE-CN.md) 验证。产物 Manifest 会记录源码修订和构建时间。
