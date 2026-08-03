# ClearTune Android 音乐播放器设计

日期：2026-08-03

## 1. 背景与目标

ClearTune 是一个面向个人使用并开源的原生 Android 音乐播放器。首版聚焦两个音乐来源：设备本地音乐和 WebDAV 音乐库。产品借鉴 MusicFree 将播放器能力与音乐来源解耦的思路，但不复用其 React Native/AGPL 代码。

首版必须提供：

- 本地音乐扫描、分类、搜索和播放；
- WebDAV 服务器配置、目录浏览、媒体库同步和在线播放；
- 歌曲、专辑、歌手和文件夹视图；
- 播放队列、收藏、自建歌单和最近播放；
- 同目录同名 LRC 歌词；
- WebDAV 歌曲手动下载及离线播放；
- 锁屏、耳机按键和后台播放；
- 播放队列及进度恢复；
- Android 8.0（API 26）及以上版本支持。

## 2. 非目标

首版不包含 SMB、Jellyfin、Navidrome、在线音源插件、均衡器、跨曲淡入淡出、标签编辑、Android Auto、投屏或跨设备同步。架构允许以后增加新的 `MusicSource` 实现，但不建设运行时脚本或动态插件系统。

## 3. 技术方向

- Kotlin 和 Kotlin Coroutines/Flow；
- Jetpack Compose、Material 3、单 Activity；
- ViewModel 和单向数据流；
- Media3 ExoPlayer、`MediaLibraryService`、`MediaLibrarySession`；
- Room 作为媒体库与用户数据的单一事实来源；
- MediaStore 读取设备音乐；
- WorkManager 执行持久化同步补全和离线下载任务；
- 支持 WebDAV 与 HTTP Range 的 HTTP 客户端；
- Android Keystore 管理 WebDAV 凭据加密密钥。

首版采用轻量模块化单体：只创建一个 `app` Gradle 模块，通过包和接口维持边界。某个区域显著增大时，再提取成独立 Gradle 模块。

## 4. 架构与代码边界

```text
app/
├─ navigation/          单 Activity 与 Compose 导航
├─ di/                  依赖注入配置
├─ core/
│  ├─ model/            领域模型
│  ├─ database/         Room、实体、DAO 与迁移
│  ├─ security/         凭据加密与安全存储
│  └─ common/           错误、日志和通用工具
├─ source/
│  ├─ local/            MediaStore 与本地文件
│  └─ webdav/           WebDAV 浏览、同步和流式读取
├─ data/repository/     数据协调与业务规则
├─ playback/            Media3、队列和后台播放
├─ download/            WorkManager 离线下载
└─ feature/
   ├─ library/
   ├─ player/
   ├─ playlist/
   ├─ webdav/
   ├─ downloads/
   └─ settings/
```

依赖方向为：

```text
Compose UI → ViewModel → Repository → Room / MusicSource
                         ↓
                  PlaybackController
                         ↓
               MediaLibraryService
```

边界规则：

- Compose UI 只读取不可变 UI 状态并发送用户意图，不直接访问数据库、MediaStore、WebDAV 或 ExoPlayer；
- ViewModel 只调用 Repository 或跨页面复用的用例；
- Repository 协调 Room 与数据源，并负责事务、一致性和错误映射；
- 每个 `MusicSource` 只理解一个来源，不依赖 UI 类型；
- ExoPlayer 只存在于播放服务中，Activity 通过 `MediaController` 与其通信；
- 页面展示的数据始终来自 Room 的 Flow，扫描和同步结果先写入 Room。

`MusicSource` 提供稳定的领域接口，至少覆盖：

- 测试来源可用性；
- 浏览文件夹；
- 扫描或同步媒体条目；
- 解析可播放位置；
- 获取封面候选和歌词；
- 报告来源能力，例如是否支持 Range、断点续传和 ETag。

## 5. 领域与持久化模型

### 5.1 核心实体

- `MusicSourceEntity`：来源 ID、类型、显示名称、根目录、启用状态、能力及最后同步结果。WebDAV 密码不存于该表；
- `TrackEntity`：稳定内部 ID、来源 ID、来源键、标题、时长、格式、音轨号、碟号、年份、流派、可用状态和元数据状态；
- `TrackLocationEntity`：歌曲的本地 MediaStore URI、WebDAV 相对路径或离线文件路径，以及大小、ETag、修改时间和完整性状态；
- `AlbumEntity`、`ArtistEntity`、`TrackArtistCrossRef`：专辑、歌手和多歌手关系；
- `PlaylistEntity`、`PlaylistTrackCrossRef`：自建歌单、收藏系统歌单及稳定排序；
- `DownloadEntity`：下载任务、进度、临时文件、目标文件、预期大小、ETag、状态及错误；
- `PlaybackQueueEntity`、`PlaybackQueueItemEntity`、`PlaybackStateEntity`：队列、当前索引、进度、循环模式、随机模式和更新时间；
- `SyncSessionEntity`：一次扫描的状态和已发现条目标记，用于防止失败扫描误删记录。

