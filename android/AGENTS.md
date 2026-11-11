# Looma: notes for working on the code

## 开发约定

- 除非明确说明，否则不要写文档和注释
- 如果遇到决策问题，都优先和 Obsidian 对齐
- 不用迁移数据库，还在个人开发阶段
- 使用过 AVD 后记得关闭

## 技术选型

- **平台**：Android Native，Kotlin，minSdk 26，targetSdk 37
- **UI**：Jetpack Compose + Material 3 Expressive，支持 Dynamic Color，设计参考 Google Keep
- **架构**：MVVM + Repository，按 Feature 组织代码，UI / Data 分离
- **状态管理**：ViewModel + StateFlow，单向数据流（UDF）
- **依赖注入**：暂时使用手动注入和 ViewModel Factory
- **文件存储**：Local-first，Markdown 文件作为唯一内容数据源
- **文件访问**：MANAGE_EXTERNAL_STORAGE，使用 java.io.File
- **配置持久化**：Preferences DataStore，保存 Vault 元数据
- **导航**：计划使用 Navigation 3
- **数据库**：计划使用 Room 3 存储索引与缓存，不存储 Markdown 正文
- **构建**：Gradle Kotlin DSL + Version Catalog

## Layout

- `app/src/main/java/com/example/looma/`
  - `LoomaApp.kt`：手动注入的容器。所有 Repository、`NoteIndexDatabase` 和 `ImageUploader` 都是 `by lazy` 单例。它也是 Coil 的 `SingletonImageLoader.Factory`，图片磁盘缓存在 `cacheDir/image_cache`。
  - `MainActivity.kt`：一个 `AppCompatActivity`，应用内切换语言需要 AppCompat。它持有 Activity 级的 `VaultSetupViewModel`，读取 `SettingsRepository.settings` 后套上 `LoomaTheme`，再把依赖传给 `LoomaNavGraph`。
  - `navigation/`：Navigation 3。`LoomaRoute` 是 `@Serializable` 的 `NavKey`，有 `VaultSetup`、`Home`、`Note`、`Trash` 和 `Settings`。`LoomaNavGraph` 在 vault 不可用时把返回栈重置为 `VaultSetup`。`rememberViewModelStoreNavEntryDecorator` 让每个 entry 有自己的 ViewModel，ViewModel 由各自的 `factory(...)` 创建。`LoomaDrawer` 是侧边抽屉，只在 Notes、Trash 和 Settings 三个页面能滑出。
  - `domain/`：纯 Kotlin，没有 Android 依赖。除了简单的数据类，每个文件都有单测。
    - `model/`：`Vault`、`NoteId`、`NotePath` 和 `FolderPath` 等 value class。`NoteRevision` 是文件原始字节的 SHA-256。`NoteWriteMode` 的 `Checked` 和 `Overwrite` 用它做乐观并发。`NoteTextStats` 统计字数，每个 CJK 字算一个词。
    - `markdown/MarkdownIndex.kt`：去掉 frontmatter 后，用 `org.intellij.markdown` 的 GFM 解析正文，得到 `MarkdownDocument`。它包含一级标题、checkbox 位置和代码之外的 wiki link。所有偏移都相对原文 `source`，所以渲染层的任何变换都不能改变文本长度。
    - `markdown/WikiImageFlavour.kt`：渲染用的 GFM flavour。它把 Obsidian 的 `![[target|size]]` 解析成标准 `IMAGE` 节点，并在 `MathParser` 之后插入 `PermissiveMathParser`。`PermissiveMathParser` 的 `$` 和 `$$` 规则更接近 Obsidian，并排除货币金额。`MarkdownIndex` 用的是普通 GFM，两者对代码范围和 checkbox 位置的判断必须一致（`WikiImageFlavourTest`）。
    - `markdown/MarkdownMath.kt`：收集公式片段。`preserveDisplayMathParagraphs` 把 `$$` 块里空行的一个字符换成 U+200B，文本长度不变。
    - `markdown/WikiLinks.kt`：用正则找 `[[target#heading|alias]]`，跳过代码。`retarget` 只替换 target 部分，保留 alias、heading 和 `!`。
    - `markdown/MarkdownEditing.kt`：工具栏命令的文本编辑，手写字符串扫描，不用 Markdown 库。行内样式可以开关，块命令逐行处理。`formatState` 给出光标处的格式。
    - `markdown/ToolbarItem.kt`、`markdown/ToolbarOrder.kt`：枚举的声明顺序就是默认顺序。各项的 `id` 用逗号拼接后存进 DataStore。改了 `id`，用户对这一项的自定义位置就会丢失。
    - `wiki/WikiGraph.kt`：按 Obsidian 的规则解析 wiki link。匹配忽略大小写和 `.md`，精确路径优先。带 `/` 的按路径后缀匹配，其余按文件名匹配。重名时同文件夹优先，然后取最短路径。它提供 backlinks 和 `referrersOf`。`referrersOf` 包含笔记自身，因为重命名时也要改它自己的链接。`retargetAfterRename` 保留链接原来的写法，文件名还是文件名，路径还是路径。
  - `data/`
    - Vault 里的 `.looma/` 存放 Looma 自己的状态：`trash/`、`conflicts/`、`operations/` 和 `abandoned-operations/`。扫描、搜索和重定位都跳过以 `.` 开头的目录。
    - `vault/VaultRepository.kt`：当前 vault 存在 Preferences DataStore 里（`VaultPreferences`：id、name、path）。`requireCurrentVault()` 每次调用都检查目录：有存储权限、存储已挂载、位于外部存储根目录之下、可读写。`VaultStorageAccess` 检查权限，并把 DocumentsUI 返回的 tree URI 转成 `java.io.File` 路径，只接受 primary 卷。
    - `vault/VaultWriteCoordinator.kt`：每个 vault 根目录一把 `Mutex`，按 canonical path 区分。等锁时可以取消。拿到锁后，写入在 `Dispatchers.IO + NonCancellable` 里跑完。所有写 vault 的操作都经过它（纯 Kotlin，有单测）。
    - `note/NoteRepository.kt`：笔记、文件夹和回收站的唯一入口。`write {}` 先拿 `VaultWriteCoordinator` 的锁，再恢复未完成的重定位。`read {}` 只切到 IO，不拿锁。每次写完，`record()` 解析一次笔记，并更新 `NoteTextCache` 和 Room 索引。`wikiContext` 的 backlinks 来自 Room，不读磁盘。
    - `note/NoteFileStore.kt`：一个 vault 的笔记读写。`saveNote` 先比对 `NoteRevision`，不一致就抛 `NoteChangedExternallyException`，替换文件前再比对一次。`Overwrite` 先把磁盘上的版本备份到 `.looma/conflicts/`，备份失败就不写。冲突副本写在原笔记旁边，文件名是 `<name> (conflicted copy yyyy-MM-dd HHmmss).md`。
    - `note/MarkdownFileIO.kt`：`writeBytesAtomically` 在同一目录写 `.looma-*.tmp`，`fsync` 后用 `ATOMIC_MOVE` 覆盖目标。单篇笔记上限 2 MiB，超过就抛 `NoteTooLargeException`。
    - `note/VaultFileOps.kt`：校验路径和文件名。名字最多 72 个字符，不能含 `\/:*?"<>|`，不能以 `.` 开头，canonical path 必须在 vault 内。它还负责新建、移动和回收站。删除笔记时，把它改名为 `.looma/trash/<uuid>.md`，旁边写 `<uuid>.properties`（`originalPath`、`deletedAt`）。恢复时原位置已有文件，就报 `RESTORE_CONFLICT`。删除文件夹只回收其中的 `.md`，然后只删空目录。
    - `note/VaultRelocation.kt`：重命名和移动笔记或文件夹，同时改写其他笔记里指向它的 wiki link。
      它先规划：用改动前后两张 `WikiGraph` 算出要改的链接。然后把计划和每个文件改动前后的内容写进 `.looma/operations/op-<uuid>`，再逐步执行，每一步都持久化进度。崩溃后，下一次操作先调用 `recoverPending()`，重放或清理日志。
      遇到不认识的状态时，它停下并返回 `StalledRelocation`。之后的重定位都抛 `RelocationStalledException`，直到用户在首页放弃这条日志，日志随即移到 `.looma/abandoned-operations/`。`afterIo` 是测试用的钩子，在每个持久化点模拟崩溃（`VaultRelocationTest`）。
    - `note/MarkdownParser.kt`、`note/MarkdownSnippet.kt`、`note/SavedNote.kt`：一次解析同时产出 `Note` 和索引行：标题、摘要、标签、链接、首图，以及卡片用的 600 字符片段。
    - `note/ImageReference.kt`、`note/NoteCover.kt`：按 Obsidian 的规则找图片。先找笔记所在文件夹，再找 vault 根目录。只写文件名时搜索整个 vault，跳过 `.trash`、`.obsidian` 和 `.git`。远程图片只接受 `https://`。封面只用本地图片。
    - `index/`：Room 数据库 `looma_index.db`，`version = 1`，schema 导出到 `app/schemas/`。它只存每篇笔记的元数据、标签、链接和搜索历史，不存正文，随时可以从磁盘重建。
      `VaultIndexer` 广度优先遍历 vault，按文件大小和 mtime 决定重读哪些文件（`ScanPlan`）。遍历不完整时，它不删除任何索引行。`NoteIndexUpdater` 增量更新失败时，整个 vault 重扫一遍。
    - `search/`：搜索历史，和索引在同一个库。查询先经 `normalizeSearchQuery` 规范化再去重，每个 vault 保留 20 条。
    - `settings/`：`SettingsRepository` 把 DataStore 映射成 `AppSettings`，包括主题、Dynamic Color、工具栏顺序、图片上传目标和 S3 图床。S3 密钥以明文存在 DataStore 里。`AppLocale` 用 `AppCompatDelegate.setApplicationLocales` 切换语言，语言不存进 DataStore。
    - `image/`：`ImageUploader` 读取用户选的图片（上限 12 MiB），用内容 SHA-256 的前 16 位命名。本地附件经 `VaultWriteCoordinator` 写到 vault 根目录。图床上传先用 `S3Signer` 做 AWS SigV4 签名，再用 `HttpURLConnection` PUT。
    - `folder/FolderRepository.kt`：把调用转发给 `NoteRepository`，自己没有逻辑。
  - `feature/`：每个页面一个文件夹。ViewModel 用私有的 `MutableStateFlow` 暴露 `uiState`，页面的组成部分放在 `components/`。
    - `home/`：笔记列表、文件夹浏览、搜索和多选。`HomeVaultIndex` 用扫描结果构建文件夹树和最近笔记。`HomeSelectionMachine` 是选择状态的纯 reducer（有单测）。`HomeViewModel` 的搜索防抖 180 ms。重命名、移动和删除都能撤销（`HomeUndo`）。卡住的重定位也显示在首页。
    - `note/`：编辑和阅读。`NoteMode` 在 Edit 和 Read 之间切换。`SaveScheduler` 防抖 2 s，连续编辑时最迟 15 s 保存一次。
      `NoteViewModel` 用一个 `Mutex` 串行所有写入，用 `operationGeneration` 丢弃过期的操作。普通保存用 `Checked`。发生冲突时先写冲突副本，用户选择覆盖时才用 `Overwrite`。App 进入后台时立即保存。
      `NoteBuffer` 只把 `MarkdownEditing` 结果中变化的中间部分写回 `TextFieldState`。`WikiLinkPanel` 是点工具栏 Link a note 后弹出的候选列表，输入 `[[` 不会打开它。
      `NoteTitleField` 在正文顶部编辑文件名，随正文滚动；只出现在 Edit 模式，Read 模式把文件名显示为文本；关闭 Show inline title 后 Edit 的输入框移到顶栏。`titleEditFor` 负责去空格、空草稿回退到文件名和丢弃重复提交。
    - `trash/`：回收站的列表、恢复、彻底删除和清空。`TrashBusy` 防止同时进行两个操作。
    - `settings/`：设置页。`SettingsViewModel` 用 `stateIn(WhileSubscribed(5_000))`。
    - `vault/`：选择 vault。`VaultSetupViewModel` 是 Activity 级的，设置页也用它。没有权限时，`VaultPicker` 跳到“所有文件访问权限”页面。有权限后，它打开 `OpenDocumentTree`。
  - `ui/`
    - `theme/`：`LoomaTheme` 套用 `MaterialExpressiveTheme`。Dynamic Color 开启且 API 31+ 时使用壁纸配色，忽略主题预设。`Palettes` 用 HSL 从种子色计算预设配色，每个 on 色都保证对比度（`PalettesTest`）。`Type.kt` 给每个 M3 字体角色设置 Google Sans Flex 可变字体的 `wght`、`wdth`、`opsz` 和 `ROND`。`LoomaTokens` 把 M3 角色映射成语义名，例如 `page`、`card`、`muted` 和 `noteBody`。页面通过 `LoomaTokens.colors` 和 `LoomaTokens.type` 取用。
    - `markdown/`：用 mikepenz 的 `multiplatform-markdown-renderer-m3` 渲染，公式用 RaTeX 渲染。
      `MarkdownBody` 是阅读视图，checkbox 和 wiki link 都能点，并显示图片和公式。`MarkdownPreview` 是卡片预览，不显示图片。`LoomaMarkdown` 定义主题和自定义组件。单个换行渲染为换行（`eolAsNewLine`），与 Obsidian 一致。
      `SynthItalic` 给斜体文字加 `skewX`，没有斜体字形的文字（例如中文）也会倾斜。`WikiLinkText` 在渲染后的文本里标出 wiki link，未解析的链接也能点。`MarkdownImage` 的 `LocalImageSourceResolver` 默认返回 null，页面提供它之后才显示图片。
    - `image/LoomaAsyncImage.kt`：用 Coil 3 加载本地 `File` 或远程 URL。
    - `components/`：`LoomaIcon` 和加载指示器。图标只用 `R.drawable.ic_*`，不用 `Icons.*`。
    - `format/Formatting.kt`：格式化文件大小和日期。
