# Chat 对话式图片编辑设计

> **状态**：已批准待实现  
> **日期**：2026-07-18  
> **负责人**：RD Agent  
> **相关模块**：`:app`（Chat、Editor）、`:runtime-core`、`:beauty-engine` / `:beauty-api`

---

## 1. 背景与目标

PoLang 的 Chat 页已从应用首页降级为相册内的 AI 助手二级页。当前聊天已支持图片消息、相册搜索、TAG 扫描控制等能力，但尚未形成“发图即可修图”的闭环。

**本期目标**：在 Chat 页实现对话式图片编辑，让用户发送图片后通过自然语言调节美颜、滤镜、基础调色参数，并直接在聊天内看到结果图，形成可迭代的修图体验。

**非目标**：
- 智能消除/去水印（已在其他需求线开发，本期不实现）
- 局部美颜（左眼/右眼/左脸/右脸独立调节）
- AI 一键优化自动推荐（沿用现有入口，不通过 Chat 重构）

---

## 2. 核心体验

### 2.1 用户旅程

```
用户进入 Chat → 发送/选择图片 → 发送编辑指令
    ↓
AI 解析意图 → 生成 EditRecipe
    ↓
GPU 离屏渲染 → 保存结果图
    ↓
聊天内返回结果图 + 快捷建议按钮
    ↓
用户继续发指令迭代，或点击「去编辑页微调」
```

### 2.2 三类交互

| 类型 | 示例指令 | 交付方式 |
|------|----------|----------|
| 轻量参数编辑 | “磨皮 30”“调亮一点”“换成冷调” | 聊天内 inline 返回结果图 |
| 多参数组合 | “胶片风 + 提亮 + 瘦脸 20” | 返回结果图 + 「去编辑页微调」入口 |
| 未支持能力 | “去掉路人”“只放大左眼” | 友好提示“暂不支持”，给出替代路径 |

### 2.3 结果消息规范

每条 AI 编辑结果消息包含：
1. 一句话说明（如“已为你应用「胶片金」滤镜”）
2. 结果图缩略图（点击可全屏预览）
3. 快捷建议按钮（最多 3 个）：例如“再亮一点”“复古一点”“去编辑页微调”

---

## 3. 参数值域与差值方式（AI 生成参考）

当前 `EditRecipe` / `BeautySettings` 的参数值域与主流修图软件（Apple Photos、Snapseed、Lightroom Mobile、美图秀秀专业版）存在差异，导致 LLM 难以直接生成合理数值。本节统一参数语义、值域和 delta 步长，作为 `ChatEditRecipeBuilder` 和 LLM prompt 的输入标准。

### 3.1 美颜参数

| 参数 | 当前 UI 值域 | 引擎实际含义 | 语义档位 | AI 推荐输出 | 单次 delta 步长 | 竞品参考 |
|------|-------------|-------------|---------|------------|----------------|----------|
| `smoothing` 磨皮 | 0..100，默认 0 | 0-1 强度 | 无/轻/中/强 | 0 / 20 / 45 / 75 | ±15 | 美图秀秀 0-100；Facetune 0-100 |
| `whitening` 美白 | 0..100，默认 0 | 0-1 强度 | 无/轻/中/强 | 0 / 15 / 35 / 60 | ±12 | 美图秀秀 0-100 |
| `slimFace` 瘦脸 | -50..+50，默认 0 | 正值→瘦脸（引擎取反） | 窄脸/瘦脸/标准/宽脸 | -30 / -15 / 0 / +15 | ±5 | 美图秀秀 -50~+50 |
| `bigEyes` 大眼 | 0..100，默认 0 | 0-1 强度 | 无/轻/中/强 | 0 / 15 / 35 / 60 | ±12 | 美图秀秀 0-100 |
| `lipColor` 唇色 | 0..100，默认 0 | 0-1 强度 | 无/浅/中/深 | 0 / 25 / 50 / 80 | ±15 | 美图秀秀 0-100 |
| `blush` 腮红 | 0..100，默认 0 | 0-1 强度 | 无/浅/中/深 | 0 / 15 / 35 / 60 | ±12 | 美图秀秀 0-100 |
| `eyebrow` 眉毛 | 0..100，默认 0 | 0-1 强度 | 无/浅/中/深 | 0 / 15 / 35 / 60 | ±12 | 美图秀秀 0-100 |

