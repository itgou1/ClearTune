# ClearTune Integration Review Round 2 Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all seven Important Round 2 findings, the residual hierarchical media-library issue, and the missing executable Room v1-to-v2 migration coverage.

**Architecture:** Keep Room as the source of truth for download ownership, generation/state authority, queue recovery, and library hierarchy. Isolate hostile metadata parsing per WebDAV entry and place bounded embedded artwork behind a deterministic, contained, atomic file cache whose URI is persisted through the existing `Track.artworkRef`; no database version change is required. Treat tested-root selection and cache usage as explicit state transitions rather than inferred UI text.

**Tech Stack:** Kotlin 2.3.21, Android SDK 26-37, Room 2.8.4, WorkManager 2.11.2, Media3 1.10.1, OkHttp 5.3.0, Compose, coroutines, JUnit4, AndroidX instrumentation.

## Global Constraints

- Start clean on `codex/integration` at `15fd9aa69fdb17c5572d95a8920ff074eca0ceca`.
- Witness a behavior-specific RED before every production fix, then run its focused GREEN gate.
- Do not modify `main`, employee/remediation refs, or external refs; do not push or use subagents.
- Preserve cancellation propagation, exact WebDAV subtree/cleartext gates, contained filesystem writes, and secret-free MediaItems.
- Store artwork only after allowlisted MIME and byte limits; use overflow-safe remaining-length checks for all attacker-controlled lengths.
- Keep browsing page-bounded and query-batched at every hierarchy level.
- Produce one final commit with subject exactly `fix: close remaining integration review gaps`.

---

### Task 1: Authoritative download ownership and worker writes

**Files:**
- Modify: `data/download/src/main/java/com/cleartune/data/download/{DownloadCoordinator,DownloadPersistencePort,ProductionDownloadWorkerRunner}.kt`
- Modify: `app/src/main/java/com/cleartune/app/DownloadPersistenceAdapter.kt`
- Modify: `core/database/src/main/java/com/cleartune/core/database/dao/ProductDaos.kt`
- Test: `data/download/src/test/java/com/cleartune/data/download/ProductionDownloadWorkerRunnerTest.kt`
- Test: `app/src/androidTest/java/com/cleartune/app/{ProductionPersistenceAndroidTest,WebDavSourceRemovalAndroidTest}.kt`

**Interfaces:**
- `DownloadPersistencePort.persistProgress(downloadId, generation, downloadedBytes, totalBytes): Boolean`
- `DownloadPersistencePort.recordFailure(downloadId, generation, code): Boolean`
- Room enqueue insertion resolves one active remote location and writes its `sourceId` inside the same transaction as the new `downloads` row.

- [ ] Add an Android regression that starts enqueue persistence, removes the source before `beginWork`, and asserts the queued record is owned, selected for cancellation, and becomes authoritative CANCELED/cleanup state.
- [ ] Run the Android-test compile/focused available test gate and record the expected RED caused by null ownership.
- [ ] Add JVM runner races for pause/cancel before progress, transfer failure, unexpected failure, and `final_file_missing`; assert no progress/FAILED mutation is accepted for the captured stale generation.
- [ ] Run the runner suite and record RED from legacy unconditional methods.
- [ ] Implement transactional source resolution during insert and state+generation conditional DAO writes for RUNNING only; pass the captured generation to every progress and failure call.
- [ ] Run download unit tests and Android-test compilation GREEN.

### Task 2: Overflow-safe metadata isolation and bounded artwork lifecycle

