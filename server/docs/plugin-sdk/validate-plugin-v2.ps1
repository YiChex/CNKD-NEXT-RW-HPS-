param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$PluginJar
)

$ErrorActionPreference = 'Stop'
$supportedCapabilities = @(
    'console-commands',
    'player-commands',
    'player-events',
    'game-events',
    'broadcast',
    'scheduler',
    'read-state'
)
$reservedIds = @('hessserver', 'uplist', 'connectlimit', 'bindplayer')
$requiredFields = @('apiVersion', 'name', 'internalName', 'author', 'main', 'description', 'version', 'supportedVersions', 'capabilities')
$allowedFields = $requiredFields + @('$schema')
$errors = [System.Collections.Generic.List[string]]::new()
$MaxDescriptorBytes = 1MB
$MaxPluginClassMajor = 55

function Add-ValidationError([string]$Message) {
    $errors.Add($Message)
}

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

try {
    $jarPath = (Resolve-Path -LiteralPath $PluginJar).Path
    $jarFile = Get-Item -LiteralPath $jarPath
    if (-not $jarFile.PSIsContainer -and
        ($jarFile.Extension -ieq '.jar' -or $jarFile.Extension -ieq '.zip') -and
        (($jarFile.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) -and
        $jarFile.Length -gt 0 -and $jarFile.Length -le 67108864) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            if ($archive.Entries.Count -gt 2048) {
                Add-ValidationError 'archive contains more than 2048 entries'
            }

            $expandedBytes = [long]0
            $entryNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
            foreach ($entry in $archive.Entries) {
                $entryName = $entry.FullName.Replace('\', '/')
                $segments = $entryName.Split('/')
                if ($entry.FullName.Contains('\') -or $entryName.StartsWith('/') -or $segments.Contains('..')) {
                    Add-ValidationError "unsafe archive path: $entryName"
                }
                if (-not $entryNames.Add($entryName)) {
                    Add-ValidationError "duplicate archive entry: $entryName"
                }
                if ($entryName.EndsWith('/plugin.json', [System.StringComparison]::Ordinal)) {
                    Add-ValidationError "nested plugin descriptor is not allowed: $entryName"
                }
                if ($entry.Length -gt 33554432) {
                    Add-ValidationError "archive entry exceeds 32 MB: $entryName"
                }
                $expandedBytes += $entry.Length
                if ($entryName.EndsWith('.class', [System.StringComparison]::OrdinalIgnoreCase) -and
                    $entryName.StartsWith('net/rwhps/server/', [System.StringComparison]::Ordinal)) {
                    Add-ValidationError "V2 archive bundles a forbidden CNKD server/API class: $entryName"
                }
                if ($entryName.EndsWith('.class', [System.StringComparison]::OrdinalIgnoreCase)) {
                    $stream = $entry.Open()
                    try {
                        $classBytes = New-Object byte[] 8
                        $read = $stream.Read($classBytes, 0, $classBytes.Length)
                        if ($read -ne 8 -or
                            $classBytes[0] -ne 0xCA -or $classBytes[1] -ne 0xFE -or
                            $classBytes[2] -ne 0xBA -or $classBytes[3] -ne 0xBE) {
                            Add-ValidationError "invalid class header: $entryName"
                        } else {
                            $majorVersion = ($classBytes[6] -shl 8) -bor $classBytes[7]
                            if ($majorVersion -gt $MaxPluginClassMajor) {
                                Add-ValidationError "archive class must target Java 11 or lower (major=$majorVersion max=$MaxPluginClassMajor): $entryName"
                            }
                        }
                    } finally {
                        $stream.Dispose()
                    }
                }
            }
            if ($expandedBytes -gt 134217728) {
                Add-ValidationError 'archive expanded size exceeds 128 MB'
            }

            $descriptorEntry = $archive.GetEntry('plugin.json')
            if ($null -eq $descriptorEntry) {
                Add-ValidationError 'plugin.json is missing from the JAR root'
            } else {
                $descriptorBytes = Read-ExactEntryBytes -Entry $descriptorEntry -Limit $MaxDescriptorBytes
                $descriptorText = [System.Text.UTF8Encoding]::new($false, $true).GetString($descriptorBytes)
                $descriptor = $descriptorText | ConvertFrom-Json

                $fieldNames = @($descriptor.PSObject.Properties.Name)
                foreach ($requiredField in $requiredFields) {
                    if ($fieldNames -cnotcontains $requiredField) {
                        Add-ValidationError "plugin.json missing required field: $requiredField"
                    }
                }
                foreach ($fieldName in $fieldNames) {
                    if ($allowedFields -cnotcontains $fieldName) {
                        Add-ValidationError "plugin.json contains unsupported field: $fieldName"
                    }
                }

                if ($descriptor.apiVersion -ne 2) {
                    Add-ValidationError 'apiVersion must be 2'
                }
                if (-not ([string]$descriptor.name) -or ([string]$descriptor.name).Length -gt 96) {
                    Add-ValidationError 'name must contain 1..96 characters'
                }
                if (-not ([string]$descriptor.author) -or ([string]$descriptor.author).Length -gt 160) {
                    Add-ValidationError 'author must contain 1..160 characters'
                }
                if (([string]$descriptor.description).Length -gt 500) {
                    Add-ValidationError 'description cannot exceed 500 characters'
                }
                $pluginId = [string]$descriptor.internalName
                if ($pluginId -cnotmatch '^[a-z0-9][a-z0-9._-]{0,63}$') {
                    Add-ValidationError 'internalName must be lowercase and match ^[a-z0-9][a-z0-9._-]{0,63}$'
                }
                if ($reservedIds -contains $pluginId) {
                    Add-ValidationError "internalName is reserved: $pluginId"
                }
                $mainClass = [string]$descriptor.main
                if ($mainClass -notmatch '^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$') {
                    Add-ValidationError 'main must be a fully qualified JVM class name'
                } elseif ($mainClass.StartsWith('net.rwhps.server.', [System.StringComparison]::Ordinal)) {
                    Add-ValidationError 'main cannot use the reserved net.rwhps.server package'
                } else {
                    $mainEntry = $mainClass.Replace('.', '/') + '.class'
                    $mainClassEntry = $archive.GetEntry($mainEntry)
                    if ($null -eq $mainClassEntry) {
                        Add-ValidationError "main class is missing: $mainEntry"
                    } else {
                        $stream = $mainClassEntry.Open()
                        try {
                            $header = New-Object byte[] 8
                            $stream.Read($header, 0, 8) | Out-Null
                        } finally {
                            $stream.Dispose()
                        }
                    }
                }

                $pluginVersion = [string]$descriptor.version
                if ($pluginVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$') {
                    Add-ValidationError 'version must be SemVer compatible'
                }
                $supportedVersions = [string]$descriptor.supportedVersions
                $rangePattern = '^(?:(?:>=|<=|!=|>|<|=) )?(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))?(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$'
                $intervalPattern = '^[\[(](?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))?,(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))?[\])]$'
                if ($supportedVersions -notmatch $rangePattern -and $supportedVersions.Replace(' ', '') -notmatch $intervalPattern) {
                    Add-ValidationError 'supportedVersions must be an exact/comparator version or one interval'
                }

                $capabilities = @($descriptor.capabilities)
                if ($capabilities.Count -gt 32) {
                    Add-ValidationError 'capabilities cannot contain more than 32 entries'
                }
                $seenCapabilities = @()
                foreach ($capability in $capabilities) {
                    $capabilityText = [string]$capability
                    if (@($seenCapabilities | Where-Object { $_ -ceq $capabilityText }).Count -gt 0) {
                        Add-ValidationError "capabilities contains duplicate: $capabilityText"
                    } else {
                        $seenCapabilities += $capabilityText
                    }
                    if ($supportedCapabilities -cnotcontains [string]$capability) {
                        Add-ValidationError "unsupported or incorrectly cased capability: $capability"
                    }
                }
                if ([string]$descriptor.import) {
                    Add-ValidationError 'V2 cannot use the legacy import field'
                }
            }

            if ($null -ne $archive.GetEntry('imports.json')) {
                Add-ValidationError 'V2 cannot contain imports.json; shade and relocate dependencies'
            }
        } finally {
            $archive.Dispose()
        }
    } else {
        Add-ValidationError 'archive must be a regular non-symbolic-link .jar/.zip within 1 byte and 64 MB'
    }
} catch {
    Add-ValidationError $_.Exception.Message
}

if ($errors.Count -gt 0) {
    Write-Host "PLUGIN_V2_INVALID errors=$($errors.Count)" -ForegroundColor Red
    $errors | ForEach-Object { Write-Host "- $_" -ForegroundColor Red }
    exit 1
}

Write-Host "PLUGIN_V2_VALID path=$jarPath" -ForegroundColor Green
exit 0