**说明**：
- `slimFace` 在 UI 语义中正值表示瘦脸，负值表示宽脸/还原；与引擎内部相反（引擎负值瘦脸）。AI 输出统一使用 UI 语义正值=瘦脸。
- `slimFace` 单次 delta 步长限制为 ±5（全量程的 5%）。聊天场景中用户照片通常已处于较好状态，仅需微调；`ChatEditRecipeBuilder` 会对 LLM 返回的 `slim_face_delta` 做上限保护，避免一次调整过度。
- “磨皮高一点”按 `min(current + 15, 100)` 计算；“磨皮低一点”按 `max(current - 15, 0)` 计算。
- 当用户说“磨皮强一点”时，推荐从 0 → 45；说“磨皮很强”时，推荐 75。

### 3.2 基础调色参数

| 参数 | 当前 UI 值域 | 语义含义 | 语义档位 | AI 推荐输出 | 单次 delta 步长 | 竞品参考 |
|------|-------------|---------|---------|------------|----------------|----------|
| `brightness` 亮度 | -100..100，默认 0 | 0=原始 | 暗 / 偏暗 / 标准 / 偏亮 / 亮 | -40 / -15 / 0 / +15 / +40 | ±15 | Apple Photos -100~+100 |
| `exposure` 曝光 | -100..100，默认 0 | 0=原始，±50 ≈ ±1 EV | 欠曝 / 偏暗 / 标准 / 偏亮 / 过曝 | -30 / -10 / 0 / +10 / +30 | ±10 | Apple Photos -2~+2 EV |
| `contrast` 对比度 | 0..200，默认 50 | 50=原始 | 低 / 偏低 / 标准 / 偏高 / 高 | 30 / 42 / 50 / 65 / 90 | ±10 | Snapseed -100~+100（映射后） |
| `saturation` 饱和度 | 0..200，默认 100 | 100=原始 | 黑白 / 低 / 标准 / 高 / 鲜艳 | 0 / 70 / 100 / 130 / 170 | ±20 | Snapseed -100~+100 |
| `temperature` 色温 | 2000..8000K，默认 5000 | 5000=原始 | 冷 / 偏冷 / 标准 / 偏暖 / 暖 | 4200 / 4700 / 5000 / 5600 / 6500 | ±300K | Lightroom 2000-8000K |
| `tint` 色调 | -100..100，默认 0 | 0=原始 | 绿 / 偏绿 / 标准 / 偏洋红 / 洋红 | -30 / -10 / 0 / +10 / +30 | ±10 | Lightroom -100~+100 |

**说明**：
- `contrast` 和 `saturation` 当前值域不是以 0 为中心，AI 生成时容易混淆。`ChatEditRecipeBuilder` 应将自然语言先映射到以“原始”为基准的语义档位，再转回当前值域。
- “调亮一点” = `brightness + 15`；“调暗一点” = `brightness - 15`。
- “变暖一点” = `temperature + 300K`；“变冷一点” = `temperature - 300K`。
- 曝光语义接近 EV：`exposure = 50` 对应 +1 EV，因此“曝光 +1” = `exposure + 50`。

### 3.3 滤镜（Color Filter）

当前 `FilterType` 是枚举，无强度参数。为了支持“稍微冷一点”“胶片风淡一点”，建议引入可选的 `filterIntensity: Float = 1.0f`（0..1），作为 `EditRecipe` 的扩展字段。

