# ClearTune Local Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users grant scoped audio access, scan MediaStore incrementally, and browse/search a live local library through the approved Library, Songs, Albums, Artists, and Folders screens.

**Architecture:** Isolate Android cursors behind `MediaStoreGateway`; feed normalized snapshots to a pure `LocalScanEngine`; apply diffs in one Room transaction; render only Room flows. Metadata failures preserve playable locations and surface non-blocking scan results.

**Tech Stack:** MediaStore/ContentResolver, Room transactions and FTS, WorkManager, Compose, Coil, coroutines/Flow, JUnit, AndroidX instrumentation.

## Global Constraints

- Depend on the completed foundation plan and preserve its public interfaces.
- Request `READ_MEDIA_AUDIO` on API 33+ and `READ_EXTERNAL_STORAGE` on API 26–32; never request broad file management.
- Scan only supported audio: MP3, FLAC, M4A/AAC, Ogg/Opus, WAV.
- A failed or canceled scan must not erase the last good library.
- Folder paths displayed to users are normalized relative paths; content URIs remain the playback source.

---

### Task 1: Implement permission policy and user flow

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/local/AudioPermissionPolicy.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/library/LibraryRoute.kt`
- Create: `app/src/test/java/com/cleartune/app/data/local/AudioPermissionPolicyTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/LocalPermissionFlowTest.kt`

**Interfaces:** `fun requiredPermission(sdkInt: Int): String`; UI states are `NotRequested`, `Granted`, `Denied(canAskAgain)`, and `Unavailable`.

- [ ] Write failing unit cases for API 26, 32, 33, and 37 plus Compose cases for first rationale, denial, permanent denial, and WebDAV still being available.
- [ ] Run focused unit and instrumentation tests; expect compilation failure.
- [ ] Implement permission selection, activity-result launcher, rationale copy, retry/settings actions, and manifest max-SDK boundaries. Never block Library navigation on denial.
- [ ] Re-run focused tests and `./gradlew.bat lintDebug`; expect green and no `MANAGE_EXTERNAL_STORAGE` finding.
- [ ] Commit with `git commit -m "feat: add scoped audio permission flow"` after staging the named files.

### Task 2: Read and normalize MediaStore rows

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/local/MediaStoreGateway.kt`
- Create: `app/src/main/java/com/cleartune/app/data/local/AndroidMediaStoreGateway.kt`
- Create: `app/src/main/java/com/cleartune/app/data/local/MediaStoreRowMapper.kt`
- Create: `app/src/test/java/com/cleartune/app/data/local/MediaStoreRowMapperTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/data/local/AndroidMediaStoreGatewayTest.kt`

**Interfaces:**

```kotlin
data class LocalAudioSnapshot(
    val sourceKey: String,
    val contentUri: String,
    val displayName: String,
    val relativeFolder: String,
    val title: String,
    val album: String?,
    val artistNames: List<String>,
    val durationMs: Long?,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long
)
interface MediaStoreGateway { suspend fun readAudio(): List<LocalAudioSnapshot> }
```

- [ ] Write failing mapper tests for null metadata, multiple artists, Unicode filenames, unsupported MIME/extension, zero duration, duplicate display names, and normalized folder separators.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests "*.MediaStoreRowMapperTest"`; expect compilation failure.
- [ ] Query `_ID`, display name, relative path/data fallback, title, album, artist, duration, size, modified date, and MIME type with `IS_MUSIC != 0`; map each accepted row to `content://media/...` and close cursors with `use`.
- [ ] Re-run mapper tests and the instrumentation gateway fixture; verify one malformed row is skipped and reported without aborting the cursor.
- [ ] Commit with `git commit -m "feat: read local MediaStore audio"`.

### Task 3: Build deterministic scan diff and transactional apply

**Files:**
- Create: `app/src/main/java/com/cleartune/app/domain/sync/ScanDiff.kt`
- Create: `app/src/main/java/com/cleartune/app/data/local/LocalScanEngine.kt`
- Create: `app/src/main/java/com/cleartune/app/data/local/LocalLibraryRepository.kt`
- Extend: `app/src/main/java/com/cleartune/app/data/db/dao/*.kt`
- Create: `app/src/test/java/com/cleartune/app/data/local/LocalScanEngineTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/data/local/LocalScanTransactionTest.kt`

