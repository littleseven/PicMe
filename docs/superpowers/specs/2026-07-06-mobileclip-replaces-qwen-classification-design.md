# MobileCLIP 替代 Qwen 分类设计

> **文档类型**：技术设计（Technical Design）
> **针对能力**：TAG 生成 / 相册图像自动打标
> **最后更新**：2026-07-06
> **维护者**：RD Agent

---

## 1. 目标

将 `TagGenerationPipeline` 中 `scene`、`objects`、`tags` 三个字段的生成，从 Qwen 的生成式 JSON 输出迁移到 MobileCLIP 零 shot 分类；Qwen 只负责 `activity` 和 `summary`。

在保持搜索侧兼容的前提下，把 Pass 3 单张推理从秒级降到百毫秒级。

---

## 2. 背景

当前 TAG 生成采用 3-Pass 管道：

- Pass 1：人脸检测 + Glint360K R100 Embedding + MobileCLIP 语义编码
- Pass 2：DBSCAN 人脸聚类
- Pass 3：Qwen3.5-2B 图像理解，输出 `scene/activity/objects/tags/summary`

Pass 3 是性能瓶颈，占全量扫描总耗时的 80% 以上。Qwen 用于「从受控词表里选标签」属于过度设计：MobileCLIP 零 shot 分类延迟为毫秒级，一致性更高。

---

## 3. 设计原则

1. **渐进替换**：MobileCLIP 失败后自动回退到 Qwen 全量输出，不破坏现有链路。
2. **搜索兼容**：`scene`、`objects`、`tags` 字段格式与旧数据保持一致（JSON 字符串数组 / 单字符串）。
3. **受控词表驱动**：候选标签由 `ControlledVocab` 统一维护，不硬编码。
4. **单次预计算**：候选标签文本 embedding 在扫描启动时预计算并缓存，避免每张图重复编码。

---

## 4. 架构

### 4.1 新增组件

#### `MobileClipTagClassifier`

职责：

- 从 `ControlledVocab` 加载各字段候选标签子集
- 启动时预计算所有候选标签的文本 embedding（`Map<String, FloatArray>`）
- 提供 `classify(bitmap: Bitmap): MobileClipTags` 接口
- 按字段阈值策略输出 Top-K 标签

```kotlin
data class MobileClipTags(
    val scene: String,
    val objects: List<String>,
    val tags: List<String>
)

class MobileClipTagClassifier(
    private val mobileClipEngine: MobileClipEngine,
    private val vocab: ControlledVocab,
    private val tokenizer: MobileClipTokenizer
) {
    suspend fun warmUp(): Boolean
    fun classify(bitmap: Bitmap): MobileClipTags?
}
```

### 4.2 修改组件

#### `TagGenerationPipeline`

Stage 3 流程变更：

```
Bitmap ──┬──► MobileClipTagClassifier ──► scene / objects / tags
         └──► Qwen (精简 prompt) ────────► activity / summary
                      │
                      ▼
              UnifiedTagResult ──► DB
```

- 先尝试 MobileCLIP 分类
- 再调用 Qwen，prompt 只要求输出 `activity` + `summary`
- 合并结果；若 MobileCLIP 失败，则 Qwen 承担全部字段

#### `TagPromptProvider`

新增接口方法：

```kotlin
fun userPromptForActivityAndSummary(language: AppLanguage): String
fun systemPromptForActivityAndSummary(language: AppLanguage): String
```

精简后的 Qwen 输出格式：

```json
{
  "activity": "散步",
  "summary": "妈妈在公园推婴儿车散步"
}
```

#### `ControlledVocab`

新增分类别候选访问：

```kotlin
val sceneCandidates: List<String>
val objectCandidates: List<String>
val tagCandidates: List<String>
```

候选类别映射：

| 字段 | 来源类别 |
|---|---|
| `scene` | `scenes` / `locations` |
| `objects` | `objects` |
| `tags` | `attributes`、`people`、`time`、`events` 等 |

#### `TagGenerationScheduler`

