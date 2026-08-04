# ClearTune Integration Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the merge-blocking data-loss, protocol-security, durable-work, playback-lifecycle, UI, and product-assembly gaps found in the three completed feature branches, then integrate them into a verified Android application.

**Architecture:** Keep `core:model`, `core:contracts`, `core:designsystem`, and `core:testing` frozen. Each feature branch fixes its owned modules through narrow branch-local ports; the final `app` assembly supplies cross-module adapters after Employee 1 and Employee 2 land. Persistent library/queue/playlist data remains Room-backed, settings remain typed persistent state, credentials remain Android-Keystore protected, and Media3 remains behind playback-owned abstractions.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.0, Gradle 9.5.0, JDK 17, compile/target SDK 37, min SDK 26, Jetpack Compose, Room, WorkManager, OkHttp 5, Media3 1.10.1, JUnit 4, AndroidX Test.

## Global Constraints

- The common base is `763987c715ef1679bfd82329013d67bf82b528ce`; preserve authored feature commits and add remediation commits instead of rewriting history.
- Do not modify `core/model`, `core/contracts`, `core/designsystem`, `core/testing`, root Gradle files, or verification scripts from an employee branch.
- Employee 1 may change only `core/database`, `data/local`, and `feature/library`.
- Employee 2 may change only `core/network`, `data/webdav`, `data/download`, `feature/sources`, and `feature/downloads`.
- Employee 3 may change only `playback`, `feature/player`, `feature/playlists`, `feature/settings`, and `app`.
- Local and WebDAV locations that disappear must become unavailable; they must not cascade-delete tracks, playlists, queue entries, favorites, downloads, or history.
- `READ_MEDIA_AUDIO` is required on API 33+, while `READ_EXTERNAL_STORAGE` is limited with `android:maxSdkVersion="32"`.
- WebDAV defaults to HTTPS. Cleartext remains explicit opt-in. Untrusted URLs must reject user-info, query, fragment, encoded traversal, cross-origin redirects, and credential forwarding outside exact scheme/host/effective-port/path boundaries.
- Downloads publish a playable final file only after authoritative byte-count validation, `fsync`, and atomic move or verified fallback. Cancellation must remain cancellation, not retry.
- Process restoration restores queue, occurrence index, position, repeat, and deterministic shuffle order but always starts paused.
- The app has no bottom navigation. Required library, source, download, player, queue, playlist, and settings routes must be real restorable routes rather than string-only placeholders.
- Every production behavior change follows red-green-refactor and receives a scoped code review before the next task.

---

### Task 1: Make Local Library Retention and Scanning Production-Safe

**Target tree:** `D:/DvWorkspaces/CodeX/ClearTune/.worktrees/remediate-employee-1` on `codex/remediate-employee-1`, forked from Employee 1 implementation head `13760e948931206daa563ccd212ec8c60baf4206`.

**Files:**
- Modify: `core/database/src/main/java/com/cleartune/core/database/dao/LibraryDao.kt`
- Modify: `core/database/src/main/java/com/cleartune/core/database/RoomLibraryRepository.kt`
- Modify: `core/database/src/main/java/com/cleartune/core/database/RoomProductRepositories.kt`
- Modify: `core/database/src/androidTest/java/com/cleartune/core/database/RoomLibraryTransactionTest.kt`
- Modify: `data/local/build.gradle.kts`
- Modify: `data/local/src/main/AndroidManifest.xml`
- Modify: `data/local/src/main/java/com/cleartune/data/local/LocalScanCoordinator.kt`
- Modify: `data/local/src/main/java/com/cleartune/data/local/LocalScanWorker.kt`
- Create: `data/local/src/main/java/com/cleartune/data/local/LocalSnapshotPort.kt`
- Modify: `data/local/src/test/java/com/cleartune/data/local/LocalScanCoordinatorTest.kt`
- Create: `data/local/src/test/java/com/cleartune/data/local/LocalScanProgressTest.kt`
- Modify: `feature/library/src/main/java/com/cleartune/feature/library/LibraryFeatureEntry.kt`
- Create: `feature/library/src/main/java/com/cleartune/feature/library/LibraryBrowseState.kt`
- Create: `feature/library/src/test/java/com/cleartune/feature/library/LibraryBrowseStateTest.kt`

