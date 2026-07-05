# TAG 中英文国际化方案

> 状态：已落地（Phase 0 + Phase 1 + Phase 1.5 实现完成）  
> 最后更新：2026-07-05  
> 维护者：RD Agent  
> 目标：在**不强制全量重新生成 TAG** 的前提下，让中英文用户都能跨语言搜索和查看 TAG。  
> 关联文档：`GALLERY_SEARCH.md`（搜索链路 SSOT）、`AUTO_TAG_GENERATION_SPEC.md`、`TAG_DATABASE_SCHEMA.md`、`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`（Tokenizer 现状）

---

## 1. 背景与约束

当前 PicMe 的 TAG 体系存在以下与国际化相关的痛点：

| 痛点 | 根因 | 影响 |
|------|------|------|
| 英文界面搜索无结果 | `media_assets.labels` 中存储的是中文 JSON（如 `["猫","户外"]`），`LIKE '%cat%'` 无法命中 | 英文用户无法通过自然语言检索照片 |
| TAG 重生成成本高 | 三阶段生成涉及 InsightFace 人脸检测、Glint360K R100 Embedding、DBSCAN 聚类、Qwen 视觉推理，全量重跑耗时耗电 | 不能因为切换语言就要求用户重新扫描所有照片 |
| Prompt 与提示词中文硬编码 | `TagGenerationPipeline.stage3SystemPrompt`、`AutoTagCapability` 描述、`MediaSearchEngine` 的 LLM prompt、搜索停用词/城市词均为中文 | Agent 在英文界面仍用中文与用户交互 |
| UI 直接展示中文 TAG | `MediaPager` 等详情页把 `labels` 原样显示 | 英文用户看到 “男/女/户外” 体验割裂 |

红线约束：

- **[PRIVACY]** 敏感数据必须本地处理。TAG 本身可能包含人物、地点等隐私信息，因此翻译/映射必须走本地词表，不能调用云端翻译 API。
- **[I18N]** 禁止硬编码，三语同步。
- **[PERF]** 不能因语言切换导致明显搜索性能退化。

---

## 2. 设计原则

1. **Canonical 不变**：已生成的中文 TAG 作为事实来源，切换语言不触发重写。
2. **薄本地化层**：语言差异在查询和展示时注入，存储层保持中文为主。
3. **隐私优先**：翻译/同义词扩展走本地双语词表。
4. **渐进式**：先让英文能搜、能看；再让新 TAG 按语言生成；最后重构为语言无关概念模型。

---

## 3. 总体架构

```text
┌─────────────────────────────────────────────────────────────┐
│  UI / Search                                                │
│  • 展示：中文 TAG ──TagTranslator──> 英文 displayName         │
│  • 搜索：英文 query ──TagTranslator──> 中文 canonical 集合    │
│         ──> Room LIKE / TagDao 查询                          │
├─────────────────────────────────────────────────────────────┤
│  Runtime Localization Layer（新增）                          │
│  • TagTranslator：zh↔en 映射 + 同义词扩展                     │
│  • BilingualVocab：从 assets/tag_translations.json 加载       │
├─────────────────────────────────────────────────────────────┤
│  Existing Storage（不变）                                    │
│  • media_assets.labels（中文 JSON）                           │
│  • tags / media_tag_cross_ref（可后续迁移）                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心设计

### 4.1 双语词表与翻译器

新增 `domain/tag/i18n/TagTranslator.kt`：

```kotlin
class TagTranslator(private val vocab: BilingualVocab) {

    /** 把数据库里的中文标签翻译成当前界面语言（用于展示） */
    fun display(chineseTag: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.ENGLISH -> vocab.zhToEn[chineseTag] ?: chineseTag
        else -> chineseTag
    }

