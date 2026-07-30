# 人物页 UI/UX 重设计

> **日期**：2026-07-30  
> **状态**：已确认，待实施  
> **范围**：`app/src/main/java/com/mamba/picme/features/person/`

## 1. 背景与问题

当前「人物」页使用 3 列自适应小网格（`LazyVerticalGrid`，最小 96dp），每个单元格底部叠加黑色半透明条显示 `名字 · 张数`。截图显示：

- 格子密集，产生视觉压迫感；
- 名字与张数被压缩换行，不易分辨；
- 封面图过小，人脸细节不清；
- 页面仅支持点击封面弹出 `PersonRenameDialog` 改名/标关系，缺少「选封面」能力。

本设计将人物页改为**垂直列表**，并明确承载三项核心操作：
1. 编辑名字（行内快速改名）；
2. 编辑人物信息（关系 / 「这是我」标记）；
3. 选择封面。

## 2. 设计目标

- **降低信息密度**：从 3 列网格改为单栏列表，单个人物信息分层展示。
- **提升可读性**：名字、张数、关系标签三层信息互不叠加，一眼可辨。
- **明确交互入口**：封面图 / 名字 / 关系标签各司其职，避免误触。
- **功能闭环**：人物页内即可完成改名、改关系、选封面，无需跳转新页面。

## 3. 布局与视觉

### 3.1 页面框架

- `Scaffold` + `TopAppBar`，标题 `R.string.people_title`。
- 主体为 `LazyColumn`：
  - `contentPadding`：左右 16dp、上下 16dp；
  - `verticalArrangement`：项间距 12dp。

### 3.2 列表项 `PersonListItem`

- 容器：`Row`，固定高度 96dp。
- 左侧封面图：
  - 尺寸 76dp × 76dp；
  - `RoundedCornerShape(12.dp)`；
  - `ContentScale.Crop`；
  - 人脸感知纵向对齐：`faceAwareVerticalAlignment(cover.faceFocusY)`；
  - 可点击，点击区域覆盖整个封面图。
- 右侧信息区：`Column` 占满剩余宽度，垂直居中，三层信息：
  1. **名字**：18sp、`FontWeight.Bold`、颜色 `colorScheme.onSurface`；未命名时使用默认名但颜色为 `onSurfaceVariant`，提示用户可编辑。
  2. **张数**：14sp、颜色 `colorScheme.onSurfaceVariant`，文案示例：「164 张照片」。
  3. **关系标签**：`SuggestionChip` 或 `AssistChip`，高度 24dp、字号 12sp；仅当人物已设置指向「我」的关系时显示；「这是我」标签使用 `primaryContainer`/`onPrimaryContainer` 高亮。
- 右侧操作入口：信息区最右侧放置 `IconButton`（`Icons.Default.MoreVert`），点击弹出「人物信息 Sheet」。

### 3.3 行内编辑态

- 点击名字区域后，该区域切换为 `OutlinedTextField`：
  - `singleLine = true`；
  - 自动请求焦点；
  - 右侧显示 ✓（保存）/ ✕（取消）图标按钮；
  - 回车键触发保存；
  - 失焦或点击取消恢复只读态。
- 编辑态下，其他列表项保持正常状态，禁止同时多行编辑。
- 保存失败时通过 `Scaffold` 的 `SnackbarHost` 提示。

## 4. 交互流程

| 操作 | 触发区域 | 反馈 |
|------|----------|------|
| 改名 | 点击名字区域 | 该行进入编辑态，显示输入框 + 保存/取消 |
| 选封面 | 点击左侧封面图 | 从底部弹出「封面选择 Sheet」 |
| 编辑关系 / 「这是我」 | 点击关系标签或右侧 ⋮ 更多 | 从底部弹出「人物信息 Sheet」 |
| 其他区域 | 列表空白 / 行内非交互区 | 无操作 |

### 4.1 封面选择 Sheet

- 使用 `ModalBottomSheet`。
- 标题：「选择封面」。
- 内容：`LazyVerticalGrid`，3 列，展示该人物所有关联媒体（`PersonDao.getMediaByPerson(personId)`，按 `captureDate` 倒序）。
- 每个候选图：圆角 8dp、宽高比 1:1、`ContentScale.Crop`。
- 点击候选图即调用 `PersonRepository.updateCover(personId, mediaId)`，关闭 Sheet 并刷新列表。
- 取消方式：下滑关闭或点击 Sheet 外部。

### 4.2 人物信息 Sheet