### 5.2 标识和重复项

来源内使用 `(sourceId, sourceKey)` 唯一定位歌曲：WebDAV 的 `sourceKey` 为规范化相对路径，本地来源使用 MediaStore 内容 URI。移动或重命名 WebDAV 文件在首版中视为删除旧条目并新增条目；不做昂贵的全文件指纹识别。

WebDAV 歌曲下载后只增加一个 `OFFLINE_COPY` 位置，不新增 `TrackEntity`。播放解析器因此可以在离线副本和远程流之间切换，而不会在媒体库产生重复歌曲。

## 6. 本地扫描

本地来源通过 MediaStore 查询音频，读取 URI、标题、歌手、专辑、时长、MIME 类型、大小和修改时间。Android 13 及以上请求 `READ_MEDIA_AUDIO`；更早版本仅请求适用的旧音频读取权限。应用不请求 `MANAGE_EXTERNAL_STORAGE`。

每次扫描与 Room 进行差异更新：

- 新文件插入；
- 已有文件的来源元数据变化时更新；
- 本次成功扫描未发现的文件标记为不可用；
- 收藏和歌单关系继续保留；
- 文件重新出现时恢复可用状态。

扫描在后台调度，但结果分批写入 Room，使 UI 可以持续展示已有内容和同步进度。

## 7. WebDAV 浏览与同步

### 7.1 连接配置

每个配置包含服务器基址、用户名、加密密码、根目录和显示名称。首版允许多个服务器配置，每个配置选择一个音乐根目录。支持 HTTPS 上的 Basic 和 Digest 鉴权；明文 HTTP 必须由用户在配置页显式开启，并持续显示不安全提示。

“测试连接”验证网络、TLS、鉴权、根目录和基本 WebDAV 方法支持，但不会写入媒体库。

### 7.2 两阶段同步

第一阶段逐层执行 `PROPFIND Depth: 1`，收集规范化路径、资源类型、大小、修改时间和 ETag。避免依赖支持不一致且代价较高的无限深度请求。扫描仅识别首版兼容测试范围内的音频扩展和同目录资源文件。

第二阶段只对新增或发生变化的音频进行低并发元数据补全。远程读取器使用 HTTP Range 和小型块缓存，为标签解析器提供随机读取，避免下载整首歌曲。如果服务器不支持 Range，则先从文件名和目录结构生成临时标题、专辑和歌手，在首次播放或完成离线下载后补全标签。

同步使用扫描会话：只有整个目录遍历成功，才将本次未出现的旧条目标记为不可用。网络中断、鉴权失败、解析错误或用户取消都不会删除已有媒体库。

### 7.3 歌词和封面

歌词优先查找同目录、同基础文件名的 `.lrc`。文本依次尝试 BOM/UTF-8 和 GB18030 解码；解析失败时保留原文件信息并显示可重试错误。

封面依次采用：

1. 音频内嵌封面；
2. 同目录 `cover.jpg`；
3. 同目录 `folder.jpg`；
4. 应用默认封面。

远程封面使用独立的小容量图片缓存，不与音频缓存混用。

## 8. 播放与队列

`PlaybackService` 继承 `MediaLibraryService`，独占 ExoPlayer、`MediaLibrarySession` 和当前队列。系统媒体控件、锁屏、耳机按键和 Activity 中的 `MediaController` 都通过同一会话控制播放。

`PlaybackResolver` 按以下优先级解析歌曲：

1. 完整且校验通过的离线副本；
2. 可用的 MediaStore URI；
3. WebDAV 远程流。

WebDAV 凭据不拼接到 URL，也不放进 `MediaItem` 的可序列化元数据。自定义 HTTP 数据源根据内部 `sourceId` 临时取得认证信息并添加请求头。

首版支持顺序播放、单曲循环、列表循环和随机播放。随机播放顺序在同一播放会话中保持稳定。首版不提供均衡器、跨曲淡入淡出或自定义音频 DSP。

队列在切歌、暂停、队列变化和应用退到后台时立即持久化；播放过程中低频保存进度。服务或应用被系统重建后恢复队列、索引、进度和播放模式，但保持暂停，避免意外发声。

播放失败按范围处理：单个本地文件丢失或远程返回 404 时，标记该位置不可用并尝试下一首；全局网络、TLS 或鉴权失败时暂停队列，避免连续跳过所有远程歌曲。

## 9. 临时缓存与离线下载

### 9.1 临时流缓存

在线播放使用 Media3 `SimpleCache`，默认上限 512 MB，采用 LRU 淘汰。用户可以查看占用并清空缓存。缓存只用于降低重复流量，不承诺离线可用；界面不得把“已缓存”显示成“已下载”。

### 9.2 用户下载

