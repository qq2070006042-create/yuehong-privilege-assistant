[CmdletBinding()]
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = Join-Path -Path $PSScriptRoot -ChildPath '..'
}
$Root = (Resolve-Path -LiteralPath $Root).Path

$forbiddenNames = @(
    'server.properties',
    'keystore.properties',
    'local.properties',
    'google-services.json'
)
$forbiddenExtensions = @('.jks', '.keystore', '.p12', '.pem', '.key', '.apk', '.aab')
$forbiddenDirectories = @('.gradle', '.kotlin', '.cxx', '.externalNativeBuild', 'target', 'out', 'build', '.codex_fixture', '.codex_transaction')

$allFiles = Get-ChildItem -LiteralPath $Root -Recurse -Force -File
$allDirectories = Get-ChildItem -LiteralPath $Root -Recurse -Force -Directory
$badFiles = $allFiles | Where-Object {
    $_.Name -in $forbiddenNames -or $_.Extension.ToLowerInvariant() -in $forbiddenExtensions
}
$badDirectories = $allDirectories | Where-Object { $_.Name -in $forbiddenDirectories }

$example = Join-Path $Root 'server.properties.example'
if (-not (Test-Path -LiteralPath $example -PathType Leaf)) {
    throw 'server.properties.example is missing'
}
$nonEmptyExampleValues = Get-Content -LiteralPath $example | Where-Object {
    $_ -match '^\s*[^#!][^=]*=\s*\S+'
}

$textExtensions = @('.kt', '.java', '.gradle', '.properties', '.xml', '.json', '.md', '.txt', '.sh', '.ps1', '.toml', '.rs', '.c', '.h', '.yml', '.yaml')
$textFiles = $allFiles | Where-Object { $_.Extension.ToLowerInvariant() -in $textExtensions }
$privateMaterialHits = $textFiles | Select-String -Pattern @(
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '^\s*(storePassword|keyPassword)\s*=\s*\S+'
)
$privateIdentifierHits = $textFiles | Select-String -Pattern @(
    'yhyun\.asia',
    'moduleId\s*=\s*yhtq'
)

$startupGateCodeHits = @()
foreach ($codeRootName in @('assistant', 'manager', 'server')) {
    $codeRoot = Join-Path $Root $codeRootName
    if (Test-Path -LiteralPath $codeRoot -PathType Container) {
        $codeFiles = Get-ChildItem -LiteralPath $codeRoot -Recurse -Force -File | Where-Object {
            $_.Extension.ToLowerInvariant() -in @('.kt', '.java', '.gradle', '.xml', '.properties')
        }
        $startupGateCodeHits += $codeFiles | Select-String -Pattern @(
            'StartupVerificationApi',
            'HttpStartupVerificationApi',
            'AnnouncementGate',
            'ChannelVerificationScreen',
            'SERVER_CHANNEL_ENDPOINT',
            'SERVER_CHANNEL_JOIN_URL',
            'SERVER_UPDATE_URL',
            'channelEndpoint\s*=',
            'channelJoinUrl\s*=',
            'updateUrl\s*='
        )
    }
}
$requiredLicenseFiles = @(
    'LICENSE',
    'LICENSES\MPL-2.0.txt',
    'LICENSES\Apache-2.0.txt',
    'LICENSES\GhostLock-Apache-2.0.txt',
    'LICENSES\GPL-2.0.txt',
    'NOTICE.md'
)
$missingLicenses = $requiredLicenseFiles | Where-Object {
    -not (Test-Path -LiteralPath (Join-Path $Root $_) -PathType Leaf)
}

Write-Output "ROOT=$Root"
Write-Output "FILES_SCANNED=$($allFiles.Count)"
Write-Output "FORBIDDEN_FILES=$($badFiles.Count)"
Write-Output "FORBIDDEN_DIRECTORIES=$($badDirectories.Count)"
Write-Output "NONEMPTY_EXAMPLE_VALUES=$($nonEmptyExampleValues.Count)"
Write-Output "PRIVATE_MATERIAL_HITS=$($privateMaterialHits.Count)"
Write-Output "PRIVATE_IDENTIFIER_HITS=$($privateIdentifierHits.Count)"
Write-Output "STARTUP_GATE_CODE_HITS=$($startupGateCodeHits.Count)"
Write-Output "MISSING_LICENSE_FILES=$($missingLicenses.Count)"

if ($badFiles.Count -gt 0 -or
    $badDirectories.Count -gt 0 -or
    $nonEmptyExampleValues.Count -gt 0 -or
    $privateMaterialHits.Count -gt 0 -or
    $privateIdentifierHits.Count -gt 0 -or
    $startupGateCodeHits.Count -gt 0 -or
    $missingLicenses.Count -gt 0) {
    Write-Output 'PUBLIC_SOURCE_RESULT=FAIL'
    exit 1
}

Write-Output 'PUBLIC_SOURCE_RESULT=PASS'
