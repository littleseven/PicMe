# AI 抠图/背景子系统 设计文档（u2netp + MODNet + 证件照专区）

> **日期**: 2026-07-18
> **状态**: 设计已评审，待编写实现计划
> **作者**: RD Agent
> **模块定位**: 图像编辑器 + 相册 AI 抠图能力（本地 ONNX 推理）

## 0. 背景与目标

为图像编辑页增加「一键去背景」，并扩展为人像精修 / 证件照专区。核心能力由两个本地 ONNX 模型提供：

- **u2netp**：通用显著目标分割，输出二值掩码（适合物体/场景，无人脸时使用）
- **MODNet**：人像 Alpha Matting，输出连续 Alpha（适合人像/发丝，边缘柔和）

两个模型均运行在项目**已集成的 ONNX Runtime（`onnxruntime-android` 1.24.3）** 上，零新增原生库成本。现有 `MobileClipOnnxBackend` 为 ONNX 图像模型推理提供了可直接照抄的范式。

### 设计决策（已与产品方确认）

| 决策点 | 选择 |
|---|---|
| 范围 | 整子系统一次设计，**分期实现**（P1/P2/P3） |
| 抠图产物 | 透明 PNG 抠图 + 合成纯色/模糊背景，**两者都要** |
| 双模型协作 | **按人脸路由**（二选一）：有人脸 → MODNet；无人脸 → u2netp |
| 推理后端 | **ONNX Runtime 直跑**（不做 MNN/NCNN 转换；不做远程推理——违反 `[PRIVACY]`） |
| 模型分发 | **demo 阶段两个模型均打包进 assets**；将来迁移至用户 ModelScope 空间（与 MobileCLIP/OPUS/LLM 一致） |

### 红线遵守

- `[PRIVACY]`：所有推理 100% 本地，严禁上传云端。
- `[I18N]`：所有用户可见文案提取到 strings.xml，三语（`values` / `values-zh-rCN` / `values-zh-rTW`）同步。
- `[PERF]`：抠图为一次性操作（非实时预览流），不卡 100ms 交互预算，但仍须在后台线程执行、不阻塞 UI。

## 1. 模块与分层

新增 **`app/src/main/java/com/mamba/picme/domain/matting/`** 包，镜像现有 `domain/tag/MobileClip*` 模式。**不进 `:runtime-core`**——该模块是 Agent/LLM 编排层，抠图是端侧视觉能力。

### 1.1 组件清单

| 组件 | 职责 | 依赖 |
|---|---|---|
| `MattingEngine` | 门面；持有两个 backend，对外暴露 `suspend fun removeBackground(bitmap: Bitmap): MattingResult` | Router + Backends + PostProcessor |
| `MattingRouter` | 复用现有 **106 点人脸检测**判定人像/非人像，路由到对应 backend | 现有人脸检测服务 |
| `U2NetOnnxBackend` | u2netp ONNX 推理（照抄 `MobileClipOnnxBackend` 的 OrtSession 用法） | ONNX Runtime + `MattingModelResolver` |
| `ModNetOnnxBackend` | MODNet ONNX 推理 | ONNX Runtime + `MattingModelResolver` |
| `MattingModelResolver` | **模型位置抽象层**：返回模型 `File` 路径，屏蔽来源（assets 拷贝 / ModelScope 下载） | assets / `ModelPathConfig` / `LlmModelDownloadManager` |
| `MaskPostProcessor` | 掩码阈值化（u2netp）/ 直传（MODNet）→ 双线性上采样回原图 → 可选羽化 → 连续 Alpha | — |
| `CutoutComposer` | Alpha → 透明 Bitmap（`setHasAlpha(true)`） | — |
| `BackgroundComposer` | Alpha → 合成纯色/模糊背景 | — |
| `MattingResult` | data class：`alpha: Bitmap`、`maskSource: MaskSource`、`processingTimeMs: Long` | — |

### 1.2 复用项

- **ONNX Runtime**：`OrtEnvironment` 进程级单例（与 ASR/翻译/MobileCLIP 共享），**只关 session 不关 env**。
- **模型分发**：`ModelPathConfig`（集中路径）、`LlmModelDownloadManager`（运行时下载）。
- **人脸检测**：编辑器现有的 106 点检测缓存（MediaPipe 默认 / MNN / NCNN），用于路由与（未来）ROI。

### 1.3 Agent NL 集成（YAGNI，后续 hook）

「自然语言去背景」（如"帮我把背景去掉"）作为后续可选 hook，不在本期实现。若要做，需在 `:runtime-core` 定义 capability 接口、在 `:app` 实现，调用本 `MattingEngine`。

