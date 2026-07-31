# 人物编辑入口去重 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> ⚠️ 本环境子代理不可用（Agent/Workflow 启动报模型不存在），应选 Inline Execution（superpowers:executing-plans）。

**Goal:** 删除相册「按人物分组」的编辑弹框 `PersonRenameDialog`，改为打开与人物页同一个全屏 `PersonInfoScreen`，并把人物编辑数据加载下沉到 `PersonRepository`，消除 UI 与数据加载重复。

**Architecture:** 在 `PersonRepository` 增 `loadPersonEditSnapshot(personId)` 作为唯一加载入口（返回实体+关系+封面媒体+封面候选照片），并把 `PersonRelationEntity → RelationDisplayItem` 映射下沉为 `RelationDisplayItem.from` 共享工厂。GalleryScreen 用快照驱动 `PersonInfoScreen` overlay；人物页 VM 复用同一映射。落库已收口在 `applyPersonEdit`，不动。

**Tech Stack:** Kotlin、Jetpack Compose、Room、Material3。`:app` 模块。

**对应 spec：** `docs/superpowers/specs/2026-07-31-person-edit-dedup-design.md`

**关键事实（已核实，非占位）：**
- `PersonDao.getPerson(personId): PersonEntity?` **已存在**（`data/local/dao/PersonDao.kt:36`），无需新增 DAO 查询。
- `PersonDao.getMediaByPersonOrderedForCover(personId): List<MediaEntity>` 已存在（同文件:75）。
- `PersonRepository(personDao, relationDao)` 构造器不变；封面从照片列表按 `coverMediaId` 解析，**不引入 mediaDao**。
- `PersonCover(coverUri: String?, faceFocusY: Float?)` 在 `features/person`（`PersonCoverResolver.kt`）。
- 字符串 `person_edit_title` / `person_edit_name_label` 仅 `PersonRenameDialog` 引用（已 grep 确认），删弹框后三语可清。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt` | 人物领域仓库；新增 `RelationDisplayItem.from` 工厂、`PersonEditSnapshot`、`loadPersonEditSnapshot` | 修改 |
| `app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt` | 人物页 VM；`relationToDisplay` 改用共享工厂 | 修改 |
| `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt` | 相册；删弹框、加 overlay、抽 `refreshPersonNameMap` | 修改 |
| `app/src/main/java/com/mamba/picme/features/common/PersonRenameDialog.kt` | 旧弹框 | **删除** |
| `app/src/main/java/com/mamba/picme/features/settings/MemoryFactsScreen.kt` | 过期注释 | 修改（1 行） |
| `app/src/main/res/values/strings.xml`、`values-zh-rCN/`、`values-zh-rTW/` | 三语字符串 | 删 2 个 key |
| `app/src/test/java/com/mamba/picme/domain/person/RelationDisplayItemTest.kt` | `RelationDisplayItem.from` 单测 | 新增 |

---

## Task 1: 下沉 `RelationDisplayItem.from` 共享映射（TDD）

**Files:**
- Create: `app/src/test/java/com/mamba/picme/domain/person/RelationDisplayItemTest.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt`（`RelationDisplayItem` 定义处，约 :299）

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mamba/picme/domain/person/RelationDisplayItemTest.kt`：

```kotlin
package com.mamba.picme.domain.person

import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelationDisplayItemTest {
    private fun person(id: Long, name: String?) = PersonEntity(personId = id, name = name)

    private fun relation(predicate: String, customLabel: String?) =
        PersonRelationEntity(
            relationId = 1,
            subjectPersonId = 5,
            objectPersonId = 1,
            predicate = predicate,
            source = "RENAME_DIALOG",
            customLabel = customLabel
        )

    @Test
    fun null_relation_returns_null() {
        assertNull(RelationDisplayItem.from(person(5, "小宝"), null))
    }

    @Test
    fun unknown_predicate_returns_null() {
        assertNull(RelationDisplayItem.from(person(5, "小宝"), relation("BOGUS", null)))
    }

    @Test
    fun valid_predicate_no_custom_label() {
        val item = RelationDisplayItem.from(person(5, "小宝"), relation("DAUGHTER", null))
        assertNotNull(item)
        assertEquals(RelationPredicate.DAUGHTER, item!!.predicate)
        assertNull(item.customLabel)
        assertEquals("小宝", item.subjectName)
        assertEquals(5L, item.subjectPersonId)
    }

    @Test
    fun blank_custom_label_normalized_to_null() {
        val item = RelationDisplayItem.from(person(5, "小宝"), relation("OTHER", "   "))
        assertNotNull(item)
        assertNull(item!!.customLabel)
    }

    @Test
    fun non_blank_custom_label_trimmed() {
        val item = RelationDisplayItem.from(person(5, "小宝"), relation("OTHER", "  小甜甜  "))
        assertEquals("小甜甜", item!!.customLabel)
    }

    @Test
    fun null_person_name_falls_back_to_hash_id() {
        val item = RelationDisplayItem.from(person(5, null), relation("SON", null))
        assertEquals("#5", item!!.subjectName)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.person.RelationDisplayItemTest"`
