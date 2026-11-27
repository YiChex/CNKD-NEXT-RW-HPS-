[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [string]$PluginDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$PluginArchives,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 128)]
    [string]$ApprovedBy,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$MaxArchiveBytes = 64MB
$MaxEntryBytes = 32MB
$MaxExpandedBytes = 128MB
$MaxEntries = 2048
$MaxDescriptorBytes = 1MB
$reservedIds = @('hessserver', 'uplist', 'connectlimit', 'bindplayer')

function Read-ExactEntryBytes {
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)][int]$Limit
    )

    $stream = $Entry.Open()
    $memory = [System.IO.MemoryStream]::new()
    try {
        $buffer = New-Object byte[] 8192
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if (($memory.Length + $read) -gt $Limit) {
                throw "archive entry '$($Entry.FullName)' exceeds $Limit bytes"
            }
            $memory.Write($buffer, 0, $read)
        }
        return ,$memory.ToArray()
    } finally {
        $memory.Dispose()
        $stream.Dispose()
    }
}

function Convert-DescriptorJson {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
    $raw = $utf8.GetString($Bytes)
    $filtered = (($raw -split "`r?`n") | Where-Object {
        $trimmed = $_.TrimStart()
        -not ($trimmed.StartsWith('#') -or $trimmed.StartsWith('//'))
    }) -join [Environment]::NewLine
    return $filtered | ConvertFrom-Json
}