## 2. 数据流

```
入口（编辑器顶部栏 / 证件照专区）
  → MattingRouter(人脸检测)
  │   ├─ 有人脸 → ModNetOnnxBackend(256×256) → 连续 Alpha@256
  │   └─ 无人脸 → U2NetOnnxBackend(320×320)  → sigmoid 掩码@320 → 阈值化
  → MaskPostProcessor(双线性上采样回原图 + 可选羽化) → MattingResult(alpha)
  ├─ bgMode = TRANSPARENT → CutoutComposer → 透明 Bitmap → PNG 导出(带 Alpha)
  └─ bgMode = COLOR/BLUR  → BackgroundComposer → JPEG 导出（沿用现有路径）
```

## 3. 编辑器集成（最大改造点）

### 3.1 EditRecipe 扩展

`features/editor/EditRecipe.kt` 新增字段：

```kotlin
val cutout: CutoutRecipe? = null
```

```kotlin
data class CutoutRecipe(
    val maskSource: MaskSource,        // U2NETP / MODNET
    val threshold: Float = 0.5f,       // 仅 u2netp 二值化阈值
    val bgMode: BgMode,                // TRANSPARENT / COLOR / BLUR
    val bgColor: Int? = null,          // bgMode=COLOR 时的颜色
    val feather: Float = 0f            // 边缘羽化半径（像素），0=关闭
) {
    enum class MaskSource { U2NETP, MODNET }
    enum class BgMode { TRANSPARENT, COLOR, BLUR }
}
```

`version` 递增以支持配方迁移。

### 3.2 RecipeApplier 新增阶段

`features/editor/RecipeApplier.kt` 在 GPU 滤镜之后、markup 之前插入 `applyCutout()`：

1. 若 `recipe.cutout == null` → 跳过
2. 调 `MattingEngine.removeBackground(currentBitmap)` 取 Alpha
3. 按 `bgMode` 调 `CutoutComposer` 或 `BackgroundComposer`

**注意**：`applyCutout` 不绑定 EGL 上下文（纯 CPU 像素操作），可在普通调度器执行，与 GPU 美颜/滤镜的单线程调度器分离。

### 3.3 导出路径分支（PNG vs JPEG）

当前两处保存点均硬编码 JPEG：
- `PhotoEditorViewModel.kt:318`（MIME）/ `:326`（compress）
- `ImageEditScreen.kt:436`（MIME）/ `:446`（compress）

改造：保存时依据 `recipe.cutout?.bgMode` 决定：
- `TRANSPARENT` → `compress(Bitmap.CompressFormat.PNG, 100, out)` + MIME `image/png` + 文件名 `.png`
- 其他 → 沿用 JPEG（质量 95）

**关键约束**：预览与保存基于同一份 `EditRecipe`（编辑器既有规约），透明模式的预览棋盘格仅是渲染效果、不影响 recipe。

### 3.4 预览渲染

透明模式下，预览区底层渲染棋盘格 Bitmap，上层用 Alpha 合成当前图。棋盘格作为本地资源，不进 recipe。

## 4. 入口与 UX

| 入口 | 位置 | 行为 |
|---|---|---|
| 一键去背景（P1/P2） | 编辑器**顶部栏新增 action 按钮**（一键、无参数 tab） | 点击 → 走 router → 默认透明抠图 + 棋盘格预览；二次操作切「换纯色背景」 |
| 证件照专区（P3） | 独立入口（Gallery 二级菜单或编辑器入口） | 固定 MODNet + 背景色选择（蓝/红/白）+ 标准证件照尺寸裁剪 |

交互细节：
- 模型未就绪（MODNet 未下载）→ 引导下载对话框
- 推理中 → 进度提示（不阻塞返回）
- 长按预览对比原图（沿用编辑器既有交互）

## 5. 分期实现

| 期 | 内容 | 主要交付物 |
|---|---|---|
| **P1** | u2netp 一键去背景（透明 PNG + 纯色合成） | `U2NetOnnxBackend` + `MattingModelResolver`（assets 源）+ `MaskPostProcessor` + `CutoutComposer`/`BackgroundComposer` + PNG 导出 + 顶部栏按钮 |
| **P2** | MODNet 人像精修 + 人脸路由打通 | `ModNetOnnxBackend` + `MattingRouter` + 下载流程 |
| **P3** | 证件照专区 | 独立入口 + 背景色选择 + 标准尺寸裁剪 |

P1 阶段 `MattingRouter` 暂只走"无人脸→u2netp"分支；P2 打通人脸分支后 router 自动生效。

## 6. 模型获取与位置抽象

