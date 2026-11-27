param(
    [string]$Version = "1.1.4"
)

$ErrorActionPreference = "Stop"

$BuildRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $BuildRoot
$ReleasesRoot = Join-Path $ProjectRoot "releases"
$Gradle = Join-Path $BuildRoot "gradlew.bat"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$VerifyRoot = Join-Path $BuildRoot "build\no-license-package-verify"

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Remove-SafeDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath($ReleasesRoot).TrimEnd('\') + '\'
    $resolvedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete path outside releases root: $resolvedPath"
    }
    Remove-Item -LiteralPath $Path -Recurse -Force
}

function Remove-SafeBuildDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath((Join-Path $BuildRoot "build")).TrimEnd('\') + '\'
    $resolvedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete path outside build root: $resolvedPath"
    }
    Remove-Item -LiteralPath $Path -Recurse -Force
}

function Invoke-VariantBuild {
    param([Parameter(Mandatory = $true)][string]$Mode)

    Push-Location $BuildRoot
    try {
        $gradleOutput = & $Gradle ":Server-All:packageStartModeDeployZip" "-PcnkdLicenseMode=$Mode" "-PcnkdReleaseVersion=$Version" "--console=plain"
        $gradleOutput | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle packageStartModeDeployZip failed for mode=$Mode"
        }
    } finally {
        Pop-Location
    }

    $deployDir = Get-ChildItem -LiteralPath $ReleasesRoot -Directory -Filter "CNKD-NEXT-$Version-Public-Linux-Deploy-*" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $deployDir) {
        throw "Cannot locate generated deploy directory for mode=$Mode"
    }

    return $deployDir.FullName
}

function Set-JsonProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Value
    )

    if ($Object.PSObject.Properties.Name -contains $Name) {
        $Object.$Name = $Value
    } else {
        $Object | Add-Member -MemberType NoteProperty -Name $Name -Value $Value
    }
}

function Update-ServerConfig {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [bool]$AdvertisingRoom = $false
    )

    $config = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Set-JsonProperty -Object $config.common -Name "autoUpList" -Value $false
    Set-JsonProperty -Object $config.common -Name "listStatusSuffixEnabled" -Value $false
    Set-JsonProperty -Object $config.common -Name "listExperimentEnabled" -Value $false
    Set-JsonProperty -Object $config.common -Name "listPromotionModeEnabled" -Value $false
    Set-JsonProperty -Object $config.common -Name "listPromotionSortBoostEnabled" -Value $false
    if (-not ($config.PSObject.Properties.Name -contains "advertisingRedirect")) {
        $config | Add-Member -MemberType NoteProperty -Name "advertisingRedirect" -Value ([pscustomobject]@{})
    }
    Set-JsonProperty -Object $config.advertisingRedirect -Name "enabled" -Value $false
    Set-JsonProperty -Object $config.advertisingRedirect -Name "targetHost" -Value "101.43.41.22"
    Set-JsonProperty -Object $config.advertisingRedirect -Name "targetPort" -Value 5123
    Set-JsonProperty -Object $config.advertisingRedirect -Name "targetRoomId" -Value ""
    Set-JsonProperty -Object $config.advertisingRedirect -Name "redirectTiming" -Value "afterJoin"
    Set-JsonProperty -Object $config.advertisingRedirect -Name "joinProbeSafeMode" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "delaySeconds" -Value 2
    Set-JsonProperty -Object $config.advertisingRedirect -Name "showNotice" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "noticeBeforeRedirect" -Value "欢迎来到 CNKD 导航服 正在为你跳转到主服"
    Set-JsonProperty -Object $config.advertisingRedirect -Name "noticeOnManualFallback" -Value "如未自动跳转 请手动加入 {host}:{port}"
    Set-JsonProperty -Object $config.advertisingRedirect -Name "kickAfterRedirect" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "cooldownSecondsPerPlayer" -Value 10
    Set-JsonProperty -Object $config.advertisingRedirect -Name "maxRedirectAttemptsPerConnection" -Value 1
    Set-JsonProperty -Object $config.advertisingRedirect -Name "allowLocalhostTarget" -Value $false
    Set-JsonProperty -Object $config.advertisingRedirect -Name "allowPrivateIpTarget" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "allowedTargetHosts" -Value @()
    Set-JsonProperty -Object $config.advertisingRedirect -Name "disableWhenRankedCoreNode" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "disableWhenRankedShardNode" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "adminBypass" -Value $true
    Set-JsonProperty -Object $config.advertisingRedirect -Name "logRedirect" -Value $true
    if ($AdvertisingRoom) {
        Set-JsonProperty -Object $config.common -Name "listStatusSuffixEnabled" -Value $false
    }
    $json = $config | ConvertTo-Json -Depth 32
    Write-Utf8NoBom -Path $Path -Text ($json + "`n")
}

function Write-RelayLicenseConfig {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][bool]$AllowAll
    )

    if ($AllowAll) {
        $text = @'
{
  "leaseEndpoint": "",
  "licenseKey": "",
  "instanceName": "CNKD NEXT No-License All-Features",
  "customerHint": "",
  "publicIpv4Hint": "",
  "requestTimeoutSeconds": 8,
  "refreshSeconds": 1800,
  "graceHours": 72,
  "requestedFeatures": [
    "relay_basic",
    "uplist",
    "relay_server_mode",
    "core_routing",
    "custom_room",
    "rooms_api",
    "admin_api"
  ]
}
'@
    } else {
        $text = @'
{
  "leaseEndpoint": "",
  "licenseKey": "",
  "instanceName": "CNKD NEXT No-License No-UPLIST",
  "customerHint": "",
  "publicIpv4Hint": "",
  "requestTimeoutSeconds": 8,
  "refreshSeconds": 1800,
  "graceHours": 72,
  "requestedFeatures": [
    "relay_basic"
  ]
}
'@
    }

    Write-Utf8NoBom -Path $Path -Text ($text.Trim() + "`n")
}

function Write-NoLicenseConfigHelp {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][bool]$AllowAll
    )

    if ($AllowAll) {
        $variantTitle = "免授权 UPLIST 版本"
        $variantPolicy = @'
- 这是免授权 UPLIST 版本，Server-All.jar 内部模式为 licenseMode=allow_all。
- data/RelayLicenseConfig.json 可以保持空白，不需要填写授权码。
- 默认 common.autoUpList=false，解压后不会自动公开到公共列表。
- 如果你确认要开放公共列表，把 common.autoUpList 改为 true 后重启服务端。
- UPLIST、Relay Server、核心路由、自定义房间、Rooms API、Admin API 均由本地免授权模式允许。
'@
    } else {
        $variantTitle = "免授权禁 UPLIST 版本"
        $variantPolicy = @'
- 这是免授权禁 UPLIST 版本，Server-All.jar 内部模式为 licenseMode=no_uplist。
- data/RelayLicenseConfig.json 可以保持空白，不需要填写授权码。
- 默认 common.autoUpList=false，解压后不会自动公开到公共列表。
- 即使 common.autoUpList=true，本版本也不会上传公共列表。
- 如果你需要公共列表能力，请改用免授权 UPLIST 版本。
'@
    }

    $text = @'
CNKD NEXT @@VARIANT_TITLE@@配置项完整说明
============================================================

这份文档面向拿到压缩包后自行部署和配置的服务器管理员。
本包为免授权发布包，核心原则是先能安全启动，再按需要开启公开列表、锁图、自动开局、权限和广告跳转等能力。

一、当前发布包能力边界
============================================================

@@VARIANT_POLICY@@

二、首次部署建议流程
============================================================

1. 解压完整压缩包
- 保留 Server-All.jar、data 目录、启动脚本和所有说明文档。
- Linux 云服务器建议使用 unzip 解压，避免 Windows 解压工具把路径或中文文件名处理异常。

2. 修改 data/ServerConfig.json
- 至少检查 common.port、common.serverName、gameplay.maxPlayer、map.enableMapLock、map.lockMap。
- 如果只是本地或私服测试，不要开启 common.autoUpList。
- 如果准备公开服，先确认云服务器安全组、防火墙和端口转发都放行 common.port。

3. 修改 data/RoomPermissionConfig.json
- 公开服建议保留普通玩家高风险权限为 false。
- 房主和管理员权限可以按玩法开放，但生成单位、游戏内加钱、游戏内核弹、克隆单位要谨慎。

4. 按需修改 data/PersistentAccessConfig.json
- 需要固定管理员或持久化封禁时再改。
- 玩家 UUID 可让玩家在房间内输入 myid 或 .myid 获取。

5. 启动服务端
- Linux 推荐执行 ./start.sh。
- Windows 可执行 java -Dfile.encoding=UTF-8 -jar Server-All.jar。
- 修改大多数配置后建议重启服务端完整生效。

三、JSON 填写规则
============================================================

- 字符串必须使用英文双引号，例如 "我的服务器"。
- true 和 false 是布尔值，不要加引号。
- 数字不要加引号，例如 5123、100、-1。
- 数组使用中括号，例如 []。
- 对象使用大括号，例如 {"id":"main","enabled":true}。
- 每个配置项之间用英文逗号分隔，最后一项后面不要多加逗号。
- 文件编码保持 UTF-8。
- 修改前建议先备份原文件。

四、data/ServerConfig.json 完整配置项说明
============================================================

[common] 基础启动与公共列表
------------------------------------------------------------

common.defStartCommand
- 作用：服务端启动完成后自动执行的控制台命令。
- 填写方式：通常保持 "start"。
- 建议：普通用户不要改，留空可能导致启动后不自动进入可用状态。

common.log
- 作用：日志等级。
- 填写方式：常用 "WARN"、"INFO"、"DEBUG"。
- 建议：日常用 WARN 或 INFO，排查问题时临时用 DEBUG。

common.cmdTitle
- 作用：控制台窗口标题。
- 填写方式：字符串，可留空。
- 建议：Windows 多实例部署时可填写实例名，Linux 下通常无影响。

common.followBetaVersion
- 作用：是否跟随测试版本更新逻辑。
- 填写方式：true 或 false。
- 建议：生产服保持 false。

common.port
- 作用：玩家连接服务端使用的游戏端口。
- 填写方式：1 到 65535 的数字。
- 建议：同一台机器部署多个实例时每个实例必须使用不同端口，并在安全组放行。

common.serverName
- 作用：服务器名称，房间内和公共列表中都可能展示。
- 填写方式：字符串。
- 建议：公开服控制在清晰可读的长度内，不要堆叠过多符号。

common.subtitle
- 作用：服务器副标题，部分列表或面板会展示。
- 填写方式：字符串，可留空。
- 建议：可写玩法说明，如 "4P极速混战"。

common.autoUpList
- 作用：是否启动后尝试开放到公共列表。
- 填写方式：true 或 false。
- UPLIST 版本：true 表示允许尝试上传公共列表。
- 禁 UPLIST 版本：即使设为 true 也不会上传公共列表。
- 建议：默认 false，确认要公开后再改 true。

common.listStatusSuffixEnabled
- 作用：公共列表名称是否自动追加状态后缀。
- 填写方式：true 或 false。
- 建议：需要稳定展示自定义文案时保持 false。

common.listNamePinnedCharacterCount
- 作用：给公共列表服务器名增加置顶字符数量。
- 填写方式：0 到 64 的数字。
- 建议：0 表示不加；需要排序提升时再按平台策略设置。

common.listPinnedCharacterCount
- 作用：给公共列表地图名增加置顶字符数量。
- 填写方式：0 到 64 的数字。
- 建议：0 表示不加；公开列表测试时不要一次设置过大。

common.publicListEntries
- 作用：同一个服务端上传多个公共列表入口，用于不同展示名称、地图名或入口文案。
- 填写方式：数组，默认 []。
- 限制：实际最多使用前 8 条，建议 2 到 5 条。
- 禁 UPLIST 版本：此字段不会产生公共列表上传效果。

common.publicListEntries[].id
- 作用：入口唯一标识。
- 填写方式：小写字母、数字、下划线、短横线，长度不超过 40，例如 "main"、"ffa_01"。
- 建议：不能重复。

common.publicListEntries[].enabled
- 作用：是否启用这一条列表入口。
- 填写方式：true 或 false。
- 建议：临时下线某个入口时改 false。

