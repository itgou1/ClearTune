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
