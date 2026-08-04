[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

$requiredRootFiles = @(
    '.gitignore',
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'gradle/libs.versions.toml',
    'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
    'gradlew',
    'gradlew.bat'
)

$requiredModules = @(
    'app',
    'core/model',
    'core/contracts',
    'core/designsystem',
    'core/testing',
    'core/database',
    'core/network',
    'data/local',
    'data/webdav',
    'data/download',
    'playback',
    'feature/library',
    'feature/sources',
    'feature/downloads',
    'feature/player',
    'feature/playlists',
    'feature/settings'
)

$violations = [System.Collections.Generic.List[string]]::new()

foreach ($relativePath in $requiredRootFiles) {
    $absolutePath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        $violations.Add("Missing root file: $relativePath")
    }
}

foreach ($module in $requiredModules) {
    foreach ($relativePath in @("$module/build.gradle.kts", "$module/src/main/AndroidManifest.xml")) {
        $absolutePath = Join-Path $repoRoot $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            $violations.Add("Missing module file: $relativePath")
        }
    }
}

$gradleFiles = Get-ChildItem -LiteralPath $repoRoot -Recurse -Filter '*.gradle.kts' -File -ErrorAction SilentlyContinue
$forbiddenPlugin = $gradleFiles | Select-String -Pattern 'org\.jetbrains\.kotlin\.android|kotlin-android'
foreach ($match in $forbiddenPlugin) {
    $relative = [System.IO.Path]::GetRelativePath($repoRoot, $match.Path)
    $violations.Add("AGP built-in Kotlin violation: ${relative}:$($match.LineNumber)")
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit 1
}

Write-Output "PROJECT_LAYOUT_OK modules=$($requiredModules.Count)"
