[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$BaselineLog,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$FixtureLog,

    [Parameter(Mandatory = $true)]
    [ValidateRange(60, 86400)]
    [int]$BaselineDurationSeconds,

    [Parameter(Mandatory = $true)]
    [ValidateRange(60, 86400)]
    [int]$FixtureDurationSeconds,

    [switch]$BaselinePlayerOperationVerified,

    [switch]$FixturePlayerOperationVerified,

    [ValidateSet('verified', 'not-verified')]
    [string]$QueueRegressionAssessment = 'not-verified',

    [string]$OutputJson
)

$ErrorActionPreference = 'Stop'

function Get-LastMetric {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Source
    )

    $matches = [regex]::Matches($Text, "(?<![A-Za-z0-9_])$([regex]::Escape($Name))=(?<value>\d+)")
    if ($matches.Count -eq 0) {
        throw "Missing metric '$Name' in $Source. Run lowlatency summary and lowlatency readout after the round."
    }
    return [long]$matches[$matches.Count - 1].Groups['value'].Value
}

function Get-LastSummaryField {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$SummaryName,
        [Parameter(Mandatory = $true)][string]$FieldName,
        [Parameter(Mandatory = $true)][string]$Source
    )

    $pattern = "singleRoomLowLatency\.testSummary\.$([regex]::Escape($SummaryName))=[^\r\n]*?(?<![A-Za-z0-9_])$([regex]::Escape($FieldName))=(?<value>\d+)"
    $matches = [regex]::Matches($Text, $pattern)
    if ($matches.Count -eq 0) {
        throw "Missing testSummary.$SummaryName.$FieldName in $Source."
    }
    return [long]$matches[$matches.Count - 1].Groups['value'].Value
}

function Get-LastHessQueue {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Source
    )

    $matches = [regex]::Matches(
        $Text,
        'singleRoomLowLatency\.testSummary\.hessQueue=[^\r\n]*?beforeMax=(?<before>\d+)[^\r\n]*?afterMax=(?<after>\d+)'
    )
    if ($matches.Count -eq 0) {
        throw "Missing testSummary.hessQueue maxima in $Source."
    }
    $match = $matches[$matches.Count - 1]
    return [ordered]@{
        beforeMax = [long]$match.Groups['before'].Value
        afterMax = [long]$match.Groups['after'].Value
    }
}

function Get-RiskMatches {
    param([Parameter(Mandatory = $true)][string]$Text)

    $patterns = @(
        '(?i)GAMECOMMAND[^\r\n]*(stopped|stop|blocked|fused|reject)',
        '(?i)(bridge|preTick)[^\r\n]*(exception|error|failed(?!\s*=))',
        '(?i)(cannot operate|unable to operate|无法操作)'
    )
    $matches = [System.Collections.Generic.List[string]]::new()
    foreach ($pattern in $patterns) {
        foreach ($match in [regex]::Matches($Text, $pattern)) {
            $matches.Add($match.Value)
        }
    }
    return @($matches)
}

function Read-RoundMetrics {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$DurationSeconds,
        [Parameter(Mandatory = $true)][bool]$PlayerOperationVerified
    )

    $text = Get-Content -LiteralPath $Path -Raw -Encoding utf8
    return [ordered]@{
        log = (Resolve-Path -LiteralPath $Path).Path
        durationSeconds = $DurationSeconds
        playerOperationVerified = $PlayerOperationVerified
        receiveToEngineP50Micros = Get-LastMetric -Text $text -Name 'receiveToEngineP50Micros' -Source $Path
        receiveToEngineP95Micros = Get-LastMetric -Text $text -Name 'receiveToEngineP95Micros' -Source $Path
        receiveToEngineMaxMicros = Get-LastMetric -Text $text -Name 'receiveToEngineMaxMicros' -Source $Path
        receiveToEnginePercentileSamples = Get-LastMetric -Text $text -Name 'receiveToEnginePercentileSamples' -Source $Path
        preTickFailures = Get-LastSummaryField -Text $text -SummaryName 'preTick' -FieldName 'failed' -Source $Path
        bridgeFailures = Get-LastSummaryField -Text $text -SummaryName 'bridge' -FieldName 'failed' -Source $Path
        hessQueue = Get-LastHessQueue -Text $text -Source $Path
        riskMatches = [string[]]@(Get-RiskMatches -Text $text)
    }
}

