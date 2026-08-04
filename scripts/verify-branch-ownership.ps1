[CmdletBinding()]
param(
    [string]$Branch,
    [string[]]$ChangedPath,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ownership = @{
    'codex/employee-1-local-library' = @('core/database/', 'data/local/', 'feature/library/')
    'codex/employee-2-webdav-offline' = @('core/network/', 'data/webdav/', 'data/download/', 'feature/sources/', 'feature/downloads/')
    'codex/employee-3-playback-product' = @('playback/', 'feature/player/', 'feature/playlists/', 'feature/settings/', 'app/')
}

function Test-AllowedPath {
    param([string]$BranchName, [string]$Path)
    if (-not $ownership.ContainsKey($BranchName)) { return $false }
    $normalized = $Path.Replace('\', '/').TrimStart('./')
    return [bool]($ownership[$BranchName] | Where-Object { $normalized.StartsWith($_, [StringComparison]::Ordinal) })
}

if ($SelfTest) {
    $cases = @(
        @{ Branch='codex/employee-1-local-library'; Path='core/database/build.gradle.kts'; Expected=$true },
        @{ Branch='codex/employee-1-local-library'; Path='data/webdav/WebDavClient.kt'; Expected=$false },
        @{ Branch='codex/employee-2-webdav-offline'; Path='feature/downloads/DownloadsRoute.kt'; Expected=$true },
        @{ Branch='codex/employee-3-playback-product'; Path='app/src/main/AndroidManifest.xml'; Expected=$true },
        @{ Branch='codex/employee-3-playback-product'; Path='core/contracts/LibraryContracts.kt'; Expected=$false }
    )
    $failed = $cases | Where-Object {
        (Test-AllowedPath -BranchName $_.Branch -Path $_.Path) -ne $_.Expected
    }
    if ($failed) {
        $failed | ForEach-Object { [Console]::Error.WriteLine("Ownership self-test failed: $($_.Branch) $($_.Path)") }
        exit 1
    }
    Write-Output 'BRANCH_OWNERSHIP_SELF_TEST_OK'
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Branch)) {
    $Branch = (& git branch --show-current).Trim()
}

if (-not $ownership.ContainsKey($Branch)) {
    [Console]::Error.WriteLine("Unknown employee branch: $Branch")
    exit 2
}

if (-not $PSBoundParameters.ContainsKey('ChangedPath')) {
    $mergeBase = (& git merge-base HEAD main).Trim()
    $ChangedPath = @(
        & git diff --name-only "$mergeBase..HEAD"
        & git diff --name-only
        & git diff --cached --name-only
        & git ls-files --others --exclude-standard
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique
}

$violations = @($ChangedPath | Where-Object { -not (Test-AllowedPath -BranchName $Branch -Path $_) })
if ($violations.Count -gt 0) {
    $violations | ForEach-Object { [Console]::Error.WriteLine("Ownership violation on ${Branch}: $_") }
    exit 1
}

Write-Output "BRANCH_OWNERSHIP_OK branch=$Branch changed=$($ChangedPath.Count)"
