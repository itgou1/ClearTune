# ClearTune

English | [简体中文](README.md)

ClearTune is a native Android music client for Navidrome and OpenSubsonic servers. It focuses on a clear, lightweight, everyday listening experience for privately hosted music libraries. The app connects directly to a server chosen by the user; it does not provide a ClearTune cloud account or embed server addresses or credentials.

Current version: `1.0.0`

<p align="center">
  <img src="screenshots/now-playing.png" alt="ClearTune Now Playing screen" width="360">
</p>

## Features

- Home recommendations: recently added, rediscovery, frequent picks, and music discovery
- Music library: browse albums, artists, songs, and folders, with library-wide search and format badges
- Songs and playlists: favorites, Play Next, add to playlist, bulk playlist removal, and two-way Navidrome sync
- Player: persistent mini player, drag-to-reorder queue, sequential/shuffle/repeat modes, synchronized lyrics, and track details
- Offline support: original-file downloads, pause/resume, duplicate detection, Wi-Fi waiting feedback, offline playback, and cache management
- Audio controls: 128/192/320 kbps or original quality on mobile data, equalizer presets, custom adjustment, and ReplayGain normalization
- Appearance: Material 3, screen transitions, artwork placeholders, light/dark/system themes, and edge-to-edge system bars
- Updates: optional GitHub Releases checks, 24-hour automatic-check throttling, and release notes

## Recent changes

- Expanded track actions with Play Next, add to playlist, download, favorite, and details shortcuts.
- Replaced queue move buttons with long-press drag reordering while retaining accessible move actions; lyrics and seek interactions were also refined.
- Added multi-select removal for playlist tracks and made album, artist, and playlist detail loading more resilient.
- Switched offline downloads to the OpenSubsonic original-file endpoint, with resume validation, clearer server errors, duplicate detection, and Wi-Fi waiting feedback.
- Added an original-quality mobile-data option alongside 128, 192, and 320 kbps transcoding choices.
- Improved compatibility with numeric music-folder IDs, servers without physical-folder browsing, artist synchronization, and artist-track matching.

## Architecture

- Kotlin, Jetpack Compose, and Material 3
- Media3 / ExoPlayer with `MediaLibraryService`
- Retrofit, OkHttp, and kotlinx.serialization
- Room, DataStore, and WorkManager
- Hilt, Coroutines, and Flow
- Navidrome / OpenSubsonic API

## Requirements

- Android Studio or a command-line Android SDK installation
- JDK 17
- Android SDK 37
- Windows, macOS, or Linux

The Gradle Wrapper pins the build tool version. Third-party dependencies are downloaded by Gradle from public repositories during the build and are not vendored in this repository.

## Build

```bash
git clone https://github.com/itgou1/ClearTune.git
cd ClearTune
```

Set `ANDROID_HOME` to your Android SDK, or create an untracked `local.properties` file:

```properties
sdk.dir=/path/to/Android/Sdk
```

macOS / Linux:

```bash
./gradlew :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Test

Run local unit tests:

```bash
./gradlew testDebugUnitTest
```

On Windows, the following command can start a visible Android emulator, build, install, and launch ClearTune:

```powershell
.\scripts\android-test.ps1
```

You can also double-click `start-android-test.bat`. The script never enters or stores music-server credentials.

### Release signing

Release builds never fall back to the debug certificate or produce an unsigned package. Provide these four Gradle properties or environment variables before building:

- `CLEARTUNE_RELEASE_STORE_FILE`
- `CLEARTUNE_RELEASE_STORE_PASSWORD`
- `CLEARTUNE_RELEASE_KEY_ALIAS`
- `CLEARTUNE_RELEASE_KEY_PASSWORD`

Never commit the key or its passwords. Run `./gradlew assembleRelease bundleRelease` after configuring them.

### Automated GitHub Releases

`.github/workflows/release.yml` runs tests and Lint for every pushed `v*` tag, signs the APK/AAB with the production certificate, creates `update.json` and SHA-256 files, and publishes a GitHub Release.

Configure these repository secrets under **Settings → Secrets and variables → Actions**:

- `CLEARTUNE_RELEASE_KEYSTORE_BASE64`: Base64 content of the production keystore
- `CLEARTUNE_RELEASE_STORE_PASSWORD`
- `CLEARTUNE_RELEASE_KEY_ALIAS`
- `CLEARTUNE_RELEASE_KEY_PASSWORD`

The tag must exactly match `versionName` in `app/build.gradle.kts`. For example:

```bash
git tag -a v1.0.0 -m "ClearTune 1.0.0"
git push origin v1.0.0
```

APK and AAB files stay out of Git history and are attached to GitHub Releases. The app checks the public `releases/latest` endpoint, treats `versionCode` in `update.json` as authoritative, and falls back to semantic tags for older releases without a manifest.

## Usage

1. Prepare an accessible Navidrome server or another OpenSubsonic-compatible server.
2. Start ClearTune and enter the server address, username, and password.
3. Once connected, ClearTune loads the music library, playlists, and favorite state from the server.

Credentials are protected with Android Keystore and stored locally on the device. ClearTune V1 contains no advertising, analytics SDK, or large-language-model integration.

## Project layout

```text
app/                 Android application and Compose UI
core/model/          Domain models and pure logic
core/network/        OpenSubsonic network protocol
core/database/       Room local database
core/datastore/      Settings and credential storage
core/player/         Media3 playback, equalizer, and normalization
core/designsystem/   Theme and design system
scripts/             Local Android test tooling
```

## License and trademarks

ClearTune source code is released under the [GNU General Public License v3.0](LICENSE). If you distribute an APK of this project or a modified version, you must provide the complete corresponding source to its recipients as required by GPL-3.0.

`ClearTune`, `轻调`, the headphone-wearing owl design, and the application icon are project marks. GPL-3.0 does not grant trademark rights to them. Modified versions should use a different name, icon, and package identifier, and clearly state that they are based on ClearTune. See the [Trademark Policy](TRADEMARKS.md).

## Related projects

- [Navidrome](https://www.navidrome.org/)
- [OpenSubsonic API](https://opensubsonic.netlify.app/)