$baseline = Read-RoundMetrics -Path $BaselineLog -DurationSeconds $BaselineDurationSeconds -PlayerOperationVerified $BaselinePlayerOperationVerified.IsPresent
$fixture = Read-RoundMetrics -Path $FixtureLog -DurationSeconds $FixtureDurationSeconds -PlayerOperationVerified $FixturePlayerOperationVerified.IsPresent
$allowedP95IncreaseMicros = [long][Math]::Max(250, [Math]::Ceiling($baseline.receiveToEngineP95Micros * 0.15))
$actualP95IncreaseMicros = [long]($fixture.receiveToEngineP95Micros - $baseline.receiveToEngineP95Micros)

$checks = @(
    [ordered]@{ name = 'baselineDuration'; passed = $baseline.durationSeconds -ge 60; actual = $baseline.durationSeconds; required = '>= 60 seconds' },
    [ordered]@{ name = 'fixtureDuration'; passed = $fixture.durationSeconds -ge 60; actual = $fixture.durationSeconds; required = '>= 60 seconds' },
    [ordered]@{ name = 'baselineSamples'; passed = $baseline.receiveToEnginePercentileSamples -ge 100; actual = $baseline.receiveToEnginePercentileSamples; required = '>= 100' },
    [ordered]@{ name = 'fixtureSamples'; passed = $fixture.receiveToEnginePercentileSamples -ge 100; actual = $fixture.receiveToEnginePercentileSamples; required = '>= 100' },
    [ordered]@{ name = 'p95Increase'; passed = $actualP95IncreaseMicros -le $allowedP95IncreaseMicros; actual = $actualP95IncreaseMicros; required = "<= $allowedP95IncreaseMicros microseconds" },
    [ordered]@{ name = 'preTickFailureIncrease'; passed = $fixture.preTickFailures -le $baseline.preTickFailures; actual = $fixture.preTickFailures - $baseline.preTickFailures; required = '<= 0' },
    [ordered]@{ name = 'bridgeFailureIncrease'; passed = $fixture.bridgeFailures -le $baseline.bridgeFailures; actual = $fixture.bridgeFailures - $baseline.bridgeFailures; required = '<= 0' },
    [ordered]@{ name = 'baselineRiskLog'; passed = $baseline.riskMatches.Count -eq 0; actual = $baseline.riskMatches.Count; required = '0 matching risk lines' },
    [ordered]@{ name = 'fixtureRiskLog'; passed = $fixture.riskMatches.Count -eq 0; actual = $fixture.riskMatches.Count; required = '0 matching risk lines' }
)

$failedChecks = @($checks | Where-Object { -not $_.passed })
$manualChecks = @(
    [ordered]@{ name = 'baselinePlayerOperation'; verified = $baseline.playerOperationVerified },
    [ordered]@{ name = 'fixturePlayerOperation'; verified = $fixture.playerOperationVerified },
    [ordered]@{ name = 'queueRegressionAssessment'; verified = $QueueRegressionAssessment -eq 'verified' }
)
$unverifiedManualChecks = @($manualChecks | Where-Object { -not $_.verified })
$decision = if ($failedChecks.Count -gt 0) {
    'FAIL'
} elseif ($unverifiedManualChecks.Count -gt 0) {
    'BLOCKED'
} else {
    'PASS'
}

$result = [ordered]@{
    gate = 'P8-08 Gate C'
    decision = $decision
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
    baseline = $baseline
    fixture = $fixture
    p95Threshold = [ordered]@{
        baselineMicros = $baseline.receiveToEngineP95Micros
        fixtureMicros = $fixture.receiveToEngineP95Micros
        actualIncreaseMicros = $actualP95IncreaseMicros
        allowedIncreaseMicros = $allowedP95IncreaseMicros
    }
    automatedChecks = $checks
    manualChecks = $manualChecks
    failedChecks = @($failedChecks | ForEach-Object name)
    unverifiedManualChecks = @($unverifiedManualChecks | ForEach-Object name)
}

$json = $result | ConvertTo-Json -Depth 8
if ($OutputJson) {
    $outputPath = [System.IO.Path]::GetFullPath($OutputJson)
    $outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
    if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
        [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    }
    [System.IO.File]::WriteAllText($outputPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

Write-Output $json
if ($decision -eq 'PASS') {
    exit 0
}
exit 2
