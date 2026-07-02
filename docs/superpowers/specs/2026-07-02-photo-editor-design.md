# 相册图片编辑器重构设计（Photo Editor Redesign）

> **文档类型**：产品 + 交互设计规格  
> **对应模块**：`app/src/main/java/com/mamba/picme/features/gallery` / 新建 `features/editor`  
> **状态**：已评审待实现  
> **最后更新**：2026-07-02

---

## 1. 背景与目标

### 1.1 当前问题

相册大图页当前的「编辑」能力实质上只有美颜（磨皮 / 美白 / 瘦脸 / 大眼 / 美妆 / 美体），存在以下体验短板：

- **入口隐蔽**：仅通过顶部 ✨ 按钮或长按触发，用户难以发现。
- **能力单一**：缺少系统相册标配的裁剪、旋转、光色调节、滤镜、标记等工具。
- **交互粗糙**：参数面板直接堆叠在底部，没有分类 Tab；缺少撤销 / 重做、原图对比、单参数重置。
- **反馈不足**：处理中仅显示一个 `CircularProgressIndicator`，无法直观看到参数前后差异。
- **保存心智弱**：保存后原图仍在，但用户不清楚是生成副本还是覆盖，也无法再次打开继续调参。

### 1.2 设计目标

- 将相册大图页的编辑从「单一美颜工具」升级为**类小米 / iOS 系统相册的通用图片编辑器**。
- 所有处理本地完成，满足项目 `[PRIVACY]` 红线。
- 支持**非破坏性编辑**：保存副本、原图不动、可再次打开继续调参。
- 为未来的 **AI 语音 / 自然语言编辑**预留显式、可序列化的操作描述接口。

### 1.3 设计原则

| 原则 | 说明 |
|------|------|
| 分类清晰 | 底部使用「构图 / 调节 / 美颜 / 滤镜 / 标记」五大 Tab，降低学习成本。 |
| 即时反馈 | 参数滑条 200ms debounce 实时回显；滑动中显示低清预览，松手后高清确认。 |
| 可控撤销 | 支持多步撤销 / 重做，支持长按「对比」按钮查看原图。 |
| 显式状态 | 所有编辑操作收敛到 `EditRecipe`，类型系统即文档，便于 AI 后续消费。 |

---

## 2. 信息架构与导航

```
相册大图页 (MediaPager)
   ├─ 顶部工具栏 ✨ 编辑按钮
   ├─ 长按照片（保留现有触感反馈）
   └─ 进入 PhotoEditorScreen （全屏新页面）
        ├─ 顶部 AppBar：取消 | 标题 | 撤销 | 重做 | 对比 | 完成
        ├─ 中央：编辑预览区（支持双指缩放 / 平移查看细节）
        ├─ 底部：分类 Tab 栏
        │    ├─ 构图
        │    ├─ 调节
        │    ├─ 美颜
        │    ├─ 滤镜
        │    └─ 标记
        └─ 底部抽屉面板：当前分类的具体控件
```

### 2.1 返回与保存

- **取消**：返回相册；若存在未保存修改，弹出二次确认 Dialog。
- **完成**：直接保存为副本并退出；保存过程中 TopBar 显示进度指示。
- **保存后**：自动回到 `MediaPager`，并定位到新生成的副本。

---

## 3. 页面布局与交互细节

### 3.1 顶部操作栏

| 元素 | 交互说明 |
|------|----------|
| 取消 | 返回相册；有未保存修改时二次确认。 |
| 标题 | 静态显示「编辑」。 |
| 撤销 / 重做 | 维护 `HistoryStack<EditRecipe>`；无可用步骤时置灰。 |
| 对比 | 长按显示原图，松手恢复当前效果。 |
| 完成 | 保存副本并退出。 |

### 3.2 底部分类 Tab

采用小米相册风格：底部固定 5 个图标 + 文字 Tab，选中态高亮。

### 3.3 各分类面板

#### 3.3.1 构图

- **裁剪比例**：自由 / 1:1 / 4:3 / 3:4 / 16:9 / 9:16 / 原始。
- **旋转**：左 / 右 90°。
- **镜像**：水平翻转、垂直翻转。
- **矫正**：水平 / 垂直透视（可选，Phase 2）。
- **交互**：中央半透明裁剪框，拖动边角 / 边调整；可选支持双指旋转。

#### 3.3.2 调节

复用现有 `BeautySettings` 中的调色字段，统一滑条交互：

