# ClearTune Parallel Baseline and Worktrees Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a compile-ready shared Android multi-module baseline, freeze cross-team contracts, enforce module ownership, and provision three isolated employee branches and worktrees from the same baseline commit.

**Architecture:** One production `:app` module assembles small Android library modules. Stable models, contracts, design tokens, and test fakes live in frozen core modules; each employee exclusively owns a disjoint group of implementation and feature modules. Git worktrees share repository objects and dependency caches while keeping working files and build output isolated.

**Tech Stack:** Git worktrees, PowerShell validation scripts, Gradle 9.5.0, AGP 9.3.0 built-in Kotlin, Kotlin/Compose compiler plugin 2.3.21, JDK 17, compile/target API 37, min API 26, KSP 2.3.10, Compose BOM 2026.06.00, Room, Media3, WorkManager, OkHttp, JUnit 4.

## Global Constraints

- Source of truth: `docs/superpowers/specs/2026-08-04-cleartune-three-branch-development-design.md`.
- Branches are `codex/employee-1-local-library`, `codex/employee-2-webdav-offline`, and `codex/employee-3-playback-product`.
- Worktrees are `.worktrees/employee-1-local-library`, `.worktrees/employee-2-webdav-offline`, and `.worktrees/employee-3-playback-product`.
- All three branches start from one committed baseline SHA; never create them from an uncommitted worktree.
- Frozen paths are root Gradle files, `core/model`, `core/contracts`, `core/designsystem`, and `core/testing`.
- Use AGP built-in Kotlin; do not apply `org.jetbrains.kotlin.android` or `kotlin-android`.
- Do not claim Android compilation succeeds until JDK 17, Android SDK Platform 37, and Build Tools 36.0.0 or compatible installed tools have run the build.
- Each implementation task ends in a separate commit before the three employee branches are created.

---

## Target File Structure

```text
ClearTune/
├── .gitignore
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── scripts/
│   ├── verify-project-layout.ps1
│   └── verify-branch-ownership.ps1
├── docs/development/
│   ├── branch-ownership.md
│   └── employee-startup.md
├── app/
├── core/{model,contracts,designsystem,testing,database,network}/
├── data/{local,webdav,download}/
├── playback/
└── feature/{library,sources,downloads,player,playlists,settings}/
```

Every module contains `build.gradle.kts`, `src/main/AndroidManifest.xml`, and a namespace-matching Kotlin package. Test-bearing modules also contain `src/test/java/...`.

### Dependency Graph

```text
core:model
   ├── core:contracts ── core:testing
   ├── core:designsystem
   ├── core:database
   ├── core:network
   └── feature modules
core:contracts
   ├── core:database
   ├── core:network
   ├── data:local
   ├── data:webdav ── core:network
   ├── data:download ── core:network
   ├── playback
   └── feature modules
app ── all implementation and feature modules
```

No feature module depends on another feature module. `core:database` does not depend on data/feature/playback modules. `playback` does not depend on Room or feature modules.

---

### Task 1: Add root build and structural verification

**Files:**
- Create: `.gitignore`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `scripts/verify-project-layout.ps1`

**Interfaces:**
- Consumes: module names from the approved design.
- Produces: `Verify-ProjectLayout` behavior expressed by a script exit code: `0` only when all required root files/modules exist and forbidden Kotlin Android plugin text is absent.

- [ ] **Step 1: Write the failing layout verifier**

  The script defines the exact modules and fails with one line per missing path:

  ```powershell
  $requiredModules = @(
    'app','core/model','core/contracts','core/designsystem','core/testing',
    'core/database','core/network','data/local','data/webdav','data/download',
    'playback','feature/library','feature/sources','feature/downloads',
    'feature/player','feature/playlists','feature/settings'
  )
  $missing = foreach ($module in $requiredModules) {
    if (-not (Test-Path -LiteralPath "$module/build.gradle.kts")) { "$module/build.gradle.kts" }
  }
  if ($missing) { $missing | ForEach-Object { Write-Error "Missing: $_" }; exit 1 }
  if (rg -n 'org\.jetbrains\.kotlin\.android|kotlin-android' -g '*.gradle.kts' .) {
    Write-Error 'AGP built-in Kotlin violation'; exit 1
  }
  ```

