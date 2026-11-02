[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Variant = 'Release'
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$gradleTask = ":manager:assemble$Variant"
$containsNonAscii = $projectRoot -match '[^\x00-\x7F]'
$junctionPath = $null
$buildRoot = $projectRoot

if ($containsNonAscii) {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $junctionPath = Join-Path $tempRoot "yhroot-stellar-build-$PID"
    $junctionPath = [IO.Path]::GetFullPath($junctionPath)
    if (-not $junctionPath.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Temporary build path escaped the system temp directory: $junctionPath"
    }
    if (Test-Path -LiteralPath $junctionPath) {
        throw "Temporary build path already exists: $junctionPath"
    }
    New-Item -ItemType Junction -Path $junctionPath -Target $projectRoot | Out-Null
    $junction = Get-Item -LiteralPath $junctionPath -Force
    if (-not ($junction.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Temporary build path is not a directory junction: $junctionPath"
    }
    $buildRoot = $junctionPath
}

try {
    Push-Location -LiteralPath $buildRoot
    try {
        & '.\gradlew.bat' $gradleTask '--no-daemon'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code ${LASTEXITCODE}: $gradleTask"
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($junctionPath -and (Test-Path -LiteralPath $junctionPath)) {
        $junction = Get-Item -LiteralPath $junctionPath -Force
        if (-not ($junction.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to remove a non-junction temporary path: $junctionPath"
        }
        [IO.Directory]::Delete($junctionPath)
    }
}

Write-Output "Build completed: $gradleTask (upstream GhostLock is embedded)"
