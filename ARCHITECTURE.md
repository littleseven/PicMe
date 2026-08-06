# ARCHITECTURE: AI 一键优化功能重建

> 对应 `PRD.md`(vt-pm)。本文给出技术选型、模块/接口/数据模型、改动范围与测试策略,
> 供 Dev/Reviewer/QA 流水线执行。所有改动遵循根 `CLAUDE.md` 硬规则(无 FQN、无 wildcard import、
> lambda 显式命名、log tag `PoLang:[模块]`、4 空格缩进、三语 i18n 同步)与 `[PRIVACY]` 红线。

---

## 1. 现状诊断(已核实)

| 缺陷 | 根因位置 | 事实 |
|------|----------|------|
| 优化"不看图" | `AiOptimizeUseCase.fastOptimize` L59 | 恒 `getPreset(Scene.GENERAL)`,8 预设中 7 个闲置;`analyzer/` 仅 `Scene.kt` 枚举 |
| 假 smart 死代码 | `SmartOptimizeEngine.kt` + `AppContainer.kt:389` | 接口零实现,`smartEngine=null`,`smartOptimize` 必降级;`mode=smart` 对 LLM 可见 |
| chat 美型失效 | `ChatImageRenderer.kt:96,115` | 硬编码 `fastOptimize`、`faceData=null`,无 `FaceDetector` 注入 |
| DTO 重复拷贝 | `AiOptimizeCapability.kt:112-141` | 手写 `BeautyPreset/FilterPreset/AdjustmentPreset`,与 `OptimizeRecipeMapper` 重复 |
| bitmap 无缓存 | `ChatImageRenderer.kt:129-145` | 每次 `decodeBitmap` 开两条流重解码 |

**正确参考路径(编辑器)**:`PhotoEditorViewModel` L49 注入 `FaceDetector` → L162 `detectPhoto(bitmap, lensFacing=1)` → `FaceDataConverter.fromLandmarks106(...)` → L278/L303 传 `cachedFaceData` 给 `RecipeApplier.applyGpuEffects`。chat 路径将复刻此模式。

**依赖可用性**:`AppContainer.faceDetector`(L424,`FaceDetectorFactory.create`)已存在,可直接注入 `ChatImageRenderer` 与 `HeuristicSceneAnalyzer`,无需新增工厂。

---

## 2. 技术决策

### 2.1 US-1 场景识别:端侧启发式 SceneAnalyzer(零网络、零新 SDK)

**PRD 约束**:不新增第 9+ 场景;不引入第三方 SDK;场景识别 100% 端侧;复用已有能力(FaceDetector 人脸计数、像素亮度/饱和度启发式)。

**抽象**——新增接口与结果模型,位于 `domain/agent/capability/optimize/analyzer/`:

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.analyzer

interface SceneAnalyzer {
    /** 端侧分析图片场景。输入本地 URI,输出场景 + 置信度。零网络。 */
    suspend fun analyze(imageUri: String): SceneAnalysis
}

