#!/bin/bash

# PoLang Wiki 文档生成脚本
# 用途: 从 docs/ 目录提取关键内容生成 Wiki 页面

set -e

WIKI_DIR="/Users/guoshuai/AndroidStudioProjects/PoLang/wiki"

echo "📝 生成 PoLang Wiki 文档..."

# 1. 人脸检测双引擎
cat > "$WIKI_DIR/Face-Detection-Engines.md" << 'EOF'
# 人脸检测双引擎架构

PoLang 采用 **InsightFace 2D106 (默认首选)** + **MediaPipe Face Mesh 468→106 (备选)** 的双引擎架构,通过统一的适配层实现无缝切换与自动容灾。

## 🎯 设计目标

- **性能优先**: InsightFace + NNAPI GPU/NPU 加速,预期性能提升 3-5x
- **可用性保障**: InsightFace 漏检时自动回退到 MediaPipe
- **统一接口**: `FaceLandmarkAdapter` 抽象层屏蔽底层差异
- **隐私保护**: 100% 本地推理,严禁云端依赖

## 📊 引擎对比

| 特性 | InsightFace 2D106 | MediaPipe Face Mesh |
|------|-------------------|---------------------|
| **定位** | 默认首选引擎 | 备选引擎 |
| **模型类型** | ONNX (RetinaFace Det10G + 2d106det) | TFLite (Face Landmarker) |
| **关键点数量** | 106 点 (标准 Face++ 规范) | 468 点 → 映射到 106 点 |
| **硬件加速** | NNAPI (GPU/DSP/NPU) | CPU (异步分析流) |
| **检测策略** | 两阶段: ROI 检测 → 关键点提取 | 单阶段: 直接输出 468 点 |
| **预期耗时** | Det10G: 0.5-1s, 2D106: 0.2-0.4s | 整体: 0.3-0.6s |
| **适用场景** | 高性能需求、精细美型 | 兼容性兜底、复杂光照 |

## 🔧 InsightFace 两阶段检测

### Phase 1: Det10G ROI 检测

**模型**: `det_10g.onnx` (RetinaFace 简化版)

**输入**:
- 尺寸: 动态 (保持宽高比,最长边 ≤ 640px)
- 格式: BGR, HWC
- 归一化: `mean=[127.5, 127.5, 127.5]`, `std=[128.0, 128.0, 128.0]`

**输出**:
- `bbox_pred`: 边界框预测 (x1, y1, x2, y2)
- `cls_pred`: 置信度分数 (0-1)
- `landmark_pred`: 5 个关键点 (10 个值)

**过滤策略**:
- 置信度阈值: `score ≥ 0.5`
- NMS (非极大值抑制): IoU 阈值 0.4
- 误检处理: 人脸框面积 < 图像面积 1% 则丢弃

### Phase 2: 2D106 关键点提取

**模型**: `2d106det.onnx`

**输入**:
- 尺寸: 固定 192×192
- 来源: 从 Det10G ROI 裁剪并 resize
- 格式: BGR, CHW

**输出**:
- `landmarks`: 106 个点 (212 个值),归一化坐标 [0,1]

### NNAPI GPU 加速配置

```kotlin
val sessionOptions = OrtSession.SessionOptions()
try {
    sessionOptions.addNnapi()  // 启用 Android NNAPI
    Logger.i(TAG, "NNAPI execution provider enabled")
} catch (e: Exception) {
    Logger.w(TAG, "NNAPI not available, falling back to CPU", e)
}
```

**预期性能提升**:
- Det10G: 2.5s (CPU) → 0.5-1s (NNAPI)
- 2D106: 1.0s (CPU) → 0.2-0.4s (NNAPI)

## 🔄 MediaPipe 468→106 映射

**映射文件**: `docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md`

**映射原则**:
1. **语义对齐**: MediaPipe 索引点与火山引擎 106 点语义一致
2. **左右对称**: 正确处理镜像翻转 (前置摄像头)
3. **缺失插值**: 无直接对应点时,通过邻近点线性插值

**坐标系转换**:
```kotlin
// MediaPipe NDC [-1, 1] → 归一化 [0, 1]
val normalizedX = (ndcX + 1.0f) / 2.0f
val normalizedY = (ndcY + 1.0f) / 2.0f

// 前置摄像头镜像翻转
if (isFrontCamera) {
    normalizedX = 1.0f - normalizedX
}
```

