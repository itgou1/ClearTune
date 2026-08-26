<#
.SYNOPSIS
Starts an Android emulator, installs ClearTune, and launches the app.

.EXAMPLE
.\scripts\android-test.ps1

.EXAMPLE
.\scripts\android-test.ps1 -AvdName Pixel_8 -RunUiTests

.EXAMPLE
.\scripts\android-test.ps1 -SkipBuild -Headless
#>

[CmdletBinding()]
param(
    [string]$AvdName = "Pixel_8",
    [ValidateSet("Debug", "Release")]
    [string]$BuildType = "Debug",
    [switch]$SkipBuild,
    [switch]$RunUiTests,
    [switch]$ColdBoot,
    [switch]$Headless,
    [switch]$CheckOnly,
    [ValidateRange(30, 600)]
    [int]$BootTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ReportDirectory = Join-Path $ProjectRoot "app\build\reports\one-click-test"
$PackageName = "com.cleartune.app"
$ActivityName = "$PackageName/.MainActivity"

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Resolve-AndroidSdk {
    $candidates = [System.Collections.Generic.List[string]]::new()

    foreach ($value in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $candidates.Add($value)
        }
    }

    $localProperties = Join-Path $ProjectRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $escapedPath = $sdkLine.Substring("sdk.dir=".Length)
            $localSdk = $escapedPath -replace '\\:', ':' -replace '\\\\', '\'
            $candidates.Add($localSdk)
        }
    }

    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $adb = Join-Path $candidate "platform-tools\adb.exe"
        $emulator = Join-Path $candidate "emulator\emulator.exe"
        if ((Test-Path -LiteralPath $adb) -and (Test-Path -LiteralPath $emulator)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "Android SDK not found. Set ANDROID_SDK_ROOT or fix sdk.dir in local.properties."
}

function Get-EmulatorRows([string]$AdbPath) {
    $rows = @()
    foreach ($line in (& $AdbPath devices 2>$null)) {
        if ($line -match '^(emulator-\d+)\s+(device|offline|unauthorized)$') {
            $rows += [pscustomobject]@{
                Serial = $Matches[1]
                State = $Matches[2]
            }
        }
    }
    return $rows
}

function Wait-ForEmulator([string]$AdbPath, [datetime]$Deadline) {
    $lastState = "not connected"
    while ((Get-Date) -lt $Deadline) {
        $row = Get-EmulatorRows $AdbPath | Select-Object -First 1
        if ($row) {
            $lastState = $row.State
            if ($row.State -eq "unauthorized") {
                throw "Emulator ADB is unauthorized. Confirm debugging authorization or restart the ADB server."
            }
            if ($row.State -eq "device") {
                $bootCompleted = (& $AdbPath -s $row.Serial shell getprop sys.boot_completed 2>$null).Trim()
                if ($bootCompleted -eq "1") {
                    return $row.Serial
                }
                $lastState = "Android is booting"
            }
        }
        Write-Host "Waiting for emulator: $lastState..."
        Start-Sleep -Seconds 2
    }
    throw "Emulator boot timed out after $BootTimeoutSeconds seconds."
}

function Resolve-Apk([string]$Type) {
    if ($Type -eq "Debug") {
        return Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    }

    $signedRelease = Join-Path $ProjectRoot "app\build\outputs\apk\release\cleartune-1.0.0-rc1-test-signed.apk"
    if (Test-Path -LiteralPath $signedRelease) {
        return $signedRelease
    }
    throw "Installable test-signed Release APK not found. Use Debug or generate the signed RC first."
}