| FilterType | 中文名 | 默认强度 | 效果描述（用于 LLM prompt） |
|------------|--------|---------|---------------------------|
| `LEICA_CLASSIC` | 徕卡经典 | 1.0 | 暗部偏青、对比度略高、肤色自然 |
| `LEICA_VIBRANT` | 徕卡鲜艳 | 1.0 | 饱和度提升、色彩更通透 |
| `LEICA_BW` | 徕卡黑白 | 1.0 | 高对比黑白，保留细节 |
| `FILM_GOLD` | 胶片金 | 1.0 | 暖黄高光、轻微颗粒感、复古 |
| `FILM_FUJI` | 胶片富士 | 1.0 | 清新偏绿、适合人像 |
| `VINTAGE` | 复古 | 1.0 | 褪色、暗角、暖调 |
| `COOL` | 冷调 | 1.0 | 整体偏蓝、清冷感 |
| `WARM` | 暖调 | 1.0 | 整体偏黄、温馨感 |

**AI 输出示例**：
- “换成胶片风” → `filter="FILM_GOLD", intensity=1.0`
- “稍微冷一点” → `filter="COOL", intensity=0.4`（叠加在当前滤镜之上）
- “原图” → `filter="NONE"`

**实现建议**：
- 若本期不改 `FilterType` 枚举，可先用 `colorFilter` + `styleFilter` 的组合来表达“强度减半”。
- 推荐在 `EditRecipe` 中新增 `filterIntensity: Float = 1.0f`，并在 `RecipeApplier` 中将强度线性混合原图与滤镜矩阵：`result = original * (1 - intensity) + filtered * intensity`。

### 3.4 风格特效（Style Filter）

风格特效（卡通、素描、海报、浮雕、交叉线）属于强风格化，建议作为“开关”而非连续强度处理：

| StyleFilter | 中文名 | AI 用法 |
|-------------|--------|--------|
| `TOON` | 卡通 | “变成卡通” |
| `SKETCH` | 素描 | “素描效果” |
| `POSTERIZE` | 海报 | “海报风” |
| `EMBOSS` | 浮雕 | “浮雕效果” |
| `CROSSHATCH` | 交叉线 | “交叉线/钢笔画” |

**注意**：风格特效与色调滤镜可叠加（当前已实现：色调滤镜先生效，风格特效后叠加）。AI 生成时应避免“胶片风 + 卡通”这类冲突组合，或明确告知用户这是艺术化叠加。

### 3.5 LLM 输出格式建议

为了让 LLM 生成结构化编辑意图，建议 prompt 中给出如下 JSON Schema：

```json
{
  "edit": {
    "beauty": {
      "smoothing": "0-100 or 'up'/'down'/'same'",
      "whitening": "0-100 or 'up'/'down'/'same'",
      "slimFace": "-50 to +50 or 'up'/'down'/'same'",
      "bigEyes": "0-100 or 'up'/'down'/'same'"
    },
    "adjustments": {
      "brightness": "-100 to 100 or 'up'/'down'/'same'",
      "exposure": "-100 to 100 or 'up'/'down'/'same'",
      "contrast": "0-200 or 'up'/'down'/'same'",
      "saturation": "0-200 or 'up'/'down'/'same'",
      "temperature": "2000-8000 or 'up'/'down'/'same'",
      "tint": "-100 to 100 or 'up'/'down'/'same'"
    },
    "filter": {
      "name": "NONE/LEICA_CLASSIC/FILM_GOLD/COOL/WARM/...",
      "intensity": "0.0-1.0"
    },
    "style": "NONE/TOON/SKETCH/..."
  },
  "explanation": "给用户的一句话说明"
}
```

`ChatEditRecipeBuilder` 负责把相对描述（`up`/`down`）转换为基于当前 Recipe 的绝对数值。

---

## 4. 架构与组件

### 4.1 新增组件

