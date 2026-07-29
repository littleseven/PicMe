# 人脸感知的列表图片纵向对齐（Face-Aware List Alignment）

- 日期：2026-07-29
- 状态：已批准（设计），待实现计划
- 范围：`MediaGrid`（相册主网格）、`MediaResultsCarousel`（chat 搜索结果横滑）
- 关联红线：`[PRIVACY]`（人脸检测 100% 端侧，仅存归一化位置）

## 1. 背景与问题

相册中有大量含人脸的照片。在相册图片列表与 chat 搜索结果横滑列表中，竖向人像经常被「砍头」：人脸靠上时切掉头顶，靠下时切掉下巴。

**根因**：两个列表展示图片的方式完全一致——

- `MediaGrid` 的 cell 是正方形（`Modifier.aspectRatio(1f)`）；
- `MediaResultsCarousel` 的卡片是 `120.dp × 150.dp` 竖卡；
- 两者均使用 `AsyncImage` + `contentScale = ContentScale.Crop` + **默认 `Alignment.Center`**。

`ContentScale.Crop` 对竖图从中间裁掉上下两端，居中对齐使人脸偏离框中心时被裁。

**关键发现**：Pass 1 扫描时，端侧 ML Kit / RetinaFace 已精确检测每张图的人脸框（`FaceRoi.roi: RectF`，像素坐标），但序列化进数据库 `MediaEntity.faceRoiResult` JSON 时只保留了 `hasFace / faceCount / isSelfie / isGroupPhoto` 四个计数字段，**人脸框坐标被丢弃**。`FaceEmbeddingEntity` 同样只存 512 维向量、无坐标。因此没有任何持久化的人脸位置数据可供列表消费。

## 2. 目标 / 非目标

**目标**

- 在保持 `ContentScale.Crop`（不整体缩放）的前提下，优化两个列表中图片的**纵向**显示位置，尽量露出人脸并落在 view 合适位置。
- 合影（多人脸）：按**人脸包络中心**对齐。
- 单人脸：人脸中心**略偏上**，留出头顶空间（接近主流相册观感）。
- 老照片：后台**一次性回填**位置数据，全局立即生效。

**非目标**

- 不改动横向对齐（保持水平居中）。
- 不改动 `ContentScale`（保持 Crop）。
- 视频缩略图保持居中（视频不进 Pass 1，`faceFocusY` 恒为 null）。
- 其他列表（重复管理、人物相册封面等）默认不动——数据具备后可一行扩展。

## 3. 数据来源方案对比

| 方案 | 做法 | 取舍 |
|---|---|---|
| **A（采用）** | 复用 Pass 1 **已跑过**的端侧人脸检测，把人脸纵向位置**补存**；老照片后台一次性回填 | 零重复检测、不发热、端侧合规。需一次 DB 加列 + 一次性回填 |
| B | 列表端对可见缩略图实时人脸检测 | 每张图重复 Stage 1 已做的工作，费电发热（违背现有发热治理）。否决 |
| C | 仅对新照片持久化、不回填 | 用户已选「后台一次性回填」，故采用 A + 回填 |

## 4. 端到端数据流

```
端侧 ML Kit / RetinaFace 检测（已有，Pass 1）
  → 由 faces[].roi 算「人脸包络中心」faceFocusY（归一化 0~1； null=无人脸）
  → UPDATE MediaEntity.faceFocusY（新增列）
  → MediaEntity.toDomain() 透传 → MediaAsset.faceFocusY（新增字段）
  → MediaGrid.MediaItem / MediaResultsCarousel.MediaCard
  → faceAwareVerticalAlignment(faceFocusY) 自定义 Alignment
  → AsyncImage(contentScale = Crop, alignment = …)   ← 只改对齐，不改缩放
```

## 5. 存储

### 5.1 存储位置（子选）

- **①（采用）`MediaEntity` 加独立列 `faceFocusY: Float?`**。Room `version + 1` + `Migration`（`ALTER TABLE media_assets ADD COLUMN faceFocusY REAL`），存量行默认 null。列表查询零解析、直接 float，高频路径最优；语义独立。
- ② 塞进现有 `faceRoiResult` JSON 加字段——免 migration，但列表每张图需解析 JSON，语义混杂。否决。

### 5.2 写入点

- **新照片**：Stage 1 检测产出 `Stage1Result.faces`（含 `roi: RectF`）→ 算包络中心 → 在现有写库处（`MediaDao.updateFaceRoiResult` 或等价 upsert）一并 `UPDATE faceFocusY`。
- **老照片回填**：新增回填逻辑，对 `hasFace = 1 AND faceFocusY IS NULL` 的照片，复用现有 ML Kit 检测算包络中心回填；挂在现有扫描调度（`TagGenerationScheduler` / `FaceClusteringWorker` 同级）上，后台低优先级，遵守发热治理。

### 5.3 faceFocusY 计算（纯函数）

```
faces 非空 → (min(roi.top) + max(roi.bottom)) / 2 / bitmapHeight   // 包络中心，归一化 0~1
faces 空   → null
```

`roi` 为相对检测 bitmap 的像素坐标；除以 `bitmapHeight` 归一化后与采样尺寸无关，值稳定。

## 6. UI 对齐算法

