# LFM2.5-VL-450M-Extract 打标模型接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `LiquidAI/LFM2.5-VL-450M-Extract`（Mamba2 混合 VLM）转成 MNN，接入相册打标管道作为可选打标模型，与 SmolVLM 做 A/B，胜出则替换默认。

**Architecture:** 复用共享 `LocalLlmEngine` + 新增 `taggerModelKey` 设置；图片走既有 `MnnLlmClient`→JNI `MultimodalPrompt` 桥；LFM 特殊点（chat 模板、512px tile）以「按模型区分」的最小改动解决。分 5 阶段，Phase 1 是 kill-switch gate。

**Tech Stack:** MNN-LLM（用户 fork `/Users/guoshuai/code/MNN`，已注册 `lfm2_vl` 架构 + `mamba`/`gated_delta_rule` custom_op）；Kotlin + JNI(C++)；Android DataStore；`llmexport` 转换器（int4）。

**Spec:** `docs/superpowers/specs/2026-07-22-lfm25vl-450m-extract-tagging-design.md`

---

## File Structure

**新建：**
- `app/src/test/java/com/mamba/picme/data/download/ModelFilesMappingTest.kt` — 模型→文件列表映射单测（含 LFM）
- `app/src/test/java/com/mamba/picme/domain/tag/TaggerModelSelectionTest.kt` — tagger key 选择/回退逻辑单测
- `app/src/main/java/com/mamba/picme/domain/usecase/TaggerABComparisonUseCase.kt` — Phase 3 A/B 对比用例
- `app/src/main/java/com/mamba/picme/domain/tag/LfmTagPromptProvider.kt` — LFM 专属 prompt（如需要）

**修改：**
- `app/src/main/res/raw/llm_models.json` — 加 `lfm2_5_vl_450m_extract` 条目
- `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt` — `LFM_MODEL_FILES` 常量 + 抽取 `modelFilesForId()` + `getModelFiles`/`getModelFilesByTags` 分支
- `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`（+ `UserSettingsRepositoryImpl`）— 加 `taggerModelKeyFlow`
- `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt` — `MODEL_KEY` 改为读设置
- `runtime-core/.../inference/local/llm/MnnLlmClient.kt` — 记录 loadedModelKey，`preprocessBitmap` 按模型选 512/420
- `runtime-core/src/main/cpp/llm_jni_bridge.cpp` — `MAX_IMAGE_DIM` 420→512（安全上限）；图片 prompt_template 按模型注入（Phase 0 挖到真实模板后）
- `app/.../domain/model/...`（`ModelConfig.REQUIRED_MODEL_IDS`）+ Debug 页 — Phase 3/4

---

## Phase 0 — 转换（macOS，本会话执行）

> 这是在 MNN fork 上跑转换器的运维步骤，非代码 TDD。每步有明确产物校验。

### Task 0.1: 环境核查

**Files:** 无（环境）

- [ ] **Step 1: 确认 MNNConvert 二进制**
  Run: `ls -la /Users/guoshuai/code/MNN/build/MNNConvert 2>/dev/null || find /Users/guoshuai/code/MNN -maxdepth 3 -name MNNConvert -type f 2>/dev/null`
  Expected: 找到可执行文件。若没有 → 在 fork 里 `cmake` 编译出 MNNConvert（记录编译命令到本任务备注）。
- [ ] **Step 2: 确认 Python 依赖**
  Run: `cd /Users/guoshuai/code/MNN && python -c "import transformers, torch, numpy; print(transformers.__version__, torch.__version__)"`
  Expected: 打印版本号。缺则 `pip install transformers torch numpy onnx onnxslim`。
- [ ] **Step 3: 确认 HF repo 的 model_type**
  Run: `python -c "from transformers import AutoConfig; c=AutoConfig.from_pretrained('LiquidAI/LFM2.5-VL-450M-Extract'); print(c.model_type)"`
  Expected: `lfm2_vl`。若不是（如 `lfm2` / 其他）→ 转换时加 `--type lfm2_vl`。
- [ ] **Step 4: 确认 Lfm2VlForConditionalGeneration 可加载**
  Run: `python -c "from transformers import AutoModelForImageTextToText; AutoModelForImageTextToText.from_pretrained('LiquidAI/LFM2.5-VL-450M-Extract', trust_remote_code=True); print('ok')"`
  Expected: `ok`（可能需要 `trust_remote_code` 或 Liquid 包；记录所需 pip 包）。失败 → 装 Liquid 官方包 / 升级 transformers 到含 LFM2-VL 的版本。

