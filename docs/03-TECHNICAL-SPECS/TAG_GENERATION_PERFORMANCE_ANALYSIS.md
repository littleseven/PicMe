# TAG 生成性能分析与优化方案

> **关联文档**：[AUTO_TAG_GENERATION_SPEC.md](AUTO_TAG_GENERATION_SPEC.md)
>
> **目标场景**：9000 张照片的全量 3-Pass TAG 扫描

---

## 1. 当前架构概览

TAG 生成采用三阶段管道（[TagGenerationScheduler](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt) + [TagGenerationPipeline](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt)）：

```
Pass 1 (人脸 ROI + 106关键点 + Glint360K R100 Embedding)  →  Pass 2 (DBSCAN 全局聚类)  →  Pass 3 (Qwen3.5-2B 图像理解)
  ~10-50ms + ~20-80ms + ~30-60ms                                 ~2-5s (一次)                  ~2-8s/张
```

执行模型：**单线程 Foreground Service**（[TagGenerationService](../../app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt)），专用单线程调度器 `singleThreadDispatcher` + 3000ms/500ms 两级节流。

---

## 2. 当前性能基线（9000 张）

### 2.1 各阶段耗时估算

| 阶段 | 单张耗时 | 9000 张总耗时 | 占比 |
|------|---------|-------------|------|
| **Pass 1** 人脸检测 + Embedding | ~170ms (推理) + 500ms (节流) = ~670ms | **~100 min** | 13% |
| **Pass 2** DBSCAN 全局聚类 | 一次执行 ~2-5s | **~3s** | <1% |
| **Pass 3** Qwen 图像理解 | ~4000ms (推理) + 500ms (节流) = ~4500ms | **~11.25 hrs** | 87% |
| **合计** | | **~13 hrs** | |

### 2.2 三大核心瓶颈

#### 瓶颈 1：`THROTTLE_MS = 500ms` — 硬节流浪费

