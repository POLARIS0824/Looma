# Looma UI 设计规范

Looma 的界面遵循 Material 3 Expressive。代码基于 Jetpack Compose 1.13.0（`compose-bom` `2026.11.01`）和 `androidx.compose.material3` 1.5.0 正式版，规范来源是 [m3.material.io](https://m3.material.io/)。标注“Looma 决定”的条目不来自 M3。修改这些条目时，同时更新本文。

## 按顺序查找设计值

确定颜色、字体、圆角、间距或动效时，按下面的顺序查找，在第一个有答案的地方停下。

1. **M3 组件自带这个值。** 使用组件默认值，不传参数。例如 `Card`、`AlertDialog` 和 `SegmentedListItem` 的形状、内边距和颜色。
2. **M3 有对应的系统 token。** 使用 `MaterialTheme.colorScheme`、`MaterialTheme.typography`、`MaterialTheme.shapes` 或 `MaterialTheme.motionScheme`。
3. **本文的表格里有这个值。** 使用表格里的值。
4. **以上都没有。** 在 Google Keep 或 Google Files 里找到相同角色的界面，按截图量出数值，取 4dp 网格上最近的值。把数值和参考来源加进对应的表格。

M3 只定义组件内部的间距。页面级的间距由各个 app 决定，所以第 3 步和第 4 步的数值都是 Looma 决定。

## 每个页面只突出一个主任务

Expressive 不是让所有元素都更醒目。先确定页面的主任务，再把尺寸、颜色、形状和动效集中用在主任务上。次要内容靠容器和字体区分，其余内容保持安静。所有元素同样醒目时，页面就没有重点。

下表是 Looma 决定。

| 页面                     | 主任务                     | 强调方式                                                                     |
|--------------------------|----------------------------|------------------------------------------------------------------------------|
| 首页                     | 找到并打开笔记             | 笔记卡片占据页面主体。`FloatingActionButtonMenu` 是页面上唯一的高强调操作    |
| 笔记                     | 读写正文                   | 正文占据页面主体。顶栏和 `NoteFormatBar` 使用 surface 色，不和正文争夺注意力 |
| 回收站、设置、选择 vault | 完成一个操作或修改一个选项 | 只用组件自带的效果                                                           |

- 自定义的 Expressive 效果只用在首页和笔记页。自定义效果包括形状变形、Emphasized 字体之外的字体轴变化和自定义动画。
- 每个页面最多一个 FAB，只放整个页面的主操作。
- 填充按钮只给主操作。次要操作用 tonal 按钮或 text 按钮。
- 选中状态同时改变形状和颜色。传入组件的 `shapes` 参数，例如 `ButtonDefaults.shapes()`、`ButtonDefaults.shapesFor(height)`、`ToggleButtonDefaults.shapesFor(height)` 和 `IconButtonDefaults.shapes()`，让按钮、图标按钮和列表项在按下和选中时变形。

## 颜色

- 默认使用 Dynamic Color。API 31 以下或用户关闭 Dynamic Color 时，`LoomaTheme` 使用 `PresetSchemes.kt` 里的预设。预设由 `tools/color/generate_preset_schemes.mjs` 用 `SchemeTonalSpot` 生成。不要手改 `PresetSchemes.kt`。
- 只使用成对的颜色角色。`X` 上的文字和图标用 `onX`。surface 上的文字用 `onSurface` 或 `onSurfaceVariant`。容器色只做填充，不做文字色。
- 降低强调时，换成更安静的角色，例如 `onSurfaceVariant`。不用 `copy(alpha = ...)`，不写十六进制颜色。
- 强调色按重要程度分配。`primary` 给主操作，`secondary` 和 `secondaryContainer` 给选中状态和辅助控件，`tertiary` 给状态提示。到处使用 `primary` 会抹平层次。
- 页面的大部分面积是 surface。用 `surfaceContainerLowest` 到 `surfaceContainerHighest` 五个级别表达层次。
- 分割线用 `outlineVariant`。只有不带容器的列表才使用分割线。带容器的分组之间用间距分开。

## 字体

- 所有字体角色都使用 Google Sans Flex。`Type.kt` 为它设置了两组轴：
  - Display 和 Headline 使用 `wdth` 90 和 `ROND` 80。
  - Title、Body 和 Label 使用 `wdth` 100 和 `ROND` 0。
  - 两组都让 `opsz` 等于字号。
- 页面直接使用 `MaterialTheme.typography` 的角色。一个页面大约使用五种样式，样式之间的差别要明显。
- Emphasized 样式（例如 `titleMediumEmphasized`）和对应的基础样式字号相同，字重更高。Emphasized 样式用在选中项、关键数字和标题上，和基础样式搭配使用。
- 自定义样式必须从一个 M3 角色派生。可以改行高、字重、`fontFeatureSettings` 和字体族，不改字号。
- 会变化的数字加 `tnum`，例如计数和卡片元数据。
- 所有界面文字使用 sentence case。
- `verifyTypography` 任务在 `ui/theme/` 和 `ui/markdown/` 之外发现 `fontSize =`、`fontWeight =` 或 `TextStyle(` 时让构建失败。

## Token

- 页面直接使用 M3 token，例如 `MaterialTheme.typography.titleLarge` 和 `MaterialTheme.colorScheme.onSurfaceVariant`。
- `LoomaTokens` 只放 M3 没有的角色：
  - 浅色和深色主题取不同 surface 级别的颜色，即 `page`、`noteCanvas`、`card`、`cardInset`、`sheet` 和 `searchField`。
  - 从 M3 角色派生、改过行高、字重或字体特性的样式，即 `heroTitle`、`noteBody`、`noteCode`、`noteHeadings`、`homeNotePreview`、`count` 和 `cardMeta`。
- 和 M3 角色一一对应的别名要删掉，调用处改回 M3 名字。别名包括：
  - 颜色：`title`、`body`、`muted`、`accent`、`accentContainer`、`avatarRing`、`chip`、`onChip`、`divider` 和 `error`。
  - 字体：`pageTitle`、`sectionTitle`、`cardTitle`、`cardBody`、`folderName`、`body`、`supporting`、`avatar` 和 `noteTitle`。

## 布局

### 布局数值

| 位置                         | 数值                                                  | 来源                                                                     |
|------------------------------|-------------------------------------------------------|--------------------------------------------------------------------------|
| 页边距                       | 紧凑窗口 16dp，中等及以上窗口 24dp                    | M3 布局规范                                                              |
| 首页和文件夹的笔记网格页边距 | 紧凑窗口 8dp，中等及以上窗口 16dp，`noteGridMargin()` | Looma 决定。笔记卡片占据页面主体，窄边距让双列卡片更宽，参考 Google Keep |
| 页面分组标题                 | `titleLarge`，颜色 `onSurface`，带 `heading()` 语义   | Looma 决定，参考 Google Files                                            |
| 分组标题到下方内容           | 16dp 起，内容较重时可到 24dp                          | Looma 决定，参考 Google Files                                            |
| 分组之间                     | 24dp 到 32dp                                          | Looma 决定，参考 Google Files                                            |
| 卡片之间、网格项之间         | 8dp                                                   | M3 卡片规范，卡片间距最多 8dp                                            |
| 分段列表项之间               | `ListItemDefaults.SegmentedGap`                       | M3 组件默认值                                                            |
| 单栏内容的最大宽度           | 640dp                                                 | Looma 决定。`bodyLarge` 下一行约 40 个汉字                               |

- 间距写在父布局上，即 `Arrangement.spacedBy` 和 `contentPadding`。不在子元素上加外边距。
- 表里的范围是起点。同一个页面里，同一种关系使用同一个值。关系越近，间距越小，所以分组标题到内容的间距小于分组之间的间距。
- M3 组件有内部间距时，不覆盖它。

### 窗口尺寸

M3 的断点是 600dp 和 840dp。宽度小于 600dp 是紧凑窗口，600dp 到 840dp 是中等窗口，840dp 及以上是展开窗口。

- 中等及以上窗口不能只把紧凑布局拉宽。首页增加网格的列数。笔记、设置和回收站的单栏内容按 640dp 限宽并居中。
- 导航在紧凑窗口用 `ModalWideNavigationRail`，在中等及以上窗口用 `WideNavigationRail`。展开和收起状态由 `rememberWideNavigationRailState()` 管理，`WideNavigationRailItem` 显式传入 `railExpanded`。
- 背景、图片和滚动内容画到系统栏后面。文字和控件留在 inset 以内。
- 每个页面都支持横屏、窗口缩放、浅色主题和深色主题。不锁定屏幕方向或主题。

## 形状

M3 Expressive 的圆角刻度如下。加粗的是 1.5.0 新增的级别。

| token                     | 圆角     |
|---------------------------|----------|
| `extraSmall`              | 4dp      |
| `small`                   | 8dp      |
| `medium`                  | 12dp     |
| `large`                   | 16dp     |
| **`largeIncreased`**      | 20dp     |
| `extraLarge`              | 28dp     |
| **`extraLargeIncreased`** | 32dp     |
| **`extraExtraLarge`**     | 48dp     |
| `CircleShape`             | 完全圆角 |

- 优先使用 M3 组件的默认形状，不传 `shape`。卡片用 `Card`，对话框用 `AlertDialog`，分组列表用 `SegmentedListItem`。
- 首页的笔记卡片、文件夹卡片、最近笔记卡片和回收站的笔记卡片使用 `Card(shape = MaterialTheme.shapes.largeIncreased)`。同一种内容的卡片圆角一致。这一条是 Looma 决定，保留现在 20dp 的外观。
- 自定义容器从上表选一级。不写 `RoundedCornerShape(xx.dp)`。
- 嵌套容器的内层圆角小于外层。M3 的公式是“内层圆角等于外层圆角减去内边距”，可以按视觉效果微调。
- 卡片和列表这类信息密集的容器不用完全圆角。
- `MaterialShapes` 的 35 个抽象形状只用在头像、图片和装饰图形上，不用在装正文的容器上。`MaterialShapes` 及其 `toShape()`、`toPath()`、`Morph.toPath()` 在 1.5.0 中仍需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`。多边形和变形动画也可以直接使用 Compose Foundation 1.13.0 的 `PolygonShape` 和 `MorphPolygonShape`。

## 动效

- `LoomaTheme` 使用 `MotionScheme.expressive()`，调用处通过 `MaterialTheme.motionScheme` 读取（`LocalMotionScheme` 已在 1.5.0 移除）。
- 自定义动画使用 `MaterialTheme.motionScheme` 提供的 spec，不用 `tween()`。
- 位置、尺寸、圆角和变形进度使用 `fastSpatialSpec()`、`defaultSpatialSpec()` 或 `slowSpatialSpec()`，允许回弹。颜色和透明度使用 `fastEffectsSpec()`、`defaultEffectsSpec()` 或 `slowEffectsSpec()`，不回弹。
- 小控件使用 `fast*Spec()`，sheet 和局部区域使用 `default*Spec()`，整页使用 `slow*Spec()`。
- 组合转场使用 `togetherWith`（Compose Animation 1.13.0 已移除 `EnterTransition.with`）。退出时可立即销毁重型子树的场景使用 1.13.0 新增的 `CapturedAnimatedVisibility`。
- 顶层页面之间切换时先淡出再淡入，不用横向滑动。进入下一层级和返回时，使用平台默认的动画。

## 组件

- 优先直接使用 M3 组件。只是重复设置几个参数时，不包一层 wrapper。
- 只有语义、行为和样式都已经稳定一致时，才把一个 UI 提取成共享组件，放进 `ui/components/`。一个 UI 出现在两个页面不是提取的理由。
- 共享组件封装的是重复的逻辑或 Looma 特有的结构，不是 M3 组件的外观。
- 顶栏最多放一到两个关键操作。其余操作放进溢出菜单。
- 顶栏标题默认靠起始边对齐。返回上一层级用向上箭头图标。
- 列表项不加向右的箭头，分组之间用间距代替分割线。
- 图标只使用 Material Symbols Rounded。通过 `LoomaIcon` 和 `R.drawable.ic_*` 使用，不用 `Icons.*`。

### 1.5.0 与 1.13.0 的组件和 API 选择

下表左列的写法在 Compose 1.13.0 和 Material 3 1.5.0 中已废弃或属于旧式组件。新代码使用右列。

| 不用                                                                              | 改用（M3 1.5.0 / Compose 1.13.0）                                                                                                                                                                                |
|-----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MediumTopAppBar`、`LargeTopAppBar`                                               | `MediumFlexibleTopAppBar`、`LargeFlexibleTopAppBar`                                                                                                                                                              |
| 没有理由的 `CenterAlignedTopAppBar`                                               | `TopAppBar`                                                                                                                                                                                                      |
| 手动重置 `scrollBehavior.state.heightOffset`                                      | `TopAppBarDefaults.pinnedScrollBehavior(scrollableState)` 或 `enterAlwaysScrollBehavior(scrollableState)`，直接传入 `LazyListState` 或 `ScrollState`                                                             |
| `ModalNavigationDrawer`                                                           | 紧凑窗口用 `ModalWideNavigationRail`，中等及以上窗口用 `WideNavigationRail`                                                                                                                                      |
| 放在 `Card` 里、用分割线隔开的 `ListItem`                                         | `SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, count))`，间距用 `ListItemDefaults.SegmentedGap`                                                                                             |
| `ListItem(headlineContent = { ... })`                                             | 1.5.0 的尾随 lambda 重载 `ListItem(...) { Text(...) }`（`headlineContent`、`ListItemDefaults.shape` 和 `ListItemDefaults.Elevation` 已废弃，改用 `ListItemDefaults.shapes()` 和 `ListItemDefaults.elevation()`） |
| `SingleChoiceSegmentedButtonRow`                                                  | `ToggleButton`、`FilledTonalToggleButton` 或 `OutlinedToggleButton`，配 `ButtonGroupDefaults.connected*ButtonShapes()`                                                                                           |
| `ToggleButtonDefaults.shapes`                                                     | `ToggleButtonDefaults.shapesFor(height)`                                                                                                                                                                         |
| `Button(shape = ...)`                                                             | `Button(shapes = ButtonDefaults.shapes())` 或 `ButtonDefaults.shapesFor(height)`                                                                                                                                 |
| 按钮加下拉箭头拼成的组合                                                          | `SplitButtonLayout` 配 `SplitButtonDefaults.LeadingButton` 和 `SplitButtonDefaults.TrailingButton`                                                                                                               |
| 速拨 FAB、`SmallFloatingActionButton`                                             | `FloatingActionButtonMenu` 加 `ToggleFloatingActionButton`；单个大号 FAB 用 `MediumFloatingActionButton`（80dp）或 `LargeFloatingActionButton`（96dp）                                                           |
| `BottomAppBar`                                                                    | `HorizontalFloatingToolbar` 或 `FlexibleBottomAppBar`                                                                                                                                                            |
| 带有单选或多选状态的 `DropdownMenuItem`                                           | `DropdownMenuPopup` 加 `DropdownMenuGroup`，菜单项用 `SelectableDropdownMenuItem` 或 `CheckableDropdownMenuItem`                                                                                                 |
| 不定进度的 `CircularProgressIndicator`                                            | `LoadingIndicator` 或 `CircularWavyProgressIndicator`                                                                                                                                                            |
| `LinearProgressIndicator`                                                         | `LinearWavyProgressIndicator`                                                                                                                                                                                    |
| `SearchBar(query, onQueryChange, ...)`                                            | `SearchBar(state, inputField)`，展开态在紧凑窗口用 `ExpandedFullScreenSearchBar` 或 `ExpandedFullScreenContainedSearchBar`，在宽窗口用 `ExpandedDockedSearchBarWithGap`                                          |
| `BasicTextField(value, onValueChange)`、`OutlinedTextField(value, onValueChange)` | 基于 `TextFieldState` 的重载；密钥输入框（例如 S3 密钥）用 `OutlinedSecureTextField(state)`                                                                                                                      |
| `Slider(value, onValueChange)`                                                    | 基于 `SliderState` 的重载 `Slider(state, onValueChange)`                                                                                                                                                         |

### 1.5.0 的实验性注解边界

Material 3 1.5.0 已将绝大部分 Expressive API 转为稳定版。不要在文件顶部习惯性添加 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` 或 `@OptIn(ExperimentalMaterial3Api::class)`。

- **已稳定，禁止加 `@OptIn`：** `MaterialExpressiveTheme`、`MotionScheme`、`expressiveLightColorScheme()`、`Typography.*Emphasized`、`Shapes.largeIncreased` / `extraLargeIncreased` / `extraExtraLarge`、`TopAppBar`、`MediumFlexibleTopAppBar`、`LargeFlexibleTopAppBar`、`TwoRowsTopAppBar`、`TopAppBarScrollBehavior`、`BottomAppBar`、`FlexibleBottomAppBar`、`HorizontalFloatingToolbar`、`WideNavigationRail`、`ModalWideNavigationRail`、`ShortNavigationBar`、`SegmentedListItem`、`ButtonGroup`、`ToggleButton` 系列、`SplitButtonLayout`、`FloatingActionButtonMenu`、`ToggleFloatingActionButton`、`LinearWavyProgressIndicator`、`CircularWavyProgressIndicator`、`SearchBar(state, inputField)`、`SearchBarState`、`ExpandedFullScreenSearchBar`、`DropdownMenuPopup`、`DropdownMenuGroup`、`SelectableDropdownMenuItem`、`CheckableDropdownMenuItem`、`BasicAlertDialog`。
- **仍需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`：** `LoadingIndicator`、`ContainedLoadingIndicator`、`LoadingIndicatorDefaults`、`PullToRefreshDefaults.LoadingIndicator`、`MaterialShapes` 及其 `toShape()`、`toPath()`、`Morph.toPath()`。
- **仍需 `@OptIn(ExperimentalMaterial3Api::class)`：** `ModalBottomSheet`、`AppBarWithSearch`、静态 `Sheet`。

### 共享组件的取舍

| 候选                                     | 处理   | 原因                                                                                                                                                                            |
|------------------------------------------|--------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 延迟显示，即 `rememberDelayedVisibility` | 提取   | `DelayedContent` 和 `DelayedLoadingIndicator` 各写了一份相同的计时和显示逻辑                                                                                                    |
| 错误横幅                                 | 提取   | 3 处的语义都是“一段出错说明加一个操作”。M3 没有横幅组件                                                                                                                         |
| 整页的加载、空和错误状态                 | 提取   | 5 处的语义相同，布局应该相同                                                                                                                                                    |
| 分组标题，即 `SectionHeader`             | 提取   | 4 处的语义相同。字体、颜色、`heading()` 语义、标题到内容的间距和分组之间的间距都定义在 `SectionHeader` 和 `SectionHeaderDefaults` 里。父布局已有间距时，用 `parentSpacing` 扣除 |
| `LoomaLoadingIndicator`                  | 删除   | 它只给 `LoadingIndicator` 设置了尺寸和颜色，属于上面禁止的 wrapper。调用处改用 `DelayedLoadingIndicator` 或 `LoadingIndicator`                                                  |
| 卡片、顶栏、确认对话框、开关行           | 不提取 | 直接使用 M3 组件                                                                                                                                                                |

## 加载状态

按下面三个问题选择指示器。

1. **能不能知道进度？** 能知道进度时，用确定进度的 `LinearWavyProgressIndicator`，例如模型下载。不知道进度、预计 5 秒内完成时，用 `LoadingIndicator`。不知道进度、可能超过 5 秒时，用不定进度的 `LinearWavyProgressIndicator`。1.5.0 的 `LinearWavyProgressIndicator` 在进度为 `Float.NaN` 时自动显示不定进度动画，数值就绪后平滑过渡到确定进度。
2. **指示器放在哪里？** 整页加载时，放在页面中间。按钮或卡片里的加载，放在那个容器的中间。加载更多内容时，放在新内容将要出现的位置，不盖住已有内容。
3. **操作由谁触发？** 用户点击按钮触发的操作，立即禁用按钮，表示点击已生效。后台读取和搜索不需要立即反馈。

M3 的规则如下：

- 等待时间短于 200ms 时，不显示指示器。
- `LoadingIndicator` 只用于 200ms 到 5 秒的等待。更长的等待使用进度指示器。

Looma 决定如下：

- 通常很快完成的操作延迟 300ms 再显示指示器，常量是 `DelayedFeedbackMillis`。这类操作包括读取本地文件、搜索和按钮里的短操作。
- 确定进度的长任务不延迟。
- 输入搜索词时，用 `DelayedContent` 延迟显示“无结果”提示，避免打字过程中闪出提示。
- 所有加载反馈都经过 `rememberDelayedVisibility`，包括整页加载、按钮里的加载、笔记阅读视图的加载和 Markdown 图片的占位块。占位块先保留尺寸，300ms 后才淡入底色。

## 无障碍

- 触摸目标至少 48dp，相邻目标至少间隔 8dp。
- 支持 200% 字体缩放。文字变大时，内边距、图标和控件尺寸不变，布局重新排列。
- 只有图标的按钮要写 `contentDescription`，内容说明用途而不是外观。开关和 FAB 菜单要写 `stateDescription`。
- 状态不能只靠颜色或动画表达，要同时配图标或文字。
- 小字的对比度至少 4.5:1，大字、图标和控件至少 3:1。`PalettesTest` 检查预设配色中各角色配对的对比度。
- 焦点环由 1.5.0 新增的 `material3-ripple` 自动渲染为内嵌焦点环（inset focus ring），不要用半透明遮罩覆盖焦点状态。

## 静态检查

- 新的检查先只报警告，不让构建失败。
- 一条规则的现存违规清零、并且没有误报之后，才升级为错误。
- 第一批警告：
  - feature 代码里出现 `RoundedCornerShape(` 加 dp 字面量。
  - feature 代码里直接调用 `LoadingIndicator(`，没有经过 `DelayedLoadingIndicator`。
  - feature 代码里出现 `tween(`。
  - 任何代码里使用“1.5.0 与 1.13.0 的组件和 API 选择”表左列的组件或废弃重载。
- 现有的 `verifyTypography` 保持不变。

## 现有代码的偏差

下表列出现有代码中违反本文的位置。修好一项，就删掉一行。

