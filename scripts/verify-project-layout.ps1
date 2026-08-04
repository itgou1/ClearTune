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
    'gradlew.bat',
    'scripts/verify-contract-shape.ps1',
    'scripts/verify-shared-baseline.ps1',
    'scripts/verify-branch-ownership.ps1'
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

$settingsPath = Join-Path $repoRoot 'settings.gradle.kts'
if (Test-Path -LiteralPath $settingsPath -PathType Leaf) {
    $typeSafeAccessors = Select-String -LiteralPath $settingsPath -Pattern 'enableFeaturePreview\("TYPESAFE_PROJECT_ACCESSORS"\)'
    if (-not $typeSafeAccessors) {
        $violations.Add('settings.gradle.kts must enable TYPESAFE_PROJECT_ACCESSORS')
    }
}

$gradleFiles = Get-ChildItem -LiteralPath $repoRoot -Recurse -Filter '*.gradle.kts' -File -ErrorAction SilentlyContinue
$forbiddenPlugin = $gradleFiles | Select-String -Pattern 'org\.jetbrains\.kotlin\.android|kotlin-android'
foreach ($match in $forbiddenPlugin) {
    $relative = [System.IO.Path]::GetRelativePath($repoRoot, $match.Path)
    $violations.Add("AGP built-in Kotlin violation: ${relative}:$($match.LineNumber)")
}

$forbiddenEdges = @(
    @{ File = 'playback/build.gradle.kts'; Pattern = 'projects\.core\.database|project\(["'']?:core:database["'']?\)' },
    @{ File = 'core/database/build.gradle.kts'; Pattern = 'projects\.(data|feature|playback)|project\(["'']?:(data|feature|playback)' }
)

foreach ($rule in $forbiddenEdges) {
    $absolutePath = Join-Path $repoRoot $rule.File
    if (Test-Path -LiteralPath $absolutePath -PathType Leaf) {
        $matches = Select-String -LiteralPath $absolutePath -Pattern $rule.Pattern
        foreach ($match in $matches) {
            $violations.Add("Forbidden dependency edge: $($rule.File):$($match.LineNumber)")
        }
    }
}

$featureBuildFiles = Get-ChildItem -Path (Join-Path $repoRoot 'feature') -Filter 'build.gradle.kts' -File -Recurse -ErrorAction SilentlyContinue
foreach ($buildFile in $featureBuildFiles) {
    $matches = Select-String -LiteralPath $buildFile.FullName -Pattern 'projects\.feature\.|project\(["'']?:feature:'
    foreach ($match in $matches) {
        $relative = [System.IO.Path]::GetRelativePath($repoRoot, $match.Path)
        $violations.Add("Feature-to-feature dependency: ${relative}:$($match.LineNumber)")
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit 1
}

Write-Output "PROJECT_LAYOUT_OK modules=$($requiredModules.Count)"