- [ ] **Step 2: Run the verifier and observe failure**

  Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-project-layout.ps1`

  Expected: exit 1 listing every absent module build file.

- [ ] **Step 3: Add deterministic root build configuration**

  `settings.gradle.kts` declares `google()`, `mavenCentral()`, `gradlePluginPortal()`, dependency repository mode, root name `ClearTune`, and all 17 modules. The version catalog pins AGP `9.3.0`, Kotlin/Compose compiler plugin `2.3.21`, KSP `2.3.10`, Compose BOM `2026.06.00`, Activity `1.13.0`, Lifecycle `2.11.0`, Navigation `2.9.8`, Room `2.8.4`, Media3 `1.10.1`, WorkManager `2.11.2`, OkHttp `5.3.0`, Coil `3.5.0`, coroutines `1.10.2`, and JUnit `4.13.2`. Apply `org.jetbrains.kotlin.plugin.compose` only in Compose modules; never apply `org.jetbrains.kotlin.android`. `gradle.properties` enables AndroidX, configuration cache, parallel execution, and built-in Kotlin. `.gitignore` contains exactly the project-local entries `.gradle/`, `**/build/`, `local.properties`, `.idea/`, `*.iml`, and `.worktrees/`.

- [ ] **Step 4: Add wrapper and run root-level checks**

  Generate or copy the standard Gradle 9.5.0 wrapper scripts/JAR without editing generated script contents. Run the structural script again; it still fails only for absent module files. If JDK 17 is available, run `.\gradlew.bat --version` and require Gradle 9.5.0/JVM 17; otherwise record `TOOLCHAIN_BLOCKED: JDK 17 unavailable` in the execution report without claiming the wrapper ran.

- [ ] **Step 5: Commit**

  ```powershell
  git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat scripts/verify-project-layout.ps1
  git commit -m "build: add ClearTune multi-module root"
  ```

### Task 2: Create all Android modules with legal one-way dependencies

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `core/*/build.gradle.kts`, `core/*/src/main/AndroidManifest.xml`
- Create: `data/*/build.gradle.kts`, `data/*/src/main/AndroidManifest.xml`
- Create: `playback/build.gradle.kts`, `playback/src/main/AndroidManifest.xml`
- Create: `feature/*/build.gradle.kts`, `feature/*/src/main/AndroidManifest.xml`
- Modify: `scripts/verify-project-layout.ps1`

**Interfaces:**
- Consumes: version catalog aliases and module graph above.
- Produces: Android namespaces `com.cleartune.app`, `com.cleartune.core.<name>`, `com.cleartune.data.<name>`, `com.cleartune.playback`, and `com.cleartune.feature.<name>`.

- [ ] **Step 1: Extend the verifier with dependency-boundary assertions**

  Add a map of allowed project dependencies and parse every module build file. The test must reject feature-to-feature, playback-to-database, and data-to-feature edges:

  ```powershell
  $forbidden = @(
    @{ File='playback/build.gradle.kts'; Pattern='projects\.core\.database' },
    @{ File='core/database/build.gradle.kts'; Pattern='projects\.(data|feature|playback)' },
    @{ File='feature/*/build.gradle.kts'; Pattern='projects\.feature\.' }
  )
  ```

- [ ] **Step 2: Run the verifier and observe failure**

  Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-project-layout.ps1`

  Expected: exit 1 because module build files and manifests do not exist.

- [ ] **Step 3: Create the minimal module build files**

  Every library applies only `com.android.library`, sets namespace, `compileSdk = 37`, `minSdk = 26`, Java 17, and unit-test Android resources. Compose feature/design-system modules enable Compose and depend on Compose UI/foundation/Material3. Implementation modules declare only the edges in the dependency graph. `:app` applies `com.android.application`, sets application ID `com.cleartune.app`, API levels 26/37, version `1/1.0.0`, Java 17, and depends on all implementation/feature modules.