- `app/src/main/res/`：`values/strings.xml` 是英文（默认语言），`values-zh/strings.xml` 是中文，两边的 key 一一对应。Key 按区域加前缀，例如 `home_`、`editor_` 和 `settings_`。`xml/locales_config.xml` 列出支持的语言。`drawable/ic_*.xml` 大多由 `tools/fetch_material_symbols.py` 生成。`raw/keep.xml` 防止 release 的资源压缩删掉字体。
- `app/src/debug/`：debug 构建的网络安全配置，只允许对 `10.0.2.2` 和 `localhost` 发明文 HTTP 请求。
- `app/src/test/`：JVM 单测，覆盖 `domain/`、大部分 `data/`、`feature/` 中的纯逻辑和 `ui/theme/`。`S3SignerIntegrationTest.signedPutIsAccepted` 只在设置了环境变量 `S3_TEST_ENDPOINT` 时运行。
- `app/src/androidTest/`：需要设备的测试，覆盖 Room DAO、`VaultIndexer`、重命名后的链接改写（`RenameRelinkTest`）和 wiki link 点击（`WikiLinkTapTest`）。
- `app/build.gradle.kts`：`verifyTypography` 任务挂在 `check` 上。`ui/theme/` 和 `ui/markdown/` 之外的 `.kt` 文件出现 `fontSize =`、`fontWeight =` 或 `TextStyle(` 等写法时，构建失败。字体值只写在 `LoomaTokens` 里。
- `tools/fetch_material_symbols.py`：从 Google Fonts 下载 Material Symbols Rounded 图标，转成 vector drawable。要加图标，在 `ICONS` 里加一行，然后在 `android/` 下运行 `python tools/fetch_material_symbols.py`。只想更新部分图标时，在命令后面写图标名。
- `.agents/skills/verify/`：在一次性的 headless 模拟器上，通过真实 UI 验证 app。`scripts/control.py` 负责启动、点击、断言、截图和拉取 vault 文件。用法见 `SKILL.md`，各功能的操作步骤在 `features/`。

