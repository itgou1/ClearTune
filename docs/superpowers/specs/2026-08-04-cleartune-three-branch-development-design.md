# ClearTune 三分支并行开发设计

## 1. 目标

在当前只有产品、UI 和实施计划文档的仓库上，先建立一个所有人共享且可编译的 Android 多模块基线，再创建三个互相隔离的 Git worktree。三名员工从同一基线提交开始工作，通过模块所有权和冻结契约避免同时编辑相同文件。

成功标准：

- 三个 worktree 均能独立执行对应模块的单元测试和编译任务。
- 任意员工的日常开发不需要修改另一名员工独占的模块。
- 公共接口变化具有单独提交、全员确认和同步步骤。
- 三个分支可以按约定顺序合入 `main`，不会依赖手工复制文件。
- 最终集成仍满足已批准的产品规格、UI 规格和实现计划。

## 2. 采用方案

采用“共享契约基线 + 模块独占分支”。不采用纯分层分工，因为数据层、播放层、UI 层会形成串行依赖；也不采用没有模块边界的纵向功能分工，因为三个分支会频繁争用数据库、导航、依赖注入和 Gradle 文件。

共享基线负责建立构建结构、公共类型、公共契约、设计令牌、测试替身和可编译占位入口。基线提交完成后，公共区域原则上冻结。

## 3. 模块结构

```text
:app                    最终组装、根导航、应用级依赖注入
:core:model             领域模型、ID、枚举、跨模块 UI 状态
:core:contracts         Repository、数据库写入网关、播放网关等接口
:core:designsystem      主题、尺寸、公共小组件及语义约定
:core:testing           Fake、fixture、协程测试工具
:core:database          Room 实体、DAO、数据库和契约实现
:core:network           OkHttp、认证、传输安全和网络错误模型
:data:local             MediaStore 与本地扫描
:data:webdav            WebDAV 浏览、同步和元数据补全
:data:download          离线下载、文件策略和 WorkManager
:playback               Media3 服务、队列同步和播放控制器
:feature:library        曲库、歌曲、专辑、歌手、文件夹、搜索
:feature:sources        WebDAV 来源配置和同步状态
:feature:downloads      下载及存储管理
:feature:player         Mini-player、完整播放器、歌词、队列
:feature:playlists      歌单 CRUD 与排序
:feature:settings       设置、关于、许可和全局状态入口
```

生产代码只有 `:app` 一个 Android application 模块，其余为 Android/Kotlin library 模块。最终发布阶段可按既有计划增加测试专用 `:benchmark` 模块，它不属于任何员工首轮开发范围。

## 4. 共享契约基线

基线提交必须包含：

- AGP、Gradle、JDK、KSP 和全部已知依赖版本。
- 上述模块及其单向依赖关系。
- `:core:model` 中的稳定模型：`MusicSource`、`Track`、`TrackLocation`、`Album`、`Artist`、`Playlist`、`Download`、`Queue`。
- `:core:contracts` 中的稳定接口：`LibraryRepository`、`LibraryWriteGateway`、`PlaybackLibraryRepository`、`SourceRepository`、`SourceWriteGateway`、`DownloadRepository`、`PlaybackGateway`、`QueueRepository`、`PlaylistRepository`、`SettingsRepository`、`CredentialStore`。
- `:core:designsystem` 中的颜色、排版、间距、48 dp 目标尺寸以及 8 个默认歌手头像入口。
- `:core:testing` 中与接口一一对应的内存 Fake 和固定 ID/时钟/调度器。
- 每个 feature 模块只有一个公开 Route 入口；`:app` 通过稳定入口注册导航。
- 所有模块的占位实现必须可编译，但不得伪装成真实功能；占位页面只显示模块名称和开发状态。

模块之间不得直接使用其他模块的内部实现。远程同步和下载通过 `LibraryWriteGateway`/`SourceWriteGateway` 写入，不直接引用 Room DAO；播放只通过 `PlaybackLibraryRepository` 读取可播放位置，不引用 Room DAO 或 feature 模块。

## 5. 分支和文件所有权

### 员工 1：本地曲库与数据库

- 分支：`codex/employee-1-local-library`
- Worktree：`.worktrees/employee-1-local-library`
- 独占模块：`:core:database`、`:data:local`、`:feature:library`
- 交付：Room v1、MediaStore 权限和增量扫描、Library/Songs/Albums/Artists/Folders/Search。
- 禁止修改：`:core:network`、`:data:webdav`、`:data:download`、`:playback`、其他 feature 模块和 `:app`。

### 员工 2：WebDAV 与离线下载

