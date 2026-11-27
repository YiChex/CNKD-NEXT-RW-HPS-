param(
    [string]$Version = "",
    [string]$LicenseMode = "no_uplist",
    [string]$PluginApiVersion = "1.3.0",
    # OPT-20261014-001: 预装插件目录（可选）。
    # 指向一个目录，其中的 *.jar / *.zip 会被复制进包的 data/plugins/，
    # 并用 cnkd-plugin-tool.jar 生成与之精确匹配的 plugin-lock.json。
    # 留空 = 与历史行为完全一致（不预装任何插件）。
    [string]$BundlePluginDirectory = "",
    # 写入 plugin-lock.json 的 approvedBy 字段（审计用，不参与校验）。
    [string]$BundlePluginApprovedBy = "release-pkg"
)

$ErrorActionPreference = "Stop"

if ($LicenseMode -notin @("no_uplist", "allow_all")) {
    throw "LicenseMode must be 'no_uplist' or 'allow_all'; received '$LicenseMode'"
}

$BuildRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $BuildRoot
$ReleasesRoot = Join-Path $ProjectRoot "releases"
$Gradle = Join-Path $BuildRoot "gradlew.bat"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$coreVersionSource = Join-Path $BuildRoot "Server-Core\src\main\java\net\rwhps\server\data\global\Data.kt"
$versionMatch = [regex]::Match([System.IO.File]::ReadAllText($coreVersionSource), 'SERVER_CORE_VERSION = "CNKD NEXT ([^"]+)"')
if (-not $versionMatch.Success) {
    throw "Cannot read SERVER_CORE_VERSION from $coreVersionSource"
}
$coreReleaseVersion = $versionMatch.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = $coreReleaseVersion
} elseif ($Version -ne $coreReleaseVersion) {
    throw "Release version $Version differs from displayed version $coreReleaseVersion"
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Remove-SafeDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$SafeRoot
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath($SafeRoot).TrimEnd('\') + '\'
    $resolvedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete path outside safe root: $resolvedPath"
    }
    Remove-Item -LiteralPath $Path -Recurse -Force
}

