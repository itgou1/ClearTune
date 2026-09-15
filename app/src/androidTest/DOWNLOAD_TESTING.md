# Download regression tests

Run on a dedicated emulator with no account signed in. Tests use an in-process HTTP server,
temporary account databases and silent WAV audio; they never connect to a real music server.
The fixtures restore the download network setting and remove only their own generated data.

## Debug

PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.cleartune.app.download.DownloadPipelineTest,com.cleartune.app.auth.AccountIsolationTest,com.cleartune.app.auth.AccountSwitchUiTest com.cleartune.app.test/androidx.test.runner.AndroidJUnitRunner
```

## Minified Release

Supply the existing `CLEARTUNE_RELEASE_*` signing environment variables or Gradle properties
without printing them. Use a separate empty emulator if a differently signed build is installed.

```powershell
.\gradlew.bat :app:assembleRelease --offline
```

Install the resulting Release app and perform the real-device acceptance steps below. These commands
do not publish. Instrumentation currently targets Debug: running the same harness against the fully
shrunk Release hits removed shared runtime classes (AndroidX Trace / Kotlin LazyKt) before tests start.
Do not interpret successful Release compilation or startup as a passed Release download regression.

## Coverage

- Real WorkManager execution, download bytes and cross-Room-instance progress observation.
- Local playback after shutting down the HTTP server, including Android audio decoding.
- HTTP 403 as a terminal, actionable failure; HTTP 503 followed by successful automatic retry.
- Pause, Range-based continuation, deletion, and rejected stale worker writes.
- Session invalidation before download; account-isolation and account-switch UI regressions.
- Scheduler submission rejection, missing-task reconciliation and existing-job network-policy updates.

Real-device acceptance still needs a production music server: verify non-metered Wi-Fi, metered
Wi-Fi, switching network restrictions, app force-stop/relaunch, and server-specific download
permissions. Automated tests do not establish the exact cause of an older production incident.