- 分支：`codex/employee-2-webdav-offline`
- Worktree：`.worktrees/employee-2-webdav-offline`
- 独占模块：`:core:network`、`:data:webdav`、`:data:download`、`:feature:sources`、`:feature:downloads`
- 交付：Keystore 凭据、HTTPS/HTTP 策略、Basic/Digest、PROPFIND、递归同步、Range 元数据、暂停/恢复/取消下载。
- 禁止修改：`:core:database`、`:data:local`、`:playback`、其他 feature 模块和 `:app`。

### 员工 3：播放与产品组装

- 分支：`codex/employee-3-playback-product`
- Worktree：`.worktrees/employee-3-playback-product`
- 独占模块：`:playback`、`:feature:player`、`:feature:playlists`、`:feature:settings`、`:app`
- 交付：MediaLibraryService、ExoPlayer、队列恢复、缓存、Mini-player、完整播放器、歌词、歌单、设置和最终导航/DI 组装。
- 禁止修改：员工 1 和员工 2 的独占模块。

根构建文件、`:core:model`、`:core:contracts`、`:core:designsystem` 和 `:core:testing` 属于共享冻结区，三名员工均不直接修改。

## 6. Git 与 Worktree 隔离

项目根目录新增 `.worktrees/` 到 `.gitignore` 并单独提交。三个 worktree 必须从同一个共享基线提交创建，不能从未提交工作区创建。

```text
D:\DvWorkspaces\CodeX\ClearTune\                         main 集成目录
D:\DvWorkspaces\CodeX\ClearTune\.worktrees\employee-1-local-library
D:\DvWorkspaces\CodeX\ClearTune\.worktrees\employee-2-webdav-offline
D:\DvWorkspaces\CodeX\ClearTune\.worktrees\employee-3-playback-product
```

各 worktree 自带独立工作树、`.gradle` 项目状态和模块 `build/` 输出；只共享 Git 对象库、Android SDK 和全局 Gradle 依赖缓存。员工不得在其他人的 worktree 中运行格式化或 Git 命令。

## 7. 公共契约变更

公共契约冻结后，确需修改时执行以下流程：

1. 提议者写明动机、旧/新签名、迁移影响和对应测试。
2. 从最新 `main` 创建短生命周期分支 `codex/contract-<topic>`。
3. 契约修改、Fake 修改和契约测试放在一个独立提交中，不夹带功能实现。
4. 三名员工确认后先合入 `main`。
5. 三个员工分支立即 rebase 或 merge 最新 `main`，全部模块重新编译后才继续功能开发。

禁止在员工功能分支中静默修改公共签名，也禁止复制一份相似接口到自己的模块规避评审。

## 8. 测试与完成定义

每个分支至少通过：

- 独占模块的全部单元测试。
- 独占模块的 `assembleDebug` 或 Kotlin 编译任务。
- Android Lint（适用于 Android 模块）。
- `git diff --check` 和文件所有权检查。

员工 1 额外通过 Room schema 和本地扫描仪器测试；员工 2 额外通过 MockWebServer、WorkManager 和文件完整性测试；员工 3 额外通过 Media3 服务、Compose 导航和播放恢复测试。

各分支的提交应保持模块内聚，禁止一个提交同时修改两个员工的所有权区域。

## 9. 合并与集成顺序

1. 员工 1 分支先合入 `main`，提供真实数据库和本地曲库实现。
2. 员工 2 基于最新 `main` rebase，解决契约层差异后合入，接通远程写入和下载位置。
3. 员工 3 基于前两次合并后的 `main` rebase，完成 `:app` 的真实依赖装配和最终导航，然后合入。
4. 在 `main` 执行完整 Lint、单元测试、仪器测试和 release 构建。

员工 1 与员工 2 的开发期可以完全并行。员工 3 的播放和 UI 模块可以并行开发并使用 Fake；只有最后的应用装配需要在前两个分支合入后完成。

## 10. 故障与冲突处理

- 基线测试失败：停止创建 worktree，先修复或由负责人明确接受红色基线。
- 某分支需要越界编辑：拆成公共契约提交，不直接跨区修改。
- 合并冲突发生在冻结区：退回产生冲突的功能提交，按契约变更流程重做。
- 合并冲突发生在独占区：说明存在越权提交，先移除越权修改再合并。
- 员工 3 等待集成时：继续使用 `:core:testing` Fake 完成播放器和页面，不提前复制员工 1/2 的实现。

## 11. 与既有计划的映射

- 员工 1 执行 Foundation 中数据库相关任务和 Local Library 计划。
- 员工 2 执行 WebDAV 计划和 Offline Downloads 计划。
- 员工 3 执行 Foundation 中应用壳层、Playback 计划及 Product Completion 中歌单/设置/集成任务。
- Foundation 中的 Gradle、公共模型、公共契约、设计系统和 Fake 由共享基线一次性完成，不归任何员工分支重复实现。