| 组件 | 位置 | 职责 |
|------|------|------|
| `ChatEditRecipeBuilder` | `app/src/main/java/com/mamba/picme/domain/model/` | 将 LLM 输出的结构化编辑意图（如 `filter_name=胶片金`）映射为 `EditRecipe` |
| `ChatEditProcessor` | `app/src/main/java/com/mamba/picme/domain/usecase/` | 接收 `EditRecipe` 与原图 URI，复用 `PhotoEditorViewModel` 的渲染链路生成结果图并保存 |
| `ImageEditCapability` | `app/src/main/java/com/mamba/picme/domain/agent/capability/` | Capability 接口实现，接收 `AgentCommand.EditImage` 并分发到 `ChatEditProcessor` |
| `ChatEditStateHolder` | `app/src/main/java/com/mamba/picme/features/chat/` | 以 `sessionId` 为 key 维护每个会话的当前 `EditRecipe`，供多轮 delta 调整读取 |

### 4.2 复用组件

- `PhotoEditorViewModel` 的 Recipe → Bitmap 渲染链路
- `CapabilityRegistry` 注册 `ImageEditCapability`
- `AgentOrchestrator.streamChat` 处理自然语言输入
- `ChatMessageDao` / `ChatSessionDao` 持久化结果消息

### 4.3 扩展点

- `:runtime-core` 的 `AgentCommand` 密封类新增 `EditImage(params: EditParams, imageUri: String, explanation: String?)`，其中 `EditParams` 包含美颜/滤镜/调度的原始意图字段（不直接依赖 `:app` 的 `EditRecipe`）。
- `:app` 的 `ChatMessageType` 新增 `AGENT_EDIT_RESULT` 用于展示结果图与快捷按钮；UI 上与现有 `AGENT_IMAGE` 的区别在于附带说明文字和最多 3 个快捷操作按钮。
- `:app` 新增 `ChatEditStateHolder`，以 `sessionId` 为 key 保存当前 `EditRecipe`，供多轮 delta 调整读取；不依赖 `AgentContext` 携带 Recipe，避免跨模块污染。
- `:app` 的 `EditRecipe` 建议新增 `filterIntensity: Float = 1.0f` 以支持滤镜强度调节。

---

## 5. 数据流

以“发送图片 + 说调成胶片风”为例：

1. **ChatScreen**：用户发送图片与文本。
2. **ChatViewModel**：保存 `user_image_text` 消息，更新 `_lastUserImageUri`。
3. **AgentOrchestrator.streamChat**：调用远程 DeepSeek 或本地 Qwen，输出结构化编辑意图（如 `{ "filter_name": "胶片金", "filter_intensity": 1.0, "brightness_delta": 15 }`）。
4. **LocalCommandParser**：将结构化意图解析为 `AgentCommand.EditImage(editParams: EditParams, imageUri: String)`。
5. **ImageEditCapability.dispatch**：调用 `ChatEditRecipeBuilder.build(editParams, currentRecipe)` 得到完整 `EditRecipe`。
6. **ChatEditProcessor.execute**：复用 `PhotoEditorViewModel` 的渲染链路生成结果 Bitmap，保存到 `Pictures/PoLang`。
7. **ChatEditStateHolder**：更新当前会话的 `currentRecipe`。
8. **ChatViewModel**：插入 `AGENT_EDIT_RESULT` 消息（结果图 + 说明 + 快捷按钮）。

### 5.1 多轮编辑状态

- `ChatEditStateHolder` 每个会话维护一个 `currentRecipe`，key 为 `sessionId`。
- 用户说“再亮一点”时，`ChatEditRecipeBuilder` 从 `ChatEditStateHolder` 读取当前 Recipe，将亮度在现有值基础上增加固定步长（+15）。
- 用户切换会话、清空对话或发送新图片时，`ChatEditStateHolder` 重置当前 Recipe。
- 用户切换会话后编辑状态隔离。

---

## 6. 自然语言映射规则

### 6.1 美颜参数

映射以当前 `EditRecipe` 为基准执行 delta 或绝对值设置：