function Get-PluginApprovalEntry {
    param(
        [Parameter(Mandatory = $true)][System.IO.FileInfo]$ArchiveFile,
        [Parameter(Mandatory = $true)][string]$Operator,
        [Parameter(Mandatory = $true)][string]$ApprovalTime
    )

    if ($ArchiveFile.Length -le 0 -or $ArchiveFile.Length -gt $MaxArchiveBytes) {
        throw "archive '$($ArchiveFile.Name)' must be within 1..$MaxArchiveBytes bytes"
    }
    if ($ArchiveFile.Extension -notin @('.jar', '.zip')) {
        throw "archive '$($ArchiveFile.Name)' must use the .jar or .zip extension"
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchiveFile.FullName)
    try {
        if ($archive.Entries.Count -gt $MaxEntries) {
            throw "archive '$($ArchiveFile.Name)' contains more than $MaxEntries entries"
        }

        $expandedBytes = [long]0
        $entryNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        $descriptorEntry = $null
        foreach ($entry in $archive.Entries) {
            $entryName = $entry.FullName.Replace('\', '/')
            if ($entry.FullName.Contains('\') -or $entryName.StartsWith('/') -or $entryName.Split('/').Contains('..')) {
                throw "archive '$($ArchiveFile.Name)' contains unsafe path '$entryName'"
            }
            if (-not $entryNames.Add($entryName)) {
                throw "archive '$($ArchiveFile.Name)' contains duplicate entry '$entryName'"
            }
            if ($entryName.EndsWith('/plugin.json', [System.StringComparison]::Ordinal)) {
                throw "archive '$($ArchiveFile.Name)' contains a nested plugin descriptor '$entryName'"
            }
            if ($entry.Length -gt $MaxEntryBytes) {
                throw "archive entry '$entryName' exceeds $MaxEntryBytes bytes"
            }
            $expandedBytes += $entry.Length
            if ($expandedBytes -gt $MaxExpandedBytes) {
                throw "archive '$($ArchiveFile.Name)' exceeds $MaxExpandedBytes expanded bytes"
            }
            if ($entryName -ceq 'plugin.json') {
                $descriptorEntry = $entry
            }
        }
        if ($null -eq $descriptorEntry) {
            throw "archive '$($ArchiveFile.Name)' is missing root plugin.json"
        }

        $descriptorBytes = Read-ExactEntryBytes -Entry $descriptorEntry -Limit $MaxDescriptorBytes
        $descriptor = Convert-DescriptorJson -Bytes $descriptorBytes
        $fields = @($descriptor.PSObject.Properties.Name)
        foreach ($field in @('internalName', 'version')) {
            if ($fields -cnotcontains $field) {
                throw "plugin.json in '$($ArchiveFile.Name)' is missing '$field'"
            }
        }

        $internalName = [string]$descriptor.internalName
        if ($internalName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') {
            throw "plugin.json in '$($ArchiveFile.Name)' has an invalid internalName"
        }
        if ($reservedIds -contains $internalName.ToLowerInvariant()) {
            throw "plugin.json in '$($ArchiveFile.Name)' uses reserved internalName '$internalName'"
        }

        $apiVersion = if ($fields -ccontains 'apiVersion') { [int]$descriptor.apiVersion } else { 1 }
        if ($apiVersion -notin @(1, 2)) {
            throw "plugin.json in '$($ArchiveFile.Name)' must use apiVersion 1 or 2"
        }
        if ($apiVersion -eq 2 -and $internalName -cne $internalName.ToLowerInvariant()) {
            throw "V2 internalName in '$($ArchiveFile.Name)' must be lowercase"
        }

        $version = [string]$descriptor.version
        if ([string]::IsNullOrWhiteSpace($version)) {
            throw "plugin.json in '$($ArchiveFile.Name)' has an empty version"
        }

        return [ordered]@{
            internalName = $internalName
            apiVersion = $apiVersion
            version = $version
            fileName = $ArchiveFile.Name
            sha256 = (Get-FileHash -LiteralPath $ArchiveFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            descriptorSha256 = ([System.BitConverter]::ToString(
                [System.Security.Cryptography.SHA256]::Create().ComputeHash($descriptorBytes)
            ) -replace '-', '').ToLowerInvariant()
            approvedAt = $ApprovalTime
            approvedBy = $Operator
        }
    } finally {
        $archive.Dispose()
    }
}

$pluginDirectoryPath = (Resolve-Path -LiteralPath $PluginDirectory).Path
$pluginDirectoryItem = Get-Item -LiteralPath $pluginDirectoryPath
if (-not $pluginDirectoryItem.PSIsContainer) {
    throw "PluginDirectory must be a directory: $pluginDirectoryPath"
}
if (($pluginDirectoryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "PluginDirectory cannot be a symbolic link or reparse point: $pluginDirectoryPath"
}
if ($ApprovedBy -ne $ApprovedBy.Trim() -or $ApprovedBy.IndexOfAny([char[]](0..31 + 127)) -ge 0) {
    throw 'ApprovedBy must not contain leading/trailing whitespace or control characters'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$resolvedArchives = foreach ($archivePath in $PluginArchives) {
    $resolved = (Resolve-Path -LiteralPath $archivePath).Path
    $item = Get-Item -LiteralPath $resolved
    if ($item.PSIsContainer) {
        throw "PluginArchives cannot contain a directory: $resolved"
    }
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "plugin archive cannot be a symbolic link or reparse point: $resolved"
    }
    if ($item.Directory.FullName -cne $pluginDirectoryItem.FullName) {
        throw "plugin archive must be a direct child of PluginDirectory: $resolved"
    }
    $item
}

$duplicateFiles = $resolvedArchives | Group-Object { $_.Name.ToLowerInvariant() } | Where-Object Count -gt 1
if ($duplicateFiles) {
    throw "PluginArchives contains duplicate file names: $($duplicateFiles.Name -join ', ')"
}

$approvedAt = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
$entries = @($resolvedArchives | Sort-Object { $_.Name.ToLowerInvariant() }, Name | ForEach-Object {
    Get-PluginApprovalEntry -ArchiveFile $_ -Operator $ApprovedBy -ApprovalTime $approvedAt
})
$duplicateIds = $entries | Group-Object { $_.internalName.ToLowerInvariant() } | Where-Object Count -gt 1
if ($duplicateIds) {
    throw "selected archives contain duplicate internalName values: $($duplicateIds.Name -join ', ')"
}

$lock = [ordered]@{
    lockVersion = 1
    plugins = $entries
}
$lockPath = Join-Path $pluginDirectoryItem.FullName 'plugin-lock.json'
$tempPath = Join-Path $pluginDirectoryItem.FullName ('.plugin-lock.' + [Guid]::NewGuid().ToString('N') + '.tmp')
$backupPath = "$lockPath.bak"

if (-not $Force -and -not $PSCmdlet.ShouldProcess($lockPath, "replace complete plugin approval lock with $($entries.Count) explicit entries")) {
    return
}

$json = $lock | ConvertTo-Json -Depth 5
$encoding = [System.Text.UTF8Encoding]::new($false)
$bytes = $encoding.GetBytes($json + [Environment]::NewLine)
$stream = [System.IO.FileStream]::new(
    $tempPath,
    [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::None,
    4096,
    [System.IO.FileOptions]::WriteThrough
)
try {
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush($true)
} finally {
    $stream.Dispose()
}

try {
    if ([System.IO.File]::Exists($lockPath)) {
        [System.IO.File]::Replace($tempPath, $lockPath, $backupPath, $true)
    } else {
        [System.IO.File]::Move($tempPath, $lockPath)
    }
} finally {
    if ([System.IO.File]::Exists($tempPath)) {
        [System.IO.File]::Delete($tempPath)
    }
}

Write-Host "Approved $($entries.Count) plugin archive(s)."
Write-Host "Lock: $lockPath"
if ([System.IO.File]::Exists($backupPath)) {
    Write-Host "Previous lock backup: $backupPath"
}
$entries | ForEach-Object {
    Write-Host ("  {0} {1} api={2} sha256={3}" -f $_.internalName, $_.version, $_.apiVersion, $_.sha256)
}