    /**
     * 把用户输入的查询词扩展为数据库中可能存在的形式。
     * 例如英文 "cat" -> ["cat", "猫"]，这样既能命中未来英文标签，也能命中现有中文标签。
     */
    fun expandForSearch(query: String, uiLang: AppLanguage): Set<String> {
        val result = linkedSetOf(query)
        when (uiLang) {
            AppLanguage.ENGLISH -> {
                vocab.enToZh[query.lowercase()]?.let { result += it }
                vocab.enSynonyms[query.lowercase()]?.let { syn ->
                    vocab.enToZh[syn]?.let { result += it }
                }
            }
            else -> {
                vocab.zhToEn[query]?.let { result += it }
            }
        }
        return result
    }
}
```

新增 asset `app/src/main/assets/tag_translations.json`：

```json
{
  "zh_to_en": {
    "猫": "cat",
    "狗": "dog",
    "男性": "male",
    "女性": "female",
    "户外": "outdoor",
    "室内": "indoor"
  },
  "en_synonyms": {
    "kitten": "cat",
    "puppy": "dog",
    "guy": "male"
  }
}
```

> 词表从 `controlled_vocab.json` 的 10 个类别导出，核心 TAG 约 600 个。可通过 `scripts/generate_tag_translations.py` 批量生成初稿，再人工校对高频/敏感词。

### 4.2 搜索层改造

在 `MediaSearchEngine.executeFilter` 中，对每个 keyword 先通过 `TagTranslator.expandForSearch` 扩展候选词集合，再分别查询：

```kotlin
val uiLang = settingsRepository.getAppLanguageBlocking()
for (keyword in filter.keywords) {
    val candidates = tagTranslator.expandForSearch(keyword, uiLang)
    for (candidate in candidates) {
        tagDao?.searchByExactTag(candidate)?.let { ... }
        mediaDao.searchByLabel(candidate).let { ... }
    }
}
```

若高频搜索性能吃紧，可在 `MediaDao` 增加多关键词 OR 查询，减少 SQL 往返。

### 4.3 UI 展示改造

在 `features/gallery/components/MediaPager.kt` 等展示处，对 `scene`、`activity`、`objects`、`tags`、`qwenSummary` 经过 `TagTranslator.display(...)` 映射后再显示。未命中词表时回退原中文。

### 4.4 Prompt 本地化

抽象 `TagPromptProvider`：

```kotlin
interface TagPromptProvider {
    fun systemPrompt(lang: AppLanguage): String
    fun userPrompt(faceContext: FaceRoiPersist?, lang: AppLanguage): String
}
```

- 中文：保持现有 prompt。
- 英文：要求模型输出英文标签，示例使用英文受控词表。

`TagGenerationPipeline` 注入 `UserSettingsRepository`，根据当前 `AppLanguage` 选择 prompt。这样：

- 中文用户继续生成中文 TAG。
- 英文用户的新照片生成英文 TAG。
- 老照片中文 TAG 由 `TagTranslator` 兜底兼容。

### 4.5 Agent / 搜索相关 Prompt 本地化

| 位置 | 改造点 |
|------|--------|
| `AutoTagCapability` | 命令描述移入 `strings.xml`，通过 `Context` 读取 |
| `MediaSearchEngine.buildLlmSearchPrompt` | 按 `AppLanguage` 返回中英文 prompt |
| `QueryParser` | 停用词、城市词、人物词按 locale 加载或从资源读取 |
| `MediaSearchEngine` | 已移除 SQL 标签未命中时的 `hasFace` 人物回退，人物搜索完全依赖 MobileCLIP 语义召回 |

---

## 5. 长期架构：语言无关 TAG 概念模型

若后续支持更多语言，中文 canonical 将不再是最佳选择。建议第二阶段引入：

```kotlin
@Entity(tableName = "tag_concepts")
data class TagConcept(
    @PrimaryKey val conceptId: String, // 稳定 key，如 "animal.cat"
    val category: String
)

