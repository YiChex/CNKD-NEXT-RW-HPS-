[CmdletBinding()]
param(
    [string]$JavaHome = $env:CNKD_HPS_JAVA_HOME,
    [string]$GradleUserHome = $env:CNKD_HPS_GRADLE_USER_HOME,
    [string]$UnixDomainTmpDir = $env:CNKD_HPS_UNIXDOMAIN_TMPDIR,
    [string]$EvidenceDir
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = $env:JAVA_HOME
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "Set -JavaHome or CNKD_HPS_JAVA_HOME to a JDK 17 installation."
}

$java = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK java.exe not found: $java"
}

if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $GradleUserHome = Join-Path $env:LOCALAPPDATA "CNKD\gradle\8.14.4"
}
if ([string]::IsNullOrWhiteSpace($UnixDomainTmpDir)) {
    $UnixDomainTmpDir = "C:\tmp\cnkd-hps-uds"
}
if ($UnixDomainTmpDir -match "\s") {
    throw "Unix-domain temp path must not contain whitespace: $UnixDomainTmpDir"
}
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $EvidenceDir = Join-Path $root "build\ranked-build-gates\$stamp"
}

$wrapperProperties = Get-Content -Raw -Path (Join-Path $root "gradle\wrapper\gradle-wrapper.properties")
$versionsSource = Get-Content -Raw -Path (Join-Path $root "buildSrc\src\main\kotlin\Versions.kt")
$rootBuild = Get-Content -Raw -Path (Join-Path $root "build.gradle.kts")
$javaProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
$javaProcessInfo.FileName = $java
$javaProcessInfo.Arguments = "-version"
$javaProcessInfo.UseShellExecute = $false
$javaProcessInfo.RedirectStandardOutput = $true
$javaProcessInfo.RedirectStandardError = $true
$javaProcess = New-Object System.Diagnostics.Process
$javaProcess.StartInfo = $javaProcessInfo
$javaProcess.Start() | Out-Null
$javaVersion = $javaProcess.StandardOutput.ReadToEnd() + $javaProcess.StandardError.ReadToEnd()
$javaProcess.WaitForExit()
if ($javaProcess.ExitCode -ne 0) {
    throw "Unable to execute JDK java.exe (exit $($javaProcess.ExitCode))."
}

if ($javaVersion -notmatch 'version "17\.') {
    throw "JDK 17 is required. Detected: $($javaVersion.Trim())"
}
if ($wrapperProperties -notmatch "gradle-8\.14\.4-bin\.zip") {
    throw "Gradle wrapper must remain pinned to 8.14.4."
}
if ($versionsSource -notmatch 'kotlinVersion\s*=\s*"2\.0\.0"') {
    throw "Kotlin must remain pinned to 2.0.0."
}
if ($rootBuild -notmatch "JvmTarget\.JVM_11") {
    throw "HPS JVM target must remain pinned to JVM 11."
}

New-Item -Path $GradleUserHome -ItemType Directory -Force | Out-Null
New-Item -Path $UnixDomainTmpDir -ItemType Directory -Force | Out-Null
New-Item -Path $EvidenceDir -ItemType Directory -Force | Out-Null
$logsDir = Join-Path $EvidenceDir "logs"
New-Item -Path $logsDir -ItemType Directory -Force | Out-Null

$previous = @{
    JAVA_HOME = $env:JAVA_HOME
    GRADLE_USER_HOME = $env:GRADLE_USER_HOME
    JAVA_TOOL_OPTIONS = $env:JAVA_TOOL_OPTIONS
    PATH = $env:PATH
}

$runs = @()

