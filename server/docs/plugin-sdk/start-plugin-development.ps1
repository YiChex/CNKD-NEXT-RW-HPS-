[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,

    [string]$PluginDirectory = "dev-data\plugins",

    [string]$Java = "java",

    [string[]]$ServerArguments = @()
)

$ErrorActionPreference = 'Stop'

$jar = (Resolve-Path -LiteralPath $ServerJar).Path
$jarItem = Get-Item -LiteralPath $jar
if ($jarItem.PSIsContainer -or $jarItem.Extension -ine '.jar') {
    throw "ServerJar must be a JAR file: $jar"
}

$serverDirectory = $jarItem.Directory.FullName
$pluginPath = if ([System.IO.Path]::IsPathRooted($PluginDirectory)) {
    [System.IO.Path]::GetFullPath($PluginDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $serverDirectory $PluginDirectory))
}

New-Item -ItemType Directory -Force -Path $pluginPath | Out-Null

Write-Host "CNKD NEXT plugin development mode"
Write-Host "Plugin directory: $pluginPath"
Write-Host "Production lock is bypassed for this process; structural validation remains enabled."

$javaArgs = @(
    "-Dcnkd.plugins.mode=development",
    "-Dcnkd.plugins.directory=$pluginPath",
    "-jar",
    $jar
) + $ServerArguments

Push-Location $serverDirectory
try {
    & $Java @javaArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