**Interfaces:**
- Consumes: existing `LocalAudioRecord`, `LocalScanStatus`, Room entities/DAOs, and frozen `LibraryRepository`/`LibraryWriteGateway`.
- Produces: a `LocalSnapshotPort` owned by `data/local` whose implementation is supplied by final app assembly; scan code no longer imports `core.database`.

- [ ] **Step 1: Add failing retention regression tests**

Add Room tests named `missing_location_is_marked_unavailable_without_deleting_product_rows` and `reappearing_location_reactivates_existing_track_identity`. Seed a track, location, playlist item, queue occurrence, download row, and history row; apply a successful snapshot without the location; assert the location is unavailable and all product rows remain; rescan the same stable key and assert the same IDs become available.

- [ ] **Step 2: Run the Room test and confirm the current cascade-delete behavior fails**

Run:

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' :core:database:testDebugUnitTest :core:database:assembleDebugAndroidTest --console=plain --no-daemon
```

Expected: the new retention assertion fails before the DAO change, while test sources compile.

- [ ] **Step 3: Replace physical retention deletion with availability updates**

Change the successful snapshot transaction to mark absent locations `available = 0`, retain orphaned track/product rows, and make stable-key upsert reactivate the existing row. Do not add a foreign-key cascade workaround; preserve referential integrity at the source operation.

- [ ] **Step 4: Add and verify production media permissions**

Declare exactly:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

Add a manifest-merging test or compiled-manifest assertion covering API split behavior, then run `:data:local:testDebugUnitTest :app:processDebugMainManifest`.

- [ ] **Step 5: Remove the `data/local -> core/database` implementation edge**

Define `LocalSnapshotPort` in `data/local` with explicit snapshot apply and progress callbacks. Refactor `LocalScanCoordinator` and `LocalScanWorker` to use it. Remove the `projects.core.database` dependency from `data/local/build.gradle.kts`. The final app adapter is intentionally deferred to Task 6.

- [ ] **Step 6: Make durable work deterministic and progress observable**

Replace the mutable process-global worker dependency with an injectable worker runner/factory seam initialized by application assembly. Re-throw cancellation, retry only classified transient failures, and report monotonic processed/total progress in bounded batches. Add tests for missing provisioning, permanent failure, transient retry, cancellation, and progress monotonicity.

- [ ] **Step 7: Supply live browse state for every library route**

Create `LibraryBrowseState` that exposes live albums, artists, folders, and detail projections instead of default empty lists. Route state remains feature-owned and consumes frozen repository flows or an app-supplied narrow browse port. Add tests proving all approved route kinds receive non-placeholder data and update when flows change.

- [ ] **Step 8: Verify and commit Employee 1 remediation**

Run:

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' :core:database:testDebugUnitTest :data:local:testDebugUnitTest :feature:library:testDebugUnitTest :core:database:assembleDebugAndroidTest :data:local:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon
```

Then run branch ownership verification, `git diff --check`, self-review, and commit with `fix: preserve local library state across rescans`.

---

### Task 2: Harden WebDAV Parsing, Authentication, and Byte-Accurate Transfers

**Target tree:** `D:/DvWorkspaces/CodeX/ClearTune/.worktrees/remediate-employee-2` on `codex/remediate-employee-2`, forked from Employee 2 implementation head `7a662632c66e231889d7f0f418eb0321c45373f9`.