common.publicListEntries[].serverName
- 作用：这一条列表入口展示的服务器名。
- 填写方式：字符串，可留空；留空时使用 common.serverName。
- 限制：过长会被忽略，建议 80 字符以内。

common.publicListEntries[].subtitle
- 作用：这一条列表入口的副标题。
- 填写方式：字符串，可留空。
- 建议：用于区分玩法或节点。

common.publicListEntries[].mapName
- 作用：这一条列表入口展示的地图名。
- 填写方式：字符串，可留空；留空时使用运行中的地图名。
- 建议：公开服可写易懂文案，如 "随机地图-满员自动调度"。

common.publicListEntries[].statusSuffixEnabled
- 作用：这一条列表入口是否追加状态后缀。
- 填写方式：true 或 false。
- 建议：文案高度自定义时保持 false。

common.publicListEntries[].listNamePinnedCharacterCount
- 作用：这一条列表入口服务器名置顶字符数量。
- 填写方式：0 到 64 的数字。
- 建议：与主入口区分配置，先小范围测试。

common.publicListEntries[].listPinnedCharacterCount
- 作用：这一条列表入口地图名置顶字符数量。
- 填写方式：0 到 64 的数字。
- 建议：需要地图栏排序时再设置。

common.publicListEntries[].maxPlayerCount
- 作用：这一条列表入口显示的人数上限。
- 填写方式：1 到 200 的数字，或 null。
- 建议：null 表示使用真实房间上限。

common.listExperimentEnabled
- 作用：启用公共列表实验字段覆盖。
- 填写方式：true 或 false。
- 建议：普通用户保持 false，除非你明确知道列表字段含义。

common.listExperimentGameName
- 作用：覆盖上传到公共列表的 game_name 字段。
- 填写方式：字符串，可留空。
- 建议：普通用户留空。

common.listExperimentCreatedBy
- 作用：覆盖上传到公共列表的 created_by 字段。
- 填写方式：字符串，可留空。
- 建议：普通用户留空。

common.listExperimentMapName
- 作用：覆盖上传到公共列表的 game_map 字段。
- 填写方式：字符串，可留空。
- 建议：优先使用 common.publicListEntries[].mapName 或 common.subtitle。

common.listExperimentGameStatus
- 作用：覆盖上传到公共列表的 game_status 字段。
- 填写方式：字符串，可留空。
- 建议：普通用户留空；推广模式用 listPromotionGameStatus。

common.listExperimentPlayerCount
- 作用：覆盖上传到公共列表的 player_count 字段。
- 填写方式：-1 或非负数字。
- 建议：-1 表示使用真实人数。

common.listExperimentMaxPlayerCount
- 作用：覆盖上传到公共列表的 max_player_count 字段。
- 填写方式：-1 或非负数字。
- 建议：-1 表示使用真实上限。

common.listPromotionModeEnabled
- 作用：启用列表推广模式，统一管理展示状态和人数。
- 填写方式：true 或 false。
- 建议：普通可玩服保持 false；广告展示或特殊列表入口再开启。

common.listPromotionGameStatus
- 作用：推广模式下展示的游戏状态。
- 填写方式：常用 "chat" 或 "battleroom"。
- 建议：可正常游玩的入口优先使用 "battleroom"。

common.listPromotionPlayerCount
- 作用：推广模式展示的人数。
- 填写方式：-1 或非负数字。
- 建议：-1 表示不覆盖真实值。

common.listPromotionMaxPlayerCount
- 作用：推广模式展示的人数上限。
- 填写方式：-1 或非负数字。
- 建议：-1 表示不覆盖真实值。

common.listPromotionSortBoostEnabled
- 作用：启用推广排序前缀逻辑。
- 填写方式：true 或 false。
- 建议：了解公共列表排序规则后再开启。

common.listPromotionSortBoostPrefix
- 作用：推广排序使用的前缀字符。
- 填写方式：字符串。
- 建议：不要随意复制不可见字符，避免列表显示异常。

common.listPromotionBlankMapEnabled
- 作用：推广排序时是否上传空地图名。
- 填写方式：true 或 false。
- 建议：可玩服保持 false，避免玩家误解。

[network] 网络与管理接口
------------------------------------------------------------

network.ipCheckMultiLanguageSupport
- 作用：IP 检查相关多语言兼容。
- 填写方式：true 或 false。
- 建议：保持 true。

network.singleUserRelay
- 作用：单用户 Relay 模式开关。
- 填写方式：true 或 false。
- 建议：普通独立服保持 false。

network.singleUserRelayMod
- 作用：单用户 Relay 模式下是否启用模组配置。
- 填写方式：true 或 false。
- 建议：普通独立服保持 false。

network.mixProtocolEnable
- 作用：是否把游戏、HTTP、RCON 等混合到同一监听协议中。
- 填写方式：true 或 false。
- 建议：普通部署保持 false。

network.rconMixEnable
- 作用：混合协议下是否启用 RCON。
- 填写方式：true 或 false。
- 建议：不使用 RCON 时保持 false。

network.webMixEnable
- 作用：混合协议下是否启用 Web。
- 填写方式：true 或 false。
- 建议：不需要内置 Web 时保持 false。

network.rconPort
- 作用：RCON 管理端口。
- 填写方式：0 或 1 到 65535 的数字；0 表示不启用独立端口。
- 建议：如启用请设置强密码并限制防火墙来源。

network.rconPasswd
- 作用：RCON 密码。
- 填写方式：字符串。
- 建议：启用 RCON 时必须改成高强度随机密码。

network.webPort
- 作用：内置 Web 管理端口。
- 填写方式：0 或 1 到 65535 的数字；0 表示不启用。
- 建议：公开环境不要裸露到公网，必须配合 token 和防火墙。

network.webToken
- 作用：内置 Web 管理鉴权 token。
- 填写方式：字符串。
- 建议：启用 webPort 时必须改成高强度随机字符串。

network.webHOST
- 作用：Web 监听地址限制。
- 填写方式：字符串，可留空。
- 建议：留空表示默认行为；只允许本机访问可填 127.0.0.1。

network.sslEnable
- 作用：是否启用 SSL。
- 填写方式：true 或 false。
- 建议：普通游戏端口不需要开启。

network.sslMixEnable
- 作用：启用 SSL 时是否允许 HTTP 混合。
- 填写方式：true 或 false。
- 建议：不了解时保持 false。

network.sslPasswd
- 作用：SSL 证书密码。
- 填写方式：字符串。
- 建议：仅启用 SSL 时需要配置。

[gameplay] 房间玩法与基础规则
------------------------------------------------------------

gameplay.serverID
- 作用：服务端内部识别 ID。
- 填写方式：字符串。
- 建议：多实例部署时每个实例使用不同 ID。

gameplay.enterAd
- 作用：玩家进入房间时显示的欢迎语。
- 填写方式：字符串，可留空。
- 建议：写简短提示，不要刷屏。

gameplay.startAd
- 作用：游戏开始时显示的提示语。
- 填写方式：字符串，可留空。
- 建议：可写规则提醒。

gameplay.maxPlayerJoinAd
- 作用：房间满员时给加入玩家的提示。
- 填写方式：字符串，可留空。
- 建议：可提示稍后重试或加入其他入口。

gameplay.startPlayerJoinAd
- 作用：游戏进行中或无法加入时的提示。
- 填写方式：字符串，可留空。
- 建议：可提示等待下一局。

gameplay.passwd
- 作用：房间密码。
- 填写方式：字符串，留空表示无密码。
- 建议：公开服通常留空，私服可填写。

gameplay.maxPlayer
- 作用：房间最大玩家数。
- 填写方式：1 到合理上限的数字。
- 建议：按地图人数和服务器性能设置。

gameplay.oneAdmin
- 作用：是否启用普通玩家房主权限，并尽量保证房间内有房主。
- 填写方式：true 或 false。
- false 时：客户端隐藏普通房主操作，服务端同步拒绝普通 isAdmin/autoAdmin 标记通过手动命令使用房主权限。
- superAdmin：后台设置的超级管理员不受此开关限制，但仍受 RoomPermissionConfig 具体动作权限控制；开启 superAdminBypass 时可绕过。
- 建议：需要玩家房主的普通房间保持 true；完全后台托管时可设为 false。

gameplay.startMinPlayerSize
- 作用：手动开局最低人数。
- 填写方式：-1 表示不限制，其他数字表示最低人数。
- 建议：公开竞技服按机制设置，例如 2、4、10、14。

gameplay.autoStartMinPlayerSize
- 作用：自动开局人数。
- 填写方式：-1 表示关闭自动开局，其他数字表示达到人数后触发倒计时。
- 建议：公开快节奏房间可设置为地图满员人数。

gameplay.autoStartCountdownSeconds
- 作用：满足自动开局人数后的倒计时秒数。
- 填写方式：0 到 600 的数字。
- 建议：5 到 15 秒较适合公开服。

gameplay.maxGameIngTime
- 作用：单局最长运行时间，单位秒。
- 填写方式：数字。
- 建议：普通对局可 7200，短局可降低。

gameplay.maxOnlyAIGameIngTime
- 作用：只剩 AI 时的兜底运行时间，单位秒。
- 填写方式：数字。
- 建议：公开服可适当降低，避免空房间长期占用。

gameplay.enableAI
- 作用：是否允许添加 AI。
- 填写方式：true 或 false。
- 建议：排位或公平房间通常 false。

gameplay.maxMessageLen
- 作用：聊天单条最大字符数。
- 填写方式：数字。
- 建议：公开服可 40 到 100，减少刷屏。

gameplay.maxUnit
- 作用：单位上限。
- 填写方式：数字。
- 建议：性能较弱的服务器不要设置太高。

gameplay.defIncome
- 作用：默认收入倍率。
- 填写方式：数字，1.0 表示默认。
- 建议：按玩法设置，例如 2.5 表示 2.5 倍收入。

gameplay.defaultStartingCreditsAmount
- 作用：默认初始资金。
- 填写方式：非负数字。
- 建议：按地图机制设置。

gameplay.disableNukes
- 作用：设置新战役室是否默认禁用核弹，不强制覆盖当前房间。
- 填写方式：true 或 false。
- 建议：竞技房间常用 true。
- 开局优先级：房主在原版战役室明确选择 > 引擎当前战役室值 > 此配置默认值。
- 房主离开或转移后，旧房主选择自动失效，不会覆盖新房主或引擎当前值。
- reloadconfig 会保留当前房间状态；新默认值从下一次新战役室或赛后重置开始使用。

gameplay.defaultFogMode
- 作用：默认战争迷雾。
- 填写方式："off"、"basic"、"lineOfSight"。
- 建议：公开竞技服通常用 "lineOfSight"。

gameplay.isAfk
- 作用：是否启用 AFK 相关管理逻辑。
- 填写方式：true 或 false。
- 建议：公开服保持 true。

gameplay.muteAll
- 作用：是否全员禁言。
- 填写方式：true 或 false。
- 建议：日常 false，紧急管控时 true。

gameplay.turnStoneIntoGold
- 作用：点石成金特殊玩法开关。
- 填写方式：true 或 false。
- 建议：普通服保持 false。

gameplay.modsLoadErrorPrint
- 作用：是否打印模组加载错误详情。
- 填写方式：true 或 false。
- 建议：排查模组问题时临时 true。

gameplay.saveRePlayFile
- 作用：是否保存服务器对局录像。
- 填写方式：true 或 false。
- 建议：需要复盘时 true；磁盘紧张时定期清理 replays。

[lobby] 战役室、同步与回收
------------------------------------------------------------

lobby.disableTeamChange
- 作用：禁止玩家更换队伍。
- 填写方式：true 或 false。
- 建议：排位或固定队伍机制开启 true。

lobby.disablePositionChange
- 作用：禁止玩家更换位置。
- 填写方式：true 或 false。
- 建议：锁位置机制开启 true。

lobby.enableFreeForAllMode
- 作用：启用混战模式。
- 填写方式：true 或 false。
- 建议：FFA 地图开启 true。

lobby.enableAlternateTeamsOnJoin
- 作用：玩家加入时自动交替分队。
- 填写方式：true 或 false。
- 建议：团战或 1v1 可开启，FFA 通常关闭。

