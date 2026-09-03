# 搜索模块技术实现规范 (Search)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（搜索召回测试页、诊断接口）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。
> - 相册自然语言搜索的完整链路以 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` 为唯一事实来源（SSOT）。

> **版本**: 1.1  
> **状态**: 生效中  
> **最后更新**: 2026-06-30  
> **维护者**: 项目开发者

**模块定位**: 提供相册自然语言搜索的诊断与观测能力，帮助 RD/QA 定位关键词召回问题。

**主要维护者**: 项目开发者

**阅读对象**: RD、QA、AI Agent

---

## 1. 核心产品逻辑 (Core Product Logic)

- **[DEV_ONLY] 仅限开发环境**: 搜索测试页仅在 Debug 构建中启用，Release 包必须完全移除入口。
- **[LOCAL] 纯本地操作**: 所有诊断查询在设备端完成，不触发云端推理。
- **[OBSERVABILITY] 全链路可观测**: 从关键词输入到最终召回结果，展示每个阶段的数据、耗时与命中维度。
- **[NON_INTRUSIVE] 非侵入式**: 诊断接口不影响现有 `MediaSearchEngine.search()` 的线上行为。

## 2. 技术实现规范 (Technical Implementation)

### 2.1 文件组织

| 文件 | 职责 |
|------|------|
| `SearchTestScreen.kt` | 搜索召回测试页 UI（Compose） |
| `SearchTestViewModel.kt` | 测试页状态管理、日志与历史 |
| `MediaSearchEngine.searchWithDiagnostics()` | 暴露搜索中间过程数据（位于 `domain/search`） |

### 2.2 诊断搜索接口

`MediaSearchEngine.searchWithDiagnostics(query, enableSemanticSearch)` 返回 `SearchDiagnosticsResult`：

```kotlin
data class SearchDiagnosticsResult(
    val originalQuery: String,
    val parsedFilter: StructuredFilter?,
    val needsLlm: Boolean,
    val usedLlm: Boolean,
    val llmFilter: StructuredFilter?,
    val metrics: SearchMetrics,
    val recallBreakdown: List<RecallDimension>,
    val sqlResults: List<DiagnosticMediaItem>,
    val semanticResults: List<DiagnosticSemanticItem>,
    val mergedResults: List<DiagnosticMediaItem>,
    val enableSemanticSearch: Boolean
)
```

**关键设计约束**:
- 不得修改 `MediaSearchEngine.search()` 的签名与行为。
- 诊断方法复用现有 `QueryParser`、`executeFilter`、`mergeAndRank` 逻辑，通过并行方法 `_WithDiagnostics` 收集过程数据。
- `SemanticSearchEngine` 未暴露候选集大小时，使用 `-1` 占位，测试页通过日志观察。

### 2.3 页面结构

```
SearchTestScreen
├── 搜索输入框
├── 历史词 Chip
├── 启用语义搜索开关
├── 搜索按钮
├── 解析结果卡片
├── 关键指标卡片
├── 召回维度列表
├── 融合结果缩略图网格（最多 30 张）
├── 语义召回结果列表
└── 日志窗口（环形缓冲 200 条）
```

### 2.4 关键指标

| 指标 | 来源 |
|------|------|
| 总耗时 | `System.currentTimeMillis()` 端到端差值 |
| 解析耗时 | `QueryParser.parse()` 前后差值 |
| SQL 召回耗时 | `executeFilterWithDiagnostics()` 前后差值 |
| 语义召回耗时 | `SemanticSearchEngine.searchByText()` 前后差值 |
| 融合排序耗时 | `mergeAndRankWithScores()` 前后差值 |
| 语义引擎就绪状态 | `SemanticSearchEngine.isReady` |
| 各维度命中数 | `executeFilterWithDiagnostics()` 中统计 |

## 3. Agent 执行规约 (Execution Rules)

- **构建隔离**: 测试页入口必须通过 `BuildConfig.DEBUG` 条件编译，Release 包不可见。
- **线程管理**: 搜索执行在 `Dispatchers.Default`/`Dispatchers.IO`，UI 仅展示。
- **日志脱敏**: 不得在日志中输出用户隐私信息。
- **I18N**: 所有 UI 文案必须提取到 `strings.xml` 并五语同步。
- **资源释放**: ViewModel 不直接持有 `SemanticSearchEngine` 生命周期，由 `AppContainer` 管理。

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 搜索测试页入口是否通过 `BuildConfig.DEBUG` 隔离？
- [ ] 是否修改了 `MediaSearchEngine.search()` 的签名？（禁止）
- [ ] 新增字符串是否已五语同步？
- [ ] 日志缓冲区是否设置了上限？（200 条）
- [ ] 结果缩略图是否使用 Coil 加载？
- [ ] 搜索执行是否在后台线程？

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 开发专用 → 仅 Debug 包可见
- ✅ 全链路观测 → QueryParser / SQL / Semantic / Merge 各阶段数据可查看
- ✅ 非侵入式 → 不影响 Gallery 正常搜索体验