@Entity(tableName = "tag_concept_translations", primaryKeys = ["conceptId", "locale"])
data class TagConceptTranslation(
    val conceptId: String,
    val locale: String,          // "en", "zh", ...
    val displayName: String,
    val synonyms: String         // JSON 数组
)
```

- `TagEntity` 改为 `tag_concepts` 的 localized view。
- `MediaTagCrossRef` 关联 `conceptId`。
- 生成时先把 Qwen 输出归一化到 concept，再按当前 locale 取 displayName。
- 搜索时把用户输入映射到 concept，再 join cross ref。

该改动需要一次 DB migration 和历史数据迁移，但新增语言时无需重跑视觉模型。

---

## 6. 实施路线图

| 阶段 | 目标 | 改动范围 | 是否重生成 | 状态 |
|------|------|----------|------------|------|
| **Phase 0** | 英文能搜、能看 | `TagTranslator` + `tag_translations.json`；改造 `MediaSearchEngine`、`QueryBuilder`、`MediaPager` | 否 | ✅ 已完成 |
| **Phase 1** | 新照片按语言生成 | `TagPromptProvider` / `DefaultTagPromptProvider`；按 `AppLanguage` 切换 prompt | 仅新照片 | ✅ 已完成 |
| **Phase 1.5** | 中文搜索召回 ML Kit 英文标签 + 中文同义词扩展 | `mlKitLabelsZh` 列、`MlKitLabelTranslator`、`TagTranslator` OPUS-MT 回退 + ControlledVocab 同义词 | 全量重扫 ML Kit（仅新增列） | ✅ 已完成 (2026-07-01) |
| **Phase 2** | 结构化多语言 TAG | `TagConcept`/`TagConceptTranslation` 表；迁移 `TagEntity`/`MediaTagCrossRef` | 否（仅迁移） | 📋 待规划 |
| **Phase 3** | 搜索体验升级 | 接入 FTS5 / 前缀索引；支持英文同义词、拼写容错 | 否 | 📋 待规划 |

---

## 6.5 Phase 1.5 详解：搜索翻译链路增强 (2026-07-01)

### 6.5.1 问题背景

Phase 0/1 解决了**英文用户搜索中文标签**的问题，但存在以下缺口：

| 缺口 | 根因 | 影响 |
|------|------|------|
| 中文搜索无法命中 ML Kit 英文标签 | `mlKitLabels` 列存英文（如 `["Cat","Outdoor"]`），中文查询 `LIKE '%猫%'` 无法命中 | 中文用户搜索丢召回 |
| 词表覆盖不全 | `tag_translations.json` 仅 534 条 zh→en，未命中时无回退 | 长尾中文词搜索丢召回 |
| 中文同义词无法扩展 | `ControlledVocab.reverseSynonyms` 仅在标签生成时使用，搜索链路未接入 | "猫咪"搜不到"猫" |
| 显式约束管道跳过翻译 | `ExplicitFirstSearchPipeline` 无 TagTranslator | "去年在室内猫" 内容词未翻译 |

### 6.5.2 ML Kit 标签中文翻译（静态映射）

ML Kit Image Labeling API 使用固定的 ~400 个英文标签。创建静态中英文映射表 `assets/mlkit_labels_zh.json`，在索引时同时存储中文翻译。

**数据流：**

```
MlKitTagExtractor → 英文标签 ["Cat","Outdoor","Food"]
                          ↓
              MlKitLabelTranslator.translateToZh()
                          ↓
              中文标签 ["猫","户外","食物"]
                          ↓
         ┌────────────────┼────────────────┐
         ↓                                 ↓
  mlKitLabels (EN)                mlKitLabelsZh (ZH)
  ["Cat","Outdoor","Food"]       ["猫","户外","食物"]
```

**DB Migration 6→7：** 新增 `media_assets.mlKitLabelsZh TEXT` 列。

**搜索时：** 所有搜索路径同时查询 `mlKitLabels` 和 `mlKitLabelsZh`，确保中英文查询都能命中。

### 6.5.3 TagTranslator 翻译分层策略

```
expandForSearch(中文查询词)
  │
  ├─ 1. BilingualVocab.zhToEn 词表精确匹配（0ms）
  │    命中 → 返回 {中文词, 英文词}
  │
  ├─ 2. ControlledVocab 中文同义词双向扩展（0ms）
  │    synonyms: "美女"→"女性"
  │    reverseSynonyms: "女性"→["美女","大美女"]
  │
  ├─ 3. OPUS-MT 模型翻译回退（~50ms，词表未命中时）
  │    中文词 → NMT → 英文词
  │    质量校验：过滤空白/异常输出
  │
  └─ 4. 保留原词兜底
```

> **Tokenizer 说明**：OPUS-MT 的编解码依赖 `:sentencepiece` 模块加载的 `source.spm` / `target.spm`，`tokenizer.json` 仅用于 Hugging Face token ID 与 SentencePiece piece 之间的映射。详见 `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` 第 4.7 节。

### 6.5.4 ChineseQueryTranslator CLIP 扩展增强

```
expandForClip(中文查询)
  │
  ├─ 1. CLIP_QUERY_EXPANSIONS 硬编码表（人工维护，CLIP 优化短语）
  │    如 "小孩"→["child","kid","children","young child"]
  │
  ├─ 2. ControlledVocab 同义词动态翻译（新增）
  │    "美女"→synonym "女性"→translateForClip("女性")→"female"
  │    弥补硬编码表仅 20 条的不足
  │
  └─ 3. translateForClip 基础翻译（vocab→OPUS-MT 回退）
```

> OPUS-MT 翻译内部同样走 SentencePiece → HF ID → 推理 → HF ID → SentencePiece 的往返，具体映射细节见 `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` 第 4.7 节。

### 6.5.5 关键文件变更

```text
新增：
app/src/main/assets/mlkit_labels_zh.json
app/src/main/java/com/mamba/picme/domain/tag/i18n/MlKitLabelTranslator.kt
app/src/test/java/com/mamba/picme/domain/tag/i18n/MlKitLabelTranslatorTest.kt