### Task 0.2: 挖真实 chat_template（供 Phase 2 用）

**Files:** 记录到本计划末尾「Phase 0 产物」节

- [ ] **Step 1: 导出 tokenizer chat_template**
  Run: `python -c "from transformers import AutoTokenizer; t=AutoTokenizer.from_pretrained('LiquidAI/LFM2.5-VL-450M-Extract'); print(repr(t.chat_template))"`
  Expected: 打印 jinja 模板字符串。
- [ ] **Step 2: 记录 system/user/图片占位符格式**
  把模板字符串 + 图片占位符 token（`<image>` / `<|image_start|>` 等，已在 `Lfm2VlVision.load()` 见到）填入本计划「Phase 0 产物」节。这决定 Phase 2 Task 2.5 的 JNI prompt 构造。

### Task 0.3: 跑 int4 转换

**Files:** 产物输出到 `/Users/guoshuai/code/MNN/model_lfm2_5_vl_450m_extract/`

- [ ] **Step 1: 执行转换**
  Run:
  ```bash
  cd /Users/guoshuai/code/MNN
  python transformers/llm/export/llmexport.py \
    --path LiquidAI/LFM2.5-VL-450M-Extract \
    --dst_path ./model_lfm2_5_vl_450m_extract \
    --quant_bit 4 \
    --mnnconvert ./build/MNNConvert 2>&1 | tee /tmp/lfm_convert.log
  ```
  （Step 0.3 若 model_type 非 lfm2_vl，追加 `--type lfm2_vl`）
  Expected: 日志末尾无 Traceback，产出 model 目录。
- [ ] **Step 2: 校验产物文件**
  Run: `ls -la /Users/guoshuai/code/MNN/model_lfm2_5_vl_450m_extract/`
  Expected: 含 `config.json`、`llm_config.json`、`llm.mnn`、`llm.mnn.weight`、`visual.mnn`、`visual.mnn.weight`、`tokenizer.txt`。
- [ ] **Step 3: 校验 llm_config.json 字段**
  Run: `python -c "import json; c=json.load(open('/Users/guoshuai/code/MNN/model_lfm2_5_vl_450m_extract/llm_config.json')); print({k:c.get(k) for k in ['is_visual','vision_start','vision_end','image_pad','image_mean','image_norm','image_size']})"`
  Expected: `is_visual=True`，vision_start/end/image_pad 为 int，image_mean≈[127.5,127.5,127.5]，image_norm≈[1/127.5,…]，image_size=512。
- [ ] **Step 4: 转换器内推理自检**
  Run:
  ```bash
  cd /Users/guoshuai/code/MNN
  python transformers/llm/export/llmexport.py \
    --path LiquidAI/LFM2.5-VL-450M-Extract \
    --dst_path ./model_lfm2_5_vl_450m_extract \
    --test "List 3 objects in one line." 2>&1 | tail -20
  ```
  Expected: 产出非乱码英文文本（验证 Mamba2 前向跑通）。失败 → 回 fork `custom_op.py`/`vision.py` 排查，记录阻塞点；**此 gate 不过则停止后续 Phase**。
- [ ] **Step 5: 记录产物体积**
  Run: `du -sh /Users/guoshuai/code/MNN/model_lfm2_5_vl_450m_extract/ && du -sh /Users/guoshuai/code/MNN/model_lfm2_5_vl_450m_extract/*.mnn*`
  填入「Phase 0 产物」节（供 `llm_models.json` 的 size 字段）。

### Task 0.4: 上线到 ModelScope

**Files:** 无（上传）

- [ ] **Step 1: 创建 ModelScope 仓库** `budaoshou/LFM2.5-VL-450M-Extract-MNN`（用户命名空间）
- [ ] **Step 2: 上传产物目录全部文件** 到该仓库 master 根目录
- [ ] **Step 3: 记录可下载校验** 用 `https://modelscope.cn/api/v1/models/budaoshou/LFM2.5-VL-450M-Extract-MNN/repo/files?Revision=master` 确认文件可见。

---

## Phase 1 — 端侧冒烟（🔴 GATE / kill-switch）

> 前置：Phase 0 Step 4 自检通过。本阶段把模型接到设备并跑一张图。

### Task 1.1: 注册模型元数据（先于设备验证，纯配置）