[TagGenerationScheduler.kt#L52](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt#L52)
```kotlin
private const val THROTTLE_MS = 500L  // 每张照片强制 delay
```

Pass 1 和 Pass 3 的每张照片循环末尾都执行 `delay(THROTTLE_MS)`。Pass 1 实际推理仅 ~70-150ms，但被 500ms delay 拖成 ~670ms — **节流耗时是推理耗时的 3-7 倍**。

#### 瓶颈 2：Pass 3 Qwen 推理是物理上限

Qwen3.5-2B 多模态推理使用 `engineMutex` 串行保护，每张约 2-8s（CPU 模式）。9000 张的理论下限 (2s/张) 即 **5 小时**。

相关代码：[LocalLlmEngine.imageInference()](../../runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/LocalLlmEngine.kt#L389-L430)
- 512px Bitmap → Vision Encoder → LLM Decode（128 tokens）
- Decode 阶段约 128 × 30ms ≈ 3.8s（CPU 推理）
- `MnnGlobalReleaseLock` 全局锁串行化所有 MNN 操作

#### 瓶颈 3：Bitmap 双重解码

Pass 1 以 640px 加载，Pass 3 重新以 512px 加载。

[TagGenerationPipeline.kt#L91](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt#L91) 和 [pipeline#L216](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt#L216)

两次 `ContentResolver.openInputStream()` + `BitmapFactory.decodeStream()`，每张浪费 20-50ms。

---

## 3. 节流机制详解

### 3.1 两层节流架构

```
┌─────────────────────────────────────────────────────────────┐
│  TagGenerationService.checkGuard()     ← 电池/热状态守卫     │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Battery ≤ 5%           → ABORT（终止扫描）          │   │
│  │  Battery ≤ 15% (非充电)  → PAUSE（→ 3000ms 节流）    │   │
│  │  Thermal ≥ SEVERE       → ABORT                     │   │
│  │  Thermal ≥ MODERATE     → PAUSE（→ 3000ms 节流）    │   │
│  │  其他                   → ALLOW（→ 500ms 节流）     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│  TagGenerationScheduler.guardCheck()                        │
│  → 调用 guard() 获取结果                                    │
│    ALLOW  → 正常执行（后续仍要 delay(THROTTLE_MS)=500ms）   │
│    PAUSE  → delay(THROTTLE_RESTRICTED_MS)=3000ms           │
│    ABORT  → return false → 退出循环                         │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 恒速节流 `THROTTLE_MS = 500ms` 的作用

| 作用 | 说明 |
|------|------|
| **热预防** | 即使"允许"状态，500ms 间隙让 SoC 有散热窗口，避免快速升温到 MODERATE/SEVERE |
| **降低平均功耗** | 将"连续满载"变为"爆发+休息"模式，降低时间平均功率 (TAP) |
| **Foreground Service 存活** | Android 对持续高功耗的 FG Service 会限频甚至杀进程，500ms break 降低被回收概率 |
| **减轻 IO 竞争** | 每次 delay 让 Room WAL checkpoint、ContentResolver 缓存有机会完成 |
| **进度更新节流** | 避免每张照片都触发 StateFlow 更新→UI 重组（每 500ms 一次已足够） |

### 3.3 移除节流的潜在害处

#### 3.3.1 热失控 → 性能反而下降

**最核心的风险**。移除 500ms 间隙后，CPU/GPU 持续满负荷运行：

```
无节流时间线：
Photo 1 (推理完成) → Photo 2 (推理完成) → ... → Photo 50 (推理完成)
             ↑ SoC 温度持续上升                  ↑ 触发 SoC 强制降频
    
降频后 Qwen 推理时间从 3s → 6s+，整体速度反而降低 50%+
```

典型 Android 设备热行为：
- 0-60s：满载运行，性能最佳
- 60-120s：温度达到 THERMAL_STATUS_MODERATE → 降频 20-30%
- 120s+：温度达到 SEVERE → 大幅降频 50%+，甚至触发内核限流

#### 3.3.2 FG Service 被系统回收

Android 12+ 的 Battery Optimization 对前台 Service 有严格的功耗监测。连续 2 分钟 CPU 占用 > 50% 的 FG Service 会被标记为异常，系统可能：
- 杀死 Service（`START_NOT_STICKY` 行为）
- 强制降低进程优先级
- 限制后台运行时间

#### 3.3.3 数据库写入压力

Pass 1 每次循环写入 `face_embeddings` 和更新 `faceRoiResult`，Room 的 WAL 机制在连续写入时会有合检查点 (checkpoint) 开销。无间隙的连续写入会导致：
- WAL 文件快速增长
- 偶发写入延迟尖峰（checkpoint 触发时）

#### 3.3.4 用户感知影响

- 手机明显发热 → 用户主动关闭应用
- 通知栏进度更新过于频繁（每 ~170ms 一次）→ UI 无意义重组
- 电池百分比肉眼可见下降 → 用户焦虑

### 3.4 综合风险评估

```
移除 THROTTLE_MS 的净收益表：

              Pass 1 (移除)                Pass 3 (移除)
收益           97min → 25min (+72min)       11.25hr → 10hr (+1.25hr)
风险评估       风险低                        风险中
              · Face Det 非持续满负载         · Qwen 推理是 CPU/GPU 高负载
              · 单张仅 70-150ms              · 连续 3-8s 推理，易过热
              · IO 为主，CPU 轻量             · 温度积累效应显著

结论：Pass 1 ✅ 可移除
      Pass 3 ❌ 不建议完全移除（建议降低到 100-200ms 或条件移除）
```

---

## 4. 优化方案

### 4.1 P0：立即实施（低风险，高收益）

#### 4.1.1 移除 Pass 1 的 THROTTLE_MS

**理由**：Pass 1 推理仅 70-150ms/张，功耗极小，500ms 节流导致 3-7 倍开销浪费。

**改动**：[TagGenerationScheduler.kt](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt) — 移除 `scanAll()`、`scanIncremental()`、`scanPass1()` 中的 `delay(THROTTLE_MS)`。

**收益**：Pass 1 从 **100 分钟 → 25 分钟**（+72 min）

**风险**：无。Pass 1 的 FaceDetector 和 MNN Embedding 推理是突发式 IO/CPU 负载，不持续。

#### 4.1.2 减少 Qwen maxTokens 从 128 → 64

**理由**：前 40-50 个 token 已足够表达场景/活动/tags，后续 token 冗余。

**改动**：[TagGenerationPipeline.kt#L53](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt#L53)
```kotlin
private const val QWEN_MAX_TOKENS = 64
```

**收益**：Qwen 推理从 ~4s → ~2.5s，Pass 3 从 **11.25 小时 → 7 小时**（+4.25 hrs）。

**风险**：极低。场景描述类任务 64 tokens 足够输出完整 JSON。

#### 4.1.3 降低 Pass 3 的 THROTTLE_MS 到 100ms

**理由**：完全移除有热风险，但 500ms 太过保守。Qwen 推理本身已占 2.5-4s，100ms 节流可忽略不计。

**改动**：Pass 3 循环末的 `delay(THROTTLE_MS)` → `delay(100L)`，或仅在 `guardCheck()` 返回 PAUSE 时才增加延迟。

**收益**：Pass 3 从 **11.25 小时 → 10.5 小时**（+45 min）。

**风险**：低。100ms 间隙足够让 SoC 散热和 DB checkpoint 完成。

### 4.2 P1：中等投入

#### 4.2.1 启用 OpenCL GPU 加速

**理由**：Qwen 的 Visual Encoder 和 LLM Decode 在 GPU（Adreno/Mali）上可提速 2-3x。

**改动**：[TagGenerationScheduler.kt](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt#L844) `ensureModelLoaded()` 中传入 `useOpencl=true`。

**收益**：Qwen 推理从 ~2.5s → ~1s，Pass 3 从 **7 小时 → 3.5 小时**（+3.5 hrs）。

**风险**：
- 部分设备不支持 OpenCL（需 fallback 到 CPU）
- OpenCL kernel 首次编译耗时较多
- GPU 推理功耗高于 CPU，可能更快触发热限制

#### 4.2.2 照片去重跳过 Pass 3

**理由**：连拍照片、截图、相似照片可能占 30-50%，标签相同无需重复推理。

**方案**：
1. Pass 1 时计算 dHash（差异哈希，64-bit，~1ms/张）
2. 存储到 `media_assets.perceptual_hash` 字段
3. Pass 3 前查询：`SELECT labels FROM media_assets WHERE perceptual_hash = ? AND labels IS NOT NULL LIMIT 1`
4. 若命中则复用标签，跳过 Qwen 推理

**收益**：假设 40% 重复，Pass 3 从 **7 小时 → 4.2 小时**（+2.8 hrs）。

**工作量**：中等（新增字段 + 哈希计算 + 查询逻辑）。

### 4.3 P2：长远优化

#### 4.3.1 合并 Bitmap 解码

Pass 1 的 640px Bitmap 缩放至 512px 供 Pass 3 复用，避免二次 ContentResolver 调用。或者统一到 640px 加载（Qwen 内部 preprocessBitmap 会自动缩放到 420px）。

**收益**：~35ms/张 → 9000 张节省 **~5 分钟**（边际收益）。

#### 4.3.2 批量 DB 写入

Pass 1 现在每条 embedding 单独 insert，可以批量 50 条/事务。

**收益**：Pass 1 缩短 ~2-3 分钟（边际收益）。

### 4.4 优化汇总

```
优化的本质：

不是「让 Qwen 跑得更快」，而是「让 Qwen 少跑」+「让 Qwen 跑的时候不空转」。

- maxTokens=64     → 每次推理少跑 50% 的 token
- 照片去重           → 少跑 30-50% 的照片
- 移除 P1 节流       → Pass 1 不浪费等待时间
- OpenCL GPU       → 每次推理跑得快 2x
- 降低 P3 节流       → Pass 3 几乎全速
```

### 4.5 优化效果对比

| 策略 | Pass 1 | Pass 3 | **总计** | 累计降幅 |
|------|--------|--------|---------|---------|
| **当前基线** | ~100 min | ~11.25 hrs | **~13 hrs** | — |
| + 移除 P1 节流 | **~25 min** | ~11.25 hrs | **~11.7 hrs** | 10% |
| + maxTokens=64 | ~25 min | **~7 hrs** | **~7.4 hrs** | 43% |
| + P3 节流 500→100ms | ~25 min | **~6.5 hrs** | **~6.9 hrs** | 47% |
| + OpenCL GPU | ~25 min | **~3.3 hrs** | **~3.7 hrs** | 72% |
| + 照片去重 40% | ~27 min | **~2 hrs** | **~2.5 hrs** | 81% |
| **最优组合** | **~25 min** | **~1.5 hrs** | **~2 hrs** | 85% |

### 4.6 实施路线

#### Phase 1（立即，当天上线）
1. 移除 Pass 1 所有 `delay(THROTTLE_MS)` — 改 3 个方法中的 `delay` 调用
2. `QWEN_MAX_TOKENS = 128` → `64` — 改 1 行常数
3. Pass 3 `delay(500L)` → `delay(100L)` — 改 3 处

#### Phase 2（1-2 天）
4. ✅ `ensureModelLoaded()` 支持 `useOpencl=true` + 自动降级到 CPU — **已完成**

#### Phase 3（3-5 天）
6. `media_assets` 表加 `perceptual_hash` 字段 — 需 Room migration
7. Pass 1 计算 dHash 并存储
8. Pass 3 去重查询逻辑

---

## 5. Pass 2 DBSCAN 性能评估

当前 DBSCAN 是朴素 O(n²) 实现 [scheduler#L710-L774](../../app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt#L710-L774)：

```kotlin
for (i in 0 until n)          // n = 所有 face embedding 数量
    for (j in 0 until n)      // O(n²) 全量比较
        cosineDistance(a, b)  // 512 维浮点运算
```

即便 3000 张有人脸（~3000 embeddings），复杂度为 **3000²/2 × 512 ≈ 2.3B FLOPs**，手机 CPU 约 1-2 GFLOPS → **~2s**。

结论：**非瓶颈，不需要优化**。但如果后续 embedding 数量 > 50000，建议引入 KD-tree 或 Ball-tree 加速。

---

## 6. 最终结论

1. **TAG 生成瓶颈的本质**：不是代码效率问题，而是 Qwen 模型 2-8s/张的物理推理延迟
2. **最大单点收益**：`maxTokens=64`（减 50% decode 时间）+ Pass 1 移除节流（省 75 分钟）
3. **节流可以移除但不能完全放弃**：Pass 1 安全，Pass 3 建议保留 100ms 最小间隙防止热积累
4. **终极方案**：去重 + GPU 加速 + 智能节流，可将 13 小时压缩到 **2-3 小时**

---

> **维护者**：RD Agent
> **状态**：分析完成 · Phase 2 第 4 项已实施
> **最后更新**：2026-06-24
