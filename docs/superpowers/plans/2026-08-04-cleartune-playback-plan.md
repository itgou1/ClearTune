# ClearTune Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver resilient local/remote/background playback with deterministic location fallback, a persistent queue, bounded streaming cache, system media controls, mini-player, full player, lyrics, and queue UI.

**Architecture:** `ClearTunePlaybackService` owns Media3 ExoPlayer and `MediaLibrarySession`. UI talks only to `PlaybackController`; service resolves track IDs through Room and builds `MediaItem`s from the preferred available location. Queue state is mirrored transactionally to Room and restored on service creation.

**Tech Stack:** Media3 1.10.1 ExoPlayer/session/common/datasource/OkHttp, Room, OkHttp, Compose, coroutines/Flow, AndroidX Media test utilities.

## Global Constraints

- Resolution priority is completed download, valid local content URI, remote WebDAV URL.
- WebDAV credentials are injected as request headers at playback time and never placed in `MediaItem.mediaUri`, extras, logs, notifications, or queue rows.
- Streaming cache is a separate 512 MiB LRU `SimpleCache`; manual downloads never use it as final storage.
- Audio focus, noisy intent, headset/Bluetooth controls, notification controls, and process recovery are required.
- Supported playback formats remain MP3, FLAC, M4A/AAC, Ogg/Opus, and WAV.

---

### Task 1: Resolve playable locations deterministically

**Files:**
- Create: `app/src/main/java/com/cleartune/app/domain/playback/LocationResolver.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/playback/PlaybackFailure.kt`
- Create: `app/src/test/java/com/cleartune/app/domain/playback/LocationResolverTest.kt`

**Interfaces:** `resolve(trackId, locations, fileExists, uriReadable, networkAvailable): Resolution`; resolution contains an ordered attempt list and user-safe failure reason.

- [ ] Write failing cases for all three location types, stale completed download, revoked local URI, offline remote-only track, multiple sources, and fallback after one attempt fails.
- [ ] Run the focused test; expect compilation failure.
- [ ] Implement stable ordering with availability checks injected as functions; never mutate Room from the pure resolver.
- [ ] Re-run focused and full unit suites.
- [ ] Commit with `git commit -m "feat: resolve playback locations"`.

### Task 2: Build secure MediaItems and data sources

**Files:**
- Create: `app/src/main/java/com/cleartune/app/playback/MediaItemFactory.kt`
- Create: `app/src/main/java/com/cleartune/app/playback/WebDavDataSourceFactory.kt`
- Create: `app/src/main/java/com/cleartune/app/playback/PlayerCache.kt`
- Modify: `app/src/main/java/com/cleartune/app/di/AppContainer.kt`
- Create: `app/src/test/java/com/cleartune/app/playback/MediaItemFactoryTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/playback/PlayerCacheTest.kt`

**Interfaces:** media ID equals track UUID; metadata carries title/artist/album/artwork only; the chosen location is represented in a service-private tag. `PlayerCache` owns one 512 MiB `LeastRecentlyUsedCacheEvictor` instance.

- [ ] Write failing tests proving URI selection, MIME hints, metadata mapping, cache key stability, credential absence from serialized `MediaItem`, and separate cache/download directories.
- [ ] Run focused tests; expect red.
- [ ] Implement `DefaultDataSource.Factory` for content/file and credential-aware OkHttp data source for remote requests. Add cache read/write around remote streams only and release `SimpleCache` at process shutdown.
- [ ] Re-run tests and scan test failure output for credential fixture.
- [ ] Commit with `git commit -m "feat: create secure playback data sources"`.

### Task 3: Persist and restore queue state

**Files:**
- Create: `app/src/main/java/com/cleartune/app/playback/QueueSynchronizer.kt`
- Extend: `app/src/main/java/com/cleartune/app/data/repository/QueueRepository.kt`
- Create: `app/src/test/java/com/cleartune/app/playback/QueueSynchronizerTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/playback/QueuePersistenceTest.kt`

**Interfaces:** persist ordered track IDs, current index, position, playWhenReady, repeat mode, shuffle enabled, and shuffle order; debounce position writes to 5 seconds and always flush on pause/stop/task removal.

- [ ] Write failing tests for replace/add-next/add-last/remove/reorder, duplicate track occurrence IDs, shuffle restoration, removed current track, unavailable current track advancing, and position clamping after duration change.
- [ ] Run focused tests; expect red.
- [ ] Implement queue commands as a pure reducer plus a transactional synchronizer. Use queue-item UUIDs so duplicate tracks can be reordered independently.
- [ ] Re-run tests including kill/recreate instrumentation fixture.
- [ ] Commit with `git commit -m "feat: persist playback queue"`.

