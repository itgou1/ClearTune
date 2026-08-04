# ClearTune Offline Downloads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-initiated, durable WebDAV offline downloads with correct pause/resume/cancel semantics, integrity validation, playback integration, and storage management.

**Architecture:** `DownloadCoordinator` translates user commands into Room state and unique WorkManager work. `DownloadWorker` delegates byte transfer to a pure, resumable `DownloadEngine`; it writes only `.part`, validates the response and expected size, then atomically renames. Completed files become high-priority `TrackLocation`s.

**Tech Stack:** WorkManager 2.11.2, OkHttp 5.3.0, Room, app-private files, coroutines, Compose, MockWebServer3.

## Global Constraints

- Downloads are manual only; a WebDAV sync never downloads full audio automatically.
- Final root is `filesDir/offline/<sourceId>/<trackId>/`; temporary name ends in `.part`; final name is sanitized and deterministic.
- Pause retains `.part`; cancel removes `.part` and final file; retry retains valid partial bytes.
- Resume uses `Range: bytes=<length>-` and validates `Content-Range`. If unsupported, restart safely from byte zero.
- A file becomes playable as downloaded only after expected length validation and atomic rename.

---

### Task 1: Define safe paths, names, and state transitions

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/download/DownloadFilePolicy.kt`
- Create: `app/src/main/java/com/cleartune/app/data/download/DownloadStateMachine.kt`
- Create: `app/src/test/java/com/cleartune/app/data/download/DownloadFilePolicyTest.kt`
- Create: `app/src/test/java/com/cleartune/app/data/download/DownloadStateMachineTest.kt`

**Interfaces:** `paths(root, sourceId, trackId, displayName): DownloadPaths`; legal states are queued→running, running→paused/completed/failed, paused→queued/canceled, failed→queued/canceled, completed→update-available, and update-available→queued/canceled; completed may transition directly to queued only for explicit redownload.

- [ ] Write failing tests for traversal characters, Windows-reserved characters, Unicode, empty/long names, extension preservation, path containment, deterministic collision behavior, and every allowed/forbidden state transition.
- [ ] Run focused tests; expect compilation failure.
- [ ] Implement segment sanitization, 120-character filename cap, canonical-root containment check, `.part`/final paths, and a pure transition reducer with explicit rejection reasons.
- [ ] Re-run focused and full unit suites.
- [ ] Commit with `git commit -m "feat: define offline download policy"`.

### Task 2: Implement resumable byte transfer

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/download/DownloadEngine.kt`
- Create: `app/src/main/java/com/cleartune/app/data/download/AtomicFilePublisher.kt`
- Create: `app/src/test/java/com/cleartune/app/data/download/DownloadEngineTest.kt`

**Interfaces:**

```kotlin
data class TransferRequest(val url: HttpUrl, val partial: File, val final: File, val expectedSize: Long?, val etag: String?)
sealed interface TransferResult { data class Completed(val bytes: Long) : TransferResult; data class Retryable(val code: String) : TransferResult; data class PermanentFailure(val code: String) : TransferResult }
```

- [ ] Write MockWebServer failing tests for fresh 200, correct 206 resume, 200 after Range causing safe restart, mismatched `Content-Range`, 416 with already-complete partial, ETag change, truncated body, oversized body, cancellation, disk-full `IOException`, and atomic publish.
- [ ] Run the focused test; expect red.
- [ ] Stream in 64 KiB buffers on IO dispatcher, report throttled progress, call `fsync`, validate expected/response length, and publish with `Files.move(..., ATOMIC_MOVE)` plus same-directory rename fallback. Never load the audio body into memory.
- [ ] Re-run tests and inspect that pause/cancellation closes response/file handles and leaves the exact valid partial length.
- [ ] Commit with `git commit -m "feat: implement resumable download engine"`.

