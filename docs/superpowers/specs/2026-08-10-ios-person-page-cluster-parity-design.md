# iOS 人物页聚类模型全量对齐设计

- **日期**：2026-08-10
- **分支**：`feat/ios-tag-scan-core`
- **状态**：已确认（设计批准），待实现计划
- **目标**：iOS 人物页（列表 + 详情）与 Android 的 UI 和功能**全部一致**

---

## 1. 背景与动机

### 1.1 两种根本不同的模型

| 维度 | Android（参照） | iOS（当前） |
|---|---|---|
| 人物来源 | 人脸 embedding 聚类**自动发现** | **手动**「Add Person」创建 |
| 人物↔照片关联 | `face_embeddings.person_id` ∪ label-mention | `person_media_assignments` 手动指派 |
| `photoCount` | cluster ∪ label-mention 并集 | 手动指派计数 |
| 封面 | 自动选取 + **人脸感知裁切**（`faceFocusY`） | 手动选取 |
| 标题 | 动态 `People (可见/总数)` | 静态 `People` |
| 工具栏 | 筛选 / **重聚类** / **重打分** | 无 |
| 手动加人 | **不存在** | 存在（`AddPersonSheet`） |

### 1.2 关键事实：聚类数据已在 iOS 产出

本分支 `feat/ios-tag-scan-core` 的 TAG 扫描 3-Pass 管线已完成（Pass1 人脸+嵌入 / Pass2 k-NN 聚类 / Pass3 Florence-2 打标）。`TagDatabase` 已落地与 Android **同构**的 schema：

- `persons`（`person_id` 自增 / `name` / `cover_media_id` / `face_count` / `is_self` / `created_at` / `updated_at`）—— 与 Android `PersonEntity` 一致
- `face_embeddings`（`embedding_id` / `media_id` / `person_id` / `embedding` / `created_at`）—— 与 Android 一致
- `media_assets`（含 `faceFocusY` / `aestheticScore` / `labels`/`labelsEn`/`labelsZh` / `localIdentifier`）—— 与 Android `MediaEntity` 一致

但人物页 UI 当前读的是**另一套** `PersonStore`（`polang_person.sqlite`，手动模型），两者**完全断开**。

### 1.3 决策

用户选择**「迁移聚类模型·全量对齐」**：人物页改读 `TagDatabase` 聚类数据，构建完整 Android UI/功能。这是达成「UI + 功能全部一致」的唯一路径。

---

## 2. 可行性核查结论（实现前已验证）

| 能力 | iOS 现状 | 结论 |
|---|---|---|
| 重聚类 | `TagScanOrchestrator.runPass2Clustering()` | ✅ Stage 1 |
| 人脸感知封面裁切 | `ThumbnailView` 已支持 `faceFocusY` 垂直裁切 | ✅ Stage 1（透传即可） |
| 聚类数据 | `persons` + `face_embeddings` 由 Pass2 产出 | ✅ Stage 1 |
| 重打分（美学） | NIMA 模型仅注册，无写入 `aestheticScore` 的打分器 | ❌ Stage 2（需接打分器） |
| 封面点击→相册按人物筛选 | 相册按 `faceId` 分组，非聚类 `personId` | ❌ Stage 2（需 faceId↔personId 桥接） |
| `TagDatabase` 人物级查询 | 仅 `insertEmbeddings` / `getUnassignedEmbeddings` | ⚠️ 数据访问层需新建 |

---

## 3. 架构

### 3.1 数据源切换

- `TagDatabase` 成为人物域**唯一数据源**。
- 新增 `person_relations` 表到 `TagDatabase`（当前无），使关系共享聚类 `person_id` 键空间。
- `PersonStore`（`polang_person.sqlite`：手动 `persons` / `person_relations` / `person_media_assignments`）**整体删除**。手动模型仅有开发测试数据，**直接抛弃**（决策4）。

### 3.2 新增 `PersonRepository`（iOS，覆盖 `TagDatabase`）

对标 Android `PersonRepository` + `PersonDao` + `PersonCoverResolver`。纯 Swift，@MainActor 之上的 IO 层。核心方法：

```
reconcileAndLoad() -> PersonListSnapshot
  // 1. reconcilePersons(): 单事务修复——删孤儿 embedding、删孤儿 person、重算 face_count、修悬空 cover_media_id
  // 2. runPass2Clustering()（容错：失败不阻塞 load）
  // 3. load()
load() -> PersonListSnapshot
  // 全量重建：persons + covers + relationsToSelf + photoCounts + 过滤 + 排序
allPersonsSorted(showAll: Bool) -> [PersonDisplayItem]
resolveCovers(personIds) -> [Int64: PersonCover]   // coverMediaId -> uri + faceFocusY
filterCoverable(persons, covers) -> [Person]
photoCount(personId, name?) -> Int                  // 未命名=distinct media；命名=cluster ∪ label-mention
relationToSelf(personId) -> RelationDisplayItem?
mediaByPersonOrderedForCover(personId) -> [MediaAsset]  // 单人脸优先，按拍摄时间倒序，去重
upsertRelationToSelf(personId, predicate, customLabel, isSelf)
renamePerson(personId, name)
updateCover(personId, mediaId)
setSelf(personId, isSelf)                           // 全局唯一 self
```

