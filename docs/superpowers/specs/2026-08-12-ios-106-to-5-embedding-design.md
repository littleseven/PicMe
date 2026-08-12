# iOS embedding 路径接通 106→5 对齐设计

> 状态:设计中(brainstorming 产物)
> 日期:2026-08-12
> 关联 Android 代码:`TagGenerationPipeline.kt`、`MnnLandmarkAdapter.kt`、`FaceClusterEngine.kt`
> 关联 iOS 代码:`Pass1Pipeline.swift`、`MnnLandmarkAdapter.swift`、`FaceAlignment.swift`、`MnnFaceDetectorBridge.{h,mm}`

## 1. 背景与根因

### 1.1 iOS 现状(TAG 扫描 / embedding 路径,相册静态图)

```
RetinaFace native 5pt → FaceAlignment.alignFace(仿射 112×112) → MNN Glint360K embedding → 聚类
```

iOS 已有 `FaceAlignment.convert106ToLandmarks5` 的完整 Swift 移植(`FaceAlignment.swift:36-68`,与 Android `TagGenerationPipeline.kt:642-687` 逐行一致),但为 **dead code,从未接通**。

### 1.2 根因(经代码事实确认,推翻"左右映射 bug"假设)

**不是** iOS 复现了 Android `ab0482c3` 的左右映射 bug。决定性证据:`FaceClusterMaintenance.swift:20` 注释 *"(iOS 5pt sim~0.5)"*——iOS native 5pt 对齐的 same-person 相似度峰值仅 ~0.5,阈值被迫压到 0.45。同模型 Android 侧 0.65。

根因是 **native 5pt 对齐精度不足**:RetinaFace 自带 5 点 = 5 个独立点、无冗余;Android 的 106→5 用双眼各 8 点均值定眼位,抗抖动。0.45 是精度差的**代偿症状**,非 MNN Glint360K 模型本身差异(同模型,Android 正常)。

## 2. 点序基础(关键设计约束)

三层概念,必须严格区分:

| 概念 | 含义 |
|---|---|
| **2d106det** | 模型名 |
| **原生序** | 模型直出 = InsightFace 原始点序(`adapt` 入参 `native`) |
| **统一106序** | 项目自定义规范,`convert106ToLandmarks5` 的索引(52-57 等)按它定义(`adapt` 输出) |

- Android 链路:`FaceDetectorManager.detectLandmarksForRoi`(`FaceDetectorManager.kt:155`)内部 `:380/499/615` 调 `adapter.adapt` → 经 `MnnLandmarkAdapter.FULL_REMAP`(`MnnLandmarkAdapter.kt:30-72`)把原生序重排成**统一序** → 返回 `landmarks106` → `convert106ToLandmarks5`。
- iOS `FULL_REMAP`(`MnnLandmarkAdapter.swift:13-43`)与 Android(`MnnLandmarkAdapter.kt:30-72`)**逐项一致**。

**结论**:iOS 接通必须走 `detectLandmarks106 → adapt → convert106ToLandmarks5`,**adapt 不可跳过**(跳过则原生序喂 convert,索引 52-57 会取到嘴巴区)。

**镜像**:双端 adapter 只在**前置相机预览**路径不同(Android `1-x` / iOS 不镜像,因检测渲染同源 buffer);TAG 扫描是相册静态图(Android 传 `LENS_FACING_BACK`、iOS 静态图),**双端都不镜像**,本次设计不受影响。

## 3. 目标与边界

### 目标
把 iOS embedding 对齐从 native 5pt 升级为 106→5(与 Android 对齐),提升 same-person sim,为 iOS 阈值独立标定提供数据基础。

