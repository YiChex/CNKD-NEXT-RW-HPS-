# CNKD NEXT 服务端 (现已免授权 请下载最新版本或2.0.5以上版本)

> 面向 `Rusted Warfare` 纯 `start` 模式的独立服务端工作区  
> 同时包含服务端发布包、可构建源码与配套授权站

## 项目简介

`CNKD NEXT` 是围绕 `RW-HPS` 的纯 `start` 模式长期独立优化后的服务端版本。  
它的目标不是简单“能运行”，而是提供一套更适合正式开服、长期维护、多人运营和低门槛部署的独立方案。

当前仓库同时包含三类内容：

- `standalone-build/`
  主开发工作区，包含完整可构建源码
- `releases/`
  历次构建后的服务端发布包
- `auth-site-releases/`
  服务端授权站发布包

## 核心特点

### 纯 `start` 模式独立优化

- 聚焦 Headless `start` 模式，不再把主精力放在 Relay 运行形态上
- 更适合做公开服、团战服、生存服、FFA 房间服
- 结构更清晰，便于长期维护与持续迭代

### 配置集中化

当前服务端主要使用两份配置文件：

- `data/ServerConfig.json`
- `data/RelayLicenseConfig.json`

相比早期多文件分散配置，维护成本更低，服主理解更直观。

### 三种玩法完整支持

当前版本已稳定支持：

- 标准团战
- FFA 混战
- 生存地图模式

其中：

- 团战模式支持奇偶位固定分阵营
- FFA 模式支持每位玩家独立队伍
- 生存模式支持 2 号位保留系统 AI，其余真人固定为 A 队

### 自动开局链路统一

当前版本统一了以下三条开局路径：

- 聊天命令开始
- 界面按钮开始
- 自动倒计时开始

它们现在共用同一套开始前校验逻辑，显著减少“表面开局、实际未开始”这类问题。

### 列表开放逻辑强化

当前版本支持：

- 启动后自动上列表
- 游戏开始后保留列表展示
- 对局中状态显示为 `ingame`
- 开局后取消置顶但不从列表消失
- 回到大厅后恢复等待状态与置顶效果

### 房间默认参数可配置

当前版本支持直接配置：

- 默认开局资金
- 是否禁用核弹
- 默认迷雾模式：`off / basic / lineOfSight`
- 自动开局人数
- 自动开局倒计时
- 列表置顶字符数量

### 结算与重置链路强化

- 游戏结束后可恢复控制台提示
- 胜负判定后统一 1 分钟自动重置
- 生存模式下不会再因为保留 AI 占位导致房间无法重置

### 更克制的资源占用

当前版本已针对纯 `start` 模式做了多轮运行资源优化，包括：

- 更低的默认启动内存参数
- 更克制的 Quartz 调度线程数
- 更精简的核心线程池
- 部分空闲资源改为懒加载

## 仓库结构

```text
Start-Mode-Source-Project/
├─ README.md
├─ README-CN.md
├─ THREAD-HANDOFF-CN.md
├─ source/
│  ├─ Server-Core/
│  └─ Server-All/
├─ standalone-build/
│  ├─ ASM-Framework/
│  ├─ TimeTaskQuartz/
│  ├─ Server-Core/
│  ├─ Server-All/
│  ├─ runtime-data/
│  ├─ gradlew
│  └─ gradlew.bat
├─ releases/
│  └─ 历次 Linux 服务端部署包
└─ auth-site-releases/
   └─ 授权站发布包
```

## 目录说明

### `standalone-build/`

当前最重要的工作目录。  
如果你要继续开发、修复问题、重新打包，请优先进入这个目录。

推荐命令：

```powershell
./gradlew.bat :Server-Core:compileKotlin
./gradlew.bat test
./gradlew.bat :Server-All:jar
./gradlew.bat :Server-All:packageStartModeDeployZip
```

### `releases/`

这里保存已经构建完成的服务端发布包，可直接部署使用。

### `auth-site-releases/`

这里保存授权站的发布包，用于搭建授权服务。

## 最新发布物

适合直接部署的服务端包位于：

- `releases/CNKD-Start-Mode-Linux-Deploy-YYYYMMDD-HHMMSS/`
- 对应的 `.zip` 文件

如果仓库中包含 GitHub 发布素材目录，也可直接使用其中整理好的包和文档进行对外发布。

## 快速部署

### Linux 服务端

1. 安装 Java 17 或更高版本
2. 解压发布包到目标目录
3. 修改：
   - `data/ServerConfig.json`
   - `data/RelayLicenseConfig.json`
4. 赋予脚本执行权限
5. 启动服务端

常规启动：

```sh
./start.sh
```

低内存启动：

```sh
./start-lowmem.sh
```

## 关键配置文件

### `data/ServerConfig.json`

这是主配置文件，用于控制：

- 基础启动参数
- 网络监听
- 房间人数和自动开局
- 大厅规则
- 地图锁定
- 默认房间参数

### `data/RelayLicenseConfig.json`

这是授权配置文件，用于控制：

- 授权站地址
- 授权码
- 租约刷新周期
- 宽限期
- 请求的授权功能列表

### `配置文件设置帮助.txt`

当前版本发布包中已附带：

- `配置文件设置帮助.txt`

其中对这两个配置文件的各项参数都做了逐条说明，适合直接发给用户或服主管理员阅读。

## 配套授权站

仓库中提供授权站发布包，用于签发服务端授权租约。

授权站特点：

- SQLite 持久化
- 管理后台
- 批量生成授权码
- 授权码唯一绑定服务端 UUID
- 登录 / 安装 / 租约接口限流
- 安全响应头与会话加固

## 授权说明

CNKD NEXT 服务端相关授权：

**全部免费**

如需获取授权，请联系：

- QQ：`475407353`

## 支持方式

- CNKD 官方帮助QQ群：`1030157691`
- 官方帮助邮箱：`475407353@qq.com`

## 适合哪些场景

- 公开服 / 列表服
- 团战匹配服
- FFA 混战服
- 生存地图服
- 低内存 Linux 环境
- 希望长期稳定维护的个人服 / 小团队服

## 当前开发建议

如果后续继续在本仓库推进开发，推荐优先关注：

- 纯 `start` 模式链路继续剥离与精简
- 配置文件中文注释与文档继续优化
- 实服验证后的细节问题回收
- 授权站源码工程化整理

## 免责声明

本项目基于 `RW-HPS` 相关能力进行整理与扩展。  
请仅在合法、合规、合理的使用场景中使用本项目及相关发布包。
