# ClearTune Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a launchable ClearTune app with pinned tooling, canonical domain types, a tested Room v1 schema, manual dependency injection, and the approved single-root Compose shell.

**Architecture:** Keep one app module while enforcing package boundaries. Domain types remain Android-free; Room types map explicitly to domain types; `AppContainer` owns process-wide dependencies; ViewModels receive narrow repositories through factories.

**Tech Stack:** AGP 9.3.0, Gradle 9.5.0, JDK 17, Kotlin via AGP built-in Kotlin, KSP 2.3.10, Compose UI 1.11.4, Material 3 1.4.0, Room 2.8.4, Navigation Compose 2.9.8, JUnit 4, AndroidX Test.

## Global Constraints

- Work from a clean branch and do not add feature behavior beyond schema, shell, and dependency seams.
- Use `com.cleartune.app` for namespace and application ID; set `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`.
- Database file is `cleartune.db`; schema version starts at 1; export schemas to `app/schemas` and commit them.
- IDs are UUID strings generated at repository boundaries. Locations have a unique `(sourceId, sourceKey)` pair.
- The app has no bottom navigation. The root scaffold reserves safe-area space for a future mini-player.

---

### Task 1: Scaffold the reproducible Android project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`, `gradlew.bat`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/cleartune/app/ClearTuneApplication.kt`
- Create: `app/src/main/java/com/cleartune/app/MainActivity.kt`
- Create: `app/src/test/java/com/cleartune/app/BuildContractTest.kt`

**Interfaces:** Application class is `ClearTuneApplication`; launcher activity is `MainActivity`; Java/Kotlin bytecode target is 17.

- [ ] Write a failing build-contract test that reads `BuildConfig` and asserts application ID and debug build type:

  ```kotlin
  @Test fun build_contract_is_stable() {
      assertEquals("com.cleartune.app", BuildConfig.APPLICATION_ID)
      assertEquals("debug", BuildConfig.BUILD_TYPE)
  }
  ```

- [ ] Run `./gradlew.bat testDebugUnitTest`; expect failure because no Gradle project exists.
- [ ] Create the wrapper and version catalog with the exact versions in the plan header. Add only required libraries: activity-compose, Compose UI/foundation/material3, lifecycle-runtime-compose/viewmodel-compose, navigation-compose, Room runtime/ktx/compiler, coroutines-android/test, and test libraries. Enable Compose, KSP, Room schema export, Java 17, and `buildConfig = true`.
- [ ] Add the application, manifest, edge-to-edge activity, string/app icon resources, and a temporary `Text("ClearTune")` root. Run `./gradlew.bat testDebugUnitTest assembleDebug`; expect both tasks to pass.
- [ ] Commit:

  ```powershell
  git add settings.gradle.kts build.gradle.kts gradle.properties gradle app gradlew gradlew.bat
  git commit -m "build: scaffold ClearTune Android app"
  ```

### Task 2: Define canonical domain models and invariants

**Files:**
- Create: `app/src/main/java/com/cleartune/app/domain/model/MusicSource.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/Track.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/TrackLocation.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/Album.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/Artist.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/Playlist.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/Download.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/model/Queue.kt`
- Create: `app/src/test/java/com/cleartune/app/domain/model/DomainInvariantTest.kt`

**Interfaces:**

```kotlin
enum class SourceType { LOCAL, WEBDAV }
enum class LocationType { LOCAL_URI, REMOTE_URL, DOWNLOADED_FILE }
enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, UPDATE_AVAILABLE, FAILED, CANCELED }
data class Track(val id: String, val title: String, val durationMs: Long?, val albumId: String?, val artworkRef: String?)
data class TrackLocation(val id: String, val trackId: String, val sourceId: String, val sourceKey: String, val type: LocationType, val uri: String, val available: Boolean)
```

- [ ] Write failing invariant tests for blank IDs/titles, negative durations, duplicate queue positions, invalid HTTP source opt-in, and a completed download without a final path.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests "*.DomainInvariantTest"`; expect compilation failure because the models do not exist.
- [ ] Implement immutable data classes, enums, and `require` checks. Model WebDAV settings with `allowCleartext: Boolean`; never model username/password in `MusicSource`.
- [ ] Re-run the focused test, then `./gradlew.bat testDebugUnitTest`; expect green.
- [ ] Commit:

  ```powershell
  git add app/src/main/java/com/cleartune/app/domain app/src/test/java/com/cleartune/app/domain
  git commit -m "feat: define ClearTune domain model"
  ```

### Task 3: Create the Room v1 schema and typed DAO surface

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/db/entity/*.kt`
- Create: `app/src/main/java/com/cleartune/app/data/db/dao/*.kt`
- Create: `app/src/main/java/com/cleartune/app/data/db/model/*.kt`
- Create: `app/src/main/java/com/cleartune/app/data/db/Converters.kt`
- Create: `app/src/main/java/com/cleartune/app/data/db/ClearTuneDatabase.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/db/ClearTuneDatabaseTest.kt`
- Create: `app/schemas/com.cleartune.app.data.db.ClearTuneDatabase/1.json`

**Interfaces:** Entities are `MusicSourceEntity`, `TrackEntity`, `TrackLocationEntity`, `AlbumEntity`, `ArtistEntity`, `TrackArtistCrossRef`, `PlaylistEntity`, `PlaylistTrackCrossRef`, `PlaybackHistoryEntity`, `DownloadEntity`, `PlaybackQueueEntity`, `PlaybackQueueItemEntity`, `PlaybackStateEntity`, and `SyncSessionEntity`. DAOs expose `Flow` for reads and `suspend` transactions for writes.

