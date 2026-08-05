# ClearTune

ClearTune 是一款原生 Android 纯音乐播放器，将设备本地音乐与 WebDAV 音乐库统一管理、播放并提供离线使用。

## 核心特性

- 扫描设备本地音乐，按歌曲、专辑、艺术家、文件夹等方式浏览，并支持搜索。
- 添加和管理多个 WebDAV 音乐源，支持在线串流与后台同步。
- 支持断点续传、整目录下载和离线播放。
- 提供歌单、收藏、歌词、播放队列及队列恢复能力。
- 布局与视觉层次受 Apple Music 启发，但不使用 Apple 商标或专有素材；整体仍采用简洁、以内容为中心的原生界面，方便在本地与云端音乐之间切换。

## 页面与体验

- 以资料库为首要入口，集中浏览本地音乐、WebDAV 来源和下载内容。
- 迷你播放器常驻于主要浏览流程中，可快速查看并控制当前播放。
- 全屏播放器提供专注的播放、队列与歌词体验。
- 支持浅色和深色主题。
- 为缺少封面或照片的艺术家提供稳定的默认头像，避免列表显示在刷新后跳变。

## 技术栈

- Kotlin
- Jetpack Compose 与 Material 3
- Media3
- Room
- WorkManager
- OkHttp
- Coil

## 项目结构

```text
ClearTune/
├── app/        # 应用入口、依赖组装和导航
├── core/       # model、contracts、designsystem、database、network、testing
├── data/       # local、webdav、download
├── playback/   # Media3 播放服务、队列和播放状态
└── feature/    # library、sources、downloads、player、playlists、settings
```

## 环境要求

- JDK 17
- Android SDK 37（`compileSdk` 与 `targetSdk` 均为 37）
- 仓库内置的 Gradle 9.5.0 Wrapper
- Android 8.0（API 26）及更高版本的设备或模拟器

## 构建与运行

在仓库根目录使用 Gradle Wrapper 构建 Debug APK。

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux：

```bash
./gradlew :app:assembleDebug
```

构建完成后，可将生成的 Debug APK 安装到 Android 8.0（API 26）或更高版本的设备或模拟器运行。

## 测试与验证

Windows：

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
```

macOS/Linux：

```bash
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
```

`test` 运行 JVM 单元测试；`assembleDebugAndroidTest` 只构建 Android 测试 APK。设备端自动化测试仍需要连接的真机或已启动的模拟器，构建测试 APK 不代表测试已在设备上执行。

## WebDAV 与安全

- 默认使用 HTTPS 连接 WebDAV 服务。
- 使用明文 HTTP 必须由用户显式允许；应用会持续提示相应的传输风险。
- 支持 Basic 和 Digest 身份验证。
- 凭据存储由 Android Keystore 支持的安全存储保护。

## 项目状态

本地音乐资料库、WebDAV 来源、在线播放与下载、歌单和播放体验等主要功能均已实现。请按上方命令在你的环境中完成构建和单元测试验证；Android 设备端自动化测试仍需连接真机或模拟器后另行执行。

## 参与贡献

欢迎通过 Issue 报告问题、提出建议，或提交 Pull Request 参与改进。