## 🛡️ 容灾与回退机制

### 自动回退触发条件

**InsightFace → MediaPipe**:
1. Det10G 连续 3 帧未检测到人脸
2. 2D106 模型加载失败 (ONNX Runtime 异常)
3. NNAPI 初始化失败且 CPU 推理超时 (>3s)
4. 关键点数量异常 (<106 点或 >111 点)

### 冷却恢复机制

**冷却窗口**: 30 秒

**恢复条件**:
1. 冷却时间到期
2. 用户手动切换引擎 (设置页)
3. 应用重启

## 📈 性能优化策略

### 智能帧跳过

- 默认每 3 帧检测一次 (10fps @ 30fps 预览)
- 无人脸时降低到每 5 帧 (6fps)
- 检测到快速运动时提高到每 2 帧 (15fps)

### Bitmap 缓存

避免重复 ImageProxy → Bitmap 转换 (~50ms):

```kotlin
private var cachedBitmap: Bitmap? = null
private var lastTimestamp: Long = 0

fun getOrConvertBitmap(imageProxy: ImageProxy): Bitmap {
    if (imageProxy.timestamp == lastTimestamp && cachedBitmap != null) {
        return cachedBitmap!!  // 复用缓存
    }
    cachedBitmap = imageProxy.toBitmap()
    lastTimestamp = imageProxy.timestamp
    return cachedBitmap!!
}
```

## 📚 相关文档

- [完整技术架构](../docs/FACE_DETECTION_ENGINE_ARCHITECTURE.md)
- [468→106 映射策略](../docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md)
- [坐标系统标准](Coordinate-System)

---

**最后更新**: 2026-05-05  
**维护者**: PoLang RD Team
EOF

echo "✅ Face-Detection-Engines.md 已生成"

# 2. 实时美颜系统
cat > "$WIKI_DIR/Beauty-Engine.md" << 'EOF'
# 实时美颜系统 (大美丽引擎)

大美丽 (Big Beauty) 是 PoLang 自研的 OpenGL ES + EGL 实时美颜引擎,支持磨皮、美白、瘦脸、大眼、唇色、腮红等完整美颜链路。

## 🎨 核心能力

| 能力 | 实现方式 | 强度范围 |
|------|----------|----------|
| **磨皮** | 双边滤波 (9pt 快速近似) | 0-100 |
| **美白** | YUV 亮度调整 + Log 曲线 | 0-100 |
| **瘦脸** | FaceWarp 网格变形 | -50~+50 |
| **大眼** | 径向放大变换 | 0-100 |
| **唇色** | HSV 色相调整 + 纹理妆容 | 0-100, 12 种色号 |
| **腮红** | 双颊椭圆染色 | 0-100 |
| **专业调色** | 曝光/对比度/饱和度/色温/色调 | -100~+100 |
| **风格特效** | 卡通/素描/浮雕/色块化/交叉线 | 开关 |

## 🔧 渲染管线

### 多 Pass 架构

```
Input Texture
    ↓
Pass 1: 基础美颜 Shader
    ├─ 磨皮 (双边滤波)
    ├─ 美白 (YUV 亮度)
    ├─ 瘦脸 (FaceWarp 网格)
    └─ 大眼 (径向放大)
    ↓
Pass 2: 唇色 (FaceMakeupPass)
    ├─ BLEND_MODE_MULTIPLY
    ├─ HSV 色相调整
    └─ 纹理妆容叠加
    ↓
Pass 3: 腮红 (FaceMakeupPass)
    ├─ BLEND_MODE_OVERLAY
    ├─ 双颊椭圆区域
    └─ 自然红润染色
    ↓
Pass 4: 风格特效 (StyleEffectShader)
    ├─ ToonFilter (卡通)
    ├─ SketchFilter (素描)
    ├─ EmbossFilter (浮雕)
    ├─ PosterizeFilter (色块化)
    └─ CrosshatchFilter (交叉线)
    ↓
Output Texture → SurfaceView
```

### Ping-Pong FBO

使用双 FBO (Framebuffer Object) 实现多 Pass 渲染:

```kotlin
var currentTextureId = inputTextureId
var nextOutputFbo = if (fboPing.getTextureId() == currentTextureId) fboPong else fboPing

// Pass 1: 唇色渲染到 FBO
if (lipColorStrength > 0.001f) {
    faceMakeupPass.setIntensity(lipColorStrength)
    faceMakeupPass.setBlendMode(FaceMakeupPass.BLEND_MODE_MULTIPLY)
    val rendered = faceMakeupPass.render(
        inputTextureId = currentTextureId,
        outputFbo = nextOutputFbo,
        makeupType = FaceMakeupPass.MakeupType.LIP
    )
    currentTextureId = nextOutputFbo.getTextureId()
    nextOutputFbo = if (nextOutputFbo === fboPing) fboPong else fboPing
}

// Pass 2: 腮红渲染到另一个 FBO
if (blushStrength > 0.001f) {
    faceMakeupPass.setIntensity(blushStrength)
    faceMakeupPass.setBlendMode(FaceMakeupPass.BLEND_MODE_OVERLAY)
    val rendered = faceMakeupPass.render(
        inputTextureId = currentTextureId,
        outputFbo = nextOutputFbo,
        makeupType = FaceMakeupPass.MakeupType.BLUSH
    )
    currentTextureId = nextOutputFbo.getTextureId()
}
```

## 📸 拍照 GPU 化

### 离屏渲染流程

```
ImageProxy (原始帧)
    ↓
OffscreenRenderer.render()
    ├─ 创建 EGL Pbuffer Surface
    ├─ 绑定纹理: ImageProxy → GL_TEXTURE_2D
    ├─ 执行完整美颜管线 (同预览)
    └─ 读取像素: glReadPixels() → Bitmap
    ↓
GpuBeautyProcessor.applyPostProcess()
    → 色调滤镜 (ColorMatrix)
    ↓
PhotoRepository.save() → MediaStore
```

### 性能指标

| 分辨率 | CPU 路径 (旧) | GPU 路径 (新) | 提升 |
|--------|---------------|---------------|------|
| **1080p** | 800-1200ms | ~280ms | 3-4x |
| **4K** | 3-5s | ~750ms | 4-6x |

### 效果一致性

- **之前**: 预览/拍照效果一致性 70-85% (不同 Shader 实现)
- **现在**: 预览/拍照效果一致性 99%+ (复用同一套 Shader)

## 🎭 风格特效

### 支持的特效

| 特效 | Shader 名称 | 视觉效果 |
|------|-------------|----------|
| **卡通** | ToonFilter | 强化边缘线条,降低色彩层级 |
| **素描** | SketchFilter | 黑白铅笔素描风格 |
| **浮雕** | EmbossFilter | 凸起质感雕刻风 |
| **色块化** | PosterizeFilter | 限制颜色层级 (默认 4 层) |
| **交叉线** | CrosshatchFilter | 手绘版画风格 |

### 叠加逻辑

- 色调滤镜与风格特效可同时生效
- 处理顺序: 先 ColorMatrix 色调,再 GPU 风格渲染
- 风格特效内部互斥 (同时只能选一种)

## 🚀 未来演进

### Phase 2 (4-8 周)
- [ ] 引导滤波磨皮: O(N) 复杂度,更优边缘保持
- [ ] 眉毛美化: 独立 Shader mask
- [ ] 多色号唇色: 动态纹理替换

### Phase 3 (8-16 周)
- [ ] 3D LUT 滤镜: 64×64×64 颜色查找表
- [ ] 多尺度细节分层: 工业级磨皮算法
- [ ] ML Kit Selfie Segmentation: 背景虚化

## 📚 相关文档

- [BEAUTY_ENGINE_TECH_SPEC](../docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md)
- [BEAUTY_ENGINE_FALLBACK](../docs/08-FALLBACK/BEAUTY_ENGINE_FALLBACK.md)

---

**最后更新**: 2026-05-05  
**维护者**: PoLang RD Team
EOF

echo "✅ Beauty-Engine.md 已生成"

# 3. 架构决策记录
cat > "$WIKI_DIR/Architecture-Decisions.md" << 'EOF'
# 架构决策记录 (ADR)

本文档记录 PoLang 项目的关键技术决策及其理由。

## 📋 ADR 列表

