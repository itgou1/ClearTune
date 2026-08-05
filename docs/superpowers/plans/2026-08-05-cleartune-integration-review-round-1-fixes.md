# ClearTune Integration Review Round 1 Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Round 1 Critical and Important integration finding, plus both requested Minor test-quality gaps, without changing reviewed employee branches.

**Architecture:** Keep Room as the product source of truth, detach completed downloads from removable WebDAV configurations through a fixed internal offline source, and make destructive workflows explicit and recoverable. Inject one typed runtime-settings provider into startup, playback service/cache, and presentation; retain source/location identity only in the private playback registry; enrich WebDAV metadata through bounded Range reads with filename fallback.

**Tech Stack:** Kotlin 2.3.21, Android SDK 26-37, Room 2.8.4, WorkManager 2.11.2, Media3 1.10.1, OkHttp 5.3.0, Compose, JUnit4, AndroidX instrumentation.

## Global Constraints

- Start from clean `codex/integration` at `d09561c38755067b7b8a7ccac9d59b734d95fc7b`.
- Use strict red-green TDD for every finding; retain focused tests in the branch.
- Do not alter `main`, reviewed remediation/original employee branches, or external refs; do not push.
- Keep all HTTP clients behind the exact per-source `allowCleartext` and subtree policy.
- Keep playback redirects disabled and secrets absent from serializable `MediaItem` state.
- Final commit subject is exactly `fix: close integration review gaps`.

---

### Task 1: Safe source deletion and Android 14 foreground work

**Files:**
- Modify: `core/database/src/main/java/com/cleartune/core/database/dao/{LibraryDao,SourceDao}.kt`
- Modify: `app/src/main/java/com/cleartune/app/{DownloadPersistenceAdapter,WebDavPersistenceAdapters,AppContainer}.kt`
- Modify: `feature/sources/src/main/java/com/cleartune/feature/sources/{SourceController,SourcesFeatureEntry,SourcesScreen}.kt`
- Modify: `data/{download,webdav}/src/main/java/.../*Worker.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/com/cleartune/app/ProductionPersistenceAndroidTest.kt`
- Test: focused feature/worker tests and merged-manifest verification

- [x] Add a failing Room graph test that seeds remote/offline locations, queue, playlist, download, and history; assert retain/delete choices never cascade product rows or orphan files.
- [x] Add failing worker/manifest checks for `FOREGROUND_SERVICE_DATA_SYNC`, `SystemForegroundService` type, and three-argument `ForegroundInfo`.
- [x] Implement a fixed internal offline source, transactional source removal, explicit two-stage confirmation, work cancellation, credential cleanup, and recoverable disk cleanup.
- [x] Implement API 34 data-sync foreground declarations and info types; run focused tests green.

### Task 2: HTTP opt-in, WebDAV reappearance, and download race

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `feature/sources/.../{SourcesUiModel,SourcesScreen,SourcesFeatureEntry}.kt`
- Modify: `app/src/main/java/com/cleartune/app/{WebDavPersistenceAdapters,DownloadPersistenceAdapter}.kt`
- Modify: `data/download/.../{DownloadPersistencePort,ProductionDownloadWorkerRunner}.kt`
- Test: focused feature, WebDAV, download, and app persistence tests

- [x] Add failing behavior tests for explicit HTTP confirmation/persistent warning and policy-gated cleartext execution.
- [x] Add a failing reappearance test proving an unavailable same-fingerprint location must be upserted with its stable ID.
- [x] Add a deterministic failing cancel-after-transfer/before-publication test using an expected work generation.
- [x] Implement minimal policy, availability-aware fingerprinting, and authoritative generation-aware publication/cleanup; run focused tests green.