**Files:**
- Modify: `core/network/src/main/java/com/cleartune/core/network/WebDavAuthenticator.kt`
- Modify: `core/network/src/main/java/com/cleartune/core/network/WebDavUrlPolicy.kt`
- Modify: `core/network/src/test/java/com/cleartune/core/network/WebDavAuthenticatorTest.kt`
- Modify: `core/network/src/test/java/com/cleartune/core/network/WebDavUrlPolicyTest.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavXmlParser.kt`
- Modify: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavXmlParserTest.kt`
- Modify: `data/download/src/main/java/com/cleartune/data/download/DownloadTransfer.kt`
- Modify: `data/download/src/test/java/com/cleartune/data/download/DownloadTransferTest.kt`

**Interfaces:**
- Consumes: existing OkHttp request/response types and `NetworkFailure` classifier.
- Produces: exact-origin descendant URL validation, bounded RFC 7616 retry state, response-status-aware XML entries, and authoritative resumable transfer completion.

- [ ] **Step 1: Add adversarial failing protocol tests**

Add tests for: supported Digest chosen after an unsupported challenge; one `stale=true` nonce retry; nonce-count reset for a new nonce; rejection of `href` containing user-info/query/fragment/encoded separators; rejection of response-level non-2xx status; parsing valid `getlastmodified`; unknown expected length with partial `Content-Range`; permanent 400/405/410/423; ETag mismatch restart; and coroutine cancellation.

- [ ] **Step 2: Run focused tests and record expected failures**

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' :core:network:testDebugUnitTest :data:webdav:testDebugUnitTest :data:download:testDebugUnitTest --console=plain --no-daemon
```

- [ ] **Step 3: Implement bounded authentication challenge selection**

Track attempts per response chain, select a supported Digest challenge rather than the first Digest token, permit one stale-nonce refresh within the two-challenge ceiling, reset nonce count for a new nonce, and never forward credentials to a request outside the exact validated protection space.

- [ ] **Step 4: Implement strict descendant URL and response validation**

Normalize scheme/host/effective port and path segments; reject user-info, query, fragment, encoded slash/backslash/dot traversal, and origin drift. Parse response-level status independently of propstat status, reject non-2xx response entries, and parse `getlastmodified` to a validated instant or null without accepting malformed values.

- [ ] **Step 5: Make transfer publication byte-accurate**

For `206`, derive the authoritative total from `Content-Range`; require contiguous ranges and final accumulated length equal to total before publication. For `200`, validate content length when present. On ETag change, discard or safely restart stale partial state. Use `NetworkFailure` to retry only transient errors, retain valid newly received resumable bytes on interruption, and re-throw cancellation.

- [ ] **Step 6: Verify and commit protocol remediation**

Run the focused tests, `git diff --check`, ownership verification, then commit `fix: harden WebDAV and resumable transfer validation`.

---

### Task 3: Complete Durable WebDAV Sync, Download, Credentials, and Source Routes

**Target tree:** `D:/DvWorkspaces/CodeX/ClearTune/.worktrees/remediate-employee-2` on `codex/remediate-employee-2`, continuing from Task 2.

