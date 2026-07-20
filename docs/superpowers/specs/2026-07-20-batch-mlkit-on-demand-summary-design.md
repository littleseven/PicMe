# 批量去 LLM（ML Kit 打标 + summary 按需）设计

> **状态**：设计稿，待写实现计划
> **日期**：2026-07-20
> **目标**：根治打标发热——批量 Pass3 不用 SmolVLM（改 ML Kit，几乎不发热）；文字描述 summary 改按需（照片详情点开时 SmolVLM 单张生成）。

## 1. 背景

SmolVLM-500m（当前打标模型）中文质量好，但 CPU 推理 ~13s/张，批量扫描持续发热严重。换更小模型（256m）卡死、ML Kit 单独打标粒度粗。结论：**「质量好」和「不发热」在批量 LLM 推理上不可兼得**。

破局：把 LLM 移出批量热路径——批量打标用 ML Kit（~2s/张、不发热、粗类别标签够搜索），LLM 只在用户点开照片详情时单张生成 summary（质量满血、瞬时、不积热）。

## 2. 设计

### 2.1 批量 Pass3 改 ML Kit（不发热）
- `executeQwenTagging`（TagGenerationScheduler）改为：`MlKitTagExtractor.extract(uri)` → 英文标签 → `MlKitLabelTranslator.translateToZh` → 中文标签 → 全部放 `labels.tags`
- `scene / objects / activity / summary` 留空（批量不生成）
- **不加载 SmolVLM**（ensureModelLoaded 跳过 LLM）→ Pass3 零 LLM 推理 → 不发热
- face（Pass1/Pass2 人脸检测/聚类）+ MobileCLIP 语义向量（Pass1）不变

### 2.2 summary 按需（照片详情触发）
- 照片详情页打开时，检查该照片 `labels.summary` 是否为空
- 空 → 触发 SmolVLM 单张 `imageInference(uri)` 生成 summary（~13s，首次等一下）→ 写回 `labels.summary`（缓存，后续秒开）
- 已有 summary → 直接展示

### 2.3 搜索
- 文字搜索：`labels.tags`（ML Kit 中文标签，如「连衣裙/项链/车辆」）LIKE 匹配
- 语义搜索：`semanticEmbedding`（MobileCLIP，Pass1 保留）余弦相似
- 两路都通（粗标签 + 语义向量），覆盖大部分搜索场景

## 3. 数据流

```
批量扫描（不发热）：
  MediaEntity → ML Kit extract（英文标签）→ translate（中文）→ labels.tags + face(Pass1)
              → semanticEmbedding(Pass1, MobileCLIP) → DB
  （SmolVLM 不参与）

按需 summary：
  照片详情打开 → labels.summary 空？→ SmolVLM imageInference(uri) → summary
              → updateLabels.summary（缓存）
```

## 4. 文件清单（预估）

| 文件 | 职责 |
|---|---|
| `TagGenerationScheduler.executeQwenTagging` | 改 ML Kit 标签（去 SmolVLM）|
| `domain/usecase/GenerateSummaryOnDemandUseCase`（新）| 按需 summary：加载 SmolVLM + 单张推理 + 写回 |
| 照片详情 ViewModel/Screen | 打开时触发 summary 按需 |
| `MlKitTagExtractor` / `MlKitLabelTranslator` | 已有，复用 |

## 5. 不做
- activity（ML Kit 给不了，不要）
- objects 字段分类（ML Kit 标签全放 tags，不分 scene/objects）
- 批量 summary（按需，不批量）

## 6. 验证
- 批量扫描不加载 SmolVLM（logcat 无 LLM 加载）+ 不发热
- 照片详情点开触发 summary（首次 ~13s，后续缓存秒开）
- 搜索：ML Kit 中文标签 + 语义向量都能命中
