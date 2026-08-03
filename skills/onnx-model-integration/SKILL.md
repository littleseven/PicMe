---
name: onnx-model-integration
description: |
  ONNX 模型接入专家。预防 AI 在接入 ONNX 模型时重复犯已验证过的错误。
version: 1.1.0
created: 2026-05-03
updated: 2026-05-25
maintainer: [RD] 全栈工程师
tags:
  - onnx
  - model
  - inference
  - integration
  - preprocessing
---


# ONNX 模型接入专家 (ONNX Model Integration)

> **定位**：预防 AI 在接入 ONNX 模型时重复犯已验证过的错误（颜色格式、归一化、激活函数等）。
> **来源**：项目 ONNX 模型集成实践经验
> **触发时机**：接入任何新 ONNX 模型（InsightFace、MediaPipe、自定义模型）时

## 接入前必须回答的 5 个问题

在编写任何模型接入代码前，必须在 AGENTS.md 或代码注释中明确以下 5 项：

| # | 问题 | 常见错误 | 验证方式 |
|---|------|---------|---------|
| 1 | **颜色通道顺序** | 误将 BGR 当 RGB（Python `cv2.dnn.blobFromImage(swapRB=True)` 的陷阱） | 对照官方 Python reference implementation 的预处理代码 |
| 2 | **归一化参数** | 均值/方差写错（如 `[0.485, 0.456, 0.406]` vs `[127.5, 127.5, 127.5]`） | 检查模型训练时的 `transforms.Normalize` 或 `preprocess_input` |
| 3 | **输入尺寸** | 忽略了 `resize` 与 `letterbox` 的区别 | 官方文档的 input shape + padding 策略 |
| 4 | **输出格式** | 将 logits 当概率值（重复 sigmoid/softmax），或将概率当 logits | 官方后处理代码中的激活函数调用 |
| 5 | **后处理公式** | NMS 阈值、bbox 解码、anchor 计算与官方实现不一致 | 逐行对照 reference implementation 的后处理部分 |

## 分阶段接入策略（禁止一次性提交全部代码）

### Phase 1: 配置元数据验证（先提交此部分）
```kotlin
data class ModelConfig(
    val inputSize: Pair<Int, Int>,
    val colorOrder: ColorOrder, // RGB or BGR
    val normalization: NormalizationParams,
    val outputFormat: OutputFormat, // PROBABILITY or LOGITS or OFFSET
    val postProcess: PostProcessConfig
)
```
**要求**：先提交 `ModelConfig` 定义 + 从官方文档提取的数值，由 CR 审核后再进入 Phase 2。

### Phase 2: 最小可复现验证
提供 **Python reference vs Kotlin output** 的对比单元测试：
```kotlin
@Test
fun `preprocessing produces same output as Python reference`() {
    val testImage = loadTestImage("face_001.jpg")
    val kotlinOutput = preprocessor.preprocess(testImage)
    val pythonOutput = loadNpy("face_001_preprocessed.npy") // 从 Python 导出
    assertArrayEquals(pythonOutput, kotlinOutput, tolerance = 1e-5f)
}
```

### Phase 3: 完整集成
仅在前两阶段通过后才进行完整 pipeline 集成。

## 已验证的错误模式清单

### ❌ 错误模式 A：颜色格式假设
```kotlin
// ❌ 错误：默认假设 BGR
val blob = preprocess(image, swapRB = true)

// ✅ 正确：显式声明并验证
val colorOrder = ModelConfig.colorOrder // RGB
val blob = when (colorOrder) {
    ColorOrder.RGB -> preprocess(image, swapRB = false)
    ColorOrder.BGR -> preprocess(image, swapRB = true)
}
```

### ❌ 错误模式 B：重复激活
```kotlin
// ❌ 错误：模型输出已是概率，又套 sigmoid
val confidence = sigmoid(rawOutput)

// ✅ 正确：根据 outputFormat 决定是否激活
val confidence = when (modelConfig.outputFormat) {
    OutputFormat.PROBABILITY -> rawOutput // 已是概率
    OutputFormat.LOGITS -> sigmoid(rawOutput)
}
```

### ❌ 错误模式 C：裁剪缩放系数
```kotlin
// ❌ 错误：使用默认 1.5f 而未对照官方实现
val cropScale = 1.5f

// ✅ 正确：从 reference implementation 提取
val cropScale = modelConfig.postProcess.faceCropScale // 1.2f for InsightFace
```

## 日志规范
- 模型预处理：`PoLang:ModelPreproc` - 记录输入尺寸、颜色顺序、归一化参数
- 模型推理：`PoLang:ModelInfer` - 记录输入 shape、输出 shape、推理耗时
- 后处理：`PoLang:ModelPostProc` - 记录 NMS 前/后框数、最终输出

## 审查清单（CR 必须检查）
- [ ] 是否已提供官方 Python/C++ reference implementation 链接？
- [ ] ModelConfig 中的 5 个参数是否已逐项验证？
- [ ] 是否有最小可复现的 Python vs Kotlin 对比测试？
- [ ] 日志中是否记录了颜色顺序和归一化参数？
- [ ] 后处理公式是否与 reference 逐行一致？

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
