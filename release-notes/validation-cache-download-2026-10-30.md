# 缓存与下载调整验证记录

验证日期：2026-10-30。对应方案：[缓存与下载管理调整计划](../design/cache-management/plan.md)。

## 实现范围

- 缓存管理独立页面，七档等距滑动条、自定义容量、保存生效、缩容确认、未保存离开提示，以及播放缓存和图片缓存分别清理。
- 缓存由进程内单一实例管理，读取原有容量后初始化；正在使用的缓存延后清理，读取结束或切歌后继续执行缩容。设备空间不足时停止新增缓存写入，仍可读已有缓存和在线播放。
- 新下载发布到公共 `Music/ClearTune/`，保存内容 URI 和本机索引，用户可以跨账号或未登录管理、播放、分享、打开文件、移除记录或删除文件。
- 所有历史账号的完整私有下载可导出，复制前后校验 SHA-256，保存公共索引并更新旧记录后才移除原文件。迁移失败保留原文件，重复迁移复用已校验的公共副本；未完成文件单独展示用于清理。
- 播放先解析匹配的本机下载，再使用缓存数据源和服务器。匹配含服务器与账号身份，不按歌名匹配；公共文件不可用时回退，已知音频版本变化时保留原下载并提示更新。

## 自动化验证

| 检查 | 结果 |
| --- | --- |
| 应用单元测试 | 136 项通过 |
| 数据存储模块单元测试 | 13 项通过 |
| 播放器模块单元测试 | 32 项通过 |
| 公共下载与历史迁移设备测试 | 2 项通过 |
| 下载流水线设备测试 | 10 项通过 |
| 图片缓存与下载封面保护设备测试 | 2 项通过 |
| 离线缓存与数据源路由设备测试 | 13 项通过 |
| 缓存实例共享及延后缩容设备测试 | 1 项通过 |
| 缓存设置标准字号设备测试 | 通过 |
| 深色主题大字号设备测试 | 通过，字号放大至 1.6 倍，主要按钮可滚动到达并完整显示 |
| 调试 APK 构建 | 通过 |
| Android Lint | 通过，0 错误，75 条警告及 2 条提示 |
| Git 差异空白检查 | 通过 |

单元测试合计 181 项，设备测试合计 30 个不同用例，均无失败和跳过。设备测试使用单独创建的 `ClearTune_Storage_Test` 模拟器，系统为 Android 17，API 37.1。下载流水线连接测试进程中的本机音频服务器，覆盖实际 HTTP 下载、暂停续传、自动重试、登录会话变化、公开文件读取与断网播放。

关键断言包括：拖动和自定义输入不立即落盘；保存后配置正确；32 GB 字节换算不溢出；公共文件写入完成后解除待发布状态；只移除记录仍保留设备文件；迁移重试不重复导出；外部删除后索引可识别不可用；不同账号不会误关联同名歌曲；低空间停止缓存写入后可恢复；正在读取的缓存不会被缩容删除，读取结束后完成淘汰。

## 验证命令与证据

单元测试和构建使用 `:app:testDebugUnitTest`、`:core:datastore:testDebugUnitTest`、`:core:player:testDebugUnitTest` 和 `:app:assembleDebug`。

设备测试分别运行应用与播放器模块的 `connectedDebugAndroidTest`，以 `android.testInstrumentationRunnerArguments.class` 选择 `DeviceDownloadsTest`、`DownloadPipelineTest`、`ArtworkCacheTest`、`CacheManagementUiTest`、`OfflinePlaybackCacheTest` 和 `PlaybackCacheManagementTest`。静态检查使用 `:app:lintDebug`。

- 应用与单元测试日志：`build/cache-download-final-tests.log`。
- 播放器最终回归及静态检查日志：`build/cache-final-quality.log`。
- 界面最终复核日志：`build/cache-ui-final-tests.log`。
- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。
- [缓存管理实际界面](../design/cache-management/cache-management.png)。
- [深色主题大字号界面](../design/cache-management/cache-management-large-text.png)。两张截图均已进行视觉检查，滚动内容允许超出当前视口，控件和文字没有相互覆盖。

## 验证边界

Android 8、9 的公共目录权限和媒体扫描兼容路径已编译，但当前环境只有 Android 17 系统镜像，尚未进行旧系统或厂商真机验证。本轮未使用用户真实音乐库执行迁移，也未修改用户原模拟器的应用或数据。

下载目录选择、任意本地音乐库扫描和卸载重装后自动重建来源关联不在首版范围。已授权文件的重新定位会核对原下载内容校验值，不能通过选取同名或同大小文件误关联其他歌曲。
