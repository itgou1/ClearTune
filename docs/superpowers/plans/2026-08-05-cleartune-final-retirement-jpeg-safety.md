# Final Retirement and JPEG Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the final late-publication race for retired WebDAV sources and require a real, memory-bounded mature-decoder success before JPEG artwork can be cached.

**Architecture:** Every follow-on sync publication returns `MutationDisposition`: update availability is guarded in its Room transaction, while checkpoint save/retire operations serialize through one persistent store lock and an atomic retired tombstone. JPEG bytes first pass the existing bounded structural validator, then an injected mature-decoder probe; Android uses a sampled platform decoder and JVM contracts use Java ImageIO.

**Tech Stack:** Kotlin 2.3.21, Room 2.8.4, SharedPreferences, kotlinx.coroutines `Mutex`, Android `BitmapFactory`/`ImageDecoder`, Java ImageIO, Gradle 9.5.0/JDK 17, JUnit 4, AndroidX instrumentation.

## Global Constraints

- Start from clean `d66e81d26b944420acd9427c37e69e4922a7b760` on `codex/integration`.
- Make exactly one final commit with subject `fix: finalize retired source and jpeg safety`.
- Do not push, merge, rewrite refs, use subagents, or modify unrelated behavior.
- Preserve the current PNG validator unchanged except for plumbing the JPEG-only probe.
- JPEG compressed input presented to the production decoder probe is capped at 256 KiB.
- Production decoded output is bounded to at most 64 by 64 pixels, uses low-memory/software settings, and is immediately recycled.
- Invalid or unsupported JPEG artwork is not cached, while ordinary track metadata continues.
- Device-only tests must be compiled and reported honestly when no device is attached.

---

### Task 1: Retirement-aware update and checkpoint publications

**Files:**
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncPort.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncEngine.kt`
- Modify: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavSyncEngineTest.kt`
- Modify: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavSyncWorkerTest.kt`
- Modify: `app/src/main/java/com/cleartune/app/WebDavPersistenceAdapters.kt`
- Modify: `app/src/main/java/com/cleartune/app/SourceRemovalCoordinator.kt`
- Modify: `app/src/main/java/com/cleartune/app/AppContainer.kt`
- Modify: `app/src/androidTest/java/com/cleartune/app/WebDavSourceRemovalAndroidTest.kt`

**Interfaces:**
- Produce: `WebDavCheckpointStore.save(checkpoint): MutationDisposition`.
- Produce: `WebDavCheckpointStore.retire(sourceId)` and `WebDavSyncPort.retireCheckpoint(sourceId)`.
- Produce: `RemoteUpdatePublisher.markUpdateAvailable(...): MutationDisposition`.
- Consume: existing `MutationDisposition.APPLIED | SOURCE_RETIRED` and Room `isSourceActive` query.

- [ ] Add an engine test where Upsert is `APPLIED`, removal wins before update publication, the publisher returns `SOURCE_RETIRED`, no checkpoint save occurs, and the report is retired.
- [ ] Add an engine test where update publication is `APPLIED`, retirement wins before checkpoint save, save returns `SOURCE_RETIRED`, and the report is retired.
- [ ] Add runner coverage that a retired report persists checkpoint retirement and returns `COMPLETED` without retry.
- [ ] Run focused WebDAV JVM tests and record RED on Unit-returning publication/checkpoint APIs.
- [ ] Change publisher and checkpoint callbacks to return a disposition, and make every engine save/mark boundary terminate with a retired report on `SOURCE_RETIRED`.
- [ ] In `RoomWebDavPersistenceAdapter.markUpdateAvailable`, execute `isSourceActive` and the download update in one `database.withTransaction`, returning the explicit disposition.
- [ ] In `SharedPreferencesWebDavCheckpointStore`, keep save/load/clear/retire under its existing `Mutex`; `retire` must atomically remove the checkpoint and persist `retired:<sourceId>`, and `save` must reject a persisted tombstone.
- [ ] Make removal and reconciliation call `retireCheckpoint`; make a retired worker reinforce retirement, while successful active completion only clears its checkpoint.
- [ ] Treat a pre-removal `UPDATE_AVAILABLE` retained offline download as `COMPLETED` in both removal and reconciliation.
- [ ] Add controlled Room instrumentation for `APPLIED -> removal -> mark` and `mark APPLIED -> removal -> save`, plus real SharedPreferences save-before-retire and retire-before-save ordering.
- [ ] Run focused JVM tests to GREEN and compile/package the instrumentation tests.

### Task 2: Mature bounded JPEG decode gate

**Files:**
- Create: `data/webdav/src/main/java/com/cleartune/data/webdav/AndroidJpegDecodeProbe.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/EmbeddedArtworkValidator.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/RangeWebDavMetadataEnricher.kt`
- Modify: `data/webdav/src/test/java/com/cleartune/data/webdav/RangeWebDavMetadataEnricherTest.kt`
- Create: `data/webdav/src/androidTest/java/com/cleartune/data/webdav/AndroidJpegDecodeProbeTest.kt`

**Interfaces:**
- Produce: `fun interface JpegDecodeProbe { fun canDecode(bytes, start, end, expectedWidth, expectedHeight): Boolean }`.
- Produce: `AndroidJpegDecodeProbe`, used by production enrichment.
- Consume: validated JPEG bounds from the existing structural parser before bytes are copied or cached.

- [ ] Add a Java ImageIO-backed real test probe and confirm real baseline/progressive fixtures decode.
- [ ] Add JPEG fixtures that pass the current structural parser but fail mature decode: one-byte/premature entropy, invalid Huffman data, consecutive or missing restart boundaries, and invalid progressive refinement order. Assert metadata survives and the cache is untouched.
- [ ] Run the focused parser test and record RED showing structurally accepted corrupt JPEG reaches the cache or the wished-for probe API is absent.
- [ ] Thread the injected probe through ID3/FLAC parsing and call it only after JPEG structure/MIME/dimension/byte checks succeed and before `EmbeddedArtwork` copies bytes.
- [ ] Implement API 26-27 `BitmapFactory`: bounds-only dimension/MIME verification, power-of-two `inSampleSize`, `RGB_565`, disabled density scaling, actual result within 64 by 64, exception/null rejection, and immediate recycle.
- [ ] Implement API 28+ `ImageDecoder`: sliced `ByteBuffer`, exact header dimensions, software allocator, `MEMORY_POLICY_LOW_RAM`, at-most-64-by-64 target, partial listener returning false, exception rejection, and immediate recycle.
- [ ] Add instrumentation coverage that the production Android probe accepts the real baseline/progressive fixtures and rejects a known corrupt entropy fixture; compile/package it when no device exists.
- [ ] Run focused parser tests to GREEN and rerun all WebDAV tests.

### Task 3: Verification and delivery

**Files:**
- Create outside the branch: `.superpowers/sdd/2026-08-04-cleartune-integration-remediation-plan/task-6-fix-round-5-report.md`

- [ ] Run the combined app/core-database/WebDAV tests and Android-test assembly gate.
- [ ] Run exact Gradle 9.5 gate: `testDebugUnitTest lintDebug :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon`.
- [ ] Run all four verification scripts, `git diff --check`, XML counts, APK hashes/sizes, and `adb devices -l`.
- [ ] Inspect the full staged diff and run `git diff --cached --check`.
- [ ] Commit exactly once with `fix: finalize retired source and jpeg safety`.
- [ ] Write the Round 5 report with RED/GREEN evidence, exact commit hash, clean status, decoder scope, and no-device limitation.
