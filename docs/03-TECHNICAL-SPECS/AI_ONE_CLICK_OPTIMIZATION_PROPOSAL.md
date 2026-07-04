# PicMe AI 一键图片优化方案

> **文档类型**：产品 + 技术方案（Product & Technical Proposal）  
> **针对能力**：AI 一键优化（AI One-Click Image Optimization）  
> **产品背景**：新路线下相册首页为默认入口，AI 对话为二级助手，IM 远程为 P2 实验线  
> **最后更新**：2026-07-03  
> **维护者**：PM Agent（产品定义）+ RD Agent（技术实现）

---

## 1. 核心结论

AI 一键优化是新路线下最值得投入的 P0 能力之一：

- **用户价值清晰**："帮我让这张照片更好看"是相册/编辑场景的高频、低学习成本诉求
- **技术基础已具备**：大美丽美颜管线、`EditRecipe` 非破坏性编辑、ML Kit 图像识别、本地 Qwen3.5-2B 多模态理解、远程 OpenAI 协议推理均已落地
- **可行性高**：优先做「本地规则 + 远程增强」的混合方案，MVP 可在 2-3 周内验证核心体验
- **与新产品路线契合**：入口自然（相册查看器/编辑器），不依赖聊天首页假设，也不强依赖 IM 远程

**推荐策略**：
- **MVP（Phase 1）**：本地快速优化为主，覆盖 80% 常见场景，端到端 < 1s
- **Phase 2**：引入远程视觉模型做「智能推荐」，处理复杂/模糊场景，首次使用需用户授权
- **Phase 3**：对话式微调、批量优化、个性化学习

---

## 2. 产品定义

### 2.1 一句话描述

用户一键触发，系统自动识别照片场景并应用最优美颜 + 滤镜 + 调节参数，生成更好看的新照片。

### 2.2 解决什么问题

| 用户痛点 | 当前做法 | AI 一键优化后 |
|----------|----------|---------------|
| 不知道参数怎么调 | 手动滑块试错 | 一键给出推荐，可在此基础上微调 |
| 修图步骤繁琐 | 打开编辑 → 调美颜 → 调滤镜 → 保存 | 一键完成，直接进入对比/保存 |
| 不同场景参数差异大 | 凭感觉调 | 基于场景（人像/风景/美食/文档）自动匹配 |
| 批量修图累 | 一张张手动调 | 选中多图后批量应用同一套优化逻辑 |

### 2.3 不做的事情

- 不做「AI 换脸/重绘」等生成式修改（超出美颜/滤镜/调节范围）
- 不做「自动保存覆盖原图」（必须是非破坏性编辑）
- 不做「无授权云端处理」（遵循新隐私红线）

---

## 3. 入口与交互流程

### 3.1 入口

| 入口 | 优先级 | 说明 |
|------|--------|------|
| **媒体查看器工具栏** | P0 | 查看单图时顶部工具栏「AI 优化」按钮，最自然的一键入口 |
| **编辑器内「AI 一键优化」** | P0 | 编辑页顶部/BeautySelector 面板中的「AI 优化」按钮 |
| **相册多选批量操作** | P1 | 长按多选后底部操作栏「AI 优化」 |
| **AI 对话命令** | P1 | "帮我优化这张照片"，结果以图片消息返回 |
| **IM 远程控制** | P2 | 实验线，与 IM 远程控制同步推进 |

### 3.2 单图优化流程

```
用户点击「AI 优化」
    ↓
系统显示「正在分析照片…」（< 500ms 本地路径可跳过 loading）
    ↓
本地场景识别（人脸/场景标签/光线）
    ↓
匹配预设配方（本地规则引擎）
    ↓
生成推荐卡片：
  ┌─────────────────────────────┐
  │  🌇 检测到风景照片            │
  │  已提升通透度、增强天空色彩    │
  │  [应用]  [微调]              │
  └─────────────────────────────┘
    ↓
用户选择：
  - 应用 → 直接保存为新文件，进入对比模式
  - 微调 → 进入编辑器，参数已预填，用户可继续调整
  - 换一换 → 调用远程视觉模型重新推荐（需授权）
```

### 3.3 编辑器内流程

```
用户进入编辑页
    ↓
点击「AI 一键优化」
    ↓
系统生成 EditRecipe 并应用到预览
    ↓
用户可立即拖动滑块覆盖任一参数
    ↓
保存为新文件（原图不动）
```

### 3.4 批量优化流程

```
相册首页 → 长按进入多选 → 选择 N 张照片 → 点击「AI 优化」
    ↓
系统对每张照片分别做本地场景识别
    ↓
按场景分组（人像/风景/美食…）应用组内统一配方
    ↓
后台依次处理，完成后进入相册并高亮新文件
```

---

## 4. 技术方案

### 4.1 总体架构：本地优先 + 云端增强

```
┌─────────────────────────────────────────────────────────────┐
│                      AiOptimizeCapability                    │
│  输入：imageUri + mode(fast|smart) + source                   │
│  输出：OptimizeRecommendation（场景 + 配方 + 说明）            │
└───────────────────────────┬─────────────────────────────────┘
                            │
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
┌───────────────┐                       ┌───────────────────┐
│ FastOptimize  │  默认路径，< 500ms     │ SmartOptimize     │  增强路径，1-3s
│ Engine（本地） │  无需网络              │ Engine（远程）    │  需用户授权
└───────┬───────┘                       └─────────┬─────────┘
        │                                         │
        ▼                                         ▼
┌───────────────┐                       ┌───────────────────┐
│ SceneAnalyzer │                       │ VisionLlmClient   │
│ - ML Kit 图像标签 │                   │ - OpenAI 兼容 API │
│ - 人脸检测      │                       │ - GPT-4o / Qwen-VL│
│ - 光线/色彩启发式│                      │ - 压缩图 base64   │
└───────┬───────┘                       └─────────┬─────────┘
        │                                         │
        ▼                                         ▼
┌───────────────┐                       ┌───────────────────┐
│ PresetRepo    │                       │ ResponseParser    │
│ 本地 JSON 预设库│                       │ 解析推荐参数        │
└───────────────┘                       └───────────────────┘
```

