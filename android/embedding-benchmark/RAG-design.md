# Looma 的证据检索、Weave 和 Agent

本方案供产品对齐。生产代码从「查到一段原文，点一下打开对应笔记」开始。用户看到的引用应能回到保存版本的真实段落。Weave 推荐可以使用同一份证据，用户确认时把 `[[WikiLink]]` 写进 Markdown。Agent 最后接入这两个能力。

## 调用方式

```kotlin
val hits = vaultEvidence.search(VaultQuery(vaultId, "附件失败后从哪里续传？", limit = 8))
val span = hits.passages.firstOrNull()?.let { vaultEvidence.resolve(it.reference) }
val suggestions = vaultEvidence.related(currentNote, limit = 5)
```

`search` 返回排序后的原文片段和索引覆盖状态。`related` 排除当前笔记、回收站内容和已关联项，聚合为带理由和证据的笔记建议。`resolve` 返回 `Found`、`Changed` 或 `Missing`。一次式 RAG 在同一次请求里检索并验证证据，分配局部引用 ID，再让 `AnswerGenerator` 生成答案。应用校验每个引用 ID 均在本次请求里。证据不足时，界面展示原文候选，并提示无法从已检索证据确认答案。

```kotlin
data class EvidenceRef(
    val note: NoteId,
    val sourceRevision: NoteRevision.Known,
    val span: SourceSpan,
)

data class SourceSpan(val startUtf16: Int, val endExclusiveUtf16: Int)

interface VaultEvidence {
    suspend fun search(query: VaultQuery): EvidenceResults
    suspend fun related(note: NoteId, limit: Int): List<RelatedNote>
    suspend fun resolve(reference: EvidenceRef): EvidenceResolution
}
```

## 所有权与更新

- `domain/markdown` 从现有 AST 提取标题路径、段落、列表、表格和代码块所对应的源范围。块长用模型 token 预算控制，保留章节内必要的相邻句。匹配片段与提交给生成模型的上下文可以有不同范围。规范化后的 embedding 文本不用于定位原文。
- `data/index` 仍由 Room 管理派生索引。Markdown 文件是权威数据。Room 以 `(vaultId, notePath)` 存已观察的版本、代数、删除状态和已发布的 chunk。后台任务取已稳定保存的快照，计算 `embedInputHash` 和向量，再在短事务内按 note、revision 和代数条件发布。旧任务完成或应用重启后，当前目标版本仍可重新发现。重命名、回收和恢复通过现有仓库及扫描变更入口登记。
- 向量由 `(modelFingerprint, embedInputHash)` 唯一标识。指纹覆盖权重、tokenizer、输入模板和维度。`sourceRevision` 来自原文件字节的 SHA-256。编辑改变位置时更新引用范围。送给模型的文本相同就复用向量。推理在保存锁和 Room 事务外运行。
- `data/retrieval` 先做按 Vault 隔离的字面检索和 exact cosine。候选通过后按真实字节 revision 重读少量笔记。外部编辑可保持 size/mtime，因此扫描指纹只用来筛选可能的更新，候选出库和引用点击都重新验证原文。首次建库、模型不可用和旧结果需要刷新时返回可用的词法候选和状态。
- `feature/ask` 消费 `VaultEvidence` 和单独的生成模型接口。`feature/note` 消费 `related` 并通过 `NoteRepository` 安全确认 WikiLink。`WikiGraph` 负责验证链接解析结果，有重名时使用可正确解析的 Vault 相对路径。路径加原文版本定位的是某时刻的快照。稳定的跨外部移动身份需要独立产品设计。

## 生产选型

Room 是管理 SQLite 的接口选择。当前应用使用 Room 2.8.4。首阶段可在当前 Room 上验证 chunk 和向量 BLOB，通过连续、归一化向量做 exact cosine。768 维 Float32 的原始 payload 在 1 万和 5 万个 chunk 时分别约 29.3 和 146.5 MiB。设备规模测试决定是否启用更短的 embedding、向量量化或 ANN。

