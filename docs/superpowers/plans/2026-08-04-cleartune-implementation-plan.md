# ClearTune Android Music Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver ClearTune as a production-ready native Android music player for local MediaStore and WebDAV libraries, matching the approved product and UI specifications.

**Architecture:** Build one production Android application module with package-by-feature boundaries, Room as the single source of truth, pure Kotlin decision engines around Android and network adapters, a Media3 `MediaLibraryService` for playback, and WorkManager for durable scans/downloads. Add one test-only benchmark module at release hardening. Each phase is a vertical, runnable increment and must be completed in order.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Navigation Compose, Room, Media3 ExoPlayer, WorkManager, OkHttp, Coil, Android Keystore, coroutines/Flow, JUnit, AndroidX Test, MockWebServer.

## Global Constraints

- Product source of truth: `docs/superpowers/specs/2026-08-03-cleartune-android-music-player-design.md`.
- UI source of truth: `docs/superpowers/specs/2026-08-04-cleartune-ui-design.md`.
- Namespace/application ID: `com.cleartune.app`; minimum API 26; compile/target API 37.
- Use AGP 9.3.0, Gradle 9.5.0, JDK 17, KSP 2.3.10, Compose UI 1.11.4, Material 3 1.4.0, Activity 1.13.0, Lifecycle 2.11.0, Navigation 2.9.8, Room 2.8.4, Media3 1.10.1, WorkManager 2.11.2, OkHttp 5.3.0, and Coil 3.5.0.
- No `MANAGE_EXTERNAL_STORAGE`, no cleartext HTTP unless the user explicitly enables it for one source, and no credentials in Room, logs, worker input, saved state, or crash text.
- Preserve Room as the only observable library state. Android/network adapters write through repositories; UI never reads MediaStore, WebDAV, or service internals directly.
- All non-trivial behavior starts with a failing test. Do not advance while the phase verification command is red.
- Use stable public APIs only. Do not copy Apple assets, fonts, icons, or exact layouts.
- Every completed task receives its own commit using the commit message specified by that task.

---

## Delivery Sequence

| Order | Plan | Runnable increment | Exit gate |
|---|---|---|---|
| 1 | `2026-08-04-cleartune-foundation-plan.md` | Launchable app, Room schema, dependency container, theme, navigation shell | Unit + instrumentation smoke tests pass on API 26 and 37 |
| 2 | `2026-08-04-cleartune-local-library-plan.md` | Local permission, MediaStore scan/diff, library browsing and search | Seeded-device scan and local browse acceptance pass |
| 3 | `2026-08-04-cleartune-webdav-plan.md` | Secure source setup, Basic/Digest, browse, recursive sync and enrichment | MockWebServer protocol suite and manual server matrix pass |
| 4 | `2026-08-04-cleartune-playback-plan.md` | Background playback, resolution priority, cache, persisted queue, player UI | Local/remote/offline playback and process-recovery tests pass |
| 5 | `2026-08-04-cleartune-downloads-plan.md` | Durable manual downloads with pause/resume/cancel and storage UI | Range/no-Range, restart, corrupt-size and flight-mode tests pass |
| 6 | `2026-08-04-cleartune-product-completion-plan.md` | Playlists, remaining screens, adaptive/accessibility polish, release gates | Full acceptance matrix and release build pass |

Do not run phases in parallel: each phase intentionally depends on types, schema, and behavior delivered by the previous phase. Within a phase, follow the listed task order unless the phase explicitly marks tasks independent.

## Complete Target File Map