**Files:**
- Modify: `app/src/main/res/raw/llm_models.json`
- Modify: `app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt`
- Test: `app/src/test/java/com/mamba/picme/data/download/ModelFilesMappingTest.kt`

- [ ] **Step 1: 写失败测试**
  Create `app/src/test/java/com/mamba/picme/data/download/ModelFilesMappingTest.kt`:
  ```kotlin
  package com.mamba.picme.data.download

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class ModelFilesMappingTest {
      @Test
      fun lfm_extract_maps_to_multimodal_files() {
          val files = LlmModelDownloadManager.modelFilesForId("lfm2_5_vl_450m_extract")
          assertEquals(
              listOf(
                  "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
                  "visual.mnn", "visual.mnn.weight", "tokenizer.txt", "embeddings_bf16.bin"
              ),
              files
          )
      }

      @Test
      fun smolvlm_mapping_unchanged() {
          // 回归保护：既有映射不被破坏
          assertTrue(
              LlmModelDownloadManager.modelFilesForId("smolvlm_256m")
                  .contains("visual.mnn")
          )
      }
  }
  ```
- [ ] **Step 2: 跑测试确认失败**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.ModelFilesMappingTest"`
  Expected: FAIL（`modelFilesForId` 未定义 / 未含 lfm 分支）。
- [ ] **Step 3: 抽取并实现 `modelFilesForId` + LFM 常量**
  In `LlmModelDownloadManager.kt`:
  - 新增伴生常量：
    ```kotlin
    /** LFM2.5-VL-450M-Extract 多模态模型文件（SigLIP2 视觉塔 + Mamba2 骨干） */
    private val LFM2_VL_EXTRACT_MODEL_FILES = listOf(
        "config.json",
        "llm_config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "visual.mnn",
        "visual.mnn.weight",
        "tokenizer.txt",
        "embeddings_bf16.bin"
    )
    ```
  - 把现有 `private fun getModelFiles(modelId: String)` 与 `getModelFilesByTags(...)` 的 `when` 体抽成 `companion object` 里的 `fun modelFilesForId(modelId: String): List<String>`（两个内部方法改为转调它，保持行为不变），并在 `when` 里加：
    ```kotlin
    modelId == "lfm2_5_vl_450m_extract" -> LFM2_VL_EXTRACT_MODEL_FILES
    ```
- [ ] **Step 4: 跑测试确认通过**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.download.ModelFilesMappingTest"`
  Expected: PASS。
- [ ] **Step 5: 加 llm_models.json 条目**
  In `app/src/main/res/raw/llm_models.json`，仿 `smolvlm_500m` 加：
  ```json
  {
    "id": "lfm2_5_vl_450m_extract",
    "name": "LFM2.5-VL-450M-Extract-MNN",
    "description": "Liquid AI LFM2.5-VL-450M-Extract（Mamba2 混合 VLM，MNN int4 版），Extract 变体专为图片→JSON 抽取调优，打标备选模型",
    "type": "LLM",
    "size": <Phase 0 Task 0.3 Step 5 记录的字节数>,
    "sources": {
      "ModelScope": "budaoshou/LFM2.5-VL-450M-Extract-MNN"
    },
    "files": [
      "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
      "visual.mnn", "visual.mnn.weight", "tokenizer.txt", "embeddings_bf16.bin"
    ],
    "tags": ["chat", "vision", "tagging", "multilingual"]
  }
  ```
- [ ] **Step 6: 提交**
  ```bash
  git add app/src/main/res/raw/llm_models.json \
          app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt \
          app/src/test/java/com/mamba/picme/data/download/ModelFilesMappingTest.kt
  git commit -m "feat(tag): 注册 LFM2.5-VL-450M-Extract 模型元数据与文件映射"
  ```

### Task 1.2: 端侧加载 + 单图冒烟（🔴 GATE）

**Files:** 无代码（设备验证）

- [ ] **Step 1: 装最新 debug 包并下载模型**
  Run: `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/polang-debug.apk`
  然后在 app 模型下载页下载 `lfm2_5_vl_450m_extract`（或 `adb push` 产物到 `/data/data/<pkg>/files/llm_models/lfm2_5_vl_450m_extract/`）。