| 参数 | 范围 | 默认值 | 说明 |
|------|------|--------|------|
| 亮度 | -100 ~ +100 | 0 | |
| 曝光 | -100 ~ +100 | 0 | |
| 对比度 | 0 ~ 200 | 50 | 对应 `BeautySettings.contrast`。 |
| 饱和度 | 0 ~ 200 | 100 | 对应 `BeautySettings.saturation`。 |
| 色温 | 2000K ~ 8000K | 5000K | 对应 `BeautySettings.temperature`。 |
| 色调 | -100 ~ +100 | 0 | 对应 `BeautySettings.tint`。 |
| 暗角 | 0 ~ 100 | 0 | Phase 2 实现，预留字段。 |

每个滑条右侧提供「重置」小按钮，点击后该参数恢复默认值。

#### 3.3.3 美颜

直接复用现有 `BeautyPanel` 的三级 Tab（面部精修 / 美妆调整 / 美体管理），搬入编辑器，避免重复造轮子。

#### 3.3.4 滤镜

- **色调滤镜**：`FilterType` 预设（LEICA_CLASSIC、LEICA_VIBRANT、LEICA_BW、FILM_GOLD、FILM_FUJI、VINTAGE、COOL、WARM）。
- **风格特效**：`StyleFilter`（TOON、SKETCH、POSTERIZE、EMBOSS、CROSSHATCH）。
- **展示**：2 行横向缩略图列表，选中即应用到主预览。

#### 3.3.5 标记

整合现有 `ImageEditScreen` 的涂鸦 / 马赛克能力，并新增：

- 画笔颜色与粗细选择。
- 马赛克样式（像素 / 高斯，复用现有 shader）。
- Phase 2 增加文字、贴纸。

---

## 4. 数据模型

所有编辑操作收敛到一个显式、可序列化的 `EditRecipe`。

```kotlin
data class EditRecipe(
    val sourceUri: String,                       // 原图 URI
    val crop: CropRecipe = CropRecipe(),
    val adjustments: AdjustmentRecipe = AdjustmentRecipe(),
    val beauty: BeautySettings = BeautySettings(enabled = true),
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val markup: List<MarkupAction> = emptyList(),
    val version: Int = 1
)

data class CropRecipe(
    val rotation: Int = 0,                       // 0 / 90 / 180 / 270
    val flippedH: Boolean = false,
    val flippedV: Boolean = false,
    val straightenAngle: Float = 0f,             // -45° ~ +45°
    val cropRect: RectF? = null,                 // 归一化到 0-1，null 表示不裁剪
    val aspectRatio: AspectRatio = AspectRatio.FREE
)

data class AdjustmentRecipe(
    val brightness: Float = 0f,                  // -100..100
    val exposure: Float = 0f,                    // -100..100
    val contrast: Float = 50f,                   // 0..200
    val saturation: Float = 100f,                // 0..200
    val temperature: Float = 5000f,              // 2000..8000
    val tint: Float = 0f,                        // -100..100
    val vignette: Float = 0f                     // 0..100，Phase 2
)
```

### 4.1 撤销 / 重做

```kotlin
class EditHistory(private val maxSize: Int = 30) {
    private val stack = mutableListOf<EditRecipe>()
    private var index = -1

    fun push(recipe: EditRecipe) {
        if (index < stack.lastIndex) {
            stack.subList(index + 1, stack.size).clear()
        }
        stack.add(recipe)
        if (stack.size > maxSize) stack.removeAt(0)
        index = stack.lastIndex
    }

    fun undo(): EditRecipe? = if (canUndo()) stack[--index] else null
    fun redo(): EditRecipe? = if (canRedo()) stack[++index] else null

    fun canUndo(): Boolean = index > 0
    fun canRedo(): Boolean = index < stack.lastIndex
}
```

---

## 5. 处理管线

```
加载原图
   ↓
生成预览尺寸 Bitmap（最长边 ≤ 2048，避免 OOM）
   ↓
应用 CropRecipe（旋转 / 翻转 / 裁剪）→ croppedPreview
   ↓
GPU 处理（PhotoProcessor）
   - 光色调节
   - 美颜
   - 色调滤镜 ColorMatrix
   - 风格特效 Shader
   ↓
叠加 Markup（Canvas 绘制涂鸦 / 马赛克 / 文字）
   ↓
显示预览
```

### 5.1 导出流程

保存时重新走**全分辨率管线**：

