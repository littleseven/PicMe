# 证件照页可编辑化设计：边缘参数调节 + 手动涂抹修补

> **日期**：2026-08-06
> **状态**：已获用户确认，待实施
> **范围**：`:app` 模块 `features/idphoto/` 与 `domain/matting/`

## 1. 背景与问题

当前证件照页（`IDPhotoScreen`）的结果是一次性产出：加载时跑一次双模型融合抠图（MediaPipe selfie_segmenter + MODNet，max 融合 + `sharpenAlpha(contrast=2.5)`），之后用户只能换底色、换尺寸、拖拽缩放构图，**没有任何机会修正分割瑕疵**——尤其是服装边缘（误抠、白边、半透明过渡带）无法所见即所得地调整。

## 2. 目标

- 用户可在证件照页内直接调整分割边缘质量（全局参数）并手动修补局部瑕疵（画笔涂抹）
- 全程所见即所得：调整直接作用在当前底色 + 当前构图的预览上
- 不破坏现有链路：底色/尺寸选择、构图手势、JPEG 保存流程保持不变

## 3. 非目标（YAGNI）

- 不新增透明 PNG 导出（证件照固定纯色背景，仍只导 JPEG）
- 不重跑/切换分割模型，不引入交互式分割（SAM 类）
- 不改动编辑器的 `CutoutRecipe` / `MarkupPanel` 体系
- 不做多语言以外的任何 UI 重构

## 4. 核心思路：缓存 alpha + 两层调整

加载时跑一次融合抠图，缓存 `fusedAlpha: FloatArray`（原图尺寸，长边 ≤1024）。**注意**：当前 `MattingEngineImpl.fusionMatting()` 内部固定执行 `sharpenAlpha(2.5)`，本设计将该步骤从融合管线中移出、交给参数层执行——即缓存的是**未 sharpen 的融合 alpha**，参数层默认对比度 2.5 恰好复现现行为，保证不调整时输出与当前版本一致。所有调整都是后处理，**全程不重跑模型推理**：

```
fusedAlpha（加载时产出一次，缓存）
  → ① 参数层：边缘对比度 → 收缩/扩张（腐蚀/膨胀）→ 羽化
  → ② 描边层：用户笔刷描边按序重放（恢复=alpha→1，擦除=alpha→0）
  → adjustedAlpha
  → BackgroundComposer.apply(original, adjustedAlpha, 底色) → 预览底图
  → 现有构图手势 / 保存链路不变
```

参数层与描边层相互独立：

- 拖动滑块只重算参数层，描边不丢失，始终叠加在参数层结果之上
- 「重置参数」只还原三个滑块为默认值；「清除描边」只清空描边层
- 默认值 = 现行为（对比度 2.5、收缩/扩张 0、羽化 0）；因 `sharpenAlpha` 已从融合管线移入参数层（见上），不调整时输出与当前版本逐像素一致

## 5. 技术方案决策：描边层用矢量描边列表

| 方案 | 机制 | 结论 |
|------|------|------|
| **① 矢量描边列表（选定）** | 每条描边记录「模式 + 半径 + 软边 + 归一化坐标点列」，按序重放到独立图层再与参数层合并；撤销=移除尾条 | 内存极小；与参数层天然解耦；撤销/重做无损 |
| ② 像素快照 | 落笔直接改 alpha 副本，撤销靠快照栈 | 1024 级 FloatArray 快照每条约 4MB；且参数层一变快照全失效 |

描边数据结构（归一化坐标，与分辨率无关）：

```kotlin
enum class StrokeMode { RESTORE, ERASE }   // 恢复前景 / 擦除为背景

data class BrushStroke(
    val mode: StrokeMode,
    val radiusPx: Float,        // 参考坐标系下的笔刷半径
    val softness: Float,        // 0=硬边, 1=全软边
    val points: List<PointF>    // 归一化到原图宽高的坐标
)
```

`StrokeLayer` 职责：持有有序描边列表 + 重做栈，提供 `addStroke / undo / redo / clear / replayOnto(alpha)`。重放只写描边包围盒覆盖的局部区域，不全量扫描。

## 6. 参数层算法

全部落在 `domain/matting/MaskPostProcessor.kt`，纯 CPU、纯计算、可 JVM 单测：