Expected: 编译失败——`RelationDisplayItem.from` 未定义。

- [ ] **Step 3: 实现 `RelationDisplayItem.from`**

在 `PersonRepository.kt` 末尾的 `RelationDisplayItem` data class 上加 `companion object`（保留现有字段与文档注释，仅新增 companion）：

```kotlin
data class RelationDisplayItem(
    val relationId: Long,
    val subjectPersonId: Long,
    val subjectName: String,
    val predicate: RelationPredicate,
    val customLabel: String? = null
) {
    companion object {
        /**
         * PersonRelationEntity → RelationDisplayItem。
         * 未知谓词或空关系返回 null；customLabel 空白归一为 null、非空则 trim。
         */
        fun from(person: PersonEntity, relation: PersonRelationEntity?): RelationDisplayItem? {
            if (relation == null) return null
            val predicate = RelationPredicate.fromStored(relation.predicate) ?: return null
            return RelationDisplayItem(
                relationId = relation.relationId,
                subjectPersonId = person.personId,
                subjectName = person.name ?: "#${person.personId}",
                predicate = predicate,
                customLabel = relation.customLabel?.trim()?.ifEmpty { null }
            )
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.person.RelationDisplayItemTest"`
Expected: PASS（纯 Kotlin，无 Room/Android 依赖）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt \
        app/src/test/java/com/mamba/picme/domain/person/RelationDisplayItemTest.kt
git commit -m "refactor(person): 下沉 RelationDisplayItem.from 共享映射 + 单测"
```

---

## Task 2: `PersonRepository.loadPersonEditSnapshot`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt`

- [ ] **Step 1: 加 `MediaEntity` import**

在 `PersonRepository.kt` 顶部 import 区加：

```kotlin
import com.mamba.picme.data.model.MediaEntity
```

- [ ] **Step 2: 加 `PersonEditSnapshot` 数据类**

在 `PersonRepository.kt` 中 `RelationDisplayItem` 之后新增：

```kotlin
/**
 * 人物信息编辑页所需数据快照（相册分组与人物页共用加载入口）。
 *
 * [coverMedia] 从 [photos] 中按 person.coverMediaId 解析；coverMediaId 失效时为 null。
 */
data class PersonEditSnapshot(
    val person: PersonEntity,
    val relation: RelationDisplayItem?,
    val coverMedia: MediaEntity?,
    val photos: List<MediaEntity>
)
```

- [ ] **Step 3: 加 `loadPersonEditSnapshot` 方法**

在 `PersonRepository` 类内（如 `getRelationToSelf` 之后）新增：

```kotlin
/**
 * 加载某人物信息编辑页所需快照：实体 + 与"我"的关系 + 封面媒体 + 封面候选照片。
 * 人物不存在返回 null。封面从封面候选照片里按 coverMediaId 解析（coverMediaId
 * 失效/媒体已删时为 null，由调用方兜底渲染）。
 */
suspend fun loadPersonEditSnapshot(personId: Long): PersonEditSnapshot? {
    val person = personDao.getPerson(personId) ?: return null
    val relation = RelationDisplayItem.from(person, getRelationToSelf(personId))
    val photos = personDao.getMediaByPersonOrderedForCover(personId).distinctBy { it.id }
    val coverMedia = person.coverMediaId?.let { cid -> photos.firstOrNull { it.id == cid } }
    return PersonEditSnapshot(person, relation, coverMedia, photos)
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: 编译通过（`getPerson` / `getMediaByPersonOrderedForCover` 均为现有 DAO 方法）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt
git commit -m "feat(person): PersonRepository.loadPersonEditSnapshot 编辑数据加载收口"
```

