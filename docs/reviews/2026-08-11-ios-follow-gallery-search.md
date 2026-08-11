# Code Review · /ios-follow Stage 5 — Gallery Search 全量对齐（iOS）

> 审查对象：`feat/ios-gallery-search` worktree 相对 main 的未提交改动
> 工作目录：`/Users/guoshuai/AndroidStudioProjects/polang/.worktrees/ios-gallery-search`
> 审查日期：2026-08-11　·　审查 agent：Stage 5 gap-analysis（只读）
> 契约 SSOT：`tmp/ios-follow/gallery-search/contracts.md`（1255 行）
> 改动规模：16 文件改动（+2331/-321）、20 个新建 Swift 搜索引擎文件（4350 行）、2 个 shared iosMain 新建 + 11 个测试文件（2623 行，174 test func）

---

## 裁决：✅ **PASS**

未发现阻塞级（🔴）问题。实现与契约逐条对齐，红线全部满足，两个有意偏差（冬天跨年 R5 / 近 N 个月清洗 R4）已正确登记为 `allowed_differences.search_engine_edge_cases`。下列 🟡 建议均为已知技术债（辅助表写入侧缺口、辅助表 JOIN 无数据），已在契约 §14 R9 与 spec platform_differences.search 中登记，不构成阻塞。

---

## ✅ 确认做对的关键项（抽查通过的契约常量 / 行为清单）

### 数据格式 & 核心常量（契约 §2.4 / §5.1 / §5.5 / §8 / §2.5）
| 契约常量 | 期望值 | Swift 实现 | 位置 |
|---|---|---|---|
| SQL_SCORE_WEIGHT | 0.25 | `0.25` ✅ | MediaSearchEngine.swift:120 |
| SEMANTIC_SCORE_WEIGHT | 0.65 | `0.65` ✅ | MediaSearchEngine.swift:122 |
| TIME_SCORE_WEIGHT | 0.1 | `0.1` ✅ | MediaSearchEngine.swift:124 |
| TIME_BOOST_RECENT_DAYS / 值 | 30 / 0.3 | `30` / `0.3` ✅ | :128/132 |
| TIME_BOOST_YEAR_DAYS / 值 | 365 / 0.15 | `365` / `0.15` ✅ | :130/134 |
| MS_PER_DAY | 86400000 | `86_400_000` ✅ | :126 |
| LIKE_BONUS / DISLIKE_PENALTY | 0.15 / 0.15 | `0.15` / `0.15` ✅ | MediaFeedbackUseCase.swift:16-18 |
| MIN_SIMILARITY | 0.22 | `0.22` ✅ | SemanticSearchEngine.swift:37 |
| defaultTopK | 50 | `50` ✅ | SemanticSearchEngine.swift:39 |
| LRU MAX_CACHE_SIZE | 64 | `64` ✅ | MediaSearchEngine.swift:33 |
| EMBEDDING_DIM | 512 | `512` ✅ | SemanticEmbeddingCodec.swift:19 |

### SemanticEmbeddingCodec（契约 §5.5 / R6）✅
- 编码：`value.bitPattern.bigEndian` → 大端 4 字节 → Base64（无换行），维度校验 512。
- 解码：Base64 → 字节数 %4 !=0 拒绝 → float 数 !=512 拒绝 → 大端组 int → `Float(bitPattern:)`。
- **写入侧已接线**：Pass1Pipeline.floatArrayToBase64 改用 `SemanticEmbeddingCodec.encode`（此前为原生小端内存拷贝，本 diff 修复为契约大端格式）。
- **R6 往返单测存在**：SemanticSearchEngineTests.swift:184-206（encode→decode→值相等 + 非法输入拒绝）。