function New-PortableZip {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDir,
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$RootFolderName
    )

    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
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
                    [void]$zip.CreateEntry("$RootFolderName/" + ($relative -replace '\\', '/') + "/")
                }
            }

        Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -File |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($resolvedSource.Length).TrimStart('\', '/')
                $entry = $zip.CreateEntry("$RootFolderName/" + ($relative -replace '\\', '/'), [System.IO.Compression.CompressionLevel]::Optimal)
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

function New-PortableFlatZip {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDir,
        [Parameter(Mandatory = $true)][string]$ZipPath
    )

    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $ZipPath) {
        Remove-Item -LiteralPath $ZipPath -Force
    }

    $resolvedSource = [System.IO.Path]::GetFullPath($SourceDir).TrimEnd('\', '/')
    $zipStream = [System.IO.File]::Open($ZipPath, [System.IO.FileMode]::CreateNew)
    $zip = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -Directory |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($resolvedSource.Length).TrimStart('\', '/')
                if ($relative.Length -gt 0) {
                    [void]$zip.CreateEntry(($relative -replace '\\', '/') + "/")
                }
            }

        Get-ChildItem -LiteralPath $SourceDir -Recurse -Force -File |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($resolvedSource.Length).TrimStart('\', '/')
                $entry = $zip.CreateEntry(($relative -replace '\\', '/'), [System.IO.Compression.CompressionLevel]::Optimal)
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

function Read-JarReleaseMode {
    param([Parameter(Mandatory = $true)][string]$JarPath)

    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry("cnkd-release-mode.properties")
        if ($null -eq $entry) {
            throw "cnkd-release-mode.properties not found"
        }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            return $reader.ReadToEnd().Trim()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

function Update-JsonConfig {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$LicenseMode
    )

    $serverConfigPath = Join-Path $PackageRoot "data\ServerConfig.json"
    $serverConfig = [System.IO.File]::ReadAllText($serverConfigPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $serverConfig.common.serverName = "CNKD NEXT Single Room"
    $serverConfig.common.subtitle = ""
    $serverConfig.common.autoUpList = $false
    $serverConfig.common.listStatusSuffixEnabled = $true
    $serverConfig.common.listNamePinnedCharacterCount = 0
    $serverConfig.common.listPinnedCharacterCount = 20
    $serverConfig.common.publicListEntries = @()
    $serverConfig.common.listExperimentEnabled = $false
    $serverConfig.common.listExperimentGameName = ""
    $serverConfig.common.listExperimentCreatedBy = ""
    $serverConfig.common.listExperimentMapName = ""
    $serverConfig.common.listExperimentGameStatus = ""
    $serverConfig.common.listExperimentPlayerCount = -1
    $serverConfig.common.listExperimentMaxPlayerCount = -1
    $serverConfig.common.listPromotionModeEnabled = $false
    $serverConfig.common.listPromotionGameStatus = ""
    $serverConfig.common.listPromotionPlayerCount = -1
    $serverConfig.common.listPromotionMaxPlayerCount = -1
    $serverConfig.common.listPromotionSortBoostEnabled = $false
    $serverConfig.common.listPromotionSortBoostPrefix = ""
    $serverConfig.common.listPromotionBlankMapEnabled = $false
    $serverConfig.network.webPort = 0
    $serverConfig.gameplay.maxPlayer = 100
    $serverConfig.gameplay.maxUnit = 1000
    $serverConfig.gameplay.enableAI = $false
    $serverConfig.gameplay.startMinPlayerSize = -1
    $serverConfig.gameplay.autoStartMinPlayerSize = -1
    $serverConfig.gameplay.maxGameIngTime = 7200
    $serverConfig.gameplay.maxOnlyAIGameIngTime = 3600
    $serverConfig.lobby.enableGameOverBattleRoomReturn = $false
    $serverConfig.lobby.pruneDisconnectedPlayersOnGameOver = $true
    $serverConfig.lobby.gameOverReturnCountdownSeconds = 30
    $serverConfig.map.enableMapLock = $false
    $serverConfig.map.lockMap = ""
    Write-Utf8NoBom -Path $serverConfigPath -Text (($serverConfig | ConvertTo-Json -Depth 64) + "`n")

    $licenseConfigPath = Join-Path $PackageRoot "data\RelayLicenseConfig.json"
    $allowUpList = $LicenseMode -eq "allow_all"
    $instanceName = if ($allowUpList) {
        "CNKD NEXT No-License UPLIST Single-Room"
    } else {
        "CNKD NEXT No-License No-UPLIST Single-Room"
    }
    $requestedFeatures = if ($allowUpList) {
        @("relay_basic", "uplist", "relay_server_mode", "core_routing", "custom_room", "rooms_api", "admin_api")
    } else {
        @("relay_basic")
    }
    $licenseConfig = [ordered]@{
        leaseEndpoint = ""
        licenseKey = ""
        instanceName = $instanceName
        customerHint = ""
        publicIpv4Hint = ""
        requestTimeoutSeconds = 8
        refreshSeconds = 1800
        graceHours = 72
        requestedFeatures = $requestedFeatures
    }
    Write-Utf8NoBom -Path $licenseConfigPath -Text (($licenseConfig | ConvertTo-Json -Depth 16) + "`n")
}

function Write-UserGuide {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$LicenseMode,
        [Parameter(Mandatory = $true)][string]$Version
    )

    $source = Join-Path $BuildRoot "runtime-data\SINGLE-ROOM-USER-GUIDE-CN.txt"
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Missing single-room user guide source: $source"
    }

    $guide = [System.IO.File]::ReadAllText($source, [System.Text.Encoding]::UTF8)
    $packageFolderName = if ($LicenseMode -eq "allow_all") {
        "CNKD-NEXT-$Version-NoLicense-UpList-SingleRoom-Fixed"
    } else {
        "CNKD-NEXT-$Version-NoLicense-NoUpList-SingleRoom-Fixed"
    }
    $guide = $guide.Replace("CNKD-NEXT-1.1.1-NoLicense-NoUpList-SingleRoom-Fixed", $packageFolderName)
    if ($LicenseMode -eq "allow_all") {
        $guide = [System.Text.RegularExpressions.Regex]::Replace(
            $guide,
            '本包为免授权禁 UPLIST 构建，data/RelayLicenseConfig\.json 可以保持空白。\r?\n本包禁止公共列表上传，即使误把 common\.autoUpList 改为 true，也不会上传公共列表。',
            "本包为免授权 UPLIST 构建，data/RelayLicenseConfig.json 可以保持空白。" + [Environment]::NewLine +
                "本包支持公共列表上传，默认 common.autoUpList=false，确认公开后再改为 true 并重启服务端。"
        )
        $guide = $guide.Replace(
            "本包虽然保留公共列表相关字段，但 Jar 内部禁止 UPLIST 上传。",
            "本包支持公共列表上传；默认关闭 common.autoUpList，确认公网端口可达后再手动开启。"
        )
        $publicListSection = @(
            "六、是否开放到公共列表",
            "",
            "本包为免授权 UPLIST 版本，已允许上传公共列表。",
            "不需要授权码，也不需要配置 licenseKey。",
            "",
            "默认 common.autoUpList=false，避免刚解压时意外公开。",
            "确认需要公开后，编辑 data/ServerConfig.json：",
            "",
            "common.autoUpList = true",
            "",
            "保存并完整重启服务端后，服务端会尝试上传公共列表。",
            "同时确认云服务器安全组、系统防火墙和端口映射已放行 common.port，且公网地址可以访问该端口。",
            "",
            "如果公共列表暂时看不到，先检查服务端启动日志、端口公网可达性和 common.autoUpList 是否为 true；不要填写不存在的授权地址或 licenseKey。"
        ) -join [Environment]::NewLine
        $guide = [System.Text.RegularExpressions.Regex]::Replace(
            $guide,
            '六、是否开放到公共列表\r?\n.*?(?=\r?\n七、地图配置)',
            $publicListSection.TrimEnd(),
            [System.Text.RegularExpressions.RegexOptions]::Singleline
        )
        $guide = [System.Text.RegularExpressions.Regex]::Replace(
            $guide,
            '2\. 公共列表看不到\r?\n.*?(?=\r?\n3\. 游戏结束后房间没有恢复)',
            (@(
                "2. 公共列表看不到",
                "",
                "确认 common.autoUpList=true 后已完整重启服务端。",
                "确认云服务器安全组、系统防火墙和端口映射均已放行 common.port。",
                "本包不需要授权码或 licenseKey；如仍不可见，请保留启动日志和公网端口检测结果。"
            ) -join [Environment]::NewLine),
            [System.Text.RegularExpressions.RegexOptions]::Singleline
        )
    }
    Write-Utf8NoBom -Path (Join-Path $PackageRoot "USER-GUIDE-CN.txt") -Text ($guide.Trim() + "`n")
}

function Write-PluginSdk {
    param([Parameter(Mandatory = $true)][string]$PackageRoot)

    $source = Join-Path $BuildRoot "docs\plugin-sdk"
    $apiJar = Join-Path $BuildRoot "Server-Plugin-API\build\libs\Server-Plugin-API.jar"
    $apiSourcesJar = Join-Path $BuildRoot "Server-Plugin-API\build\libs\Server-Plugin-API-sources.jar"
    $apiJavadocJar = Join-Path $BuildRoot "Server-Plugin-API\build\libs\Server-Plugin-API-javadoc.jar"
    $toolJar = Join-Path $BuildRoot "Plugin-Tool\build\libs\cnkd-plugin-tool.jar"
    $thirdPartyGuide = Join-Path $BuildRoot "docs\THIRD_PARTY_PLUGIN_INTEGRATION_CN.md"
    $target = Join-Path $PackageRoot "plugin-sdk"
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Missing plugin SDK source: $source"
    }
    if (-not (Test-Path -LiteralPath $apiJar)) {
        throw "Missing built plugin API JAR: $apiJar"
    }
    if (-not (Test-Path -LiteralPath $toolJar)) {
        throw "Missing built plugin tool JAR: $toolJar"
    }
    foreach ($required in @($apiSourcesJar, $apiJavadocJar, $thirdPartyGuide)) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Missing plugin SDK asset: $required"
        }
    }

    Remove-SafeDirectory -Path $target -SafeRoot $PackageRoot
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Get-ChildItem -LiteralPath $source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force
    }
    Copy-Item -LiteralPath $thirdPartyGuide -Destination (Join-Path $target "THIRD_PARTY_PLUGIN_INTEGRATION_CN.md") -Force
    Copy-Item -LiteralPath $apiJar -Destination (Join-Path $target "server-plugin-api-$PluginApiVersion.jar") -Force
    Copy-Item -LiteralPath $apiSourcesJar -Destination (Join-Path $target "server-plugin-api-$PluginApiVersion-sources.jar") -Force
    Copy-Item -LiteralPath $apiJavadocJar -Destination (Join-Path $target "server-plugin-api-$PluginApiVersion-javadoc.jar") -Force

    # Templates are source-only. Never distribute local Gradle or compiler caches in a release package.
    $generatedDirectoryNames = @(".gradle", ".kotlin", "build")
    Get-ChildItem -LiteralPath $target -Directory -Recurse -Force |
        Where-Object { $generatedDirectoryNames -contains $_.Name } |
        Sort-Object { $_.FullName.Length } -Descending |
        ForEach-Object { Remove-SafeDirectory -Path $_.FullName -SafeRoot $target }

    foreach ($templateName in @("java-minimal", "kotlin-minimal")) {
        $templateLibs = Join-Path $target "$templateName\libs"
        New-Item -ItemType Directory -Path $templateLibs -Force | Out-Null
        Copy-Item -LiteralPath $apiJar -Destination (Join-Path $templateLibs "server-plugin-api-$PluginApiVersion.jar") -Force
    }
    $toolDirectory = Join-Path $target "tools"
    New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
    Copy-Item -LiteralPath $toolJar -Destination (Join-Path $toolDirectory "cnkd-plugin-tool.jar") -Force

    $javaCommand = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        "java"
    }
    & $javaCommand -jar $toolJar api-compare `
        (Join-Path $target "api-baselines\server-plugin-api-1.1.0.json") `
        (Join-Path $target "server-plugin-api-$PluginApiVersion.jar") `
        --expected-change minor `
        --json (Join-Path $target "api-compatibility.json") `
        --force
    if ($LASTEXITCODE -ne 0) {
        throw "Plugin API ABI compatibility gate failed"
    }
    & $javaCommand -jar $toolJar sdk-manifest $target --json (Join-Path $target "sdk-manifest.json") --force
    if ($LASTEXITCODE -ne 0) {
        throw "Plugin SDK manifest generation failed"
    }
}

function Assert-PluginLockMatchesArchives {
    param([Parameter(Mandatory = $true)][string]$PluginsRoot)

    $lockPath = Join-Path $PluginsRoot "plugin-lock.json"
    if (-not (Test-Path -LiteralPath $lockPath)) {
        throw "Plugin lock missing: $lockPath"
    }
    $lock = [System.IO.File]::ReadAllText($lockPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($lock.lockVersion -ne 1) {
        throw "Unexpected plugin lock version in ${lockPath}: $($lock.lockVersion)"
    }
    $entries = @($lock.plugins)
    $archives = @(Get-ChildItem -LiteralPath $PluginsRoot -File -Force |
        Where-Object { $_.Extension -ieq ".jar" -or $_.Extension -ieq ".zip" })
    if ($entries.Count -ne $archives.Count) {
        throw "Plugin lock/archive count mismatch in ${PluginsRoot}: lock=$($entries.Count) archives=$($archives.Count)"
    }
    foreach ($archive in $archives) {
        $hash = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash
        $match = $entries | Where-Object { $_.fileName -eq $archive.Name }
        if ($null -eq $match) {
            throw "Plugin lock has no entry for archive: $($archive.Name)"
        }
        if ($match.sha256.ToUpperInvariant() -ne $hash) {
            throw "Plugin lock sha256 mismatch for $($archive.Name): lock=$($match.sha256) actual=$hash"
        }
        if ([string]::IsNullOrWhiteSpace($match.internalName)) {
            throw "Plugin lock entry for $($archive.Name) is missing internalName"
        }
        if ([int]$match.apiVersion -notin @(1, 2)) {
            throw "Plugin lock entry for $($archive.Name) has unsupported apiVersion: $($match.apiVersion)"
        }
    }
}

function Write-BundledPlugins {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$PluginSourceDirectory,
        [Parameter(Mandatory = $true)][string]$ApprovedBy
    )

    if (-not (Test-Path -LiteralPath $PluginSourceDirectory -PathType Container)) {
        throw "BundlePluginDirectory is not an existing directory: $PluginSourceDirectory"
    }

    # Top-level archives only: PluginsLoad.pluginArchives() scans data/plugins/
    # without recursion, so a nested archive would be silently ignored while the
    # lock tool (which scans deeper) would still approve it.
    $archives = @(Get-ChildItem -LiteralPath $PluginSourceDirectory -File -Force |
        Where-Object { $_.Extension -ieq ".jar" -or $_.Extension -ieq ".zip" } |
        Sort-Object Name)
    if ($archives.Count -eq 0) {
        throw "BundlePluginDirectory contains no .jar/.zip archives: $PluginSourceDirectory"
    }

    $pluginsRoot = Join-Path $PackageRoot "data\plugins"
    New-Item -ItemType Directory -Path $pluginsRoot -Force | Out-Null
    foreach ($archive in $archives) {
        Copy-Item -LiteralPath $archive.FullName -Destination (Join-Path $pluginsRoot $archive.Name) -Force
    }

    $toolJar = Join-Path $BuildRoot "Plugin-Tool\build\libs\cnkd-plugin-tool.jar"
    if (-not (Test-Path -LiteralPath $toolJar)) {
        $toolJar = Join-Path $PackageRoot "plugin-sdk\tools\cnkd-plugin-tool.jar"
    }
    if (-not (Test-Path -LiteralPath $toolJar)) {
        throw "Missing cnkd-plugin-tool.jar; cannot generate plugin-lock.json for bundled plugins"
    }
    $javaCommand = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        "java"
    }

    # Structural + compatibility gate before anything is approved.
    & $javaCommand -jar $toolJar matrix $pluginsRoot --server-api $PluginApiVersion --force
    if ($LASTEXITCODE -ne 0) {
        throw "Bundled plugin compatibility matrix gate failed"
    }

    & $javaCommand -jar $toolJar lock $pluginsRoot `
        --approved-by $ApprovedBy `
        --server-api $PluginApiVersion `
        --output (Join-Path $pluginsRoot "plugin-lock.json") `
        --force
    if ($LASTEXITCODE -ne 0) {
        throw "Bundled plugin lock generation failed"
    }

    # The tool keeps a .bak of the previous lock; a release ships exactly one lock.
    $lockBackup = Join-Path $pluginsRoot "plugin-lock.json.bak"
    if (Test-Path -LiteralPath $lockBackup) {
        Remove-Item -LiteralPath $lockBackup -Force
    }

    Assert-PluginLockMatchesArchives -PluginsRoot $pluginsRoot
}

function New-UnicodeString {
    param([Parameter(Mandatory = $true)][int[]]$CodePoints)

    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

function Assert-UserExperienceGuideGate {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$ExpectedMode
    )

    $guidePath = Join-Path $PackageRoot "USER-GUIDE-CN.txt"
    if (-not (Test-Path -LiteralPath $guidePath)) {
        throw "Package missing USER-GUIDE-CN.txt before user-experience gate"
    }

    $guide = [System.IO.File]::ReadAllText($guidePath, [System.Text.Encoding]::UTF8)
    $requiredSnippets = @(
        "startmode pretest",
        "pretest.lowLatency.profile",
        (New-UnicodeString @(0x73A9, 0x5BB6, 0x5165, 0x623F, 0x8F7B, 0x63D0, 0x793A)),
        (New-UnicodeString @(0x672C, 0x623F, 0x5DF2, 0x5F00, 0x542F, 0x4F4E, 0x5EF6, 0x8FDF, 0x6A21, 0x5F0F)),
        (New-UnicodeString @(0x73A9, 0x5BB6, 0x4F53, 0x611F, 0x56DE, 0x4F20, 0x5EFA, 0x8BAE)),
        (New-UnicodeString @(0x5B9E, 0x6D4B, 0x540E, 0x53C2, 0x6570, 0x51B3, 0x7B56, 0x95E8, 0x69DB)),
        ((New-UnicodeString @(0x4FDD, 0x6301)) + " recommended"),
        ((New-UnicodeString @(0x5207)) + " stable"),
        ((New-UnicodeString @(0x8FDB, 0x5165)) + " production"),
        ("off " + (New-UnicodeString @(0x53EA, 0x7528, 0x4E8E, 0x4EBA, 0x5DE5, 0x5E94, 0x6025, 0x5BF9, 0x7167))),
        (New-UnicodeString @(0x4F4E, 0x5EF6, 0x8FDF, 0x884C, 0x4E3A, 0x7EE7, 0x7EED, 0x4FDD, 0x7559)),
        "lead 8/8",
        "120 FPS",
        "1 worker",
        "direct writes",
        (New-UnicodeString @(0x670D, 0x4E3B, 0x6700, 0x77ED, 0x56DE, 0x4F20, 0x5305)),
        (New-UnicodeString @(0x73A9, 0x5BB6, 0x5355, 0x884C, 0x53CD, 0x9988)),
        (New-UnicodeString @(0x9996, 0x8F6E, 0x4E0D, 0x8981, 0x505A)),
        (New-UnicodeString @(0x9996, 0x8F6E, 0x5F02, 0x5E38, 0x7B2C, 0x4E00, 0x54CD, 0x5E94)),
        (New-UnicodeString @(0x5F00, 0x5C40, 0x81EA, 0x52A8, 0x7ED3, 0x675F)),
        "lowlatency apply stable",
        "startmode diagnose",
        "gameCommandPendingMetricPackets",
        "commandTypes",
        "systemActionLast",
        "gameCommandSystemAction",
        "latency.teamListRefreshPolicy=timer_only",
        "latency.teamListRefreshDurationAvgMs"
    )
    foreach ($snippet in $requiredSnippets) {
        if (-not $guide.Contains($snippet)) {
            throw "USER-GUIDE-CN.txt missing user-experience gate snippet: $snippet"
        }
    }

    if ($ExpectedMode -eq "licenseMode=allow_all") {
        if (-not $guide.Contains("免授权 UPLIST 版本") -or
            $guide.Contains("本包禁止公共列表上传") -or
            $guide.Contains("本包是禁 UPLIST 安全版本")) {
            throw "UPLIST package user guide contains an incorrect public-list capability statement"
        }
    }
}

function Assert-CleanPackage {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$ExpectedMode
    )

    $requiredFiles = @(
        "Server-All.jar",
        "start.sh",
        "start-lowmem.sh",
        "start-windows.bat",
        "CNKD-NEXT-AFTER-INSTALL-USER-SETUP-CN.md",
        "CNKD-NEXT-PublicList-VisualConfig.json",
        "USER-GUIDE-CN.txt",
        "plugin-sdk\PLUGIN_DEVELOPMENT_GUIDE_CN.md",
        "plugin-sdk\PLUGIN_DEVELOPMENT_RUNTIME_CN.md",
        "plugin-sdk\PLUGIN_TOOL_CLI_CN.md",
        "plugin-sdk\sdk-manifest.json",
        "plugin-sdk\sdk-manifest.schema.json",
        "plugin-sdk\api-baselines\server-plugin-api-1.1.0.json",
        "plugin-sdk\api-compatibility.json",
        "plugin-sdk\README-CN.md",
        "plugin-sdk\cnkd-plugin-tool.sh",
        "plugin-sdk\cnkd-plugin-tool.bat",
        "plugin-sdk\start-plugin-development.ps1",
        "plugin-sdk\validate-plugin-v2.ps1",
        "plugin-sdk\approve-plugin-lock.ps1",
        "plugin-sdk\tools\cnkd-plugin-tool.jar",
        "plugin-sdk\server-plugin-api-$PluginApiVersion.jar",
        "plugin-sdk\server-plugin-api-$PluginApiVersion-sources.jar",
        "plugin-sdk\server-plugin-api-$PluginApiVersion-javadoc.jar",
        "plugin-sdk\THIRD_PARTY_PLUGIN_INTEGRATION_CN.md",
        "plugin-sdk\java-minimal\build.gradle.kts",
        "plugin-sdk\java-minimal\src\main\resources\plugin.json",
        "plugin-sdk\java-minimal\libs\server-plugin-api-$PluginApiVersion.jar",
        "plugin-sdk\kotlin-minimal\build.gradle.kts",
        "plugin-sdk\kotlin-minimal\src\main\resources\plugin.json",
        "plugin-sdk\kotlin-minimal\libs\server-plugin-api-$PluginApiVersion.jar",
        "data\ServerConfig.json",
        "data\RoomPermissionConfig.json",
        "data\RelayLicenseConfig.json"
    )
    foreach ($relative in $requiredFiles) {
        $path = Join-Path $PackageRoot $relative
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Package missing required file: $relative"
        }
    }

    $settingsPath = Join-Path $PackageRoot "data\Settings.bin"
    if (Test-Path -LiteralPath $settingsPath) {
        throw "Package contains instance-specific data/Settings.bin; release packages must generate a fresh identity on first start"
    }

    Assert-UserExperienceGuideGate -PackageRoot $PackageRoot -ExpectedMode $ExpectedMode

    $pluginSdkGeneratedDirectory = Get-ChildItem -LiteralPath (Join-Path $PackageRoot "plugin-sdk") -Directory -Recurse -Force |
        Where-Object { $_.Name -in @(".gradle", ".kotlin", "build") } |
        Select-Object -First 1
    if ($null -ne $pluginSdkGeneratedDirectory) {
        throw "Package SDK contains generated directory: $($pluginSdkGeneratedDirectory.FullName)"
    }

    $forbiddenDocs = @(
        "README-Linux.txt",
        "DEPLOY-GUIDE-CN.md",
        "CONFIG-HELP-CN.txt",
        "OPERATIONS-CHECKLIST-CN.txt",
        "DATA-API-GUIDE-CN.txt",
        "API-OPS-CHECK-CN.txt",
        "PLAYER-EXPERIENCE-CN.txt",
        "RELEASE-MODE-CN.txt",
        "BUILD-VERIFY-CN.txt"
    )
    foreach ($relative in $forbiddenDocs) {
        $path = Join-Path $PackageRoot $relative
        if (Test-Path -LiteralPath $path) {
            throw "Package contains forbidden doc: $relative"
        }
    }

    $jarMode = Read-JarReleaseMode -JarPath (Join-Path $PackageRoot "Server-All.jar")
    if ($jarMode -ne $ExpectedMode) {
        throw "Unexpected jar mode: expected=$ExpectedMode actual=$jarMode"
    }

    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $badSeparator = $zip.Entries | Where-Object { $_.FullName.Contains('\') } | Select-Object -First 1
        if ($null -ne $badSeparator) {
            throw "Zip contains Windows path separator: $($badSeparator.FullName)"
        }
        $nestedZip = $zip.Entries | Where-Object { $_.FullName.EndsWith(".zip", [System.StringComparison]::OrdinalIgnoreCase) } | Select-Object -First 1
        if ($null -ne $nestedZip) {
            throw "Zip contains nested zip: $($nestedZip.FullName)"
        }
        $settingsEntry = $zip.Entries |
            Where-Object { $_.FullName -eq "data/Settings.bin" } |
            Select-Object -First 1
        if ($null -ne $settingsEntry) {
            throw "Zip contains instance-specific data/Settings.bin"
        }
        $workflowDoc = $zip.Entries |
            Where-Object {
                $_.FullName -notmatch '(^|/)plugin-sdk/' -and
                $_.FullName -match 'workflow|工作流|BUILD-VERIFY|RELEASE-MODE|CONFIG-HELP|OPERATIONS|DATA-API|API-OPS|PLAYER-EXPERIENCE'
            } |
            Select-Object -First 1
        if ($null -ne $workflowDoc) {
            throw "Zip contains non-user-facing doc: $($workflowDoc.FullName)"
        }
    } finally {
        $zip.Dispose()
    }
}

function Assert-FlatPackageZip {
    param([Parameter(Mandatory = $true)][string]$ZipPath)

    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entryNames = $zip.Entries | ForEach-Object { $_.FullName }
        $requiredEntries = @(
            "Server-All.jar",
            "start.sh",
            "start-windows.bat",
            "CNKD-NEXT-AFTER-INSTALL-USER-SETUP-CN.md",
            "CNKD-NEXT-PublicList-VisualConfig.json",
            "USER-GUIDE-CN.txt",
            "plugin-sdk/PLUGIN_DEVELOPMENT_GUIDE_CN.md",
            "plugin-sdk/PLUGIN_DEVELOPMENT_RUNTIME_CN.md",
            "plugin-sdk/PLUGIN_TOOL_CLI_CN.md",
            "plugin-sdk/sdk-manifest.json",
            "plugin-sdk/sdk-manifest.schema.json",
            "plugin-sdk/api-baselines/server-plugin-api-1.1.0.json",
            "plugin-sdk/api-compatibility.json",
            "plugin-sdk/README-CN.md",
            "plugin-sdk/cnkd-plugin-tool.sh",
            "plugin-sdk/cnkd-plugin-tool.bat",
            "plugin-sdk/start-plugin-development.ps1",
            "plugin-sdk/validate-plugin-v2.ps1",
            "plugin-sdk/approve-plugin-lock.ps1",
            "plugin-sdk/tools/cnkd-plugin-tool.jar",
            "plugin-sdk/server-plugin-api-$PluginApiVersion.jar",
            "plugin-sdk/server-plugin-api-$PluginApiVersion-sources.jar",
            "plugin-sdk/server-plugin-api-$PluginApiVersion-javadoc.jar",
            "plugin-sdk/THIRD_PARTY_PLUGIN_INTEGRATION_CN.md",
            "plugin-sdk/java-minimal/build.gradle.kts",
            "plugin-sdk/java-minimal/src/main/resources/plugin.json",
            "plugin-sdk/java-minimal/libs/server-plugin-api-$PluginApiVersion.jar",
            "plugin-sdk/kotlin-minimal/build.gradle.kts",
            "plugin-sdk/kotlin-minimal/src/main/resources/plugin.json",
            "plugin-sdk/kotlin-minimal/libs/server-plugin-api-$PluginApiVersion.jar",
            "data/ServerConfig.json",
            "data/RoomPermissionConfig.json",
            "data/RelayLicenseConfig.json"
        )
        foreach ($entry in $requiredEntries) {
            if ($entryNames -notcontains $entry) {
                throw "Flat zip missing required root entry: $entry"
            }
        }

        $nestedRoot = $entryNames |
            Where-Object { $_ -match '^CNKD-NEXT-[^/]+/' } |
            Select-Object -First 1
        if ($null -ne $nestedRoot) {
            throw "Flat zip unexpectedly contains nested release root: $nestedRoot"
        }
        if ($entryNames -contains "data/Settings.bin") {
            throw "Flat zip contains instance-specific data/Settings.bin"
        }
    } finally {
        $zip.Dispose()
    }
}

New-Item -ItemType Directory -Path $ReleasesRoot -Force | Out-Null

Push-Location $BuildRoot
try {
    & $Gradle ":Server-Plugin-API:build" ":Plugin-Tool:jar" ":Server-All:packageStartModeDeployZip" "-PcnkdLicenseMode=$LicenseMode" "-PcnkdReleaseVersion=$Version" "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle packageStartModeDeployZip failed"
    }
} finally {
    Pop-Location
}

$generated = Get-ChildItem -LiteralPath $ReleasesRoot -Directory -Filter "CNKD-NEXT-$Version-Public-Linux-Deploy-*" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $generated) {
    throw "Cannot locate generated deploy directory"
}

$folderName = if ($LicenseMode -eq "allow_all") {
    "CNKD-NEXT-$Version-NoLicense-UpList-SingleRoom-Fixed"
} else {
    "CNKD-NEXT-$Version-NoLicense-NoUpList-SingleRoom-Fixed"
}
$target = Join-Path $ReleasesRoot $folderName
Remove-SafeDirectory -Path $target -SafeRoot $ReleasesRoot
Copy-Item -LiteralPath $generated.FullName -Destination $target -Recurse

# Release packages are pure server distributions. Keep the runtime directories,
# but never ship maps or mods copied from the generated deployment fixture.
foreach ($relative in @("data\maps", "data\mods")) {
    $contentRoot = Join-Path $target $relative
    New-Item -ItemType Directory -Path $contentRoot -Force | Out-Null
    Get-ChildItem -LiteralPath $contentRoot -Force -ErrorAction SilentlyContinue |
        ForEach-Object {
            if ($_.PSIsContainer) {
                Remove-SafeDirectory -Path $_.FullName -SafeRoot $target
            } else {
                $resolvedTarget = [System.IO.Path]::GetFullPath($_.FullName)
                $resolvedRoot = [System.IO.Path]::GetFullPath($target).TrimEnd('\') + '\'
                if (-not $resolvedTarget.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                    throw "Refusing to delete release content outside package root: $resolvedTarget"
                }
                Remove-Item -LiteralPath $_.FullName -Force
            }
        }
}

$docsToRemove = @(
    "README-Linux.txt",
    "DEPLOY-GUIDE-CN.md",
    "CONFIG-HELP-CN.txt",
    "OPERATIONS-CHECKLIST-CN.txt",
    "DATA-API-GUIDE-CN.txt",
    "API-OPS-CHECK-CN.txt",
    "PLAYER-EXPERIENCE-CN.txt",
    "RELEASE-MODE-CN.txt",
    "BUILD-VERIFY-CN.txt"
)
foreach ($relative in $docsToRemove) {
    $path = Join-Path $target $relative
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Force
    }
}

Write-Utf8NoBom -Path (Join-Path $target "start-windows.bat") -Text @'
@echo off
cd /d "%~dp0"
if not defined CNKD_UDS_TMP set "CNKD_UDS_TMP=%SystemDrive%\tmp\cnkd-hps-uds"
if not exist "%CNKD_UDS_TMP%" mkdir "%CNKD_UDS_TMP%"
if not exist "%CNKD_UDS_TMP%" (
    echo Cannot create short temporary directory. Set CNKD_UDS_TMP to a writable short path.
    exit /b 1
)
set DEFAULT_JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xms160m -Xmx1024m -Xss512k -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError
if "%JAVA_OPTS%"=="" set JAVA_OPTS=%DEFAULT_JAVA_OPTS%
java "-Djdk.net.unixdomain.tmpdir=%CNKD_UDS_TMP%" %JAVA_OPTS% -jar Server-All.jar
set "CNKD_JAVA_EXIT=%ERRORLEVEL%"
pause
exit /b %CNKD_JAVA_EXIT%
'@

    Update-JsonConfig -PackageRoot $target -LicenseMode $LicenseMode
    Write-UserGuide -PackageRoot $target -LicenseMode $LicenseMode -Version $Version
    Write-PluginSdk -PackageRoot $target

# OPT-20261014-001: optional pre-installed plugin bundle. Runs AFTER the SDK
# stage (so the fallback tool path exists) and BEFORE the zips are built.
if (-not [string]::IsNullOrWhiteSpace($BundlePluginDirectory)) {
    $resolvedPluginSource = [System.IO.Path]::GetFullPath($BundlePluginDirectory)
    Write-BundledPlugins -PackageRoot $target `
        -PluginSourceDirectory $resolvedPluginSource `
        -ApprovedBy $BundlePluginApprovedBy
}

$sourceRevision = (& git -C $BuildRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot identify release source revision" }
$sourceChanges = @(& git -C $BuildRoot status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0) { throw "Cannot identify release source worktree state" }
$releaseInfo = [ordered]@{
    version = $Version
    packagedAt = [DateTimeOffset]::Now.ToString("o")
    sourceRevision = $sourceRevision
    sourceDirty = ($sourceChanges.Count -gt 0)
    licenseMode = $LicenseMode
    pluginApiVersion = $PluginApiVersion
    serverJarSha256 = (Get-FileHash -LiteralPath (Join-Path $target "Server-All.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
    pluginLock = (Get-Content -LiteralPath (Join-Path $target "data\plugins\plugin-lock.json") -Raw | ConvertFrom-Json)
}
Write-Utf8NoBom -Path (Join-Path $target "RELEASE-INFO.json") -Text ($releaseInfo | ConvertTo-Json -Depth 12)
$releaseNotesSource = Join-Path $BuildRoot "docs\RELEASE-$Version-CN.md"
if (Test-Path -LiteralPath $releaseNotesSource) {
    Copy-Item -LiteralPath $releaseNotesSource -Destination (Join-Path $target "RELEASE-NOTES-CN.md")
}

$zipPath = Join-Path $ReleasesRoot "$folderName.zip"
New-PortableZip -SourceDir $target -ZipPath $zipPath -RootFolderName $folderName
Assert-CleanPackage -PackageRoot $target -ZipPath $zipPath -ExpectedMode "licenseMode=$LicenseMode"

$flatZipPath = Join-Path $ReleasesRoot "$folderName-Flat.zip"
New-PortableFlatZip -SourceDir $target -ZipPath $flatZipPath
Assert-CleanPackage -PackageRoot $target -ZipPath $flatZipPath -ExpectedMode "licenseMode=$LicenseMode"
Assert-FlatPackageZip -ZipPath $flatZipPath

$panelZipPath = Join-Path $ReleasesRoot "CNKD NEXT S.zip"
Copy-Item -LiteralPath $flatZipPath -Destination $panelZipPath -Force
Assert-FlatPackageZip -ZipPath $panelZipPath

$jarSha = (Get-FileHash -Algorithm SHA256 (Join-Path $target "Server-All.jar")).Hash
$zipSha = (Get-FileHash -Algorithm SHA256 $zipPath).Hash
$flatZipSha = (Get-FileHash -Algorithm SHA256 $flatZipPath).Hash
$panelZipSha = (Get-FileHash -Algorithm SHA256 $panelZipPath).Hash
$shaFile = Join-Path $ReleasesRoot "$folderName.sha256.txt"
Write-Utf8NoBom -Path $shaFile -Text (
    "SHA256 ($folderName.zip) = $zipSha`n" +
    "SHA256 ($folderName/Server-All.jar) = $jarSha`n" +
    "JarMode = licenseMode=$LicenseMode`n"
)
$flatShaFile = Join-Path $ReleasesRoot "$folderName-Flat.zip.sha256.txt"
Write-Utf8NoBom -Path $flatShaFile -Text (
    "SHA256 ($folderName-Flat.zip) = $flatZipSha`n" +
    "SHA256 (Server-All.jar) = $jarSha`n" +
    "JarMode = licenseMode=$LicenseMode`n"
)
$panelShaFile = Join-Path $ReleasesRoot "CNKD NEXT S.zip.sha256.txt"
Write-Utf8NoBom -Path $panelShaFile -Text (
    "SHA256 (CNKD NEXT S.zip) = $panelZipSha`n" +
    "SHA256 (Server-All.jar) = $jarSha`n" +
    "JarMode = licenseMode=$LicenseMode`n"
)

[pscustomobject]@{
    Folder = $target
    Zip = $zipPath
    Sha256File = $shaFile
    FlatZip = $flatZipPath
    FlatSha256File = $flatShaFile
    PanelZip = $panelZipPath
    PanelSha256File = $panelShaFile
    JarSha256 = $jarSha
    ZipSha256 = $zipSha
    FlatZipSha256 = $flatZipSha
    PanelZipSha256 = $panelZipSha
}
