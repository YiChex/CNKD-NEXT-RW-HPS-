[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ServerJar,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RuntimeDataDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$FixtureJar,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputRoot,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 128)]
    [string]$ApprovedBy
)

$ErrorActionPreference = 'Stop'

function Resolve-RequiredFile {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Name)

    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force
    if ($item.PSIsContainer) {
        throw "$Name must be a file: $resolved"
    }
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Name cannot be a symbolic link or reparse point: $resolved"
    }
    return $item
}

function Resolve-RequiredDirectory {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Name)

    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force
    if (-not $item.PSIsContainer) {
        throw "$Name must be a directory: $resolved"
    }
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Name cannot be a symbolic link or reparse point: $resolved"
    }
    return $item
}

$serverJarFile = Resolve-RequiredFile -Path $ServerJar -Name 'ServerJar'
$runtimeData = Resolve-RequiredDirectory -Path $RuntimeDataDirectory -Name 'RuntimeDataDirectory'
$fixtureJarFile = Resolve-RequiredFile -Path $FixtureJar -Name 'FixtureJar'
$outputRootPath = [System.IO.Path]::GetFullPath($OutputRoot)

if (Test-Path -LiteralPath $outputRootPath) {
    throw "OutputRoot already exists. Refusing to overwrite Gate D evidence: $outputRootPath"
}

$sourcePluginDirectory = Join-Path $runtimeData.FullName 'plugins'
$sourceLockPath = Join-Path $sourcePluginDirectory 'plugin-lock.json'
if (-not (Test-Path -LiteralPath $sourceLockPath -PathType Leaf)) {
    throw "RuntimeDataDirectory is missing plugins/plugin-lock.json: $sourceLockPath"
}
$sourceLock = Get-Content -LiteralPath $sourceLockPath -Raw -Encoding utf8 | ConvertFrom-Json
$sourceArchives = @(Get-ChildItem -LiteralPath $sourcePluginDirectory -File | Where-Object { $_.Extension -in @('.jar', '.zip') })
if (@($sourceLock.plugins).Count -ne 0 -or $sourceArchives.Count -ne 0) {
    throw 'Gate D input must have an empty external plugin lock and no external JAR/ZIP archives.'
}

$validator = Join-Path $PSScriptRoot 'validate-plugin-v2.ps1'
$approver = Join-Path $PSScriptRoot 'approve-plugin-lock.ps1'
& $validator -PluginJar $fixtureJarFile.FullName
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$runtimeRoot = Join-Path $outputRootPath 'fault-recovery'
New-Item -ItemType Directory -Path $runtimeRoot | Out-Null
Copy-Item -LiteralPath $serverJarFile.FullName -Destination (Join-Path $runtimeRoot 'Server-All.jar')
Copy-Item -LiteralPath $runtimeData.FullName -Destination (Join-Path $runtimeRoot 'data') -Recurse

$pluginDirectory = Join-Path $runtimeRoot 'data\plugins'
$fixtureTarget = Join-Path $pluginDirectory $fixtureJarFile.Name
Copy-Item -LiteralPath $fixtureJarFile.FullName -Destination $fixtureTarget
& $approver -PluginDirectory $pluginDirectory -PluginArchives @($fixtureTarget) -ApprovedBy $ApprovedBy -Force
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$lock = Get-Content -LiteralPath (Join-Path $pluginDirectory 'plugin-lock.json') -Raw -Encoding utf8 | ConvertFrom-Json
if (@($lock.plugins).Count -ne 1) {
    throw 'Prepared Gate D runtime must contain exactly one approved external plugin.'
}

$manifest = [ordered]@{
    purpose = 'P8-08 Gate D controlled callback failure and next-game recovery validation'
    status = 'PREPARED_REAL_PLAYER_VALIDATION_REQUIRED'
    preparedAtUtc = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
    serverJar = [ordered]@{
        source = $serverJarFile.FullName
        fileName = $serverJarFile.Name
        sha256 = (Get-FileHash -LiteralPath $serverJarFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    runtimeDataSource = $runtimeData.FullName
    faultRecovery = [ordered]@{
        root = $runtimeRoot
        pluginLock = (Join-Path $pluginDirectory 'plugin-lock.json')
        approvedBy = $ApprovedBy
        archive = [ordered]@{
            fileName = $fixtureJarFile.Name
            sha256 = (Get-FileHash -LiteralPath $fixtureJarFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    requiredNextSteps = @(
        'Use only this isolated runtime for the controlled failure scenario.',
        'Verify the expected command and first game-start callback failures are isolated.',
        'Verify a second match is playable without an instance restart.',
        'Preserve full logs and plugin callback metrics before any Gate D conclusion.'
    )
}
$manifestPath = Join-Path $outputRootPath 'gated-runtime-manifest.json'
[System.IO.File]::WriteAllText(
    $manifestPath,
    (($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output "GATED_RUNTIME_PREPARED=$outputRootPath"
Write-Output "GATED_RUNTIME_ROOT=$runtimeRoot"
Write-Output "MANIFEST=$manifestPath"
Write-Output 'GATED_STATUS=PREPARED_REAL_PLAYER_VALIDATION_REQUIRED'