- [ ] Write failing in-memory database tests proving cascade behavior, unique `(sourceId, sourceKey)`, ordered playlist/queue queries, FTS title/album/artist lookup, and that credentials cannot be stored in the source table.
- [ ] Run `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cleartune.app.db.ClearTuneDatabaseTest`; expect compilation failure.
- [ ] Implement normalized entities, indices, relations, FTS4 table, DAOs, converters, and `@Database(version = 1, exportSchema = true)`. Add a database callback that creates the single local source exactly once.
- [ ] Re-run the focused instrumentation test and inspect the exported JSON for all tables/indices. Run `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest`; expect green.
- [ ] Commit:

  ```powershell
  git add app/src/main/java/com/cleartune/app/data/db app/src/androidTest/java/com/cleartune/app/db app/schemas
  git commit -m "feat: add Room library schema"
  ```

### Task 4: Add repository contracts and manual dependency injection

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/repository/LibraryRepository.kt`
- Create: `app/src/main/java/com/cleartune/app/data/repository/PlaylistRepository.kt`
- Create: `app/src/main/java/com/cleartune/app/data/repository/SourceRepository.kt`
- Create: `app/src/main/java/com/cleartune/app/data/repository/QueueRepository.kt`
- Create: `app/src/main/java/com/cleartune/app/data/repository/RoomLibraryRepository.kt`
- Create: `app/src/main/java/com/cleartune/app/di/AppContainer.kt`
- Modify: `app/src/main/java/com/cleartune/app/ClearTuneApplication.kt`
- Create: `app/src/test/java/com/cleartune/app/data/repository/RepositoryContractTest.kt`

**Interfaces:**

```kotlin
interface LibraryRepository {
    fun observeLibraryHome(): Flow<LibraryHome>
    fun observeSongs(query: SongQuery): Flow<List<TrackSummary>>
    fun observeAlbum(albumId: String): Flow<AlbumDetail?>
    fun search(query: String): Flow<SearchResults>
}
interface AppContainerContract {
    val libraryRepository: LibraryRepository
    val sourceRepository: SourceRepository
    val queueRepository: QueueRepository
}
```

- [ ] Write failing contract tests using fake DAOs: blank search returns empty results, deleted locations disappear from observable summaries, and deterministic sort keys place unknown metadata last.
- [ ] Run the focused unit test; expect compilation failure.
- [ ] Implement contracts, Room-backed projections, explicit entity/domain mappers, `AppContainer`, and a test container. `ClearTuneApplication` creates exactly one container and database.
- [ ] Run `./gradlew.bat testDebugUnitTest`; expect green and no Android framework dependency in domain tests.
- [ ] Commit:

  ```powershell
  git add app/src/main/java/com/cleartune/app/data/repository app/src/main/java/com/cleartune/app/di app/src/main/java/com/cleartune/app/ClearTuneApplication.kt app/src/test
  git commit -m "feat: add repositories and dependency container"
  ```

### Task 5: Implement theme, navigation shell, and empty Library root

**Files:**
- Create: `app/src/main/java/com/cleartune/app/ui/ClearTuneApp.kt`
- Create: `app/src/main/java/com/cleartune/app/ui/navigation/Destination.kt`
- Create: `app/src/main/java/com/cleartune/app/ui/navigation/ClearTuneNavHost.kt`
- Create: `app/src/main/java/com/cleartune/app/ui/theme/{Color.kt,Theme.kt,Type.kt,Dimensions.kt}`
- Create: `app/src/main/java/com/cleartune/app/ui/component/{AppTopBar.kt,MiniPlayer.kt,EmptyState.kt}`
- Create: `app/src/main/java/com/cleartune/app/ui/feature/library/{LibraryRoute.kt,LibraryScreen.kt,LibraryViewModel.kt}`
- Modify: `app/src/main/java/com/cleartune/app/MainActivity.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/LibraryShellTest.kt`

**Interfaces:** `Destination` is a sealed, serializable route set. `ClearTuneApp` owns one `NavHostController`, one adaptive root scaffold, and one future mini-player slot. Library category IDs are stable strings: `songs`, `albums`, `artists`, `playlists`, `folders`, `downloads`.

- [ ] Write failing Compose tests asserting the six category semantics, search/settings buttons, absence of bottom navigation, light/dark theme contrast tags, and content not obscured by system bars at 200% font scale.
- [ ] Run the focused instrumentation test; expect compilation failure.
- [ ] Implement neutral light/dark schemes with coral accent, Android typography, 48 dp targets, root scaffold, placeholder routes, and an empty-state Library screen. Do not show `MiniPlayer` when queue state is empty.
- [ ] Run `./gradlew.bat lintDebug testDebugUnitTest connectedDebugAndroidTest`; expect green. Manually inspect phone portrait and tablet landscape previews.
- [ ] Commit:

  ```powershell
  git add app/src/main/java/com/cleartune/app/ui app/src/main/java/com/cleartune/app/MainActivity.kt app/src/androidTest/java/com/cleartune/app/ui
  git commit -m "feat: build ClearTune application shell"
  ```

## Phase Exit Verification

- [ ] Run `./gradlew.bat clean lintDebug testDebugUnitTest connectedDebugAndroidTest assembleDebug` on JDK 17.
- [ ] Install on API 26 and API 37; confirm startup, edge-to-edge insets, light/dark switching, rotation, and process recreation.
- [ ] Run `rg -n "TODO|TBD|FIXME|NotImplementedError" app`; expect no output.
- [ ] Confirm Room types and domain types use identical enum names and nullable rules; add mapper round-trip tests for any mismatch found.