### 不动
- **MNN Glint360K embedder**(`PLMnnFaceEmbedder`,`Pass1Pipeline.swift:64`):2026-08-12 已由 ONNX 回切 MNN(`ORTFaceEmbedder` 已删,本次不动 embedder。
- **相机美颜路径**:已用 106。
- **聚类算法结构**。
- **`shared/ClusteringConfig.kt`**:iOS 仍是本地 Swift 常量,两端聚类系数独立。

## 4. 数据流改动

核心改动一处:`Pass1Pipeline.swift:177-183` 的 per-ROI 循环。

```
当前:  RetinaFace native5pt → alignFace → extractEmbedding
目标:  RetinaFace ROI
         → detectLandmarks106:(单 ROI Stage-2,MnnFaceDetectorBridge.h:101,已存在)
         → MnnLandmarkAdapter.adapt(原生106→统一106,已存在)
         → FaceAlignment.convert106ToLandmarks5(启用现 dead code)
         → FaceAlignment.alignFace(仿射 112×112)
         → PLMnnFaceEmbedder.extractEmbedding(MNN Glint360K, Pass1Pipeline.swift:189)
fallback: detectLandmarks106 失败 → RetinaFace native 5pt(与 Android fallback 一致)
```

复用 4 个现成零件 + 启用 1 个 dead-code 函数。**不写新 JNI、不动 embedder。**

## 5. 两步走

### Step 1 — 接通 + 诊断(阈值保持 0.45)

1. 接通 §4 的 106→5 链路,native 5pt 降级 fallback。
2. 诊断埋点(debug 开关,对标 Android commit `87458683`):
   - **簇内 pair sim 分布**:用现有 0.45 聚类结果近似 same-person。
   - **跨簇 pair sim 分布**:看 same/cross 分离度。
   - **fallback 计数**:应接近 0。
   - 可选:保存前 N 张对齐后 112×112 脸到 `Documents/debug_faces` 肉眼核对。
3. **验收**:same-person sim 中位数/峰值从 ~0.5 回升到 0.6+;same/cross 分离度增大;fallback 率低;扫描总时长可接受。

### Step 2 — 阈值独立标定(Step1 数据确认后)

基于 iOS 实测 same/cross sim 分布,**独立标定** `FaceClusterer.minSimilarity`、`FaceClusterMaintenance` 的 `cosineThreshold`/`mergeSimilarityThreshold`/`splitIntraMin`(具体值视 Step1 分布而定,不预设)。**不照搬 Android 0.65**(两端聚类系数独立)。

修正 `FaceClusterMaintenance.swift:20` 的 "(iOS 5pt sim~0.5)" 注释(根因已消除)。

**验收**:已知相册 fixture 回归,同人合并/异人分离质量提升,无性能回退。

## 6. 误注释修复(接通时一并)

`FaceAlignment.swift:34` 现注释 *"106-point order: InsightFace 2D106det canonical order"* **错误**。改为明确:输入须为经 `MnnLandmarkAdapter.adapt` 重排后的**统一 106 序**(非原生序)。加注释强约束,防止后续误用原生序直喂。

## 7. 错误处理

- `detectLandmarks106` 失败 / `adapt` 输入不足 / 点数 < 212 → 回退 native 5pt(埋点计 fallback)。
- 对齐矩阵退化(det≈0)→ `FaceAlignment` 现有单位矩阵回退,不动。
- embedding NaN/Inf/零向量 → embedder 现有过滤,不动。

## 8. 测试

- **双端一致性(关键)**:固定一组原生序 106 点 fixture → 两端各跑 `adapt → convert106ToLandmarks5`,断言输出 5 点完全一致(或误差 < ε)。守住 `FULL_REMAP + convert` 双端同源。
- **集成**:`Pass1Pipeline` 对多人脸测试图产出 embedding,通过埋点验证走 106→5 路径;可触发 fallback 分支。
- **回归**:Step2 阈值标定后,已知相册 fixture 聚类质量与 Step1 baseline 对比提升。

## 9. 风险

- **性能(Step1 观测)**:Pass1 全量扫描每 ROI 多一次 2d106 推理。相机美颜实时预览已在跑 2d106,静态图单帧开销更宽裕,但全量相册图片数大,需 Step1 观测总扫描时长。超预期则备选 ROI 级并发 / 异步流水线(不在本次范围,视数据决定)。
- **双端数值差异**:iOS `FaceAlignment` 仿射与 Android `FaceClusterEngine` 独立实现,靠 §8 双端一致性测试守住。