### ADR-001: 大美丽单引擎架构

**状态**: ✅ 已接受  
**日期**: 2026-04  
**影响范围**: `beauty-engine/egl/`, App 层依赖

**决策**: 移除 GPUPixel,仅保留自研 OpenGL ES 引擎

**理由**:
- ✅ 完全自主可控,无商业 SDK 依赖
- ✅ 代码量减少 40%,维护成本降低
- ✅ 渲染效果一致性提升 (预览/拍照同源 Shader)
- ❌ 初期开发成本高 (已克服)

**后果**:
- GPUPixel 相关代码全部清理
- App 层仅依赖 `beauty-engine:api`
- 容灾降级展示无美颜原生预览

详见: [ADR-001](../docs/02-ARCHITECTURE/ADR/ADR-001-beauty-engine-architecture.md)

---

### ADR-002: OpenGL 离屏渲染统一管线

**状态**: ✅ 已接受  
**日期**: 2026-05  
**影响范围**: `beauty-engine/egl/OffscreenRenderer.kt`

**决策**: 预览与拍照使用同一套 OpenGL Shader

**理由**:
- ✅ 预览/拍照效果一致性从 70-85% 提升至 99%+
- ✅ 代码复用率提升,避免重复实现
- ✅ 性能优化: 1080p 处理 < 300ms (CPU 路径 800-1200ms)

**实现**:
- 预览: `SurfaceTexture → OpenGL ES → SurfaceView`
- 拍照: `Bitmap → EGL Pbuffer → OpenGL ES → Bitmap`

**降级策略**:
- GPU 离屏渲染失败时 (EGL 上下文创建失败/OOM)
- 自动回退到现有 CPU Canvas 路径
- 确保拍照不失败

详见: [ADR-002](../docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md)

---

### ADR-003: 坐标系统标准

**状态**: ✅ 已接受  
**日期**: 2026-04  
**影响范围**: 全项目 (人脸检测、渲染引擎、UI 展示)

**决策**: 统一使用归一化坐标 [0,1],明确左右命名规范

**理由**:
- ✅ 避免坐标系混用导致的错位问题
- ✅ 前置摄像头镜像翻转逻辑清晰
- ✅ 跨平台移植友好 (iOS/Web)

**规范**:
- **OpenGL NDC**: [-1,1],Y 轴向上
- **图像像素坐标**: [0,width]×[0,height],Y 轴向下
- **归一化坐标**: [0,1],Y 轴向下
- **左右命名**: 以人物视角为准 (非屏幕视角)

**坐标转换公式**:
```kotlin
// 像素坐标 → 归一化坐标
val normalizedX = pixelX / imageWidth
val normalizedY = pixelY / imageHeight

// 归一化坐标 → OpenGL NDC
val ndcX = normalizedX * 2.0f - 1.0f
val ndcY = -(normalizedY * 2.0f - 1.0f)  // Y 轴翻转

// 前置摄像头镜像翻转
if (isFrontCamera) {
    normalizedX = 1.0f - normalizedX
}
```

详见: [ADR-003](../docs/02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md)

---

## 🔄 决策流程

1. **提出问题**: 在 GitHub Issues 中描述技术难题
2. **方案对比**: 列出至少 2 个备选方案,分析优缺点
3. **团队讨论**: RD Team 内部评审,PM/QA 参与意见
4. **决策记录**: 编写 ADR 文档,明确选择理由
5. **实施验证**: 编码实现并通过 QA 验收
6. **归档维护**: 将 ADR 纳入版本管理,定期回顾

## 📊 决策状态

- ✅ **已接受**: 已实施并验证通过
- ⏸️ **进行中**: 正在实施,未完成
- ❌ **已拒绝**: 经过评估后放弃
- 🔄 **已废弃**: 曾经实施,后被新方案替代

---

**最后更新**: 2026-05-05  
**维护者**: PoLang RD Team
EOF

echo "✅ Architecture-Decisions.md 已生成"

echo ""
echo "🎉 Wiki 核心文档生成完成!"
echo ""
echo "📁 生成的文件:"
ls -lh "$WIKI_DIR"/*.md
echo ""
echo "💡 提示: 将 wiki/ 目录推送到 GitHub Wiki 仓库即可发布"
