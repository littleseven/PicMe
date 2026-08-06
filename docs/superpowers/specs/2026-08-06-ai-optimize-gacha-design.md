# AI 优化抽卡闭环设计（best-of-N + NIMA 评分守卫）

> **状态**：设计稿（2026-08-06），待实现
> **范围**：为 AI 一键优化引入「生成 4 候选 → 渲染 → NIMA 评分 → 自动选优 + 退化守卫」闭环，解决「AI 给的调节值大概率退化」问题；用户可「换一组」重抽并手选，点选行为落库为后续个性化做准备。
> **关联文档**：`docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md`（参数标准与预设规范）、`docs/superpowers/specs/2026-08-02-nima-aesthetic-cover-design.md`（NIMA 评分器落地）

---

## 1. 背景与问题

AI 一键优化现状（`AiOptimizeUseCase`）：

| 路径 | 实现 | 现状 |
|------|------|------|
| Fast | `SceneAnalyzer`（`HeuristicSceneAnalyzer`）→ `PresetRepository` 查固定预设 → `EditRecipe` | ✅ 已落地，但每场景参数写死，不随照片内容变化 |
| Smart | VLM 看图推荐参数（`SMART_OPTIMIZE_VLM_DESIGN.md`） | ❌ 未实现 |

**核心问题**：无论是固定预设还是（未来的）模型推荐，「一次给值」都无法保证效果——不同照片的最优参数差异大，盲信单次输出大概率退化（过曝、过艳、肤色失真）。

**解决思路**：不追求一次给对，而是**抽卡（best-of-N）+ 自动评分选优 + 退化守卫**：

- 以推荐值为中心采样 4 个差异化候选
- 用现有编辑管线渲染候选图，用端侧已有 NIMA 美学评分器（`NimaScorer`）自动选最优
- **退化守卫**：最优候选不显著优于原图时保持原图，从机制上杜绝"越优化越差"
- 用户可「换一组」重抽并手选；点选/拒绝行为落库，为 Phase 2 个性化参数收窄积累数据

**关键前提（均已验证存在）**：

- `NimaScorer`（`app/.../domain/aesthetic/NimaScorer.kt`）：ONNX Runtime + NNAPI，整图美学分 1~10，已用于封面选择
- `RecipeApplier.applyGpuEffects(bitmap, recipe, faceData)`（`app/.../features/editor/RecipeApplier.kt:82`）：任意 `EditRecipe` → Bitmap，GPU 失败自动 CPU 滤镜兜底 + 全黑检测
- `OptimizeRecipeMapper`：`OptimizePreset` ↔ `EditRecipe` 双向映射
- 零新模型、零网络请求，符合 [PRIVACY] 红线

## 2. 方案选型记录

| 方案 | 描述 | 结论 |
|------|------|------|
| **A. 自动抽卡 + 评分守卫** | 4 候选 NIMA 自动选优直接应用，一步交互 | ✅ 选为 MVP 主干 |
| **B. 抽卡 UI 用户手选** | 4 卡片用户点选，兼作偏好数据采集 | ✅ 以轻量形态并入（「换一组」入口） |
| C. NIMA 引导多轮迭代搜索（爬山） | 多轮扰动-评分-接受 | ❌ 延迟不可控、NIMA 噪声被爬山放大、耗电；抽卡即其单轮截断版 |

用户已确认选型：**A + 轻量 B**。

> **修订记录（2026-08-06）**：交互改为「用户手选为主体 + 先预览后应用」。不再自动应用最优卡；抽卡后进入对比模式，候选在主预览区全尺寸预览（复用 2048px 预览管线，不入撤销历史），用户点「应用」才生效并入历史。落库时点相应调整：`user` 在「应用」时落库（而非点选时），`dismiss` 在「关闭」时落库。

## 3. 架构与组件

新增包 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/`：

| 组件 | 职责 | 依赖 | 可测性 |
|------|------|------|--------|
| `CandidateSampler` | 以 base preset 为中心生成 4 个差异化候选 `OptimizePreset`；seed 可复现 | 无（纯函数） | 纯 JVM 单测 |
| `CandidateRenderer` | 解码 512px 小图，逐候选调 `RecipeApplier.applyGpuEffects` 渲染成 Bitmap | RecipeApplier | mock PhotoProcessor |
| `OptimizeScorer` | NIMA 打分 + 技术护栏（高光裁剪、亮度漂移）→ 每卡总分或淘汰 | NimaScorer | mock scorer |
| `OptimizeGachaEngine` | 编排：采样→渲染→评分→选优→退化守卫，返回 `GachaResult` | 上述三者 | mock 三依赖 |

### 3.1 数据模型

```kotlin
/** 单张候选卡 */
data class OptimizeCandidate(
    val index: Int,                // 0..3，0 为 base preset 锚点
    val direction: String,         // 扰动方向标签，如 "base" / "clarity" / "warm" / "cool"
    val preset: OptimizePreset
)