新增公共工具 `faceAwareVerticalAlignment(faceFocusY: Float?, biasUp: Float = 1f / 6): Alignment`：

- `faceFocusY == null` → 返回 `Alignment.Center`（无人脸 / 无数据 / 视频 / 回填未完成 → 与现状逐像素一致）。
- 否则返回自定义 `Alignment`，其 `align(size, space, layoutDirection)`：
  - **x 保持居中**（不动横向）：`x = ((space.width - size.width) / 2f).roundToInt()`
  - **y**：把「人脸中心」对齐到「框中心**上方** biasUp 处」，并 clamp 到合法裁剪范围：
    ```
    y = (space.height / 2f - biasUp * space.height - faceFocusY * size.height)
            .roundToInt()
            .coerceIn(space.height - size.height, 0)
    ```
  - `biasUp = 1/6`：人脸中心落在框中心上方约 1/6 框高，头顶留白。

两处 `AsyncImage` 各加一行：

- `MediaGrid.kt` → `MediaItem`：`alignment = faceAwareVerticalAlignment(asset.faceFocusY)`
- `MediaResultsCarousel.kt` → `MediaCard`：`alignment = faceAwareVerticalAlignment(asset.faceFocusY)`

`contentScale = ContentScale.Crop` 保持不变。

```
竖图 → 正方形 cell， Crop（不缩放）：
  居中（现状=砍头）      biasUp=1/6 人脸感知
   ┌────┐               ┌────┐
   │    │               │    │  ← 头顶留白
   │    │               │ 脸 │  ← 人脸中心高于框中心
   │ 腿 │               │ 身 │
   └────┘               └────┘
```

## 7. 降级 / 边界 / 性能 / 隐私

- **零回归**：`faceFocusY == null` → `Alignment.Center`，与现状完全一致。
- **贴边人脸**（faceFocusY ≈ 0 或 1）：`coerceIn` 保证图片不越界，仍尽量露脸。
- **性能**：`Alignment.align` 在 layout 阶段调用（非每帧绘制）；`faceFocusY` 为 float，UI 零额外开销；列表查询只多读一个 float 列。
- **隐私**：100% 端侧检测；仅存归一化位置，不存人脸图像，符合 `[PRIVACY]` 红线。
- **DB 注意**：加列需 `version + 1` + `Migration`；worktree 落库测试若遇版本不符，用 `pm clear` 绕过（已知坑）。

## 8. 范围

- **本次**：`MediaGrid.MediaItem` + `MediaResultsCarousel.MediaCard`。
- **可选扩展（本次不做）**：`DuplicateManager`、人物相册封面等——数据具备后加一行 `alignment` 即可。

## 9. 测试（JVM 单测）

- `faceAwareVerticalAlignment`：纯函数，覆盖 `faceFocusY ∈ {null, 0f, 0.5f, 1f}` × 不同 cell 宽高比 → 期望偏移与 clamp 边界。
- `faceFocusY` 计算：单脸 / 合影包络 / 空 faces。
- `toDomain` 透传：`MediaEntity.faceFocusY → MediaAsset.faceFocusY`。

## 10. 关键改动点（供实现计划）

| 层 | 文件 | 改动 |
|---|---|---|
| DB schema | `data/model/MediaEntity.kt` | 加 `val faceFocusY: Float? = null` |
| DB schema | `data/local/AppDatabase.kt` | `version + 1` + `Migration`（ALTER TABLE ADD COLUMN faceFocusY REAL） |
| DAO | `data/local/MediaDao.kt` | insert/update 携带 faceFocusY；回填查询 `hasFace=1 AND faceFocusY IS NULL`；`updateFaceFocusY(mediaId, y)` |
| Domain 模型 | `runtime-core/.../context/MediaAsset.kt` | 加 `val faceFocusY: Float? = null` |
| 映射 | `data/repository/MediaRepositoryImpl.kt` | 3 处 `MediaAsset(` + `toDomain()` 透传 faceFocusY |
| 检测写入 | `domain/tag/TagGenerationPipeline.kt` + `TagGenerationScheduler.kt` | Stage1 后算 faceFocusY，写库时一并持久化；新增老照片回填逻辑 |
| UI 工具 | 新增 `app/src/main/java/com/mamba/picme/core/image/FaceAwareAlignment.kt` | `faceAwareVerticalAlignment(faceFocusY, biasUp)` + faceFocusY 计算纯函数（`core/image` 为 app 内公共包，gallery/chat 两个 feature 均可依赖） |
| UI | `features/gallery/components/MediaGrid.kt` | `MediaItem` 的 `AsyncImage` 加 `alignment` |
| UI | `features/chat/components/MediaResultsCarousel.kt` | `MediaCard` 的 `AsyncImage` 加 `alignment` |
| 测试 | `app/src/test/` | 对齐算法 + faceFocusY 计算 + toDomain 透传 |

## 11. 验收标准

1. 含人脸的竖图在两个列表中不再「砍头」，人脸落在框中心偏上区域。
2. 无人脸 / 视频 / 回填未完成时，显示与改动前逐像素一致（居中）。
3. 老照片后台回填完成后全局生效。
4. JVM 单测全部通过；编译通过。
5. 人脸检测全程端侧，无任何图片/视频文件上传。
