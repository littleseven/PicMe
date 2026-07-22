# LFM2.5-VL-450M-Extract 打标模型接入设计稿

- 日期：2026-07-22
- 状态：**设计稿，待评审**
- 范围：将 Liquid AI 的 `LFM2.5-VL-450M-Extract`（Mamba2 混合架构 VLM）转成 MNN 格式，接入相册打标管道，与现有 SmolVLM 做 A/B，胜出则替换为默认打标模型。

## 1. 背景与目标

当前相册「打标」（Pass 3）使用 SmolVLM-256M/500M（MNN 版）做图像理解，输出结构化 JSON（scene/activity/objects/tags/summary）。本设计探索用 **LFM2.5-VL-450M-Extract** 替代：

- LFM2.5-VL-450M-Extract 是 Liquid AI 首个 Liquid Nanos 系列视觉模型，**Extract 变体专为「从图片抽取字段并返回 JSON」调优**，与打标任务天然契合。
- 架构：LFM2-350M 骨干（**混合 Mamba2 SSM + Transformer**）+ SigLIP2 视觉塔 + pixel_unshuffle 投影器，约 450M 参数。
- 官方仅提供 Transformers / llama.cpp / vLLM / MLX / Ollama 部署，**无官方 MNN 版**，需自行转换。

**成功标准**（分阶段，逐级 gate）：
1. 模型成功转成 MNN 并能在端侧加载、跑通一次图片推理（Mamba2 自定义算子真正执行）。
2. 接入为「可选打标模型」，能在真实相册照片上手动跑通。
3. A/B 数据化对比 SmolVLM：JSON 合法率、标签质量、时延、峰值内存。
4. 若 LFM 胜出，翻转为默认打标模型，并同步三层文档。

## 2. 现状分析（接入点）

- 打标核心：`app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt`
  - Stage 3 调用 `llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens)`，图片缩放到 `MAX_VISION_SIZE=512`。
  - Prompt 由 `app/.../domain/tag/prompt/DefaultTagPromptProvider.kt` 生成，强制末尾输出 JSON（针对小模型优化）。
- VLM 推理桥：`runtime-core/.../inference/local/llm/MnnLlmClient.kt` + `runtime-core/src/main/cpp/llm_jni_bridge.cpp`
  - 标准 MNN-LLM `Llm` + `MultimodalPrompt`，用 `<img>image_0</img>` 占位符，像素走 uint8 原始 RGB（MNN 内部按 `llm_config` 的 `image_mean/norm` 归一化）。
  - **⚠️ JNI 图片路径硬编码 Qwen ChatML 模板**（`<|im_start|>system…<|im_start|>user\n<img>image_0</img>…`），LFM2 格式不同，需处理。
  - JNI `MAX_IMAGE_DIM=420`；LFM2-VL `tile_size=512`。
- 引擎装配：`runtime-core/.../facade/AgentConfigurator.kt:40` 创建单一共享 `LocalLlmEngine(context)`；`LocalLlmEngine.loadModel(modelId, useOpencl)` 已按 modelId 参数化。**当前无「打标模型选择」设置项。**
- 模型注册：
  - `app/src/main/res/raw/llm_models.json`（已有 `smolvlm_256m`、`smolvlm_500m`、`qwen3_vl_2b` 等条目）。
  - `app/.../data/download/LlmModelDownloadManager.kt`：`getModelFiles(modelId)` 分支 + 文件列表常量（如 `SMOLVLM_MODEL_FILES`）。
  - 模型下载源统一走 ModelScope，落盘 `filesDir/llm_models/<modelId>/`。

## 3. 可行性结论（已用本仓库事实核对）

**绿灯——依赖用户自己的 MNN fork（`/Users/guoshuai/code/MNN`，master 分支）。** 该 fork 的转换器与运行时已具备真实的 LFM2/Mamba 支持：

- GGUF 路径识别 `MODEL_ARCH.MAMBA`（`transformers/llm/export/gguf/constants.py:259/446/1156`）。
- HF 路径有专门的 **LFM2-VL** 视觉转换器 `Lfm2VlVision`（`transformers/llm/export/utils/vision.py:1824`），处理 SigLIP2 + pixel_unshuffle，tile_size=512/patch=16/downsample=2，并把 `image_mean=[127.5,…]`、`image_norm=[1/127.5,…]` 写入 `llm_config`——**与现有 uint8 像素 JNI 归一化路径一致**。
- `model_mapper.py:1005` 处理 `lfm.` 前缀；`transformers.py:808` 处理 Qwen3.5/LFM2 风格 rope。
- `custom_op.py:109` 声明算子类型 `"gated_delta_rule" | "mamba" | "rwkv" | "gla"`，即 SSM/线性注意力运行时路径存在，与 `libMNN.so` 内 `Conv1D+SiLU`、`gated_delta_rule` 内核及相关提交（"fix OpenCL LinearAttention GQA bug"、"Reset linear-attention states"）一致。
- chat_template 由转换器写入 `config.json`（`llmexport.py:97-117`）。

