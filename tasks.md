# tasks.md — AI 一键优化功能重建

> 来源:`PRD.md`(vt-pm)+ `ARCHITECTURE.md`(vt-architect)。
> 每个任务含:id / 描述 / 改动范围 / 验收命令 / 依赖。遵循根 `CLAUDE.md` 硬规则与 `[PRIVACY]` 红线。
> 执行顺序:T1 → T2 → T3 → T4 → T5 → T6 → T7(依赖见各任务)。

---

## Phase 1 — 核心价值 + 诚信清理(US-1 + US-2)

### T1: 新建 SceneAnalyzer 抽象与 SceneAnalysis 模型 [US-1, AC-1.1]

**描述**:在 `optimize/analyzer/` 新建 `SceneAnalyzer.kt`,定义 `interface SceneAnalyzer { suspend fun analyze(imageUri: String): SceneAnalysis }` 与 `data class SceneAnalysis(scene: Scene, confidence: Float)`。入参用 URI(分析器内部解码),使 use case 保持纯业务且可 mock。零 Android UI 依赖,零网络 import。

**改动范围**:
- 新建 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/SceneAnalyzer.kt`

**验收命令**:
```bash
./gradlew :app:compileDebugKotlin
test -f app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/SceneAnalyzer.kt
grep -qE "interface SceneAnalyzer|abstract class SceneAnalyzer" app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/SceneAnalyzer.kt
```

**依赖**:无

---

### T2: 实现 HeuristicSceneAnalyzer(端侧启发式,零网络)[US-1, AC-1.4]

**描述**:新建 `HeuristicSceneAnalyzer.kt`,构造注入 `Context` + `FaceDetector`。优先级分类器:① 解码下采样分析图(长边≤256)② 亮度采样→LOW_LIGHT ③ `faceDetector.detectFacesOnly` 计数→GROUP/SELFIE/PORTRAIT(按 faceAreaRatio)④ 非人像→FOOD/LANDSCAPE/DOCUMENT/GENERAL(饱和度+色温+边缘密度启发式)。阈值为 `companion object` 具名常量。log tag `PoLang:SceneAnalyzer`。**禁止任何网络 import**。

**改动范围**:
- 新建 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/HeuristicSceneAnalyzer.kt`

**验收命令**:
```bash
./gradlew :app:compileDebugKotlin
# AC-1.4 零网络红线
test -z "$(grep -rE 'okhttp|HttpURL|URLConnection|Socket|upload|retrofit' app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/)"
```

**依赖**:T1

---

### T3: fastOptimize 接入场景识别(场景感知预设选择)[US-1, AC-1.2/1.3/1.5/1.6]

**描述**:`AiOptimizeUseCase` 构造增 `sceneAnalyzer: SceneAnalyzer`;`fastOptimize` 改为先 `sceneAnalyzer.analyze(imageUri)` 再 `getPreset(analysis.scene)`(不再硬编码 `Scene.GENERAL`,GENERAL 仅兜底)。`AppContainer.aiOptimizeUseCase`(L385-391)构造增 `sceneAnalyzer = HeuristicSceneAnalyzer(context, faceDetector)`。**保留 smartOptimize/consent/smartEngine 不动**(T4 删)。重写 `AiOptimizeUseCaseTest`:mock `sceneAnalyzer` 返回 SELFIE/FOOD/LANDSCAPE/DOCUMENT(≥4 非 GENERAL),断言 `Result.scene` 与 mock 一致、`getPreset(<对应 Scene>)` 被 verify;补齐 8 场景预设各 verify 至少一次。保留既有 smart 测试(仍编译)。

**改动范围**:
- 编辑 `app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt`(仅 fastOptimize + 构造增参,不动 smartOptimize)
- 编辑 `app/src/main/java/com/mamba/picme/di/AppContainer.kt`(L385-391 增 sceneAnalyzer 构造)
- 编辑 `app/src/test/java/com/mamba/picme/domain/usecase/AiOptimizeUseCaseTest.kt`(增 mock analyzer + ≥4 非 GENERAL 场景断言)

**验收命令**:
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*AiOptimize*"
# AC-1.2 GENERAL 不再是 fast 固定选择
! grep -qE 'getPreset\(Scene\.GENERAL\)' app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt || \
  grep -n "Scene.GENERAL" app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt | grep -qv "getPreset(Scene.GENERAL)"
