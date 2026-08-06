# 相册图片去重设计 Spec

> **日期**：2026-08-06
> **状态**：待实施
> **范围**：`:app` 模块——修复并接通既有的「重复照片管理」半成品，识别精确重复 + 高度相似图片，用户确认后保留一张。
> **关联文档**：`app/src/main/java/com/mamba/picme/features/gallery/AGENTS.md`（若存在）、`docs/superpowers/specs/2026-08-06-pexels-test-image-download-design.md`（测试图来源，去重的典型目标）

---

## 1. 背景与问题

相册中存在重复图片（典型来源：Debug 页 `SampleDataGenerator` 百度抓取、Pexels 批量下载 `TEST_PEXELS_*`，以及编辑/另存产出）。用户希望：找出重复 → 确认 → 每组只保留一张。

### 1.1 现状：存在一套断线的半成品

代码库已有一整套去重垂直切片，但**未接进导航/设置（死代码）**，且检测器有两个硬 bug：

| 组件 | 文件 | 现状 |
|------|------|------|
| 检测器 | `app/.../core/common/DuplicateImageDetector.kt` | 有 MD5 + pHash + hamming，但 **pHash `1L shl i`(0..1023) 溢出 64-bit Long**；近似判定用「pHash 完全相等」而非汉明距离阈值 |
| UseCase | `app/.../domain/usecase/FindDuplicateMediaUseCase.kt` | **`File(uri.removePrefix("file://"))` + `!file.exists()` 跳过** → 不认 `content://media/...`，几乎筛不出系统相册照片 |
| ViewModel | `app/.../features/gallery/MediaViewModel.kt`（约 206–264 行） | 已暴露 `_duplicateGroups` / `_isScanningDuplicates` / `startDuplicateScan()` / `deleteDuplicateGroup(...)` / `deleteAllDuplicatesExceptOne()`，DI 已挂 |
| UI | `app/.../features/gallery/components/DuplicateManager.kt`、`GalleryTopBar.kt`（约 121 行 `DuplicateManagerTopBar`） | 完整 Compose 栈（路由/页面/分组卡/预览对话框/顶栏），但 `DuplicateManagerRoute` 无任何引用 |
| 领域模型 | `app/.../domain/model/DuplicateGroup.kt` | `id, fileUris, isExactDuplicate, getKeepUri()/getDeleteUris()` |
| 文案 | `values/strings.xml`（约 326 行起）+ zh-rCN + zh-rTW | `manage_duplicates` 等已存在，无 Kotlin 引用 |
| 测试 | `app/src/test/.../core/common/DuplicateImageDetectorTest.kt` | 存在，需扩展 |

### 1.2 删除链路已安全可用

`MediaViewModel.deleteDuplicateGroup` → `deleteMediaByIds`：Android 10+ 走 `createDeleteRequest` 用户授权，低版本直接删；并级联清理 face embedding。本次直接复用，不动。

### 1.3 人脸聚类已独立存在（不在本范围）

`人物/People` 页（`FaceClusterEngine` + ArcFace 512 维 embedding + DBSCAN + `persons`/`face_embeddings`/`person_relations`）已完整上线。经与用户确认，**本次去重不并入人脸维度**，人脸聚类沿用现有人物页。

## 2. 方案选型记录（已与用户确认）

| 决策点 | 结论 |
|--------|------|
| 功能范围 | **A · 先修通基础去重**：精确 + 高度相似，分组→确认→保留一张；人脸聚类不并入 |
| 相册规模 | **较小（<1000 张）** → **按需现算**，不做数据库迁移、不新增 worker、不新增模型 |
| 检测策略 | **方案 A · 分层检测**：Pass1 精确（size+MD5，不解码、秒出）+ Pass2 近似（修复后的 pHash + 汉明距离） |

确认的默认值（实施期可在代码常量处调整）：

- **媒体范围**：v1 仅照片。视频的精确去重（size+MD5，零解码）作为便宜的后续项，不在本次。
- **每组默认「保留」择优规则**：像素最多（width×height）→ 并列看 `aestheticScore`（若已打分）→ 再并列取最新 `captureDate`；UI 内可改选。
- **相似阈值**：精确 = MD5 相等；近似 = 汉明距离 ≤ 5（pHash 64-bit，保守阈值）。