- [ ] **Step 4: Verify structure and build configuration**

  Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-project-layout.ps1`

  Expected: PASS. When the Android toolchain is available, also run `.\gradlew.bat projects` and `.\gradlew.bat :app:assembleDebug`; expected PASS. Otherwise retain the explicit toolchain-blocked result.

- [ ] **Step 5: Commit**

  ```powershell
  git add app core data playback feature scripts/verify-project-layout.ps1
  git commit -m "build: scaffold isolated Android modules"
  ```

### Task 3: Freeze domain models and cross-module contracts

**Files:**
- Create: `core/model/src/main/java/com/cleartune/core/model/Ids.kt`
- Create: `core/model/src/main/java/com/cleartune/core/model/LibraryModels.kt`
- Create: `core/model/src/main/java/com/cleartune/core/model/PlaybackModels.kt`
- Create: `core/model/src/main/java/com/cleartune/core/model/DownloadModels.kt`
- Create: `core/contracts/src/main/java/com/cleartune/core/contracts/LibraryContracts.kt`
- Create: `core/contracts/src/main/java/com/cleartune/core/contracts/SourceContracts.kt`
- Create: `core/contracts/src/main/java/com/cleartune/core/contracts/DownloadContracts.kt`
- Create: `core/contracts/src/main/java/com/cleartune/core/contracts/PlaybackContracts.kt`
- Create: `core/contracts/src/main/java/com/cleartune/core/contracts/ProductContracts.kt`
- Create: `core/contracts/src/test/java/com/cleartune/core/contracts/ContractShapeTest.kt`

**Interfaces:**
- Consumes: Kotlin/coroutines/Flow only plus `:core:model`.
- Produces: value IDs `TrackId`, `SourceId`, `LocationId`, `AlbumId`, `ArtistId`, `PlaylistId`, `DownloadId`, and `QueueItemId`; enums and data classes approved in the main implementation plan; repository/gateway interfaces named in the design spec.

- [ ] **Step 1: Write a failing contract-shape test**

  ```kotlin
  @Test fun contracts_are_narrow_and_android_free() {
      assertEquals(1, LibraryWriteGateway::class.java.methods.count { it.name == "applyLibraryMutation" })
      assertTrue(PlaybackLibraryRepository::class.java.methods.any { it.name == "getPlayableTrack" })
      assertFalse(LibraryRepository::class.java.methods.any { it.parameterTypes.any { type -> type.name.startsWith("android.") } })
  }
  ```

- [ ] **Step 2: Run the test and observe failure**

  Run: `.\gradlew.bat :core:contracts:testDebugUnitTest --tests "*.ContractShapeTest"`

  Expected: compilation failure because the contracts do not exist. If toolchain-blocked, confirm the files are still absent with `Test-Path` and record the blocked Gradle command.

- [ ] **Step 3: Implement the exact contract surface**

  Use these principal signatures:

  ```kotlin
  interface LibraryRepository {
      fun observeLibraryHome(): Flow<LibraryHome>
      fun observeSongs(query: SongQuery): Flow<List<TrackSummary>>
      fun search(query: String): Flow<SearchResults>
  }
  interface LibraryWriteGateway {
      suspend fun applyLibraryMutation(mutation: LibraryMutation): MutationResult
  }
  interface PlaybackLibraryRepository {
      suspend fun getPlayableTrack(trackId: TrackId): PlayableTrack?
  }
  interface SourceWriteGateway {
      suspend fun applySourceMutation(mutation: SourceMutation): MutationResult
  }
  interface SourceRepository {
      fun observeSources(): Flow<List<MusicSource>>
      suspend fun getSource(sourceId: SourceId): MusicSource?
  }
  interface DownloadRepository {
      fun observeDownloads(): Flow<List<DownloadSummary>>
      suspend fun dispatch(command: DownloadCommand)
  }
  interface QueueRepository {
      fun observeQueue(): Flow<QueueSnapshot>
      suspend fun apply(command: QueueCommand)
  }
  interface PlaylistRepository {
      fun observePlaylists(): Flow<List<PlaylistSummary>>
      suspend fun apply(command: PlaylistCommand)
  }
  interface SettingsRepository {
      val settings: Flow<AppSettings>
      suspend fun update(command: SettingsCommand)
  }
  data class WebDavCredential(val username: String, val password: CharArray)
  interface CredentialStore {
      suspend fun put(alias: CredentialAlias, credential: WebDavCredential)
      suspend fun get(alias: CredentialAlias): WebDavCredential?
      suspend fun delete(alias: CredentialAlias)
  }
  interface PlaybackGateway {
      val state: StateFlow<PlaybackState>
      suspend fun dispatch(command: PlaybackCommand)
  }
  ```

  Model credentials only as `CredentialAlias`; `WebDavCredential` remains confined to `CredentialStore`. No contract exposes Room, Media3, OkHttp, WorkManager, Android URI, or Compose types.

- [ ] **Step 4: Run focused and structural tests**

  Run: `.\gradlew.bat :core:model:testDebugUnitTest :core:contracts:testDebugUnitTest`

  Expected: PASS with `ContractShapeTest` green. Also run `rg -n "androidx\.room|androidx\.media3|okhttp3|androidx\.work|android\.net\.Uri|androidx\.compose" core/model core/contracts`; expected no matches.

- [ ] **Step 5: Commit**

  ```powershell
  git add core/model core/contracts
  git commit -m "feat: freeze shared domain contracts"
  ```

### Task 4: Add design system, shared fakes, and compile-safe feature entries

**Files:**
- Create: `core/designsystem/src/main/java/com/cleartune/core/designsystem/theme/*.kt`
- Create: `core/designsystem/src/main/java/com/cleartune/core/designsystem/component/ArtistAvatar.kt`
- Create: `core/designsystem/src/main/res/drawable/avatar_artist_01.xml` through `avatar_artist_08.xml`
- Create: `core/testing/src/main/java/com/cleartune/core/testing/FakeRepositories.kt`
- Create: `core/testing/src/main/java/com/cleartune/core/testing/TestFixtures.kt`
- Create: `core/testing/src/test/java/com/cleartune/core/testing/FakeContractTest.kt`
- Create: `feature/*/src/main/java/com/cleartune/feature/*/*FeatureEntry.kt`
- Create: `app/src/main/java/com/cleartune/app/ClearTuneApplication.kt`
- Create: `app/src/main/java/com/cleartune/app/MainActivity.kt`
- Create: `app/src/main/java/com/cleartune/app/BaselineApp.kt`
- Create: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: frozen core models/contracts.
- Produces: `LibraryFeatureEntry`, `SourcesFeatureEntry`, `DownloadsFeatureEntry`, `PlayerFeatureEntry`, `PlaylistsFeatureEntry`, and `SettingsFeatureEntry`. Each is an object with `const val route`, a feature-specific immutable dependencies class, and `@Composable fun Content(dependencies, onNavigate: (String) -> Unit)`. Dependencies are: Library=`LibraryRepository`, `PlaybackGateway`, `PlaylistRepository`; Sources=`SourceRepository`; Downloads=`DownloadRepository`, `PlaybackGateway`; Player=`PlaybackGateway`, `QueueRepository`; Playlists=`PlaylistRepository`, `PlaybackGateway`; Settings=`SettingsRepository`, `SourceRepository`, `DownloadRepository`.

- [ ] **Step 1: Write failing fake and avatar tests**

  ```kotlin
  @Test fun fake_library_emits_seeded_home() = runTest {
      val fake = FakeLibraryRepository(home = LibraryHome(songCount = 3))
      assertEquals(3, fake.observeLibraryHome().first().songCount)
  }

  @Test fun artist_avatar_is_stable() {
      assertEquals(artistAvatarIndex(ArtistId("artist-a")), artistAvatarIndex(ArtistId("artist-a")))
      assertTrue(artistAvatarIndex(ArtistId("artist-a")) in 0..7)
  }
  ```

- [ ] **Step 2: Run focused tests and observe failure**

  Run: `.\gradlew.bat :core:testing:testDebugUnitTest :core:designsystem:testDebugUnitTest`

  Expected: compilation failure because fakes/avatar selector do not exist.

- [ ] **Step 3: Implement shared assets, fakes, and feature entries**

  Create neutral light/dark theme with coral accent, stable `Math.floorMod(artistId.value.hashCode(), 8)` avatar selection, eight simple vector resources, in-memory Flow-backed fakes for every frozen repository, and six feature entries. Each baseline feature screen renders its module name plus `semantics { stateDescription = "baseline stub" }`; it performs no data or network work. `BaselineApp` lists the six entries without bottom navigation.

- [ ] **Step 4: Run tests and assemble the baseline**

  Run: `.\gradlew.bat :core:testing:testDebugUnitTest :core:designsystem:testDebugUnitTest :app:assembleDebug`

  Expected: PASS. Run the structural verifier; expected PASS. When toolchain-blocked, run the PowerShell verifier and record the exact Gradle command awaiting JDK/SDK.

- [ ] **Step 5: Commit**

  ```powershell
  git add core/designsystem core/testing feature app
  git commit -m "feat: add shared UI and test baseline"
  ```

### Task 5: Enforce branch ownership and document employee startup

**Files:**
- Create: `scripts/verify-branch-ownership.ps1`
- Create: `docs/development/branch-ownership.md`
- Create: `docs/development/employee-startup.md`
- Modify: `scripts/verify-project-layout.ps1`

**Interfaces:**
- Consumes: current branch, merge base, and changed paths from Git.
- Produces: exit `0` when every changed path belongs to that employee; exit `2` for an unknown branch; exit `1` with every violating path for ownership violations.

- [ ] **Step 1: Write fixture-driven ownership tests inside the script**

  Support `-Branch`, `-ChangedPath`, and `-SelfTest`. The self-test asserts:

  ```powershell
  Assert-Allowed 'codex/employee-1-local-library' 'core/database/build.gradle.kts' $true
  Assert-Allowed 'codex/employee-1-local-library' 'data/webdav/WebDavClient.kt' $false
  Assert-Allowed 'codex/employee-2-webdav-offline' 'feature/downloads/DownloadsRoute.kt' $true
  Assert-Allowed 'codex/employee-3-playback-product' 'app/src/main/AndroidManifest.xml' $true
  Assert-Allowed 'codex/employee-3-playback-product' 'core/contracts/LibraryContracts.kt' $false
  ```

- [ ] **Step 2: Run self-test and observe failure**

  Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-branch-ownership.ps1 -SelfTest`

  Expected: exit 1 before the branch ownership map is implemented.

- [ ] **Step 3: Implement exact ownership maps and docs**

  Employee 1 allowed prefixes: `core/database/`, `data/local/`, `feature/library/`. Employee 2: `core/network/`, `data/webdav/`, `data/download/`, `feature/sources/`, `feature/downloads/`. Employee 3: `playback/`, `feature/player/`, `feature/playlists/`, `feature/settings/`, `app/`. Deny root files and all frozen core paths for every employee branch. Documentation gives clone/worktree path, branch, focused build commands, ownership command, contract-change process, and integration order.

- [ ] **Step 4: Run policy and layout verification**

  Run:

  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts/verify-branch-ownership.ps1 -SelfTest
  powershell -ExecutionPolicy Bypass -File scripts/verify-project-layout.ps1
  ```

  Expected: both exit 0.

- [ ] **Step 5: Commit**

  ```powershell
  git add scripts docs/development
  git commit -m "docs: enforce parallel branch ownership"
  ```

### Task 6: Verify the baseline and create three isolated worktrees

**Files:**
- Modify: none after the baseline verification commit.
- Create through Git: `.worktrees/employee-1-local-library/`
- Create through Git: `.worktrees/employee-2-webdav-offline/`
- Create through Git: `.worktrees/employee-3-playback-product/`

**Interfaces:**
- Consumes: clean `main` at the baseline SHA.
- Produces: three branches/worktrees whose `HEAD` equals the recorded baseline SHA and whose ownership self-test passes.

- [ ] **Step 1: Verify ignore and clean baseline**

  Run:

  ```powershell
  git check-ignore -q .worktrees
  git status --short
  powershell -ExecutionPolicy Bypass -File scripts/verify-project-layout.ps1
  ```

  Expected: ignore check exit 0, empty status, layout verifier exit 0. Record `$baselineSha = git rev-parse HEAD`.

- [ ] **Step 2: Run available build gates before branching**

  If JDK 17/API 37 are installed, run:

  ```powershell
  .\gradlew.bat :core:model:testDebugUnitTest :core:contracts:testDebugUnitTest :core:testing:testDebugUnitTest :app:assembleDebug
  ```

  Expected: PASS. If unavailable, record the missing executable/SDK package and continue only with structural isolation; do not report build success.

- [ ] **Step 3: Create the branches/worktrees from the same SHA**

  ```powershell
  git worktree add .worktrees/employee-1-local-library -b codex/employee-1-local-library $baselineSha
  git worktree add .worktrees/employee-2-webdav-offline -b codex/employee-2-webdav-offline $baselineSha
  git worktree add .worktrees/employee-3-playback-product -b codex/employee-3-playback-product $baselineSha
  ```

- [ ] **Step 4: Verify isolation in every worktree**

  For each path, run `git branch --show-current`, `git rev-parse HEAD`, `git status --short`, the layout verifier, and `verify-branch-ownership.ps1 -SelfTest`. Expected: correct unique branch, identical baseline SHA, empty status, both scripts exit 0. Run `git worktree list --porcelain` and verify four entries: main plus three employee worktrees.

- [ ] **Step 5: Hand off exact commands**

  Report each absolute path, branch, baseline SHA, owned modules, focused build command, and current build-verification state. Do not create a commit after worktree creation because Task 5's commit is the shared baseline all branches must reference.

## Self-Review and Completion Gate

- [ ] Confirm every module in the design appears in `settings.gradle.kts`, the layout verifier, and one ownership category or frozen category.
- [ ] Search for `TODO`, `TBD`, `FIXME`, `NotImplementedError`, and unimplemented exceptions in baseline sources; expected no matches.
- [ ] Search root/module Gradle files for dynamic versions and `org.jetbrains.kotlin.android`; expected no matches.
- [ ] Confirm contract type names match the three-branch design and the main ClearTune implementation plan.
- [ ] Confirm all worktree HEADs equal the same baseline SHA and none contain uncommitted files.