### 4.2 Fast 路径（本地，默认）

**为什么优先本地**：
- 符合新隐私红线「敏感数据优先本地」
- 速度最快，用户体验最好
- 不依赖网络，离线可用
- 80% 常见场景可用规则覆盖

**场景识别输入**：

| 信号 | 来源 | 用途 |
|------|------|------|
| 人脸数量/位置/大小 | MediaPipe / ML Kit Face Detection | 判断人像/自拍/合影 |
| 图像标签 Top-K | ML Kit Image Labeling | 判断风景/食物/文档/动物/建筑等 |
| EXIF 信息 | MetadataExtractor | 判断白天/夜晚/逆光、焦距 |
| 直方图/亮度统计 | GPU 管线采样 | 判断过曝/欠曝/低对比度 |
| 色彩分布 | 简单色彩直方图 | 判断偏暖/偏冷/饱和度 |

**规则引擎示例**：

```kotlin
when {
    faceCount >= 2 && faceRatio > 0.1 -> "group_portrait"
    faceCount == 1 && faceRatio > 0.3 -> "selfie"
    faceCount == 1 && faceRatio < 0.3 -> "portrait"
    labels.containsAny("Food", "Meal", "Dish") -> "food"
    labels.containsAny("Sky", "Mountain", "Sea", "Tree") -> "landscape"
    labels.containsAny("Document", "Text") -> "document"
    brightness < 40 -> "low_light"
    else -> "general"
}
```

**预设配方示例**（`presets.json`）：

```json
{
  "portrait_day": {
    "beauty": { "smooth": 40, "whiten": 30, "slimFace": 8, "bigEye": 20, "lipColor": 35, "blush": 15 },
    "filter": "film_gold",
    "adjustment": { "brightness": 5, "contrast": 5, "saturation": 2, "warmth": 3 }
  },
  "selfie": {
    "beauty": { "smooth": 50, "whiten": 35, "slimFace": 12, "bigEye": 25, "lipColor": 40, "blush": 20 },
    "filter": "natural",
    "adjustment": { "brightness": 8, "contrast": 3, "saturation": 0, "warmth": 2 }
  },
  "food": {
    "beauty": { "enabled": false },
    "filter": "vivid",
    "adjustment": { "brightness": 0, "contrast": 8, "saturation": 12, "warmth": 5 }
  },
  "landscape": {
    "beauty": { "enabled": false },
    "filter": "leica_vivid",
    "adjustment": { "brightness": 0, "contrast": 10, "saturation": 8, "warmth": 0, "shadow": -5, "highlight": 5 }
  },
  "low_light": {
    "beauty": { "smooth": 30, "whiten": 20 },
    "filter": "warm",
    "adjustment": { "brightness": 15, "contrast": 5, "saturation": -3, "shadow": 10 }
  }
}
```

### 4.3 Smart 路径（远程，增强）

**触发条件**：
- 用户主动点击「换一换 / 智能推荐」
- Fast 路径置信度低于阈值（如 < 0.6）
- 用户设置了「智能推荐优先」

**实现方式**：

1. **图像编码**：将原图缩放至 512px 长边，JPEG 质量 80，转 base64
2. **模型选择**：通过 OpenAI 兼容 API 调用视觉模型
   - 推荐：GPT-4o-mini（成本可控）或 Qwen-VL-Max
   - 避免依赖 DeepSeek（当前公开版本无 vision 能力）
3. **Prompt 设计**：

```
你是一位手机修图专家。请分析这张图片，并返回 JSON 推荐修图参数。

输出格式：
{
  "scene": "场景英文名",
  "scene_label_zh": "中文场景名",
  "confidence": 0.0-1.0,
  "recipe": {
    "beauty": {"smooth":0-100, "whiten":0-100, "slimFace":-50~50, "bigEye":0-100, "lipColor":0-100, "blush":0-100, "enabled":true|false},
    "filter": "none|film_gold|vivid|natural|warm|cool|leica_classic|...",
    "adjustment": {"brightness":-50~50, "contrast":-50~50, "saturation":-50~50, "warmth":-50~50}
  },
  "explanation": "一句话说明做了什么优化"
}
```

4. **隐私合规**：
   - 首次调用前弹窗告知「将上传压缩图片至云端模型，是否允许？」
   - 设置中提供「允许云端 AI 优化」开关
   - 不在云端存储用户图片

### 4.4 本地 Qwen3.5-2B 多模态作为离线 fallback

项目已有的 `TagGenerationPipeline` Pass 3 使用 Qwen3.5-2B 做图像理解。可探索复用该路径：

- 输入：压缩图
- 输出：场景标签 + 简短描述
- 映射到本地预设

**当前限制**：
- 2B 模型输出 JSON 稳定性有限
- 推理速度约 1-3s（取决于设备）
- 功耗/发热较高

**建议**：不作为默认路径，仅作为无网络且用户未禁用云端时的降级提示："当前无法使用智能推荐，已应用本地优化"。

---

## 5. 数据模型与 Capability 接口

### 5.1 Capability 注册

在 `CapabilityRegistry` 中新增：

```kotlin
Capability(
    name = "ai_optimize",
    description = "分析照片场景并自动推荐美颜/滤镜/调节参数，支持一键优化",
    schema = AiOptimizeCapabilitySchema
)
```

### 5.2 输入模型

```kotlin
data class AiOptimizeRequest(
    val imageUri: String,
    val mode: OptimizeMode = OptimizeMode.FAST,
    val source: OptimizeSource = OptimizeSource.GALLERY_VIEWER,
    val allowCloud: Boolean = false
)

enum class OptimizeMode { FAST, SMART }
enum class OptimizeSource { GALLERY_VIEWER, EDITOR, CHAT, BATCH, IM_REMOTE }
```

