# ClearTune Employee Startup

## Shared prerequisites

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0
- Android SDK Platform Tools (latest stable)
- `JAVA_HOME` and `ANDROID_HOME` configured for the current shell

Validate the shared baseline in every worktree:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-project-layout.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-contract-shape.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-shared-baseline.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-branch-ownership.ps1 -SelfTest
```

## Employee 1: local library

- Branch: `codex/employee-1-local-library`
- Worktree: `.worktrees/employee-1-local-library`
- Build command:

```powershell
.\gradlew.bat :core:database:testDebugUnitTest :data:local:testDebugUnitTest :feature:library:assembleDebug
```

## Employee 2: WebDAV and offline

- Branch: `codex/employee-2-webdav-offline`
- Worktree: `.worktrees/employee-2-webdav-offline`
- Build command:

```powershell
.\gradlew.bat :core:network:testDebugUnitTest :data:webdav:testDebugUnitTest :data:download:testDebugUnitTest :feature:sources:assembleDebug :feature:downloads:assembleDebug
```

## Employee 3: playback and product assembly

- Branch: `codex/employee-3-playback-product`
- Worktree: `.worktrees/employee-3-playback-product`
- Build command:

```powershell
.\gradlew.bat :playback:testDebugUnitTest :feature:player:assembleDebug :feature:playlists:assembleDebug :feature:settings:assembleDebug :app:assembleDebug
```

## Before every commit

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-branch-ownership.ps1
git diff --check
git status --short
```

If a required change is outside the owned paths, stop and use the contract change procedure in `docs/development/branch-ownership.md`.

## Integrated production assembly

The app module is the only cross-deliverable composition root. Production uses Room for the library,
sources, playlists, queue/recovery, downloads, and appearance settings. The app module owns the
local-snapshot, WebDAV-sync, download-publication, source-action, playlist-detail, browse, and media
catalog adapters; data and feature modules remain independent of one another.

WebDAV credentials are encrypted with Android Keystore and stored under `noBackupFilesDir`. Worker
process recreation is provisioned by `ClearTuneApplication`: local scans use its WorkManager factory,
while WebDAV sync and download workers resolve durable runners from application host interfaces.
Library scans and enabled WebDAV sources are scheduled at application startup.

Run the integration gate from the integration worktree:

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' testDebugUnitTest lintDebug :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon
```

`assembleDebugAndroidTest` compiles device assertions but does not execute them. Run those APKs on an
existing compatible device or emulator when one is available; do not create an AVD solely for this gate.
License content has no provider in the merged deliverables and therefore remains explicitly unavailable
instead of being bound to a no-op action.