---

## Task 3: `PersonViewModel` 复用共享映射

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt`

- [ ] **Step 1: 调用处改用 `RelationDisplayItem.from`**

`PersonViewModel.kt:78-83` 当前：

```kotlin
            val relationMap = withContext(Dispatchers.IO) {
                all.associate { person ->
                    val relation = personRepository.getRelationToSelf(person.personId)
                    person.personId to relationToDisplay(person, relation)
                }
            }
```

改为：

```kotlin
            val relationMap = withContext(Dispatchers.IO) {
                all.associate { person ->
                    val relation = personRepository.getRelationToSelf(person.personId)
                    person.personId to RelationDisplayItem.from(person, relation)
                }
            }
```

- [ ] **Step 2: 删除私有 `relationToDisplay`**

删除 `PersonViewModel.kt:247-260` 的整个 `private fun relationToDisplay(...)`（其逻辑已下沉到 `RelationDisplayItem.from`）。`RelationDisplayItem` 已在 :12 import。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: 编译通过；确认 `relationToDisplay` 无其它引用（仅 :81 一处）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt
git commit -m "refactor(person): PersonViewModel 复用 RelationDisplayItem.from"
```

---

## Task 4: GalleryScreen 改用 PersonInfoScreen overlay（删弹框接线）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`

- [ ] **Step 1: 调整 import**

删除：
- `:74` `import com.mamba.picme.features.common.PersonRenameDialog`
- `:83` `import com.mamba.picme.domain.person.RelationPredicate`（删弹框后无引用）

新增：
```kotlin
import com.mamba.picme.domain.person.PersonEditSnapshot
import com.mamba.picme.features.person.PersonCover
import com.mamba.picme.features.person.components.PersonInfoScreen
```

- [ ] **Step 2: 替换弹框状态为 overlay 状态 + 抽 `refreshPersonNameMap`**

`GalleryScreen.kt:312-333` 当前：

```kotlin
    // 人物分组重命名状态
    var renamingPersonGroup by remember { mutableStateOf<GroupedMedia?>(null) }
    var renamingPersonName by remember { mutableStateOf("") }
    // 人物关系声明状态（重命名对话框内"TA 是我的…"与"这是我"）
    var renamingPersonRelation by remember { mutableStateOf<RelationPredicate?>(null) }
    var renamingPersonCustomLabel by remember { mutableStateOf("") }
    var renamingPersonIsSelf by remember { mutableStateOf(false) }

    // 当切换到 PERSON 分组模式时加载所有 person 名称
    LaunchedEffect(groupingMode) {
        if (groupingMode == GroupingMode.PERSON) {
            try {
                val db = AppDatabase.getDatabase(context)
                val persons = db.personDao().getAllPersons()
                personNameMap.clear()
                for (p in persons) {
                    val displayName = p.name ?: "人物 ${p.personId}"
                    personNameMap[p.personId.toString()] = displayName
                }
            } catch (_: Exception) {}
        }
    }
```

改为：

```kotlin
    // 人物信息编辑 overlay 状态（点分组名打开 PersonInfoScreen）
    var infoPersonId by remember { mutableStateOf<Long?>(null) }
    var infoSnapshot by remember { mutableStateOf<PersonEditSnapshot?>(null) }

    // 从 DB 重载人物分组名称映射（进入 PERSON 模式 / 编辑保存后刷新分组标题）
    suspend fun refreshPersonNameMap() {
        try {
            val db = AppDatabase.getDatabase(context)
            val persons = db.personDao().getAllPersons()
            personNameMap.clear()
            for (p in persons) {
                val displayName = p.name ?: "人物 ${p.personId}"
                personNameMap[p.personId.toString()] = displayName
            }
        } catch (_: Exception) {}
    }

    // 切到 PERSON 分组模式时加载所有 person 名称
    LaunchedEffect(groupingMode) {
        if (groupingMode == GroupingMode.PERSON) refreshPersonNameMap()
    }

    // 点分组名 → 加载编辑快照；infoPersonId 置空时清 overlay
    LaunchedEffect(infoPersonId) {
        val id = infoPersonId
        if (id != null) {
            infoSnapshot = null
            runCatching { app.container.personRepository.loadPersonEditSnapshot(id) }
                .onSuccess { snapshot -> infoSnapshot = snapshot }
                .onFailure { Logger.e(TAG, "Failed to load person edit snapshot", it) }
        } else {
            infoSnapshot = null
        }
    }
```