### 3.3 关系模型（对齐 Android）

- 关系始终为「**该人物 是 我的 (谓词)**」—— object 隐式为 `is_self=1` 的人物。
- `person_relations(subject_person_id, predicate, object_person_id=.selfId, source, custom_label, confidence)`，`UNIQUE(subject, predicate, object)`，幂等 upsert。
- `source` 统一记 `RelationSource.renameDialog`（iOS 暂无聊天声明通道）。
- 谓词单一事实来源 = `:shared` `RelationPredicate`（iOS 经 `PersonRelationSupport` 桥接，已存在）。

---

## 4. 列表页（`PersonView`）对齐规格

### 4.1 顶栏

- **标题**：`People (可见/总数)`（Android `people_title_with_count`，`%1$d/%2$d`）。
- 返回按钮：保持现有注入式 `onBack`（MainTabView page 3 用法）/ NavigationStack 系统返回（Settings 入口）。
- **移除**「Add Person」按钮与 `AddPersonSheet`（决策2）。

### 4.2 工具栏（右侧 action，顺序）

1. **筛选切换**：`line.3.horizontal.decrease` ↔ `line.3.horizontal.decrease.circle`（显示全部 / 隐藏未命名单人组）。点击 `toggleShowAll()`。
   - 隐藏计数提示：当 `hidden > 0 && !showAll`，提示「已隐藏 N 个未命名单人分组」。
2. **重聚类**：`arrow.triangle.2.circlepath`，点击 `TagScanOrchestrator.shared.runPass2Clustering()` + 提示「已开始重聚类，完成后返回本页刷新」。
3. **重打分**：⚠️ Stage 2，Stage 1 不渲染此按钮。

### 4.3 网格

- `LazyVGrid`，2 列，`spacing` / contentPadding = 12pt。
- 卡片：16pt 圆角，`surfaceContainerLow` 等效色（暗色 `white.opacity(0.06)`），轻微投影对齐 Android 1dp elevation。

### 4.4 卡片单元（对标 Android `PersonListItem`）

- **封面**：1:1 方形，**人脸感知**（透传 `faceFocusY` 到 `ThumbnailView`），仅顶部 16pt 圆角。点击 → 相册按人物筛选（⚠️ Stage 2；Stage 1 点击进入详情）。
- **信息行**：名称（命名显示 `name`，未命名显示「点击命名」灰字）+ 照片数（`%d photos`）+ ⓘ info 按钮（进详情）。
- **关系 chip**：仅当 `isSelf` 或已设关系时显示。`isSelf` → 高亮色（`primaryContainer` 等效）；否则中性色。
- **行内改名**：点名称 → `TextField` + ✓（保存，trim，空忽略）/ ✗（取消）。无独立弹窗。
- **空态/加载态**：对齐 Android——**什么都不渲染**（决策1），网格直接空。

### 4.5 过滤与排序（ViewModel 计算，对齐 Android）

- **默认（showAll=false）**：隐藏「未命名 且 照片数 < 2」的单人噪声组。
- **coverable 过滤**：封面 uri 解析为空者丢弃。
- **排序**（降序）：self(5) > 浪漫[PARTNER/SPOUSE](4) > IDOL(3) > 家庭谓词(2) > 其他社会关系(1) > 无关系(0) → 命名优先 → 命名按 photoCount desc / 未命名按 updatedAt desc → updatedAt desc 兜底。

---

## 5. 详情页（`PersonInfoView`）对齐规格

### 5.1 顶栏

- **标题 = `Cluster #N`**（`person_id`），**不是**人物名（与 Android 一致）。
- 返回、保存（✓）按钮。
- ⚠️ Stage 1 不含重打分 action（Stage 2 补）。

### 5.2 主体（纵向滚动）

- **封面**：55% 宽 × 180pt 高，16pt 圆角，人脸感知，可点 → 封面选择器。
- **名称大标题**：命名显示 `name`，未命名显示「点击命名」；点击进入行内编辑（headline 字号、居中、Done 收键盘）。
- **「这是我」开关**：全局唯一 self，开启时清除其他人物的 self 标记。
- **关系选择器**（替换现有两级选择器，决策3）：
  - 家庭组 chip：FATHER / MOTHER / SON / DAUGHTER / ELDER/YOUNGER BROTHER / ELDER/YOUNGER SISTER / GRANDFATHER / GRANDMOTHER / SPOUSE / PARTNER
  - 社会组 chip：FRIEND / CLASSMATE / COLLEAGUE / IDOL
  - 「不设置」chip
  - 自定义文本框（非空时覆盖谓词 → 强制 `OTHER`；选 chip 清空自定义）
  - 仅编辑「该人物对己关系」，object 隐式 = self。

