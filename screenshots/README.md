# 界面图片说明

## 当前界面（1.3.3）

| 文件 | 内容 | 来源 |
| --- | --- | --- |
| ui-home-v1.3.3.png | 今天听什么：首页发现卡片、叠放封面推荐、最近添加 | 当前 Debug 应用直接截图 |
| ui-mine-v1.3.3.png | 我的：画像与喜欢、紧凑离线入口、页内歌单 | 当前 Debug 应用直接截图 |

截图使用浅色主题和未播放状态。演示服务器只在测试设备本地运行，曲目、音乐人、歌单与账号均为虚构数据；原创插画封面由测试代码用 Android Canvas 绘制，采用晨光、城市、海面、夜景、植物和远山六种不同主题。页面来自实际生产界面代码，未重绘或拼接截图中的控件。

首页内容取决于曲库规模和播放记录：“发现音乐”需至少 20 首歌曲，左侧推荐可能显示“随便听听”或“好久不见”。未播放状态不会显示迷你播放器。静态截图不展示入场与按压动效。

## 重新生成

使用无真实账号登录的专用 Android 模拟器。截图测试默认跳过，必须显式启用；测试结束会退出演示账号并恢复原来的主题设置。

在仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.cleartune.app.DocumentationScreenshotsTest -e documentationScreenshots true com.cleartune.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull /sdcard/Android/data/com.cleartune.app/files/documentation/ui-home-v1.3.3.png screenshots/ui-home-v1.3.3.png
adb -s emulator-5554 pull /sdcard/Android/data/com.cleartune.app/files/documentation/ui-mine-v1.3.3.png screenshots/ui-mine-v1.3.3.png
```

设备序列号按实际情况替换。不要为生成截图卸载真实手机上的应用，也不要将真实账号或私人曲库截图直接提交到仓库。

## 历史图片与概念图

- ui-home-sanitized.png、ui-player-sanitized.png、ui-queue-sanitized.png：早期脱敏参考图，不作为 1.3.3 实机界面的依据。
- ui-library-redesign-v1.png、ui-library-redesign-v2.png：音乐库设计方案，不等同于已实现界面。
- product-overview.png：早期产品概念插图，README 不再将其作为当前界面展示。
- private-library.png：私人音乐服务器关系示意图，不是应用截图。

历史图片保留以避免破坏已有引用；更新界面文档时优先使用带版本号的真实截图。
