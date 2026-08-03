# 端侧图片理解模型调研

> **文档状态**: 调研报告（2026-06-21）
> **范围**: 3B 参数以内、可在 Android 端侧运行的图片理解和人脸聚类模型
> **维护者**: 项目开发者

---

## 决策（2026-07-26）

经多轮端侧实测，**图片打标（TAG）方案已定型**：

- **默认首选**：Florence-2-base（ONNX INT8，~260MB，`florence2_base`）——结构化物体检测 + 图像描述，轻量稳定。
- **备选**：Qwen3-VL-2B-Instruct（MNN 4bit，`qwen3_vl_2b`）——未下载 Florence-2 时的回退。

以下方案经评估**出局，不再考虑**：SmolVLM-500M / SmolVLM-2.2B / LFM2-VL / MiniCPM-V / MobileVLM / LightCap。

> 本文其余内容为历史调研记录，保留作参考；打标落地实现见 `TAG_GENERATION.md`。
>
> **变更说明（2026-08）**：端侧**文本** LLM（Qwen3.5-2B 聊天/指令模型）已移除（`AiAgentMode.LOCAL` 及相机本地 Agent 链路全部删除）。MNN 运行时现仅承载 **VLM 打标**（Qwen3-VL-2B）和**人脸检测**模型。本文涉及的 Qwen3-VL-2B 和 Florence-2 打标方案不受影响。

---

## 1. 背景

PoLang 早期使用 Google ML Kit Image Labeling 进行图片标注（ADR-007 Phase 1），覆盖 400+ 常见物体/场景标签（**历史背景**：ML Kit 打标链路已移除，打标现由 Florence-2 / Qwen3-VL-2B 承担，见顶部「决策」）。但 ML Kit 标签粒度有限，无法满足以下需求：

- **语义搜索**：跨模态查询（"温暖的照片""快乐的时光"）
- **中文场景理解**：截图/文档/中文标签图片的深度理解
- **人脸聚类**：按人物自动分组照片（`faceId` 字段已有但未实现聚类）

本报告调研 3B 以内的开源模型，评估其在 PoLang 中的适用性。

---

## 2. 人脸聚类

### 2.1 现状

PoLang 已有 MediaPipe Face Landmarker（468→106 点）与 MNN 人脸检测（备选引擎），Room DB `hasFace`/`faceId` 字段已预留（~~ML Kit Face Detection~~ 已移除；人脸聚类已由 TAG Pass 1/2 落地：Glint360K R100 Embedding + DBSCAN，见 `TAG_GENERATION.md`）。

### 2.2 方案对比

| 方案 | 模型大小 | 推理速度 | 聚类精度 | 新增依赖 | 实现难度 |
|------|---------|---------|---------|---------|---------|
| **Landmark 几何特征 + DBSCAN** | 0 | <1ms | 中 | 无 | 低（~100行） |
| **MobileFaceNet TFLite** | ~4MB | ~20ms/张 | 高 | TFLite Runtime | 中 |
| **MediaPipe Face Embedder** | ~6MB | ~15ms/张 | 高 | MediaPipe Tasks | 中（已有生态） |

### 2.3 方案一：Landmark 几何特征 + DBSCAN（Phase 1 推荐）

利用已有的 106 点 landmark 提取几何特征，无需额外模型。

```
MediaPipe / MNN 人脸检测 → 106 点 Landmark
    → 几何特征向量（眼距、鼻嘴距、脸宽、下颚轮廓）
    → DBSCAN 聚类（eps 基于特征空间距离）
    → 分配 faceId → 写入 Room DB
```

**特征向量示例**:
```kotlin
data class FaceFeature(
    val eyeDistance: Float,       // 左右眼中心距离 / 脸宽（归一化）
    val noseToMouthRatio: Float,  // 鼻尖到嘴中心距离 / 脸高
    val faceWidthToHeight: Float, // 脸宽高比
    val jawShapeFeature: FloatArray, // 下颚 17 点 PCA 简化
    val leftEyeAspectRatio: Float,   // 左眼长宽比
    val rightEyeAspectRatio: Float   // 右眼长宽比
)
```

| 维度 | 评估 |
|------|------|
| **优势** | 零额外模型，复用已有 106 点检测结果，<1ms 提取 |
| **局限** | 同人不同角度/表情可能分群；仅人脸可见时有效 |
| **适用** | 快速验证人脸聚类效果，作为 baseline |

### 2.4 方案二：MobileFaceNet（Phase 2 精度提升）

