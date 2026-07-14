# ADR-007: 端侧自然语言相册搜索 — CV 标签 + LLM 混合架构

> **状态**: 已全面实施  
> **日期**: 2026-06-30  
> **决策**: RD  
> **依赖**: ADR-005（本地/远程推理协议分离，LLM 解析层复用 Agent Runtime）
>
> **实现详情见**: [`docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md`](../../03-TECHNICAL-SPECS/GALLERY_SEARCH.md)（本 ADR 保留决策背景，具体链路以该文档为唯一事实来源）

---

## 1. 背景与问题陈述

### 1.1 产品需求

PoLang 已从相机转向智能相册（ADR-005 产品重心迁移），需要支持自然语言搜索照片：

| 查询类型 | 示例 | 所需能力 |
|---------|------|---------|
| 时间 | "去年夏天的照片" | 时间语义解析 |
| 物体/场景 | "猫""海滩""食物" | 图像内容理解 |
| 文字 | "包含'会议'的截图" | OCR 文字索引 |
| 地点 | "在上海拍的照片" | GPS + 逆地理编码 |
| 人名 | "我和妈妈的合照" | 人脸聚类 |
| 组合 | "去年在上海拍的猫" | LLM 多条件推理 |

### 1.2 当前状态

相册搜索功能为空骨架：
- `GalleryCapability.search_media` 命令已定义但仅打印日志
- Room DB `media_assets` 表无文本/标签字段
- ML Kit OCR 已集成但结果从不存储
- Agent Runtime 已有 LLM（本地 Qwen3.5-2B + 远程 DeepSeek）
- 没有任何图像标注/分类模型

### 1.3 约束

- **个人开发精力有限**：不能引入需要大量调优的自研模型
- **隐私优先 (PRIVACY)**：所有图像处理必须端侧完成，不上传任何照片数据
- **性能 (PERF)**：搜索响应 < 2s（规则匹配 < 100ms，LLM 路径依赖远程延迟）
- **复用优先**：尽量使用已有基础设施（Agent Runtime、ML Kit、Room DB）

---

## 2. 决策

### 2.1 总体方案：CV 标签 + LLM 语义解析双层架构

```
┌──────────────────────────────────────────────────────────────┐
│                    离线索引（后台异步）                          │
│  ML Kit Image Labeling ──┐                                   │
│  ML Kit Text Recognition ─┼──→ Room DB (media_assets 扩展)    │
│  EXIF GPS + Geocoder ────┘                                    │
└──────────────────────────────────────────────────────────────┘
                              │
                              │ 标签/分类数据
                              ▼
┌──────────────────────────────────────────────────────────────┐
│                    在线搜索                                    │
│                                                              │
│  Layer 1: QueryParser 规则匹配（离线，< 100ms）                │
│  - 时间词："去年"→年份-1，"夏天"→6-8月                         │
│  - 关键词 → labels/ocrText/locationName LIKE 匹配              │
│                                                              │
│  Layer 2: Agent LLM 语义解析（需要时）                         │
│  - 复杂混合查询："去年夏天在上海拍的猫"                          │
│  - LLM → StructuredFilter {timeRange, keywords}                │
│  - 通过 AgentOrchestrator → search_media 命令执行              │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 决策 1：使用 ML Kit Image Labeling 而非 CLIP

| 维度 | ML Kit Image Labeling | Chinese-CLIP (ONNX) |
|------|----------------------|---------------------|
| **模型大小** | < 10MB（Google Play Services 内置） | ~300MB（需单独下载） |
| **推理速度** | < 100ms/张 | ~500ms+/张 |
| **标签语言** | 中文（400+ 标签） | 需中文微调版 |
| **集成难度** | 一行依赖，API 简单 | ONNX 转换 + Runtime 集成 |
| **离线可用** | ✅（端侧模型） | ✅ |
| **维护成本** | Google 维护 | 自行维护模型更新 |
| **适用场景** | 常见物体/场景分类 | 高精度语义相似度搜索 |

**决策**：选用 ML Kit Image Labeling 作为第一版图像标注方案。CLIP 作为后续迭代选项，当标签匹配无法满足用户需求时（如跨模态语义搜索）再引入。

### 2.3 决策 2：搜索不单独建索引服务，直接扩展 Room DB

不引入独立的向量数据库或搜索引擎（如 Lucene/FTS5），而是直接在 `media_assets` 表上扩展字段：

```sql
ALTER TABLE media_assets ADD COLUMN labels TEXT;        -- JSON数组
ALTER TABLE media_assets ADD COLUMN ocrText TEXT;       -- OCR文字
ALTER TABLE media_assets ADD COLUMN latitude REAL;      -- GPS
ALTER TABLE media_assets ADD COLUMN longitude REAL;
ALTER TABLE media_assets ADD COLUMN locationName TEXT;  -- 地名
ALTER TABLE media_assets ADD COLUMN indexedAt INTEGER;  -- 索引时间
```

**理由**：
- 照片数量通常在数千到数万级别，Room SQL LIKE 查询完全够用
- 不引入额外依赖，降低维护成本
- 如需全文搜索，可在 `ocrText` 列上建 FTS5 虚拟表（Room 原生支持）

### 2.4 决策 3：搜索与 LLM 通过 Agent Runtime 的 search_media 命令集成

不另建搜索接口，而是将搜索作为 Gallery Capability 的一个命令，通过现有 Agent Runtime 路由：

```
用户输入 "找出去年夏天的猫"
    │
    ▼