手动下载由 WorkManager 执行，文件保存到应用专属外部目录：

- 下载中使用 `.part` 文件；
- 支持 Range 的服务器允许断点续传；
- 完成后校验预期大小，再原子改名；
- Room 事务同时更新 `DownloadEntity` 与 `TrackLocationEntity`；
- 支持取消、手动重试、删除和仅 Wi-Fi 下载；
- 鉴权错误不自动重试，暂时性网络错误采用退避重试；
- 卸载应用时离线副本随应用删除。

若远程 ETag 或文件大小发生变化，旧离线副本仍可播放，但显示“远程文件已有更新”，由用户决定是否重新下载。

## 10. 用户界面

应用采用 Material 3 与底部导航：

- 媒体库：歌曲、专辑、歌手、文件夹；
- 歌单：收藏、自建歌单、最近播放；
- 下载：进行中、已完成、失败任务；
- 设置：本地扫描、WebDAV 配置、缓存、主题和播放设置。

有可播放内容时，底部导航上方常驻迷你播放器。完整播放页包含封面、标题、歌手、进度、播放控制、播放模式和收藏操作，并可切换歌词与播放队列。

每个主要页面采用加载、内容、空状态和错误四态。后台同步错误使用非阻塞提示并保留已有内容；需要用户操作的鉴权、权限或存储错误提供明确入口。

## 11. 安全与隐私

- Android Keystore 中的不可导出密钥用于加密 WebDAV 密码；
- 加密凭据与密钥相关数据排除在自动备份之外，避免恢复到新设备后无法解密；
- 密码、Authorization 头和带敏感查询参数的 URL 不进入日志、Room 或崩溃报告；
- TLS 证书异常默认拒绝连接，不提供“信任所有证书”；用户安装到系统信任库的私有 CA 可以正常使用；
- 删除 WebDAV 配置时同步删除对应凭据；媒体记录按用户确认选择删除或保留为不可用；
- 所有应用数据默认仅保存在本机，不建设账号、遥测或云端服务。

## 12. 错误模型

底层异常统一映射为稳定的领域错误：`PermissionDenied`、`AuthenticationFailed`、`TlsFailure`、`NetworkUnavailable`、`Timeout`、`ServerFailure`、`NotFound`、`UnsupportedCapability`、`StorageFull`、`CorruptMedia` 和 `Unknown`。

错误对象包含安全的用户消息、可重试性、影响范围和内部原因；不得包含密码、认证头或完整敏感 URL。Repository 决定保留旧数据、重试、暂停还是标记单个位置不可用，UI 只根据领域错误展示操作。

## 13. 测试策略

### 13.1 单元测试

- WebDAV URL 与路径规范化；
- 扫描差异、同步会话和失败不删除规则；
- LRC 解析与 UTF-8/GB18030 解码；
- 队列增删、循环和稳定随机顺序；
- 播放位置优先级与远程更新判断；
- 错误映射和日志脱敏。

### 13.2 数据库与集成测试

- Room 关系查询、事务、级联行为和版本迁移；
- 模拟 WebDAV 服务器覆盖 `PROPFIND`、Basic/Digest、Range、ETag、404、超时和中断；
- 断点续传、取消、空间不足、完成校验和原子改名；
- 中断同步不误删已有歌曲。

### 13.3 Android 与 UI 测试

- 本地音频权限允许和拒绝路径；
- 本地、远程和离线位置切换；
- 锁屏、后台、耳机事件和播放服务重建；
- Compose 空状态、错误状态、连接测试、下载进度和队列操作。

## 14. 首版验收标准

- 用户授予音频权限后能够扫描、分类、搜索并播放本地音乐；
- 用户能够配置 WebDAV、测试连接、选择根目录、同步并在线播放歌曲；
- 同步进度可见，取消或失败不会删除已有媒体记录；
- 手动下载成功的歌曲在飞行模式下可以完整播放；
- 锁屏、切后台和销毁 Activity 不会中断正在进行的播放；
- 应用或播放服务重建后恢复队列与进度，但不会自动播放；
- WebDAV 密码不以明文进入数据库、URL、日志或备份；
- MP3、FLAC、M4A/AAC、Ogg/Opus 和 WAV 纳入兼容测试；实际解码能力受 Android 设备可用解码器约束；
- 首版范围可由一份实现计划覆盖，不包含第 2 节列出的后续功能。

## 15. 参考资料

- [Android 应用架构指南](https://developer.android.com/topic/architecture)
- [Media3 后台播放与 MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [Media3 基础播放器与 MediaLibraryService](https://developer.android.com/media/implement/playback-app)
- [Room 持久化](https://developer.android.com/training/data-storage/room)
- [WorkManager](https://developer.android.com/reference/androidx/work/WorkManager)
- [MediaStore 共享音频访问](https://developer.android.com/training/data-storage/shared/media)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [MusicFree 项目](https://github.com/maotoumao/MusicFree)