1. 重新解码原图为全尺寸 Bitmap。
2. 应用 `CropRecipe`。
3. 应用 GPU 调色 / 美颜 / 滤镜。
4. 叠加 Markup（按原图比例缩放坐标）。
5. 压缩为 JPEG（质量 95）写入 MediaStore。

禁止直接保存预览 Bitmap，避免画质损失。

---

## 6. 保存与非破坏性存储

### 6.1 保存策略

1. 生成 `EDITED_${timestamp}.jpg` 副本，写入 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`。
2. 将 `EditRecipe`（含 `sourceUri`）序列化为 JSON，存入 App 本地数据库 `photo_edit_recipes` 表，主键为副本 URI。
3. 保存完成后刷新媒体库，并通知 `MediaPager` 定位到副本。

### 6.2 再次编辑

- 用户打开已编辑副本时，从 `photo_edit_recipes` 读取 `EditRecipe`。
- 恢复所有参数、分类 Tab 状态、历史栈初始状态。
- 若原图 `sourceUri` 仍可访问，以原图为基准重算；否则以副本为基准降级为可继续编辑。

### 6.3 数据清理

- 副本被删除时，同步清理 `photo_edit_recipes` 中对应记录。
- 应用启动时执行一次性清理：删除已不存在的副本对应的 Recipe 记录。

---

## 7. 错误与边界处理

| 场景 | 处理策略 |
|------|----------|
| 原图加载失败 | 顶部 Snackbar：「图片加载失败」，返回相册。 |
| 大图 OOM | 自动降低预览采样率，提示「已降低预览清晰度」。 |
| 人脸检测失败 | 非阻塞；美颜 Tab 置灰或显示「未检测到人脸」。 |
| GPU 处理失败 | 尝试软件 Canvas 降级；仍失败则弹错误并保持原图。 |
| 保存失败 | 保留在当前页，提示重试。 |
| 旋转屏幕 | 状态通过 `ViewModel` 保留，预览区自动适配。 |

---

## 8. 为 AI 编辑（方案 C）预留的接口

- `EditRecipe` 本身就是 AI 可理解的显式操作描述。
- 新增 `RecipeCommandParser`：
  - 输入：自然语言或 Agent 指令，例如「裁成 1:1、亮度加 20、肤色暖一点」。
  - 输出：`EditRecipeDiff`（一组 Recipe 字段变更）。
- 在 `PhotoEditorScreen` 顶部或底部预留语音 / Agent 输入按钮：
  - 点击后调用 `VoiceCommandCoordinator`。
  - 解析结果通过 `applyDiff(diff: EditRecipeDiff)` 应用到当前 Recipe。
- 复杂语义（如「去掉路人」）作为 Phase 3 的 AI 消除能力，暂不实现，但预留 `markup` 中的 `AiGeneratedPatch` 类型。

---

## 9. 实施阶段

### Phase 1：核心编辑器（4 周）

- 新建 `PhotoEditorScreen` 与 `PhotoEditorViewModel`。
- 实现 `EditRecipe`、`EditHistory`、撤销 / 重做。
- 完成顶部栏、底部分类 Tab、构图面板、调节面板。
- 接入现有 `BeautyPanel` 作为美颜面板。
- 实现保存副本 + `photo_edit_recipes` 数据库存储。

### Phase 2：体验补齐（2 周）

- 滤镜 / 风格特效面板与缩略图。
- 标记面板：整合涂鸦 / 马赛克，增加文字。
- 长按对比、单参数重置、裁剪手势优化。

### Phase 3：AI 编辑入口（2 周）

- 接入 `VoiceCommandCoordinator`。
- 实现 `RecipeCommandParser` 的基础指令映射。
- 在编辑器内提供语音 / 文字 Agent 输入入口。

---

## 10. 验收标准

- [ ] 相册大图页点击编辑后进入全屏 `PhotoEditorScreen`。
- [ ] 底部 5 个分类 Tab 可正常切换，控件与分类对应。
- [ ] 裁剪、旋转、镜像、光色调节参数可实时预览。
- [ ] 撤销 / 重做至少支持 30 步。
- [ ] 长按「对比」按钮可查看原图，松手恢复。
- [ ] 保存后生成副本，原图保持不变。
- [ ] 重新打开副本可恢复所有编辑参数。
- [ ] 所有新增文案已提取到 `strings.xml`（满足 `[I18N]`）。
- [ ] 所有处理在本地完成，不上传图片（满足 `[PRIVACY]`）。
- [ ] 参数调节响应 < 200ms（满足 `[PERF]` 体验目标）。