### 6.1 demo 阶段（本地打包）

两个模型均置于 `app/src/main/assets/matting/`：

- `u2netp.onnx`（fp32，~5 MB）
- `modnet.onnx`（移动端量化 256 版，~7-8 MB）

### 6.2 MattingModelResolver 抽象

引擎代码只依赖「给我一个模型 `File` 路径」，不关心来源：

```kotlin
interface MattingModelResolver {
    suspend fun resolve(modelId: String): File?   // 不存在返回 null，由调用方决定引导下载
}
```

- **AssetSource（demo）**：首次访问时把 assets 中的 `.onnx` 拷贝到 `filesDir/llm_models/<modelId>/`，返回该 File。后续命中直接返回。
- **DownloadSource（未来）**：检查 `filesDir` 是否存在；不存在则经 `LlmModelDownloadManager` 从 ModelScope 下载，返回 File。

**切换到 ModelScope = 改 Resolver 实现配置，零引擎重构**。`ModelPathConfig` 同步新增 `MODEL_ID_U2NETP = "u2netp-onnx"`、`MODEL_ID_MODNET = "modnet-onnx"` 常量与文件清单。

## 7. 错误与降级

| 场景 | 处理 |
|---|---|
| 模型未下载（MODNet） | 引导下载对话框；u2netp 打包在 assets 不存在此问题 |
| fp16 NaN | `MobileClipOnnxBackend` 已踩坑——统一用 fp32/int8，模型选型即规避 |
| 推理异常 / OOM | `try-catch` → `State.Error` + `PoLang:Matting` 日志，原图不变 |
| MODNet 误触发于无人脸图 | 路由层规避；即便误触发仅影响效果，不崩溃 |
| 合成后全黑/全透明 | 复用编辑器既有"全黑检测"思路，回退纯色背景 |
| 大图 OOM | 推理输入固定 320/256；中间 Bitmap 及时 recycle；复用编辑器 2048px 预览降采样 |

## 8. 性能与内存

- **推理输入固定**：u2netp 320×320、MODNet 256×256；掩码双线性上采样回原图尺寸。
- **线程**：推理与 CPU 合成走 `Dispatchers.Default`，独立于编辑器 GPU 预览的 EGL 单线程调度器，避免上下文串扰。
- **内存峰值**：上采样后的全图 Alpha Bitmap 是主要占用；合成完成后立即 recycle 中间产物。
- **预期耗时**：u2netp/MODNet（量化）在主流 arm64 CPU 上单次 < 1s（实测确认写入 TECH SPEC）。

## 9. i18n（强制三语）

新增 strings（`values` + `values-zh-rCN` + `values-zh-rTW` 同步）：

- 去背景 / 人像精修 / 证件照
- 透明背景 / 棋盘格预览提示
- 背景颜色名称（蓝/红/白）
- 模型下载引导与进度
- 抠图失败提示

## 10. 测试

### 10.1 JVM 单测（无设备）

- u2netp / MODNet 预处理数学（归一化均值方差、NCHW 排布、/255 顺序）
- `MaskPostProcessor`：掩码阈值化、双线性上采样正确性、羽化半径效果
- `MattingRouter`：有人脸/无人脸/多人脸路由分支
- `CutoutRecipe` Moshi 序列化往返（含 version 迁移）
- `MattingModelResolver`（AssetSource）：assets 拷贝、二次命中复用

### 10.2 真机/Instrumentation（需设备）

- ONNX 推理端到端：参照现有 MobileCLIP 测试方式
- 透明 PNG 导出可被图库识别且背景透明
- 人像图走 MODNet、物体图走 u2netp 的路由实测

## 11. 风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| MODNet 移动端量化版质量不达预期 | 中 | P2 初期对比 fp32 与量化版；必要时改用 256 fp32（~25MB） |
| u2netp 对复杂背景/多目标抠图边缘锯齿 | 中 | 提供羽化参数（`CutoutRecipe.feather`）；证件照强制走 MODNet |
| PNG 导出改造影响编辑器保存主路径 | 中 | 两处保存点分支化；单测覆盖 JPEG/PNG 两条路径 |
| 模型选型/op 兼容在 ORT Android 上异常 | 低 | 现有 MobileCLIP/OPUS 已验证 ORT 1.24.3 可用 |

## 12. 未来扩展（不在本期）

- Agent 自然语言去背景（capability 接口）
- 半身/ROI 级 MODNet 精修（u2netp 粗掩码 + 人脸 ROI MODNet 重算，即"叠加"方案）
- 证件照批量、AI 换背景（图片背景）
- 抠图结果独立保存/分享（脱离编辑器 recipe）
