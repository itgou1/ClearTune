# 耳机上一曲行为诊断（2026-09-17）

后续状态：已按用户要求修复，原诊断方法已替换为切歌一致性的回归测试。下文保留修复前证据；修复及验证见 [修复记录](media-previous-fix-2026-09-17.md)。

已通过系统媒体会话命令复现：当前歌曲进度超过默认阈值时，外部“上一曲”重播当前歌曲；手机内上一曲直接切换歌曲。本轮只增加诊断测试，未修改产品播放逻辑。

## 运行结果

环境为 Android API 37 临时只读模拟器，3 首合成歌曲，列表循环模式，实际 PlaybackService / ExoPlayer。外部命令为 `cmd media_session dispatch previous`。未连接实体蓝牙耳机，因此未验证蓝牙链路或耳机固件行为。

| 操作 | 实际结果 |
| --- | --- |
| 从第三首开头发出上一曲 | 第二首，进度 875 ms |
| 再发出上一曲 | 第一首，进度 40 ms |
| 第三次发出上一曲 | 循环回第三首，进度 14 ms |
| 将第三首进度设为 5 秒，再发出上一曲 | 仍为第三首，回到 0 ms |
| 再将第三首进度设为 5 秒，再发出上一曲 | 仍为第三首，回到 0 ms |
| 同样在第三首 5 秒位置调用手机按钮对应的 `PlayerViewModel.previous()` | 切换第二首，进度 282 ms |

本轮没有复现“快速操作恰好两次后永久失效”。已复现的条件是歌曲进度超过阈值。5 秒条件通过 seek 设置，未模拟耳机手势耗时。

## 原因核对

手机路径为 `PlayerViewModel.previous()` → `PlayerConnection.previous()` → `seekToPreviousMediaItem()`。服务将 ExoPlayer 直接交给默认 MediaLibrarySession，媒体键路径调用 `seekToPrevious()`。

检查项目实际使用的 Media3 1.11.0 本地依赖字节码：`BasePlayer.seekToPrevious()` 在当前进度大于 `getMaxSeekToPreviousPosition()` 时跳到当前歌曲的 0 秒；`C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS` 为 3000。当前应用未覆盖该设置。

## 证据

- 新增诊断方法：`PlaybackAcceptanceTest.diagnoseExternalPreviousVersusPhonePrevious`。
- 必须在空白可丢弃设备上显式传入 `playbackAcceptance=true` 和 `mediaPreviousDiagnostic=true` 并指定该方法执行。断言描述现有问题，不代表期望修复行为；修复时应替换为切歌一致性的回归断言。
- 成功日志：`outputs/media-previous-20260917/diagnostic-retest.txt`，`OK (1 test)`，23.354 秒。
- 事件日志：同目录 `events.txt`；依赖行为证据：`BasePlayer-bytecode.txt`、`session-key-bytecode.txt`。
- 初次 `input keyevent KEYCODE_MEDIA_PREVIOUS` 注入等待切歌超时，无法单独判定原因；该次记录不作为复现结论依据。改为系统媒体会话命令后，上述完整对照通过。

建议后续将耳机/系统媒体控制与手机内上一曲统一为直接切换上一首，并补测顺序、循环、随机、单曲循环及队列边界。实体耳机仍需按用户实际操作间隔复核。