原型用 FTS5 trigram BM25 做检索对照。先保留字面查找，尤其是短中文、路径、标签、代码标识和标点。真实 Vault 证明需要 FTS5 后，再在 Android 目标 API 上验证 Room 3、BundledSQLiteDriver、派生正文的磁盘开销和事务更新。Room 3 提供 `@Fts5`。TRIGRAM 的全文 `MATCH` 对一至两个字符没有命中，需要单独的字面查找路径。 [Room FTS5 API](https://developer.android.com/reference/androidx/room3/Fts5) · [SQLite FTS5](https://www.sqlite.org/fts5.html#the_trigram_tokenizer)

语义检索优先比较 EmbeddingGemma 2 Text 270M CPU 与轻量 Bekko。MediaPipe 封装在 `EmbeddingProvider` 内。需要区分模型下载体积、运行时 peak RSS、首次索引和编辑时内存。若原 50 MiB 权重、150 MiB RSS 增量门槛继续适用，Gemma 2 暂只作实验候选。生成模型与 embedding 有各自的生命周期。共用预算不足时测试串行加载和释放。

首版分块以 AST 标题和段落为主。固定窗口保留为实验对照，有需要时用于超长节点。语义分块要额外向量化候选段落。Proposition chunking 把正文拆成可独立理解的事实，可能用生成模型改写。应比较它的多证据收益和引用准确率。使用时保存每条命题到原文范围的映射，展示和引用仍取原文。 [Dense X Retrieval](https://aclanthology.org/2024.emnlp-main.845/)

检索基线保留词法和向量两个通道。自然语言问题以向量排名为基准，明确的字面值可以补候选。使用同一组难题对比等权 RRF、字面值门控和候选重排。首版先做重叠片段去重和按笔记限制片段数量。独立 cross-encoder 仅在留出集的证据召回与可归因答案有增益、真机延迟和内存仍在预算内时接入。

`WikiGraph` 先用于处理用户维护的双链。有充足的真实链接题时，单独对照搜索和有上限的显式一跳扩展。GraphRAG 涉及实体抽取、关系图和社区摘要。如果简单检索、人工或自动拆问、显式链接仍持续漏掉跨笔记的证据，再对照 GraphRAG 的质量和重建成本。 [Microsoft GraphRAG 论文](https://arxiv.org/abs/2404.16130)

## 选择与顺序

共享索引方案为 Ask、Weave 和 Agent 定义一份证据契约，承担后台索引与版本校验成本。另一方案先按标题、显式链接和全文查找候选，再从候选笔记切段。后者可更早展示 Weave 建议，适合当作词法回退，遗漏的同义或跨语笔记无法由后续切段补回。选择共享证据契约和分阶段接入。把词法回退独立留在服务内，同期做端侧模型和索引预算验收。

1. **证据检索。** 先交付保存笔记的段落检索、可点击引用、后台增量更新和精确查询回退。测 1 千、1 万、5 万 chunk 的冷建库、增量命中、暖检索、首问、峰值 PSS/RSS 和与编辑自动保存并行时的体验。为留出集分开标注自然语言、精确词、同义词、跨语和多证据问题。
2. **Weave。** 读笔记时显示带原文的关联。用户确认插入 WikiLink，使用现有编辑缓冲和 `Checked` 保存。验证目标重名、重复确认、外部编辑冲突、移动和恢复。若确认时当前编辑有未保存输入，先在当前编辑状态中合并并预览。
3. **一次式 RAG。** 使用同一个检索服务，一次生成带引用的回答。与 Weave 可并行。测试引用支持率、对无法回答问题的响应、隐私范围、生成与检索总延迟。生成模型未选定前，可以先完成检索证据界面。
4. **Agent。** 案例先限定为「为了查清一个问题多次查找、读取证据并回答」。提供有调用上限和超时的只读工具。按当前 Vault 权限、用户问题及工具输入限制作用域，笔记内容只作数据。对照固定检索、固定拆问和 Agent 工具循环的证据收益、耗时、失败率及调用数。写操作再单独拓展为单文件 diff、用户确认和 `Checked(baseRevision)` 提交。

当前等待确认生成模型的隐私策略。默认建议只在设备内处理，云端生成需要用户明确启用并看到发送范围。Weave 先交付是因为它可复用证据检索与现有写入流程。若生成已选型且问答交付优先，二者可以交换或并行。

## 重跑检索原型

从 `android/` 在 Nushell 执行。设备需在 ADB 列表中，宿主 Python 的 SQLite 需支持 FTS5 trigram。离线运行需事先缓存模型。

```nu
^./gradlew.bat :embedding-benchmark:rag --offline -PembeddingModel=gemma-cpu '-PembeddingSerial=<设备序列号>'
^./gradlew.bat :embedding-benchmark:rag --offline -PembeddingModel=bekko '-PembeddingSerial=<设备序列号>'
```

报告写到 `embedding-benchmark/build/reports/rag-*.json`。Android 负责模型推理，宿主 Python 负责分块、向量排名和 SQLite FTS5 对照。这是检索原型，生产 Android 索引、生成、GraphRAG 和 Agent 尚需独立验收。

## 本次结果

41 篇人工编写的 Markdown 笔记。原有 32 问用于整体质量对比，另有四道多来源题和四道精确字面题。Gemma 和 Bekko 在同一个 API 36 x86_64 模拟器上生成向量。证据 Recall@5 统计前五篇不同笔记的最佳片段中能找到多少完整标注原文。

| 分块，Gemma CPU | 块数 | 笔记 Recall@5 | 证据 Recall@5 |
| --- | ---: | ---: | ---: |
| 只取开头 | 41 | 87.0% | 39.6% |
| 固定窗口 | 177 | 97.4% | 57.6% |
| 章节内窗口 | 218 | 98.2% | 55.2% |
| 段落合并 | 236 | 98.2% | 57.0% |
| 相邻段落语义阈值 0.60 | 236 | 98.2% | 57.0% |
| 相邻段落语义阈值 0.85 | 245 | 98.2% | 53.1% |

- 两个语义阈值共用 332 个段落候选的边界向量。0.60 产生与结构分块同样的 Gemma 片段。0.85 增加九个片段，证据召回下降。固定窗口与段落方案的质量差距很小。既有最长笔记段落编辑测试中，固定窗口有 14 个新 embedding 输入，章节内窗口只有 1 个。
- 章节内窗口上，Gemma 和 Bekko 的笔记 Recall@5 为 98.2% 和 69.3%，证据 Recall@5 为 55.2% 和 34.9%。Gemma 的向量、trigram BM25、等权 RRF、RRF 后用 cosine 重排的证据 Recall@5 依次是 55.2%、14.6%、37.0%、47.4%。
- 章节内窗口的四道字面题，Gemma 纯向量有三道相关笔记排第一，显式字面值命中前置后有四道。数据量只够确认这四个用例。四道多来源题中，人工拆问把证据 Recall@5 从 75.0% 提升至 87.5%，找到所有所需笔记的题仍是三道。固定窗口的同一项从一道到两道。拆问文本由人写，结果用于验证查询拆解的可能收益。
- 模拟器系统 SQLite 3.44.3 的命令行拒绝 `CREATE VIRTUAL TABLE ... USING fts5`。宿主 SQLite 3.53.4 能建表，`MATCH '指纹'` 为零，`MATCH '文件指纹'` 与字面 `LIKE '%指纹%'` 均命中。Android 应用中的 bundled driver、Room schema 和索引性能仍需验证。

原型只从每篇笔记返回一段证据，因此结果同时反映分块、排序和证据聚合。跨章节扩段、多证据引用以及真实笔记上的查全率需另测。