/** 评分结果 */
data class ScoredCandidate(
    val candidate: OptimizeCandidate,
    val nimaScore: Float?,         // null = NIMA 不可用
    val rejected: Boolean,         // 护栏淘汰（过曝/亮度漂移）
    val rejectReason: String? = null
)

/** 抽卡结果 */
sealed interface GachaResult {
    /** 最优候选过守卫，可应用 */
    data class Selected(
        val best: ScoredCandidate,
        val all: List<ScoredCandidate>,
        val originalScore: Float?
    ) : GachaResult

    /** 全部候选未过退化守卫，保持原图 */
    data class KeepOriginal(
        val all: List<ScoredCandidate>,
        val originalScore: Float?
    ) : GachaResult

    /** 抽卡不可用（NIMA 未下载 / 有效卡不足），调用方退回现有固定预设路径 */
    data object Unavailable : GachaResult
}
```

### 3.2 AiOptimizeUseCase 扩展

```kotlin
suspend fun optimizeWithGacha(
    imageUri: String,
    baseRecipe: EditRecipe? = null,
    seed: Long = Random.nextLong()
): GachaOutcome
```

- 原 `optimize()` 保留不动，继续服务**批量优化**和**抽卡不可用时的兜底**
- `GachaOutcome` 在 `GachaResult` 基础上附带 `EditRecipe`（选中卡映射结果）与场景说明

## 4. 采样策略（CandidateSampler）

4 张卡保证**区分度**而非纯随机噪声：

- **卡 0**：base preset 原样（AI 推荐值锚点）
- **卡 1~3**：从场景的「方向模板」取 3 个方向 × seed 抖动。方向模板示例：
  - 通用/风景：通透（contrast+8, saturation+6）、暖调（temperature+400, tint+3）、清冷（temperature-400, brightness+5）
  - 人像/自拍：smoothing/whitening ±10 内扰动，叠加上述色温/亮度方向
  - 美食：饱和度/暖色方向为主
- 每个方向在模板值上叠加 seed 决定的小幅抖动（如 ±30%），保证「换一组」有新组合
- **扰动维度约束**：只动调色维度（brightness/exposure/contrast/saturation/temperature/tint/滤镜轮换）+ smoothing/whitening；**slimFace/bigEyes 等形变维度 v1 不扰动**（依赖人脸关键点，512px 小图上形变不可靠）
- 所有参数 clamp 到 `OptimizePreset` 合法范围（见 `AI_OPTIMIZATION.md` §4.2）
- **换一组去重**：参数量化到整数栅格后记录已出现组合，新 seed 重采直至 4 卡均为新组合（上限重试后放宽）

## 5. 评分与守卫（OptimizeScorer）

### 5.1 技术护栏（防 NIMA 怪癖）

NIMA 偏好高对比高饱和，需护栏约束：

| 护栏 | 阈值（初始值，可调） | 处理 |
|------|---------------------|------|
| 高光裁剪增量 | 候选裁剪率（r,g,b 均 ≥ 250 像素占比）− 原图裁剪率 > 5pp | 该卡淘汰 |
| 平均亮度漂移 | 候选均亮度相对原图漂移 > 15% | 该卡淘汰 |

护栏在 512px 渲染结果上采样计算（步长采样，不全图遍历）。

> **2026-08-06 真机校准**：高光护栏由绝对阈值（占比 > 5% 即淘汰）改为**增量判定**。绝对阈值会误杀天然偏亮的照片（亮调食物/文档/白背景人像：任何候选都超标 → 4 卡全灭 → 退回固定预设，反而把护栏想防的退化放行了）。增量判定只拦「把高光推爆」的候选，不惩罚照片本身的亮度。

### 5.2 退化守卫（核心）

- 对原图（512px 解码、无任何 recipe）同样打 NIMA 分
- `best.nimaScore <= originalScore + 0.05` → 判定优化无收益 → `KeepOriginal`，保持原图
- 阈值 0.05 为初始值，按离线样张验证结果调整

### 5.3 NIMA 不可用降级

`NimaScorer.initialize()` 返回 false（模型未下载）→ 整个抽卡链路返回 `GachaResult.Unavailable` → 调用方退回现有固定预设行为。**功能不阻塞、不提示用户下载**（与封面选择一致的渐进增强策略）。

## 6. 交互流程（2026-08-06 修订：先预览后应用）

```
点击「AI 优化」（媒体查看器 / 编辑器入口不变）
  → HeuristicSceneAnalyzer 识别场景 → base preset
  → CandidateSampler 抽 4 卡
  → 解码 512px 小图 → CandidateRenderer 渲染 ×4（串行，复用现有单线程 EGL dispatcher）
  → OptimizeScorer 评分 ×4 + 原图评分
  ├─ 有候选过守卫 → 进入对比模式：主预览区全尺寸预览最优卡（不入撤销历史），
  │     底部候选条显示 4 卡（缩略图 + 推荐徽标，预览中的卡高亮边框）
  ├─ 全部未过守卫 → 进入对比模式但 previewedIndex=-1（保持原图预览），
  │     提示"AI 认为原图已很好，仍可试看候选"
  └─ Unavailable → 退回现有 optimize() 固定预设路径（直接应用）

