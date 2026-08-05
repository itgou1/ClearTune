# Source and Artwork Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent retired WebDAV sources from publishing late database writes and accept embedded artwork only after complete bounded PNG/JPEG structural validation.

**Architecture:** Room owns the retirement linearization point: every remote library mutation checks the source row inside the same transaction that would publish it and returns an explicit retired disposition. The WebDAV engine propagates that disposition as a successful terminal report. Image parsing moves to a decoder-free bounded validator that validates the complete PNG/JPEG byte structure before producing cacheable bytes.

**Tech Stack:** Kotlin 2.3.21, Room 2.8.4, Gradle 9.5.0/JDK 17, kotlinx.coroutines, JUnit 4, AndroidX instrumentation, `java.util.zip.CRC32`/`Inflater`.

## Global Constraints

- Make exactly one final commit with subject `fix: enforce source and artwork integrity`.
- Do not push, merge, rewrite refs, use subagents, or change unrelated production behavior.
- Device-only tests must be compiled and reported honestly when no device is attached.
- PNG inflation is capped at 64 MiB of exact expected scanline bytes and never allocates a decoded-pixel buffer.
- Support non-interlaced and Adam7 PNG with legal standard color types/bit depths; unsupported or invalid images are not cached, while metadata enrichment continues.
- Support ordinary 8-bit Huffman baseline and progressive JPEG; unsupported arithmetic/lossless/hierarchical JPEG is not cached, while metadata enrichment continues.

---

### Task 1: Retired-source write boundary

**Files:**
- Modify: `core/model/src/main/java/com/cleartune/core/model/LibraryModels.kt`
- Modify: `core/database/src/main/java/com/cleartune/core/database/dao/LibraryDao.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncEngine.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/WebDavSyncPort.kt`
- Modify: `app/src/main/java/com/cleartune/app/SourceRemovalCoordinator.kt`
- Test: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavSyncEngineTest.kt`
- Test: `data/webdav/src/test/java/com/cleartune/data/webdav/WebDavSyncWorkerTest.kt`
- Test: `app/src/androidTest/java/com/cleartune/app/WebDavSourceRemovalAndroidTest.kt`

**Interfaces:**
- Produce: `MutationDisposition.APPLIED | SOURCE_RETIRED` on `MutationResult`.
- Produce: `WebDavSyncReport.retired: Boolean` and runner terminal `COMPLETED` behavior.
- Consume: the existing `LibraryWriteGateway.applyLibraryMutation` transaction boundary.

- [ ] Add a JVM engine test whose gateway returns `SOURCE_RETIRED` from an upsert. Assert no update publication, no checkpoint finalization/retain mutation, and `report.retired == true`.
- [ ] Add a runner test with a retired report carrying a stale retryable failure. Assert `COMPLETED`, checkpoint clear, and no retry.
- [ ] Run the two focused JVM tests and record RED on the missing retired contract.
- [ ] Add a Room instrumentation race: pause metadata enrichment after the runner loaded its source, commit source removal, resume the sync mutation, then assert `COMPLETED` and the stable location remains unavailable.
- [ ] Add the disposition/report types and make `LibraryWriteDao.applyMutation` query `music_sources.removed = 0` before either `Upsert` or `RetainSourceKeys`, inside its existing `@Transaction`.
- [ ] Stop `WebDavSyncEngine` immediately on the retired disposition and make `DurableWebDavSyncRunner` prioritize retired as successful terminal completion.
- [ ] Reassert `markRemoteLocationsUnavailable` inside tombstone reconciliation before external cancellation effects.
- [ ] Run focused JVM tests to GREEN and compile the Android instrumentation source.

### Task 2: Complete bounded embedded-image validation

**Files:**
- Create: `data/webdav/src/main/java/com/cleartune/data/webdav/EmbeddedArtworkValidator.kt`
- Modify: `data/webdav/src/main/java/com/cleartune/data/webdav/RangeWebDavMetadataEnricher.kt`
- Modify: `data/webdav/src/test/java/com/cleartune/data/webdav/RangeWebDavMetadataEnricherTest.kt`

**Interfaces:**
- Produce: `validateEmbeddedArtwork(...) : EmbeddedArtwork?`, returning copied bytes only after complete validation.
- Consume: declared APIC/PICTURE MIME, optional FLAC dimensions, compressed-byte cap, and the existing cache boundary.

- [ ] Add real baseline/progressive JPEG and PNG acceptance cases plus cache-before-rejection cases for forged SOF+EOI, bad SOF/SOS components, truncated/invalid scan markers, bad PNG CRC, non-contiguous/empty IDAT, invalid zlib, filter byte outside 0..4, and trailing bytes.
- [ ] Recompute test PNG CRCs after dimension mutation so dimension/pixel-limit tests reach those policies rather than failing earlier on CRC.
- [ ] Run the focused parser suite and record the exact RED failures.
- [ ] Validate every PNG chunk CRC and overflow-safe length; require first/unique IHDR, legal color format, valid PLTE policy, contiguous IDAT, terminal IEND, and no trailing bytes.
- [ ] Stream IDAT through bounded zlib inflation and validate exactly calculated scanlines/filters for non-interlaced and Adam7 passes without retaining inflated pixels.
- [ ] Parse JPEG through EOI: validate DQT/DHT tables, one legal SOF and unique components, SOS component/table references and baseline/progressive scan parameters, entropy data byte stuffing/restart sequence, complete segment lengths, and terminal EOI with no trailing bytes.
- [ ] Keep metadata fallback behavior unchanged: invalid/unsupported artwork returns null and never reaches `ArtworkCache.store`.
- [ ] Run the focused parser suite to GREEN and rerun all WebDAV tests.

### Task 3: Verification and delivery

**Files:**
- Create outside the branch: `.superpowers/sdd/2026-08-04-cleartune-integration-remediation-plan/task-6-fix-round-4-report.md`

- [ ] Run the combined app/core-database/WebDAV focused test and Android-test assembly gate.
- [ ] Run the exact Gradle 9.5 command: `testDebugUnitTest lintDebug :app:assembleDebugAndroidTest :app:assembleDebug --console=plain --no-daemon`.
- [ ] Run all four verification scripts, `git diff --check`, unit/lint XML counts, APK size/hash, and `adb devices -l`.
- [ ] Inspect the complete staged diff for only planned files and run `git diff --cached --check`.
- [ ] Commit once with exact subject `fix: enforce source and artwork integrity`.
- [ ] Write the Round 4 report with RED/GREEN evidence, exact commit hash, clean status, and the no-device limitation.