- [ ] **Step 2: 用 Debug 页/最小路径加载并推理一张图**
  触发一次 LFM 图片推理（Debug 页「单图打标」或临时把 tagger 切到 LFM——切换 UI 在 Phase 2；此处可用 adb am / 临时代码触发 `MnnLlmClient.load("lfm2_5_vl_450m_extract")` + `generateWithImage`）。
  Run: `adb logcat -s "PoLang:LlmJNI" "PoLang:MnnLlmClient" "TagPipeline"`
  Expected: `[Vision] Image: ...` + 末尾非空 `[Vision] result:` + `vision_us>0`、`decode tokens>0`。
- [ ] **Step 3: 判定 gate**
  - 无 SIGSEGV / `[CRASH]` 日志 ✅
  - 输出非乱码、含可识别 JSON 片段 ✅
  - 若崩溃或乱码 → **停止**，回 Phase 0 排查转换器/`custom_op`，下游全部搁置。

---

## Phase 2 — 接入为可选打标模型

> 前置：Phase 1 gate 通过。

### Task 2.1: taggerModelKey 设置项（TDD）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/...`（`UserSettingsRepositoryImpl`，路径以 grep 为准）
- Test: `app/src/test/java/com/mamba/picme/domain/tag/TaggerModelSelectionTest.kt`

- [ ] **Step 1: 写失败测试**
  Create `app/src/test/java/com/mamba/picme/domain/tag/TaggerModelSelectionTest.kt`:
  ```kotlin
  package com.mamba.picme.domain.tag

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class TaggerModelSelectionTest {
      @Test
      fun default_tagger_is_smolvlm_256m() {
          assertEquals("smolvlm_256m", TaggerModelSelector.defaultKey)
      }

      @Test
      fun unknown_setting_falls_back_to_default() {
          assertEquals("smolvlm_256m", TaggerModelSelector.resolve(null))
          assertEquals("smolvlm_256m", TaggerModelSelector.resolve(""))
          assertEquals("smolvlm_256m", TaggerModelSelector.resolve("   "))
      }

      @Test
      fun known_lfm_key_is_kept() {
          assertEquals("lfm2_5_vl_450m_extract", TaggerModelSelector.resolve("lfm2_5_vl_450m_extract"))
      }
  }
  ```
- [ ] **Step 2: 跑测试确认失败**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.TaggerModelSelectionTest"`
  Expected: FAIL（`TaggerModelSelector` 未定义）。
- [ ] **Step 3: 实现 TaggerModelSelector**
  Create `app/src/main/java/com/mamba/picme/domain/tag/TaggerModelSelector.kt`:
  ```kotlin
  package com.mamba.picme.domain.tag

  /**
   * 打标模型选择器：把用户设置解析为有效的 tagger model key。
   *
   * - 默认 [defaultKey] = smolvlm_256m
   * - 空白/未识别 → 回退默认
   * - 已注册 key（白名单）原样返回
   */
  object TaggerModelSelector {
      const val defaultKey = "smolvlm_256m"

      private val knownKeys = setOf(
          "smolvlm_256m",
          "smolvlm_500m",
          "qwen3_vl_2b",
          "lfm2_5_vl_450m_extract"
      )

      fun resolve(raw: String?): String {
          val trimmed = raw?.trim().orEmpty()
          return if (trimmed in knownKeys) trimmed else defaultKey
      }
  }
  ```
- [ ] **Step 4: 跑测试确认通过**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.TaggerModelSelectionTest"`
  Expected: PASS。
- [ ] **Step 5: 加 DataStore 设置**
  In `UserSettingsRepository.kt`（接口）加：
  ```kotlin
  /** 相册打标模型 key（由 [TaggerModelSelector] 解析为有效值） */
  val taggerModelKeyFlow: Flow<String>
  fun getTaggerModelKeyBlocking(): String
  suspend fun updateTaggerModelKey(key: String)
  ```
  在 `UserSettingsRepositoryImpl` 仿 `aiAgentLocalModelFlow` 用 `stringPreferencesKey("tagger_model_key")` 实现，默认值 `"smolvlm_256m"`。
- [ ] **Step 6: 提交**
  ```bash
  git add app/src/main/java/com/mamba/picme/domain/tag/TaggerModelSelector.kt \
          app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
          app/src/main/java/com/mamba/picme/data <Impl路径> \
          app/src/test/java/com/mamba/picme/domain/tag/TaggerModelSelectionTest.kt
  git commit -m "feat(tag): 新增 taggerModelKey 设置与选择器"
  ```