**Interfaces:** `LocalScanEngine.diff(previous, incoming): ScanDiff`; match first by stable source key, then retain existing track UUID; remove a track only when no location remains.

- [ ] Write failing cases for add/update/remove/no-op, rename with same MediaStore ID, same metadata at two locations, removed local plus surviving WebDAV location, and duplicate incoming source keys.
- [ ] Run focused tests; expect compilation failure.
- [ ] Implement a pure O(n) map-based diff and a `@Transaction` apply method that upserts album/artist cross refs, FTS rows, locations, and `SyncSession`; publish completion only after commit.
- [ ] Re-run tests. Simulate an exception halfway through apply and prove the previous database state remains intact.
- [ ] Commit with `git commit -m "feat: synchronize local library incrementally"`.

### Task 4: Schedule scans and expose progress/recovery

**Files:**
- Create: `app/src/main/java/com/cleartune/app/worker/LocalScanWorker.kt`
- Create: `app/src/main/java/com/cleartune/app/data/local/LocalScanCoordinator.kt`
- Modify: `app/src/main/java/com/cleartune/app/di/AppContainer.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/library/LibraryViewModel.kt`
- Create: `app/src/test/java/com/cleartune/app/data/local/LocalScanCoordinatorTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/worker/LocalScanWorkerTest.kt`

**Interfaces:** unique work name `local-library-scan`; policy `KEEP` for automatic requests and `REPLACE` for explicit refresh; progress contains `phase`, `processed`, and `total` only.

- [ ] Write failing tests for request deduplication, manual replacement, permission loss, worker retry on transient resolver failure, and permanent success-with-warning for malformed rows.
- [ ] Run focused tests; expect red.
- [ ] Implement coordinator and `CoroutineWorker`; keep large data out of WorkManager input/output and write detail to `SyncSession`. Trigger first scan after grant and refresh from Library pull-to-refresh/source action.
- [ ] Re-run worker tests with `WorkManagerTestInitHelper`, then all unit tests.
- [ ] Commit with `git commit -m "feat: run durable local library scans"`.

### Task 5: Complete local browsing and search screens

**Files:**
- Create/modify: `app/src/main/java/com/cleartune/app/ui/feature/{library,songs,albums,artists,folders,search}/**/*.kt`
- Create: `app/src/main/java/com/cleartune/app/ui/component/{Artwork.kt,ArtistAvatar.kt,TrackRow.kt,ErrorState.kt}`
- Create: `app/src/main/res/drawable/avatar_artist_01.xml` through `avatar_artist_08.xml`
- Create: `app/src/main/java/com/cleartune/app/ui/component/ArtistAvatarSelector.kt`
- Create: `app/src/test/java/com/cleartune/app/ui/component/ArtistAvatarSelectorTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/LocalLibraryScreensTest.kt`

**Interfaces:** `avatarIndex = floorMod(artistId.hashCode(), 8)`; search groups are songs/albums/artists; all list keys are persistent IDs; sort/filter state survives recreation.

- [ ] Write failing tests for stable avatar mapping and Compose navigation/content states: populated, empty, loading, partial scan warning, permission denied, zero search results, folder drill-down, album detail, and artist detail.
- [ ] Run focused tests; expect red.
- [ ] Implement the approved root sections, category grid, recent rows, songs list, album grid/detail, artist list/detail, folder browser, and grouped search. Use 8 simple bundled abstract vectors for artists without artwork; use Coil only for real artwork refs.
- [ ] Re-run tests at normal and 200% font scale, light/dark, phone/tablet. Run `./gradlew.bat lintDebug testDebugUnitTest connectedDebugAndroidTest`.
- [ ] Commit with `git commit -m "feat: add local library browsing UI"`.

## Phase Exit Verification

- [ ] On API 26 and 37, seed at least 12 files across all supported formats and 3 folders; verify counts, sort, metadata fallbacks, rescans, and deletion.
- [ ] Deny/revoke permission and confirm WebDAV entry remains usable and the last Room snapshot is not silently destroyed.
- [ ] Compare a 1,000-track second no-op scan against the first scan; it must write no track/location changes.
- [ ] Run `rg -n "MANAGE_EXTERNAL_STORAGE|file://" app/src`; expect no matches.
