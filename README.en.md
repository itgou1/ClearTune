# ClearTune

English | [简体中文](README.md)

ClearTune is a native Android music client for Navidrome and OpenSubsonic servers. It focuses on a clear, lightweight, everyday listening experience for privately hosted music libraries. The app connects directly to a server chosen by the user; it does not provide a ClearTune cloud account or embed server addresses or credentials.

Current version: `1.0.0-rc1`

## Features

- Home recommendations: recently added, rediscovery, frequent picks, and music discovery
- Music library: browse albums, artists, and songs, with library-wide search
- Navidrome synchronization: favorites, playlists, and playback activity
- Player: queue management, sequential/shuffle/single-repeat modes, lyrics, and track details
- Offline support: download tasks, pause/resume, offline playback, and cache management
- Audio controls: approachable equalizer presets, custom adjustment, and ReplayGain volume normalization
- Appearance: Material 3, light/dark/system themes, and edge-to-edge system bars
- Updates: optional checks against GitHub Releases

## Architecture

- Kotlin, Jetpack Compose, and Material 3
- Media3 / ExoPlayer with `MediaLibraryService`
- Retrofit, OkHttp, and kotlinx.serialization
- Room, DataStore, and WorkManager
- Hilt, Coroutines, and Flow
- Navidrome / OpenSubsonic API

ClearTune is an independently implemented Android project. Tempo was used for product evaluation, protocol behavior, and compatibility testing, but ClearTune is not a Tempo code fork and does not copy its application source code.

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
- [Tempo](https://github.com/CappielloAntonio/tempo)
