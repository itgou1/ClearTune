# 播放与升级验收（2026-09-17）

当前代码在隔离模拟器中的已测场景通过。真实运营商弱信号、实体蓝牙耳机、厂商系统长时间后台播放仍未验收，不能据此宣布五项真机场景全部通过。

## 环境与范围

- Pixel 8 模拟器，Android API 37，独立端口 5580，以 `-read-only -no-snapshot` 启动临时副本。未修改原始 AVD 的持久数据。
- 使用合成账号、本地测试服务器和静音 WAV 文件。实际运行应用的下载任务、数据库、播放服务和 ExoPlayer；未连接用户音乐服务器。
- 运行场景使用 Debug instrumentation；覆盖安装使用原始 v1.3.4 正式 APK 和当前代码构建的正式签名、R8 压缩 Release APK。
- 当前候选包仍为 `versionName=1.3.4 / versionCode=9`，未提升版本号、提交代码或发布。覆盖测试验证同签名替换后的数据保留，未覆盖应用内更新发现/下载流程。

## 五项结果

| 场景 | 结果 | 已验证内容及边界 |
| --- | --- | --- |
| 移动网络播放 → 无信号 → 恢复网络 | 部分通过 | 确认 CELLULAR 网络、请求 192 kbps；完整缓冲后断开数据网络和测试服务，缓存重播且进度增长；恢复网络后播放下一首。另测服务失败后恢复，点击播放可重试。未覆盖真实弱信号、长时间断网耗尽缓冲后的自动续播。 |
| 下载 → 关闭服务器 → 重启应用 | 通过（模拟器） | 实际下载 3 首；关闭测试服务器，结束应用进程，再启动独立测试进程。账号离线恢复，下载可播放和切歌，队列、保存进度、循环模式、深色主题和 128 kbps 设置恢复。 |
| 锁屏、切换应用、蓝牙 | 部分通过 | Home 进入后台、灭屏后，分别确认播放进度继续增长；唤醒回到应用后仍播放。仅短时测试，未验证密码锁屏、长时间后台和实体蓝牙连接/断开。 |
| 连续切歌、拖进度、改队列、回曲库 | 通过（已测操作） | 通过实际 ViewModel/播放服务连续切换 5 次，跳转 3 秒和 5 秒，队列移位、删除及撤销，点击音乐库后当前歌曲和队列一致。未覆盖手指拖动的完整手势轨迹或长时间压力测试。 |
| 旧正式版覆盖升级 | 通过（本地同签名覆盖） | 从保留的原始 v1.3.4 APK 创建真实下载和偏好，再 `adb install -r` 当前签名包，全程保留应用数据；服务器关闭后启动。3 首下载、3 首队列及顺序、当前第二首、19.823 秒暂停位置、循环模式、深色主题和 128 kbps 均保留；离线播放进度从 19.823 秒增长到 25.741 秒，下一首也成功播放。 |

## 验收中发现并修复的问题

网络请求失败后，播放器可能处于 `STATE_IDLE`，但 `playWhenReady` 仍为 true。原播放按钮此时调用暂停，未重新准备音频，恢复网络后点击播放仍不能恢复。

修改 `core/player/src/main/java/com/cleartune/core/player/PlayerConnection.kt`：存在播放错误或处于 IDLE 时，非空队列通过 `prepare()` + `play()` 重试；点击队列歌曲也补充准备。该问题先在真实播放服务测试中复现，修复后同一测试通过。

## 可复用测试与证据

新增 `app/src/androidTest/java/com/cleartune/app/player/PlaybackAcceptanceTest.kt`：

- 4 项运行场景通过，日志 `outputs/acceptance-20260917/runtime-retest.txt`。
- 冷启动分为 `prepareDownloadForColdRestart` 和 `downloadedQueueAndSettingsSurviveColdRestartWithServerStopped`，两阶段各 1 项通过，日志 `cold-start-prepare.txt`、`cold-start-verify.txt`。
- 仅在空白、可丢弃设备中，传入 `-e playbackAcceptance true` 并指定方法执行；冷启动两阶段必须分成两次 instrumentation，之间 force-stop 应用，不能直接运行整类替代该流程。
- `:core:player:testDebugUnitTest` 的 16 项单元测试通过；`:app:assembleRelease` 成功，包含 Release lint 检查。日志 `release-final-retry.log`。
- 初次构建出现内存不足，并导致模拟器失去响应；冷启动测试随后在重新启动的临时副本中完整重跑通过。中断及失败记录不计入通过数。

`scripts/acceptance_music_server.py` 提供覆盖升级用的本地合成服务器；`scripts/android_acceptance_ui.py` 提供本机专用 UI 记录工具，固定操作端口 5580。服务器请求日志省略认证参数。覆盖安装前后 JSON、截图、系统媒体会话及机器核对结果均保存在 `outputs/acceptance-20260917/`：

- `upgrade-install.txt`：保留数据覆盖安装成功。
- `upgrade-before/after-downloads.jsonl`、`upgrade-before/after-settings.jsonl`：下载和设置。
- `upgrade-before/after-queue.jsonl`、`upgrade-after-queue.xml`：队列顺序和循环模式。
- `upgrade-before-media.txt`、`upgrade-after-media-paused.txt`、`upgrade-after-media-playing-1/2.txt`：进度、当前歌曲、播放状态。
- `upgrade-verification.json`：核对摘要及 APK 哈希。

原始 APK SHA-256：`92BB792052105E5E9382D1D7EE80CFB3427FC1C23D2EBB831C122A282D314F64`。

当前验收 APK：`outputs/acceptance-20260917/ClearTune-acceptance.apk`；SHA-256：`6E19C3A1A142DF993B51700C105690B4F2F0205F2B1AD9B80E1D602C21A71F21`。

两者签名证书 SHA-256 相同：`635562bf935baa0513b975f5b7192832d9a2878a8e693dcebc7afe6deef3fe0e`。

## 真机待补测

1. 移动网络真实进入弱信号/无信号区域，分别测试有缓存、无完整缓存、断网耗尽缓冲；记录恢复网络后自动续播及手动重试表现。
2. 使用实际蓝牙耳机连接、断开、重连，检查音频去向、意外外放及耳机播放控制。
3. 在用户实际手机系统中锁屏和后台持续播放 15–30 分钟，检查电池优化及后台限制下是否中断。
4. 真机快速拖动进度与队列排序，再返回曲库，补充手势流畅性验收。
