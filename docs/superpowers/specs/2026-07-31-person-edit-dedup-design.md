# 人物编辑入口去重：相册分组弹框 → 统一人物信息编辑页

- 日期：2026-07-31
- 状态：已通过设计评审，待写实施计划
- 模块：`:app`（`features/gallery`、`features/person`、`features/common`、`domain/person`）

## 1. 背景与目标

相册「按人物分组」模式下，点击分组名弹出的「分组编辑弹框」（`PersonRenameDialog`）与人物页的「人物信息编辑页」（`PersonInfoScreen`）功能高度重复：改名、关系（`PersonRelationPicker`）、自定义称呼、"这是我"标记，且两者最终都走同一个落库方法 `PersonRepository.applyPersonEdit(...)`。弹框是编辑页的**严格子集**——编辑页还多出「改封面」「浏览该人照片」。

目标：**删除弹框，相册分组名点击改为打开同一个全屏 `PersonInfoScreen`**，消除重复 UI 与重复数据加载逻辑。

非目标：不改造导航图（不把编辑页改成独立 nav 路由）；不重构人物页 `PersonScreen` 的整体结构。

## 2. 现状（两处入口）

| 维度 | 分组弹框 `PersonRenameDialog` | 编辑页 `PersonInfoScreen` |
|---|---|---|
| 入口 | `GalleryScreen` PERSON 模式 `onGroupTitleClick`（GalleryScreen.kt:824） | `PersonScreen` 的 MoreVert `onInfoClick`（PersonScreen.kt:209） |
| 改名 / 关系 / 自定义称呼 / "我" | ✅ | ✅ |
| 改封面 / 看照片 | ❌ | ✅ |
| 落库 | `applyPersonEdit` | `applyPersonEdit` + `renamePerson` / `updateCover` |
| 形态 | `AlertDialog` | 全屏 `Scaffold`（overlay） |

`PersonRenameDialog` 现仅 GalleryScreen 引用（人物页已改用 inline 改名 + `PersonInfoScreen`）。`MemoryFactsScreen.kt:183` 另有一处提及它的过期注释。

## 3. 设计

### 3.1 架构决策

把「加载某人物编辑所需数据」下沉到 `PersonRepository`，作为相册与人物页的**唯一加载入口**；`PersonInfoScreen` 作为**唯一编辑 UI**。落库已收口在 `applyPersonEdit`，无需改动。

### 3.2 新增 / 改动

**A. `PersonRepository`（domain/person/PersonRepository.kt）**
- 新增数据类与加载方法：
  ```kotlin
  data class PersonEditSnapshot(
      val person: PersonEntity,
      val relation: RelationDisplayItem?,
      val cover: PersonCover,
      val photos: List<MediaEntity>
  )
  suspend fun loadPersonEditSnapshot(personId: Long): PersonEditSnapshot?
  ```
  内部聚合：
  1. `personDao().getPersonByPersonId(personId)`（**新增**该 DAO 单行查询 `@Query("SELECT * FROM persons WHERE personId = :id LIMIT 1")`，避免为单人加载全表）；
  2. `getRelationToSelf(personId)` → 经共享映射转 `RelationDisplayItem?`；
  3. 封面：取 `person.coverMediaId`，经 `db.mediaDao().getMediaByIds(...)` 拿 uri/faceFocusY，用 `PersonCoverResolver.resolve(...)` 得 `PersonCover`（空封面返回 `PersonCover(null, null)`）；
  4. 照片：`personDao().getMediaByPersonOrderedForCover(personId).distinctBy { it.id }`（与现 `PersonViewModel.loadPhotosByPerson` 一致）。
- **下沉** `relationToDisplay`（现 PersonViewModel.kt:247-260）为共享映射：`RelationDisplayItem` 工厂或 repo 内私有函数，含 customLabel 空串归一、未知 predicate 归 null。`PersonViewModel.load()` 改为调用该共享映射（去重）。