### Task 2.2: 打标引擎按设置加载

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`

- [ ] **Step 1: 定位 MODEL_KEY 与 ensureModelLoaded**
  Run: `grep -n "MODEL_KEY\|ensureModelLoaded\|loadModel" app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`
  记录 `MODEL_KEY` 当前常量值（应为 `smolvlm_256m`）与 `ensureModelLoaded` 加载调用点。
- [ ] **Step 2: MODEL_KEY 改为从设置解析**
  把 `private const val MODEL_KEY = "smolvlm_256m"` 改为按需解析：
  ```kotlin
  private val taggerModelKey: String
      get() = TaggerModelSelector.resolve(userSettingsRepository.getTaggerModelKeyBlocking())
  ```
  `ensureModelLoaded()` 里原本用 `MODEL_KEY` 的地方改用 `taggerModelKey`；`OpenClGuardian(... modelId = taggerModelKey)` 同步。
- [ ] **Step 3: 编译验证**
  Run: `./gradlew :app:assembleDebug`
  Expected: BUILD SUCCESSFUL。
- [ ] **Step 4: 提交**
  ```bash
  git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
  git commit -m "feat(tag): 打标引擎按 taggerModelKey 设置加载"
  ```

### Task 2.3: 图片尺寸按模型区分（LFM 512 / 其它 420）

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt`
- Modify: `runtime-core/src/main/cpp/llm_jni_bridge.cpp`

- [ ] **Step 1: C++ 安全上限提到 512**
  In `llm_jni_bridge.cpp:72`: `static constexpr int MAX_IMAGE_DIM = 420;` → `512;`（仅作安全上限，实际目标尺寸由 Kotlin 按模型决定）。
- [ ] **Step 2: MnnLlmClient 记录 loadedModelKey 并按模型选目标尺寸**
  In `MnnLlmClient.kt`：
  - 加字段 `private var loadedModelKey: String = ""`，在 `load(modelKey, ...)` 开头 `loadedModelKey = modelKey`。
  - 加：
    ```kotlin
    /** 按模型决定图像最长边：LFM2-VL tile_size=512，其余保持 420 */
    private fun maxImageDimForCurrent(): Int =
        if (loadedModelKey.contains("lfm", ignoreCase = true)) 512 else MAX_IMAGE_DIM
    ```
  - `preprocessBitmap` 里把 `MAX_IMAGE_DIM` 用法改为 `maxImageDimForCurrent()`（`MAX_IMAGE_DIM` 常量保留为默认 420）。
- [ ] **Step 3: 编译 native + Kotlin**
  Run: `./gradlew :runtime-core:assembleDebug :app:assembleDebug`
  Expected: BUILD SUCCESSFUL（注意 native 重新编译 `libagent_native.so`）。
- [ ] **Step 4: 提交**
  ```bash
  git add runtime-core/src/main/cpp/llm_jni_bridge.cpp \
          runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt
  git commit -m "feat(llm): 图片输入尺寸按模型区分（LFM 放开到 512px）"
  ```

### Task 2.4: Debug 页/设置页切换 tagger（手动验证）

**Files:**
- Modify: 设置页或 Debug 页 ViewModel（路径以 grep `debugUiEnabled` / 现有模型选择 UI 为准）

- [ ] **Step 1: 定位现有「本地模型选择」UI 复用**
  Run: `grep -rIn "aiAgentLocalModelFlow" --include="*.kt" app/src/main/java/com/mamba/picme/features`
  找到现有模型选择 UI 组件，仿其加一个 tagger 选择项（下拉：SmolVLM-256M / SmolVLM-500M / Qwen3-VL-2B / LFM2.5-VL-Extract）。
- [ ] **Step 2: 接到 `updateTaggerModelKey`**
  选中后调 `userSettingsRepository.updateTaggerModelKey(key)`；切换后需卸载旧引擎（`localLlmEngine` 重新 load 新 key——参考既有本地模型切换的卸载/重载逻辑）。
- [ ] **Step 3: 设备验证**
  切到 LFM → 对一张相册照片打标 → 输出合法 JSON。切回 SmolVLM → 仍正常。
- [ ] **Step 4: 提交**
  ```bash
  git add <UI 文件>
  git commit -m "feat(tag): 设置/Debug 页可切换打标模型"
  ```

### Task 2.5: 图片 JNI chat 模板按模型注入（依赖 Phase 0 Task 0.2）