**结论**：这不是「MNN 能否跑 Mamba」的问题——fork 已支持。真正的工作是：在 Extract 模型上跑通转换器，再接到位。

## 4. 设计决策（已与用户确认）

| 决策点 | 选择 | 备注 |
|---|---|---|
| 接入架构 | **复用 `LocalLlmEngine`** + 新增 `tagger_model_key` 设置 | 共享引擎按所选 key 加载；A/B 串行（内存只能驻留一个 VLM） |
| Chat 模板 | **Phase 0 先挖 LFM2 真实模板** | 从 HF tokenizer 提取 `chat_template`，确认格式后再定 JNI 改法 |
| 图片尺寸 | **LFM 路径放开到 512px** | 让 SigLIP2 视觉塔吃满一个 tile（tile_size=512） |
| A/B 工具 | **Debug 页按钮** | 串行跑固定 N 张照片，输出 JSON 合法率/质量/时延/峰值内存 |

未选方案（备查）：独立 `LfmTaggerEngine` 类（解耦更干净但重复 load/生命周期代码）；改 JNI 按模型读取模板（通用但本次先按真实模板最小改动）。

## 5. 分阶段设计（逐级 gate，前阶段不过则停）

### Phase 0 — 转换（macOS，Python）

**前置事实（已核对 fork）**：转换器架构已完整注册——`model.py:55` `MODEL_CLASS_MAPPING['lfm2_vl']='Lfm2VlForConditionalGeneration'`，`model_mapper.py:942 regist_lfm2_vl`（含 config/model/decoder/attention/**linear_attention** 映射），`vision.py:1824 Lfm2VlVision`（SigLIP2 + pixel_unshuffle，视觉默认 `quant_bit=8`）。**但 fork 内无任何 LFM 转换脚本/文档/已转产物** → 即该路径代码在、但从未在 LFM 模型上实跑过，需按下方命令首跑并预期调试。

- 依赖：fork 的 `transformers/llm/export/llmexport.py`（CLI）；`MNNConvert` 二进制（默认 `../../../build/MNNConvert`，需确认 fork 已编译出）；LFM2-VL HF 模型代码（`Lfm2VlForConditionalGeneration`，需确认 transformers 版本 / Liquid 包可 import）。
- **命令**（`cd /Users/guoshuai/code/MNN`）：
  ```bash
  python transformers/llm/export/llmexport.py \
    --path LiquidAI/LFM2.5-VL-450M-Extract \
    --dst_path ./model_lfm2_5_vl_450m_extract \
    --quant_bit 4
  # 视觉编码器自动 int8（Lfm2VlVision.quant_bit=8）；embedding 默认 bf16
  # 纯流程自检可加 --skip_weight；推理自检可加 --test "<query>"
  ```
- **先挖模板**：从该模型 HF tokenizer 提取 `chat_template`（转换器会写入 `config.json`），记录 system/user/图片占位符真实格式（供 Phase 2）。
- **首跑 4 个验证点**：① HF repo 的 `config.model_type` 是否为 `lfm2_vl`（不是则加 `--type lfm2_vl`）；② `Lfm2VlForConditionalGeneration` 能否 import（transformers/Liquid 包版本）；③ `MNNConvert` 二进制是否在预期路径；④ Mamba2 各算子是否都被 `custom_op`（`"mamba"`/`"gated_delta_rule"`）覆盖。
- 产物校验：目录含 `config.json`、`llm_config.json`（含 `is_visual`、`vision_start/end`、`image_pad`、`image_mean=[127.5,…]`、`image_norm=[1/127.5,…]`）、`llm.mnn`、`llm.mnn.weight`、`visual.mnn`、`visual.mnn.weight`、`tokenizer.txt`，体积合理（int4 下 LLM 主体约数百 MB）。
- 风险：Mamba2 某算子未覆盖 → 扩展 `custom_op.py`；视觉转换首跑 bug → 按 `Lfm2VlVision` 调试。
- 退出准则：产物文件齐全、`llm_config.json` 字段正确、且 `--test` 能产出非乱码文本。

### Phase 1 — 端侧冒烟（🔴 GATE / kill-switch）

- 上线模型：传到 ModelScope（用户命名空间，如 `budaoshou/LFM2.5-VL-450M-Extract-MNN`），或 `adb push` 到 `filesDir/llm_models/lfm2_5_vl_450m_extract/`。
- 注册：`llm_models.json` 加 `lfm2_5_vl_450m_extract` 条目；`LlmModelDownloadManager` 加文件列表常量 + `getModelFiles`/`getModelFilesByTags` 分支。
- 冒烟：用 `MnnLlmClient` 加载，跑一次 `imageInference`（测试 bitmap）。
- **通过判据**：无 SIGSEGV（JNI 已有 sigsetjmp 守卫）、输出非空、logcat 显示视觉 + 线性注意力/mamba 路径真正执行（`vision_us`>0、decode tokens>0）。
- **kill-switch**：若推理崩溃或乱码 → 停下，回转换器/运行时排查，下游全部搁置。

### Phase 2 — 接入为可选打标模型

- 设置：新增 DataStore `tagger_model_key`（默认 `smolvlm_256m`）；`AgentConfigurator`/`TagGenerationScheduler` 按该 key 加载 tag 引擎；在设置页/Debug 页暴露切换。
- Chat 模板：按 Phase 0 挖到的真实 LFM2 模板，最小改动图片 JNI 的 `prompt_template`（按模型构造 system/user 与 `<img>image_0</img>` 注入），保证不破坏 SmolVLM/Qwen 既有路径。
- 图片尺寸：图片上限分两层——`TagGenerationPipeline.MAX_VISION_SIZE=512`（已满足）、`MnnLlmClient.kt:527` 的 `MAX_IMAGE_DIM=420`、`llm_jni_bridge.cpp:72` 的 C++ `MAX_IMAGE_DIM=420`。LFM 路径需把后两处放开到 512（按模型区分，SmolVLM 保持 420），否则 JNI 会在 420 截断，喂不满足 512 tile。
- Prompt：先复用 `DefaultTagPromptProvider` 的 JSON schema（Extract 本就是抽取式），必要时加 `LfmTagPromptProvider` 微调。
- 退出准则：Debug 页能切换到 LFM 并对真实相册照片跑通打标，输出合法 JSON。

### Phase 3 — A/B 质量对比（Debug 页按钮）

- 串行：加载 SmolVLM-256m → 打标固定 N 张照片 → 记录指标 → 卸载 → 加载 LFM → 同批 N 张 → 对比。
- 指标：JSON 解析成功率、标签质量（人工抽检 + 启发式，如标签数/命中物体）、平均时延、峰值 RSS。
- 数据驱动判定胜者。

### Phase 4 — 若 LFM 胜出则翻转为默认

- 更新 `ModelConfig.REQUIRED_MODEL_IDS`（默认打标模型由 `smolvlm_256m` 改为 `lfm2_5_vl_450m_extract`）、默认 `tagger_model_key`、prompt provider、模型卡片文案。
- 文档同步（doc-sync-guardian）：CLAUDE.md 打标章节、相关 AGENTS.md、技术 spec、更新 `smolvlm-tag-model` 记忆。

## 6. 错误处理与回退

- 转换失败：先补 `custom_op`；若 Mamba2 算子缺口过大无法短期补齐，记录阻塞点，暂不推进，SmolVLM 保持默认。
- 端侧崩溃：依赖既有 JNI SIGSEGV 守卫 + `OpenClGuardian` 超时降级；LFM 加载/推理失败时 tagger 自动回退 SmolVLM（设置项带「加载失败回退默认」语义）。
- 质量不达标：Phase 3 判负则不翻转，LFM 仅作为可选实验模型保留。

## 7. 测试策略

- Phase 1：单图冒烟（设备端 logcat 断言）。
- Phase 3：固定照片集 A/B（Debug 页），断言 JSON 合法率与时延基线。
- 既有单测：`TagNormalizerTest` 等不受影响（输入仍是 JSON）；新增模型切换/回退逻辑补 JVM 单测。
- 回归：`./gradlew :app:testDebugUnitTest` + `ai-gate.sh`。

## 8. 非目标（YAGNI）

- 不做 LFM 的多 tile / 高分辨率拼接优化（先单 tile 512px 跑通）。
- 不引入第二个常驻 VLM（内存不允许，A/B 串行即可）。
- 不动 chat agent（仍用 Qwen3.5-2B 远程/本地链路）。