- [ ] **Step 3: 改写 `onGroupTitleClick`**

`GalleryScreen.kt:824-850` 当前 `onGroupTitleClick = { group -> ... }` 整个 lambda 体改为：

```kotlin
                        onGroupTitleClick = { group ->
                            if (groupingMode == GroupingMode.PERSON) {
                                infoPersonId = group.titleValue.toLongOrNull()
                            }
                        },
```

（删除原先设置 5 个 renaming 状态 + `MainScope().launch` 回显关系/"我"的整段。）

- [ ] **Step 4: 用 PersonInfoScreen overlay 替换弹框渲染块**

`GalleryScreen.kt:974-1000` 当前：

```kotlin
    // ── 人物分组编辑对话框（公共组件 PersonRenameDialog）──
    val renamingGroup = renamingPersonGroup
    if (renamingGroup != null) {
        PersonRenameDialog(
            initialName = renamingPersonName,
            initialRelation = renamingPersonRelation,
            initialCustomLabel = renamingPersonCustomLabel,
            initialIsSelf = renamingPersonIsSelf,
            onConfirm = { name, relation, customLabel, isSelf ->
                val personId = renamingGroup.titleValue.toLongOrNull()
                if (personId != null) {
                    kotlinx.coroutines.MainScope().launch {
                        try {
                            val repo = app.container.personRepository
                            repo.applyPersonEdit(personId, name, relation, customLabel, isSelf)
                            if (name.isNotBlank()) {
                                personNameMap[renamingGroup.titleValue] = name
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "Failed to update person group", e)
                        }
                    }
                }
            },
            onDismiss = { renamingPersonGroup = null }
        )
    }
```

改为：

```kotlin
    // ── 人物信息编辑 overlay（点分组名打开，与人物页同一 PersonInfoScreen）──
    val infoSnap = infoSnapshot
    if (infoSnap != null) {
        PersonInfoScreen(
            person = infoSnap.person,
            relation = infoSnap.relation,
            cover = infoSnap.coverMedia?.let { media -> PersonCover(media.uri, media.faceFocusY) },
            photos = infoSnap.photos,
            onSave = { relation, customLabel, isSelf ->
                kotlinx.coroutines.MainScope().launch {
                    runCatching {
                        app.container.personRepository.applyPersonEdit(
                            infoSnap.person.personId,
                            infoSnap.person.name.orEmpty(),
                            relation,
                            customLabel,
                            isSelf
                        )
                    }.onSuccess { refreshPersonNameMap() }
                        .onFailure { Logger.e(TAG, "Failed to apply person edit", it) }
                }
            },
            onNavigateBack = { infoPersonId = null },
            onUpdateCover = { photo ->
                kotlinx.coroutines.MainScope().launch {
                    runCatching {
                        app.container.personRepository.updateCover(infoSnap.person.personId, photo.id)
                    }.onSuccess { refreshPersonNameMap() }
                        .onFailure { Logger.e(TAG, "Failed to update cover", it) }
                }
            },
            onUpdateName = { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    kotlinx.coroutines.MainScope().launch {
                        runCatching {
                            app.container.personRepository.renamePerson(infoSnap.person.personId, trimmed)
                        }.onFailure { Logger.e(TAG, "Failed to rename person", it) }
                    }
                }
            }
        )
    }
```

> 说明：`PersonInfoScreen.doSave` 内部依次调 `onUpdateName`（改名）→ `onSave`（关系/"我"，name 传当前名）→ `onNavigateBack`（关 overlay）。故刷新只在 `onSave`/`onUpdateCover` 成功后做一次；`onUpdateName` 仅改名不刷新（紧随其后的 `onSave` 会刷新）。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: 编译通过。确认无 `renaming*` / `PersonRenameDialog` / `RelationPredicate` 残留引用：

```bash
grep -n "renamingPerson\|PersonRenameDialog\|RelationPredicate" app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt
```
Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt
git commit -m "refactor(gallery): 分组名点击改开 PersonInfoScreen overlay，移除编辑弹框接线"
```

---

## Task 5: 删除 `PersonRenameDialog.kt`

**Files:**
- Delete: `app/src/main/java/com/mamba/picme/features/common/PersonRenameDialog.kt`

- [ ] **Step 1: 确认无残留引用**

Run: `grep -rn "PersonRenameDialog" app/src/main`
Expected: 无输出（Task 4 已移除唯一使用点）。若仍有引用，回到 Task 4 修补。

- [ ] **Step 2: 删除文件**

```bash
git rm app/src/main/java/com/mamba/picme/features/common/PersonRenameDialog.kt
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: 编译通过。

