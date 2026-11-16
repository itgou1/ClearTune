# 1.6.2 发布准备与验证记录

日期：2026-11-16。目标版本：1.6.2 / versionCode 20。

## 本轮已验证

- Pixel 8 / Android 17 只读模拟器：20 个不同用例通过，共执行 21 次，其中容量弹窗用例扩充放弃修改断言后复测。
- 覆盖容量保存与取消、深色大字体、歌词前台播放常亮及暂停/离开/生命周期降级释放、本机下载菜单和失效文件入口、音乐库标签顺序与默认专辑页、批量选择、播放器菜单、公共下载和旧文件迁移。
- 已检查缓存页面普通字号及深色大字号截图，未发现控件遮挡。
- 听歌画像和容量解析专项 JVM 测试：5 项通过。
- 用户在本次对话中确认手动锁屏正常；未提供机型与系统版本，不推断其他机型结论。
- 设备日志：`app/build/reports/recent-ui/instrumentation.txt`、`followup.txt`；截图同目录 `screenshots/`。
- 既有缓存/下载开发阶段结果见 [历史验证记录](validation-cache-download-2026-10-30.md)，不计入本轮用例数。

## 发布门禁

- `testDebugUnitTest lintDebug :app:assembleRelease :app:bundleRelease` 构建成功。日志：`.release/v1.6.2-validation.log`。
- 全模块 JVM：262 项，257 项通过、5 项跳过、0 失败、0 错误。
- Debug Lint：0 错误、97 条警告、1 条提示；Release 必要 Lint 通过。
- APK：6,744,611 字节，apksigner 校验通过，证书 SHA-256 与下载留存的线上 1.6.1 相同。
- AAB：12,636,840 字节，构建签名完成，jarsigner 校验退出码 0；有自签名证书、无时间戳及 ZIP/JarInputStream 条目顺序提示，未将其描述为零警告。
- 更新清单：versionCode 20、versionName 1.6.2、APK 名 `ClearTune-v1.6.2.apk`，哈希 `0055d5599b9c3ce2e3d126630a1f12126ed56f69d54918ef9a7f9234e6c372ad`。
- 本地候选附件目录：`.release/prepared-v1.6.2/`，包含 APK、AAB、各自 SHA-256 和 `update.json`。凭据未复制到附件目录。
- Pixel 8 / Android 17 只读模拟器：先安装线上留存 1.6.1，再以 `adb install -r` 覆盖为候选 1.6.2；安装成功，版本号 19 → 20，首次安装时间未改变。冷启动成功，进程保持运行，未见该进程 AndroidRuntime 错误。未逐项核验真实账号、设置和下载内容。

## 发布步骤

1. 审查本次应用、核心模块、测试及文档改动，提交对应源码。`scripts/music_library/` 是独立本地音乐库工具，不纳入此版本；不得提交 `.release/` 内的凭据和签名文件。
2. 核对版本 1.6.2 / 20、`release-notes/v1.6.2.md` 及正式签名一致性。
3. 在确认发布后推送源码提交，再将 `v1.6.2` 标签指向同一提交并推送。推送标签会触发 `.github/workflows/release.yml`，自动执行 JVM 测试、Lint、APK/AAB 构建并公开 GitHub Release。
4. 持续检查工作流至完成，核对 Release 的 APK、AAB、SHA-256 文件和 `update.json`（版本 20 / 1.6.2、APK 文件名及哈希）。
5. 从已发布附件验证签名、版本及下载完整性；在既有 1.6.1 正式签名版本上覆盖升级，核对账号、设置和下载保留，以及应用内更新检测。

发布已获用户授权。将提交对应源码并推送 `v1.6.2` 标签，通过仓库现有工作流公开发布；实际工作流结果以 GitHub Actions 记录为准。

## 边界

20 个模拟器交互用例基于当前 Debug 代码构建；正式候选额外完成了上述覆盖安装和冷启动检查，未重复全部交互用例。Android 8/9、厂商后台策略及真实音乐库批量迁移未在本轮重测。