# AC-1.5 构造注入
grep -q "sceneAnalyzer" app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt
```

**依赖**:T1, T2

---

### T4: 删除 smart 死代码 + 命令层 mode 移除(编译耦合集,原子)[US-2, AC-2.1~2.9]

**描述**:smart 语义需上传图片违 `[PRIVACY]`,无合法实现路径,终局删除。删除面(必须一次编译通过):① 删 `SmartOptimizeEngine.kt` + `CloudOptimizeConsentManager.kt`(已核实无其他消费者)② `AiOptimizeUseCase` 移除 `consentManager`/`smartEngine` 参数、`smartOptimize()` 方法、相关 import ③ `AgentCommand.AiOptimize`(runtime-core)删 `mode` 字段 ④ `ChatToolService.aiOptimize` 删 `mode` 参数、@Tool 描述去 "smart" ⑤ `ToolCallCommandParser.parseAiOptimize` 不解析 `mode` ⑥ `AiOptimizeCapability` 删 mode 分支(L105-108)、`getCommandParameterSchema` mode enum(L65)、`getCommandDescription` mode 说明(L51)⑦ `AppContainer` 构造去 `consentManager`/`smartEngine`(L388-389)⑧ `AiOptimizeUseCaseTest` 删 smart 测试用例。无 i18n 变更(已核实)。

**改动范围**:
- 删除 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/smart/SmartOptimizeEngine.kt`
- 删除 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/consent/CloudOptimizeConsentManager.kt`
- 编辑 `app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt`(移除 smart 相关)
- 编辑 `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`(AiOptimize 删 mode)
- 编辑 `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/tool/ChatToolService.kt`(aiOptimize @Tool 删 mode)
- 编辑 `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/parser/ToolCallCommandParser.kt`(parseAiOptimize 删 mode)
- 编辑 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt`(删 mode 分支/schema/desc)
- 编辑 `app/src/main/java/com/mamba/picme/di/AppContainer.kt`(删 consent/smartEngine 构造 + import)
- 编辑 `app/src/test/java/com/mamba/picme/domain/usecase/AiOptimizeUseCaseTest.kt`(删 smart 用例 + consent/smartEngine import)

**验收命令**:
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*AiOptimize*"
# AC-2.1
test ! -f app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/smart/SmartOptimizeEngine.kt
# AC-2.2/2.6 死代码清除
test "$(grep -cE 'smartOptimize|smartEngine|SmartOptimizeEngine' app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt)" = 0
test "$(grep -cE 'smartEngine|SmartOptimizeEngine' app/src/main/java/com/mamba/picme/di/AppContainer.kt)" = 0
# AC-2.4/2.5
test "$(grep -ci 'smart' app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt)" = 0
```

**依赖**:T3(同改 AiOptimizeUseCase,在其后)

---

## Phase 2 — chat 渲染修复 + 卫生(US-3 + US-4 + US-5)

### T5: ChatImageRenderer 注入 FaceDetector,修复美型不生效 [US-3, AC-3.1~3.5]

**描述**:`ChatImageRenderer` 构造增 `faceDetector: FaceDetector`。`renderRecipe` 复刻编辑器模式:对 cropped bitmap 执行 `faceDetector.detectPhoto(cropped, lensFacing = 1)` → `FaceDataConverter.fromLandmarks106(...)` → 传真实 `faceData` 给 `applyGpuEffects`(不再硬编码 null,L115)。`aiOptimize` 经 T3 成果自动获场景感知路径(AC-3.3,无须改签名)。更新/删除类注释 L33-34 "faceData=null…不生效" 过时说明(AC-3.5)。`AppContainer.chatImageRenderer`(L602-603)构造增 `faceDetector`。测试:mock `FaceDetector` 返回含 landmarks 的 `FaceDetectionResult`,断言传入 `applyGpuEffects` 的 faceData.hasFace==true;若 `FaceDataConverter` 的 `beauty.internal` 依赖在纯 JVM 不可加载,人脸断言降级 `androidTest` 插桩并在 PR 说明。

**改动范围**:
- 编辑 `app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt`(构造增 faceDetector + renderRecipe 真实检测 + 注释更新)
- 编辑 `app/src/main/java/com/mamba/picme/di/AppContainer.kt`(chatImageRenderer 构造 L602-603 增 faceDetector)
- 新建/编辑 `app/src/test/java/com/mamba/picme/features/chat/ChatImageRendererTest.kt`(人脸检测断言;不可行则 androidTest)

**验收命令**:
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*ChatImageRenderer*"
# AC-3.1
grep -q "FaceDetector" app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt
# AC-3.2 不再有硬编码 faceData = null
test -z "$(grep -n 'faceData = null' app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt)"
# AC-3.5 过时缺陷注释清除
test -z "$(grep -E 'faceData=null|不生效' app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt)"
```