### 5.3 封面选择器（`coverPicker`）

- `.sheet`，3 列方形候选（`getMediaByPersonOrderedForCover`：单人脸优先 + 拍摄时间倒序 + 去重）。
- 选中 → `updateCover(personId, mediaId)` → 关闭。

### 5.4 移除项（决策2）

详情页**移除**：照片网格、多选指派照片、删除人物。这些 Android 均无。（Android 的照片→人物关联由聚类自动完成；坏簇通过重聚类修正，无手动删除入口。）

### 5.5 保存

`doSave()`：自定义非空 → effectiveRelation=`OTHER`；否则所选谓词。持久化 name（trim，空忽略）+ relation-to-self + isSelf，随后返回。

---

## 6. i18n（红线）

同步 Android 约 30 个 key 到 `iosApp/.../Localizable.xcstrings`，**三语**（en / zh-Hans / zh-Hant），逐条核对 Android `values/` + `values-zh-rCN/` + `values-zh-rTW/`：

- 列表：`people_title_with_count`、`people_filter_show_all`、`people_filter_hide_singletons`、`people_filter_hidden_hint`、`people_default_name`、`people_photos_count`、`people_recluster`、`people_recluster_started`。
- 详情：`person_cluster_id`、`person_edit_name_hint`、`person_set_cover_hint`、`person_select_cover_title`、`person_is_self`、`save`、`cancel`。
- 关系：`person_relation_label`、`person_relation_none`、`person_relation_group_family`、`person_relation_group_social`、`person_relation_custom_label`、`person_relation_custom_hint`、`person_relation_custom_supporting` + 23 个谓词 key。

> 注：`people_rescore` / 重打分相关文案随 Stage 2 一并补。

---

## 7. 分阶段交付

### Stage 1（本次，核心全量对齐）

1. 数据层：`TagDatabase` 新增 `person_relations` 表 + 人物级查询方法；新建 iOS `PersonRepository`；删除 `PersonStore`。
2. 列表页：动态计数标题、2 列人脸感知卡片、行内改名、关系 chip、Android 排序、筛选切换 + 重聚类工具栏。
3. 详情页：`Cluster #N` 标题、55%/180 封面 + 选择器、名称大标题行内编辑、「这是我」、关系 chip 组选择器；移除照片网格/指派/删除/手动加人。
4. i18n 三语同步。
5. 真机验证：聚类数据在 UI 正确渲染（封面、计数、关系、排序、筛选、重聚类）。

**验收**：列表/详情外观与行为与 Android 一致；筛选与重聚类可用；三语齐全；编译通过。

### Stage 2（后续，暂缓项）

1. 重打分：接 NIMA 美学打分器，`aestheticScore` 写入，工具栏重打分按钮。
2. 封面点击 → 相册按聚类 `personId` 筛选（相册当前按 `faceId` 分组，需 faceId↔personId 桥接）。
3. 相册侧 `PersonInfoScreen` 复用入口对齐（Android Gallery 分组点击进入同一详情组件）。

---

## 8. 影响文件（预估）

**新增**
- `iosApp/PoLang/Features/Person/PersonRepository.swift`（数据访问层）
- `TagDatabase` 内 `person_relations` 表 + 查询方法

**重写**
- `iosApp/PoLang/Features/Person/PersonView.swift`（列表卡 + 工具栏）
- `iosApp/PoLang/Features/Person/PersonInfoView.swift`（详情：封面/名称/关系选择器，移除照片网格/指派/删除/两级关系选择器）
- `iosApp/PoLang/Features/Person/PersonViewModel.swift`（list + detail VM 改读 `PersonRepository`）

**删除**
- `iosApp/PoLang/Features/Person/PersonStore.swift`
- `AddPersonSheet`、`MediaPickerSheet`（多选指派用）/ 改造为封面选择器

**同步**
- `Localizable.xcstrings`（三语）

---

## 9. 红线与约束

- **[I18N]** 三语强制同步，禁硬编码 UI 字符串。
- **[隐私]** 人物/照片关联 100% 端侧（聚类来自端侧 face embedding，不触远程）。✅ 天然满足。
- **[代码风格]** 无全限定名、无通配符 import、lambda 显式命名、日志 tag `PoLang:[模块]`。
- **对齐纪律**：iOS 聚类/渲染共用同一数据，landmark/faceFocusY 双端同源，不得引入镜像误差。