**Files:**
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/EncryptedCredentialStore.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncEngine.kt`
- Create: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncWorker.kt`
- Create: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncPort.kt`
- Modify: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavSyncEngineTest.kt`
- Create: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavSyncWorkerTest.kt`
- Modify: `data/download/src/main/java/com/cleartune/data/download/DownloadCoordinator.kt`
- Modify: `data/download/src/main/java/com/cleartune/data/download/DownloadWorker.kt`
- Create: `data/download/src/main/java/com/cleartune/data/download/ProductionDownloadWorkerRunner.kt`
- Create: `data/download/src/main/java/com/cleartune/data/download/DownloadPersistencePort.kt`
- Modify: `data/download/src/test/java/com/cleartune/data/download/DownloadCoordinatorTest.kt`
- Create: `data/download/src/test/java/com/cleartune/data/download/ProductionDownloadWorkerRunnerTest.kt`
- Modify: `feature/sources/src/main/java/com/cleartune/feature/sources/SourcesFeatureEntry.kt`
- Modify: `feature/sources/src/main/java/com/cleartune/feature/sources/SourcesScreen.kt`
- Create: `feature/sources/src/main/java/com/cleartune/feature/sources/SourceController.kt`
- Create: `feature/sources/src/test/java/com/cleartune/feature/sources/SourceControllerTest.kt`
- Modify: `feature/downloads/src/main/java/com/cleartune/feature/downloads/DownloadsUiModel.kt`
- Modify: `feature/downloads/src/test/java/com/cleartune/feature/downloads/DownloadsUiModelTest.kt`

**Interfaces:**
- Produces: `WebDavSyncPort` and `DownloadPersistencePort` owned by their data modules. Final app assembly supplies concrete cross-module persistence adapters in Task 6.
- Produces: reachable restorable source list/add/edit/root/browse/sync routes and actionable download rows.

- [ ] **Step 1: Add failing durable-flow tests**

Cover persisted checkpoint resume, unique work replacement/cancellation, unchanged ETag+size short-circuit, cancellation propagation, bounded enrichment, `UPDATE_AVAILABLE`, worker process recreation, progress persistence, transactional final location publication, canceled-record re-enqueue, scheduler failure reconciliation, and distinct foreground notification IDs.

- [ ] **Step 2: Implement sync worker and persistence port**

`WebDavSyncWorker` resolves its runner through guaranteed application provisioning, reads/writes a persisted BFS checkpoint, uses unique work per source, rethrows cancellation, limits metadata enrichment concurrency, skips unchanged files, and reports classified retry/failure results.

- [ ] **Step 3: Implement production download runner and reconciliation**

Resolve record, URL, credentials, partial path, and expected validators through `DownloadPersistencePort`; invoke `DownloadTransfer`; persist monotonic progress; transactionally publish `DOWNLOADED_FILE` only after transfer verification; reconcile scheduler/file failures; allow explicit revival of canceled rows; and use a stable per-download notification ID.

- [ ] **Step 4: Make encrypted storage backup-safe**

Move encrypted blobs to a no-backup-backed store or add explicit Android backup/data-extraction exclusions. Convert password chars to bytes without an immutable plaintext password `String`, clear temporary buffers, and add Android instrumentation coverage for Keystore round-trip, restore incompatibility handling, deletion, and backup exclusion resources.

- [ ] **Step 5: Complete source and download feature routes**

Implement restorable source list/add/edit/root/browse/sync routes through `SourceController`, wire test/save/delete/sync actions, surface classified errors, and expose lifecycle-aware download actions for cancel/delete/retry. Resolve display titles through injected presentation data instead of raw track IDs.

- [ ] **Step 6: Verify and commit durable-flow remediation**

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' :core:network:testDebugUnitTest :data:webdav:testDebugUnitTest :data:download:testDebugUnitTest :feature:sources:testDebugUnitTest :feature:downloads:testDebugUnitTest :data:webdav:assembleDebugAndroidTest :data:download:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon
```

Run ownership and whitespace gates, then commit `feat: complete durable WebDAV and offline flows`.

---

### Task 4: Make Playback Restoration, Fallback, and Session Lifecycle Safe

**Target tree:** `D:/DvWorkspaces/CodeX/ClearTune/.worktrees/remediate-employee-3` on `codex/remediate-employee-3`, forked from Employee 3 implementation head `b41ea07ef0133d0211f76f6bfd23b4d06d892d83`.

**Files:**
- Modify: `playback/src/main/java/com/cleartune/playback/PlaybackCoordinator.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/Media3PlaybackBackend.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/PersistentQueueRepository.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/ClearTuneLibrarySessionCallback.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/MediaItemFactory.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/PrivateMediaSourceRegistry.kt`
- Modify: `playback/src/test/java/com/cleartune/playback/PlaybackCoordinatorTest.kt`
- Modify: `playback/src/test/java/com/cleartune/playback/PersistentQueueRepositoryTest.kt`
- Create: `playback/src/test/java/com/cleartune/playback/PlaybackRecoveryTest.kt`
- Create: `playback/src/test/java/com/cleartune/playback/MediaControllerLifecycleTest.kt`

**Interfaces:**
- Consumes: frozen playback/queue contracts and Media3.
- Produces: paused process recovery, persistent occurrence index and deterministic shuffle order, asynchronous location fallback, reconnectable controller state, functional browse/play-by-ID metadata, and bounded private URI mappings.

- [ ] **Step 1: Add failing playback safety tests**

