# ClearTune（轻调）

一款为私人音乐库设计的原生 Android 播放器。

ClearTune 连接你自己的 Navidrome 或 OpenSubsonic 兼容服务器，把浏览、播放、歌单、下载和音质设置整理成轻量、清晰的日常听歌体验。它不提供云音乐内容，也不要求注册 ClearTune 账号。

当前版本：`1.3.1`

<p align="center">
  <img src="screenshots/product-overview.png" alt="ClearTune 首页、正在播放与离线音乐体验插图">
</p>
<p align="center"><sub>产品体验示意图；画面使用抽象封面和占位信息，不包含真实用户数据。</sub></p>

## 真实界面

以下画面来自实际运行中的 ClearTune。为保护隐私，截图中的用户名、人物封面、歌曲名称和艺术家名称已替换为虚拟内容；页面布局、功能控件与视觉样式保持真实状态。

<table>
  <tr>
    <td align="center"><img src="screenshots/ui-home-sanitized.png" alt="ClearTune 首页真实界面（已脱敏）" width="250"></td>
    <td align="center"><img src="screenshots/ui-player-sanitized.png" alt="ClearTune 正在播放真实界面（已脱敏）" width="250"></td>
  </tr>
  <tr>
    <td align="center"><sub>首页与个性化推荐</sub></td>
    <td align="center"><sub>正在播放</sub></td>
  </tr>
</table>

## 产品亮点

### 打开就有想听的音乐

首页围绕私人曲库提供最近加入、换个口味、常听精选和发现音乐等内容。推荐只来自你自己的音乐收藏，不混入广告或平台推广内容。

### 从曲库到播放，操作更顺手

- 按专辑、艺术家、歌曲、流派或服务器文件夹浏览；流派可直接进入对应歌曲列表
- 搜索会先从本地缓存即时显示结果，再合并服务器结果，并支持继续加载更多内容
- 音乐库显示当前同步阶段和上次成功同步时间，连接异常时仍保留本地内容
- 快速喜欢歌曲、下一首播放、加入歌单、下载或查看歌曲详情
- 歌单与 Navidrome 同步，支持多选后批量移除歌曲
- 常驻迷你播放器，在浏览不同页面时也能继续控制播放
- 播放队列支持长按拖动排序，并提供顺序、随机和循环模式
- 支持同步歌词、歌曲格式与码率信息，以及播放进度快速调整

### 离线也能完整听歌

ClearTune 可以下载服务器中的原始音乐文件，并支持暂停、继续和断点续传。应用会识别重复任务、显示 Wi-Fi 等待状态，并在服务器拒绝下载或本地空间不足时给出明确提示。取消下载任务与删除已完成文件使用不同操作文案，并在删除前再次确认。完成下载后，无网络也可以直接播放。

### 音质由你决定

- Wi-Fi 下默认保留服务器提供的原始音质
- 移动网络可选择 128、192、320 kbps，或继续使用原始音质
- ReplayGain 音量平衡，减少不同专辑之间忽大忽小的听感
- 提供易用的均衡器预设，也支持自定义调节

### 贴近日常 Android 使用习惯

界面采用 Material 3，支持浅色、深色和跟随系统主题，并配有沉浸式系统栏、自然的页面动效和无封面时的统一占位图。播放、下载和操作结果会在当前页面及时反馈。

## 为私人音乐库而设计

<p align="center">
  <img src="screenshots/private-library.png" alt="手机直连私人音乐服务器的隐私体验插图">
</p>
<p align="center"><sub>隐私关系示意图；不展示账号、服务器地址、真实封面或曲目信息。</sub></p>

- 应用只连接你主动配置的音乐服务器，不内置公共服务器或共享账号
- 不提供 ClearTune 云账户，曲库和播放内容不会上传到 ClearTune 服务
- 登录凭据由 Android Keystore 保护并保存在设备本地
- 喜欢、歌单和播放记录只在本地设备与你的音乐服务器之间处理或同步
- 不接入广告、用户行为统计 SDK 或大模型服务
- 开启更新检查时，仅访问公开的 GitHub Releases 接口

## 适合谁

ClearTune 适合已经拥有 Navidrome 或其他 OpenSubsonic 兼容服务器，希望在 Android 上获得原生、轻量、重视离线和音质体验的用户。

它不是在线音乐平台，不提供歌曲版权内容或公共曲库；使用前需要准备一个可访问的私人音乐服务器。目前客户端面向 Android 设备。

## 快速开始

1. 从项目的 [GitHub Releases](https://github.com/itgou1/ClearTune/releases) 下载并安装最新版 APK。
2. 准备一个可访问的 Navidrome 或 OpenSubsonic 兼容服务器。
3. 打开 ClearTune，填写服务器地址、用户名和密码。
4. 连接成功后，即可浏览曲库、同步喜欢与歌单并开始播放。

<details>
<summary>开发者构建信息</summary>

本项目使用 JDK 17 和 Android SDK 37，并通过 Gradle Wrapper 固定构建工具版本。

```bash
git clone https://github.com/itgou1/ClearTune.git
cd ClearTune
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

运行本地单元测试：

```bash
./gradlew testDebugUnitTest
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。正式版由仓库的 GitHub Actions 工作流签名并发布到 GitHub Releases；签名文件和密码不得提交到仓库。

</details>

## 许可与商标

ClearTune 源代码采用 [GNU General Public License v3.0](LICENSE) 发布。发布本项目或其修改版本的 APK 时，必须按照 GPL-3.0 向接收者提供完整对应源码。

`ClearTune`、`轻调`、猫头鹰耳机图形和应用图标属于项目标识，不因 GPL-3.0 自动获得商标使用许可。修改版应使用不同的名称、图标和包名，并清晰说明其基于 ClearTune。详情参见 [商标政策](TRADEMARKS.zh-CN.md)。

## 相关生态

- [Navidrome](https://www.navidrome.org/)
- [OpenSubsonic API](https://opensubsonic.netlify.app/)