data class SceneAnalysis(
    val scene: Scene,
    val confidence: Float
)
```

- **入参用 `imageUri` 而非 `Bitmap`**:分析器内部负责解码下采样小图(FaceDetector 需 Bitmap、亮度需像素,解码不可避免)。此举让 `AiOptimizeUseCase` 保持纯业务(不碰解码),且测试 mock `SceneAnalyzer` 时直接返回 `SceneAnalysis`,无需构造真实 Bitmap/ContextResolver,满足 AC-1.3 可测性。
- **零网络保证(AC-1.4)**:`analyzer/` 包仅用 `android.graphics.BitmapFactory` + `android.content.Context` + `FaceDetector`(beauty-api),无 okhttp/HttpURL/retrofit 等。

**实现** `HeuristicSceneAnalyzer(context, faceDetector)` —— 优先级分类器,全部端侧:

1. 解码下采样分析图(长边 ≤ `ANALYSIS_MAX_DIM=256`,RGB_565 节省内存)。
2. **亮度采样**:步长采样像素 → `luminance=(0.299R+0.587G+0.114B)/255` 求均值。`avgLuminance < LOW_LIGHT_THRESHOLD(0.25f)` → **LOW_LIGHT**(conf≈0.70)。极暗时其他特征不可靠,优先判定。
3. **人脸计数**:`faceDetector.detectFacesOnly(bitmap)`(轻量,仅 ROI 无关键点)。
   - `faceCount >= 2` → **GROUP**(conf≈0.80)
   - `faceCount == 1`:计算 `faceAreaRatio = ROI面积/图面积`;`> SELFIE_RATIO(0.15f)` → **SELFIE**(特写);否则 → **PORTRAIT**
4. **非人像**(`faceCount == 0`):计算均饱和度 + 色温(R vs B):
   - 高饱和 + 暖色(R>B 显著) → **FOOD**
   - 绿/蓝主导(自然色) → **LANDSCAPE**
   - 低饱和 + 高边缘密度(邻像素差计数) → **DOCUMENT**(弱启发式,准确率有限)
   - 其余 → **GENERAL**
5. 默认 → **GENERAL**(兜底,对应通用预设,永不出错)。

阈值均为具名常量(`companion object`),可后续调参。置信度为各分支启发式估值(0.70~0.85),非 ML 概率。

> **设计取舍**:启发式分类器准确率有限(尤以 FOOD/LANDSCAPE/DOCUMENT 易混淆),但 PRD 非目标 #1 明确不扩场景、不引 SDK;所有误判安全降级到 GENERAL 预设。这换来 100% 端侧 + 零新依赖 + 可单测路由。

### 2.2 US-1 接入 use case

`AiOptimizeUseCase` 构造增 `sceneAnalyzer: SceneAnalyzer`;`fastOptimize` 改为:

```kotlin
val analysis = sceneAnalyzer.analyze(imageUri)           // 端侧场景识别
val preset = presetRepository.getPreset(analysis.scene)  // 按场景取预设(GENERAL 仅兜底)
return Result(scene = analysis.scene, confidence = analysis.confidence, editRecipe = ..., ...)
```

`Scene.GENERAL` 不再是 fast 路径固定值,仅作 `analyze` 兜底与 `getPreset` 缺省回退(满足 AC-1.2)。`AppContainer` 构造 `HeuristicSceneAnalyzer(context, faceDetector)` 注入。

### 2.3 US-2 smart 删除(终局决策)

smart 语义="云端视觉模型推荐",实现必上传用户图片 → 违 `[PRIVACY]` 红线;端侧 smart 与 US-1 重建的 fast 语义重叠 → **无合法实现路径,删除**。

删除面(编译耦合集,必须原子改动):
- **删文件**:`optimize/smart/SmartOptimizeEngine.kt`、`optimize/consent/CloudOptimizeConsentManager.kt`(已核实仅 `AiOptimizeUseCase`+`AppContainer` 引用,无其他消费者)。
- **`AiOptimizeUseCase`**:移除 `consentManager`/`smartEngine` 参数、`smartOptimize()` 方法及相关 import。
- **`AgentCommand.AiOptimize`**(runtime-core):移除 `mode` 字段(原 L329)。
- **`ChatToolService.aiOptimize`**(runtime-core @Tool):移除 `mode` 参数,@Tool 描述去 "smart"。
- **`ToolCallCommandParser.parseAiOptimize`**(runtime-core):不再解析 `mode`。
- **`AiOptimizeCapability`**:移除 `mode` 分支(L105-108)、`getCommandParameterSchema` 的 mode enum(L65)、`getCommandDescription` 的 mode 说明(L51)。
- **`AppContainer`**:构造 `AiOptimizeUseCase` 时去 `consentManager`/`smartEngine`(L388-389)。

> i18n:已核实三语 `strings.xml` 无 AI 优化 mode 相关用户可见文案(现有 3 处 "smart" 命中均无关:smart search/assistant/gallery index),故删除 smart **不产生 i18n 变更**。

### 2.4 US-3 chat 美型修复(FaceDetector 注入)

`ChatImageRenderer` 构造增 `faceDetector: FaceDetector`(已核实 `AppContainer.faceDetector` 可用,DI 仅加一个参数)。`renderRecipe` 复刻编辑器模式:

```kotlin
val bitmap = decodeBitmap(imageUri)          // US-5:命中缓存则跳过解码
...
val cropped = applier.applyCrop(bitmap, recipe.crop)
val faceData = withContext(dispatcher) {     // 新增:真实人脸检测
    faceDetector.detectPhoto(cropped, lensFacing = 1)?.landmarks106?.let { lm ->
        FaceDataConverter.fromLandmarks106(lm, cropped.width, cropped.height)
    }
}
val processed = applier.applyGpuEffects(cropped, recipe, faceData)  // 不再 null
```

- **复用 `FaceDataConverter`**(`features/editor/`,编辑器同款转换),保证 chat 与编辑器美型一致。
- `aiOptimize`(L94-104)经由 US-1 成果自动获得场景感知路径(AC-3.3)——它已调 `optimizeUseCase.fastOptimize`,T3 后该路径含场景分析。
- 类注释 L33-34 "faceData=null…美型不生效" 过时说明同步更新/删除(AC-3.5)。
- **DI**:`AppContainer.chatImageRenderer`(L602-603)构造加 `faceDetector`。

### 2.5 US-4 DTO 映射去重

`AiOptimizeCapability.execute` L112-141 手写 `OptimizePreset/BeautyPreset/FilterPreset/AdjustmentPreset` 与 `OptimizeRecipeMapper` 的反向逻辑重复。新增 `OptimizeRecipeMapper` 反向映射:

```kotlin
fun toOptimizePreset(recipe: EditRecipe, scene: Scene): OptimizePreset
fun toResultDto(sourceUri: String, scene: Scene, explanation: String, recipe: EditRecipe): OptimizeResultDto
```

`AiOptimizeCapability` 改为 `OptimizeRecipeMapper.toResultDto(imageUri, result.scene, result.explanation, result.editRecipe)`。顺手消除该文件 FQN 违规(L112-141 用全限定名 `com.mamba.picme.domain...`,违反硬规则)。

### 2.6 US-5 bitmap 解码缓存

`ChatImageRenderer.decodeBitmap`(L129-145)每次开两条流重解码。新增 URI→Bitmap 内存缓存:

- 用 `android.util.LruCache<String, Bitmap>`,按字节计数(`sizeOf` 返回 `bitmap.byteCount`,`maxSize` 取 `Runtime.maxMemory()/8` 上限封顶,典型缓存数张 2048px 图)。
- `decodeBitmap`:先 `cache.get(uri)`;命中直接返回(跳过 `openInputStream`);未命中解码后 `cache.put`。
- 缓存生命周期 = `ChatImageRenderer` 实例(单例于 `AppContainer.by lazy`),会话内有效。

---

## 3. 模块边界与依赖

- 全部改动在 **`:app`**(optimize/、usecase/、features/chat/、features/editor/ 复用、di/)与 **`:runtime-core`**(AgentCommands/ChatToolService/ToolCallCommandParser 的 smart 删除)。
- **`:app` → `:beauty-api`**(`FaceDetector`/`FaceData`/`PhotoProcessor`):合法,沿用编辑器既有依赖。
- **`:app` → `:beauty-engine:api/`**:`FaceData` 实定义于 `beauty-engine/.../api/PhotoProcessor.kt`(包 `com.mamba.picme.beauty.api`),编辑器已如此依赖,不新增越界。
- `FaceDataConverter` 引用 `beauty.internal.facedetect.Face106ToWarpParams`——**既有**依赖(编辑器路径已用),本轮复用不扩大。
- `analyzer/` 包零网络依赖(AC-1.4)。

---

## 4. 数据模型

- **新增**:`SceneAnalysis(scene: Scene, confidence: Float)`;`SceneAnalyzer` 接口。
- **`AiOptimizeUseCase.Result`**:沿用(scene/confidence/editRecipe/explanation/usedCloud/processingTimeMs)。smart 删除后 `usedCloud` 恒 false(字段保留,避免破坏 Result 契约;测试断言 `usedCloud == false`)。
- **`AgentCommand.AiOptimize`**:删 `mode` 字段;保留 `imageUri/explanation/resultRecipe`。
- **`OptimizeRecipeMapper`**:新增 `toOptimizePreset`/`toResultDto`(反向映射)。
- **预设模型**(`OptimizePreset/BeautyPreset/FilterPreset/AdjustmentPreset`)与 `Scene` 枚举:不变。

---

## 5. 测试策略

| 测试 | 类型 | 覆盖 AC |
|------|------|---------|
| `AiOptimizeUseCaseTest`(重写) | JVM(`:app:testDebugUnitTest`) | AC-1.2/1.3/1.5/1.6/2.9:mock `SceneAnalyzer` 返回 SELFIE/FOOD/LANDSCAPE/DOCUMENT(≥4 非 GENERAL),断言 `Result.scene` 一致 + `getPreset(<scene>)` 被调用;8 场景预设各 `verify` 一次;删 smart 降级测试 |
| `analyzer/` 零网络 | grep 门 | AC-1.4:CI grep `okhttp\|HttpURL\|...` 输出空 |
| `ChatImageRenderer` 人脸/缓存 | JVM(mock FaceDetector/ContentResolver) | AC-3.4/5.2:检测到人脸时 `faceData.hasFace==true`;同 URI 二次渲染 `openInputStream` 仅一次。若 `FaceDataConverter` 依赖 `beauty.internal` 在纯 JVM 不可加载,则人脸断言降级 `androidTest` 插桩,PR 说明;缓存断言用 mock LruCache 在 JVM 可行 |
| `OptimizeRecipeMapperTest`(增补) | JVM | AC-4.1/4.2:反向映射 DTO 字段正确 |
| 全量编译/单测 | gate | AC-2.8/4.3/全局门:`./gradlew :app:assembleDebug` + `--tests "*AiOptimize*" --tests "*ChatImageRenderer*"` 退出 0 |

> 注:`HeuristicSceneAnalyzer` 自身启发式逻辑需真实 Bitmap + FaceDetector(JVM 不友好),以 `androidTest` 插桩或人工验证为主;use-case 层用 mock analyzer 覆盖路由正确性(AC-1.3/1.6 的可机器判定点)。

---

## 6. 红线合规

- **[PRIVACY]**:场景识别全链路(`SceneAnalyzer`/`HeuristicSceneAnalyzer`)零网络,用户图片不出端。smart(唯一需上传的路径)删除。grep 门 CI 强制。
- **[I18N]**:本轮无新增用户可见文案(smart 删除的 mode 仅 LLM @Tool 描述,非 UI 文案);若 review 发现文案变更须三语同步。
- **硬规则**:改动文件须无 FQN(`com.mamba.picme.*` 用 import)、无 wildcard import、lambda 显式命名、log tag `PoLang:[模块]`、4 空格缩进。`AiOptimizeCapability` 顺带修 FQN 违规。