### 三层混合检索编排（契约 §2.2 / §2.3）✅
- Layer 0.5 短路：`hasNarrowingExplicit`（TIME/LOCATION 段）且 pipeline 注入 → 非空直接返回 ✅
- Layer 1：QueryParser.parse + `!needsLlm` → SQL ∥ 语义（topK=50）→ mergeAndRank ✅
- 兜底：cachedExpandForSearch 候选逐词 searchAll ∥ 语义 → mergeAndRank ✅
- filter 入口：personName 非空 → 关闭语义召回（`enableSemanticForFilter`）✅
- 语义候选集 §5.4：filter.keywords 故意忽略；时间/人脸/OCR/地点逐维交集 ✅

### QueryParser 时间词（契约 §3.2，16 条分支）✅
- 相对年+月、绝对年+月/整年、独立中文月份、相对年+季节（冬天跨年修正 **R5 有意偏差**）、去年/今年/前年、夏天、春天/秋天/冬天、上个月、本周/上周、前天/昨天/今天、近半年/近一年/近两年/近三年、近 N 个月（1..99）、近[中文数字]个月 — 全覆盖。
- 中文数字映射（一二三…十 / 十一 / 十二）正确。
- **R4 有意偏差**：removeTimeWords 数字月份分支用正确 `\d{1,2}`（修复 Android raw-string 转义失效）。
- monthEndMs = 下月 1 日 - 1ms = 23:59:59.999；本周/昨天等 endMs 不置 999（R12 对齐）。

### QuerySegmenter（契约 §3.3 / §14 R2）✅
- 词典匹配顺序以代码为准：停用词 → SCENE → LOCATION → **PERSON** → OBJECT → ACTIVITY → OCR（R2 修正 KDoc 与代码不一致）。
- TIME_PATTERN 锚定 `^`，交替项逐字照抄。
- toFilters：TIME 段拼接走 parseTimeRange；personKeywords 剔除 PERSON_GENERIC_TRIGGERS 后并入 content.keywords ✅

### 词表 / 词典全量核对（§3.5-3.10 / §5.6-5.8 / §5.7 / §7.2）✅ 零偏差
经子 agent 逐条核对（10 大类、约 350 条词目）：
- SearchSynonyms 18 keys ✅ · LOCATION 71 ✅ · PERSON_GENERIC_TRIGGERS 14 ✅ · isPeopleSearch 25 ✅
- QuerySegmenter 词典 PERSON 25 / SCENE 13 / OBJECT 19 / OCR 15 / ACTIVITY 16 ✅
- CLIP_QUERY_EXPANSIONS 26 条 ✅ · KinshipLexicon 38 条 + queryPredicatesFor 族扩展 + scan 去重 ✅
- translator 停用词 29 ✅ · isTranslationValid 7 规则 ✅
- TagTranslator/BilingualVocab/ControlledVocab 加载器 + 方向逻辑（en lowercase / zh 原文）✅
- OPUS-MT 词表-only 降级：模型未下载 → translateForClip 返回原查询 → isTranslationValid 拒绝 → 硬编码扩展 + 同义词兜底，无崩溃面 ✅

### MobileCLIP Tokenizer / Text Encoder（契约 §5.3）✅ 零偏差
- BOS 49406 / EOS 49407 / PAD 0 / context 77 ✅
- normalize：NFC → trim → lowercase → 合并空白 ✅
- pre-tokenize 正则：`'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+`（**`[\p{N}]` 单字符而非 `[\p{N}]+`**）✅
- bytes_to_unicode（GPT-2）✅ · `</w>` 词尾（CLIP 风格，merge 前附加）✅ · 贪心 rank 合并 ✅
- [BOS]+tokens+[EOS] → 截断保留末尾 EOS（前 76+EOS）→ PAD 补齐 ✅
- text_model.onnx 输入 `input_ids` → 输出 `text_embeds`；NaN/Inf 拒绝 + 零向量拒绝 + 强制 L2 归一化 ✅