### 5.3 输出模型

```kotlin
data class AiOptimizeResult(
    val scene: String,
    val sceneLabel: String,
    val confidence: Float,
    val recipe: EditRecipe,
    val explanation: String,
    val usedCloud: Boolean,
    val processingTimeMs: Long
)
```

### 5.4 与 Editor 集成

```kotlin
// PhotoEditorViewModel
fun applyAiOptimize(result: AiOptimizeResult) {
    val newRecipe = currentRecipe.copy(
        beauty = result.recipe.beauty,
        colorFilter = result.recipe.colorFilter,
        adjustments = result.recipe.adjustments
    )
    history.push(newRecipe)
    renderPreview()
}
```

### 5.5 与 Agent 集成

本地/远程 Agent 均可通过 tool call 调用：

```json
// 用户指令："帮我优化这张照片"
{
  "name": "ai_optimize",
  "arguments": "{\"imageUri\":\"file:///...\",\"mode\":\"fast\"}"
}
```

---

## 6. 实现路径

### 6.1 MVP（Phase 1，2-3 周）

目标：验证核心体验，覆盖最常见场景。

**必须完成**：
1. `AiOptimizeCapability` 框架与 `FastOptimizeEngine`
2. 5 个核心场景预设：
   - 人像（单人）
   - 自拍
   - 合影
   - 美食
   - 风景
3. 媒体查看器「AI 优化」按钮
4. 编辑器内「AI 一键优化」入口
5. 应用后进入对比模式
6. 保存为新文件，不覆盖原图

**验收标准**：
- 本地路径端到端 < 1s（中端机）
- 5 个场景参数合理（PM + 设计师评审）
- 用户可一键应用并保存

### 6.2 Phase 2（4-6 周）

1. 扩展 preset 到 10-15 个场景（夜景、文档、宠物、建筑、室内等）
2. 引入 `SmartOptimizeEngine`（远程视觉模型）
3. 隐私授权流程
4. 对话命令支持："帮我优化这张照片"
5. 批量优化（相册多选）
6. A/B 测试框架：对比不同 preset 的保存率

### 6.3 Phase 3（8-12 周）

1. 对话式微调："再亮一点""磨皮少一点"
2. 个性化学习：根据用户历史调整推荐（本地）
3. IM 远程控制支持
4. 专业模式：允许用户保存自己的 preset

---

## 7. 可行性评估

### 7.1 技术可行性

| 组件 | 状态 | 说明 |
|------|------|------|
| 美颜/滤镜/调节渲染 | ✅ 已落地 | 大美丽引擎 + `PhotoProcessor` + `EditRecipe` |
| 人脸检测 | ✅ 已落地 | MediaPipe / MNN / NCNN |
| 图像标签识别 | ✅ 已落地 | ML Kit Image Labeling 已在 TAG 系统使用 |
| 远程 OpenAI 协议推理 | ✅ 已落地 | `:agent-core` `OpenAiChatModel` |
| 本地多模态理解 | ⚠️ 已落地但能力有限 | Qwen3.5-2B 可做简单图像理解，不建议作为默认路径 |
| 视觉模型接入 | ⚠️ 需评估 | 需要接入支持 vision 的模型（GPT-4o / Qwen-VL） |
| 编辑器非破坏性编辑 | ✅ 已落地 | `EditRecipe` + `RecipeApplier` |

**结论**：MVP 完全可行，无需等待任何新技术。Smart 路径需要接入 vision 模型，但基础设施已具备。

### 7.2 产品可行性

| 维度 | 评估 |
|------|------|
| 用户需求 | 高。修图 app 的「一键优化/自动增强」是标配功能 |
| 学习成本 | 极低。用户只需点一个按钮 |
| 差异化 | 中。结合本地美颜管线，可实现比系统相册更强的优化 |
| 隐私合规 | 可控。Fast 路径完全本地；Smart 路径需授权 |
| 资源投入 | 中。MVP 2-3 周，1 名 RD 可完成 |

### 7.3 商业/实验价值

- 提升编辑器完成率和保存率
- 为后续对话式编辑、批量处理、IM 远程提供基础能力
- 可量化验证 AI 在相册场景的用户价值

---

## 8. 风险与应对

| 风险 | 可能性 | 影响 | 应对 |
|------|--------|------|------|
| 推荐参数质量不稳定 | 中 | 用户觉得「优化了但没变好」 | 1) 先做 5 个高置信场景；2) 预设由设计师/PM 审定；3) 提供「撤销/微调」 |
| 远程视觉模型成本/延迟 | 中 | Smart 路径体验差 | 1) 默认本地路径；2) 压缩图至 512px；3) 使用 GPT-4o-mini 等低成本模型 |
| 隐私授权阻碍使用 | 低 | Smart 路径转化率低 | 1) Fast 路径无需授权；2) 明确告知用途；3) 不存储图片 |
| 端侧性能不足 | 低 | 本地分析 + 渲染卡顿 | 1) 场景识别在子线程；2) 降采样分析；3) 低端机跳过复杂 preset |
| 用户期望过高 | 中 | 期望 AI 修图无所不能 | 产品文案强调「辅助优化」而非「专业修图」 |

---

## 9. 验收指标

| 指标 | MVP 目标 | 测量方式 |
|------|----------|----------|
| AI 优化按钮点击率 | > 30%（进入查看器/编辑器的用户） | 埋点 |
| 应用率（点击后保存） | > 60% | 埋点 |
| 微调率（点击后进入编辑器调整） | < 40% | 埋点 |
| 本地路径处理时间 | < 1s（P50） | 日志 |
| 场景识别准确率 | > 80%（5 个核心场景） | 人工标注样本 |
| 崩溃率 | 0 | Crashlytics |

---

## 10. 与现有系统的集成点