AgentOrchestrator.dispatch()
    │
    ├── LOCAL mode → LocalLlmEngine → 输出 [{"method":"search_media","params":{"query":"..."}}]
    └── REMOTE mode → RemoteReActAgent → tool_calls → search_media
    │
    ▼
LocalCommandParser / ToolCallCommandParser → AgentCommand.SearchMedia
    │
    ▼
CapabilityRegistry.dispatch() → GalleryCapability.execute()
    │
    ▼
MediaSearchEngine.search(query) → 结构化过滤 → MediaDao 查询
```

**理由**：
- 复用已有 Agent Runtime 基础设施
- LLM 对 `search_media` 的语义理解已通过 Prompt 示例增强
- 搜索结果可作为后续对话上下文（计划中）

### 2.5 决策 4：后台索引使用协程而非 WorkManager

不引入 WorkManager 依赖，使用简单的 `CoroutineScope(IO)` 后台批量处理：

```kotlin
class MediaIndexingWorker(context: Context) {
    fun start() { scope.launch { doIndex() } }
    fun cancel() { currentJob?.cancel() }
}
```

**理由**：
- 项目当前未使用 WorkManager，避免引入新依赖
- 索引任务简单（遍历未索引照片、调用 ML Kit、写 DB），不需要 WorkManager 的调度能力
- 未来如需要充电/WiFi 约束调度，可再迁移到 WorkManager

---

## 3. 架构设计

### 3.1 模块划分

```
app/
├── data/indexing/
│   ├── MetadataExtractor.kt      # ML Kit 标签+OCR+EXIF+地名提取
│   └── MediaIndexingWorker.kt    # 后台协程批量索引
├── domain/search/
│   ├── QueryParser.kt            # 时间词/关键词规则解析
│   └── MediaSearchEngine.kt      # 两层搜索策略
├── data/model/MediaEntity.kt     # +6 元数据字段
├── data/local/MediaDao.kt        # +10 搜索查询方法
├── data/local/AppDatabase.kt     # v5→v6 migration
└── features/gallery/capability/
    └── GalleryCapability.kt      # 注入 MediaSearchEngine
```

### 3.2 数据流

```
[拍照/导入照片]
    │
    ▼
MediaRepositoryImpl.refreshMediaLibrary()
    │
    ▼
MediaIndexingWorker.start()
    │
    ├── 读取未索引照片 (indexedAt IS NULL)
    ├── 每批 20 张
    │   ├── ML Kit Image Labeling → labels JSON
    │   ├── ML Kit Text Recognition → ocrText
    │   ├── EXIF GPS → lat/lon
    │   └── Geocoder → locationName
    ├── 写入 Room DB
    └── 标记 indexedAt

[用户搜索]
    │
    ▼
QueryParser.parse("猫")
    ├── 无时间词，有关键词["猫"]
    └── → StructuredFilter(keywords=["猫"])
    │
    ▼
MediaSearchEngine.executeFilter()
    ├── searchByLabel("猫") → 匹配标签
    ├── searchByOcrText("猫") → 匹配OCR文字
    └── 合并去重，按时间降序
```

### 3.3 LLM Prompt 集成

search_media 在 System Prompt 中的描述：

```
- gallery: search_media(params.query)
  search_media: 自然语言搜索照片。用户说"找出去年夏天的照片""猫的照片"
  "上海的合照"时，直接用原话作为 query 参数。
  例："找出去年夏天的猫" -> {"method":"search_media","params":{"query":"去年夏天的猫"}}
```

---

## 4. 实施状态

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | DB 扩展 (v6 migration) + ML Kit 依赖 | ✅ 已完成 |
| Phase 2 | 人脸 Embedding + DBSCAN 聚类 + Qwen 标签 + MobileCLIP 语义编码 | ✅ 已完成 |
| Phase 3 | QueryParser + QuerySegmenter + ExplicitFirstSearchPipeline + MediaSearchEngine | ✅ 已完成 |
| Phase 4 | Prompt 增强 + `search_media` Agent 命令 | ✅ 已完成 |
| Phase 5 | Gallery 搜索 UI（搜索框 + 结果网格 + 选择/删除/分享） | ✅ 已完成 |
| Phase 6 | MobileCLIP 语义召回集成 | ✅ 已完成 |
| Phase 7 | 语音搜索集成（KWS→ASR→搜索） | ⏳ 待启动 |

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| ML Kit 标签覆盖不足 | 中 | 中 | Layer 2 LLM 解析兜底；未来引入 CLIP |
| 大批量索引时 ML Kit 限流 | 低 | 低 | 分批处理（20 张/批），ML Kit 无请求限制 |
| OCR 误识别导致搜索结果噪音 | 中 | 低 | 搜索时标签匹配权重 > OCR 匹配 |
| 地名依赖 Geocoder 可用性 | 低 | 中 | Geocoder 失败时用 GPS 坐标作为 fallback |
| LLM 不识别 search_media 命令 | 低 | 高 | Prompt 中已加入示例；规则匹配优先于 LLM |
| 首次索引耗时过长 | 中 | 低 | 分批处理 + 后台执行 + indexedAt 断点续扫 |

---

## 6. 相关文档

- `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` — **相册搜索完整实现链路（SSOT）**
- `docs/02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md` — LLM 推理协议分离
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent 运行时架构（search_media 路由）
- `docs/01-PRODUCT/FEATURES.md` — 智能相册产品需求
- `app/src/main/java/com/mamba/picme/domain/search/` — 搜索引擎实现
- `app/src/main/java/com/mamba/picme/domain/tag/` — TAG 生成与语义编码实现