### Task 4: Implement MediaLibraryService and system integration

**Files:**
- Create: `app/src/main/java/com/cleartune/app/playback/ClearTunePlaybackService.kt`
- Create: `app/src/main/java/com/cleartune/app/playback/LibrarySessionCallback.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/androidTest/java/com/cleartune/app/playback/PlaybackServiceTest.kt`

**Interfaces:** root library exposes categories and playable track children backed by Room; custom commands include `ADD_NEXT`, `ADD_LAST`, and `TOGGLE_FAVORITE` only if implemented in schema. Service notification opens `MainActivity` and never displays sensitive URLs.

- [ ] Write failing service tests for connection, browse root, play by media ID, audio focus pause/duck, becoming noisy pause, notification metadata/actions, remote failure fallback, and service recreation.
- [ ] Run focused instrumentation; expect red.
- [ ] Implement ExoPlayer, audio attributes, session callback, foreground service declarations/permissions, error mapping, queue synchronizer, and library browse callbacks. Record playback history only after 30 seconds or 50% played, whichever comes first.
- [ ] Re-run service tests on API 26 and 37; inspect notification and lock-screen controls.
- [ ] Commit with `git commit -m "feat: add background media playback service"`.

### Task 5: Connect UI through a lifecycle-safe controller

**Files:**
- Create: `app/src/main/java/com/cleartune/app/playback/PlaybackController.kt`
- Create: `app/src/main/java/com/cleartune/app/domain/playback/{PlaybackCommand.kt,PlaybackState.kt}`
- Modify: `app/src/main/java/com/cleartune/app/di/AppContainer.kt`
- Create: `app/src/test/java/com/cleartune/app/playback/PlaybackControllerTest.kt`

**Interfaces:** controller exposes `StateFlow<PlaybackState>` and suspending/queued commands; it owns one `MediaController` future, reconnects after service loss, and is released by application scope.

- [ ] Write failing fake-session tests for connect/disconnect, command ordering before connection, state mapping, seek clamping, repeat/shuffle, and sanitized playback errors.
- [ ] Run focused tests; expect red.
- [ ] Implement controller and callback-to-Flow bridge; expose no Media3 types to feature ViewModels.
- [ ] Re-run tests and verify one controller instance survives Activity recreation without leaking Activity context.
- [ ] Commit with `git commit -m "feat: expose playback controller state"`.

### Task 6: Build mini-player, full player, lyrics, and queue UI

**Files:**
- Modify: `app/src/main/java/com/cleartune/app/ui/component/MiniPlayer.kt`
- Create/modify: `app/src/main/java/com/cleartune/app/ui/feature/{player,queue}/**/*.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/ClearTuneApp.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/navigation/ClearTuneNavHost.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/PlaybackScreensTest.kt`

**Interfaces:** mini-player is fixed above bottom safe area and hidden for an empty queue; full player uses album-derived color only on that screen; lyrics accept embedded/static timed lines; queue reorder exposes both drag and TalkBack move actions.

- [ ] Write failing Compose tests for mini-player visibility/content/actions, full-player controls, scrub semantics, artwork fallback, dynamic-background contrast, no-lyrics state, timed-line focus, queue reorder/remove, loading, and recoverable failure.
- [ ] Run focused UI tests; expect red.
- [ ] Implement the approved layouts and motion. Disable decorative transitions when reduced motion is active; keep all touch targets at least 48 dp; announce track changes and queue moves.
- [ ] Re-run UI tests in light/dark, 200% font, phone portrait/landscape, tablet dual-pane, and reduced motion. Run full lint/unit/instrumentation suite.
- [ ] Commit with `git commit -m "feat: add playback and queue UI"`.

## Phase Exit Verification

- [ ] Play every supported format from local URI and WebDAV; verify seek, next/previous, repeat, shuffle, headset controls, Bluetooth metadata, audio interruption, and network loss.
- [ ] Kill UI process while playing, relaunch, and verify queue/current item/position and notification remain coherent.
- [ ] Fill streaming cache beyond 512 MiB and verify LRU eviction does not touch manual downloads.
- [ ] Run `rg -n "username|password|Authorization|https?://" app/src/main/java/com/cleartune/app/playback`; manually approve only code identifiers or sanitized policy checks, never literal secrets/logging.
