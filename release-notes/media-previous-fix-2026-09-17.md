# 耳机上一曲修复（2026-09-17）

耳机及系统媒体控制的“上一曲”现在直接切到上一首，与应用内按钮一致。歌曲播放超过 3 秒后，也不再被解释为重播当前歌曲。

## 改动

`PlaybackService` 向 MediaLibrarySession 提供一个 ForwardingPlayer，将 `seekToPrevious()` 转到 `seekToPreviousMediaItem()`，并同步控制器使用的上一曲进度阈值。顺序播放到第一首时，与手机按钮一样保持当前进度；暂停状态保持暂停。切歌仍遵循现有队列、随机顺序和循环模式。

此前复现过程见 [诊断记录](media-previous-diagnostic-2026-09-17.md)。

## 验证

使用 Android API 37 临时只读模拟器、合成音乐和实际播放服务，以 `cmd media_session dispatch previous` 发送系统媒体命令。`PlaybackAcceptanceTest` 新的三项回归全部通过（44.389 秒）：

- `externalPreviousSkipsAfterFiveSecondsAndRepeatedly`：应用退到后台，每次把歌曲进度设为 5 秒后触发上一曲，第三首 → 第二首 → 第一首 → 循环回第三首；随后再连续切换三次。
- `externalPreviousMatchesPhoneInEveryPlaybackMode`：顺序、列表循环、单曲循环、随机四种模式均与手机按钮结果一致。随机对照复用同一队列，避免重新生成随机顺序。
- `externalPreviousAtQueueStartDoesNotRestartOrResumePausedAudio`：顺序模式下，3 首队列的第一首及单首队列均在 5 秒暂停，连续三次上一曲后仍保留歌曲、暂停状态与 5000 ms 进度。

播放器现有 16 项单元测试通过，Debug 及 instrumentation 构建成功。最初运行被模拟器以 `LOW_MEMORY` 终止（无应用异常堆栈），已保留原因并在系统启动稳定后完整重跑；该次中断不计通过。

证据保存在 `outputs/media-previous-fix-20260917/`：`regression-retest.txt`、`events.txt`、`debug-build.log` 和 `initial-process-exit.txt`。

实体蓝牙耳机及其手势识别尚未实测；本轮验证的是应用收到系统媒体上一曲命令后的处理。

## 签名测试包

Release 构建及 lintVital 通过，签名验证成功，与原正式版使用同一证书。测试包位于 `outputs/media-previous-fix-20260917/ClearTune-previous-fix.apk`，仍为 1.3.4 / versionCode 9，未发布。

APK SHA-256：`AD38638A130327C8E218D77D676287AF8E5BD696F2C148D3E30F96A2480A1BBA`。

签名证书 SHA-256：`635562bf935baa0513b975f5b7192832d9a2878a8e693dcebc7afe6deef3fe0e`。

构建与验签证据：同目录 `release-build.log` 和 `signature.txt`。运行回归使用 Debug 包；本轮未重新运行 Release 包的真机蓝牙测试。