## 3. 架构与组件

核心原则：**把哈希/聚类逻辑做成纯 Kotlin 核心，Android I/O 隔离在薄壳**，使核心可纯 JVM 单测（绕开 Robolectric/SDK36 坑）。

### 3.1 改动地图

| 类型 | 对象 | 动作 |
|------|------|------|
| 复用（不动） | `DuplicateManager.kt` UI 栈、`MediaViewModel` 去重/删除方法、`deleteMediaByIds` 链路、`DuplicateGroup` 模型、三语文案 | 原样复用 |
| 重写 | `DuplicateImageDetector.kt` | 拆为**纯计算核心**（哈希/聚类/择优）+ **Android I/O 薄壳**（`ContentResolver` 读取）；修复 pHash 溢出、改汉明聚类 |
| 重写 | `FindDuplicateMediaUseCase.kt` | 去掉 `File(file://)` 写法，统一走 `ContentResolver.openInputStream` / `openFileDescriptor` |
| 新增 | 导航 + 设置入口 | `Screen.DuplicateManager` 路由 + NavHost `composable(...)` + 设置页 `SettingsCategory.GALLERY` 卡内一行（文案 `manage_duplicates` 已存在） |
| 新增（极少） | 文案 | 「精确重复 / 高度相似」分组标题等新 key，三语同步 |
| 不碰 | 数据库、worker、模型、`MediaRepository` 合并逻辑 | 无迁移、无新依赖 |

> 设计要点：检测核心入参为抽象 `MediaSource`（提供 size/mime/`inputStream`/解码后 Bitmap 与宽高），出参为 `List<DuplicateGroup>`。薄壳用 `ContentResolver` 实现该接口；单测用内存 `ByteArray` fake。

### 3.2 信号获取（无数据库）

由于不入库，去重所需信号在扫描时现取：

- `size`：`contentResolver.openFileDescriptor(uri,"r")?.statSize`（或 `AssetFileDescriptor.length`）
- `mime`：`contentResolver.getType(uri)`
- `width/height`：pHash 解码时顺手取 `BitmapFactory.Options.outWidth/outHeight`（先 `inJustDecodeBounds` 不增成本）
- `aestheticScore` / `captureDate`：从 `MediaAsset`（已由 `mergeMedia` 富化）取，用于择优

## 4. 检测算法

### 4.1 Pass 1 · 精确去重（秒出，不解码）

1. 取全部照片 URI。
2. 按 `(size, mime)` 分桶；**仅 ≥2 个的桶**进入下一步（单元素必唯一，跳过）。
3. 桶内用 `DigestInputStream` 包 `openInputStream` **流式**算 MD5（不整块读入内存，防 OOM）。
4. MD5 相同 → 精确重复组（`isExactDuplicate = true`）。

> 直接命中「一模一样」主诉；对全量图无解码开销，<1000 张毫秒级。

### 4.2 Pass 2 · 近似去重（修复后的 pHash）

- **修复溢出**：现有 `1L shl i`(0..1023) 仅低 64 位有效。改为标准 **pHash = 32×32 灰度 → 2D DCT → 左上 8×8（去 DC）→ 64-bit Long**。
- **修复相似判定**：从「pHash 完全相等」改为 **汉明距离 ≤ 5**，用 **并查集（Union-Find）** 聚类成组（`isExactDuplicate = false`）。<1000 张 O(n²)≈50 万次比较，无需索引。
- 阈值 `SIMILAR_HAMMING_THRESHOLD = 5` 提为常量，便于调参。
- 解码统一走 `decodeSampled`，目标 32×32，省内存；超大图降采样。

### 4.3 择优（默认保留项）

`pickKeepIndex(group)`：组内按 `width×height` 降序 → 并列按 `aestheticScore` 降序 → 再并列按 `captureDate` 降序 → 取首个为默认「保留」索引。UI 可改选。

## 5. 数据流与 UI/UX

```
设置 → 相册功能 → 「重复照片管理」（manage_duplicates 文案）
   → DuplicateManager 页（进入即扫 + 手动「重扫」按钮）
   → [进度] 扫描 X/Y：精确组先出，相似组后出
   → 两段列表：【精确重复】+【高度相似】
        每组卡：缩略图 ×N + 单选「保留」+「删除其余」
   → 预览对话框（已有 DuplicatePreviewDialog）确认
   → deleteMediaByIds → createDeleteRequest 用户授权 → 删除 + 级联清 face embedding
```