| 系统 | 集成方式 |
|------|----------|
| `CapabilityRegistry` | 注册 `ai_optimize` capability |
| `AgentOrchestrator` | 支持从对话/IM 触发 `ai_optimize` tool call |
| `PhotoEditorViewModel` | 接收 `AiOptimizeResult` 并生成 `EditRecipe` |
| `PhotoProcessor` | 应用 recipe 到预览和输出 |
| `MediaRepository` | 保存优化后的新文件到相册 |
| `BeautySettings` / `AdjustmentRecipe` / `FilterType` | 复用现有数据模型 |
| `MlKitTagExtractor` | 复用 ML Kit 图像标签能力 |
| `FaceDetector` | 复用人脸检测能力 |
| `OpenAiChatModel`（remote） | Smart 路径调用视觉模型 |

---

## 11. 建议的下一步

1. **PM/设计评审**：确认 5 个 MVP 场景和对应 preset 参数
2. **RD 技术预研**：
   - 验证 ML Kit Image Labeling 在典型场景下的标签稳定性
   - 评估接入 GPT-4o-mini / Qwen-VL 的成本和延迟
   - 确定压缩图尺寸和 base64 体积
3. **产出 PRD 细化**：将本方案转化为 FEATURES.md 中的交互细节和 `agent-task`
4. **启动 MVP 开发**：`AiOptimizeCapability` + Fast 路径 + 媒体查看器入口

---

## 附录：典型场景预设参考（V1）

| 场景 | 美颜 | 滤镜 | 亮度 | 对比 | 饱和 | 色温 | 适用条件 |
|------|------|------|------|------|------|------|----------|
| 自拍 | 磨皮 50 美白 35 瘦脸 12 大眼 25 | natural | +8 | +3 | 0 | +2 | 人脸 1 个，占画面 > 30% |
| 单人像 | 磨皮 40 美白 30 瘦脸 8 大眼 20 | film_gold | +5 | +5 | +2 | +3 | 人脸 1 个，占画面 < 30% |
| 合影 | 磨皮 35 美白 25 瘦脸 5 | natural | +5 | +3 | 0 | +2 | 人脸 ≥ 2 个 |
| 美食 | 关闭 | vivid | 0 | +8 | +12 | +5 | 标签含 Food/Meal/Dish |
| 风景 | 关闭 | leica_vivid | 0 | +10 | +8 | 0 | 标签含 Sky/Mountain/Sea |
| 夜景 | 磨皮 30 美白 20 | warm | +15 | +5 | -3 | +5 | 亮度统计 < 40 |
| 文档 | 关闭 | none | +10 | +15 | -10 | 0 | 标签含 Document/Text |

> 以上参数为初始参考，需经实际样本调优。

---

## 12. Agent Task 拆解

> 以下任务按 `agent-task` 规范拆解，可直接被 CO Agent 解析并分配给 RD/QA/CR。

### 12.1 任务总览

| Task ID | 名称 | Assignee | Priority | 依赖 |
|---------|------|----------|----------|------|
| `aio-001` | 场景分析器 SceneAnalyzer | RD | P0 | - |
| `aio-002` | 本地预设库 PresetRepository | RD | P0 | - |
| `aio-003` | AI 优化 Capability | RD | P0 | aio-001, aio-002 |
| `aio-004` | 媒体查看器 AI 优化入口 | RD | P0 | aio-003 |
| `aio-005` | 编辑器 AI 一键优化入口 | RD | P0 | aio-003 |
| `aio-006` | 优化结果保存与对比模式 | RD | P0 | aio-004, aio-005 |
| `aio-007` | 隐私授权与设置开关 | RD | P1 | aio-003 |
| `aio-008` | 远程 Smart 优化引擎 | RD | P1 | aio-007 |
| `aio-009` | AI 对话命令集成 | RD | P1 | aio-003 |
| `aio-010` | 批量 AI 优化 | RD | P1 | aio-003, aio-006 |
| `aio-011` | 验收测试与性能基准 | QA | P0 | aio-004, aio-005, aio-006 |
| `aio-012` | 架构合规审查 | CR | P0 | aio-003 完成后 |

---

### [agent-task:aio-001] 场景分析器 SceneAnalyzer

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/analyzer/SceneAnalyzer.kt`
- **Expected Change**:
  1. 定义 `Scene` 枚举（selfie, portrait, group, food, landscape, low_light, document, general）
  2. 集成 ML Kit Image Labeling 获取 Top-K 标签
  3. 复用 `FaceDetector` 获取人脸数量与占画面比例
  4. 读取 EXIF 与亮度统计作为辅助信号
  5. 实现规则引擎，将多路信号映射为 `Scene` + 置信度
  6. 添加单元测试覆盖 5 个核心场景
- **Priority**: P0
- **Acceptance**:
  - 5 个核心场景识别准确率 > 80%（人工标注 50 张测试集）
  - 单张分析时间 < 200ms（中端机）
  - 单元测试覆盖率 > 60%

---

### [agent-task:aio-002] 本地预设库 PresetRepository

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/preset/PresetRepository.kt`
- **Expected Change**:
  1. 定义 `OptimizePreset` 数据类（包含 beauty, filter, adjustment）
  2. 在 `assets/presets/optimize_presets.json` 中配置 5 个 MVP 场景 preset
  3. 实现从 JSON 加载并缓存到内存
  4. 提供 `getPreset(scene: Scene): OptimizePreset` 接口
  5. 添加单元测试验证 preset 可正确反序列化
- **Priority**: P0
- **Acceptance**:
  - 5 个 MVP preset 可被正确加载
  - preset 参数范围符合 `BeautySettings` / `AdjustmentRecipe` 约束
  - PM + 设计师评审通过参数合理性

---