```text
ClearTune/
├── .github/workflows/android.yml
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/gradle-wrapper.properties
├── benchmark/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/cleartune/benchmark/{BaselineProfileGenerator.kt,StartupAndScrollBenchmark.kt}
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/cleartune/app/
        │   │   ├── ClearTuneApplication.kt
        │   │   ├── MainActivity.kt
        │   │   ├── di/AppContainer.kt
        │   │   ├── data/
        │   │   │   ├── db/{ClearTuneDatabase.kt,Converters.kt,dao/,entity/,model/}
        │   │   │   ├── local/{MediaStoreGateway.kt,AndroidMediaStoreGateway.kt,LocalScanEngine.kt,LocalLibraryRepository.kt}
        │   │   │   ├── remote/{CredentialStore.kt,KeystoreCredentialStore.kt,WebDavClient.kt,WebDavXmlParser.kt,DigestAuth.kt,WebDavSyncEngine.kt,RemoteMetadataReader.kt}
        │   │   │   ├── download/{DownloadCoordinator.kt,DownloadEngine.kt,DownloadWorker.kt,DownloadFilePolicy.kt}
        │   │   │   └── repository/{LibraryRepository.kt,PlaylistRepository.kt,SourceRepository.kt,QueueRepository.kt}
        │   │   ├── domain/
        │   │   │   ├── model/{MusicSource.kt,Track.kt,TrackLocation.kt,Album.kt,Artist.kt,Playlist.kt,Download.kt,Queue.kt}
        │   │   │   ├── playback/{LocationResolver.kt,PlaybackCommand.kt,PlaybackState.kt}
        │   │   │   └── sync/{ScanDiff.kt,SyncResult.kt}
        │   │   ├── playback/{ClearTunePlaybackService.kt,PlaybackController.kt,MediaItemFactory.kt,PlayerCache.kt,QueueSynchronizer.kt}
        │   │   ├── ui/
        │   │   │   ├── ClearTuneApp.kt
        │   │   │   ├── navigation/{Destination.kt,ClearTuneNavHost.kt}
        │   │   │   ├── theme/{Color.kt,Theme.kt,Type.kt,Dimensions.kt}
        │   │   │   ├── component/{AppTopBar.kt,MiniPlayer.kt,Artwork.kt,ArtistAvatar.kt,EmptyState.kt,ErrorState.kt,TrackRow.kt}
        │   │   │   └── feature/{library/,songs/,albums/,artists/,folders/,playlists/,downloads/,search/,player/,queue/,sources/,settings/}
        │   │   └── worker/{LocalScanWorker.kt,WebDavSyncWorker.kt}
        │   └── res/{drawable/,mipmap-anydpi-v26/,values/,values-night/,xml/}
        ├── test/java/com/cleartune/app/{data/,domain/,playback/,ui/}
        └── androidTest/java/com/cleartune/app/{db/,ui/,worker/,playback/}
```

## Cross-Phase Acceptance Matrix

- [ ] Fresh install opens Library root without bottom navigation; search/settings are in the top bar and mini-player is absent until a queue exists.
- [ ] Local permission denial leaves the app usable for WebDAV and offers a non-blocking retry action.
- [ ] MediaStore rescan adds, updates, and removes locations without changing an existing track UUID unnecessarily.
- [ ] HTTPS WebDAV works with Basic and Digest; HTTP requires an explicit per-source confirmation and displays a persistent warning.
- [ ] Recursive WebDAV scan uses `PROPFIND Depth: 1`, avoids cycles, records partial failures, and enriches supported audio with bounded `Range` reads.
- [ ] Playback location preference is downloaded file, then valid local URI, then remote stream; failure falls through only when the next location is usable.
- [ ] Queue, current item, position, repeat, and shuffle recover after process death; an unavailable current item advances with an understandable message.
- [ ] Manual downloads keep `.part` on pause, remove it on cancel, validate length, and atomically publish the final file.
- [ ] Songs, albums, artists, folders, playlists, downloads, search, full player, lyrics, queue, source setup, and settings match the approved information hierarchy.
- [ ] Artist without artwork uses one of 8 bundled abstract vector avatars chosen stably from artist ID; it never generates album mosaics.
- [ ] Phone and tablet layouts, 200% font scaling, TalkBack traversal/actions, 48 dp targets, reduced motion, and light/dark contrast pass.

## Final Suite Verification

- [ ] Run static and unit checks:

  ```powershell
  .\gradlew.bat clean lintDebug testDebugUnitTest
  ```

- [ ] Run connected instrumentation on API 26 and API 37:

  ```powershell
  .\gradlew.bat connectedDebugAndroidTest
  ```

- [ ] Build release artifacts and inspect R8 configuration:

  ```powershell
  .\gradlew.bat :app:analyzeReleaseR8Config :app:bundleRelease
  ```

- [ ] Run placeholder and accidental-secret scans; both commands must produce no matches:

  ```powershell
  rg -n "TODO|TBD|FIXME|NotImplementedError|error\(\"not implemented" app docs
  rg -n "Authorization:|password\s*=|credential\s*=" app/src
  ```

- [ ] Confirm `git status --short` contains only intentional release artifacts, then commit:

  ```powershell
  git add README.md .github app build.gradle.kts settings.gradle.kts gradle.properties gradle
  git commit -m "release: complete ClearTune Android player"
  ```