### SQL 数据层（契约 §4.3/§4.4/§4.5/§8）✅
- 全部 DAO SQL 逐字复现（LIKE `'%'||kw||'%'`、BETWEEN 双端含、ORDER BY captureDate DESC、DISTINCT、HAVING 共现）。
- `IN (:ids)` 分批 500（SQLite 变量上限 999 留余量）✅
- LIKE 不转义 `%`/`_`（R8 对齐）、不加 COLLATE NOCASE ✅
- feedback query_text 精确等值（R10）✅ · media_id 为 TEXT ✅
- 六张辅助表 schema 已建（tags/media_tag_cross_ref/ocr_words/ocr_word_occurrences/location_hierarchy/media_locations/media_feedback）✅
- `queue.sync` 串行化，每个方法扁平单层，**无 queue.sync 重入死锁风险**（SQLite C API 调用不回调本类 queue.sync）✅

### Chat 搜索链路（契约 §9）✅
- supportedCommands 五命令（search_media/refine_media_search/feedback/more/exclude）+ @Tool 描述逐字 ✅
- onSearchMedia：intent→filter / query→全文；结果只保留 PHOTO；命中存 lastSearchAssets + query 快照 ✅
- onRefineMediaSearch：无 prior → fresh 全局搜；intent→in-set 精确交集；字符串→cleanConstraint+resolveRefine（filterInSet 优先）；**refined 空→保留上一轮不变** ✅
- feedback：resolveTarget（LastShown/Ordinal 1-based/MediaId/Description）→ recordFeedback（dbId.toString, action.lowercase, lastRoundQuery）✅
- more：tags 前 3 拼 constraint → executeRefine，isRefinement 强制 false ✅
- exclude：内存态 setExcludes，立即过滤当前结果，换 session 失效 ✅
- MAX_CARDS=20 / MAX_MORE_TAGS=3 ✅ · SearchIntent→SearchFilter 含 sanitizeTimeKeywords（timeOnlyKeywords 41 词 + monthKeywordRegex）✅
- 桥 completion 必被调用（suspendCancellableCoroutine），异常不逃逸 K/N 边界 ✅

### UI（spec gallery-grid.yaml search_top_bar/search_results/search_no_result）✅
- SearchTopBar：引用 SearchFieldTokens 全套 + TopBarTokens + AppAlpha；autofocus；singleLine ✅
- GalleryViewModel：防抖 300ms（AppMotion.searchDebounceMs）；空白→清空结果不搜索；首次无旧结果→全屏 Loading；Task.cancel 语义正确 ✅
- GalleryGridView：搜索态优先渲染 SearchTopBar + searchContent；resultCount visible_when search_finished ✅
- 组标题/空结果/结果计数全走 xcstrings（gallery_search_results_title / gallery_search_no_result_with_query / people_photos_count）✅

### 红线检查 ✅ 全部通过
- **[I18N]**：新建 UI Swift 文件无硬编码 CJK（SearchTopBar.swift / ChatSearchMapping.swift 全走 Localizable.xcstrings）；6 个搜索 key 在 xcstrings 均存在。
- **[TOKENS]**：SearchTopBar 无裸尺寸/透明度数字（除注释）；SearchFieldTokens 已在 DesignTokens.swift + design-tokens.json 同步定义。
- **[PRIVACY]**：搜索全端侧——新建 Search 目录无 URLSession/URL/http/fetch（SQL + 端侧 MobileCLIP ONNX）。
- **[架构]**：`git diff` + 未跟踪文件 **零 androidApp 路径**；shared 仅 iosMain/iosTest 改动，commonMain 仅 design-tokens.json 资源新增。

### 有意偏差（已登记，不算问题）✅
- **R4**：removeTimeWords 近 N 个月清洗用正确 `\d`（spec `allowed_differences.search_engine_edge_cases`）。
- **R5**：裸「冬天」跨年修正（endYear=year+1），不复刻 Android startMs>endMs 恒空 bug（同 spec 登记）。
- QueryBuilder/SearchRanker 死代码不移植（契约 §4.1）。
- OPUS-MT 词表-only 降级（契约 §13.6，模型未下载行为等价）。