[MobileFaceNet](https://github.com/sirius-ai/MobileFaceNet_TF) 是轻量级人脸识别模型，输出 128/256/512 维 embedding 向量。

| 维度 | 评估 |
|------|------|
| 模型大小 | ~4MB（TFLite 量化版） |
| 输出 | 128/256/512 维 embedding |
| 推理速度 | ~20ms/张（ARM CPU） |
| 框架 | TFLite / MNN |
| 集成 | 需写适配器（遵循 beauty-engine 已有的人脸检测适配模式） |

**与 PoLang 现有架构的契合度**:
- beauty-engine 已有 MNN/MediaPipe 多引擎适配器模式
- 可新增 `FaceEmbeddingAdapter` 接口，类似已有 `FaceLandmarkAdapter`

### 2.5 方案三：MediaPipe Face Embedder

Google 官方 [MediaPipe Face Embedder](https://ai.google.dev/edge/mediapipe/solutions/vision/face_embedder)，输出 192 维 embedding。

| 维度 | 评估 |
|------|------|
| 模型大小 | ~6MB（TFLite） |
| 输出 | 192 维 embedding |
| 优势 | 与已有 MediaPipe Face Landmarker 同一生态 |
| 劣势 | 需额外 task 文件下载 |

---

## 3. 图片内容理解（3B 以内 VL 模型）

### 3.1 模型对比矩阵

| 模型 | 参数 | 量化后大小 | 中文能力 | 推理速度 | 推理框架 |
|------|------|-----------|---------|---------|---------|
| **SmolVLM 500M** | 0.5B | ~600MB | ⚠️ 一般 | ~7-15s | llama.cpp/GGUF |
| **SmolVLM 2.2B** | 2.2B | ~1GB | ⚠️ 一般 | ~12-30s | llama.cpp/GGUF |
| **Qwen3-VL 2B** | 2B | ~1GB(INT4) | ⭐⭐⭐⭐⭐ | ~15s | MNN/llama.cpp |
| **MiniCPM-V 2.0** | 2.8B | ~1.5GB(INT4) | ⭐⭐⭐⭐ | ~20s | llama.cpp/MLC |
| **MobileVLM 3B** | 3B | ~1.5GB(INT4) | ❌ 未知 | ~20s | llama.cpp |
| **Chinese-CLIP RN50** | 0.1B | ~300MB(ONNX) | ⭐⭐⭐⭐ | ~50ms | ONNX Runtime |
| **MobileCLIP-S0** | 0.05B | ~200MB | ⚠️ 一般 | ~5ms | ONNX Runtime |
| **LightCap** | 0.03B | ~150MB | ⚠️ 一般 | CPU 实时 | TFLite |

### 3.2 模型详解

#### SmolVLM 系列（HuggingFace）

专为移动端设计的小型视觉语言模型。已有开源 App ([Off Grid](https://github.com/brendmung/AbodeLLM)) 在 Android 上成功运行。

**能力**: 图像描述、物体识别、场景理解、文字阅读、视觉问答

```
输入: 图片 + "描述这张照片的内容"
输出: "这是一张在海边拍摄的照片，画面中有两个人站在沙滩上，
      远处可以看到日落。照片左下角有日期水印 2025-08-15。"
```

**PoLang 适用**: 批量离线标注（后台处理，用户不感知延迟）

#### Qwen3-VL 2B（阿里，🔥 中文场景首选）

阿里通义千问团队出品，原生中文视觉语言模型。

| 维度 | 评估 |
|------|------|
| 中文 OCR | ⭐⭐⭐⭐⭐ 截图/文档/手写文字 |
| 中文场景描述 | ⭐⭐⭐⭐⭐ 自然流畅的中文输出 |
| 物体定位 | 支持 bounding box 输出 |
| 推理框架 | MNN（已有）/ llama.cpp |

**PoLang 适用**: 中文截图、带中文标签的图片、中文自然语言描述生成。

#### MiniCPM-V 2.0（面壁智能 + 清华）

2.8B 参数，端侧 VL 模型中场景文字识别能力最强者（匹配 Gemini Pro）。

| 维度 | 评估 |
|------|------|
| 场景文字 | ⭐⭐⭐⭐⭐ |
| 图像描述 | ⭐⭐⭐⭐ |
| 推理框架 | llama.cpp / MLC-LLM |

#### Chinese-CLIP（OFA-Sys）

中文 CLIP 模型，适合图像-文本跨模态匹配。

**核心能力**: 将图片和文本映射到同一向量空间，通过余弦相似度比较。

```
图片 → CLIP Image Encoder → 512维 embedding
查询 "猫" → CLIP Text Encoder → 512维 embedding
余弦相似度 > 阈值 → 匹配
```

**适用场景**: 无法用标签覆盖的语义查询（"温暖的照片""拥挤的街道""安静的角落"）

#### MobileCLIP-S0

CLIP 的极致轻量版（50M），适合快速标注。

| 维度 | 评估 |
|------|------|
| 推理速度 | ~5ms/张（可实时） |
| 标签覆盖 | 对比 ML Kit 多 ~10x 标签 |
| 中文 | 需 Chinese-CLIP 替代 |

#### LightCap

CPU 实时图像标注模型，30M 参数。

| 维度 | 评估 |
|------|------|
| 推理速度 | CPU 实时（<50ms） |
| 输出 | 自然语言描述 |
| 框架 | TFLite |

---

## 4. VL 模型 vs CLIP 类模型

两类模型能力互补，适用场景不同：

| 维度 | VL 模型（SmolVLM/Qwen3-VL） | CLIP 类（Chinese-CLIP/MobileCLIP） |
|------|---------------------------|----------------------------------|
| **输出** | 自然语言描述、问答、物体定位 | 图像+文本 embedding 向量 |
| **推理速度** | 7-30s/张（慢） | 5-50ms/张（快） |
| **使用方式** | 离线批量处理 | 在线实时搜索 |
| **适用** | 图片标注、内容理解、描述生成 | 语义搜索、相似图片查找 |
| **模型大小** | 600MB-1.5GB | 150-300MB |
| **中文** | Qwen3-VL 最强 | Chinese-CLIP 较强 |

**共存策略**: VL 模型后台批量生成描述和标签存 DB，CLIP 模型在线处理语义搜索请求。

---

## 5. 推理框架选择

PoLang 已有的推理框架：

| 框架 | 当前用途 | VL 模型支持 |
|------|---------|------------|
| **MNN** | VLM 打标 (Qwen3-VL-2B) + 人脸检测 | 可支持 Qwen3-VL |

**推荐**:
- VL 模型：**MNN** 或 **llama.cpp**（已有 MNN LLM 经验）
- CLIP 模型：**ONNX Runtime**（已有 Sherpa-ONNX 经验）
- 人脸 Embedding：**TFLite** 或 **MediaPipe**（已有 MediaPipe 生态）

---

## 6. 建议路线

```
Phase 1（历史，已移除/被 VLM 打标取代）:
  ├── 图片标注：ML Kit Image Labeling（400+ 标签，端侧免费）——已移除，
  │   打标现由 Florence-2（默认）/ Qwen3-VL-2B（备选）承担（见顶部「决策」）
  └── 搜索引擎：QueryParser + LLM 语义解析（ADR-007）

Phase 2（人脸聚类，已实施）:
  ├── 实际落地：Glint360K R100 512 维 Embedding + DBSCAN 聚类（TAG Pass 1/2，见 TAG_GENERATION.md）
  └── 几何特征 baseline 与 MobileFaceNet 备选未再需要

Phase 3（图片内容理解升级，可选）:
  ├── 中文场景首选：Qwen3-VL 2B + MNN（复用已有 MNN LLM 框架）——已落地为备选打标 tagger
  ├── 快速标注：LightCap / MobileCLIP-S0（<10ms/张）
  └── 语义搜索：Chinese-CLIP + ONNX Runtime（复用已有 Sherpa-ONNX 框架）——实际落地为 MobileCLIP-S2 + ONNX Runtime

Phase 4（远期）:
  └── SmolVLM / MiniCPM-V：端到端图片理解和问答
```

---

## 7. 参考

- [SmolVLM](https://huggingface.co/HuggingFaceTB/SmolVLM2-2.2B-Instruct) — HuggingFace 移动端 VL 模型
- [Qwen3-VL](https://github.com/QwenLM/Qwen2.5-VL) — 阿里通义千问视觉语言模型
- [MiniCPM-V](https://github.com/OpenBMB/MiniCPM-V) — 面壁智能端侧 VL 模型
- [Chinese-CLIP](https://github.com/OFA-Sys/Chinese-CLIP) — 中文 CLIP 模型
- [MobileCLIP](https://github.com/apple/ml-mobileclip) — Apple 轻量 CLIP
- [LightCap](https://hackernoon.com/lite/new-ai-lightcap-shrinks-image-captioning-for-your-phone-runs-on-cpu) — CPU 实时图像标注
- [MobileFaceNet](https://github.com/sirius-ai/MobileFaceNet_TF) — 轻量人脸识别
- [MediaPipe Face Embedder](https://ai.google.dev/edge/mediapipe/solutions/vision/face_embedder) — Google 人脸 Embedding
- [Off Grid](https://github.com/brendmung/AbodeLLM) — Android 端侧 VL 开源 App（SmolVLM 运行验证）
- [ADR-007](../02-ARCHITECTURE/ADR/ADR-007-natural-language-photo-search.md) — 相册搜索技术方案