### [agent-task:aio-003] AI 优化 Capability

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/AiOptimizeCapability.kt`
- **Expected Change**:
  1. 实现 `Capability` 接口，name = `"ai_optimize"`
  2. 定义 `AiOptimizeRequest` / `AiOptimizeResult` 数据类
  3. 组合 `SceneAnalyzer` + `PresetRepository` 实现 Fast 路径
  4. 注册到 `CapabilityRegistry`
  5. 添加错误处理：分析失败时返回降级 preset
  6. 添加单元测试
- **Priority**: P0
- **Acceptance**:
  - 可被 AgentOrchestrator 通过 tool call 调用
  - Fast 路径端到端 < 500ms
  - 失败时 graceful 降级，不崩溃

---

### [agent-task:aio-004] 媒体查看器 AI 优化入口

- **Assignee**: RD
- **Scope**: `features/gallery/components/MediaPager.kt` 或 `features/gallery/components/MediaViewer.kt`
- **Expected Change**:
  1. 在媒体查看器顶部工具栏添加「AI 优化」按钮
  2. 点击后调用 `AiOptimizeCapability`
  3. 显示推荐卡片（场景图标 + 说明 + 应用/微调/换一换）
  4. 处理加载态、空态、错误态
  5. 应用后进入对比模式
  6. 所有文案提取到 strings.xml 并三语同步
- **Priority**: P0
- **Acceptance**:
  - 按钮在图片查看器可见，视频隐藏
  - 推荐卡片展示符合交互规范
  - 用户可一键应用并保存

---

### [agent-task:aio-005] 编辑器 AI 一键优化入口

- **Assignee**: RD
- **Scope**: `features/editor/PhotoEditorScreen.kt`
- **Expected Change**:
  1. 在 BeautySelector 面板或顶部工具栏添加「AI 一键优化」按钮
  2. 点击后直接生成 `EditRecipe` 并应用到预览
  3. 参数预填后用户可立即微调
  4. 撤销栈正确处理 AI 优化步骤
  5. 所有文案提取到 strings.xml 并三语同步
- **Priority**: P0
- **Acceptance**:
  - 编辑器内点击后 200ms 内预览更新
  - 撤销/重做正常工作
  - 保存后生成新文件

---

### [agent-task:aio-006] 优化结果保存与对比模式

- **Assignee**: RD
- **Scope**: `features/editor/PhotoEditorViewModel.kt` + `data/repository/MediaRepository.kt`
- **Expected Change**:
  1. 将 `AiOptimizeResult.recipe` 转换为 `EditRecipe`
  2. 调用 `PhotoProcessor` 生成优化后图片
  3. 保存为新文件到相册（不覆盖原图）
  4. 保存后自动进入对比模式（左右分屏）
  5. 添加保存进度反馈
- **Priority**: P0
- **Acceptance**:
  - 原图不被覆盖
  - 新文件可在相册中查看
  - 对比模式可正常退出

---

### [agent-task:aio-007] 隐私授权与设置开关

- **Assignee**: RD
- **Scope**: `features/settings/SettingsAiAgent.kt` + `domain/agent/capability/optimize/CloudOptimizeConsentManager.kt`
- **Expected Change**:
  1. 首次触发 Smart 路径时显示授权说明弹窗
  2. 设置页新增「允许云端 AI 优化」开关
  3. 将用户授权状态持久化到 DataStore
  4. 用户拒绝后禁用 Smart 路径，仅保留 Fast 路径
  5. 所有文案三语同步
- **Priority**: P1
- **Acceptance**:
  - 首次触发 Smart 路径必现授权弹窗
  - 开关状态跨重启保持
  - 拒绝后不再请求授权（除非用户在设置中重新开启）

---

### [agent-task:aio-008] 远程 Smart 优化引擎

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/smart/SmartOptimizeEngine.kt`
- **Expected Change**:
  1. 将图片压缩至 512px 长边、JPEG 80，转 base64
  2. 通过 `:agent-core` `OpenAiChatModel` 调用视觉模型（GPT-4o-mini / Qwen-VL）
  3. 设计 system prompt + user message，要求返回固定 JSON 格式
  4. 解析返回结果并转换为 `OptimizePreset`
  5. 处理网络超时、模型不可用等异常
  6. 添加单元测试（mock 响应）
- **Priority**: P1
- **Acceptance**:
  - 仅在有授权且网络可用时调用
  - 端到端 < 3s（网络良好）
  - JSON 解析失败时 fallback 到 Fast 路径

---

### [agent-task:aio-009] AI 对话命令集成

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/` + Chat 相关 tool call 处理
- **Expected Change**:
  1. 让 Agent 能识别 "帮我优化这张照片" 等意图
  2. 调用 `AiOptimizeCapability` 处理图片
  3. 将优化后图片作为图片消息插入对话
  4. 附带一句话说明（如"已为你优化这张照片，提升了肤色通透度"）
- **Priority**: P1
- **Acceptance**:
  - 在 Chat 页发送图片 + 优化指令可成功执行
  - 优化后图片正确显示在消息列表

---

### [agent-task:aio-010] 批量 AI 优化

- **Assignee**: RD
- **Scope**: `features/gallery/` 多选模式 + `domain/agent/capability/optimize/batch/BatchOptimizer.kt`
- **Expected Change**:
  1. 长按多选后底部操作栏添加「AI 优化」
  2. 对选中图片逐个走 Fast 路径（禁用 Smart）
  3. 按场景分组应用统一配方
  4. 后台处理，显示进度浮层
  5. 完成后在相册高亮新文件
  6. 限制单次最多 50 张
- **Priority**: P1
- **Acceptance**:
  - 批量处理不阻塞 UI
  - 完成后面片正常显示
  - 处理失败时给出部分成功提示

---

### [agent-task:aio-011] 验收测试与性能基准

- **Assignee**: QA
- **Scope**: `docs/06-QA/QA_EXECUTION_CHECKLIST.md` + 手动测试
- **Expected Change**:
  1. 补充 AI 一键优化测试用例到 QA checklist
  2. 准备 50 张覆盖 5 个核心场景的测试样本
  3. 测量并记录：按钮点击率、应用率、处理时间、崩溃率
  4. 验证隐私授权流程
  5. 输出测试报告
- **Priority**: P0
- **Acceptance**:
  - MVP 场景识别准确率 > 80%
  - 本地路径端到端 < 1s（中端机 P50）
  - 无 P0 缺陷

---

### [agent-task:aio-012] 架构合规审查

- **Assignee**: CR
- **Scope**: 全量 AI 一键优化相关代码
- **Expected Change**:
  1. 审查是否符合 Agent First 原则（显式注入、枚举状态、自描述类型）
  2. 审查隐私红线是否合规（Fast 路径本地、Smart 路径授权）
  3. 审查是否遵循 I18N 规范
  4. 审查单元测试覆盖
- **Priority**: P0
- **Acceptance**:
  - 无 Critical 架构问题
  - 隐私实现与文档一致
  - 单测覆盖率符合模块要求

---

## 13. 执行顺序建议

```
Week 1:
  aio-001 (SceneAnalyzer)
  aio-002 (PresetRepository)
  aio-003 (AiOptimizeCapability)
  aio-012 CR 初审