**依赖**:T3(需场景感知 fastOptimize 路径)

---

### T6: 消除 preset→DTO 重复映射 [US-4, AC-4.1/4.2/4.3]

**描述**:`OptimizeRecipeMapper` 新增反向映射 `toOptimizePreset(recipe: EditRecipe, scene: Scene): OptimizePreset` 与 `toResultDto(sourceUri, scene, explanation, recipe): OptimizeResultDto`(集中字段拷贝)。`AiOptimizeCapability.execute` 删除手写 L112-141 的 `OptimizePreset/BeautyPreset/FilterPreset/AdjustmentPreset` 构造,改调 `OptimizeRecipeMapper.toResultDto(...)`。顺带消除该文件 FQN 违规(L112-141 用全限定名,违反硬规则——补 import 替换)。补 `OptimizeRecipeMapperTest` 反向映射用例。

**改动范围**:
- 编辑 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/recipe/OptimizeRecipeMapper.kt`(新增 toOptimizePreset/toResultDto)
- 编辑 `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt`(L112-141 改调 mapper + 修 FQN)
- 编辑 `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/recipe/OptimizeRecipeMapperTest.kt`(增反向映射断言)

**验收命令**:
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*OptimizeRecipeMapper*"
# AC-4.1 不再手写 DTO 构造
test "$(grep -cE 'BeautyPreset\(|FilterPreset\(|AdjustmentPreset\(' app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt)" = 0
# AC-4.2 复用 mapper
grep -q "OptimizeRecipeMapper" app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt
```

**依赖**:T4(同改 AiOptimizeCapability,在其后)

---

### T7: ChatImageRenderer bitmap 解码缓存 [US-5, AC-5.1/5.2]

**描述**:`ChatImageRenderer` 新增 URI→Bitmap 内存缓存(`android.util.LruCache<String, Bitmap>`,按字节计数,`maxSize` 取 `Runtime.maxMemory()/8`)。`decodeBitmap` 先查缓存命中则跳过 `openInputStream`,未命中解码后 `put`。测试:同 URI 调两次 `renderRecipe`,mock `ContentResolver` 断言 `openInputStream` 仅一次(或解码仅发生一次)。

**改动范围**:
- 编辑 `app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt`(增 LruCache + decodeBitmap 查缓存)
- 编辑 `app/src/test/java/com/mamba/picme/features/chat/ChatImageRendererTest.kt`(增缓存命中断言)

**验收命令**:
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*ChatImageRenderer*"
# AC-5.1 存在以 URI 为 key 的缓存
grep -qE "LruCache<String, Bitmap>|LruCache<String,Bitmap>" app/src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt
```

**依赖**:T5(同改 ChatImageRenderer,在其后)

---

## 全局验收门(全部完成后)

```bash
# 1. 编译
./gradlew :app:assembleDebug
# 2. 单测
./gradlew :app:testDebugUnitTest --tests "*AiOptimize*" --tests "*ChatImageRenderer*"
# 3. 隐私红线:场景识别链路零网络
test -z "$(grep -rE 'okhttp|HttpURL|URLConnection|Socket|upload|retrofit' app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/)"
# 4. 死代码清除
test ! -f app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/smart/SmartOptimizeEngine.kt
test "$(grep -cE 'smartOptimize|SmartOptimizeEngine|smartEngine' app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt)" = 0
test "$(grep -cE 'smartEngine|SmartOptimizeEngine' app/src/main/java/com/mamba/picme/di/AppContainer.kt)" = 0
# 5. 硬规则(抽样)
./gradlew checkNoFullyQualifiedName  # 若该任务启用;否则人工核验无 com.mamba.picme.* FQN
```
