[CmdletBinding()]
param()

$repoRoot = Split-Path -Parent $PSScriptRoot
$requiredFiles = @(
    'core/designsystem/src/main/java/com/cleartune/core/designsystem/component/ArtistAvatar.kt',
    'core/designsystem/src/main/java/com/cleartune/core/designsystem/component/ArtistAvatarSelector.kt',
    'core/testing/src/main/java/com/cleartune/core/testing/FakeRepositories.kt',
    'feature/library/src/main/java/com/cleartune/feature/library/LibraryFeatureEntry.kt',
    'feature/sources/src/main/java/com/cleartune/feature/sources/SourcesFeatureEntry.kt',
    'feature/downloads/src/main/java/com/cleartune/feature/downloads/DownloadsFeatureEntry.kt',
    'feature/player/src/main/java/com/cleartune/feature/player/PlayerFeatureEntry.kt',
    'feature/playlists/src/main/java/com/cleartune/feature/playlists/PlaylistsFeatureEntry.kt',
    'feature/settings/src/main/java/com/cleartune/feature/settings/SettingsFeatureEntry.kt',
    'app/src/main/java/com/cleartune/app/BaselineApp.kt'
)

$violations = [System.Collections.Generic.List[string]]::new()
foreach ($relativePath in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath) -PathType Leaf)) {
        $violations.Add("Missing shared baseline file: $relativePath")
    }
}

foreach ($index in 1..8) {
    $name = 'avatar_artist_{0:D2}.xml' -f $index
    $relativePath = "core/designsystem/src/main/res/drawable/$name"
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath) -PathType Leaf)) {
        $violations.Add("Missing artist avatar: $relativePath")
    }
}

$appPath = Join-Path $repoRoot 'app/src/main/java/com/cleartune/app/BaselineApp.kt'
if (Test-Path -LiteralPath $appPath) {
    $bottomNavigation = Select-String -LiteralPath $appPath -Pattern 'NavigationBar|BottomNavigation'
    if ($bottomNavigation) {
        $violations.Add('BaselineApp must not contain bottom navigation')
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit 1
}

Write-Output 'SHARED_BASELINE_OK avatars=8 features=6'
