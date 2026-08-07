# Chat 单图图像理解跟随设置页打标模型 — 设计

> **日期**：2026-08-07
> **状态**：已实现（分支 `fix/chat-image-model-selection`）
> **Scope**：`app` 模块 Chat 页单图理解链路

## 背景与问题

Chat 页发送单张图片触发图像理解时，`ChatViewModel.sendImageMessage()` 调用
`LocalModelService.withModelLoaded()` 未传 `modelId`，回退到 `AgentConfigurator` 硬编码默认值
`qwen3_vl_2b`；模型未下载时只能回复「模型未加载」。设置页的「打标模型」选择
（`AUTO / florence2_base / qwen3_vl_2b`，持久化于 `tagger_model_key`）对 Chat 图像理解不生效，
且 Florence-2（ONNX，不走 MNN）在 Chat 链路完全不可用。

## 设计决策（方案 B）

保留 `ChatViewModel` 现有流程结构（流式占位、错误区分、performance metrics、MemoryManager 回写），
只把「引擎选择」改为与打标同源：

1. **模型解析同源**：`TagGenerationScheduler` 新增公开 `currentTaggerModelKey()`（即原私有
   `taggerModelKey` 解析：读设置 → Florence-2 文件存在性检查 → `TaggerModelSelector.resolve`
   下载感知兜底）。ChatViewModel 经此取值，不重复解析逻辑。
2. **Florence-2 分支**：`TagGenerationScheduler` 新增 `describeImage(bitmap: Bitmap)` 重载
   （原 `describeImage(uri)` 加载 Bitmap 后委托同一私有 `describeLoadedBitmap`；uri 版仍负责回收，
   Bitmap 版所有权归调用方）。Chat 传已解码的 Bitmap，避免对内部存储文件路径二次解码
   （`TagGenerationPipeline.loadBitmap` 走 ContentResolver，不支持裸文件路径）。
   失败（未下载/初始化失败/推理空）→ 报错「模型未加载：florence2_base …」。
3. **MNN 分支（qwen3_vl_2b）**：`withModelLoaded(modelId = modelKey, …)` 显式传模型 id；
   提示词改用 `ImageDescriptionStrategyResolver.resolve(modelKey, appLanguage)` 按 UI 语言直出
   （替换原硬编码中文提示词）。未下载错误信息由 `LocalModelService.ensureModelLoaded` 携带所选模型名。
4. **依赖注入**：`ChatViewModelDependencies` 新增 `tagGenerationScheduler: TagGenerationScheduler? = null`
   （与 `optimizeGachaController` 同款可空约定，单测默认不接线）；`AppContainer` 注入容器单例。
   未注入时兜底 `qwen3_vl_2b`（与 `AgentConfigurator` 默认一致），保持旧行为。
5. **modelUsed 修正**：图像理解消息的 `modelUsed` 由 `currentModelLabel()`（恒 `remote_deepseek`，
   对本链路是错误的）改为实际 VLM 模型 key。

## 否决方案

- **方案 A（直接复用 `describeImage(uri)` 全量替换）**：失败只返回 null，丢失「未下载 vs 推理出错」
  的错误区分与 metrics；且 uri 形参走 ContentResolver，与 Chat 的内部存储裸路径不兼容。

## 涉及文件

| 文件 | 改动 |
|------|------|
| `app/.../domain/tag/TagGenerationScheduler.kt` | `currentTaggerModelKey()`；`describeImage(bitmap)` 重载 + `describeLoadedBitmap` 私有核心 |
| `app/.../features/chat/ChatViewModel.kt` | `sendImageMessage()` 引擎选择跟随设置；提示词语言化；modelUsed 修正 |
| `app/.../features/chat/ChatViewModelDependencies.kt` | 新增可空 `tagGenerationScheduler` 注入 |
| `app/.../di/AppContainer.kt` | 注入容器单例 scheduler |

## 验证

- `./gradlew :app:compileDebugKotlin` 编译通过。
- 手动链路：设置页选 Florence-2 → Chat 发单图走 caption（中文 UI 自动 en→zh）；选 Qwen3-VL-2B →
  走 MNN imageInference；所选模型未下载 → 错误消息含对应模型名。
