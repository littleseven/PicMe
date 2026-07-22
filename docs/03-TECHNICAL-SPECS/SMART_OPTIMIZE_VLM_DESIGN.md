# SmartOptimizeEngine VLM 实现设计（LLM 推荐图片优化参数）

> 状态：设计稿（2026-07-22），暂未实现。
> 范围：用多模态 LLM（VLM）看图直接推荐美颜/滤镜/调色参数，填补现有 `SmartOptimizeEngine` 空接口。

---

## 1. 背景与现状

PoLang 的 AI 一键优化（`AiOptimizeUseCase`）有两条路径：

| 路径 | 实现 | 现状 |
|---|---|---|
| `fastOptimize` | `SceneAnalyzer`（场景分类）→ `PresetRepository`（每场景固定 recipe） | ✅ 已实现，但参数固定、无个性化 |
| `smartOptimize` | `SmartOptimizeEngine.optimize(uri) → OptimizePreset` | ❌ **空接口**（`AiOptimizeUseCase.smartEngine = null`），实际降级 fast |

需求：让 LLM/VLM **看图后直接推荐优化参数**（beauty 强度 / 滤镜 / 调色值），而非按场景查固定预设。

## 2.「LLM 推荐参数」的本质

= **VLM（多模态 LLM）+ Structured Output**：VLM 看图 → prompt 引导 → 输出 recipe JSON → 解析成 `EditRecipe` → 喂 `RecipeApplier` 渲染。

**关键结论：没有成熟的「图像 → 优化参数」专用开源模型。** 学术界的 image enhancement 模型（DeepUPE / CSRNet / AIHU）是端到端出增强图、不出可调参数，也不支持相册的「美颜+滤镜+调色」组合语义。业界通用做法就是 **VLM + prompt**。

## 3. 模型选型

PoLang **已有可直接复用的 VLM**，无需新模型：

| 模型 | 位置 | 多模态 | 适配 |
|---|---|---|---|
| **SmolVLM-500M** | 端侧（已集成，打标用） | ✅ | 改 prompt 输出 recipe；500M 偏小，调色美学判断有限 |
| **Qwen3.5-2B-MNN** | 端侧（已集成，图像理解/chat） | ✅ | 2B 比 SmolVLM 强，推荐质量更稳 |
| 远程多模态（GPT-4V / Qwen-VL / DeepSeek-VL） | 需 server vision 支持 | ✅ | 质量最好，但图片外发 + 依赖 server |

> 注：当前 chat 远程是 `deepseek-v4-flash`（偏文本），图像理解走端侧 Qwen/SmolVLM。远程推荐需先确认 server 多模态能力。

**推荐：先做端侧 VLM 版**（零新模型、零隐私外发、复用现有），质量不够再上远程多模态（server 中转，复用 LLM 网关机制）。

## 4. 架构（PoLang 适配）

```
SmartOptimizeEngine.optimize(imageUri)
  → decode bitmap
  → VLM 推理（system + few-shot + 图片 + 「输出 JSON 参数」）
  → 解析 JSON → OptimizePreset
  → AiOptimizeUseCase.smartOptimize 包装成 EditRecipe
  → RecipeApplier 渲染
```

### 新增
- `LocalVlmSmartOptimizeEngine(context, vlmEngine): SmartOptimizeEngine` —— 端侧 VLM 实现
- 注入 `AiOptimizeUseCase(smartEngine = LocalVlmSmartOptimizeEngine(...))`

### 复用（已有）
- VLM 推理：SmolVLM/Qwen 的 `imageInference(bitmap, prompt)`
- recipe 映射：`OptimizeRecipeMapper`（preset → EditRecipe）
- 渲染：`RecipeApplier`（applyGpuEffects/applyCutout/applyMarkup）

## 5. Prompt 工程（决定质量的关键）

**Few-shot + schema 比写规则更有效**（参考端侧提示词最佳实践）：

- **system**：`你是图像优化专家。分析照片的曝光/肤色/场景，推荐美颜、滤镜、调色参数。只输出 JSON。`
- **user**：`[图片]` + 输出 schema 说明
- **few-shot**（2-3 例）：
  - 过曝 → 降 brightness + 提 contrast
  - 偏暗 → 提 brightness/exposure
  - 人像 → 轻度 smoothing/whitening
- **约束**：参数范围 0..1、filter 用枚举名、严格 JSON

### 输出 JSON schema（对齐 `OptimizePreset` / `EditRecipe`）
```json
{
  "beauty": { "smoothing": 0.4, "whitening": 0.2, "slimFace": 0.1, "bigEyes": 0.0, "lipColor": 0.0, "blush": 0.0, "eyebrow": 0.0 },
  "colorFilter": "COOL",
  "styleFilter": "NONE",
  "adjustments": { "brightness": 0.1, "exposure": 0.0, "contrast": 0.15, "saturation": 0.1, "temperature": 0.0, "tint": 0.0 }
}
```

## 6. 落地步骤

1. 实现 `LocalVlmSmartOptimizeEngine`（VLM optimize → recipe JSON → `OptimizePreset`）
2. Prompt 设计（system + few-shot + schema），参数 clamp 到合法范围
3. JSON 解析（复用 `OptimizeRecipeMapper` 或新增 VLM→preset mapper）
4. `AppContainer` 注入 `AiOptimizeUseCase(smartEngine = LocalVlmSmartOptimizeEngine(...))`
5. 验证：对比 fast（固定预设）vs smart（VLM 推荐）在过曝/偏暗/人像等场景的差异

## 7. 隐私 / 质量 / 风险

### 隐私
- 端侧 VLM：100% 本地，无外发 ✅（符合 PoLang 隐私优先）
- 远程 VLM：图片外发，需用户授权 + PrivacyGuard 放行

### 质量
- 端侧小模型（500M-2B）：调色美学判断有限，推荐可能偏保守 → few-shot + schema 提升
- 远程质量好但成本/隐私高

### 风险与兜底
- VLM 输出 JSON 不规范 → 解析失败 → **降级 fast**（已有 fallback 链）
- 参数越界 → clamp 到 0..1
- 推荐不稳定 → A/B + 调 prompt + few-shot 迭代

## 8. 结论

- **不存在成熟的专用「图像→优化参数」模型**，VLM + prompt 是正解。
- PoLang 已有端侧 VLM（SmolVLM/Qwen），**零新模型**即可实现 `SmartOptimizeEngine`。
- 先做**端侧 VLM 版**（隐私、复用、离线），质量不够再上远程多模态。
- 关键在 prompt 工程（few-shot + schema），而非换更大模型。

> 关联：`AiOptimizeUseCase` / `SmartOptimizeEngine`（空接口）/ `OptimizeRecipeMapper` / `RecipeApplier`；端侧 VLM 提示词最佳实践见 [[本地 LLM 提示词 review]]。