lobby.enableSurvivalMapMode
- 作用：启用生存地图模式。
- 填写方式：true 或 false。
- 建议：生存图开启，普通对战图关闭。

lobby.survivalGameOverGraceSeconds
- 作用：生存图结算保护时间，单位秒。
- 填写方式：非负数字。
- 建议：防止开局刷兵阶段误判结束。

lobby.survivalEnemyClearConfirmSeconds
- 作用：敌人清空后的确认时间，单位秒。
- 填写方式：非负数字。
- 建议：生存图可 30 到 60 秒。

lobby.enableLobbySyncOptimization
- 作用：启用大厅同步优化。
- 填写方式：true 或 false。
- 建议：保持 true。

lobby.lobbyRefreshHeartbeatMillis
- 作用：大厅刷新心跳间隔，单位毫秒。
- 填写方式：正数。
- 建议：默认 30000，频繁改小会增加负载。

lobby.lobbyPacketHeartbeatMillis
- 作用：大厅数据包心跳间隔，单位毫秒。
- 填写方式：正数。
- 建议：默认 30000。

lobby.latencyHeartbeatMillis
- 作用：延迟探测心跳间隔，单位毫秒。
- 填写方式：正数。
- 建议：默认 1000，过低会增加负载。

lobby.enableLobbyGameSyncPacketFilter
- 作用：启用大厅游戏同步包过滤。
- 填写方式：true 或 false。
- 建议：保持 true，降低异常同步影响。

lobby.lobbyGameSyncPacketHeartbeatMillis
- 作用：大厅游戏同步包心跳间隔，单位毫秒。
- 填写方式：正数。
- 建议：默认 3000。

lobby.lobbyGameSyncPacketJoinGraceMillis
- 作用：玩家加入后的同步宽限时间，单位毫秒。
- 填写方式：正数。
- 建议：默认 180000，网络较差可适当提高。

lobby.reconnectGraceMillis
- 作用：重连宽限时间，单位毫秒。
- 填写方式：非负数字。
- 建议：默认 5000。

lobby.enableGameOverBattleRoomReturn
- 作用：游戏结束后是否自动返回战役室。
- 填写方式：true 或 false。
- 默认：false；字段缺失也按 false 处理。只有显式 true 才自动返回战役室。
- 建议：独立单房间保持 false，赛后会明确提示服务器强制重置且玩家需要重新进入。

lobby.gameOverReturnCountdownSeconds
- 作用：自动返回战役室或服务器强制重置前共用的等待时间，单位秒。
- 填写方式：5 到 300。
- 建议：保持 30。

lobby.pruneDisconnectedPlayersOnGameOver
- 作用：游戏结束返回或重置时是否清理掉线玩家占位。
- 填写方式：true 或 false。
- 建议：公开服保持 true。

lobby.enableJoinReadinessTips
- 作用：玩家加入时是否显示准备提示。
- 填写方式：true 或 false。
- 建议：新手较多的公开服保持 true。

lobby.joinTipModWarningThreshold
- 作用：加入提示中的模组警告阈值。
- 填写方式：数字。
- 建议：默认 1。

[map] 地图与模组包地图
------------------------------------------------------------

map.enableMapLock
- 作用：是否锁定地图。
- 填写方式：true 或 false。
- 建议：公开固定玩法服开启 true。

map.lockMap
- 作用：锁定地图名称。
- 填写方式：字符串，填写地图文件名或服务端识别的地图名。
- 建议：必须与 data/maps 中地图一致；不锁图时留空。

map.enableModPackageMapDiscovery
- 作用：是否扫描模组包内地图。
- 填写方式：true 或 false。
- 建议：使用模组地图时开启。

map.modPackageMapScanMaxArchiveSizeMb
- 作用：模组包扫描大小上限，单位 MB。
- 填写方式：正数。
- 建议：默认 512，大模组可调高但会增加扫描时间。

map.enableModPackageMapSupportExtraction
- 作用：是否支持提取模组包地图。
- 填写方式：true 或 false。
- 建议：需要模组包地图时开启。

map.enableModPackageMapAutoExtraction
- 作用：启动时是否自动提取模组包地图。
- 填写方式：true 或 false。
- 建议：需要自动可用时开启。

[ranked] 排位核心服接入
------------------------------------------------------------

ranked.coreNodeEnabled
- 作用：是否把当前服务端作为排位核心入口服。
- 填写方式：true 或 false。
- 建议：普通单房间发布包保持 false。

ranked.gatewayBaseUrl
- 作用：排位后台或调度网关地址。
- 填写方式：URL 字符串，例如 "http://127.0.0.1:3010"。
- 建议：只有接入排位系统时修改。

ranked.queueCode
- 作用：排位队列编码。
- 填写方式：字符串，例如 "ranked_1v1"。
- 建议：必须与后台队列配置一致。

ranked.pollIntervalSeconds
- 作用：核心服轮询后台状态间隔，单位秒。
- 填写方式：正数。
- 建议：默认 2，过低会增加后台压力。

ranked.jumpDelaySeconds
- 作用：玩家匹配成功后跳转延迟，单位秒。
- 填写方式：非负数字。
- 建议：默认 1。

[advertisingRedirect] 广告房跳转
------------------------------------------------------------

advertisingRedirect.enabled
- 作用：是否启用广告房跳转模式。
- 填写方式：true 或 false。
- 建议：默认 false；普通可玩服不要开启。

advertisingRedirect.targetHost
- 作用：跳转目标主机或 IP。
- 填写方式：域名或 IPv4 字符串。
- 默认：101.43.41.22。

advertisingRedirect.targetPort
- 作用：跳转目标端口。
- 填写方式：1 到 65535 的数字。
- 默认：5123。

advertisingRedirect.targetRoomId
- 作用：跳转目标房间 ID。
- 填写方式：字符串，可留空。
- 建议：不需要固定房间时留空。

advertisingRedirect.redirectTiming
- 作用：跳转时机。
- 填写方式：当前建议保持 "afterJoin"。
- 说明：玩家先真实进入广告房，再触发跳转，减少列表误判。

advertisingRedirect.joinProbeSafeMode
- 作用：进入探测安全模式。
- 填写方式：true 或 false。
- 建议：保持 true。

advertisingRedirect.delaySeconds
- 作用：进入广告房后等待多少秒再跳转。
- 填写方式：0 到 30 的数字。
- 建议：1 到 3 秒。

advertisingRedirect.showNotice
- 作用：跳转前是否显示提示。
- 填写方式：true 或 false。
- 建议：保持 true，让玩家知道正在跳转。

advertisingRedirect.noticeBeforeRedirect
- 作用：跳转前提示文案。
- 填写方式：字符串。
- 建议：简短说明目标服，例如 "正在为你跳转到主服"。

advertisingRedirect.noticeOnManualFallback
- 作用：自动跳转失败时的手动加入提示。
- 填写方式：字符串，可使用 {host} 和 {port} 占位。
- 示例："如未自动跳转 请手动加入 {host}:{port}"。

advertisingRedirect.kickAfterRedirect
- 作用：跳转后是否移出广告房，减少占位。
- 填写方式：true 或 false。
- 建议：广告房保持 true。

advertisingRedirect.cooldownSecondsPerPlayer
- 作用：同一玩家重复触发跳转的冷却秒数。
- 填写方式：0 到 600 的数字。
- 建议：默认 10。

advertisingRedirect.maxRedirectAttemptsPerConnection
- 作用：单次连接最大跳转次数。
- 填写方式：至少 1。
- 建议：保持 1，避免循环跳转。

advertisingRedirect.allowLocalhostTarget
- 作用：是否允许跳转目标为本机地址。
- 填写方式：true 或 false。
- 建议：正式环境 false。

advertisingRedirect.allowPrivateIpTarget
- 作用：是否允许跳转目标为内网地址。
- 填写方式：true 或 false。
- 建议：内网部署可 true，公网广告房按需设置。

advertisingRedirect.allowedTargetHosts
- 作用：允许跳转的目标主机白名单。
- 填写方式：字符串数组，默认 [] 表示不额外限制。
- 建议：对外提供面板配置时可设置白名单降低误跳风险。

advertisingRedirect.disableWhenRankedCoreNode
- 作用：当前实例作为排位核心服时自动禁用广告跳转。
- 填写方式：true 或 false。
- 建议：保持 true。

advertisingRedirect.disableWhenRankedShardNode
- 作用：当前实例作为排位分片服时自动禁用广告跳转。
- 填写方式：true 或 false。
- 建议：保持 true。

advertisingRedirect.adminBypass
- 作用：管理员是否绕过广告跳转。
- 填写方式：true 或 false。
- 建议：保持 true，方便排查。

advertisingRedirect.logRedirect
- 作用：是否记录广告跳转日志。
- 填写方式：true 或 false。
- 建议：保持 true。

五、data/RoomPermissionConfig.json 完整配置项说明
============================================================

RoomPermissionConfig.enable
- 作用：是否启用房间权限控制。
- 填写方式：true 或 false。
- 建议：公开服保持 true。

[moderation] 权限治理
------------------------------------------------------------

moderation.superAdminBypass
- 作用：超级管理员是否绕过普通权限限制。
- 填写方式：true 或 false。
- 建议：保持 true，但只给可信 UUID 超级管理员。

moderation.allowAutoAdminAssignment
- 作用：空房间首个玩家是否自动获得房主。
- 填写方式：true 或 false。
- 前提：gameplay.oneAdmin 必须为 true。
- 建议：普通单房间可 true；排位分片或严格托管服可 false。

moderation.allowAutoAdminTransferOnLeave
- 作用：房主离开时是否自动转移房主。
- 填写方式：true 或 false。
- 前提：gameplay.oneAdmin 必须为 true。
- 建议：普通房间保持 true。

moderation.allowManualAdminGrantFromConsole
- 作用：是否允许控制台授予管理员。
- 填写方式：true 或 false。
- 建议：需要运维介入时保持 true。

moderation.allowGiveAdminFromConsole
- 作用：是否允许控制台转移房主或授予房主。
- 填写方式：true 或 false。
- 前提：gameplay.oneAdmin 必须为 true；后台超级管理员请使用 admin add/remove 管理。
- 建议：需要人工处理房主问题时保持 true。

[host] 与 [admin] 通用权限字段
------------------------------------------------------------

host.allowStartGame / admin.allowStartGame
- 作用：允许开局。
- 填写方式：true 或 false。

host.allowChangeMap / admin.allowChangeMap
- 作用：允许换图。
- 填写方式：true 或 false。

host.allowViewMaps / admin.allowViewMaps
- 作用：允许查看地图列表。
- 填写方式：true 或 false。

host.allowKickPlayers / admin.allowKickPlayers
- 作用：允许踢出玩家。
- 填写方式：true 或 false。

host.allowAddAi / admin.allowAddAi
- 作用：允许添加 AI。
- 填写方式：true 或 false。

host.allowSetCredits / admin.allowSetCredits
- 作用：允许设置初始资金。
- 填写方式：true 或 false。

host.allowSetIncome / admin.allowSetIncome
- 作用：允许设置收入倍率。
- 填写方式：true 或 false。

host.allowSetFog / admin.allowSetFog
- 作用：允许设置战争迷雾。
- 填写方式：true 或 false。

host.allowSetStartingUnits / admin.allowSetStartingUnits
- 作用：允许设置起始单位数量。
- 填写方式：true 或 false。

host.allowToggleNukes / admin.allowToggleNukes
- 作用：允许切换核弹。
- 填写方式：true 或 false。

host.allowToggleSharedControl / admin.allowToggleSharedControl
- 作用：允许切换共享控制。
- 填写方式：true 或 false。

host.allowSetAiDifficulty / admin.allowSetAiDifficulty
- 作用：允许设置 AI 难度。
- 填写方式：true 或 false。

host.allowMovePlayers / admin.allowMovePlayers
- 作用：允许移动玩家位置。
- 填写方式：true 或 false。

host.allowChangeTeam / admin.allowChangeTeam
- 作用：允许调整玩家队伍。
- 填写方式：true 或 false。

host.allowToggleMixedTeam / admin.allowToggleMixedTeam
- 作用：允许切换混队。
- 填写方式：true 或 false。