### 测试覆盖 ✅
11 个测试文件 / 174 test func（QueryParser 40、SearchDatabase 23、MediaSearchEngine 20、SemanticSearchEngine 18、MediaFeedback 8、PersonQueryResolver 13、QuerySegmenter 13、TagTranslator 6、ChatSearchMapping 6 + shared iosTest 1）。覆盖关键路径：时间词 16 分支、mergeAndRank 权重公式、LRU 64、embedding 往返、feedback 精确匹配、人物四级优先级、in-set refine。

---

## 🟡 建议（质量 / 一致性 / 已知技术债）

### 🟡-1 辅助表（tags / ocr_words / location_hierarchy）无写入侧 → 对应召回维度静默空（契约 §14 R9）
**文件**：`iosApp/PoLang/Platform/Search/TagDatabase+Search.swift:590-749`（upsertTag/upsertOcrWord/upsertLocation 等写入 API 已定义但无调用方）

**问题**：grep 确认 `iosApp/` 下无任何扫描 / indexing pass 调用 `upsertTag`/`insertMediaTag`/`upsertOcrWord`/`upsertLocation` 等写入 API。当前媒体扫描（Pass1Pipeline）只写 `media_assets` 内联列（labels/ocrText/locationName）。后果：
- `searchByExactTag` / `searchByWordPrefix` / `searchByPlace`（辅助表 JOIN 查询）在 iOS 上**恒返回空**；
- 但主表 `searchByLabel` / `searchByOcrText` / `searchByLocation` / `searchByFileName`（media_assets LIKE）**可用**，搜索仍降级工作。

**评估**：这是已登记的 R9 技术债 + spec `platform_differences.search.data` 已标注「需补六张辅助表」。数据层（schema + 写入 API + 查询）本轮已完备，是后续 indexing pass 的能力前置。**不阻塞本轮**，但建议在 docs/reviews 或 TODO 显式登记「iOS tags/ocr/location 倒排索引 pass 待实现」以防被当作已就绪。

**修复建议**：无需改代码；建议在 `TagDatabase+Search.swift:584` 的注释块补充一句「本轮无写入调用方（待 indexing pass）」，或建一个跟踪 issue。

---

### 🟡-2 旧行 semanticEmbedding 小端格式需重跑 Pass1 覆盖（R6 衍生）
**文件**：`iosApp/PoLang/Platform/Pass1Pipeline.swift:321`（注释已说明）

**问题**：本 diff 将 `floatArrayToBase64` 从原生小端内存拷贝改为 `SemanticEmbeddingCodec.encode`（大端）。此前写入库的旧行仍为小端格式，与新解码器不兼容（解码后向量全错 → 语义召回静默返回错误结果或全过滤）。代码注释称「此前无消费方，无线上影响」。

**修复建议**：上线时确保触发一次全量 Pass1 重扫覆盖旧行；或在 release notes / migration note 显式标注「语义搜索需重跑 Pass1」。无需改代码。

---

### 🟡-3 GalleryViewModel.searchContent 未复用主网格的 faceAware / 拖拽选择完整能力（spec inherit_main_grid）
**文件**：`iosApp/PoLang/Features/Gallery/GalleryGridView.swift` `searchGridBody`

**问题**：spec `search_results.grid: same_as_main_grid` + `interactions: inherit_main_grid`。当前 `searchGridBody` 用独立的 ScrollView+LazyVGrid，而非调用 `gridBody`（主网格复用）。虽然 `cells(for:)` 复用单元格，但选择态 / 长按多选 / faceAwareVerticalAlignment 等主网格能力是否在搜索态完整继承，取决于 `allItems` 在搜索态切换为 `searchResults` 后是否被外层选择逻辑一致消费——本审查未逐行验证选择/多选在 `isSearchActive` 态的行为路径。