Cover recovery with persisted `playWhenReady=true` remaining paused; next/previous index persistence; stable shuffle order after recreation; asynchronous item-scoped error fallback; global TLS/auth error pause; controller disconnect/reconnect with queued command order; browse children/play-by-ID; full title/artist/album metadata; and registry eviction.

- [ ] **Step 2: Implement paused deterministic recovery**

Persist queue occurrence index and deterministic shuffle order alongside queue state. Recovery restores position/modes/order, explicitly clears `playWhenReady`, prepares without playing, and updates persisted recovery state transactionally.

- [ ] **Step 3: Implement asynchronous fallback and reconnectable controller**

Classify `PlayerException` callbacks. Advance locations only for item-scoped read/decoder/not-found failures; pause and surface global auth/TLS/network errors. Recreate `MediaController` after exceptional completion or disconnect, expose accurate connection state, preserve command order, and release old controllers.

- [ ] **Step 4: Complete media-library session metadata and browse**

Inject browse/play resolution, return playable children for approved categories, implement play-by-media-ID, and populate sanitized title/artist/album/artwork metadata. Bound or clear private URI registrations whenever queue items are replaced.

- [ ] **Step 5: Verify and commit playback remediation**

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' :playback:testDebugUnitTest :playback:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon
```

Run ownership and whitespace gates, then commit `fix: make playback recovery and session lifecycle safe`.

---

### Task 5: Complete Player, Queue, Playlist, and Settings Product Behavior

**Target tree:** `D:/DvWorkspaces/CodeX/ClearTune/.worktrees/remediate-employee-3` on `codex/remediate-employee-3`, continuing from Task 4.

**Files:**
- Modify: `feature/player/src/main/java/com/cleartune/feature/player/PlayerFeatureEntry.kt`
- Modify: `feature/player/src/main/java/com/cleartune/feature/player/PlayerUiState.kt`
- Modify: `feature/player/src/test/java/com/cleartune/feature/player/PlayerUiStateTest.kt`
- Modify: `feature/playlists/src/main/java/com/cleartune/feature/playlists/PlaylistsFeatureEntry.kt`
- Modify: `feature/playlists/src/main/java/com/cleartune/feature/playlists/InMemoryPlaylistRepository.kt`
- Modify: `feature/playlists/src/test/java/com/cleartune/feature/playlists/InMemoryPlaylistRepositoryTest.kt`
- Modify: `feature/settings/src/main/java/com/cleartune/feature/settings/SettingsFeatureEntry.kt`
- Modify: `feature/settings/src/main/java/com/cleartune/feature/settings/PersistentSettingsRepository.kt`
- Modify: `feature/settings/src/test/java/com/cleartune/feature/settings/PersistentSettingsRepositoryTest.kt`
- Modify: `app/src/main/java/com/cleartune/app/BaselineApp.kt`
- Modify: `app/src/test/java/com/cleartune/app/AppRoutesTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ProductNavigationTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/AccessibilitySmokeTest.kt`

**Interfaces:**
- Produces: restorable product routes and feature-owned UI state that consume contracts rather than feature implementation classes.
- Defers replacement of temporary repository adapters to Task 6.

- [ ] **Step 1: Add failing behavior and navigation tests**

Cover mini-player progress/error state, queue tap/reorder/remove/clear confirmation, explicit accessibility actions, playlist detail restoration and back-to-list, add-next/add-last, track-title presentation, settings command coverage, reduced-motion application, 200% font layout, and process-recreated app routes.

- [ ] **Step 2: Complete player and queue interactions**

Implement progress and actionable error state, real artwork fallback, favorite/download actions through injected commands, lyrics state rather than a permanent placeholder, queue occurrence presentation, tap-to-play, reorder/remove, clear confirmation, and semantics for every gesture-only action.

- [ ] **Step 3: Make playlists restorable and persistence-neutral**

Use saved route arguments for playlist detail and Android back navigation. Resolve presentation data, implement required add/order/remove actions, and depend on `PlaylistRepository`; keep the in-memory implementation test-only or as an explicit preview fallback rather than production storage.

- [ ] **Step 4: Complete settings behavior**

Expose queue restore, headphone-disconnect pause, cache/offline management, dynamic background, scan, cleanup, and licenses settings through typed commands/state. Apply reduced motion to transitions and avoid feature-owned ad-hoc persistence in production assembly.

- [ ] **Step 5: Compile instrumentation and verify product modules**

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' :feature:player:testDebugUnitTest :feature:playlists:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon
```