host.allowReturnToBattleRoom / admin.allowReturnToBattleRoom
- 作用：允许返回战役室。
- 填写方式：true 或 false。

host.allowPauseGame / admin.allowPauseGame
- 作用：允许暂停游戏。
- 填写方式：true 或 false。

host.allowUnpauseGame / admin.allowUnpauseGame
- 作用：允许解除暂停。
- 填写方式：true 或 false。

host.allowSetPlayerStartUnit / admin.allowSetPlayerStartUnit
- 作用：允许设置玩家初始单位。
- 填写方式：true 或 false。

host.allowSpawnUnits / admin.allowSpawnUnits
- 作用：允许生成单位。
- 填写方式：true 或 false。
- 建议：高风险权限，公开服谨慎开放。

host.allowAddMoneyInGame / admin.allowAddMoneyInGame
- 作用：允许游戏内加钱。
- 填写方式：true 或 false。
- 建议：高风险权限，竞技服关闭。

host.allowFireNukeInGame / admin.allowFireNukeInGame
- 作用：允许游戏内发射核弹。
- 填写方式：true 或 false。
- 建议：高风险权限，竞技服关闭。

host.allowCloneUnits / admin.allowCloneUnits
- 作用：允许克隆单位。
- 填写方式：true 或 false。
- 建议：高风险权限，可能造成性能压力。

[player] 普通玩家权限字段
------------------------------------------------------------

player.allowVoteGameOver
- 作用：允许普通玩家投票结束。
- 填写方式：true 或 false。
- 建议：公开服可 true。

player.allowAfkTakeAdmin
- 作用：允许普通玩家接管 AFK 房主。
- 填写方式：true 或 false。
- 建议：普通房间可 true，严格托管服可 false。

player.allowViewStatus
- 作用：允许普通玩家查看房间状态。
- 填写方式：true 或 false。

player.allowViewMyId
- 作用：允许普通玩家查看自己的 UUID。
- 填写方式：true 或 false。
- 建议：需要配置固定管理员时保持 true。

player.allowViewMaps
- 作用：允许普通玩家查看地图列表。
- 填写方式：true 或 false。

player.allowStartGame
- 作用：允许普通玩家开局。
- 填写方式：true 或 false。
- 建议：公开排位或托管服通常 false。

player.allowChangeMap
- 作用：允许普通玩家换图。
- 填写方式：true 或 false。
- 建议：公开固定玩法服 false。

player.allowAddAi
- 作用：允许普通玩家添加 AI。
- 填写方式：true 或 false。
- 建议：公平玩法 false。

player.allowSetCredits
- 作用：允许普通玩家设置资金。
- 填写方式：true 或 false。
- 建议：公平玩法 false。

player.allowSetIncome
- 作用：允许普通玩家设置收入。
- 填写方式：true 或 false。
- 建议：公平玩法 false。

player.allowSetFog
- 作用：允许普通玩家设置迷雾。
- 填写方式：true 或 false。
- 建议：公平玩法 false。

player.allowSetStartingUnits
- 作用：允许普通玩家设置起始单位。
- 填写方式：true 或 false。

player.allowToggleNukes
- 作用：允许普通玩家切换核弹。
- 填写方式：true 或 false。

player.allowToggleSharedControl
- 作用：允许普通玩家切换共享控制。
- 填写方式：true 或 false。

player.allowSetAiDifficulty
- 作用：允许普通玩家设置 AI 难度。
- 填写方式：true 或 false。

player.allowMovePlayers
- 作用：允许普通玩家移动玩家位置。
- 填写方式：true 或 false。

player.allowChangeTeam
- 作用：允许普通玩家调整队伍。
- 填写方式：true 或 false。

player.allowToggleMixedTeam
- 作用：允许普通玩家切换混队。
- 填写方式：true 或 false。

六、data/PersistentAccessConfig.json 完整配置项说明
============================================================

PersistentAccessConfig.enable
- 作用：是否启用持久化访问控制。
- 填写方式：true 或 false。
- 建议：公开服保持 true。

PersistentAccessConfig.persistRuntimeChanges
- 作用：运行中 ban、admin 等命令产生的变化是否立即写回 JSON。
- 填写方式：true 或 false。
- 建议：保持 true，避免重启后丢失封禁和管理员。

PersistentAccessConfig.legacySettingsMigrationDone
- 作用：旧 Settings.bin 访问数据是否已迁移的内部标记。
- 填写方式：true 或 false。
- 建议：普通用户不要手动改。

PersistentAccessConfig.persistentBlacklistEnabled
- 作用：是否启用持久化黑名单。
- 填写方式：true 或 false。
- 建议：公开服保持 true。

PersistentAccessConfig.persistentAdminEnabled
- 作用：是否启用持久化管理员。
- 填写方式：true 或 false。
- 建议：需要固定管理员时保持 true。

PersistentAccessConfig.bannedUUIDs
- 作用：被封禁的玩家 UUID 列表。
- 填写方式：字符串数组，例如 ["uuid1","uuid2"]。
- 建议：通常由 ban 命令或后台写入。

PersistentAccessConfig.bannedIPs
- 作用：被封禁的完整 IPv4 列表。
- 填写方式：字符串数组，例如 ["1.2.3.4"]。
- 建议：误封时谨慎删除。

PersistentAccessConfig.bannedIP24
- 作用：被封禁的 IPv4 /24 网段列表。
- 填写方式：字符串数组，例如 ["1.2.3.0/24"] 或按服务端支持的网段格式。
- 建议：只在攻击明显时使用，避免误伤正常玩家。

PersistentAccessConfig.autoHostUUIDs
- 作用：加入后自动获得房主权限的 UUID 列表。
- 填写方式：字符串数组。
- 建议：适合固定服主。

PersistentAccessConfig.adminUUIDs
- 作用：加入后自动获得普通管理员权限的 UUID 列表。
- 填写方式：字符串数组。

PersistentAccessConfig.superAdminUUIDs
- 作用：加入后自动获得超级管理员权限的 UUID 列表。
- 填写方式：字符串数组。
- 建议：只给高度可信人员。

PersistentAccessConfig.admins
- 作用：带备注和角色的管理员列表。
- 填写方式：对象数组，每项包含 uuid、name、role、superAdmin。
- 建议：优先使用 admins 统一管理固定管理员。

PersistentAccessConfig.admins[].uuid
- 作用：玩家 UUID。
- 填写方式：字符串，玩家可通过 myid 或 .myid 获取。

PersistentAccessConfig.admins[].name
- 作用：备注名称。
- 填写方式：字符串。
- 说明：只用于人工识别，不参与匹配。

PersistentAccessConfig.admins[].role
- 作用：管理员角色。
- 填写方式："host"、"admin"、"super_admin"。
- 建议：服主用 host 或 super_admin，普通协管用 admin。

PersistentAccessConfig.admins[].superAdmin
- 作用：是否授予超级管理员能力。
- 填写方式：true 或 false。
- 建议：只给可信人员。

七、data/RelayLicenseConfig.json 完整配置项说明
============================================================

说明：本发布包是免授权包，以下字段保留是为了兼容旧配置结构和管理面板。通常不需要填写。

RelayLicenseConfig.leaseEndpoint
- 作用：授权租约接口地址。
- 填写方式：免授权包保持空字符串 ""。

RelayLicenseConfig.licenseKey
- 作用：授权密钥字段。
- 填写方式：免授权包保持空字符串 ""。

RelayLicenseConfig.instanceName
- 作用：实例名称备注。
- 填写方式：字符串，可保持默认。

RelayLicenseConfig.customerHint
- 作用：客户或部署者备注。
- 填写方式：字符串，可留空。

RelayLicenseConfig.publicIpv4Hint
- 作用：公网 IPv4 提示。
- 填写方式：字符串，可留空。
- 建议：多出口网络排查时再填写。

RelayLicenseConfig.requestTimeoutSeconds
- 作用：授权接口请求超时秒数。
- 填写方式：正数。
- 建议：免授权包保持默认。

RelayLicenseConfig.refreshSeconds
- 作用：授权刷新间隔秒数。
- 填写方式：60 到 86400 之间较合理。
- 建议：免授权包保持默认。

RelayLicenseConfig.graceHours
- 作用：授权宽限小时数。
- 填写方式：非负数字。
- 建议：免授权包保持默认。

RelayLicenseConfig.requestedFeatures
- 作用：期望授权的能力列表。
- 填写方式：字符串数组。
- 免授权 UPLIST 版本：默认包含 relay_basic、uplist、relay_server_mode、core_routing、custom_room、rooms_api、admin_api。
- 免授权禁 UPLIST 版本：默认只保留 relay_basic。
- 建议：普通用户不要修改。

八、常用配置模板
============================================================

私有房间
- common.autoUpList=false
- gameplay.passwd="你的密码"
- gameplay.maxPlayer 按地图人数设置

公开 UPLIST 房间
- 使用免授权 UPLIST 版本
- common.autoUpList=true
- common.serverName 填写清晰服务器名
- common.listStatusSuffixEnabled=false
- map.enableMapLock=true
- map.lockMap 填写地图名

固定地图 1v1
- gameplay.maxPlayer=2
- gameplay.startMinPlayerSize=2
- gameplay.autoStartMinPlayerSize=2
- lobby.enableFreeForAllMode=false
- lobby.enableAlternateTeamsOnJoin=true
- map.enableMapLock=true
- map.lockMap 填写 1v1 地图

固定地图 FFA
- gameplay.maxPlayer 按地图人数设置
- lobby.enableFreeForAllMode=true
- lobby.enableAlternateTeamsOnJoin=false
- lobby.disableTeamChange=true
- map.enableMapLock=true
- map.lockMap 填写 FFA 地图

广告跳转房
- 建议使用免授权 UPLIST 版本
- common.autoUpList=true
- advertisingRedirect.enabled=true
- advertisingRedirect.targetHost 填目标主服 IP 或域名
- advertisingRedirect.targetPort 填目标主服端口
- advertisingRedirect.redirectTiming="afterJoin"

九、上线前检查清单
============================================================

- ServerConfig.json 可以被 JSON 工具正常解析。
- common.port 没有和其他实例冲突。
- 云服务器安全组和系统防火墙已放行 common.port。
- 公开服确认使用的是免授权 UPLIST 版本，不是禁 UPLIST 版本。
- 禁 UPLIST 私有包确认 common.autoUpList=false。
- 房间地图文件已放入 data/maps。
- map.lockMap 与地图名称一致。
- 高风险权限没有开放给普通玩家。
- 如果启用 Web/RCON，密码和 token 已修改且端口不裸露给公网。
- 首次公开前建议先用私有方式进房测试开局、重启、换图、踢人和聊天。
'@

    $text = $text.Replace("@@VARIANT_TITLE@@", $variantTitle).Replace("@@VARIANT_POLICY@@", $variantPolicy.Trim())
    Write-Utf8NoBom -Path $Path -Text ($text.Trim() + "`n")
}

