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
    [string]$FixtureDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputRoot,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 128)]
    [string]$ApprovedBy
)

$ErrorActionPreference = 'Stop'

$expectedFixtures = @(
    'cnkd-gatec-console-probe-1.0.0.jar',
    'cnkd-gatec-game-events-probe-1.0.0.jar',
    'cnkd-gatec-player-command-probe-1.0.0.jar',
    'cnkd-gatec-player-events-probe-1.0.0.jar',
    'cnkd-gatec-scheduler-probe-1.0.0.jar'
)

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

function Test-DescendantPath {
    param([Parameter(Mandatory = $true)][string]$Child, [Parameter(Mandatory = $true)][string]$Parent)

    $normalizedChild = [System.IO.Path]::GetFullPath($Child).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    return $normalizedChild.StartsWith($normalizedParent + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
}

$serverJarFile = Resolve-RequiredFile -Path $ServerJar -Name 'ServerJar'
$runtimeData = Resolve-RequiredDirectory -Path $RuntimeDataDirectory -Name 'RuntimeDataDirectory'
$fixtureDirectoryItem = Resolve-RequiredDirectory -Path $FixtureDirectory -Name 'FixtureDirectory'
$outputRootPath = [System.IO.Path]::GetFullPath($OutputRoot)

if (Test-Path -LiteralPath $outputRootPath) {
    throw "OutputRoot already exists. Refusing to overwrite a previous Gate C run: $outputRootPath"
}
if ((Test-DescendantPath -Child $outputRootPath -Parent $runtimeData.FullName) -or
    (Test-DescendantPath -Child $outputRootPath -Parent $fixtureDirectoryItem.FullName)) {
    throw 'OutputRoot must not be inside RuntimeDataDirectory or FixtureDirectory.'
}

$sourcePluginDirectory = Join-Path $runtimeData.FullName 'plugins'
$sourceLockPath = Join-Path $sourcePluginDirectory 'plugin-lock.json'
if (-not (Test-Path -LiteralPath $sourceLockPath -PathType Leaf)) {
    throw "RuntimeDataDirectory is missing plugins/plugin-lock.json: $sourceLockPath"
}
$sourceLock = Get-Content -LiteralPath $sourceLockPath -Raw -Encoding utf8 | ConvertFrom-Json
$sourceArchives = @(Get-ChildItem -LiteralPath $sourcePluginDirectory -File | Where-Object { $_.Extension -in @('.jar', '.zip') })
if (@($sourceLock.plugins).Count -ne 0 -or $sourceArchives.Count -ne 0) {
    throw 'Gate C baseline input must have an empty external plugin lock and no external JAR/ZIP archives.'
}

$fixtureFiles = @()
foreach ($fixtureName in $expectedFixtures) {
    $fixturePath = Join-Path $fixtureDirectoryItem.FullName $fixtureName
    $fixtureFiles += Resolve-RequiredFile -Path $fixturePath -Name "Gate C fixture $fixtureName"
}
$unexpectedFixtures = @(Get-ChildItem -LiteralPath $fixtureDirectoryItem.FullName -File | Where-Object {
    $_.Extension -in @('.jar', '.zip') -and $_.Name -notin $expectedFixtures
})
if ($unexpectedFixtures.Count -gt 0) {
    throw "FixtureDirectory contains unexpected JARs: $($unexpectedFixtures.Name -join ', ')"
}

$validator = Join-Path $PSScriptRoot 'validate-plugin-v2.ps1'
$approver = Join-Path $PSScriptRoot 'approve-plugin-lock.ps1'
foreach ($fixtureFile in $fixtureFiles) {
    & $validator -PluginJar $fixtureFile.FullName
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

$baselineRoot = Join-Path $outputRootPath 'baseline'
$fixtureRoot = Join-Path $outputRootPath 'fixtures'
New-Item -ItemType Directory -Path $baselineRoot | Out-Null
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
Copy-Item -LiteralPath $serverJarFile.FullName -Destination (Join-Path $baselineRoot 'Server-All.jar')
Copy-Item -LiteralPath $serverJarFile.FullName -Destination (Join-Path $fixtureRoot 'Server-All.jar')
Copy-Item -LiteralPath $runtimeData.FullName -Destination (Join-Path $baselineRoot 'data') -Recurse
Copy-Item -LiteralPath $runtimeData.FullName -Destination (Join-Path $fixtureRoot 'data') -Recurse

$baselinePluginDirectory = Join-Path $baselineRoot 'data\plugins'
$fixturePluginDirectory = Join-Path $fixtureRoot 'data\plugins'
foreach ($fixtureFile in $fixtureFiles) {
    Copy-Item -LiteralPath $fixtureFile.FullName -Destination (Join-Path $fixturePluginDirectory $fixtureFile.Name)
}

$fixtureArchiveTargets = @($fixtureFiles | ForEach-Object { Join-Path $fixturePluginDirectory $_.Name })
& $approver -PluginDirectory $fixturePluginDirectory -PluginArchives $fixtureArchiveTargets -ApprovedBy $ApprovedBy -Force
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$baselineLock = Get-Content -LiteralPath (Join-Path $baselinePluginDirectory 'plugin-lock.json') -Raw -Encoding utf8 | ConvertFrom-Json
$fixtureLock = Get-Content -LiteralPath (Join-Path $fixturePluginDirectory 'plugin-lock.json') -Raw -Encoding utf8 | ConvertFrom-Json
$baselineArchives = @(Get-ChildItem -LiteralPath $baselinePluginDirectory -File | Where-Object { $_.Extension -in @('.jar', '.zip') })
if (@($baselineLock.plugins).Count -ne 0 -or $baselineArchives.Count -ne 0) {
    throw 'Prepared baseline is not empty.'
}
if (@($fixtureLock.plugins).Count -ne $expectedFixtures.Count) {
    throw "Prepared fixture lock must contain $($expectedFixtures.Count) entries."
}

$manifest = [ordered]@{
    purpose = 'P8-08 Gate C same-environment baseline and five-fixture preparation'
    status = 'PREPARED_REAL_PLAYER_VALIDATION_REQUIRED'
    preparedAtUtc = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
    serverJar = [ordered]@{
        source = $serverJarFile.FullName
        fileName = $serverJarFile.Name
        sha256 = (Get-FileHash -LiteralPath $serverJarFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    runtimeDataSource = $runtimeData.FullName
    baseline = [ordered]@{
        root = $baselineRoot
        pluginLock = (Join-Path $baselinePluginDirectory 'plugin-lock.json')
        externalPluginCount = 0
    }
    fixtures = [ordered]@{
        root = $fixtureRoot
        pluginLock = (Join-Path $fixturePluginDirectory 'plugin-lock.json')
        approvedBy = $ApprovedBy
        archives = @($fixtureFiles | ForEach-Object {
            [ordered]@{
                fileName = $_.Name
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        })
    }
    requiredNextSteps = @(
        'Run baseline and fixtures sequentially; both copies default to the same game port.',
        'Use identical map, mode, humans, AI, MOD, network conditions, and lowlatency test profile.',
        'Capture lowlatency readout and summary after at least 60 seconds with at least 100 percentile samples.',
        'Evaluate logs with evaluate-plugin-gatec.ps1 before any Gate D or release decision.'
    )
}
$manifestPath = Join-Path $outputRootPath 'gatec-runtime-manifest.json'
[System.IO.File]::WriteAllText(
    $manifestPath,
    (($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output "GATEC_RUNTIME_PREPARED=$outputRootPath"
Write-Output "BASELINE_ROOT=$baselineRoot"
Write-Output "FIXTURE_ROOT=$fixtureRoot"
Write-Output "MANIFEST=$manifestPath"
Write-Output 'GATEC_STATUS=PREPARED_REAL_PLAYER_VALIDATION_REQUIRED'