### Task 3: Coordinate WorkManager and Room state

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/download/DownloadCoordinator.kt`
- Create: `app/src/main/java/com/cleartune/app/data/download/DownloadWorker.kt`
- Extend: `app/src/main/java/com/cleartune/app/data/db/dao/DownloadDao.kt`
- Extend: `app/src/main/java/com/cleartune/app/data/db/dao/TrackLocationDao.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/cleartune/app/di/AppContainer.kt`
- Create: `app/src/test/java/com/cleartune/app/data/download/DownloadCoordinatorTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/data/download/DownloadWorkerTest.kt`

**Interfaces:** unique work `download-<trackId>`; network constraint `CONNECTED`; worker input contains only download ID; foreground notification channel `downloads`; pause/cancel are explicit coordinator operations.

- [ ] Write failing tests for duplicate enqueue, pause, resume, cancel, retry, source credential missing, process restart, progress persistence, completed location insertion, and stale completed file reconciliation.
- [ ] Run focused tests; expect red.
- [ ] Implement coordinator transactions and worker. Resolve URL/credential from repositories inside the worker, promote long work to foreground, update bytes at most four times per second, and insert `DOWNLOADED_FILE` location only after publish. Cancel deletes files after WorkManager acknowledges stop.
- [ ] Re-run unit/worker tests with forced process recreation and no network.
- [ ] Commit with `git commit -m "feat: coordinate durable offline downloads"`.

### Task 4: Integrate updates, deletion, and playback fallback

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/data/remote/WebDavSyncEngine.kt`
- Modify: `app/src/main/java/com/cleartune/app/domain/playback/LocationResolver.kt`
- Create: `app/src/main/java/com/cleartune/app/data/download/OfflineFileReconciler.kt`
- Create: `app/src/test/java/com/cleartune/app/data/download/OfflineFileReconcilerTest.kt`
- Extend: `app/src/test/java/com/cleartune/app/domain/playback/LocationResolverTest.kt`

**Interfaces:** remote ETag/size change marks a completed download `UPDATE_AVAILABLE` without deleting it; deleting an offline copy removes only its downloaded location and falls back to local/remote.

- [ ] Write failing cases for source track update, remote deletion with retained offline copy, missing final file, orphan final/partial file, active worker protection, and playback during delete.
- [ ] Run focused tests; expect red.
- [ ] Implement startup/periodic reconciliation with root containment checks. Preserve usable offline files for remotely deleted tracks until user deletes them; mark missing files and let resolver fall through.
- [ ] Re-run focused and playback suites.
- [ ] Commit with `git commit -m "feat: reconcile offline music files"`.

### Task 5: Build Downloads and storage-management UI

**Files:**
- Create/modify: `app/src/main/java/com/cleartune/app/ui/feature/downloads/**/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/settings/**/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/{songs,albums,player}/**/*.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/DownloadsScreenTest.kt`

**Interfaces:** sections are active, completed, failed/paused; each row exposes state, bytes/percent when known, pause/resume/retry/cancel/delete actions; storage shows offline bytes and streaming-cache bytes separately.

- [ ] Write failing Compose tests for queue/running/indeterminate/paused/completed/update-available/failed states, confirmation dialogs, bulk completed deletion, clear streaming cache, offline play badge, and action semantics.
- [ ] Run focused UI tests; expect red.
- [ ] Implement Downloads screen, track/album/player download actions, foreground progress copy, storage summary, and destructive confirmations. Keep the completed file if retrying an update until the replacement publishes.
- [ ] Re-run UI tests with TalkBack, 200% font, light/dark, and tablet layout; run lint/unit/instrumentation suite.
- [ ] Commit with `git commit -m "feat: add offline download management UI"`.

## Phase Exit Verification

- [ ] Download a large file, pause, kill process, relaunch, resume, and compare SHA-256 with the server fixture.
- [ ] Repeat against servers that support Range, ignore Range, change ETag, truncate bodies, and omit content length.
- [ ] Enable flight mode and verify completed downloads play while remote-only items show an actionable offline error.
- [ ] Cancel and delete downloads, then inspect the scoped offline root for orphan `.part`/final files; only intentionally paused partials may remain.