- **触发**：纯按需（进入页面扫描 + 重扫按钮），不做后台 worker、不挂现有 `TagGenerationService`。
- **路由接线**：`navigation/Screen.kt` 增 `data object DuplicateManager : Screen("duplicate_manager")`；NavHost（`MainActivity` / `MainPagerHost`）注册 `composable(...)`；`SettingsScreen` 的 `SettingsCategory.GALLERY` 卡内加 `SettingsClickableRow`。
- **进度**：复用 `MediaViewModel._isScanningDuplicates` + 分子/分母；扫描在 IO 调度器，结果回主线程塞 `_duplicateGroups`。

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| URI 不可读 / `openInputStream` 失败 | 跳过该项，计入 `skipped` 计数，不中断扫描 |
| 解码失败 / 损坏图 | 跳过 pHash（仍可参与 Pass1 精确判定），计入 `skipped` |
| 超大图 OOM 风险 | `decodeSampled` 降采样到 32×32；MD5 走 `DigestInputStream` 流式 |
| 删除被 scoped-storage 拒绝 | 沿用 `deleteMediaByIds` 现有重试/提示；授权后重试 |
| 空结果 | 列表空态文案「未发现重复照片」 |
| 扫描中离开页面 | 取消协程，不残留半结果（或保留已算部分，二次进入续算——MVP 取消重算） |

## 7. 红线与约束核对

| 红线 | 结论 |
|------|------|
| **[PRIVACY]** | MD5/pHash 全部端侧 `ContentResolver.openInputStream` + 本地解码，**零上传**，与现有打标流水线同构 ✅ |
| **[PERF]** | Pass1 无解码；Pass2 降采样解码；扫描在 IO 线程 + 进度反馈；<1000 张交互可接受 ✅ |
| **[I18N]** | 新增分组标题等文案同步 `values/`（EN）+ `values-zh-rCN/` + `values-zh-rTW/` ✅ |
| **[DOC-SYNC]** | 实施后更新 `app/AGENTS.md`（新增/接通「重复照片管理」小节） |
| **代码硬规则** | 无全限定名、无通配 import、lambda 显式命名、日志 tag `PoLang:Gallery`、4 空格缩进 ✅ |

## 8. 测试计划

- **纯 JVM 单测**（扩展 `DuplicateImageDetectorTest`，测纯核心，不依赖 Android）：
  - MD5 流式正确性（`ByteArrayInputStream` fake）
  - pHash **64-bit 正确性 + 确定性**（同图两次相等；Resize/重压缩后汉明距离小）
  - 汉明距离计算
  - Pass1 精确分桶（`(size,mime)` 单元素跳过、桶内 MD5 合并）
  - Pass2 并查集聚类（阈值边界：距离 4 合并、距离 8 不合并）
  - `pickKeepIndex` 择优排序（像素/评分/日期）
- **Android I/O 薄壳**：小规模 instrument 或手测（`content://` URI 真读）。
- **闭环验证**：`./gradlew :app:assembleDebug` 编译 → 真机安装 → 用 Pexels/抓取下载造几组重复图 → 设置入口进入「重复照片管理」→ 确认精确组与相似组正确 → 保留一张删除其余 → 相册确认清理结果。

## 9. 交付清单

- [ ] 重写 `DuplicateImageDetector`（纯核心 + Android 薄壳；修 pHash 溢出 + 汉明聚类 + 择优）
- [ ] 重写 `FindDuplicateMediaUseCase`（`ContentResolver` 流式，去 `File(file://)`）
- [ ] 扩展 `DuplicateImageDetectorTest` 纯 JVM 单测全绿
- [ ] 接线：`Screen.DuplicateManager` 路由 + NavHost `composable` + `SettingsScreen` GALLERY 卡入口
- [ ] 新增/确认文案三语同步（`values/` + `values-zh-rCN/` + `values-zh-rTW/`）
- [ ] 编译通过 + 真机闭环验证
- [ ] 更新 `app/AGENTS.md`