function Write-NoLicenseDeploymentDocs {
    param(
        [Parameter(Mandatory = $true)][string]$DeployDir,
        [Parameter(Mandatory = $true)][bool]$AllowAll
    )

    if ($AllowAll) {
        $variantTitle = "免授权 UPLIST 版本"
        $publicListLine = "4. 如需开放到公共列表，把 data/ServerConfig.json 中的 common.autoUpList 改为 true。"
        $publicListGuide = @'
- 当前包允许 UPLIST，默认 autoUpList=false，避免解压后误公开。
- 要开放公共列表，只需要把 `data/ServerConfig.json` 中 `common.autoUpList` 改为 `true` 并重启。
- `data/RelayLicenseConfig.json` 保持空白即可，不需要授权码。
- 如果设置了 `publicListEntries`，服务端会按配置上传多个列表入口；建议先用 1 到 2 个入口测试可见性。
- 多入口使用各自稳定的列表 ID；master server 暂时超时或快照格式异常时，服务端会保留上传状态并等待重试，不会因一次探测异常主动删除并重建条目。
'@
        $publicListChecklist = @'
如果后台显示端口开放，但列表看不到:
- 确认当前使用的是免授权 UPLIST 版本，不是禁 UPLIST 版本。
- 确认 data/ServerConfig.json 中 common.autoUpList=true。
- 确认云服务器安全组和系统防火墙放行 common.port。
- 执行 uplist status，检查 gs1/gs4 是否可见。
- 执行 uplist diagnose，检查 snapshot.rows、snapshot.parsed、snapshot.schema、snapshot.complete；complete=false 或 master server unavailable 时应视为探测暂时不可用。
- 如果配置了 publicListEntries，确认 id 不重复、serverName 不过长、最多只使用前 8 条。
- 如果启用了推广模式，确认 listPromotionGameStatus 使用 battleroom 时玩家可正常进入。
'@
    } else {
        $variantTitle = "免授权禁 UPLIST 版本"
        $publicListLine = "4. 当前包固定禁用公共列表上传；如需公开列表请改用免授权 UPLIST 版本。"
        $publicListGuide = @'
- 当前包在 jar 内部固定禁用 UPLIST。
- `common.autoUpList` 默认 false；即使手动改成 true，也不会上传公共列表。
- `data/RelayLicenseConfig.json` 保持空白即可，不需要授权码。
- 如果你需要公共列表展示，请换用免授权 UPLIST 版本重新部署。
'@
        $publicListChecklist = @'
如果你看不到公共列表:
- 这是禁 UPLIST 版本的预期行为。
- 请不要通过填写 RelayLicenseConfig.json 或反复重启来排查公共列表。
- 如需公共列表能力，请使用免授权 UPLIST 版本。
- 私有服仍需确认 common.port 已放行，玩家可通过 IP:端口直连。
'@
    }

    $readme = @'
CNKD NEXT Start Mode @@VARIANT_TITLE@@ Linux 部署包

首次使用:
1. 安装 Java 17 或更高版本。
2. 解压整个压缩包到一个全新目录，不要只替换 Server-All.jar。
3. 打开 CONFIG-HELP-CN.txt，按说明修改 data/ServerConfig.json。
@@PUBLIC_LIST_LINE@@
5. 执行: chmod +x start.sh start-lowmem.sh cleanup-pure-start.sh
6. 常规启动: ./start.sh
7. 低内存启动: ./start-lowmem.sh
8. 当前包首次启动会自动完成开源声明和初始化状态写入，无需手动确认或二次确认。

说明:
- data/ServerConfig.json 是主配置文件，管理端口、名称、人数、自动开局、锁图、公共列表等。
- data/RelayLicenseConfig.json 可保持空白；当前免授权包不需要授权码。
- data/RoomPermissionConfig.json 管理房主、管理员、普通玩家的房间内权限。
- data/PersistentAccessConfig.json 管理持久化管理员、黑名单、IP 封禁和运行中写回。
- 默认 autoUpList=false，不会自动上传公共列表。
- 默认 webPort=0，减少额外常驻开销。
- 未放置 JavaScript 插件时，将自动跳过 Graal/Wasm 可选依赖。
- 如果从旧 Relay 目录覆盖升级，可先执行 ./cleanup-pure-start.sh 删除不再需要的 Relay 配置文件。
'@

    $deployGuide = @'
# CNKD NEXT Start Mode @@VARIANT_TITLE@@部署说明

## 版本定位
这份部署包面向 Rusted Warfare Headless `start` 模式，适合私有房间、公开房间、团战服、FFA、生存图和固定地图活动服。

它是免授权构建，`data/RelayLicenseConfig.json` 可以保持空白。新管理员应优先修改 `data/ServerConfig.json`，不要从旧教程里的 `Config.json` 或 `ConfigServer.json` 开始。

## 启动方式
常规:
```sh
./start.sh
```

低内存:
```sh
./start-lowmem.sh
```

自定义:
```sh
JAVA_OPTS="-Xms256m -Xmx1536m" ./start.sh
```

## 首次启动
当前包首次启动会自动完成开源声明状态和初始化状态写入，无需在控制台进行许可确认，也不需要进行第二次确认。

如果启动后无法进入房间，优先检查 Java 版本、端口占用、安全组、防火墙、地图文件和 `data/ServerConfig.json` 是否为合法 JSON。

## 第一次应该修改哪些文件
- `CONFIG-HELP-CN.txt`: 先读这份文档，它按字段解释了配置含义和常见模板。
- `data/ServerConfig.json`: 主配置文件，管理端口、服务器名称、人数、自动开局、房间规则、锁图和公共列表字段。
- `data/RoomPermissionConfig.json`: 管理房主、管理员、普通玩家可以使用的命令权限。
- `data/PersistentAccessConfig.json`: 管理持久化管理员、黑名单、IP 封禁和运行中写回。
- `data/RelayLicenseConfig.json`: 当前免授权包保持空白即可，不需要授权码。

## 公共列表提示
@@PUBLIC_LIST_GUIDE@@

## 清理旧目录
如果你从旧的 Relay 目录覆盖升级，可执行:
```sh
./cleanup-pure-start.sh
```
它会删除当前纯 start 模式不再需要的 Relay 配置文件。

## 可选依赖说明
只有在 `data/plugins/` 中检测到 JavaScript 插件时，服务端才会启用:
- `js`
- `regex`
- `truffle-api`
- `graal-sdk`
- `icu4j`
- `Wasm.jar`

这可以降低纯 start 模式下的启动内存和部署包复杂度。
'@

    $operations = @'
CNKD NEXT Start Mode @@VARIANT_TITLE@@上线与运维清单
============================================================

一、启动前检查
------------------------------------------------------------

- Java 版本为 17 或更高。
- 使用全新目录解压，不只替换 Server-All.jar。
- data/ServerConfig.json 可以被 JSON 工具正常解析。
- common.port 没有和其他实例冲突。
- 云服务器安全组和系统防火墙已放行 common.port。
- data/maps 中已经放入需要使用的 .tmx 地图。
- 如果启用 map.enableMapLock，map.lockMap 与地图名一致。
- data/RelayLicenseConfig.json 保持空白即可，免授权包不需要授权码。
- 当前包首次启动会自动完成初始化，无需手动确认。

二、启动后检查
------------------------------------------------------------

建议依次执行:
health
config check
startmode diagnose

如果开放公共列表，再执行:
uplist status

三、权限检查
------------------------------------------------------------

- RoomPermissionConfig.enable 建议保持 true。
- 普通玩家高风险权限建议保持 false。
- 重点检查 player.allowStartGame、player.allowChangeMap、player.allowAddAi、player.allowSetCredits、player.allowSetIncome。
- host/admin 的 allowSpawnUnits、allowAddMoneyInGame、allowFireNukeInGame、allowCloneUnits 属于高风险权限。
- PersistentAccessConfig.admins 中只给可信 UUID 超级管理员。
- IP 或 /24 封禁要谨慎使用，避免误伤正常玩家。

四、公共列表排查
------------------------------------------------------------

@@PUBLIC_LIST_CHECKLIST@@

五、内存风险处理
------------------------------------------------------------

health 显示 WARN:
- 先减少同时在线人数或降低 gameplay.maxPlayer。
- 检查是否导入了过大的模组包。
- 检查 data/replays 和 data/cache 是否长期未清理。

health 显示 CRITICAL:
- 不建议继续开新局。
- 等当前玩家结束后重启实例。
- 如果频繁出现，给 Java 增加 -Xmx，或拆分模组/玩法实例。

六、推荐命令组合
------------------------------------------------------------

轻量健康检查:
health

完整运维检查:
health detail
maintenance plan
config check
startmode diagnose
uplist status

配置修改后检查:
reloadconfig
health
config check

============================================================
建议把这份清单和 CONFIG-HELP-CN.txt 一起交给每位服务器管理员。
'@

    $readme = $readme.Replace("@@VARIANT_TITLE@@", $variantTitle).Replace("@@PUBLIC_LIST_LINE@@", $publicListLine)
    $deployGuide = $deployGuide.Replace("@@VARIANT_TITLE@@", $variantTitle).Replace("@@PUBLIC_LIST_GUIDE@@", $publicListGuide.Trim())
    $operations = $operations.Replace("@@VARIANT_TITLE@@", $variantTitle).Replace("@@PUBLIC_LIST_CHECKLIST@@", $publicListChecklist.Trim())

    Write-Utf8NoBom -Path (Join-Path $DeployDir "README-Linux.txt") -Text ($readme.Trim() + "`n")
    Write-Utf8NoBom -Path (Join-Path $DeployDir "DEPLOY-GUIDE-CN.md") -Text ($deployGuide.Trim() + "`n")
    Write-Utf8NoBom -Path (Join-Path $DeployDir "OPERATIONS-CHECKLIST-CN.txt") -Text ($operations.Trim() + "`n")
}

function Write-NoLicenseConfigSchema {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][bool]$AllowAll
    )

    if ($AllowAll) {
        $publicListSchemaTemplate = Join-Path $BuildRoot "runtime-data\CNKD-NEXT-PublicList-VisualConfig.json"
        if (-not (Test-Path -LiteralPath $publicListSchemaTemplate)) {
            throw "Public-list visual config template not found: $publicListSchemaTemplate"
        }

        Copy-Item -LiteralPath $publicListSchemaTemplate -Destination $Path -Force
        return
    }

    $variantLabel = if ($AllowAll) { "免授权 UPLIST 全功能版本" } else { "免授权禁 UPLIST 版本" }
    $variantDescription = if ($AllowAll) {
        "当前包不需要授权码，允许公共列表 UPLIST；默认 common.autoUpList=false，需要公开时再手动开启。"
    } else {
        "当前包不需要授权码，但 jar 内部固定禁用公共列表 UPLIST；即使 autoUpList=true 也不会上传。"
    }

    $schema = [pscustomobject]@{
        schemaVersion = 1
        key = "cnkd-next-no-license-config"
        label = "CNKD NEXT $variantLabel 配置模板"
        description = $variantDescription
        files = @(
            [pscustomobject]@{
                path = "data/ServerConfig.json"
                key = "cnkd-next-server-config"
                label = "CNKD NEXT 服务端主配置"
                description = "配置端口、服务器名、公共列表、玩法、房间、地图、后台和单房间低延迟参数。"
                fields = @(
                    [pscustomobject]@{
                        key = "common-port"
                        type = "number"
                        label = "服务端端口"
                        valuePath = "common.port"
                        description = "玩家连接使用的 UDP/TCP 端口，需同步放行安全组和系统防火墙。"
                        requiresRestart = $true
                        min = 1
                        max = 65535
                    },
                    [pscustomobject]@{
                        key = "common-server-name"
                        type = "string"
                        label = "服务器名称"
                        valuePath = "common.serverName"
                        description = "公共列表和战役室中显示的主要服务器名。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "common-chat-sender-name"
                        type = "string"
                        label = "消息栏服务端名"
                        valuePath = "common.chatSenderName"
                        description = "服务端提示消息在聊天栏中使用的名称。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "common-auto-uplist"
                        type = "boolean"
                        label = "开放公共列表"
                        valuePath = "common.autoUpList"
                        description = "免授权 UPLIST 包可开启；默认关闭，确认公开服信息后再改为 true。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "common-list-status-suffix-enabled"
                        type = "boolean"
                        label = "列表状态后缀"
                        valuePath = "common.listStatusSuffixEnabled"
                        description = "默认关闭，避免公共列表名称被自动追加状态文本。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "gameplay-max-player"
                        type = "number"
                        label = "最大人数"
                        valuePath = "gameplay.maxPlayer"
                        description = "单房间最大玩家数量。默认 100，实测时应按地图、模组和机器配置下调。"
                        requiresRestart = $true
                        min = 1
                        max = 100
                    },
                    [pscustomobject]@{
                        key = "gameplay-max-unit"
                        type = "number"
                        label = "单位上限"
                        valuePath = "gameplay.maxUnit"
                        description = "全局单位上限。默认 1000，模组房应结合内存和卡顿反馈调整。"
                        requiresRestart = $true
                        min = 1
                        max = 10000
                    },
                    [pscustomobject]@{
                        key = "gameplay-start-credits"
                        type = "number"
                        label = "初始资金"
                        valuePath = "gameplay.defaultStartingCreditsAmount"
                        description = "开局默认初始资金。"
                        requiresRestart = $true
                        min = 0
                    },
                    [pscustomobject]@{
                        key = "gameplay-default-fog"
                        type = "string"
                        label = "默认迷雾"
                        valuePath = "gameplay.defaultFogMode"
                        description = "建议使用 lineOfSight、fog 或 none。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "lobby-enable-gameover-return"
                        type = "boolean"
                        label = "结束返回战役室"
                        valuePath = "lobby.enableGameOverBattleRoomReturn"
                        description = "默认关闭；只有显式开启才自动返回，否则赛后强制重置且玩家需要重新进入。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "lobby-enable-join-readiness-tips"
                        type = "boolean"
                        label = "轻量入房提示"
                        valuePath = "lobby.enableJoinReadinessTips"
                        description = "默认关闭，避免影响原版体验。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "map-enable-lock"
                        type = "boolean"
                        label = "锁定地图"
                        valuePath = "map.enableMapLock"
                        description = "开启后使用 lockMap 指定固定地图。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "map-lock-map"
                        type = "string"
                        label = "锁定地图名"
                        valuePath = "map.lockMap"
                        description = "固定地图名称，应与 data/maps 中地图文件或游戏地图名匹配。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "console-verbosity"
                        type = "string"
                        label = "控制台日志级别"
                        valuePath = "console.verbosity"
                        description = "建议 normal 或 quiet，排查时再临时提高。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "low-latency-enabled"
                        type = "boolean"
                        label = "单房间低延迟"
                        valuePath = "singleRoomLowLatency.enabled"
                        description = "默认开启；如遇兼容问题可临时关闭用于对照排查。"
                        requiresRestart = $true
                    }
                )
            },
            [pscustomobject]@{
                path = "data/RoomPermissionConfig.json"
                key = "cnkd-next-room-permissions"
                label = "CNKD NEXT 房间权限配置"
                description = "配置房主、管理员和普通玩家可使用的房间操作能力。"
                fields = @(
                    [pscustomobject]@{
                        key = "room-permission-enable"
                        type = "boolean"
                        label = "启用权限控制"
                        valuePath = "enable"
                        description = "建议保持开启，避免普通玩家获得高风险房间操作。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "player-allow-start-game"
                        type = "boolean"
                        label = "玩家允许开局"
                        valuePath = "player.allowStartGame"
                        description = "公开服建议关闭，由房主或管理员控制开局。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "player-allow-vote-game-over"
                        type = "boolean"
                        label = "玩家允许投降/结束投票"
                        valuePath = "player.allowVoteGameOver"
                        description = "保持与原版投降体验兼容时建议谨慎开启。"
                        requiresRestart = $true
                    }
                )
            },
            [pscustomobject]@{
                path = "data/RelayLicenseConfig.json"
                key = "cnkd-next-relay-license"
                label = "CNKD NEXT 免授权兼容配置"
                description = "免授权包保持 licenseKey 和 leaseEndpoint 为空即可。"
                fields = @(
                    [pscustomobject]@{
                        key = "relay-license-key"
                        type = "string"
                        label = "授权码"
                        valuePath = "licenseKey"
                        description = "免授权包保持空字符串。"
                        requiresRestart = $true
                    },
                    [pscustomobject]@{
                        key = "relay-requested-features"
                        type = "stringArray"
                        label = "请求能力"
                        valuePath = "requestedFeatures"
                        description = "普通管理员不要修改；AllFeatures 包默认包含 uplist。"
                        requiresRestart = $true
                    }
                )
            }
        )
    }

    $json = $schema | ConvertTo-Json -Depth 32
    Write-Utf8NoBom -Path $Path -Text ($json + "`n")
}