对比模式（调整面板与底栏隐藏，undo/redo 禁用）：
  → 点选某卡 → 主预览区切换到该卡（full-quality 预览，仅预览不落历史不落库）
  → 「换一组」→ 新 seed 重抽 4 卡（去重已出现组合），预览自动切到新最优卡
  → 「应用」→ history.push + 落库 user（previewedIndex=-1 时按钮禁用）
  → 「关闭」→ 预览回退 baseRecipe + 落库 dismiss + 退出对比模式
```

UI 文案遵循 [I18N] 红线，新增字符串同步 en / zh / zh-rCN / zh-rTW。

## 7. 反馈落库（Phase 2 个性化铺路，v1 只记录不学习）

新增 Room 表 `optimize_feedback`：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long PK | 自增 |
| image_key | String | 图片 URI 的 SHA-256 前 16 位（不存原始路径） |
| scene | String | Scene 枚举名 |
| candidates_json | String | 4 卡参数 + NIMA 分 + 是否被护栏淘汰 |
| selected_index | Int | 选中的卡；-1 = KeepOriginal |
| selection_source | String | `auto`（每组生成时 NIMA 选优）/ `user`（用户点「应用」确认）/ `dismiss`（用户点「关闭」放弃） |
| created_at | Long | 时间戳 |

Phase 2（不在本 spec 范围）：按 scene 聚合 user pick 相对 base 的参数偏移（EMA），把采样中心向用户偏好方向收窄——即"逐渐优化参数范围"。v1 只把 schema 定好、数据落准。

## 8. 性能预算

| 阶段 | 预算 | 说明 |
|------|------|------|
| 512px 解码 | < 150ms | BitmapFactory inSampleSize，长边 512 |
| GPU 渲染 ×4 | < 1.2s | 串行，复用 RecipeApplier 单线程 dispatcher；512px 小图渲染远快于全分辨率 |
| NIMA ×5（4 候选 + 原图） | < 500ms | NNAPI 加速，224×224 输入；原图分可在渲染期间并行预计算 |
| **端到端** | **P50 < 2.5s** | 比现有 fast 路径慢，但结果卡有 loading；可接受 |

批量优化**不走抽卡**（延迟 ×4 不可接受），保持固定预设。

## 9. 错误处理与降级

| 场景 | 处理 |
|------|------|
| NIMA 模型未下载 | `GachaResult.Unavailable` → 退回固定预设 |
| 单卡渲染失败 | 丢弃该卡，继续其余卡 |
| 有效卡 < 2 | 退回固定预设直接应用（现有行为） |
| 全部候选未过退化守卫 | 保持原图 + 提示文案 |
| NIMA 推理异常 | 该卡按淘汰处理；原图评分失败则跳过退化守卫（仅护栏选优） |
| GPU 渲染全黑 | RecipeApplier 已有 CPU 滤镜兜底，无需新增 |

## 10. 测试计划

- **单测（纯 JVM）**：
  - `CandidateSampler`：4 卡互不相同、参数均在合法范围、同 seed 可复现、换组去重生效
  - 守卫判定：mock scorer 构造过守卫/未过守卫/NIMA 缺失三分支
  - 护栏计算：高光裁剪率与亮度漂移的采样算法（构造已知像素 Bitmap）
- **集成测试**：`OptimizeGachaEngine` mock 三依赖，验证编排分支（Selected/KeepOriginal/Unavailable）
- **离线验证**：用 `input_images/` 样张写脚本对比「固定预设 vs 抽卡选优」的 NIMA 分与人眼效果，校准守卫阈值 0.05 与护栏阈值
- **真机闭环**：编译 → 安装 → 媒体查看器/编辑器点 AI 优化 → 验证自动选优、换一组、KeepOriginal 三分支

## 11. 范围边界（v1 不做）

- 不做个性化学习（只落库，Phase 2 再消费）
- 不做美颜形变维度（slimFace/bigEyes）扰动
- 批量优化不走抽卡
- 不做多轮迭代搜索（爬山）
- 不改动 Smart/VLM 路径（未来 VLM 推荐落地后，其输出直接作为抽卡的 base preset，天然兼容）

## 12. 改动清单（预估）

| 位置 | 改动 |
|------|------|
| `domain/agent/capability/optimize/gacha/` | 新增 4 组件 + 数据模型 |
| `domain/usecase/AiOptimizeUseCase.kt` | 新增 `optimizeWithGacha()` |
| `data/local/`（Room） | 新增 `optimize_feedback` 表 + DAO + Migration |
| 媒体查看器 / 编辑器结果卡 UI | 接 `optimizeWithGacha`，加「换一组」与 4 卡对比条 |
| `AppContainer` | 组装 GachaEngine 依赖 |
| `res/values*/strings.xml` | 新文案三语同步 |
| `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md` | 实现后补「抽卡闭环」章节链接回本 spec |