修改：
app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt          (version 6→7, MIGRATION_6_7)
app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt          (新增 mlKitLabelsZh 字段)
app/src/main/java/com/mamba/picme/data/local/MediaDao.kt             (新增 updateMlKitLabelsZh/searchByMlKitLabelZh/searchMlKitLabelsZhInIds/resetAllMlKitLabelsZh)
app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt (索引时存储中文翻译)
app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt (全量重扫时同步清空 mlKitLabelsZh)
app/src/main/java/com/mamba/picme/domain/tag/i18n/TagTranslator.kt   (OPUS-MT 回退 + ControlledVocab 同义词 + Logger)
app/src/main/java/com/mamba/picme/domain/tag/i18n/ChineseQueryTranslator.kt (ControlledVocab 动态扩展)
app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt (searchByMlKitLabelZh)
app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt (注入 TagTranslator + mlKitLabelsZh 搜索)
app/src/main/java/com/mamba/picme/di/AppContainer.kt                 (注入 ControlledVocab/OpusMtTranslator/MlKitLabelTranslator)
app/src/test/java/com/mamba/picme/domain/tag/i18n/TagTranslatorTest.kt (扩展测试)
```

---

## 7. 关键文件清单

```text
新增：
app/src/main/java/com/mamba/picme/domain/tag/i18n/TagTranslator.kt
app/src/main/java/com/mamba/picme/domain/tag/i18n/BilingualVocab.kt
app/src/main/java/com/mamba/picme/domain/tag/i18n/MlKitLabelTranslator.kt      (Phase 1.5)
app/src/main/java/com/mamba/picme/domain/tag/prompt/TagPromptProvider.kt
app/src/main/java/com/mamba/picme/domain/tag/prompt/DefaultTagPromptProvider.kt
app/src/main/assets/tag_translations.json
app/src/main/assets/mlkit_labels_zh.json                                        (Phase 1.5)

修改：
app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt
app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt
app/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt
app/src/main/java/com/mamba/picme/domain/search/QueryBuilder.kt
app/src/main/java/com/mamba/picme/domain/search/QueryParser.kt
app/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt   (Phase 1.5)
app/src/main/java/com/mamba/picme/domain/tag/i18n/TagTranslator.kt              (Phase 1.5)
app/src/main/java/com/mamba/picme/domain/tag/i18n/ChineseQueryTranslator.kt     (Phase 1.5)
app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt                     (Phase 1.5: v6→v7)
app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt                     (Phase 1.5)
app/src/main/java/com/mamba/picme/data/local/MediaDao.kt                        (Phase 1.5)
app/src/main/java/com/mamba/picme/di/AppContainer.kt                            (Phase 1.5)
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
```

---

## 8. 风险与回退

| 风险 | 缓解 |
|------|------|
| 翻译词表覆盖不全 | 高频词优先人工校对；未命中时回退原中文 |
| 搜索扩展导致误匹配 | 仅对受控词表内词汇做扩展，自由文本保持原样 |
| 英文 prompt 改变输出格式 | 继续强制 JSON schema；增加单元测试 |
| 隐私合规 | 翻译完全本地，不调用云端 API |

---

## 9. 验收标准

- [x] 英文界面下搜索 "cat" 能命中标签为 "猫" 的照片（通过 `TagTranslator.expandForSearch`）。
- [x] 英文界面下照片详情页的标签展示为英文（通过 `TagTranslator.display`）。
- [x] 切换为中文后，搜索/展示恢复为中文。
- [x] 不触发全量 TAG 重新生成即可生效。
- [x] Prompt 与 Agent Capability 描述支持英文。
- [x] 中文搜索能命中 ML Kit 英文标签（通过 `mlKitLabelsZh` 列 + `MlKitLabelTranslator`）。
- [x] 中文同义词搜索扩展（"美女"→"女性"，"猫咪"→"猫"，通过 `ControlledVocab.reverseSynonyms`）。
- [x] 词表未命中时 OPUS-MT NMT 模型翻译回退（通过 `TagTranslator` MT fallback）。
- [x] 显式约束管道支持跨语言搜索（通过 `ExplicitFirstSearchPipeline` 注入 `TagTranslator`）。
- [x] CLIP 语义搜索使用 ControlledVocab 动态扩展候选英文短语。