function Write-AdvertisingRoomUserGuide {
    param([Parameter(Mandatory = $true)][string]$Path)

    $text = @'
CNKD NEXT 免授权 UPLIST 广告房配置说明
============================================================

这份说明只面向广告房版本。
此版本允许公共列表 UPLIST，但默认不会主动开放到列表，也不会默认开启列表状态后缀，避免刚解压就被误公开。

一、这个包适合做什么
============================================================

- 作为引流入口服或广告房使用。
- 玩家先真实进入广告房，再由服务端自动提示并跳转到指定主服或大厅。
- 可在后台/面板中修改广告跳转目标、文案和延迟。
- 不需要填写授权码，RelayLicenseConfig.json 可以保持空白。

二、你最先要改的文件
============================================================

1. data/ServerConfig.json
- 这是最重要的配置文件。
- 重点检查 common.serverName、common.port、gameplay.maxPlayer、map.lockMap、advertisingRedirect 这几组字段。

2. data/RoomPermissionConfig.json
- 这是房间权限配置。
- 用来控制房主、管理员和普通玩家能不能执行踢人、换图、开局、加 AI、改资金等操作。

3. ADVERTISING-ROOM-CONFIG-SCHEMA.json
- 这是给面板或可视化配置器使用的 schema 文件。
- 你可以直接把它上传到面板，让面板按字段渲染配置项。

三、广告房模式核心字段
============================================================

advertisingRedirect.enabled
- 默认 false。
- true 时启用广告房跳转。

advertisingRedirect.targetHost
- 默认 101.43.41.22。
- 这是跳转目标地址，可以改成你的主服或中转服地址。

advertisingRedirect.targetPort
- 默认 5123。
- 这是跳转目标端口。

advertisingRedirect.targetRoomId
- 可留空。
- 如果你要跳转到固定房间，可以在这里填写房间 ID。

advertisingRedirect.redirectTiming
- 默认 afterJoin。
- 建议保持 afterJoin，不要改成别的值。
- 这样玩家会先真正进到广告房，再收到跳转提示。

advertisingRedirect.joinProbeSafeMode
- 默认 true。
- 建议保持 true，减少列表误判和边缘客户端兼容问题。

advertisingRedirect.delaySeconds
- 默认 2。
- 表示玩家进入后，等待多少秒再跳转。

advertisingRedirect.showNotice
- 默认 true。
- true 时会先给玩家显示提示文案。

advertisingRedirect.noticeBeforeRedirect
- 跳转前提示语。

advertisingRedirect.noticeOnManualFallback
- 自动跳转失败时给玩家看的手动引导提示。

advertisingRedirect.kickAfterRedirect
- 默认 true。
- true 时玩家跳转后会从广告房移出，减少占位。

advertisingRedirect.cooldownSecondsPerPlayer
- 默认 10。
- 防止同一个玩家重复触发跳转。

advertisingRedirect.maxRedirectAttemptsPerConnection
- 默认 1。
- 防止连接循环跳转。

advertisingRedirect.allowLocalhostTarget
- 默认 false。
- 除非你在本机联调，否则不要打开。

advertisingRedirect.allowPrivateIpTarget
- 默认 true。
- 如果目标服在内网，可以保持开启。

advertisingRedirect.disableWhenRankedCoreNode
- 默认 true。
- 作为排位核心服时建议保持开启，避免误跳。

advertisingRedirect.disableWhenRankedShardNode
- 默认 true。
- 作为分片服时建议保持开启，避免误跳。

advertisingRedirect.adminBypass
- 默认 true。
- 管理员通常可以绕过跳转，便于排查问题。

advertisingRedirect.logRedirect
- 默认 true。
- 建议保持开启，方便追踪跳转问题。

四、列表相关字段建议
============================================================

- common.autoUpList 默认 false。
- 如果你想真正开放到公共列表，再手动改成 true。
- common.listStatusSuffixEnabled 默认 false。
- 广告房不建议开启状态后缀，避免影响文案展示和排序观感。
- common.listPinnedCharacterCount 和 common.listNamePinnedCharacterCount 可按需调整。
- 如果你要做列表置顶文案，建议先小范围测试。

五、首次部署步骤
============================================================

1. 解压压缩包。
2. 修改 data/ServerConfig.json。
3. 如需权限控制，再修改 data/RoomPermissionConfig.json。
4. 如果要做广告房跳转，先把 advertisingRedirect.enabled 改为 true。
5. 把 advertisingRedirect.targetHost 和 advertisingRedirect.targetPort 改成你的主服地址。
6. 启动服务端。
7. 在控制台执行 advertising status，查看广告房是否启用、目标地址和跳转统计。
8. 在控制台执行 advertising check，主动探测目标 IP 和端口是否可连接。

六、运行状态和排查命令
============================================================

advertising status
- 查看广告房开关、目标地址、跳转次数、失败次数、冷却拦截次数、管理员绕过次数。
- 建议每次修改广告房配置后执行一次。

advertising check
- 立即探测 advertisingRedirect.targetHost 和 advertisingRedirect.targetPort 是否可连接。
- 如果返回 failed，请检查目标服是否启动、云服务器安全组、防火墙和端口映射。

config check
- 检查 ServerConfig.json 中广告房目标、端口、UPLIST 和常见高风险配置。

后台/API 排查
- 如果你接入了管理面板，可以通过管理员接口调用 advertising_status 查看广告房运行快照。
- 如果你接入了管理面板，可以通过管理员接口调用 advertising_check 主动探测目标端口。
- 这两个动作必须走 Admin API Token 和白名单限制，不建议暴露给普通玩家。
- 调用 advertising_check 后会刷新状态缓存，面板应立即重新读取状态接口。

七、常见问题
============================================================

1. 玩家看起来像进不去房间
- 先检查广告房是否真的启用了跳转。
- 再检查 targetHost 和 targetPort 是否能被外网访问。
- 执行 advertising check，确认目标端口不是 failed。
- 如果你把目标设成了本机地址或同一房间地址，可能会形成跳转循环。

2. 玩家进房后没有跳转
- 检查 advertisingRedirect.enabled 是否为 true。
- 检查 redirectTiming 是否保持 afterJoin。
- 检查日志中是否有 redirect 失败提示。

3. 想让广告房继续保留列表展示
- 这是正常的。
- 广告房本身可以上列表，但它的核心职责仍是“进房后跳转引流”。

4. 想在后台快速改文案
- 直接改 ServerConfig.json 里的 advertisingRedirect.noticeBeforeRedirect 和 noticeOnManualFallback。

八、给面板管理员的建议
============================================================

- 先把这份包当成“广告房模板”。
- 如果你后面要做多个广告房，可以复制这个目录再分别改 targetHost、targetPort 和文案。
- 如果你要把广告房当成统一入口，建议只让它做跳转和引流，不要在里面堆太复杂的玩法配置。
'@

    Write-Utf8NoBom -Path $Path -Text ($text.Trim() + "`n")
}