Run ownership and whitespace gates, then commit `feat: complete playback product experiences`.

---

### Task 6: Integrate Real Repositories, Routes, and All Three Deliverables

**Target tree:** `D:/DvWorkspaces/CodeX/ClearTune/.worktrees/integration` on `codex/integration`, forked from `main` after this plan is committed.

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/AppContainer.kt`
- Modify: `app/src/main/java/com/cleartune/app/ClearTuneApplication.kt`
- Modify: `app/src/main/java/com/cleartune/app/BaselineApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/cleartune/app/LocalSnapshotAdapter.kt`
- Create: `app/src/main/java/com/cleartune/app/WebDavPersistenceAdapters.kt`
- Modify: `app/src/test/java/com/cleartune/app/AppRoutesTest.kt`
- Create: `app/src/test/java/com/cleartune/app/AppContainerIntegrationTest.kt`
- Modify: `docs/development/employee-startup.md`

**Interfaces:**
- Consumes: Employee 1 Room repositories and library browse state; Employee 2 sync/download persistence ports and source/download controllers; Employee 3 playback/product assembly.
- Produces: a production container with no empty adapters, safe credential-source matching, real feature routes, and one verified APK.

- [ ] **Step 1: Validate remediation refs without rewriting commits**

Confirm `codex/remediate-employee-1`, `codex/remediate-employee-2`, and `codex/remediate-employee-3` contain their corresponding reviewed implementation heads and every implementation merge-base remains `763987c`. Preserve the original employee branches and external Codex worktrees unchanged.

- [ ] **Step 2: Merge in the approved order**

Merge Employee 1 remediation, then Employee 2 remediation, then Employee 3 remediation into `codex/integration` using non-fast-forward merge commits so provenance remains visible. Stop at the first conflict and resolve only in `app` or the owning branch's modules. Do not merge `codex/integration` into `main` in this task.

- [ ] **Step 3: Add failing production-assembly tests**

Assert the production container uses Room-backed library/playlist/queue state, a real credential store, real source/download controllers, real local/sync/download persistence adapters, exact parsed source-origin matching, and all approved routes. Assert no `Empty*Repository` reaches production construction.

- [ ] **Step 4: Implement cross-module adapters in `app`**

Implement `LocalSnapshotAdapter`, sync/download persistence adapters, WorkerFactory/runner provisioning, Room/DataStore repository wiring, exact scheme/host/effective-port/path-segment credential lookup, and application startup scheduling. Do not introduce data-to-data or feature-to-feature dependencies.

- [ ] **Step 5: Replace generic routes with real feature entry points**

Wire library albums/artists/folders/search/detail, sources add/edit/root/browse/sync, downloads actions, player/queue, playlist detail, and settings routes into the no-bottom-navigation app shell with saved state and Android back behavior.

- [ ] **Step 6: Run full integration verification**

```powershell
& 'D:\DvEnvironment\gradle-9.5.0\bin\gradle.bat' testDebugUnitTest lintDebug :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon
powershell -ExecutionPolicy Bypass -File scripts\verify-project-layout.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-contract-shape.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-shared-baseline.ps1
powershell -ExecutionPolicy Bypass -File scripts\verify-branch-ownership.ps1 -SelfTest
git diff --check
```

Expected: zero test/lint/build/structural failures; `app-debug.apk` and debug test APK exist.

- [ ] **Step 7: Perform final whole-tree review and commit integration fixes**

Review the complete `main..codex/integration` range against the approved product/UI specs. Fix every Critical/Important finding in one scoped wave, re-run Step 6, then commit `feat: integrate ClearTune local WebDAV and playback product`. After verification, stop and present the required merge/push/keep choices; never merge to `main` without the user's explicit selection.
