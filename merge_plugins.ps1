# Merge plugins from Cs-GizliKeyif-master and CXXX-master into Streampulse-master
$ErrorActionPreference = "Stop"
$base = Split-Path $PSScriptRoot -Parent
$dest = $PSScriptRoot
$csSrc = Join-Path $base "Cs-GizliKeyif-master"
$cxSrc = Join-Path $base "CXXX-master"

$skipFolders = @("__Temel", "__New", "__PlayerTest", "ExampleProvider")
$excludeCopy = @("build", ".gradle", ".git")

function Write-Utf8NoBom([string]$path, [string]$content) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($path, $content, $utf8NoBom)
}

function Test-PluginDir([string]$path) {
    Test-Path (Join-Path $path "build.gradle.kts")
}

function Get-ExistingPlugins([string]$root) {
    $set = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $map = @{}
    Get-ChildItem -Path $root -Directory | ForEach-Object {
        if (Test-PluginDir $_.FullName) {
            [void]$set.Add($_.Name)
            $map[$_.Name.ToLowerInvariant()] = $_.Name
        }
    }
    return @{ Set = $set; Map = $map }
}

function Resolve-TargetName([string]$name, $existing) {
    if (-not $existing.Set.Contains($name)) { return $name }
    $candidate = "${name}backup"
    while ($existing.Set.Contains($candidate)) {
        $candidate = "${candidate}backup"
    }
    return $candidate
}

function Get-PluginPackage([string]$pluginPath) {
    $pluginFiles = Get-ChildItem -Path $pluginPath -Recurse -Filter "*Plugin.kt" -ErrorAction SilentlyContinue
    foreach ($file in $pluginFiles) {
        foreach ($line in Get-Content -Path $file.FullName -Encoding UTF8 -ErrorAction SilentlyContinue) {
            if ($line -match '^\s*package\s+([\w.]+)') {
                return $Matches[1].Trim()
            }
        }
    }
    return $null
}

function Get-NamespaceFromBuildGradle([string]$pluginPath) {
    $gradle = Join-Path $pluginPath "build.gradle.kts"
    if (-not (Test-Path $gradle)) { return $null }
    $content = Get-Content -Path $gradle -Raw -Encoding UTF8
    if ($content -match 'namespace\s*=\s*"([^"]+)"') {
        return $Matches[1]
    }
    return $null
}

function Copy-PluginFolder([string]$source, [string]$target) {
    if (Test-Path $target) {
        Remove-Item -Path $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Get-ChildItem -Path $source -Force | ForEach-Object {
        if ($excludeCopy -contains $_.Name) { return }
        Copy-Item -Path $_.FullName -Destination (Join-Path $target $_.Name) -Recurse -Force
    }
}

function Enable-InactivePlugins([string]$pluginPath) {
    $gradle = Join-Path $pluginPath "build.gradle.kts"
    if (-not (Test-Path $gradle)) { return }
    $content = Get-Content -Path $gradle -Raw -Encoding UTF8
    if ($content -match 'status\s*=\s*0') {
        $updated = $content -replace 'status\s*=\s*0', 'status = 1'
        Set-Content -Path $gradle -Value $updated -Encoding UTF8 -NoNewline
    }
}

$existing = Get-ExistingPlugins $dest
$mergeLog = @()
$namespaceLines = [System.Collections.Generic.List[string]]::new()

# Preserve existing namespace overrides from file
$nsFile = Join-Path $dest "plugin_namespaces.properties"
if (Test-Path $nsFile) {
    Get-Content $nsFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            [void]$namespaceLines.Add($line)
        }
    }
}

function Register-Namespace([string]$folder, [string]$namespace) {
    if (-not $namespace) { return }
    $prefix = "$folder="
    $namespaceLines.RemoveAll({ param($x) $x.StartsWith($prefix) }) | Out-Null
    [void]$namespaceLines.Add("$folder=$namespace")
}

function Import-FromRepo([string]$srcRoot, [string]$label) {
    Get-ChildItem -Path $srcRoot -Directory | Sort-Object Name | ForEach-Object {
        $folder = $_.Name
        if ($skipFolders -contains $folder) {
            $mergeLog += "SKIP [$label] $folder (template/disabled)"
            return
        }
        if (-not (Test-PluginDir $_.FullName)) { return }

        $targetName = Resolve-TargetName $folder $existing
        $targetPath = Join-Path $dest $targetName

        Copy-PluginFolder $_.FullName $targetPath
        Enable-InactivePlugins $targetPath

        [void]$existing.Set.Add($targetName)
        $existing.Map[$targetName.ToLowerInvariant()] = $targetName

        $pkg = Get-PluginPackage $targetPath
        Register-Namespace $targetName $pkg

        if ($targetName -ne $folder) {
            $mergeLog += "COPY [$label] $folder -> $targetName (conflict backup)"
        } else {
            $mergeLog += "COPY [$label] $folder"
        }
    }
}

Write-Host "=== Merging Cs-GizliKeyif-master ==="
Import-FromRepo $csSrc "Cs-GizliKeyif"

Write-Host "=== Merging CXXX-master ==="
Import-FromRepo $cxSrc "CXXX"

Write-Host "=== Refreshing namespaces for all plugins ==="
Get-ChildItem -Path $dest -Directory | ForEach-Object {
    if (-not (Test-PluginDir $_.FullName)) { return }
    $folder = $_.Name
    $ns = Get-NamespaceFromBuildGradle $_.FullName
    if (-not $ns) {
        $ns = Get-PluginPackage $_.FullName
    }
    Register-Namespace $folder $ns
}

$header = @(
    "# Override Android namespace per plugin folder when it differs from the default.",
    "# The value must match the Kotlin package name used inside that plugin.",
    "# Auto-generated/updated by merge_plugins.ps1"
)
$nsContent = ($header + ($namespaceLines | Sort-Object -Unique)) -join "`n"
Write-Utf8NoBom $nsFile $nsContent

$enabledFile = Join-Path $dest "enabled_plugins.txt"
$enabledContent = @(
    "# Daftar plugin yang di-build oleh Gradle.",
    "# Hapus file ini atau kosongkan untuk build SEMUA plugin.",
    "# Satu nama folder per baris.",
    "#",
    "# File dikosongkan agar semua plugin (termasuk hasil merge) ikut di-build."
) -join "`n"
Write-Utf8NoBom $enabledFile $enabledContent

$logFile = Join-Path $dest "merge_log.txt"
Write-Utf8NoBom $logFile (($mergeLog -join "`n"))

Write-Host "=== Done ==="
Write-Host "Merged entries: $($mergeLog.Count)"
Write-Host "Namespace entries: $($namespaceLines.Count)"
Write-Host "Log: $logFile"