**Files:**
- Modify: `runtime-core/src/main/cpp/llm_jni_bridge.cpp`（`nativeGenerateWithImage` / `nativeGenerateWithImageTimeout` / `doLockedImageInference` 内 `multimodal.prompt_template` 构造）

> ⚠️ 本任务的模板字符串取自 Phase 0 Task 0.2 记录的 LFM2 真实 chat_template。若该模板恰好与既有 Qwen `<img>image_0</img>` 注入兼容，则本任务降级为「仅验证不改 JNI」。

- [ ] **Step 1: 对照 Phase 0 产物里的 LFM2 模板，确定 system/user/图片占位符顺序**
  （在「Phase 0 产物」节查阅）。
- [ ] **Step 2: 把 prompt_template 构造改为按模型分支**
  传入模型标识（由 `MnnLlmClient` 经新 JNI 参数 `jstring modelKey` 透传，或读 config.json 的 `chat_template`）。LFM 分支用 Phase 0 挖到的模板，图片仍以 `<img>image_0</img>` 注入到模板对应位置；Qwen 分支保持现状。
  示例（伪代码，实际以 Phase 0 模板为准）：
  ```cpp
  std::string tmpl;
  if (isLfm) {
      tmpl = "<|im_start|>user\n"                // ← 替换为 Phase 0 真实 LFM2 模板
             "<img>image_0</img>" + userStr +
             "<|im_end|>\n<|im_start|>assistant\n";
  } else {
      tmpl = "<|im_start|>system\n" + systemStr + "<|im_end|>\n"
             "<|im_start|>user\n<img>image_0</img>" + userStr + "<|im_end|>\n"
             "<|im_start|>assistant\n";
  }
  multimodal.prompt_template = tmpl;
  ```
- [ ] **Step 3: 设备验证 LFM 输出仍合法**
  Run: `adb logcat -s "PoLang:LlmJNI"`
  Expected: 切 LFM 打标，输出 JSON 片段、无 `<|im_start|>` 泄漏到结果。
- [ ] **Step 4: 提交**
  ```bash
  git add runtime-core/src/main/cpp/llm_jni_bridge.cpp \
          runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt
  git commit -m "feat(llm): 图片 JNI chat 模板按模型注入（支持 LFM2）"
  ```

---

## Phase 3 — A/B 质量对比（Debug 页按钮）

> 前置：Phase 2 完成（可切换并跑通 LFM）。

### Task 3.1: TaggerABComparisonUseCase

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/usecase/TaggerABComparisonUseCase.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/usecase/TaggerABComparisonUseCaseTest.kt`

- [ ] **Step 1: 写失败测试（指标聚合纯函数）**
  ```kotlin
  package com.mamba.picme.domain.usecase

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class TaggerABComparisonUseCaseTest {
      @Test
      fun json_validity_counts_valid_and_invalid() {
          val r = ABResult(modelKey = "lfm2_5_vl_450m_extract",
              total = 5, jsonValid = 4, tags = emptyList(),
              avgLatencyMs = 0.0, peakRssKb = 0)
          assertEquals(0.8f, r.jsonValidity, 1e-5f)
      }

      @Test
      fun empty_result_has_zero_validity() {
          val r = ABResult("x", total = 0, jsonValid = 0, emptyList(), 0.0, 0)
          assertEquals(0f, r.jsonValidity, 1e-5f)
      }
  }
  ```
- [ ] **Step 2: 跑测试确认失败**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.TaggerABComparisonUseCaseTest"`
  Expected: FAIL（类型未定义）。
- [ ] **Step 3: 实现 UseCase**
  `TaggerABComparisonUseCase`：串行——加载 SmolVLM → 对固定 N 张照片 `stage3QwenTagging` → 记 `{JSON 合法数(用 TagGenerationPipeline.extractJson 逻辑判断)、tag 列表、时延、峰值RSS}` → `localLlmEngine.unload()` → 加载 LFM → 同批 → 产出 `ABReport`。`ABResult.jsonValidity = jsonValid/total`。
- [ ] **Step 4: 跑测试确认通过**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.TaggerABComparisonUseCaseTest"`
  Expected: PASS。
- [ ] **Step 5: 提交**
  ```bash
  git add app/src/main/java/com/mamba/picme/domain/usecase/TaggerABComparisonUseCase.kt \
          app/src/test/java/com/mamba/picme/domain/usecase/TaggerABComparisonUseCaseTest.kt
  git commit -m "feat(tag): 新增 SmolVLM vs LFM A/B 对比用例"
  ```