| 用户说法 | 映射 |
|----------|------|
| “磨皮 30” | `beauty.smoothing = 30` |
| “磨皮高一点” | `beauty.smoothing = min(current.smoothing + 15, 100)` |
| “美白归零” | `beauty.whitening = 0` |
| “瘦脸 20” | `beauty.slimFace = 20` |
| “大眼强一点” | `beauty.bigEyes = min(current.bigEyes + 15, 100)` |

### 6.2 滤镜

| 用户说法 | 映射 |
|----------|------|
| “胶片风” | `colorFilter = FilterType.FILM_GOLD, filterIntensity = 1.0` |
| “稍微冷一点” | `colorFilter = FilterType.COOL, filterIntensity = 0.4` |
| “复古” | `colorFilter = FilterType.VINTAGE, filterIntensity = 1.0` |
| “原图” | `colorFilter = FilterType.NONE, filterIntensity = 0.0` |

### 6.3 基础调色

| 用户说法 | 映射 |
|----------|------|
| “调亮一点” | `adjustments.brightness = current.brightness + 15` |
| “对比度拉高” | `adjustments.contrast = current.contrast + 10` |
| “饱和度降低” | `adjustments.saturation = current.saturation - 20` |
| “色温偏暖” | `adjustments.temperature = current.temperature + 300K` |

---

## 7. 错误处理与降级

| 场景 | 处理策略 | 用户反馈 |
|------|----------|----------|
| LLM 解析失败/意图不明 | 返回澄清卡片 | “你可以说：磨皮 30、换成胶片风、调亮一点” |
| 图片加载失败 | 提示重试 | “图片加载失败，请重新发送” |
| GPU 渲染超时/OOM | 降级为跳转 Editor 预填参数 | “参数已准备好，请在编辑页查看效果” |
| 提到未支持能力（消除/局部美颜） | 友好提示 | “智能消除正在开发中，你可以先在编辑页手动处理” |

**兜底规则**：
- 所有编辑结果保存为新文件，不覆盖原图。
- 若 inline 渲染失败，自动把 Recipe 通过导航参数传给 `PhotoEditorScreen`。

---

## 8. 测试策略

| 层级 | 测试对象 | 关键用例 |
|------|----------|----------|
| Unit | `ChatEditRecipeBuilder` | “磨皮 30” → `smoothing=30`；“冷调” → `FilterType.COLD` |
| Unit | `LocalCommandParser` | 验证 LLM JSON 输出能解析为 `EditImage` 命令 |
| Integration | `ChatEditProcessor + BeautyEngine` | 输入 Recipe，验证输出 bitmap 尺寸与颜色通道变化 |
| Integration | `ImageEditCapability + CapabilityRegistry` | `AgentCommand.EditImage` 正确分发并返回 `AgentAction` |
| UI | `ChatScreen` | 结果图消息展示、快捷按钮点击、跳转 Editor |

---

## 9. 验收标准

- [ ] 支持 20+ 条自然语言编辑指令，意图识别准确率 ≥ 90%。
- [ ] 单图轻量编辑端到端延迟 < 2s（含 LLM 推理 + GPU 渲染 + 保存）。
- [ ] 多轮 delta 调整（连续 3 次“再亮一点”）结果正确递增。
- [ ] 所有编辑结果不覆盖原图，保存至 `Pictures/PoLang`。
- [ ] 提及智能消除等未支持能力时，给出友好提示而非报错。
- [ ] 新增 Capability 不破坏现有 Chat 搜索、相册摘要、TAG 扫描能力。

---

## 10. 相关文档与代码

- `PRODUCT.md` — 产品定位与路线图
- `docs/01-PRODUCT/FEATURES.md` — 交互规范
- `app/AGENTS.md` — App 模块架构
- `runtime-core/AGENTS.md` — Agent Runtime 规范
- `beauty-engine/src/main/java/com/mamba/picme/beauty/api/BeautyParamsConverter.kt` — 参数到引擎的映射
- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt`
- `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt`
- `app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt`