Week 2:
  aio-004 (媒体查看器入口)
  aio-005 (编辑器入口)
  aio-006 (保存与对比)
  aio-011 QA 准备测试集

Week 3:
  aio-011 验收测试
  aio-012 CR 终审
  Bugfix & 调参
  aio-007 (授权) 可并行启动

Week 4+:
  aio-008 (Smart 引擎)
  aio-009 (对话命令)
  aio-010 (批量优化)
```

---

## 14. 技术规格

> 本节为 RD 实现参考，包含数据模型、类设计、集成时序、错误处理与 Agent tool call schema。

### 14.1 包结构

```
domain/agent/capability/optimize/
├── AiOptimizeCapability.kt          # Capability 入口
├── AiOptimizeRequest.kt             # 输入模型
├── AiOptimizeResult.kt              # 输出模型
├── OptimizeMode.kt                  # FAST / SMART 枚举
├── OptimizeSource.kt                # 调用来源枚举
├── analyzer/
│   ├── SceneAnalyzer.kt             # 场景分析接口
│   ├── LocalSceneAnalyzer.kt        # ML Kit + 人脸 + 规则实现
│   └── Scene.kt                     # 场景枚举
├── preset/
│   ├── PresetRepository.kt          # 预设仓库接口
│   ├── AssetPresetRepository.kt     # assets/presets.json 实现
│   └── OptimizePreset.kt            # 预设数据模型
├── smart/
│   ├── SmartOptimizeEngine.kt       # 远程视觉模型引擎接口
│   └── OpenAiVisionOptimizeEngine.kt# OpenAI 兼容实现
├── recipe/
│   └── OptimizeRecipeMapper.kt      # OptimizePreset → EditRecipe 映射
└── consent/
    └── CloudOptimizeConsentManager.kt # 云端授权管理
```

### 14.2 数据模型

#### 14.2.1 场景枚举

```kotlin
enum class Scene(
    val labelResId: Int,
    val iconResId: Int
) {
    SELFIE(R.string.scene_selfie, R.drawable.ic_scene_selfie),
    PORTRAIT(R.string.scene_portrait, R.drawable.ic_scene_portrait),
    GROUP(R.string.scene_group, R.drawable.ic_scene_group),
    FOOD(R.string.scene_food, R.drawable.ic_scene_food),
    LANDSCAPE(R.string.scene_landscape, R.drawable.ic_scene_landscape),
    LOW_LIGHT(R.string.scene_low_light, R.drawable.ic_scene_low_light),
    DOCUMENT(R.string.scene_document, R.drawable.ic_scene_document),
    GENERAL(R.string.scene_general, R.drawable.ic_scene_general)
}
```

#### 14.2.2 优化预设

```kotlin
data class OptimizePreset(
    val scene: Scene,
    val beauty: BeautySettingsPreset,
    val filter: FilterPreset,
    val adjustment: AdjustmentPreset
)

data class BeautySettingsPreset(
    val enabled: Boolean,
    val smooth: Int = 0,
    val whiten: Int = 0,
    val slimFace: Int = 0,
    val bigEye: Int = 0,
    val lipColor: Int = 0,
    val blush: Int = 0,
    val eyebrow: Int = 0
)

data class FilterPreset(
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter? = null
)

data class AdjustmentPreset(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val highlight: Float = 0f,
    val shadow: Float = 0f
)
```

#### 14.2.3 请求与结果

```kotlin
data class AiOptimizeRequest(
    val imageUri: String,
    val mode: OptimizeMode = OptimizeMode.FAST,
    val source: OptimizeSource = OptimizeSource.GALLERY_VIEWER,
    val allowCloud: Boolean = false
)

enum class OptimizeMode { FAST, SMART }

enum class OptimizeSource {
    GALLERY_VIEWER,
    EDITOR,
    CHAT,
    BATCH,
    IM_REMOTE
}

data class AiOptimizeResult(
    val scene: Scene,
    val confidence: Float,
    val preset: OptimizePreset,
    val explanation: String,
    val usedCloud: Boolean,
    val processingTimeMs: Long
)
```

### 14.3 核心类设计

#### 14.3.1 SceneAnalyzer

```kotlin
interface SceneAnalyzer {
    suspend fun analyze(imageUri: String): SceneAnalysis
}

data class SceneAnalysis(
    val scene: Scene,
    val confidence: Float,
    val signals: List<SceneSignal>
)