| 参数 | 范围 | 算法 | 现状 |
|------|------|------|------|
| 边缘对比度 | 1.0–4.0，默认 2.5 | 复用现有 `sharpenAlpha(contrast)`（围绕 0.5 对比度拉伸） | 已有，接入滑块 |
| 收缩/扩张 | -20–+20 px，默认 0 | **新增** `erode/dilate`：分离式滑动窗口 min/max 滤波（O(n) 每方向） | 新增 |
| 羽化 | 0–20 px，默认 0 | 复用现有 `feather(radius)`（可分离盒滤波） | 已有，接入滑块 |

应用顺序固定为 **对比度 → 收缩/扩张 → 羽化**（先定边缘位置，再软化）。

## 7. UI 设计（方案 A：页内 Tab 面板）

底部面板改为 4 个 tab：**底色 / 尺寸 / 边缘 / 修补**。现有 `ColorSwatchRow`、`SizeChipRow` 原样保留为前两个 tab 的内容。

### 7.1 边缘 tab

- 三个滑块（羽化 / 收缩扩张 / 边缘对比度），标签与数值三语（[I18N] 红线）
- 交互：拖动过程不重建底图，松手后防抖 ~150ms 触发参数层重算 + 描边重放 + 重合成
- 「重置参数」按钮：三滑块回默认值

### 7.2 修补 tab

- 工具行：模式切换（恢复 / 擦除）、笔刷大小滑块、软边开关
- 操作行：撤销 / 重做 / 清除描边
- 进入该 tab 时，预览画框手势从「构图变换（拖拽/双指缩放）」切换为「涂抹」；切走 tab 即恢复构图手势。涂抹期间不允许双指缩放（避免描边坐标系歧义），需要构图调整请切回其他 tab
- 涂抹直接在所见即所得的合成预览上进行，手指处显示笔刷圈光标
- 描边坐标记录的是**原图归一化坐标**：预览画框 → 原图坐标的换算复用现有 cropRect 变换（`IDPhotoComposer`）

### 7.3 保存

顶栏 ✓ 保存逻辑不变：`composePreview()` 改为使用 adjustedAlpha 全分辨率重新合成，导出 JPEG（质量 95，`Pictures/PoLang/IDPHOTO_*`）不变。

## 8. ViewModel 状态扩展

`IDPhotoViewModel.State.Ready` 增加：

```kotlin
val edgeParams: EdgeParams,        // 对比度/收缩扩张/羽化，默认=现行为
val strokeState: StrokeState,      // 可undo/可redo/描边数（用于按钮可用态）
val activeTab: IdPhotoTab,         // BG_COLOR / SIZE / EDGE / REPAIR
```

新增动作：`setEdgeParams()`（防抖重建）、`beginStroke/updateStroke/endStroke()`、`undoStroke/redoStroke/clearStrokes()`、`resetEdgeParams()`、`selectTab()`。

预览底图缓存键从「底色」扩展为「底色 + edgeParams + 描边版本号」；底色切换仍只走 `BackgroundComposer` 重合成，不重算参数层。

## 9. 性能与边界

- 参数层三件套在 1024 长边 FloatArray 上单次约 10–30ms，松手 + 防抖重建足够流畅
- 描边重放只算描边包围盒局部区域；落笔过程中（`updateStroke`）以当前描边增量局部合成，保证跟手
- alpha 全程钳制 0–1，涂抹超出人像区无害
- 大图 OOM 防护沿用现有 `decodePreview()` 长边 1024 限制，不改动

## 10. 测试计划

延续 `app/src/test/.../domain/matting/` 的 JVM 单测模式：

- `MaskPostProcessor`：新增 erode/dilate 单测（边界、半径 0 恒等、收缩后前景面积单调不增）
- `StrokeLayer`：重放正确性（恢复/擦除写入）、undo/redo/clear、与参数层叠加顺序（描边永远在最后）
- `IDPhotoViewModel`：tab 切换状态机、参数默认值输出与现行为一致、防抖合并、保存使用 adjustedAlpha
- 合成路径：`BackgroundComposer` 已有测试，不改动

## 11. 红线核对

- **[PRIVACY]**：全部端侧 CPU 计算，无任何网络请求 ✅
- **[PERF]**：滑块松手重建 <100ms 目标；涂抹跟手走局部增量合成 ✅
- **[I18N]**：新增 UI 文案三语资源同步，禁止硬编码 ✅
- **[AGENT-FIRST]**：状态用枚举（`IdPhotoTab`、`StrokeMode`）、参数用具名 data class（`EdgeParams`）、依赖经 ViewModelFactory 显式注入 ✅
- **[DOC-SYNC]**：实施完成后同步 `app/AGENTS.md` 与相关技术文档
