[CmdletBinding()]
param()

$repoRoot = Split-Path -Parent $PSScriptRoot
$requirements = @(
    @{ Path = 'core/model/src/main/java/com/cleartune/core/model/Ids.kt'; Pattern = 'value class TrackId' },
    @{ Path = 'core/model/src/main/java/com/cleartune/core/model/Ids.kt'; Pattern = 'value class CredentialAlias' },
    @{ Path = 'core/model/src/main/java/com/cleartune/core/model/Ids.kt'; Pattern = 'value class PlaylistItemId' },
    @{ Path = 'core/contracts/src/main/java/com/cleartune/core/contracts/LibraryContracts.kt'; Pattern = 'interface LibraryWriteGateway' },
    @{ Path = 'core/contracts/src/main/java/com/cleartune/core/contracts/LibraryContracts.kt'; Pattern = 'suspend fun applyLibraryMutation' },
    @{ Path = 'core/contracts/src/main/java/com/cleartune/core/contracts/PlaybackContracts.kt'; Pattern = 'interface PlaybackLibraryRepository' },
    @{ Path = 'core/contracts/src/main/java/com/cleartune/core/contracts/PlaybackContracts.kt'; Pattern = 'suspend fun getPlayableTrack' },
    @{ Path = 'core/contracts/src/main/java/com/cleartune/core/contracts/PlaybackContracts.kt'; Pattern = 'interface PlaybackGateway' }
)

$violations = [System.Collections.Generic.List[string]]::new()
foreach ($requirement in $requirements) {
    $absolutePath = Join-Path $repoRoot $requirement.Path
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        $violations.Add("Missing contract file: $($requirement.Path)")
        continue
    }
    if (-not (Select-String -LiteralPath $absolutePath -SimpleMatch $requirement.Pattern -Quiet)) {
        $violations.Add("Missing contract signature: $($requirement.Pattern)")
    }
}

$contractRoots = @(
    (Join-Path $repoRoot 'core/model/src/main/java'),
    (Join-Path $repoRoot 'core/contracts/src/main/java')
)
foreach ($contractRoot in $contractRoots) {
    if (Test-Path -LiteralPath $contractRoot) {
        $forbidden = Get-ChildItem -LiteralPath $contractRoot -Recurse -Filter '*.kt' -File |
            Select-String -Pattern 'android\.|androidx\.|okhttp3\.|org\.xmlpull\.'
        foreach ($match in $forbidden) {
            $relative = [System.IO.Path]::GetRelativePath($repoRoot, $match.Path)
            $violations.Add("Platform type in frozen contract: ${relative}:$($match.LineNumber)")
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit 1
}

Write-Output 'CONTRACT_SHAPE_OK'