**B. `GalleryScreen`（features/gallery/GalleryScreen.kt）**
- 删除：`renamingPersonGroup/Name/Relation/CustomLabel/IsSelf` 五个状态（313-318）、`onGroupTitleClick` 内的弹框回显协程（827-848）、`PersonRenameDialog` 渲染块（974-1000）、`PersonRenameDialog` import（74）。
- 新增：
  - `var infoPersonId by remember { mutableStateOf<Long?>(null) }`、`var infoSnapshot by remember { mutableStateOf<PersonEditSnapshot?>(null) }`；
  - `onGroupTitleClick`（PERSON 模式）→ `infoPersonId = group.titleValue.toLongOrNull()`；
  - `LaunchedEffect(infoPersonId)`：置空 snapshot；id 非空时在 `rememberCoroutineScope` 协程内 `runCatching { app.container.personRepository.loadPersonEditSnapshot(id) }`，成功赋给 `infoSnapshot`，失败 `Logger.e`；
  - overlay：`infoSnapshot?.let { snap -> PersonInfoScreen(person=snap.person, relation=snap.relation, cover=snap.cover, photos=snap.photos, onSave=…, onNavigateBack={ infoPersonId=null }, onUpdateCover=…, onUpdateName=…) }`（复刻 PersonScreen.kt:216-233 套法）；
  - 回调落库（均在协程内 `runCatching`，成功后 `refreshPersonNameMap()`）：
    - `onSave` → `applyPersonEdit`；`PersonInfoScreen.doSave` 在 `onSave` 后会自行调 `onNavigateBack` 关闭 overlay；
    - `onUpdateName` → `renamePerson`（编辑页仅在名字非空时回调，GalleryScreen 侧同样 trim 后判空）；
    - `onUpdateCover` → `updateCover`（不关 overlay，封面在相册分组列表不展示，刷新 map 为无害兜底）；
    - `onNavigateBack` → `infoPersonId = null`。
- **抽取** `refreshPersonNameMap()`：把现 320-333「PERSON 模式下从 `db.personDao().getAllPersons()` 重载 `personNameMap`」的逻辑提成函数，供进入分组模式与编辑保存后共用。

**C. 删除 `features/common/PersonRenameDialog.kt`（114 行，整文件）**

**D. 文档/注释清理**
- `MemoryFactsScreen.kt:183` 注释改为反映现状（人物关系编辑统一在 `PersonInfoScreen`）。
- 若 `AGENTS.md` / `docs/` 有 `PersonRenameDialog` 引用同步更新（实施时 grep 确认）。

### 3.3 数据流

```
相册 PERSON 分组 → 点分组名 → infoPersonId
  → LaunchedEffect: repo.loadPersonEditSnapshot(id) → infoSnapshot
  → PersonInfoScreen(snapshot)
保存 → repo.applyPersonEdit/renamePerson/updateCover → refreshPersonNameMap() → overlay 关闭
```

### 3.4 边界与错误处理

- 分组 `titleValue` 解析为 null（非数字）→ 不开 overlay。
- 快照加载失败 → 不开 overlay、`Logger.e`、名字 map 不变（与现有弹框回显 try/catch 一致）。
- 保存失败 → `Logger.e`，不刷新 map、不关 overlay（让用户可重试）。
- `PersonInfoScreen` 的 `onUpdateName` 仅在名字非空时调 `renamePerson`（编辑页内部已有 `trimmedName.isNotBlank()` 判断，GalleryScreen 侧同样 trim 后判空）。
- 改封面 / 改关系对相册分组列表的显示无影响（列表标题仅由 `personNameMap` 驱动）；改名后 `refreshPersonNameMap()` 即可让标题回显正确。

## 4. 测试

- **JVM 单测**：为下沉的 `relationToDisplay` 共享映射补纯函数单测（customLabel 空串→null、未知 predicate→null、正常 predicate→对应枚举）。
- `PersonCoverResolver` 已有单测，不受影响。
- `loadPersonEditSnapshot` 依赖 Room，按本项目 JVM 环境惯例（大量环境性预存失败）归为「编译 + 设备手动验证」，不强求 JVM 单测。
- **设备验证（`/ui-driver`）**：相册 → 切「人物」分组 → 点分组名 → 进 `PersonInfoScreen` → 改名/关系/自定义称呼/"我"/封面 → 保存 → 返回分组列表，名字标题正确回显；再次进入回显上次保存值。
- 真实质量门：编译通过 + 现有 JVM 单测不回退。

## 5. i18n 清理

- 删除 `PersonRenameDialog` 后，grep `person_edit_title` 等字符串；若仅弹框使用则在 `values/`、`values-zh-rCN/`、`values-zh-rTW/` 三套 `strings.xml` 同步删除。`PersonInfoScreen` 沿用现有字符串，不动。
- 用 `/i18n-validator` 校验三语同步、无新增硬编码。

## 6. 不在本次范围

- 不把 `PersonInfoScreen` 改为 nav 路由（用户已明确选「整体替换 overlay」而非「导航路由」）。
- 不重构 `PersonScreen` / `PersonViewModel` 的列表加载、排序、`showAll` 等逻辑（仅让其复用下沉后的 `relationToDisplay` 映射）。
- 不改聚类 / 关系图后端。