function Invoke-GradleGate {
    param(
        [string]$Name,
        [string]$Task
    )

    $logPath = Join-Path $logsDir "$Name.log"
    $startedAt = (Get-Date).ToUniversalTime()
    $gradlew = Join-Path $root "gradlew.bat"
    $commandLine = "`"$gradlew`" $Task --no-daemon --stacktrace 2>&1"
    & $env:ComSpec /d /s /c $commandLine | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    $endedAt = (Get-Date).ToUniversalTime()
    $script:runs += [pscustomobject]@{
        name = $Name
        task = $Task
        exitCode = $exitCode
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        durationSeconds = [math]::Round(($endedAt - $startedAt).TotalSeconds, 3)
        log = "logs/$Name.log"
    }
    if ($exitCode -ne 0) {
        throw "Gradle gate failed: $Task (exit $exitCode)"
    }
}

try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $GradleUserHome
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$UnixDomainTmpDir"
    if (-not [string]::IsNullOrWhiteSpace($previous.JAVA_TOOL_OPTIONS)) {
        $env:JAVA_TOOL_OPTIONS = "$($previous.JAVA_TOOL_OPTIONS) $($env:JAVA_TOOL_OPTIONS)"
    }
    $env:PATH = "$(Join-Path $JavaHome 'bin');$($previous.PATH)"

    Invoke-GradleGate -Name "01-compileKotlin" -Task ":Server-Core:compileKotlin"
    Invoke-GradleGate -Name "02-server-core-test" -Task ":Server-Core:test"
    Invoke-GradleGate -Name "03-server-all-jar" -Task ":Server-All:jar"

    $exclude = '\\(\.gradle|\.idea|\.kotlin|\.proxyai|build|runtime-data|backups)\\'
    $sourceFiles = Get-ChildItem -Path $root -File -Recurse | Where-Object {
        $_.FullName -notmatch $exclude -and
        $_.Name -notmatch '(^\.tmp-|\.(log|pid|tmp)$)' -and
        $_.Name -notmatch '(^\.env|secret|credential|private.*key|\.pem$|\.key$|\.pfx$|\.jks$|\.keystore$)'
    }
    $sourceManifest = Join-Path $EvidenceDir "SOURCE-SHA256SUMS.txt"
    $sourceRows = foreach ($file in ($sourceFiles | Sort-Object FullName)) {
        $relative = $file.FullName.Substring($root.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToUpperInvariant()
        "$hash  $relative"
    }
    $sourceRows | Set-Content -Path $sourceManifest -Encoding utf8

    $jarPath = Join-Path $root "Server-All\build\libs\Server-All.jar"
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Server-All.jar was not generated."
    }
    $jar = Get-Item -LiteralPath $jarPath
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToUpperInvariant()

    $testFiles = Get-ChildItem -Path (Join-Path $root "Server-Core\build\test-results\test") -Filter "TEST-*.xml" -File
    $testCount = 0
    $failureCount = 0
    $errorCount = 0
    $skippedCount = 0
    foreach ($file in $testFiles) {
        $document = New-Object System.Xml.XmlDocument
        $document.Load($file.FullName)
        $suite = $document.testsuite
        $testCount += [int]$suite.tests
        $failureCount += [int]$suite.failures
        $errorCount += [int]$suite.errors
        $skippedCount += [int]$suite.skipped
    }

    $summary = [ordered]@{
        status = "passed"
        root = $root
        javaHome = $JavaHome
        javaVersion = ($javaVersion -split "`r?`n")[0]
        gradleVersion = "8.14.4"
        kotlinVersion = "2.0.0"
        jvmTarget = "11"
        unixDomainTmpDir = $UnixDomainTmpDir
        sourceFiles = $sourceFiles.Count
        sourceManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceManifest).Hash.ToUpperInvariant()
        tests = $testCount
        failures = $failureCount
        errors = $errorCount
        skipped = $skippedCount
        jarPath = $jarPath
        jarBytes = $jar.Length
        jarSha256 = $jarHash
        runs = $runs
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $EvidenceDir "summary.json") -Encoding utf8
    Write-Output "[ranked-build] passed: tests=$testCount failures=$failureCount errors=$errorCount skipped=$skippedCount"
    Write-Output "[ranked-build] source manifest sha256: $($summary.sourceManifestSha256)"
    Write-Output "[ranked-build] Server-All.jar sha256: $jarHash"
    Write-Output "[ranked-build] evidence: $EvidenceDir"
} catch {
    [ordered]@{
        status = "failed"
        error = $_.Exception.Message
        runs = $runs
    } | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $EvidenceDir "summary.json") -Encoding utf8
    throw
} finally {
    $env:JAVA_HOME = $previous.JAVA_HOME
    $env:GRADLE_USER_HOME = $previous.GRADLE_USER_HOME
    $env:JAVA_TOOL_OPTIONS = $previous.JAVA_TOOL_OPTIONS
    $env:PATH = $previous.PATH
}
