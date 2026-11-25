# Looma: notes for working on the code

## 开发约定

- 除非明确说明，否则不要写文档和注释
- 不用迁移数据库，还在个人开发阶段
- 如果 /verify 没有更新，就不要使用 /verify 验证和 avd 验证
- 只有在单独说明要写 test 和 androidTest 的时候才能写测试
- 写或改 UI 前，先读 `DESIGN.md`，按其中的规范实现
- 每次用完 AVD 都要关闭模拟器进程。用 verify skill 时执行 `v cleanup`，再确认 `adb devices` 里没有 `emulator-*`。

## 技术选型

- **平台**。Android Native，Kotlin，minSdk 26，targetSdk 37，compileSdk 37。
- **UI**。Jetpack Compose + Material 3 Expressive，支持 Dynamic Color，设计参考 Google Keep。
- **编辑器**。笔记正文使用 `sora-editor`（`io.github.rosemoe:editor`）。
- **架构**。MVVM + Repository，按 Feature 组织代码，UI 与 Data 分离。
- **状态管理**。ViewModel + StateFlow，单向数据流（UDF）。
- **依赖注入**。手动注入（`LoomaApp`）和 ViewModel Factory。
- **文件存储**。Local-first，Markdown 文件作为唯一内容数据源。
- **文件访问**。`MANAGE_EXTERNAL_STORAGE`，使用 `java.io.File`。
- **配置与密钥**。Preferences DataStore 保存 Vault 元数据和应用设置。S3 密钥由 `S3CredentialCipher` 使用 Android Keystore AES-256-GCM 加密后写入 DataStore。
- **导航**。Navigation 3（`navigation3-runtime`、`navigation3-ui`）与 `androidx.navigationevent`。
- **数据库**。Room `2.8.4`（`looma_index.db`）存储索引、搜索历史和同步状态，不存储 Markdown 正文。`PassageVectorStore` 使用独立 SQLite 数据库存储语义检索分段向量。
- **网络与同步**。Ktor（`ktor-client-okhttp`）配合 AWS SigV4 签名，支持 S3 图床上传与双向云同步。
- **后台任务**。WorkManager 调度语义索引更新和 S3 云同步。
- **本地 AI**。`:embedding` 模块（EmbeddingGemma + MediaPipe / ONNX Runtime）负责向量化；`litertlm-android` 负责本地问答生成；`LocalModelSession` 互斥管理两者生命周期。
- **构建**。多模块 Gradle Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`）。

## Layout

Android 工程包含五个 Gradle 模块：`:app`、`:markdown-core`、`:embedding`、`:markdown-benchmark` 和 `:embedding-benchmark`。

- `markdown-core/src/main/kotlin/com/example/looma/domain/markdown/`。纯 Kotlin Markdown 解析与结构模块，无 Android 依赖。
  - `Frontmatter.kt`、`MarkdownDocument.kt`、`MarkdownIndex.kt`：剥离 frontmatter 后用 `org.intellij.markdown` 的 GFM 解析正文，提取标题、checkbox 位置和代码块外的 wiki link。所有偏移都相对原文 `source`。
  - `WikiImageFlavour.kt`、`PermissiveMathParser.kt`、`MarkdownMath.kt`：渲染用 GFM flavour，把 `![[target|size]]` 解析为标准 `IMAGE` 节点，按 Obsidian 规则解析 `$` 和 `$$` 公式并排除货币金额。`MarkdownIndex` 与之对代码范围和 checkbox 位置的判断必须一致（`WikiImageFlavourTest`）。
  - `WikiLinks.kt`、`PreparedMarkdown.kt`、`ReadingBlocks.kt`、`MarkdownNavigation.kt`：链接重定位、渲染预处理、阅读分块和目录导航。
- `embedding/src/main/java/com/example/looma/embedding/`。`EmbeddingGemma.kt` 封装本地 embedding 模型加载与推理后端（CPU / NPU）。
- `markdown-benchmark/` 与 `embedding-benchmark/`。独立性能基准模块。
- `app/src/main/java/com/example/looma/`
  - `LoomaApp.kt`：手动注入容器，用 `by lazy` 持有所有 Repository、`NoteIndexDatabase`、`httpClient`、`LocalModelSession`、`SemanticSearchRepository`、`VaultEvidence`、`LocalGenerator`、`VaultAgent`、`ImageUploader` 和 `SyncRepository`。它也实现 Coil 的 `SingletonImageLoader.Factory`，图片磁盘缓存在 `cacheDir/image_cache`。
  - `MainActivity.kt`、`LaunchGate.kt`：`AppCompatActivity`，应用内切换语言需要 AppCompat。它用 `LaunchGate` 控制启动页释放时机，持有 Activity 级 `VaultSetupViewModel`，读取 `SettingsRepository.settings` 后套用 `LoomaTheme`，再进入 `LoomaNavGraph`。
  - `navigation/`：`LoomaRoute` 定义 `@Serializable` 的 `NavKey`，包含 `VaultSetup`、`Home`、`Ask`、`Trash`、`Settings`、`SemanticSearchSettings`、`SyncSettings` 和 `Note`。`LoomaNavGraph` 在 vault 不可用时重置返回栈到 `VaultSetup`，用 `rememberViewModelStoreNavEntryDecorator` 管理各页面 ViewModel，并用 `NoteBackHandler`（`NavigationBackHandler`）在返回前完成保存。`LoomaNavigationRail` 提供 `Notes`、`Ask`、`Trash` 和 `Settings` 四个入口，在紧凑窗口用 `ModalWideNavigationRail`，在中等及以上窗口用常驻 `WideNavigationRail`。
  - `domain/`：纯 Kotlin 领域层（`model/`、`markdown/`、`wiki/`、`search/`、`sync/`）。
    - `model/`：`Vault`、`NoteId`、`NotePath`、`FolderPath`、`NoteRevision`（文件原始字节 SHA-256，用于乐观并发）和 `NoteTextStats`（每个 CJK 字符算一个词）。
    - `markdown/`：`MarkdownEditing.kt`（手写字符串扫描的工具栏文本编辑）、`ToolbarItem.kt`、`ToolbarOrder.kt`（枚举顺序即默认顺序，项 `id` 逗号拼接后存 DataStore）和 `ImageSource.kt`。
    - `wiki/`：`WikiGraph.kt` 与 `LocalNoteLink.kt`，按 Obsidian 规则解析链接并计算 `referrersOf` 与 `retargetAfterRename`。
    - `search/`：`NoteSearch.kt`、`PassageAnchor.kt`。
    - `sync/`：`SyncModels.kt`、`SyncPlanner.kt`、`SyncFileFilter.kt`。
  - `data/`
    - Vault 内的 `.looma/` 存放应用状态：`trash/`、`conflicts/`、`operations/` 和 `abandoned-operations/`。扫描、搜索和重定位都跳过以 `.` 开头的目录。
    - `vault/`：`VaultRepository` 将当前 vault 存入 Preferences DataStore，每次调用 `requireCurrentVault()` 检查权限、挂载状态、外部存储根目录与读写权限。`VaultStorageAccess` 只接受 primary 卷的 tree URI。`VaultWriteCoordinator` 为每个 vault 规范路径分配一把 `Mutex`，并在 `Dispatchers.IO + NonCancellable` 中执行写入。
    - `note/`：`NoteRepository` 是笔记、文件夹和回收站的唯一入口，`write {}` 先取锁并恢复未完成重定位，写后更新索引和派生语义索引。`NoteFileStore` 做 `NoteRevision` 乐观并发检查，冲突时写入 `<name> (conflicted copy yyyy-MM-dd HHmmss).md` 并在 `Overwrite` 前备份到 `.looma/conflicts/`。`MarkdownFileIO` 原子写入单篇上限 2 MiB 的笔记。`VaultFileOps` 校验路径与 72 字符文件名上限并管理 `.looma/trash/`。`VaultRelocation` 持久化重定位日志并在崩溃后通过 `recoverPending()` 恢复，异常状态转入 `StalledRelocation` 并在放弃后移至 `.looma/abandoned-operations/`。
    - `index/`：Room 数据库 `looma_index.db`（`version = 1`，schema 导出到 `app/schemas/`），包含笔记索引、标签、链接、搜索历史和同步状态共 7 张表。`VaultIndexer` 广度优先扫描并按文件大小与 mtime 增量更新。
    - `search/`：`PassageVectorStore`（独立 SQLite）、`SemanticChunks`、`EmbeddingModelFile`、`SemanticSearchRepository`、`SemanticSearchWorkers` 和 `SearchHistoryRepository`（每个 vault 保留 20 条规范化去重历史）。
    - `assistant/`：`VaultEvidence`（混合检索）、`LocalGenerator`（LiteRT-LM 推理 `noBackupFilesDir/answer-model.litertlm`）和 `VaultAgent`（多步规划与带引用回答）。
    - `inference/`：`LocalModelSession` 串行互斥管理 Embedding 与 Generation 模型会话，支持空闲自动释放。
    - `s3/`：`LoomaHttpClient`、`S3Client`、`S3EndpointConfig`、`S3RequestSigner`（AWS SigV4）和 `S3XmlParser`。
    - `sync/`：`SyncEngine`、`SyncLocalStore`、`SyncRepository`、`SyncStateDao`、`SyncStateEntities` 和 `SyncWorker`。
    - `settings/`：`SettingsRepository`、`SettingsPreferences` 和 `S3CredentialCipher`（AES-256-GCM 加密 S3 图床与同步密钥）。`AppLocale` 用 `AppCompatDelegate.setApplicationLocales` 切换语言。
    - `image/`：`ImageUploader` 读取图片（上限 12 MiB），以内容 SHA-256 前 16 位命名并写入本地附件或上传 S3。
    - `folder/`：`FolderRepository` 将调用转发给 `NoteRepository`。
  - `feature/`：按页面分包，ViewModel 用私有 `MutableStateFlow` 暴露 `uiState`，子组件放在 `components/`。
    - `home/`：笔记列表、文件夹浏览、关键字与语义搜索、多选、撤销（`HomeUndo`）、卡住重定位提示和同步状态条。
    - `note/`：Edit 与 Read 模式切换。`NoteBodyEditor` 基于 `sora-editor`，`NoteBuffer` 做差异写回。`SaveScheduler` 防抖 2 s、连续编辑最迟 15 s 保存，后台切出时立即保存。`NoteTitleField` 在 Edit 模式顶部编辑文件名（关闭 Show inline title 后移至顶栏）。包含 `NoteNavigator`、`WikiLinkPanel`、`WeaveAction` 和 `DraftExport`。
    - `ask/`：`AskScreen` 与 `AskViewModel`，提供基于当前 vault 的本地问答与引用跳转。
    - `trash/`：回收站列表、恢复、彻底删除和清空。
    - `settings/`：`SettingsScreen`、`SemanticSearchSettingsScreen`、`SyncSettingsScreen`、`SyncConfigSheet` 和 `SyncPreviewSheet`。
    - `vault/`：`VaultSetupScreen` 与 Activity 级 `VaultSetupViewModel`。
  - `ui/`
    - `theme/`：`LoomaTheme` 使用 `MaterialExpressiveTheme`。开启 Dynamic Color 且 API 31+ 时使用壁纸配色；否则按 `Palettes.kt` 与 `PresetSchemes.kt`（由 `tools/color/generate_preset_schemes.mjs` 生成）选择配色。`Type.kt` 配置 Google Sans Flex 可变字体，`LoomaTokens` 只定义 M3 之外的令牌。
    - `markdown/`：用 `multiplatform-markdown-renderer-m3` 与 RaTeX 渲染正文（`MarkdownBody`）和卡片预览（`MarkdownPreview`），支持 `SynthItalic`、`WikiLinkText` 和 `MarkdownImage`。
    - `components/`：`FeaturePage`、`FeatureStatusCard`、`SectionHeader`、`LoomaIcon`（仅使用 `R.drawable.ic_*`）、`DelayedLoadingIndicator`、`ErrorBanner`、`LoadingPage` 和 `MessagePage`。
    - `image/`、`format/`：`LoomaAsyncImage`（Coil 3）与日期大小格式化。
- `app/src/main/res/`：`values/strings.xml`（默认英文）与 `values-zh/strings.xml`（中文）一一对应。`xml/locales_config.xml` 声明支持语言。`drawable/ic_*.xml` 主要由 `tools/fetch_material_symbols.py` 生成。`raw/keep.xml` 保留字体资源。
- `app/src/debug/`：debug 网络安全配置，仅允许对 `10.0.2.2` 和 `localhost` 发起明文 HTTP 请求。
- `app/src/test/` 与 `markdown-core/src/test/`：JVM 单测。`S3SignerIntegrationTest.signedPutIsAccepted` 仅在设置环境变量 `S3_TEST_ENDPOINT` 时运行。
- `app/src/androidTest/`：仪器测试，覆盖 Room DAO、`VaultIndexer`、`RenameRelinkTest` 和 `WikiLinkTapTest`。
- `app/build.gradle.kts`：`versionName` 为 `0.2.0`，配置 `devRelease` 构建类型与挂在 `check` 上的 `verifyTypography` 任务。`ui/theme/` 和 `ui/markdown/` 之外的 `.kt` 文件出现硬编码字体样式时会构建失败。
- `tools/fetch_material_symbols.py`：下载 Material Symbols Rounded 图标并转为 vector drawable。
- `.agents/skills/verify/`：在一次性 headless 模拟器上通过真实 UI 验证 app。用法见 `SKILL.md`，各功能步骤在 `features/`。

## Dev loop

- 所有命令在 `android/` 下运行。Windows 用 `.\gradlew.bat`，其他系统用 `./gradlew`。需要 JDK 25（`gradle/gradle-daemon-jvm.properties` 中的 `toolchainVersion`）和 SDK Platform 37。
- `.\gradlew.bat assembleDebug` 构建 debug APK，`.\gradlew.bat installDebug` 安装到已连接设备。`release` 和 `devRelease` 也使用 debug 密钥签名，`versionName` 为 `0.2.0`。
- 测试命令如下。
  - `.\gradlew.bat :markdown-core:test :app:testDebugUnitTest` 运行全部 JVM 单测。加 `--tests "<全限定类名>"` 只跑单个测试类。
  - `.\gradlew.bat connectedDebugAndroidTest` 运行 `app/src/androidTest/`，需要已连接的模拟器或真机。CI 不跑这一项。
  - `.\gradlew.bat check` 运行 lint 和 `verifyTypography`。
  - CI（`.github/workflows/android-ci.yml`，ubuntu + Temurin 25）依次运行 `:app:assembleDebug`、`:markdown-core:test :app:testDebugUnitTest` 和 `:app:lintDebug`，仅在 `android/**` 或该 workflow 变更时触发。
- 修改 Room 实体后，构建会重写 `app/schemas/.../1.json`。版本保持 1，不写迁移。装过旧版本的设备需要先卸载再安装。
- 新增图标时，在 `tools/fetch_material_symbols.py` 的 `ICONS` 中加一行，再在 `android/` 下运行 `python tools/fetch_material_symbols.py <名字>`。
- 验证 S3 图床或同步时，debug 构建仅允许对 `10.0.2.2` 和 `localhost` 发起明文 HTTP 请求。
- 在真实 UI 上验证改动时，使用 `.agents/skills/verify/`。
  - 它在一次性 headless 模拟器 `looma36`（API 36，英文）上以 `-read-only` 运行，退出后不保留数据。
  - 先构建 APK，再运行 `python .agents/skills/verify/scripts/control.py --run app/build/verify/<run> launch --avd looma36 --port 5580`。它安装 APK 并在 `/sdcard/Documents/<run>` 创建空 vault，随后按 `features/vault.md` 通过系统设置和文件选择器连接 vault。
  - 常用子命令包括 `doctor [--system]`、`ui`、`tap`、`assert`、`text`（仅 ASCII，不含 `%`）、`key`、`swipe up|down|left|right`、`toggle '<标签>'`、`select-all`、`relaunch`、`fixture`、`capture <名字>`、`files <名字>` 和 `cleanup`。
  - 截图、UI 树、logcat 和拉取的 vault 保存在 `app/build/verify/<run>/`。需要这些证据时不要执行 `clean`。
  - 用完必须执行 `cleanup`，再确认 `adb devices` 中没有 `emulator-*`。遇到 `device not found` 时，先执行 `cleanup`，再换新的 run 名称重新 `launch`。
- App 的界面或流程改动后，运行 `/maintain-verification-skill` 同步 `.agents/skills/verify/`。
