param(
    [string]$OutputDirectory = ".release"
)

$ErrorActionPreference = "Stop"

function New-ReleaseSecret {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$releaseDirectory = Join-Path $projectRoot $OutputDirectory
$keystorePath = Join-Path $releaseDirectory "cleartune-release.jks"
$credentialsPath = Join-Path $releaseDirectory "credentials.env"

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $credentialsPath)) {
    throw "Release signing files already exist in $releaseDirectory. Refusing to overwrite them."
}

$keytool = Get-Command keytool -ErrorAction Stop
$storePassword = New-ReleaseSecret
$keyPassword = New-ReleaseSecret
$keyAlias = "cleartune-release"

New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null

& $keytool.Source `
    -genkeypair `
    -keystore $keystorePath `
    -storetype JKS `
    -storepass $storePassword `
    -keypass $keyPassword `
    -alias $keyAlias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 36500 `
    -dname "CN=ClearTune Release, O=ClearTune, C=CN" `
    -noprompt

if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE"
}

$credentials = @(
    "CLEARTUNE_RELEASE_STORE_FILE=.release/cleartune-release.jks"
    "CLEARTUNE_RELEASE_STORE_PASSWORD=$storePassword"
    "CLEARTUNE_RELEASE_KEY_ALIAS=$keyAlias"
    "CLEARTUNE_RELEASE_KEY_PASSWORD=$keyPassword"
)
[IO.File]::WriteAllLines($credentialsPath, $credentials, [Text.UTF8Encoding]::new($false))

Write-Output "Release keystore created: $keystorePath"
Write-Output "Release credentials created: $credentialsPath"
Write-Output "Back up both files securely. Losing them prevents upgrades signed with the same identity."