- 使用 `ModalBottomSheet`。
- 标题：「人物信息」。
- 内容复用现有 `PersonRelationPicker`：
  - 关系快捷 chips（家庭 / 社会）；
  - 自定义称呼输入框；
  - 「这是我」开关。
- 底部固定「保存」按钮，保存后关闭 Sheet 并刷新列表。

## 5. 数据层变更

### 5.1 ViewModel

`PersonViewModel` 新增：

```kotlin
private val _relations = MutableStateFlow<Map<Long, RelationDisplayItem>>(emptyMap())
val relations: StateFlow<Map<Long, RelationDisplayItem>> = _relations.asStateFlow()
```

`load()` 流程扩展为：
1. 获取全部人物；
2. 解析封面 uri / faceFocusY；
3. 在 IO 线程为每个人物查询指向「我」的关系（`PersonRepository.getRelationToSelf`），组装为 `Map<personId, RelationDisplayItem>`；
4. 过滤无封面的人物后刷新 UI。

新增方法：

```kotlin
fun updateCover(personId: Long, mediaId: Long)
fun updateName(personId: Long, name: String)
fun updatePersonInfo(personId: Long, relation: RelationPredicate?, customLabel: String, isSelf: Boolean)
```

### 5.2 Repository

`PersonRepository` 新增封面更新收口：

```kotlin
suspend fun updateCover(personId: Long, mediaId: Long) {
    personDao.updateCoverMedia(personId, mediaId)
}
```

命名、关系、「这是我」继续使用现有 `applyPersonEdit`。

### 5.3 DAO

- `PersonDao.updateCoverMedia(personId, coverMediaId)` 已存在，直接复用。
- `PersonDao.getMediaByPerson(personId)` 已存在，直接复用。

## 6. 组件拆分

| 组件 | 路径 | 职责 |
|------|------|------|
| `PersonScreen` | `features/person/PersonScreen.kt` | 页面框架、状态聚合、Sheet 调度 |
| `PersonListItem` | `features/person/components/PersonListItem.kt` | 列表项 UI + 行内编辑态 |
| `PersonCoverPickerSheet` | `features/person/components/PersonCoverPickerSheet.kt` | 底部封面选择 Sheet |
| `PersonInfoSheet` | `features/person/components/PersonInfoSheet.kt` | 底部人物信息/关系编辑 Sheet |

> `PersonRenameDialog` 保留在 `features/common/`，继续供 Gallery 页使用；人物页自身不再调用它。

## 7. 边界与异常

- **空状态**：`persons` 为空时显示占位文案（复用或新增 `R.string.people_empty`）。
- **未命名人物**：默认名使用现有 `R.string.people_default_name`，颜色降级显示以提示编辑。
- **无关系**：不显示关系标签，避免空白 Chip。
- **封面媒体删除**：保留 `reconcileAndLoad` 兜底，自动修复悬空封面。
- **行内编辑冲突**：通过 ViewModel 维护 `editingPersonId: StateFlow<Long?>`，确保同时仅一行处于编辑态。
- **保存失败**：所有 Repository 调用 try/catch，错误通过 `Snackbar` 或日志透出。

## 8. 红线与规范

- **[PRIVACY]**：封面选择只读取本地 `media_assets` / `face_embeddings`，不向远程上传任何图片/人脸数据。
- **[I18N]**：所有新增文案必须进入 `values/strings.xml`、`values-zh/strings.xml`、`values-zh-rTW/strings.xml`、`values-zh-rCN/strings.xml`。
- **[PERF]**：列表使用 `LazyColumn` + `key`；封面图走 Coil 异步加载；关系查询在 IO 线程批量完成。
- **[AGENT-FIRST]**：新组件构造函数显式注入依赖；状态使用枚举或显式 sealed 类；避免布尔标志组合。

## 9. 验收标准

- [ ] 人物页显示为垂直列表，每行高度 96dp，封面 76dp，信息三层分行。
- [ ] 名字点击后可在行内直接修改，回车/✓ 保存，✕/失焦取消。
- [ ] 点击封面弹出底部 Sheet，展示该人物所有照片，点选后更新封面。
- [ ] 点击关系标签或 ⋮ 弹出底部 Sheet，可修改关系、自定义称呼和「这是我」。
- [ ] 已设置的关系标签在列表项中正确显示（含自定义称呼）。
- [ ] 编译通过，`./gradlew :app:assembleDebug` 无新增错误。
- [ ] 三语文案同步更新。