Push-Location $ProjectRoot
$startedEmulatorProcess = $null
try {
    Write-Step "Checking Android SDK and AVD"
    $sdkRoot = Resolve-AndroidSdk
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    $emulator = Join-Path $sdkRoot "emulator\emulator.exe"
    $availableAvds = @(& $emulator -list-avds)
    if ($AvdName -notin $availableAvds) {
        $availableText = if ($availableAvds.Count -gt 0) { $availableAvds -join ", " } else { "none" }
        throw "AVD '$AvdName' was not found. Available AVDs: $availableText"
    }
    Write-Host "SDK: $sdkRoot"
    Write-Host "AVD: $AvdName"
    if ($CheckOnly) {
        Write-Host "Environment check passed. No emulator, build, or install was started." -ForegroundColor Green
        return
    }

    Write-Step "Connecting to or starting emulator"
    & $adb start-server | Out-Null
    $existing = Get-EmulatorRows $adb | Select-Object -First 1
    if (-not $existing) {
        $emulatorArguments = @("-avd", $AvdName, "-no-boot-anim")
        if ($ColdBoot) {
            $emulatorArguments += "-no-snapshot-load"
        }
        if ($Headless) {
            $emulatorArguments += @("-no-window", "-no-audio", "-gpu", "swiftshader_indirect")
            $emulatorProcess = Start-Process -FilePath $emulator -ArgumentList $emulatorArguments -WindowStyle Hidden -PassThru
        } else {
            # Keep the emulator visible for interactive touch, rotation, notification, and media testing.
            $emulatorProcess = Start-Process -FilePath $emulator -ArgumentList $emulatorArguments -PassThru
        }
        $startedEmulatorProcess = $emulatorProcess
        Write-Host "Emulator process started. PID=$($emulatorProcess.Id)"
    } else {
        Write-Host "Reusing emulator $($existing.Serial) ($($existing.State))"
    }

    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $serial = Wait-ForEmulator $adb $deadline
    $pageSize = (& $adb -s $serial shell getconf PAGE_SIZE).Trim()
    $androidVersion = (& $adb -s $serial shell getprop ro.build.version.release).Trim()
    Write-Host "Device: $serial / Android $androidVersion / PAGE_SIZE=$pageSize"

    if (-not $SkipBuild) {
        Write-Step "Building $BuildType APK"
        $gradle = Join-Path $ProjectRoot "gradlew.bat"
        $task = if ($BuildType -eq "Debug") { ":app:assembleDebug" } else { ":app:assembleRelease" }
        & $gradle $task
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    }

    $apk = Resolve-Apk $BuildType
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "APK does not exist: $apk"
    }

    Write-Step "Installing and cold-starting ClearTune"
    & $adb -s $serial install -r -d $apk
    if ($LASTEXITCODE -ne 0) {
        throw "APK install failed with exit code $LASTEXITCODE."
    }
    & $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb -s $serial shell wm dismiss-keyguard | Out-Null
    & $adb -s $serial logcat -c
    & $adb -s $serial shell am force-stop $PackageName
    $launchOutput = @(& $adb -s $serial shell am start -W -n $ActivityName)
    $launchOutput | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        throw "App launch failed with exit code $LASTEXITCODE."
    }

    New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
    & $adb -s $serial shell uiautomator dump /sdcard/cleartune-one-click.xml | Out-Null
    & $adb -s $serial pull /sdcard/cleartune-one-click.xml (Join-Path $ReportDirectory "window.xml") | Out-Null
    & $adb -s $serial shell screencap -p /sdcard/cleartune-one-click.png
    & $adb -s $serial pull /sdcard/cleartune-one-click.png (Join-Path $ReportDirectory "launch.png") | Out-Null

    $logcat = @(& $adb -s $serial logcat -d -t 1000)
    $logcat | Set-Content -LiteralPath (Join-Path $ReportDirectory "logcat.txt") -Encoding utf8
    $fatalLines = @($logcat | Select-String 'FATAL EXCEPTION|Process: com\.cleartune\.app|SecurityException')

    if ($RunUiTests) {
        Write-Step "Running Compose device UI tests"
        $previousSerial = $env:ANDROID_SERIAL
        $env:ANDROID_SERIAL = $serial
        try {
            & (Join-Path $ProjectRoot "gradlew.bat") ":app:connectedDebugAndroidTest"
            if ($LASTEXITCODE -ne 0) {
                throw "Device UI tests failed with exit code $LASTEXITCODE."
            }
        } finally {
            $env:ANDROID_SERIAL = $previousSerial
        }
        & $adb -s $serial shell am start -n $ActivityName | Out-Null
    }

    $summary = @(
        "ClearTune one-click test result"
        "Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        "AVD: $AvdName"
        "Serial: $serial"
        "Android: $androidVersion"
        "Page size: $pageSize"
        "APK: $apk"
        "Crash/security log matches: $($fatalLines.Count)"
        ""
        "Launch output:"
    ) + $launchOutput
    $summary | Set-Content -LiteralPath (Join-Path $ReportDirectory "summary.txt") -Encoding utf8

    if ($fatalLines.Count -gt 0) {
        Write-Warning "Found $($fatalLines.Count) crash/security log matches. See $ReportDirectory\logcat.txt"
    } else {
        Write-Host "No app crash/security log matches found." -ForegroundColor Green
    }

    Write-Host "`nTest environment is ready. The emulator will remain running." -ForegroundColor Green
    Write-Host "Report directory: $ReportDirectory"
    Write-Host "Screenshot: $(Join-Path $ReportDirectory 'launch.png')"
} catch {
    if ($startedEmulatorProcess -and -not $startedEmulatorProcess.HasExited) {
        Write-Host "Stopping the emulator process started by this failed run..." -ForegroundColor Yellow
        & taskkill.exe /PID $startedEmulatorProcess.Id /T /F 2>$null | Out-Null
    }
    Write-Host "`nOne-click test failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}