sealed class SceneSignal {
    data class FaceSignal(val count: Int, val faceRatio: Float) : SceneSignal()
    data class LabelSignal(val labels: List<ImageLabel>) : SceneSignal()
    data class BrightnessSignal(val meanBrightness: Float) : SceneSignal()
    data class ExifSignal(val iso: Int?, val focalLength: Float?) : SceneSignal()
}
```

`LocalSceneAnalyzer` 实现：

```kotlin
class LocalSceneAnalyzer(
    private val faceDetector: FaceDetector,
    private val imageLabeler: ImageLabeler,
    private val metadataExtractor: MetadataExtractor
) : SceneAnalyzer {

    override suspend fun analyze(imageUri: String): SceneAnalysis = withContext(Dispatchers.IO) {
        val faceSignal = detectFaces(imageUri)
        val labelSignal = labelImage(imageUri)
        val brightnessSignal = analyzeBrightness(imageUri)
        val exifSignal = readExif(imageUri)

        val scene = ruleEngine.resolve(
            faceSignal,
            labelSignal,
            brightnessSignal,
            exifSignal
        )

        SceneAnalysis(
            scene = scene.scene,
            confidence = scene.confidence,
            signals = listOf(faceSignal, labelSignal, brightnessSignal, exifSignal)
        )
    }
}
```

#### 14.3.2 PresetRepository

```kotlin
interface PresetRepository {
    fun getPreset(scene: Scene): OptimizePreset
}

class AssetPresetRepository(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : PresetRepository {

    private val presets by lazy { loadPresets() }

    override fun getPreset(scene: Scene): OptimizePreset {
        return presets[scene] ?: presets[Scene.GENERAL]!!
    }

    private fun loadPresets(): Map<Scene, OptimizePreset> {
        val jsonString = context.assets.open("presets/optimize_presets.json")
            .bufferedReader()
            .use { it.readText() }
        return json.decodeFromString(jsonString)
    }
}
```

#### 14.3.3 AiOptimizeCapability

```kotlin
class AiOptimizeCapability(
    private val sceneAnalyzer: SceneAnalyzer,
    private val presetRepository: PresetRepository,
    private val smartEngine: SmartOptimizeEngine? = null,
    private val consentManager: CloudOptimizeConsentManager
) : Capability {

    override val name: String = "ai_optimize"
    override val description: String = "分析照片场景并自动推荐美颜/滤镜/调节参数"

    override suspend fun execute(request: CapabilityRequest): CapabilityResponse {
        val optimizeRequest = request.parseAs<AiOptimizeRequest>()

        return try {
            when (optimizeRequest.mode) {
                OptimizeMode.FAST -> fastOptimize(optimizeRequest)
                OptimizeMode.SMART -> smartOptimize(optimizeRequest)
            }
        } catch (e: Exception) {
            Logger.e("AiOptimize", "Optimization failed", e)
            fallbackFastOptimize(optimizeRequest)
        }
    }

    private suspend fun fastOptimize(request: AiOptimizeRequest): CapabilityResponse {
        val startTime = SystemClock.elapsedRealtime()
        val analysis = sceneAnalyzer.analyze(request.imageUri)
        val preset = presetRepository.getPreset(analysis.scene)
        val elapsed = SystemClock.elapsedRealtime() - startTime

        val result = AiOptimizeResult(
            scene = analysis.scene,
            confidence = analysis.confidence,
            preset = preset,
            explanation = buildExplanation(analysis.scene),
            usedCloud = false,
            processingTimeMs = elapsed
        )

        return CapabilityResponse.Success(result)
    }

    private suspend fun smartOptimize(request: AiOptimizeRequest): CapabilityResponse {
        if (!consentManager.isCloudOptimizeAllowed()) {
            return CapabilityResponse.NeedConsent("cloud_optimize")
        }
        if (smartEngine == null) {
            return fastOptimize(request)
        }
        return smartEngine.optimize(request)
    }

    private fun fallbackFastOptimize(request: AiOptimizeRequest): CapabilityResponse {
        val preset = presetRepository.getPreset(Scene.GENERAL)
        return CapabilityResponse.Success(
            AiOptimizeResult(
                scene = Scene.GENERAL,
                confidence = 0.5f,
                preset = preset,
                explanation = context.getString(R.string.ai_optimize_fallback_explanation),
                usedCloud = false,
                processingTimeMs = 0
            )
        )
    }
}
```

### 14.4 与编辑器集成

#### 14.4.1 时序图

```
用户点击「AI 一键优化」
    │
    ▼
PhotoEditorViewModel.onAiOptimizeClick()
    │
    ▼
AiOptimizeCapability.execute(AiOptimizeRequest(imageUri, FAST, EDITOR))
    │
    ▼
LocalSceneAnalyzer.analyze(imageUri)
    │
    ▼
AssetPresetRepository.getPreset(scene)
    │
    ▼
OptimizeRecipeMapper.toEditRecipe(result.preset, currentRecipe.crop)
    │
    ▼
EditHistory.push(newRecipe)
    │
    ▼
RecipeApplier.apply(newRecipe, faceData)
    │
    ▼
预览更新
```

#### 14.4.2 ViewModel 集成代码

```kotlin
class PhotoEditorViewModel(
    private val aiOptimizeCapability: AiOptimizeCapability,
    private val recipeApplier: RecipeApplier,
    private val history: EditHistory
) : ViewModel() {

    fun onAiOptimizeClick() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAiOptimizing = true)

            val request = AiOptimizeRequest(
                imageUri = currentImageUri,
                mode = OptimizeMode.FAST,
                source = OptimizeSource.EDITOR
            )

            when (val response = aiOptimizeCapability.execute(CapabilityRequest(request))) {
                is CapabilityResponse.Success -> {
                    val result = response.data as AiOptimizeResult
                    val newRecipe = OptimizeRecipeMapper.toEditRecipe(
                        preset = result.preset,
                        baseRecipe = history.current
                    )
                    history.push(newRecipe)
                    renderPreview()
                }
                is CapabilityResponse.NeedConsent -> {
                    _events.emit(EditorEvent.ShowCloudConsentDialog)
                }
                is CapabilityResponse.Error -> {
                    _events.emit(EditorEvent.ShowError(response.message))
                }
            }

            _uiState.value = _uiState.value.copy(isAiOptimizing = false)
        }
    }
}
```

### 14.5 与媒体查看器集成

```kotlin
class MediaViewerViewModel(
    private val aiOptimizeCapability: AiOptimizeCapability,
    private val photoProcessor: PhotoProcessor,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    fun onAiOptimizeClick(imageUri: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(showOptimizeCard = false)

            val request = AiOptimizeRequest(
                imageUri = imageUri,
                mode = OptimizeMode.FAST,
                source = OptimizeSource.GALLERY_VIEWER
            )

            when (val response = aiOptimizeCapability.execute(CapabilityRequest(request))) {
                is CapabilityResponse.Success -> {
                    val result = response.data as AiOptimizeResult
                    _uiState.value = _uiState.value.copy(
                        optimizeRecommendation = result,
                        showOptimizeCard = true
                    )
                }
                is CapabilityResponse.NeedConsent -> {
                    _events.emit(MediaViewerEvent.ShowCloudConsentDialog)
                }
                is CapabilityResponse.Error -> {
                    _events.emit(MediaViewerEvent.ShowError(response.message))
                }
            }
        }
    }

    fun applyOptimization(result: AiOptimizeResult) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            val recipe = OptimizeRecipeMapper.toEditRecipe(result.preset)
            val optimizedBitmap = photoProcessor.process(
                sourceUri = result.imageUri,
                recipe = recipe
            )

            val savedUri = mediaRepository.saveOptimizedImage(
                bitmap = optimizedBitmap,
                originalUri = result.imageUri
            )

            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                comparisonMode = ComparisonMode(
                    originalUri = result.imageUri,
                    optimizedUri = savedUri
                )
            )
        }
    }
}
```

### 14.6 与 Agent 集成

#### 14.6.1 Tool Spec

```json
{
  "type": "function",
  "function": {
    "name": "ai_optimize",
    "description": "分析照片场景并自动推荐美颜、滤镜、调节参数，一键优化图片。",
    "parameters": {
      "type": "object",
      "properties": {
        "image_uri": {
          "type": "string",
          "description": "待优化图片的本地文件 URI"
        },
        "mode": {
          "type": "string",
          "enum": ["fast", "smart"],
          "description": "优化模式：fast 为本地快速优化（默认），smart 为云端智能推荐"
        }
      },
      "required": ["image_uri"],
      "additionalProperties": false
    }
  }
}
```

#### 14.6.2 Agent 执行流程

```
用户：帮我优化这张照片
    │
    ▼
