# 图片编辑模块技术实现规范 (Image Editor)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

> **版本**: 2.0  
> **状态**: 生效中  
> **最后更新**: 2026-07-02  
> **维护者**: RD Agent

**模块定位**: 从 Gallery 进入的独立非破坏性图片编辑器，基于配方（Recipe）模型实现裁剪、调节、美颜、滤镜（Phase 2）、标记（Phase 2）五大类编辑。

**主要维护者**: [RD] 全栈工程师

**阅读对象**: RD、QA、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[LOCAL] 纯本地处理**: 所有编辑操作必须在设备本地完成，严禁上传云端
- **[PERF] 实时预览**: 参数调节后 200ms debounce 触发预览，处理在后台线程执行
- **[I18N] 多语言文案**: 所有用户可见标签、内容描述、错误提示必须提取到 strings.xml
- **[FEEDBACK] 操作反馈**: 撤销/重做、保存等关键操作提供明确的视觉反馈；长按预览区可对比原图
- **[MEMORY] 内存优化**: 预览图按最长边 2048px 降采样，源 Bitmap 在 ViewModel 销毁时回收
- **[NON-DESTRUCTIVE] 非破坏性编辑**: 原图不动，保存生成新副本，并将完整配方持久化到 Room

## 2. 技术实现规范 (Technical Implementation)

### 2.1 配方数据模型 (EditRecipe)

**核心文件**: `features/editor/EditRecipe.kt`

**结构**:
- `crop: CropRecipe` — 裁剪比例 `AspectRatio`、旋转角度、水平翻转
- `adjustments: AdjustmentRecipe` — 亮度、曝光、对比度、饱和度、色温、色调
- `beauty: BeautySettings` — 复用相机模块美颜参数
- `colorFilter: FilterType` / `styleFilter: StyleFilter` — 色调/风格滤镜（Phase 2）
- `markup: List<MarkupAction>` — 涂鸦/马赛克/文字路径（Phase 2）
- `version: Int` — 配方版本，便于后续迁移

**代码示例**:
```kotlin
val recipe = EditRecipe(
    sourceUri = asset.uri,
    crop = CropRecipe(aspectRatio = AspectRatio.SQUARE, rotation = 90),
    adjustments = AdjustmentRecipe(brightness = 10f, contrast = 60f),
    beauty = BeautySettings(enabled = true, smooth = 30)
)
```

### 2.2 编辑历史与撤销 (EditHistory)

**核心文件**: `features/editor/EditHistory.kt`

**技术规范**:
- 使用 `MutableList<EditRecipe>` 作为状态栈，配合 `index` 指向当前配方
- `push()` 丢弃当前索引之后的重做历史，追加新配方
- `undo()` / `redo()` 仅移动索引，不修改历史列表
- 单元测试覆盖空历史、undo/redo 边界、push 后丢弃重做分支

### 2.3 配方应用器 (RecipeApplier)

**核心文件**: `features/editor/RecipeApplier.kt`

**处理顺序**:
1. `applyCrop(base, crop)` — 先旋转/翻转，再按 `AspectRatio` 自动居中裁剪
2. `applyGpuEffects(cropped, recipe, faceData)` — 调用 `PhotoProcessor.process()` 应用美颜与滤镜
3. `applyMarkup(processed, markup)` — 当前为占位，Phase 2 叠加涂鸦/马赛克路径

**关键约束**:
- 裁剪矩形使用原图归一化坐标；`AspectRatio.FREE` 时不裁剪
- 旋转后通过 `Bitmap.createBitmap` 生成新 Bitmap
- 人脸检测缓存由 `PhotoEditorViewModel` 提供，避免重复检测

### 2.4 ViewModel 与状态 (PhotoEditorViewModel)

**核心文件**: `features/editor/PhotoEditorViewModel.kt`

**状态定义**:
```kotlin
sealed class State {
    object Loading : State()
    data class Ready(
        val originalBitmap: Bitmap,
        val previewBitmap: Bitmap,
        val recipe: EditRecipe,
        val selectedTab: EditorTab = EditorTab.CROP,
        val isProcessing: Boolean = false,
        val isSaving: Boolean = false,
        val error: String? = null
    ) : State()
    data class Error(val message: String) : State()
}
```

**技术规范**:
- `load(context, sourceUri, recipeUri)` 在 `Dispatchers.IO` 解码预览图，并尝试从 `recipeUri` 恢复配方
- 预览通过 `_recipeChanges.debounce(200)` 自动触发，避免滑动过程中频繁重算
- 保存时使用完整分辨率原图，按同一配方处理，输出 JPEG（质量 95）到 `Pictures/PicMe`
- 保存成功后调用 `PhotoEditRecipeRepository.save(outputUri, sourceUri, recipe)` 持久化配方