function Write-AdvertisingRoomSchema {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BaseSchemaPath
    )

    if (-not (Test-Path -LiteralPath $BaseSchemaPath)) {
        throw "Base schema not found: $BaseSchemaPath"
    }

    $schema = [System.IO.File]::ReadAllText($BaseSchemaPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $serverFile = $schema.files | Where-Object { $_.path -eq "data/ServerConfig.json" } | Select-Object -First 1
    if ($null -eq $serverFile) {
        throw "ServerConfig schema entry not found in $BaseSchemaPath"
    }
    Set-JsonProperty -Object $serverFile -Name "key" -Value "cnkd-next-advertising-room-server-config"
    Set-JsonProperty -Object $serverFile -Name "label" -Value "CNKD NEXT 广告房服务端配置"
    Set-JsonProperty -Object $serverFile -Name "description" -Value "免授权允许 UPLIST 广告房版本的常用配置。默认不开放公共列表、不启用广告跳转，管理员可在面板中自行开启。"

    $roomFile = $schema.files | Where-Object { $_.path -eq "data/RoomPermissionConfig.json" } | Select-Object -First 1
    if ($null -ne $roomFile) {
        Set-JsonProperty -Object $roomFile -Name "key" -Value "cnkd-next-advertising-room-room-permissions"
        Set-JsonProperty -Object $roomFile -Name "label" -Value "CNKD NEXT 广告房房间权限配置"
        Set-JsonProperty -Object $roomFile -Name "description" -Value "配置广告房中房主、管理员和普通玩家可使用的操作权限。公开广告房建议谨慎开放高风险权限。"
    }

    $autoUpListField = $serverFile.fields | Where-Object { $_.key -eq "common-auto-uplist" } | Select-Object -First 1
    if ($null -ne $autoUpListField) {
        Set-JsonProperty -Object $autoUpListField -Name "description" -Value "当前包允许 UPLIST，但默认关闭。需要公开到公共列表时再手动开启。"
    }
    $listSuffixField = $serverFile.fields | Where-Object { $_.key -eq "common-list-status-suffix-enabled" } | Select-Object -First 1
    if ($null -ne $listSuffixField) {
        Set-JsonProperty -Object $listSuffixField -Name "description" -Value "广告房默认关闭列表状态后缀，避免入口文案被自动追加状态影响展示。"
    }

    $existingKeys = @()
    if ($serverFile.fields) {
        $existingKeys = @($serverFile.fields | ForEach-Object { $_.key })
    }

    $extraFields = @(
        [pscustomobject]@{
            key = "advertising-enabled"
            type = "boolean"
            label = "广告房模式"
            valuePath = "advertisingRedirect.enabled"
            description = "开启后玩家真实进入广告房后会触发跳转逻辑。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-target-host"
            type = "string"
            label = "跳转目标主机"
            valuePath = "advertisingRedirect.targetHost"
            description = "跳转到的目标主机名或 IP。默认值适合常用主服引流场景。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-target-port"
            max = 65535
            min = 1
            type = "number"
            label = "跳转目标端口"
            valuePath = "advertisingRedirect.targetPort"
            description = "目标服务端口。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-target-room-id"
            type = "string"
            label = "跳转目标房间 ID"
            valuePath = "advertisingRedirect.targetRoomId"
            description = "如需跳转到指定房间，可在这里填写。留空表示跳转到目标主机默认入口。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-redirect-timing"
            type = "select"
            label = "跳转时机"
            options = @("afterJoin")
            valuePath = "advertisingRedirect.redirectTiming"
            description = "当前版本固定在玩家真实进入后再跳转，避免列表误判。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-join-probe-safe-mode"
            type = "boolean"
            label = "进入探测安全模式"
            valuePath = "advertisingRedirect.joinProbeSafeMode"
            description = "建议开启。它会让跳转链路更稳，降低边缘客户端误判。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-delay-seconds"
            max = 30
            min = 0
            type = "number"
            label = "跳转延迟秒数"
            valuePath = "advertisingRedirect.delaySeconds"
            description = "玩家进入广告房后等待多少秒再跳转。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-show-notice"
            type = "boolean"
            label = "显示跳转提示"
            valuePath = "advertisingRedirect.showNotice"
            description = "开启后会先向玩家展示跳转提示文案。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-notice-before-redirect"
            type = "string"
            label = "跳转前提示语"
            valuePath = "advertisingRedirect.noticeBeforeRedirect"
            description = "玩家进入广告房后、正式跳转前显示的提示。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-notice-on-manual-fallback"
            type = "string"
            label = "手动引导提示"
            valuePath = "advertisingRedirect.noticeOnManualFallback"
            description = "自动跳转失败时给玩家看的手动加入提示。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-kick-after-redirect"
            type = "boolean"
            label = "跳转后移出广告房"
            valuePath = "advertisingRedirect.kickAfterRedirect"
            description = "建议开启，避免广告房被跳转后的玩家继续占位。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-cooldown-seconds-per-player"
            max = 600
            min = 0
            type = "number"
            label = "单玩家跳转冷却"
            valuePath = "advertisingRedirect.cooldownSecondsPerPlayer"
            description = "同一玩家再次触发跳转前需要等待的秒数。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-max-attempts-per-connection"
            max = 10
            min = 1
            type = "number"
            label = "每次连接最大跳转次数"
            valuePath = "advertisingRedirect.maxRedirectAttemptsPerConnection"
            description = "建议保持较小值，避免形成循环跳转。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-allow-localhost-target"
            type = "boolean"
            label = "允许本机目标"
            valuePath = "advertisingRedirect.allowLocalhostTarget"
            description = "调试时可临时开启，正式环境建议关闭。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-allow-private-ip-target"
            type = "boolean"
            label = "允许内网目标"
            valuePath = "advertisingRedirect.allowPrivateIpTarget"
            description = "如果目标服在内网，可以保持开启。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-disable-when-ranked-core-node"
            type = "boolean"
            label = "排位核心服自动禁用"
            valuePath = "advertisingRedirect.disableWhenRankedCoreNode"
            description = "作为排位核心服时建议保持开启，避免误跳。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-disable-when-ranked-shard-node"
            type = "boolean"
            label = "排位分片自动禁用"
            valuePath = "advertisingRedirect.disableWhenRankedShardNode"
            description = "作为排位分片时建议保持开启，避免误跳。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-admin-bypass"
            type = "boolean"
            label = "管理员绕过跳转"
            valuePath = "advertisingRedirect.adminBypass"
            description = "管理员通常应保留绕过能力，便于排查。"
            requiresRestart = $true
        },
        [pscustomobject]@{
            key = "advertising-log-redirect"
            type = "boolean"
            label = "记录跳转日志"
            valuePath = "advertisingRedirect.logRedirect"
            description = "建议保持开启，方便追踪跳转问题。"
            requiresRestart = $true
        }
    )

    $serverFile.fields = @($serverFile.fields) + ($extraFields | Where-Object { $existingKeys -notcontains $_.key })

    $json = $schema | ConvertTo-Json -Depth 100
    Write-Utf8NoBom -Path $Path -Text ($json + "`n")
}

function Read-JarLicenseMode {
    param([Parameter(Mandatory = $true)][string]$JarPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry("cnkd-release-mode.properties")
        if ($null -eq $entry) {
            throw "cnkd-release-mode.properties not found in $JarPath"
        }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            return $reader.ReadToEnd().Trim()
        } finally {
            $reader.Close()
        }
    } finally {
        $zip.Dispose()
    }
}

function Write-ModeReadme {
    param(
        [Parameter(Mandatory = $true)][string]$DeployDir,
        [Parameter(Mandatory = $true)][bool]$AllowAll,
        [Parameter(Mandatory = $true)][string]$JarSha256,
        [bool]$AdvertisingRoom = $false
    )

    if ($AllowAll -and $AdvertisingRoom) {
        $releaseLines = @(
            "CNKD NEXT $Version - 免授权 UPLIST 广告房版本",
            "",
            "用途:",
            "- 不需要填写授权码。",
            "- UPLIST 允许使用，但 data/ServerConfig.json 的 common.autoUpList 默认仍为 false，避免解压后误公开。",
            "- 广告房模式 advertisingRedirect.enabled 默认不启用。",
            "- 默认跳转目标为 101.43.41.22:5123，可在 data/ServerConfig.json 中自行修改。",
            "- 玩家会先真实进入广告房，再收到提示并触发跳转，避免公共列表误判为无法进入的房间。",
            "- 包内额外提供 CONFIG-HELP-CN.txt 与 ADVERTISING-ROOM-CONFIG-SCHEMA.json，便于面板和用户自助配置。",
            "",
            "首次配置:",
            "1. 修改 data/ServerConfig.json 的 common.serverName、common.subtitle、common.port。",
            "2. 如需开放公共列表，把 common.autoUpList 改为 true。",
            "3. 保持 common.listStatusSuffixEnabled 为 false，避免列表状态后缀干扰广告入口展示。",
            "4. 如需启用广告房跳转，把 advertisingRedirect.enabled 改为 true。",
            "5. 按需修改 advertisingRedirect.targetHost 与 advertisingRedirect.targetPort。",
            "6. 确认 advertisingRedirect.redirectTiming 为 afterJoin，joinProbeSafeMode 为 true。",
            "7. data/RelayLicenseConfig.json 可以保持空白，不需要授权码。"
        )
        $verifyLines = @(
            "CNKD NEXT $Version - 免授权 UPLIST 广告房版本校验说明",
            "",
            "构建模式:",
            "- jar 内部资源: cnkd-release-mode.properties",
            "- 期望内容: licenseMode=allow_all",
            "- 行为边界: 不需要授权码；UPLIST 能力由本地构建模式允许。",
            "",
            "默认配置:",
            "- data/ServerConfig.json 的 common.autoUpList 默认为 false。",
            "- data/ServerConfig.json 的 common.listStatusSuffixEnabled 默认为 false。",
            "- data/ServerConfig.json 的 advertisingRedirect.enabled 默认为 false。",
            "- 默认跳转目标: advertisingRedirect.targetHost=101.43.41.22, targetPort=5123。",
            "- 跳转时机: advertisingRedirect.redirectTiming=afterJoin。",
            "- 包内提供 CONFIG-HELP-CN.txt 与 ADVERTISING-ROOM-CONFIG-SCHEMA.json，便于面板导入和人工排查。",
            "",
            "Server-All.jar SHA256:",
            $JarSha256
        )
    } elseif ($AllowAll) {
        $releaseLines = @(
            "CNKD NEXT $Version - 免授权全功能版本",
            "",
            "用途:",
            "- 不需要填写授权码。",
            "- UPLIST、Relay Server、核心路由、自定义房间、Rooms API、Admin API 均由 jar 内部本地模式允许。",
            "- 为避免解压后误公开，默认仍将 data/ServerConfig.json 的 common.autoUpList 保持为 false；需要开放到公共列表时手动改为 true。",
            "- 默认关闭 common.listStatusSuffixEnabled，避免公共列表名称被自动追加状态后缀。",
            "",
            "首次配置:",
            "1. 修改 data/ServerConfig.json 的端口、服务器名、人数、地图、模组等配置。",
            "2. 如需公共列表上传，把 data/ServerConfig.json -> common.autoUpList 改为 true。",
            "3. data/RelayLicenseConfig.json 可以保持空白，不需要授权码。"
        )
        $verifyLines = @(
            "CNKD NEXT $Version - 免授权全功能版本校验说明",
            "",
            "构建模式:",
            "- jar 内部资源: cnkd-release-mode.properties",
            "- 期望内容: licenseMode=allow_all",
            "- 行为边界: 不需要授权码；UPLIST、Relay Server、核心路由、自定义房间、Rooms API、Admin API 均由本地构建模式允许。",
            "",
            "默认配置:",
            "- data/RelayLicenseConfig.json 的 leaseEndpoint 与 licenseKey 均为空。",
            "- data/ServerConfig.json 的 common.autoUpList 默认为 false。",
            "- data/ServerConfig.json 的 common.listStatusSuffixEnabled 默认为 false。",
            "- 如需开放到公共列表，请手动把 common.autoUpList 改为 true。",
            "",
            "Server-All.jar SHA256:",
            $JarSha256
        )
    } else {
        $releaseLines = @(
            "CNKD NEXT $Version - 免授权禁 UPLIST 版本",
            "",
            "用途:",
            "- 不需要填写授权码。",
            "- 本地开房、房间管理、模组/地图链路可用。",
            "- UPLIST/公共列表上传在 jar 内部被固定禁止，即使把 data/ServerConfig.json 里的 common.autoUpList 改成 true 也不会上传。",
            "",
            "首次配置:",
            "1. 修改 data/ServerConfig.json 的端口、服务器名、人数、地图、模组等配置。",
            "2. data/RelayLicenseConfig.json 可以保持空白，不需要授权码。",
            "3. 如果需要公共列表，请不要使用本版本，请改用 AllFeatures 版本。"
        )
        $verifyLines = @(
            "CNKD NEXT $Version - 免授权禁 UPLIST 版本校验说明",
            "",
            "构建模式:",
            "- jar 内部资源: cnkd-release-mode.properties",
            "- 期望内容: licenseMode=no_uplist",
            "- 行为边界: 不需要授权码；UPLIST/公共列表上传固定禁止；本地开房、房间管理、模组/地图链路可用。",
            "",
            "默认配置:",
            "- data/RelayLicenseConfig.json 的 leaseEndpoint 与 licenseKey 均为空。",
            "- data/ServerConfig.json 的 common.autoUpList 默认为 false。",
            "- data/ServerConfig.json 的 common.listStatusSuffixEnabled 默认为 false。",
            "- 即使管理员误把 common.autoUpList 改成 true，本版本也不会开放到公共列表。",
            "",
            "Server-All.jar SHA256:",
            $JarSha256
        )
    }

    Write-Utf8NoBom -Path (Join-Path $DeployDir "RELEASE-MODE-CN.txt") -Text (($releaseLines -join "`n") + "`n")
    Write-Utf8NoBom -Path (Join-Path $DeployDir "BUILD-VERIFY-CN.txt") -Text (($verifyLines -join "`n") + "`n")
}

