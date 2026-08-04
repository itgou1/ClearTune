# ClearTune Product Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete playlists and all remaining approved UI behavior, harden adaptive/accessibility states, add end-to-end quality gates, and produce a releasable app.

**Architecture:** Finish product behavior through existing repositories and playback controller, then test the app as a system across phone/tablet, API 26/37, local/WebDAV/offline, and accessibility configurations. No new parallel data path is introduced.

**Tech Stack:** Existing app stack plus Compose UI tests, AndroidX Macrobenchmark and Baseline Profile, Gradle managed devices, lint, R8, GitHub Actions.

## Global Constraints

- Depend on all prior phases and preserve their behavior.
- UI decisions defer to the approved UI specification; additions require a spec amendment before code.
- No bottom navigation. Mini-player remains the only persistent bottom element.
- All user-facing failure states include a plain-language explanation and only actions that can actually resolve the condition.
- Release gates must be reproducible from a clean checkout on JDK 17.

---

### Task 1: Complete playlist CRUD and playback actions

**Files:**
- Implement: `app/src/main/java/com/cleartune/app/data/repository/PlaylistRepository.kt`
- Create/modify: `app/src/main/java/com/cleartune/app/ui/feature/playlists/**/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/{songs,albums,artists,player}/**/*.kt`
- Create: `app/src/test/java/com/cleartune/app/data/repository/PlaylistRepositoryTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/PlaylistFlowTest.kt`

**Interfaces:** playlist name is trimmed and 1–100 characters; ordered entries use cross-ref IDs; duplicate tracks are allowed; delete playlist never deletes tracks; actions include play, shuffle, add next, add last, rename, reorder, remove.

- [ ] Write failing repository/UI tests for create/rename/delete, duplicate names, duplicate tracks, stable reorder, remove one duplicate occurrence, empty state, selection mode, and queue actions.
- [ ] Run focused tests; expect red.
- [ ] Implement transactions, screens, dialogs, overflow actions, and multi-select add-to-playlist. Provide TalkBack “move up/down” actions in addition to drag reorder.
- [ ] Re-run focused and full suites at process recreation boundaries.
- [ ] Commit with `git commit -m "feat: complete playlist workflows"`.

### Task 2: Finish settings, source management, and global state handling

**Files:**
- Create/modify: `app/src/main/java/com/cleartune/app/ui/feature/settings/**/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/sources/**/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/component/{EmptyState.kt,ErrorState.kt}`
- Create: `app/src/main/java/com/cleartune/app/data/repository/SettingsRepository.kt`
- Create: `app/src/test/java/com/cleartune/app/data/repository/SettingsRepositoryTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/SettingsAndStatesTest.kt`

**Interfaces:** settings include theme (system/light/dark), reduced motion (system/on/off), scan actions, source list, offline/cache storage, and about/licenses. Persist non-sensitive values with DataStore; credentials stay in Keystore.

- [ ] Write failing tests for persistence, theme/reduced-motion application, source remove cleanup policy, local/remote rescan, clear cache, about/licenses, and every loading/empty/offline/permission/auth/server/storage failure state named in the UI spec.
- [ ] Run focused tests; expect red.
- [ ] Implement typed DataStore settings and complete screens/state components. Removing a source requires confirmation, cancels its work, deletes its credentials, and transactionally removes only that source’s remote locations; separately ask whether to delete its offline copies.
- [ ] Re-run tests and verify sensitive fields are excluded from DataStore and saved-instance state.
- [ ] Commit with `git commit -m "feat: complete settings and source management"`.

### Task 3: Enforce adaptive layouts and visual system consistency

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/ui/ClearTuneApp.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/theme/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/**/*.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/AdaptiveLayoutTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/VisualSystemTest.kt`

**Interfaces:** compact width uses single-pane push navigation; medium/expanded uses list-detail when meaningful; content max width and gutters come from `Dimensions`; only full player derives a background palette from album art.

- [ ] Write failing tests at compact/medium/expanded widths for library grids, albums, artists, folders, playlists, source detail, queue, orientation changes, mini-player insets, and full-player palette/fallback.
- [ ] Run focused tests; expect red.
- [ ] Implement window-size-based layouts, stable selection across pane changes, consistent spacing/type/iconography, and neutral surfaces with coral accent. Verify the 8 default artist avatars remain simple vectors and stable by ID.
- [ ] Re-run adaptive tests and capture golden screenshots for review in light/dark at phone and tablet sizes.
- [ ] Commit with `git commit -m "feat: polish adaptive ClearTune UI"`.

### Task 4: Pass the accessibility acceptance suite

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/ui/**/*.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/AccessibilityTest.kt`
- Create: `docs/testing/accessibility-checklist.md`