### 2.5 UI 结构

**核心文件**:
- `features/editor/PhotoEditorScreen.kt` — 编辑器主屏幕
- `features/editor/components/EditorTopBar.kt` — 顶部导航、撤销/重做、完成
- `features/editor/components/EditorBottomBar.kt` — 底部 tab（Crop / Adjust / Beauty / Filter / Markup）
- `features/editor/components/CropPanel.kt` — 裁剪比例与旋转/翻转
- `features/editor/components/AdjustPanel.kt` — 光色参数滑块
- `features/editor/components/MarkupPanel.kt` — Phase 2 标记工具占位

**交互规范**:
- 顶部标题使用 `R.string.edit`
- 长按预览区切换显示原图，松开后恢复编辑后效果
- 底部 tab 文案全部来自 `strings.xml`，支持英文/简体中文/繁体中文

### 2.6 配方持久化 (PhotoEditRecipeRepository)

**核心文件**: `data/repository/PhotoEditRecipeRepository.kt`

**技术规范**:
- Room 表 `photo_edit_recipes` 以 `outputUri` 为主键，保存 `sourceUri`、`recipeJson`、`updatedAt`
- 使用 Moshi 将 `EditRecipe` 序列化为 JSON
- `load(outputUri)` 反序列化配方，用于「再次编辑」已保存的副本

## 3. Agent 执行规约 (Execution Rules)

- **图片加载**: 必须在 `Dispatchers.IO` 线程加载/解码大图，严禁在 UI 线程解码
- **Bitmap 回收**: `sourceBitmap` 在 ViewModel `onCleared()` 时回收；中间 Bitmap 不手动 recycle，依赖垃圾回收
- **线程管理**: 预览处理在 `Dispatchers.Default`，保存写入在 `Dispatchers.IO`
- **I18N**: 所有用户可见文案必须提取到 strings.xml，禁止硬编码
- **权限检查**: 保存前无需显式检查存储权限（Android 10+ Scoped Storage）
- **日志规范**: 关键操作（加载、保存、撤销、预览失败）需记录 `PicMe:Editor` 日志
- **配方一致性**: 预览与保存必须基于同一份 `EditRecipe`，禁止在保存路径中单独构造参数

## 4. 常见陷阱检查清单 (Checklist)

- [ ] 是否在 UI 线程中加载/解码了大图？(必须使用 Dispatchers.IO)
- [ ] `sourceBitmap` 是否在 ViewModel 销毁时 recycle？(避免 OOM)
- [ ] 预览 debounce 是否合理？(200ms，避免滑动时频繁渲染)
- [ ] 撤销/重做后是否触发了预览更新？(`_recipeChanges.value = recipe`)
- [ ] 保存操作是否在后台线程执行？(避免 ANR)
- [ ] 保存失败是否有明确的错误提示？(State.Error + 日志)
- [ ] 所有新 UI 文案是否已提取到 strings.xml？(I18N)
- [ ] `AspectRatio` 显示文案是否通过 `stringResource(labelRes)` 获取？
- [ ] 编辑后的图片是否正确通知了相册刷新？(`mediaRepository.refreshMediaLibrary()`)
- [ ] 是否处理了图片加载失败的异常？(try-catch + State.Error)

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ 纯本地编辑 → 无网络请求，所有处理与保存都在设备端完成
- ✅ 非破坏性编辑 → 原图不变，保存为新副本并持久化配方
- ✅ 撤销/重做 → `EditHistory` 支持完整状态回退与重做
- ✅ 三语本地化 → 编辑器标签/错误提示全部提取到 strings.xml
- ✅ 相册刷新 → 保存成功后刷新媒体库，新副本立即可见

**技术决策记录**:
- 使用 `EditRecipe` 配方模型统一描述所有编辑操作：便于撤销/重做、持久化、再次编辑与后续 AI 自然语言编辑
- 独立 `PhotoEditorScreen` 替代 MediaPager 就地编辑：减少手势冲突，支持更复杂的底部面板
- 预览与保存共用 `RecipeApplier`：保证「所见即所得」，避免预览与最终输出不一致
- 配方持久化到 Room 而非 EXIF：JSON 更灵活，可跨版本迁移
- 标记/滤镜功能 Phase 2 实现：当前保留 UI 占位与数据字段，避免一次性改动过大