AgentOrchestrator 解析意图 → 调用 ai_optimize tool
    │
    ▼
AiOptimizeCapability.execute(FAST)
    │
    ▼
生成 AiOptimizeResult
    │
    ▼
Agent 调用 image_message 能力发送优化后图片
    │
    ▼
附带 text_reply："已为你优化这张照片，提升了肤色通透度"
```

### 14.7 错误处理与降级策略

| 错误场景 | 处理策略 | 用户反馈 |
|----------|----------|----------|
| 本地分析超时 | 使用 GENERAL preset | "已应用通用优化" |
| ML Kit 不可用 | 使用 GENERAL preset | "已应用通用优化" |
| 远程模型超时 | fallback 到 FAST 路径 | "网络较慢，已使用本地优化" |
| 远程模型返回非法 JSON | fallback 到 FAST 路径 | "智能推荐失败，已使用本地优化" |
| 用户未授权云端 | 返回 NeedConsent | 显示授权弹窗 |
| 图片加载失败 | 返回 Error | "无法加载图片，请重试" |
| GPU 处理失败 | 返回 Error | "处理失败，请手动编辑" |

### 14.8 性能要求

| 路径 | P50 | P95 | 测量点 |
|------|-----|-----|--------|
| Fast 场景分析 | < 200ms | < 400ms | 从点击到返回 SceneAnalysis |
| Fast 端到端 | < 1s | < 1.5s | 从点击到显示推荐卡片 |
| 媒体查看器应用保存 | < 2s | < 3s | 从点击「应用」到进入对比模式 |
| Smart 端到端 | < 2s | < 4s | 从点击「换一换」到返回新推荐 |
| 批量处理单张 | < 1.5s | < 2.5s | 多选批量模式下单张平均 |

### 14.9 隐私与安全

- **Fast 路径**：所有分析在本地完成，敏感数据不出设备
- **Smart 路径**：
  - 图片缩放至最长边 512px，JPEG 质量 80
  - 通过 HTTPS 发送到用户配置的模型服务商
  - 不在 PicMe 任何服务端存储图片
  - 用户可在设置中随时关闭云端 AI 优化
- **批量处理**：强制使用 Fast 路径，不上传任何图片

### 14.10 测试策略

| 测试类型 | 覆盖点 |
|----------|--------|
| 单元测试 | SceneAnalyzer 规则引擎、PresetRepository JSON 解析、RecipeMapper 映射 |
| 集成测试 | AiOptimizeCapability → Editor ViewModel → PhotoProcessor |
| UI 测试 | 推荐卡片展示、应用/微调/换一换按钮行为 |
| 性能测试 | 5 个核心场景处理时间 |
| 隐私测试 | Fast 路径无网络请求、Smart 路径需授权 |
| 兼容性测试 | 不同 Android 版本、不同机型 |

---

## 15. 相关文档索引

| 文档 | 说明 |
|------|------|
| `docs/01-PRODUCT/FEATURES.md#141-ai-一键优化` | 交互 PRD |
| `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | 大美丽美颜引擎 |
| `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | Agent 架构 |
| `app/src/main/java/com/mamba/picme/features/editor/AGENTS.md` | 编辑器模块规范 |
| `app/src/main/java/com/mamba/picme/features/gallery/AGENTS.md` | 相册模块规范 |