**Interfaces:** every actionable element has role/name/state/action semantics; minimum target is 48 dp; body/controls meet WCAG AA; focus order follows visual order; dynamic announcements are concise.

- [ ] Write failing automated checks for missing content descriptions, tiny targets, merged semantics, selected/playing/download states, queue reorder actions, progress semantics, 200% font clipping, and reduced-motion behavior.
- [ ] Run the focused test with font scale 2.0 and animation scales 0; expect failures before fixes.
- [ ] Fix each screen and document manual TalkBack flows for onboarding/permission, local playback, WebDAV setup, download management, and queue reorder. Decorative artwork descriptions remain null; meaningful artwork uses album/artist text.
- [ ] Run automated checks, Android Accessibility Scanner, and the documented TalkBack flows on phone/tablet.
- [ ] Commit with `git commit -m "fix: meet ClearTune accessibility requirements"`.

### Task 5: Add end-to-end scenarios and a test-only benchmark module

**Files:**
- Create: `app/src/androidTest/java/com/cleartune/app/e2e/{LocalPlaybackJourneyTest.kt,WebDavJourneyTest.kt,OfflineJourneyTest.kt,RecoveryJourneyTest.kt}`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/AndroidManifest.xml`
- Create: `benchmark/src/main/java/com/cleartune/benchmark/BaselineProfileGenerator.kt`
- Create: `benchmark/src/main/java/com/cleartune/benchmark/StartupAndScrollBenchmark.kt`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `docs/testing/manual-server-matrix.md`

**Interfaces:** test fixtures use deterministic fake repositories or MockWebServer; no live credentials enter CI. Baseline journey is cold launch→Library→Songs→play→full player.

- [ ] Write end-to-end journeys covering local grant/scan/play, WebDAV setup/sync/play, download/offline play, permission revocation, network loss, service/process restart, database reopen, and source removal; add the benchmark task names to the documented verification command before the module exists.
- [ ] Run the journeys and `./gradlew.bat :benchmark:connectedCheck`; expect failing journey assertions or `project 'benchmark' not found`.
- [ ] Fix product behavior exposed by the journeys without timing sleeps, then add the `com.android.test` benchmark module targeting `:app`, baseline profile generation, and macrobenchmarks for cold startup, 1,000-song scrolling, search, and opening full player. Store the measured reference values in the release checklist; fail review when median startup or frame-duration P95 regresses more than 15% from the same-device reference.
- [ ] Run full e2e and benchmark on API 37 release-like build; expect green, then record server compatibility results in the matrix.
- [ ] Commit with `git commit -m "test: add ClearTune end-to-end quality gates"`.

### Task 6: Configure CI, release build, documentation, and licenses

**Files:**
- Create: `.github/workflows/android.yml`
- Create: `README.md`
- Create: `docs/testing/release-checklist.md`
- Modify: `app/proguard-rules.pro`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/com/cleartune/app/ReleaseMetadataTest.kt`

**Interfaces:** CI runs on JDK 17 and checks wrapper validation, lint, unit tests, debug build, API 26/37 managed-device tests, and release bundle. Signing secrets are environment-provided and never committed.

- [ ] Write `ReleaseMetadataTest` to assert the production app label, semantic version fields, privacy/about text, and bundled dependency-license resource; document every intended CI command before creating its workflow or resources.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests "*.ReleaseMetadataTest"`; expect compilation or assertion failure because release metadata/resources do not exist.
- [ ] Configure CI caching without caching credentials/keystores, add deterministic test reports, release optimization/keep rules, dependency licenses screen, privacy notes, setup/build instructions, and a release checklist covering app identity/version/signing.
- [ ] Run `./gradlew.bat clean lintDebug testDebugUnitTest connectedDebugAndroidTest :app:analyzeReleaseR8Config :app:bundleRelease`; expect green, then address any non-failing warning that affects correctness/accessibility/security.
- [ ] Run placeholder, secret, cleartext, and broad-storage scans; inspect the release bundle manifest and mapping output.
- [ ] Commit with `git commit -m "release: prepare ClearTune Android app"`.

## Phase Exit Verification

- [ ] Execute every row in the master cross-phase acceptance matrix on API 26 phone, API 37 phone, and API 37 tablet.
- [ ] Test a 10,000-track seeded Room library for search/list responsiveness and a 1,000-item queue for reorder/restore correctness.
- [ ] Confirm clean install, upgrade from every committed Room schema fixture, backup/restore policy, app-data clear, and uninstall leave no externally scoped files.
- [ ] A human reviewer compares every primary screen against `docs/superpowers/specs/2026-08-04-cleartune-ui-design.md` and signs `docs/testing/release-checklist.md`.