## Dev loop

- 命令都在 `android/` 下运行。Windows 用 `.\gradlew.bat`，其他系统用 `./gradlew`。需要 JDK 25（`gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion`）和 SDK Platform 37。
- `.\gradlew.bat assembleDebug` 构建 debug APK，`.\gradlew.bat installDebug` 装到已连接的设备上。release 也用 debug 密钥签名，`versionName` 是 `0.1.0`。
- 测试：
  - `.\gradlew.bat :app:testDebugUnitTest` 跑 JVM 单测。只跑一个类时加 `--tests "com.example.looma.feature.note.SaveSchedulerTest"`。
  - `.\gradlew.bat connectedDebugAndroidTest` 跑 `app/src/androidTest/`，需要已启动的模拟器或设备。CI 不跑它。
  - `.\gradlew.bat check` 跑 lint 和 `verifyTypography`。
  - CI（`.github/workflows/android-ci.yml`，ubuntu + Temurin 25）依次跑 `:app:assembleDebug`、`:app:testDebugUnitTest` 和 `:app:lintDebug`，只在 `android/**` 或这个 workflow 改动时触发。
- 改了 Room 实体后，构建会重写 `app/schemas/.../1.json`。版本保持 1，不写迁移。装过旧版本的设备，先卸载 app 再装。
- 加图标：在 `tools/fetch_material_symbols.py` 的 `ICONS` 里加一行，再运行 `python tools/fetch_material_symbols.py <名字>`。
- 测 S3 图床时，debug 构建只允许对 `10.0.2.2`（模拟器里的宿主机）和 `localhost` 发明文 HTTP。`S3SignerIntegrationTest.signedPutIsAccepted` 只在设置了 `S3_TEST_ENDPOINT` 时运行。
- 在真实 UI 上验证改动，用 `.agents/skills/verify/`：
  - 它在一次性的 headless 模拟器上跑，AVD 是 `looma36`（API 36，英文）。模拟器以 `-read-only` 启动，关掉后什么都不留。
  - 先构建 APK，再 `python .agents/skills/verify/scripts/control.py --run app/build/verify/<run> launch --avd looma36 --port 5580`。它装 APK，并在 `/sdcard/Documents/<run>` 建一个空 vault。之后按 `features/vault.md` 通过系统设置和文件选择器连上这个 vault。
  - 常用子命令：`doctor [--system]`、`ui`、`tap`/`assert`（`--text`、`--desc`、`--class`、`--id`，精确匹配；tap 还有 `--index`、`--long`、`--at X,Y`）、`text`（只支持 ASCII，不能含 `%`）、`key`、`swipe up|down|left|right`、`toggle '<标签>'`、`relaunch`、`capture <名字>`、`files <名字>` 和 `cleanup`。
  - `fixture <文件> --to pictures` 把图片放进相册，供 Insert image 选择。`fixture <文件> --to vault --replace` 覆盖 vault 里的笔记，模拟别的编辑器改了它，用来测冲突。除此之外，测试数据都要通过 UI 创建。
  - 截图、UI 树、logcat 和拉下来的 vault 都留在 `app/build/verify/<run>/`。别在还要用这些证据时跑 `clean`。
  - 用完一定 `cleanup`。它只关这次 run 启动的模拟器。
  - 这个模拟器在长时间运行中曾经自己退出过一次。遇到 `device not found` 时，先 `cleanup`，再换一个新的 run 名字重新 launch。
- 各功能的操作步骤和已知坑在 `.agents/skills/verify/features/`。app 的界面或流程改了之后，跑 `/maintain-verification-skill` 同步它们。