### Task 3: Production shuffle and authenticated playback identity

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/ProductPersistenceAdapters.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/{SecureMediaDescriptor,PrivateMediaSourceRegistry,MediaItemFactory,PlayerCache,Media3PlaybackBackend}.kt`
- Modify: `app/src/main/java/com/cleartune/app/{AppContainer,ClearTuneApplication}.kt`
- Test: Room adapter and playback HTTP/security tests

- [x] Add a failing Room-adapter shuffle test for deterministic non-natural order, current occurrence preservation, and add/remove reconciliation.
- [x] Add failing private-registry and MockWebServer tests for overlapping source roots and challenge Basic/Digest by exact `sourceId`, with redirects rejected.
- [x] Persist deterministic shuffle occurrence order transactionally and carry opaque location/source identity through the private registry to an authenticated playback client.
- [x] Run focused production-adapter and playback tests green.

### Task 4: Runtime settings and WebDAV onboarding

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/{AppContainer,ClearTuneApplication,ProductPersistenceAdapters,BaselineApp}.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/{ClearTunePlaybackService,PlayerCache}.kt`
- Modify: `feature/sources/src/main/java/com/cleartune/feature/sources/{SourceController,SourcesFeatureEntry,SourcesScreen}.kt`
- Test: app, playback, settings, source navigation/controller tests

- [x] Add failing process-recreation/runtime tests for queue restore, noisy handling, cache enable/limit/statistics, dynamic background, and background playback; assert licenses action is not exposed while unavailable.
- [x] Add a failing new-source test proving test receipt survives root browsing, selected root is saved, first sync is enqueued, and scheduling failure leaves a recoverable saved source.
- [x] Implement one typed settings snapshot/provider and inject each behavior; complete root-selection state and save/schedule reconciliation.
- [x] Run focused settings, playback, source, and app tests green.

### Task 5: Favorites and bounded WebDAV metadata

**Files:**
- Modify: `core/database/src/main/java/com/cleartune/core/database/{RoomProductRepositories,dao/ProductDaos}.kt`
- Modify: `app/src/main/java/com/cleartune/app/{AppContainer,BaselineApp}.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/{WebDavSyncEngine,OkHttpWebDavClient}.kt`
- Create: `data/webdav/src/main/java/com/cleartune/data/webdav/RangeWebDavMetadataEnricher.kt`
- Test: Room playlist/favorite, player binding, MP3/FLAC Range and fallback tests

- [x] Add failing tests for fixed Favorites identity, idempotent favorite/unfavorite, order, player state, and retention after all locations become unavailable.
- [x] Add failing literal-fixture tests for MP3 ID3 and FLAC Vorbis tags, exact bounded Range requests, concurrency, and ignored-Range `200` fallback without buffering.
- [x] Implement the persistent Favorites adapter/binding and bounded metadata reader/parser; persist title/album/artist/duration/artwork fields without changing stable track IDs.
- [x] Run focused database, player, and WebDAV tests green.

### Task 6: Runtime smoke and category-specific media browsing

**Files:**
- Modify: `app/src/androidTest/java/com/cleartune/app/ProductionPersistenceAndroidTest.kt`
- Modify: `app/src/main/java/com/cleartune/app/ProductPersistenceAdapters.kt`
- Modify: `core/database/src/main/java/com/cleartune/core/database/{RoomLibraryRepository,dao/LibraryDao}.kt`
- Test: AppContainer smoke and media library callback/catalog tests

- [x] Add a failing Android smoke test that constructs the real container and asserts runtime repositories, worker factory, and owners.
- [x] Add failing category and pagination tests proving songs/albums/artists/playlists differ and page queries avoid per-item blocking resolution.
- [x] Expose only safe smoke inspection and add batched category/page projections; run focused tests green.

### Task 7: Complete verification and delivery

- [x] Run every focused suite changed above and record exact counts/results.
- [x] Run `gradle.bat testDebugUnitTest lintDebug :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon`.
- [x] Run all four structural scripts and `git diff --check`; record APK sizes and `adb devices` state.
- [x] Re-read every Round 1 finding, map it to its regression test and implementation, inspect the complete diff, and verify branch cleanliness.
- [x] Commit with `fix: close integration review gaps` and write `task-6-fix-round-1-report.md` with evidence and remaining device-only risk.