- 初始化时创建 `MobileClipTagClassifier`
- 进入 Pass 3 前调用 `classifier.warmUp()` 预计算文本 embedding
- 将 classifier 注入 `TagGenerationPipeline`

---

## 5. 阈值策略

| 字段 | Top-K | 最低相似度 | 说明 |
|---|---|---|---|
| `scene` | 1 | 0.30 | 场景唯一，阈值较高保证准确性 |
| `objects` | 3 | 0.25 | 物体数量有限 |
| `tags` | 5 | 0.20 | 标签可适当放宽，增加搜索覆盖 |

若某字段没有候选达到阈值，则该字段为空。

---

## 6. 错误处理与降级

| 场景 | 行为 |
|---|---|
| MobileCLIP 引擎未初始化 | 初始化失败时回退到 Qwen 全量输出 |
| 文本 embedding 预计算失败 | 回退到 Qwen 全量输出 |
| 单张图像分类失败 | 该张使用 Qwen 全量输出 |
| Qwen 精简 prompt 输出为空 | 保留 MobileCLIP 结果，`activity`/`summary` 为空 |
| Qwen 输出仍包含 `scene/objects/tags` | 以 MobileCLIP 结果为准，丢弃 Qwen 的对应字段 |

---

## 7. 数据兼容性

最终写入 `media_assets.labels` 的 JSON 结构保持不变：

```json
{
  "face": { "count": 1, "selfie": false, "groupPhoto": false, "personIds": [] },
  "scene": "公园",
  "activity": "散步",
  "objects": ["婴儿", "推车", "树"],
  "tags": ["户外", "公园", "亲子", "白天"],
  "qwenSummary": "妈妈在公园推婴儿车散步"
}
```

搜索侧无需改动。

---

## 8. 性能预期

| 阶段 | 当前（Qwen） | 优化后 |
|---|---|---|
| `scene/objects/tags` | ~2-4s（Qwen decode） | ~100-300ms（MobileCLIP 图像编码 + 点积） |
| `activity/summary` | 包含在上述时间内 | ~0.5-1.5s（Qwen 输出 token 减少约 60%） |
| Pass 3 单张总耗时 | ~2-4s | ~0.6-1.8s |

---

## 9. 验证计划

1. **单元测试**：
   - `MobileClipTagClassifier` 对固定候选词表的 Top-K 选择逻辑
   - 阈值过滤行为

2. **集成测试**：
   - 选 20 张测试图，分别用旧 Qwen 全量和 MobileCLIP 混合方案跑一遍
   - 对比 `scene/objects/tags` 字段的重合率

3. **搜索回归测试**：
   - 验证按 `scene`、`objects`、`tags` 搜索仍能召回
   - 验证新写入数据的 JSON 结构与旧结构一致

4. **性能基准**：
   - 记录 Pass 3 单张平均耗时
   - 对比优化前后

---

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| MobileCLIP 分类质量不如 Qwen | 保留 Qwen 全量回退；集成测试评估质量 |
| 候选词表过大导致预计算慢 | 仅加载方案 2 指定的类别子集，控制在 200 词以内 |
| text model 加载增加启动时间 | 在 Pass 3 开始前懒加载，不影响 Pass 1/2 |
| 内存占用增加 | 候选 embedding 总数 ≤ 200 × 512 × 4B ≈ 400KB，可忽略 |

---

## 11. 决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 替代范围 | `scene/objects/tags` 走 MobileCLIP，`activity/summary` 留 Qwen | scene/objects/tags 是受控分类任务，activity/summary 需要上下文推理 |
| 候选标签策略 | 分类别子集 | 避免 700+ 词全量比较，语义更聚焦 |
| 文本 embedding 缓存 | 启动时一次性预计算 | 候选词表固定且不大，避免运行时重复编码 |
| 阈值策略 | scene 0.30 / objects 0.25 / tags 0.20 | 保证 scene 准确性，tags 适当增加覆盖 |

---

> **下一步**：基于本设计编写实现计划（implementation plan）。