**Files:**
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/{RangeWebDavMetadataEnricher,WebDavSyncEngine}.kt`
- Create: `data/webdav/src/main/java/com/cleartune/data/webdav/EmbeddedArtworkCache.kt`
- Modify: `app/src/main/java/com/cleartune/app/{AppContainer,SourceRemovalCoordinator}.kt`
- Modify: `core/database/src/main/java/com/cleartune/core/database/dao/LibraryDao.kt`
- Test: `data/webdav/src/test/java/com/cleartune/data/webdav/{RangeWebDavMetadataEnricherTest,WebDavSyncEngineTest,EmbeddedArtworkCacheTest}.kt`

**Interfaces:**
- `ArtworkCache.store(sourceId, sourceKey, mimeType, bytes): String`
- `ArtworkCache.remove(sourceId, sourceKey)` and `ArtworkCache.clearSource(sourceId)`
- Parser output carries optional `EmbeddedArtwork(mimeType, bytes)` internally; persisted metadata carries only the resulting contained `file:` URI.

- [ ] Add literal malformed ID3/FLAC fixtures with `Int.MAX_VALUE`, unsigned-overflow, truncated APIC/PICTURE lengths, and parser exceptions; assert per-entry filename fallback while sibling entries still sync.
- [ ] Run WebDAV tests and record parser/isolation RED.
- [ ] Add literal valid APIC and FLAC PICTURE fixtures plus malicious MIME/oversize fixtures; assert accepted bytes are written atomically under the configured root, repeat is idempotent, replacement removes stale alternate files, and source clear removes only its subtree.
- [ ] Run artwork tests and record RED because no cache/parser path exists.
- [ ] Implement cursor-style remaining-length readers, catch non-cancellation enrichment failures per entry, enforce JPEG/PNG plus compressed-byte bounds, and atomically store deterministic per-source artwork files.
- [ ] Clear artwork for a tombstoned source and replace Room artwork references on changed WebDAV metadata so removed/replaced art does not leave a stale reference.
- [ ] Run WebDAV, database compile, and app compile gates GREEN.

### Task 3: Shuffle-preserving queue replacement

**Files:**
- Modify: `core/database/src/main/java/com/cleartune/core/database/RoomProductRepositories.kt`
- Test: `app/src/androidTest/java/com/cleartune/app/ProductionPersistenceAndroidTest.kt`
- Test: `core/database/src/test/java/com/cleartune/core/database/QueueShuffleOrderTest.kt`

**Interfaces:**
- Internal replacement order builder accepts new occurrence IDs and selected occurrence ID, returns selected first plus a non-natural deterministic order when at least three occurrences permit one.

- [ ] Add `shuffle enabled -> QueueCommand.Replace -> repository recreation` coverage with duplicate occurrences; assert selected occurrence, saved position semantics, shuffle flag, and non-natural complete order.
- [ ] Run Android-test compilation/focused helper tests and record RED from natural replacement order.
- [ ] Generate replacement shuffle order whenever persisted shuffle is enabled, while preserving the selected replacement occurrence and position contract.
- [ ] Run queue helper/unit and Android-test compile gates GREEN.

### Task 4: Live cache usage state

**Files:**
- Modify: `feature/settings/src/main/java/com/cleartune/feature/settings/{PersistentSettingsRepository,SettingsFeatureEntry}.kt`
- Modify: `app/src/main/java/com/cleartune/app/ProductPersistenceAdapters.kt`
- Test: `feature/settings/src/test/java/com/cleartune/feature/settings/PersistentSettingsRepositoryTest.kt`
- Test: `app/src/test/java/com/cleartune/app/AppProductSettingsControllerTest.kt`

**Interfaces:**
- Add `SettingsProductCommand.RefreshCacheUsage` as an event-driven refresh.
- Settings entry dispatches the refresh when it enters composition; cleanup always republishes the post-cleanup byte count.

- [ ] Add a controller test that collects `productSettings`, writes a non-empty file after construction, dispatches refresh, observes non-zero bytes, then dispatches cleanup and observes zero.
- [ ] Run the focused test and record RED from stale construction-only sampling.
- [ ] Implement event refresh and settings-entry lifecycle dispatch without periodic polling or broad cache traversal.
- [ ] Run settings/app unit tests GREEN.

### Task 5: Mandatory nested tested-root selection

**Files:**
- Modify: `feature/sources/src/main/java/com/cleartune/feature/sources/{SourceController,SourcesUiModel,SourcesScreen,SourcesFeatureEntry}.kt`
- Test: `feature/sources/src/test/java/com/cleartune/feature/sources/SourceControllerTest.kt`
- Test: `app/src/test/java/com/cleartune/app/AppRoutesTest.kt`

**Interfaces:**
- `TestedSourceDraft` owns `browsePath` and `selectedRoot` state alongside the validated draft.
- `selectRoot` accepts `""` for the tested directory itself; `save` rejects receipts whose root step is incomplete.
- Form presents `Use this folder` for the current tested path and directory rows navigate deeper without invalidating the receipt.

- [ ] Add tests proving save-before-selection fails without consuming the receipt, the tested directory itself can be selected, nested `album/live` browsing preserves the receipt, and state can be restored without exposing the password.
- [ ] Run source tests and record RED from optional/one-level selection.
- [ ] Implement required receipt state, safe relative-path joining/back navigation, explicit current-folder selection, nested browse rendering, and save enablement based on completed selection.
- [ ] Run source/navigation tests and app Android-test compilation GREEN.

### Task 6: Hierarchical MediaLibrary and executable Room migration coverage

**Files:**
- Modify: `core/database/src/main/java/com/cleartune/core/database/{dao/LibraryDao,model/LibraryDatabaseModels}.kt`
- Modify: `app/src/main/java/com/cleartune/app/ProductPersistenceAdapters.kt`
- Modify: `playback/src/main/java/com/cleartune/playback/ClearTuneLibrarySessionCallback.kt`
- Test: `playback/src/test/java/com/cleartune/playback/PlaybackRecoveryTest.kt`
- Test: `app/src/androidTest/java/com/cleartune/app/ProductionPersistenceAndroidTest.kt`
- Modify: `core/database/build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `core/database/src/androidTest/java/com/cleartune/core/database/ClearTuneDatabaseMigrationTest.kt`

**Interfaces:**
- Catalog rows carry `browsable`/`playable` and nullable playback URI.
- `albums`, `artists`, and `playlists` return paged entity nodes (`album:<id>`, `artist:<id>`, `playlist:<id>`); their children are paged track queries with one chosen playable location each.

- [ ] Add callback/catalog tests that browse category nodes and then their distinct child tracks across pages; assert entity nodes are browsable/non-playable and children playable.
- [ ] Run playback test and Android-test compilation to record flat-row RED.
- [ ] Add batched entity-page and entity-child DAO queries and route callback descriptions through node flags without per-item resolution.
- [ ] Add Room `MigrationTestHelper` source that creates the exported v1 schema, seeds active/tombstoned ownership cases, migrates with `MIGRATION_1_2`, validates v2, and asserts backfill plus `removed/workGeneration/cleanupPending` defaults.
- [ ] Compile the migration instrumentation test and run it only if `adb devices` exposes a device; otherwise report compile-only honestly.
- [ ] Run playback/database/app focused gates GREEN.

### Task 7: Final verification and delivery

- [ ] Run all modified focused suites and record their exact outcomes.
- [ ] Run `gradle.bat testDebugUnitTest lintDebug :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon`.
- [ ] Run all four structural PowerShell scripts and `git diff --check`.
- [ ] Record unit/lint XML totals, Room schema, APK sizes, merged manifest, and `adb devices -l` state.
- [ ] Re-read every Round 2 finding and map implementation to regression evidence in `task-6-fix-round-2-report.md`.
- [ ] Commit once with `fix: close remaining integration review gaps`, verify clean status, and do not push or alter other refs.
