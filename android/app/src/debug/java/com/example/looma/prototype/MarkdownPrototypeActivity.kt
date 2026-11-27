package com.example.looma.prototype

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.data.settings.ThemePreset
import com.example.looma.domain.markdown.WikiImageFlavour
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown

class MarkdownPrototypeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialStyle = ProtoStyle.fromKey(intent.getStringExtra("style"))
        val initialView = ProtoView.fromKey(intent.getStringExtra("view"))
        val initialDark = intent.getBooleanExtra("dark", false)

        setContent {
            var style by remember { mutableStateOf(initialStyle) }
            var view by remember { mutableStateOf(initialView) }
            var dark by remember { mutableStateOf(initialDark) }

            LoomaTheme(
                darkTheme = dark,
                dynamicColor = false,
                preset = ThemePreset.Default,
            ) {
                val palette = rememberProtoSurfacePalette(style, dark)
                val bg = if (view == ProtoView.PREVIEW) palette.canvasBg else palette.pageBg

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bg)
                        .safeDrawingPadding(),
                ) {
                    ProtoControlsBar(
                        style = style,
                        view = view,
                        dark = dark,
                        onStyleChange = { style = it },
                        onViewChange = { view = it },
                        onDarkChange = { dark = it },
                    )
                    when (view) {
                        ProtoView.PREVIEW -> ProtoNotePreviewScreen(style = style, dark = dark)
                        ProtoView.HOME -> ProtoHomeScreen(style = style, dark = dark)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoControlsBar(
    style: ProtoStyle,
    view: ProtoView,
    dark: Boolean,
    onStyleChange: (ProtoStyle) -> Unit,
    onViewChange: (ProtoView) -> Unit,
    onDarkChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProtoStyle.entries.forEachIndexed { index, item ->
                    ToggleButton(
                        checked = style == item,
                        onCheckedChange = { if (it) onStyleChange(item) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            ProtoStyle.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProtoView.entries.forEach { item ->
                    ToggleButton(
                        checked = view == item,
                        onCheckedChange = { if (it) onViewChange(item) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(item.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.weight(1f))
                ToggleButton(
                    checked = dark,
                    onCheckedChange = onDarkChange,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(if (dark) "深色" else "浅色", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ProtoNotePreviewScreen(style: ProtoStyle, dark: Boolean) {
    val palette = rememberProtoSurfacePalette(style, dark)
    val theme = rememberProtoMarkdownTheme(style = style, dark = dark, tier = CompactTier.NONE)
    val components = rememberProtoMarkdownComponents(style = style, theme = theme, dark = dark, tier = CompactTier.NONE)
    val parsed = remember { parseMarkdown(content = SAMPLE_NOTE_BODY, flavour = WikiImageFlavour()) }

    Markdown(
        state = parsed,
        annotator = protoAnnotator,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        extendedSpans = theme.extendedSpans,
        components = components,
        modifier = Modifier.fillMaxSize(),
        success = { state, activeComponents, bodyModifier ->
            if (style == ProtoStyle.CURRENT) {
                LazyColumn(
                    modifier = bodyModifier,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    item(key = "title") {
                        Text(
                            text = "Looma Markdown 排版与渲染设计",
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            color = palette.titleColor,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(state.node.children, key = { it.startOffset }, contentType = { it.type }) { node ->
                        MarkdownElement(node, activeComponents, state.content)
                    }
                }
            } else {
                val blocks = state.node.children
                LazyColumn(
                    modifier = bodyModifier,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    item(key = "title") {
                        Text(
                            text = "Looma Markdown 排版与渲染设计",
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            color = palette.titleColor,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }
                    items(blocks.size, key = { blocks[it].startOffset }) { index ->
                        val node = blocks[index]
                        if (index > 0) {
                            Spacer(Modifier.height(blockGapAfter(blocks[index - 1], node, style, CompactTier.NONE)))
                        }
                        MarkdownElement(node, activeComponents, state.content)
                    }
                }
            }
        },
    )
}

@Composable
private fun ProtoHomeScreen(style: ProtoStyle, dark: Boolean) {
    val palette = rememberProtoSurfacePalette(style, dark)
    val recentTheme = rememberProtoMarkdownTheme(style = style, dark = dark, tier = CompactTier.RECENT)
    val recentComponents = rememberProtoMarkdownComponents(style = style, theme = recentTheme, dark = dark, tier = CompactTier.RECENT)
    val summaryTheme = rememberProtoMarkdownTheme(style = style, dark = dark, tier = CompactTier.SUMMARY)
    val summaryComponents = rememberProtoMarkdownComponents(style = style, theme = summaryTheme, dark = dark, tier = CompactTier.SUMMARY)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Recent",
                style = MaterialTheme.typography.titleLarge,
                color = palette.titleColor,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SAMPLE_RECENT_CARDS) { card ->
                    Card(
                        modifier = Modifier.width(168.dp),
                        shape = MaterialTheme.shapes.largeIncreased,
                        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 152.dp)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ProtoCompactMarkdown(
                                markdown = card.markdown,
                                style = style,
                                tier = CompactTier.RECENT,
                                theme = recentTheme,
                                components = recentComponents,
                                fadeColor = palette.cardBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.titleLarge,
                color = palette.titleColor,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProtoSummaryCard(SAMPLE_SUMMARY_CARDS[0], style, palette, summaryTheme, summaryComponents)
                    ProtoSummaryCard(SAMPLE_SUMMARY_CARDS[2], style, palette, summaryTheme, summaryComponents)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProtoSummaryCard(SAMPLE_SUMMARY_CARDS[1], style, palette, summaryTheme, summaryComponents)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtoSummaryCard(
    item: SampleCard,
    style: ProtoStyle,
    palette: ProtoSurfacePalette,
    theme: com.example.looma.ui.markdown.LoomaMarkdownTheme,
    components: MarkdownComponents,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = palette.titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ProtoCompactMarkdown(
                markdown = item.markdown,
                style = style,
                tier = CompactTier.SUMMARY,
                theme = theme,
                components = components,
                fadeColor = palette.cardBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
            )
            if (item.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.tags.forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = palette.cardInsetBg,
                        ) {
                            Text(
                                text = "#$tag",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = LoomaTokens.type.cardMeta,
                                color = palette.mutedColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoCompactMarkdown(
    markdown: String,
    style: ProtoStyle,
    tier: CompactTier,
    theme: com.example.looma.ui.markdown.LoomaMarkdownTheme,
    components: MarkdownComponents,
    fadeColor: Color,
    modifier: Modifier = Modifier,
) {
    val state = remember(markdown) {
        parseMarkdown(content = markdown, flavour = WikiImageFlavour())
    }
    if (state !is State.Success) return
    Box(modifier.clipToBounds()) {
        if (style == ProtoStyle.CURRENT) {
            Markdown(
                state = state,
                annotator = protoAnnotator,
                colors = theme.colors,
                typography = theme.typography,
                padding = theme.padding,
                extendedSpans = theme.extendedSpans,
                components = components,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Markdown(
                state = state,
                annotator = protoAnnotator,
                colors = theme.colors,
                typography = theme.typography,
                padding = theme.padding,
                extendedSpans = theme.extendedSpans,
                components = components,
                modifier = Modifier.fillMaxWidth(),
                success = { readyState, activeComponents, bodyModifier ->
                    val blocks = readyState.node.children
                    Column(modifier = bodyModifier) {
                        blocks.forEachIndexed { index, node ->
                            if (index > 0) {
                                Spacer(Modifier.height(blockGapAfter(blocks[index - 1], node, style, tier)))
                            }
                            MarkdownElement(node, activeComponents, readyState.content)
                        }
                    }
                },
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.72f to Color.Transparent,
                        1f to fadeColor,
                    ),
                ),
        )
    }
}

private data class SampleCard(
    val title: String,
    val markdown: String,
    val tags: List<String> = emptyList(),
)

private val SAMPLE_NOTE_BODY = """
# 一级标题：语义排版基准
Looma 的阅读体验需要在移动端保持克制的留白与清晰的层级。段落中常混排 **加粗重点**、*斜体强调*、`rememberMarkdownState()` 等行内代码，以及参考 [Material Design 3](https://m3.material.io)。

## 二级标题：引述与结构节奏
> 好的笔记排版应当像纸张一样安静，让视线自然落在内容本身，而不是装饰性的边框上。
>
> Good typography honors bilingual rhythm while preserving crisp Latin metrics.

### 三级标题：任务、列表与代码块
- [x] 统一中英文混排行高与段落间距
- [ ] 验证深色模式代码块与 M3E 表格对比度

1. 解析 Markdown AST 并映射至语义化节点
2. 应用当前主题色板与表面层级

```kotlin
// Looma Markdown 渲染配置示例
data class LoomaTypography(
    val lineHeight: Float = 1.64f,
    val letterSpacing: Float = 0.02f
)
```

| 元素类型 | 间距策略 | 视觉重点 |
| :--- | :--- | :--- |
| Heading | 上宽下窄 | 字重与字号递进 |
| Code Block | 圆角容器 | 等宽字体与语法高亮 |
| Table | M3E 规范 | 保持原样不变 |

---

尾部说明：以上包含标题、引述、任务列表、代码块与 M3E 表格。
""".trimIndent()

private val SAMPLE_RECENT_CARDS = listOf(
    SampleCard(
        title = "M3 Expressive 色彩",
        markdown = "### 色彩层级\n使用 `primaryContainer` 承接激活态，保持柔和对比。",
    ),
    SampleCard(
        title = "妙言阅读体验拆解",
        markdown = "> 极简留白与温和灰度，让长文阅读更专注。\n\n- 1.64 行高",
    ),
    SampleCard(
        title = "双周迭代检查",
        markdown = "- [x] 对比度检查\n- [ ] 表格横向滚动\n\n`LoomaTokens`",
    ),
)

private val SAMPLE_SUMMARY_CARDS = listOf(
    SampleCard(
        title = "Looma 排版系统备忘",
        markdown = """
            ### 核心原则
            - 正文行高保持 1.64 以上
            - 行内代码 `surfaceContainer`

            ```kotlin
            val radius = 16.dp
            ```
        """.trimIndent(),
        tags = listOf("design", "markdown"),
    ),
    SampleCard(
        title = "双周迭代检查清单",
        markdown = """
            > 先确保可读性，再微调 Expressive 动效与圆角。

            - [x] 暗色主题对比度
            - [ ] 表格横向滚动

            | 模块 | 状态 |
            | :--- | :--- |
            | Preview | Ready |
        """.trimIndent(),
        tags = listOf("sprint"),
    ),
    SampleCard(
        title = "语法高亮与引用规范",
        markdown = """
            1. 标题紧跟下文内容
            2. 引用条使用圆头细线
            3. 表格保留 M3E 圆角与网格
        """.trimIndent(),
        tags = listOf("typography"),
    ),
)