function New-PortableZip {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDir,
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$RootFolderName
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $ZipPath) {
        Remove-Item -LiteralPath $ZipPath -Force
    }

    $resolvedSource = [System.IO.Path]::GetFullPath($SourceDir).TrimEnd('\', '/')
    $zipStream = [System.IO.File]::Open($ZipPath, [System.IO.FileMode]::CreateNew)
    $zip = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        [void]$zip.CreateEntry("$RootFolderName/")

        Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -Directory |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($resolvedSource.Length).TrimStart('\', '/')
                if ($relative.Length -gt 0) {
                    $entryName = "$RootFolderName/" + ($relative -replace '\\', '/') + "/"
                    [void]$zip.CreateEntry($entryName)
                }
            }

        Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -File |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($resolvedSource.Length).TrimStart('\', '/')
                $entryName = "$RootFolderName/" + ($relative -replace '\\', '/')
                $entry = $zip.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $_.LastWriteTime

                $entryStream = $entry.Open()
                try {
                    $sourceStream = [System.IO.File]::OpenRead($_.FullName)
                    try {
                        $sourceStream.CopyTo($entryStream)
                    } finally {
                        $sourceStream.Dispose()
                    }
                } finally {
                    $entryStream.Dispose()
                }
            }
    } finally {
        $zip.Dispose()
        $zipStream.Dispose()
    }
}

function Assert-PackageZip {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$FolderName,
        [Parameter(Mandatory = $true)][string]$ExpectedJarMode,
        [bool]$AdvertisingRoom = $false
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $nestedZip = $zip.Entries |
            Where-Object { $_.FullName.EndsWith(".zip", [System.StringComparison]::OrdinalIgnoreCase) } |
            Select-Object -First 1
        if ($null -ne $nestedZip) {
            throw "Nested zip detected in package: $($nestedZip.FullName)"
        }
        $windowsPathEntry = $zip.Entries |
            Where-Object { $_.FullName.Contains('\') } |
            Select-Object -First 1
        if ($null -ne $windowsPathEntry) {
            throw "Windows path separator detected in package: $($windowsPathEntry.FullName)"
        }
    } finally {
        $zip.Dispose()
    }

    $extractRoot = Join-Path $VerifyRoot ([System.IO.Path]::GetFileNameWithoutExtension($ZipPath))
    Remove-SafeBuildDirectory -Path $extractRoot
    New-Item -ItemType Directory -Path $extractRoot -Force | Out-Null
    try {
        $jar = Join-Path $extractRoot "Server-All.jar"
        $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
        try {
            $jarEntry = $zip.GetEntry("$FolderName/Server-All.jar")
            if ($null -eq $jarEntry) {
                throw "Package missing Server-All.jar entry: $FolderName/Server-All.jar"
            }
            $inputStream = $jarEntry.Open()
            $outputStream = [System.IO.File]::Create($jar)
            try {
                $inputStream.CopyTo($outputStream)
            } finally {
                $outputStream.Dispose()
                $inputStream.Dispose()
            }
        } finally {
            $zip.Dispose()
        }
        $jarMode = Read-JarLicenseMode -JarPath $jar
        if ($jarMode -ne $ExpectedJarMode) {
            throw "Unexpected extracted jar mode. expected=$ExpectedJarMode actual=$jarMode"
        }
    } finally {
        Remove-SafeBuildDirectory -Path $extractRoot
    }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $helpEntry = $zip.GetEntry("$FolderName/CONFIG-HELP-CN.txt")
        if ($null -eq $helpEntry) {
            throw "Package missing CONFIG-HELP-CN.txt"
        }
        $helpReader = New-Object System.IO.StreamReader($helpEntry.Open(), [System.Text.Encoding]::UTF8)
        try {
            $helpText = $helpReader.ReadToEnd()
            if ($AdvertisingRoom) {
                if ($helpText -notmatch "免授权 UPLIST 广告房配置说明") {
                    throw "Advertising help text does not contain the expected title"
                }
                if ($helpText -match "licenseKey") {
                    throw "Advertising help text still mentions licenseKey"
                }
            } else {
                $requiredHelpFragments = @(
                    "配置项完整说明",
                    "本包为免授权发布包",
                    "common.autoUpList",
                    "common.publicListEntries",
                    "gameplay.defIncome",
                    "lobby.enableGameOverBattleRoomReturn",
                    "map.lockMap",
                    "ranked.coreNodeEnabled",
                    "advertisingRedirect.enabled",
                    "data/RoomPermissionConfig.json",
                    "host.allowStartGame",
                    "player.allowVoteGameOver",
                    "data/PersistentAccessConfig.json",
                    "PersistentAccessConfig.admins",
                    "data/RelayLicenseConfig.json",
                    "RelayLicenseConfig.licenseKey"
                )
                foreach ($fragment in $requiredHelpFragments) {
                    if (-not $helpText.Contains($fragment)) {
                        throw "Config help missing required fragment '$fragment'"
                    }
                }
                if ($ExpectedJarMode -eq "licenseMode=no_uplist" -and -not $helpText.Contains("即使 common.autoUpList=true，本版本也不会上传公共列表")) {
                    throw "No-UPLIST help text does not explain disabled public-list behavior"
                }
                if ($ExpectedJarMode -eq "licenseMode=allow_all" -and -not $helpText.Contains("如果你确认要开放公共列表，把 common.autoUpList 改为 true 后重启服务端")) {
                    throw "UPLIST help text does not explain how to enable public-list upload"
                }

                $docNames = @("README-Linux.txt", "DEPLOY-GUIDE-CN.md", "OPERATIONS-CHECKLIST-CN.txt")
                foreach ($docName in $docNames) {
                    $docEntry = $zip.GetEntry("$FolderName/$docName")
                    if ($null -eq $docEntry) {
                        throw "Package missing $docName"
                    }
                    $docReader = New-Object System.IO.StreamReader($docEntry.Open(), [System.Text.Encoding]::UTF8)
                    try {
                        $docText = $docReader.ReadToEnd()
                        $forbiddenFragments = @("首次许可确认", "请输入 Yes", "输入 Yes", "licenseKey 有 uplist 权限", "填写 licenseKey", "公共发布版")
                        foreach ($forbidden in $forbiddenFragments) {
                            if ($docText.Contains($forbidden)) {
                                throw "$docName contains forbidden legacy fragment '$forbidden'"
                            }
                        }
                        if (-not $docText.Contains("免授权")) {
                            throw "$docName does not clearly identify the no-license package"
                        }
                    } finally {
                        $docReader.Dispose()
                    }
                }
            }
        } finally {
            $helpReader.Dispose()
        }

        if ($AdvertisingRoom) {
            $schemaEntry = $zip.GetEntry("$FolderName/ADVERTISING-ROOM-CONFIG-SCHEMA.json")
            if ($null -eq $schemaEntry) {
                throw "Advertising package missing ADVERTISING-ROOM-CONFIG-SCHEMA.json"
            }

            $schemaReader = New-Object System.IO.StreamReader($schemaEntry.Open(), [System.Text.Encoding]::UTF8)
            try {
                $schemaText = $schemaReader.ReadToEnd()
                if ($schemaText -notmatch "advertisingRedirect") {
                    throw "Advertising schema does not contain advertisingRedirect fields"
                }
            } finally {
                $schemaReader.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
}

function Publish-Variant {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][string]$FolderName,
        [Parameter(Mandatory = $true)][string]$ExpectedJarMode,
        [Parameter(Mandatory = $true)][bool]$AllowAll,
        [bool]$AdvertisingRoom = $false
    )

    $source = Invoke-VariantBuild -Mode $Mode
    $target = Join-Path $ReleasesRoot $FolderName
    Remove-SafeDirectory -Path $target
    Copy-Item -LiteralPath $source -Destination $target -Recurse

    Update-ServerConfig -Path (Join-Path $target "data\ServerConfig.json") -AdvertisingRoom:$AdvertisingRoom
    Write-RelayLicenseConfig -Path (Join-Path $target "data\RelayLicenseConfig.json") -AllowAll:$AllowAll

    $jar = Join-Path $target "Server-All.jar"
    $jarMode = Read-JarLicenseMode -JarPath $jar
    if ($jarMode -ne $ExpectedJarMode) {
        throw "Unexpected jar mode in $jar. expected=$ExpectedJarMode actual=$jarMode"
    }

    [void]([System.IO.File]::ReadAllText((Join-Path $target "data\ServerConfig.json"), [System.Text.Encoding]::UTF8) | ConvertFrom-Json)
    [void]([System.IO.File]::ReadAllText((Join-Path $target "data\RelayLicenseConfig.json"), [System.Text.Encoding]::UTF8) | ConvertFrom-Json)

    $jarSha = (Get-FileHash -Algorithm SHA256 $jar).Hash
    Write-ModeReadme -DeployDir $target -AllowAll:$AllowAll -JarSha256 $jarSha -AdvertisingRoom:$AdvertisingRoom
    if ($AdvertisingRoom) {
        Write-AdvertisingRoomUserGuide -Path (Join-Path $target "CONFIG-HELP-CN.txt")
        Write-AdvertisingRoomSchema -Path (Join-Path $target "ADVERTISING-ROOM-CONFIG-SCHEMA.json") -BaseSchemaPath (Join-Path $ReleasesRoot "CNKD-NEXT-$Version-NoLicense-AllFeatures-CONFIG-SCHEMA.json")
    } else {
        Write-NoLicenseConfigHelp -Path (Join-Path $target "CONFIG-HELP-CN.txt") -AllowAll:$AllowAll
        Write-NoLicenseDeploymentDocs -DeployDir $target -AllowAll:$AllowAll
        if ($AllowAll) {
            Write-NoLicenseConfigSchema -Path (Join-Path $ReleasesRoot "CNKD-NEXT-$Version-NoLicense-AllFeatures-CONFIG-SCHEMA.json") -AllowAll:$true
        }
    }

    $zipPath = Join-Path $ReleasesRoot ($FolderName + ".zip")
    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }
    New-PortableZip -SourceDir $target -ZipPath $zipPath -RootFolderName $FolderName

    Assert-PackageZip -ZipPath $zipPath -FolderName $FolderName -ExpectedJarMode $ExpectedJarMode -AdvertisingRoom:$AdvertisingRoom

    $zipSha = (Get-FileHash -Algorithm SHA256 $zipPath).Hash
    $shaSidecar = Join-Path $ReleasesRoot ($FolderName + ".sha256.txt")
    Write-Utf8NoBom -Path $shaSidecar -Text (
        "SHA256 ($FolderName.zip) = $zipSha`n" +
        "SHA256 ($FolderName/Server-All.jar) = $jarSha`n" +
        "JarMode = $jarMode`n"
    )
    [pscustomobject]@{
        Folder = $target
        Zip = $zipPath
        Sha256File = $shaSidecar
        JarMode = $jarMode
        JarSha256 = $jarSha
        ZipSha256 = $zipSha
    }
}

New-Item -ItemType Directory -Path $ReleasesRoot -Force | Out-Null

$results = @()
$results += Publish-Variant `
    -Mode "no_uplist" `
    -FolderName "CNKD-NEXT-$Version-NoLicense-NoUpList" `
    -ExpectedJarMode "licenseMode=no_uplist" `
    -AllowAll:$false

$results += Publish-Variant `
    -Mode "allow_all" `
    -FolderName "CNKD-NEXT-$Version-NoLicense-AllFeatures" `
    -ExpectedJarMode "licenseMode=allow_all" `
    -AllowAll:$true

$results += Publish-Variant `
    -Mode "allow_all" `
    -FolderName "CNKD-NEXT-$Version-NoLicense-UpList-AdvertisingRoom" `
    -ExpectedJarMode "licenseMode=allow_all" `
    -AllowAll:$true `
    -AdvertisingRoom:$true

Push-Location $BuildRoot
try {
    $gradleOutput = & $Gradle ":Server-All:jar" "-PcnkdLicenseMode=allow_all" "--console=plain"
    $gradleOutput | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restore allow_all Server-All.jar build output"
    }
} finally {
    Pop-Location
}

$results | Format-List