**修复建议**：建议补一个搜索态长按进多选、全选作用于搜索结果集的手测或 UI 测试（契约 §11「搜索态全选只作用于搜索结果集」）。若已验证则忽略。

---

## 🔵 可选（风格 / 远期优化）

### 🔵-1 `getMediaByPersonsCooccurrence` 用 `searchCols`（无 `m.` 前缀）但 FROM 别名为 `m`
**文件**：`TagDatabase+Search.swift:484-492`

当前 SQL `SELECT {searchCols} FROM media_assets m WHERE m.id IN (...)`——`searchCols` 为非限定列名（`id, uri, ...`），FROM 仅有单表 `media_assets m` 无 JOIN，故非限定列名明确解析为 `m.*`，**不报错、行为正确**。仅为一致性，可改用 `searchColsM`（带 `m.` 前缀）与其余 PersonDao JOIN 查询统一风格。不影响正确性。

### 🔵-2 契约 LOCATION 词表注释「63 词」与实际 71 词不符（双端共有笔误）
**文件**：契约 `contracts.md:476` 与 `SearchVocabulary.swift` 注释

子 agent 核对发现：契约注释写「63 词」、Swift 注释同，但实际两处词表均含 **71** 条。数据完全一致（非行为偏差），仅计数标签漂移。KinshipLexicon 同理（注释「37 条」实际 38）。远期可顺手修正注释计数。

### 🔵-3 SemanticSearchEngine `searchByText(filter:)` 与 MediaSearchEngine `semanticCandidateIds` 候选集规则各有一份实现
**文件**：`SemanticSearchEngine.swift:208-238`（filteredCandidateIds）vs `MediaSearchEngine.swift:291-331`（semanticCandidateIds）

契约 §5.4 注释已指出双端各一份实现（「两处规则一致，若后续修改需同步」）。当前两份逻辑等价。远期可抽公共函数消除重复维护面。

---

## 审查方法说明

- **逐文件读全文**：20 个新建 Search Swift 文件 + ChatSearchMapping/SearchTopBar/PhSearchBridge + 3 个 shared iosMain/iosTest Kotlin 文件 + 全部 diff（GalleryViewModel/GalleryGridView/TagDatabase/Pass1Pipeline/DesignTokens/Localizable.xcstrings/spec yaml/DI）。
- **契约符合性抽查**：§2/§3/§4/§5/§7/§8/§9 关键常量逐项核对（见上表）。
- **词表核对**：委托子 agent 对 §3.5-3.10 / §5.6-5.8 / §5.7 / §7.2 / §6.1-6.3 共 10 大类约 350 词目逐条比对，**零偏差**。
- **Tokenizer 核对**：委托子 agent 对 §5.3（24 个检查点）+ §5.9 OPUS-MT 降级逐项核对，**零偏差**（含高风险点 `[\p{N}]` 单字符 vs `[\p{N}]+`、`</w>` 词尾位置、截断保留 EOS）。
- **红线 grep**：androidApp 路径（0）、网络调用（0）、硬编码 CJK UI（0）、裸数字 UI（0）。
- **逻辑风险抽查**：asynclet 并发数据竞争（SearchTranslationCache 有 NSLock、semanticEngine 无共享可变状态）、queue.sync 重入（无——单层扁平）、防抖 Task.cancel（正确）、PhSearchBridge 线程（completion 在 Task 内回传、不跨 K/N 边界抛异常）、大小端（正确）——**均无崩溃/竞争风险**。

---

*审查结论：实现质量高，契约保真度优秀（逐字照抄 + 有意偏差登记清晰），红线全过，测试覆盖充分。🟡 项均为已登记技术债，不阻塞合入。建议合入后跟踪 🟡-1（辅助表 indexing pass）与 🟡-2（重跑 Pass1 覆盖旧 embedding）。*