- [ ] **Step 4: 提交**

```bash
git commit -m "refactor(common): 删除冗余的 PersonRenameDialog（统一为 PersonInfoScreen）"
```

---

## Task 6: i18n 清理（三语删 2 个 key）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（:929-930）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`（:923-924）
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`（:901-902）

- [ ] **Step 1: 确认两个 key 仅弹框使用过**

Run: `grep -rn "person_edit_title\|person_edit_name_label" app/src`
Expected: 仅三语 `strings.xml` 命中（kt 引用已随弹框删除）。

- [ ] **Step 2: 三语各删两行**

分别在三个 `strings.xml` 删除：
```xml
    <string name="person_edit_title">…</string>
    <string name="person_edit_name_label">…</string>
```
（values 为 "Edit person" / "Name"；zh-rCN 为 "编辑人物" / "名称"；zh-rTW 为 "編輯人物" / "名稱"。）

- [ ] **Step 3: 编译验证（资源引用完整性）**

Run: `./gradlew :app:assembleDebug`
Expected: 编译通过（无 `person_edit_title` 残留引用 → 无资源找不到错误）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n: 移除 PersonRenameDialog 专属字符串 person_edit_title/name_label（三语）"
```

---

## Task 7: 修过期注释

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/MemoryFactsScreen.kt:183`

- [ ] **Step 1: 改注释**

`MemoryFactsScreen.kt:183` 当前：

```kotlin
    // 注：人物关系编辑已迁至「人物」页重命名对话框（PersonRenameDialog），本页专注事实记忆。
```

改为：

```kotlin
    // 注：人物关系编辑统一在人物信息编辑页（PersonInfoScreen），本页专注事实记忆。
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/MemoryFactsScreen.kt
git commit -m "docs(memory): 更新人物关系编辑位置注释（PersonInfoScreen）"
```

---

## Task 8: 总验证

- [ ] **Step 1: 跑目标单测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.person.RelationDisplayItemTest"`
Expected: PASS。

- [ ] **Step 2: 编译 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 残留扫描**

```bash
grep -rn "PersonRenameDialog\|person_edit_title\|person_edit_name_label" app/src
```
Expected: 无输出。

- [ ] **Step 4: 设备端到端（/ui-driver）**

用 `/ui-driver` 驱动：相册 → 切「人物」分组 → 点某分组名 → 进入 `PersonInfoScreen` → 改名 / 选关系 / 自定义称呼 / "这是我" / 换封面 → 保存 → 返回分组列表，确认：
- 分组标题名字已更新；
- 再次点开同一分组，回显上次保存的名字/关系/称呼/封面；
- 人物页（「人物」tab）点 MoreVert 进入同一编辑页，行为一致。

- [ ] **Step 5: 最终提交（如有设备端调整）**

若设备验证发现需要微调，按需补 commit；否则本任务无新增提交。

---

## Self-Review（写计划后自检）

- **Spec 覆盖**：删弹框(Task4-5)、下沉快照+映射(Task1-2)、VM 复用映射(Task3)、i18n(Task6)、注释(Task7) 均覆盖；测试(Task1 单测 + Task8 设备) 覆盖。✓
- **占位扫描**：所有代码块完整、命令具体、无 TBD。✓
- **类型一致**：`RelationDisplayItem.from(PersonEntity, PersonRelationEntity?)` 在 Task1 定义，Task2/Task3 调用签名一致；`PersonEditSnapshot(person, relation, coverMedia, photos)` 定义与 Task4 取用（`infoSnap.person/.relation/.coverMedia/.photos`）一致；`PersonInfoScreen` 形参（`person/relation/cover/photos/onSave/onNavigateBack/onUpdateCover/onUpdateName`）与 Task4 实参一一对应。✓
- **spec 已同步**：spec 3.2 A 原写「新增 DAO 单点查询」，实际 `PersonDao.getPerson` 已存在——已回填 spec 改为「复用现有」，封面解析改为「从 photos 按 coverMediaId 解析、不引入 mediaDao」（见本仓库 spec 提交）。