### Task 3.2: Debug 页按钮 + 报告展示

**Files:**
- Modify: Debug 页（`grep "debugUiEnabled"` 定位）/ 其 ViewModel

- [ ] **Step 1: 加「打标 A/B 对比」按钮**
  点击 → 跑 `TaggerABComparisonUseCase`（选固定测试集，如 assets 里 10 张样图或最近 N 张已索引照片）→ 展示两模型 JSON 合法率/平均时延/峰值内存。
- [ ] **Step 2: 设备实跑并记录结论**
  在真机跑一次，把结果（谁胜、差距）填入「Phase 0 产物 / A/B 结论」节。
- [ ] **Step 3: 提交**
  ```bash
  git add <Debug 页文件>
  git commit -m "feat(debug): 打标 A/B 对比入口与报告展示"
  ```

---

## Phase 4 — 若 LFM 胜出则翻转为默认（条件执行）

> 仅当 Phase 3 结论为 LFM 胜出时执行；否则到此为止（LFM 作为可选实验模型保留）。

### Task 4.1: 翻转默认 + 文档同步

**Files:**
- Modify: `app/.../data/download/LlmModelDownloadManager.kt`（`ModelConfig.REQUIRED_MODEL_IDS`：`smolvlm_256m` → `lfm2_5_vl_450m_extract`）
- Modify: `TaggerModelSelector.defaultKey` → `lfm2_5_vl_450m_extract`（并更新其单测）
- Modify: `app/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW`（模型卡片文案 / 默认打标模型说明）
- Modify: `CLAUDE.md`（打标章节）、相关 `AGENTS.md`、`docs/03-TECHNICAL-SPECS/*`（用 `/doc-sync-guardian`）
- Modify: 用户记忆 `smolvlm-tag-model.md`

- [ ] **Step 1: 改默认 key + 更新单测**
  `TaggerModelSelector.defaultKey = "lfm2_5_vl_450m_extract"`；`TaggerModelSelectionTest` 的 `default_tagger_is_smolvlm_256m` 改为断言 LFM；跑 `:app:testDebugUnitTest` 通过。
- [ ] **Step 2: 改 REQUIRED_MODEL_IDS**
  把 `smolvlm_256m` 换成 `lfm2_5_vl_450m_extract`。
- [ ] **Step 3: 三语言文案同步**（`/i18n-validator`）。
- [ ] **Step 4: 文档同步**（`/doc-sync-guardian`）：CLAUDE.md 打标段、AGENTS、技术 spec、记忆 `smolvlm-tag-model.md`（更名为 lfm-tag-model 或更新内容）。
- [ ] **Step 5: 提交**
  ```bash
  git add -A
  git commit -m "feat(tag): 默认打标模型切换为 LFM2.5-VL-450M-Extract（A/B 胜出）"
  ```

---

## Phase 0 产物（执行时回填）

- **LFM2 chat_template（Task 0.2）**：`<填入 jinja 字符串>`
- **图片占位符顺序**：`<填入>`
- **model_type**：`<lfm2_vl 或其它 + 是否加 --type>`
- **转换所需 pip 包/transformers 版本**：`<填入>`
- **产物体积（Task 0.3 Step 5）**：总 `<X>M`，llm.mnn.weight `<X>M`，visual `<X>M`
- **MNNConvert 路径**：`<填入>`
- **A/B 结论（Task 3.2）**：`<胜者 + JSON 合法率/时延/内存数据>`

---

## Self-Review（已执行）

- **Spec 覆盖**：Phase 0/1/2/3/4 一一对应 spec 第 5 节五阶段；4 个设计决策（复用 LocalLlmEngine=Task2.2、挖模板=Task0.2、512px=Task2.3、Debug 页 A/B=Task3.2）均有任务落地；两层 MAX_IMAGE_DIM 在 Task 2.3 明确；ModelScope 名 Task 0.4 落地。
- **占位符**：无 TBD/「add error handling」；唯一「数据依赖」是 Task 2.5 的模板字符串来自 Task 0.2 实测产物，已在节首声明并给了兼容降级路径——非占位符。
- **类型一致**：`modelFilesForId`、`TaggerModelSelector.resolve/defaultKey`、`ABResult.jsonValidity` 在定义与使用处签名一致；`loadedModelKey` 字段贯穿 Task 2.3/2.5。
- **gate**：Task 1.2 是 kill-switch，明确「不过则停」。
