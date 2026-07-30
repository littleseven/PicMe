# 人物页 + 入口 + 命名（Plan C）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建独立「人物」页：全部人脸聚类以代表图封面网格呈现，点封面直接改名/标关系/标"我"；相册顶栏加「人物」直达入口；设置新增一级「人物」入口（从 AI 记忆拆出，AI 记忆专注事实记忆）。

**Architecture:** 抽取 GalleryScreen 里的人物重命名对话框为公共 `PersonRenameDialog`，命名/关系/自我标记的落库逻辑收口到 `PersonRepository.applyPersonEdit()`（DRY，相册与人物页共用）。新建 `PersonScreen` + `PersonViewModel`（Coil 加载 coverMediaId 整图为封面缩略图）。导航加 `Screen.People`；`GalleryTopBar` 加人物图标；`SettingsScreen` 主菜单加一级人物项；`MemoryFactsScreen` 移除人物关系 section。

**Tech Stack:** Kotlin + Jetpack Compose + ViewModel + StateFlow + Room；Coil 2.7 图片加载；纯 JVM 单测（ViewModel 映射逻辑）+ 设备 `/ui-driver`（accessibility）验证 UI。

**Spec:** `docs/superpowers/specs/2026-07-30-face-cluster-experience-design.md`（§3.4 人物页、§3.5 入口；A→C→B 之 C）

**⚠️ 与 spec 的两处务实偏差（已确认前提）:**
1. **封面用整图缩略图，非人脸裁剪**：`faceRoiResult` 持久化的 JSON 仅含 `{hasFace,faceCount,isSelfie,isGroupPhoto}`，**不含 ROI 矩形坐标**（`faceRoiToJson` 不写 roi）。人脸裁剪需显示时重检测或改 schema 存 ROI+重扫，超出 v1；改用 coverMediaId 整图封面（仍满足"看代表图认人→命名"）。
2. **"点进看该人照片"延后**：需相册按 personId 过滤的额外路由接线；v1 聚焦命名+入口，照片浏览留作后续。

**验证命令（本环境真门槛=编译+JVM单测）:**
- 编译：`./gradlew :app:assembleDebug`
- 单测：`./gradlew :app:testDebugUnitTest`
- 设备 UI（非自动化门槛）：`/ui-driver` accessibility 驱动

**约定:**
- 禁止 `com.mamba.picme.*` 全限定名（用 import）；禁止 wildcard import；lambda 参数显式命名；4 空格缩进。
- 每个任务末尾提交一次（Conventional Commits）。commit message 结尾加 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- I18N：所有新文案同步 4 套 `values/`、`values-zh/`、`values-zh-rCN/`、`values-zh-rTW/`。
- Log tag：`PoLang:People`。

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `domain/person/PersonRepository.kt` | 新增 `applyPersonEdit()`（命名/关系/自我收口）+ `getAllPersons()` | 改 |
| `features/common/PersonRenameDialog.kt` | 公共重命名对话框（纯 UI） | 新 |
| `features/gallery/GalleryScreen.kt` | 改用 `PersonRenameDialog` + `applyPersonEdit` | 改 |
| `features/person/PersonViewModel.kt` | 人物列表 StateFlow + 封面 uri 解析 + 编辑 | 新 |
| `features/person/PersonScreen.kt` | 封面网格 + 重命名 + 入口 | 新 |
| `navigation/Screen.kt` | `data object People` | 改 |
| `MainActivity.kt` | `Screen.People` composable 接线 | 改 |
| `features/gallery/components/GalleryTopBar.kt` | 人物图标入口 | 改 |
| `features/settings/SettingsScreen.kt` | 主菜单一级人物项 | 改 |
| `features/settings/MemoryFactsScreen.kt` | 移除人物关系 section | 改 |
| `res/values*/strings.xml` | 新文案（4 locale） | 改 |

---

### Task 1: PersonRepository.applyPersonEdit() + getAllPersons()

> 把 GalleryScreen 重命名对话框 confirm 里的落库逻辑（rename + setSelf/clearSelf + declareRelation/removeAllRelationsOf）收口到仓库，供相册与人物页共用（DRY）。该方法调用 DAO，属集成代码，依赖编译 + 设备验证。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt`

- [ ] **Step 1: 新增 getAllPersons()**

在 `getNamedPersons()` 之后新增（返回全部人物簇，含未命名）：

```kotlin
    /** 全部人物簇（含未命名），按更新时间倒序，供人物页展示。 */
    suspend fun getAllPersons(): List<PersonEntity> =
        personDao.getAllPersons().sortedByDescending { person -> person.updatedAt }
```

- [ ] **Step 2: 新增 applyPersonEdit()**

在 `declareRelation(...)` 之前新增（封装命名 + 自我标记 + 关系声明/清除的完整写入）：

```kotlin
    /**
     * 人物编辑收口（相册重命名对话框与人物页共用）：
     * 1) name 非空 → 改名；
     * 2) isSelf → 设为"我"，否则若当前是"我"则清除；
     * 3) relation != null → 声明（覆盖）；relation == null → 清除该人物所有关系。
     * 自定义称呼非空时 predicate 应为 [RelationPredicate.OTHER]（由调用方决定）。
     */
    suspend fun applyPersonEdit(
        personId: Long,
        name: String,
        relation: RelationPredicate?,
        customLabel: String,
        isSelf: Boolean
    ) {
        if (name.isNotBlank()) {
            renamePerson(personId, name)
        }
        if (isSelf) {
            setSelf(personId)
        } else if (getSelfPerson()?.personId == personId) {
            clearSelf()
        }
        if (relation != null) {
            declareRelation(
                subjectPersonId = personId,
                predicate = relation,
                source = RelationSource.RENAME_DIALOG,
                customLabel = customLabel.ifEmpty { null }
            )
        } else {
            removeAllRelationsOf(personId)
        }
    }
```

> 用到的 `RelationSource`、`RelationPredicate` 已是该文件现有 import（`declareRelation` 已用）。`renamePerson/setSelf/clearSelf/getSelfPerson/declareRelation/removeAllRelationsOf` 均为同类已有方法。

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt
git commit -m "feat(person): PersonRepository.applyPersonEdit 收口命名/关系/自我 + getAllPersons

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 抽取公共 PersonRenameDialog 并改造 GalleryScreen

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/common/PersonRenameDialog.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`（替换 885-990 行的内联对话框）

- [ ] **Step 1: 新建 PersonRenameDialog（纯 UI）**

```kotlin
package com.mamba.picme.features.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlignmentLine
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.person.RelationPredicate

/**
 * 人物重命名/关系/自我标记公共对话框（纯 UI）。
 *
 * 相册「按人物分组」与「人物」页共用。落库由 [onConfirm] 回调收口
 * （调用方走 `PersonRepository.applyPersonEdit`）。
 *
 * @param initialName 当前名字（可空 → 显示占位）
 * @param initialRelation 已有关系谓词
 * @param initialCustomLabel 已有自定义称呼
 * @param initialIsSelf 是否已是"我"
 */
@Composable
fun PersonRenameDialog(
    initialName: String,
    initialRelation: RelationPredicate?,
    initialCustomLabel: String,
    initialIsSelf: Boolean,
    onConfirm: (name: String, relation: RelationPredicate?, customLabel: String, isSelf: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var relation by remember { mutableStateOf(initialRelation) }
    var customLabel by remember { mutableStateOf(initialCustomLabel) }
    var isSelf by remember { mutableStateOf(initialIsSelf) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.person_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value -> name = value },
                    label = { Text(stringResource(R.string.person_edit_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PersonRelationPicker(
                    selectedPredicate = relation,
                    customLabel = customLabel,
                    onPredicateChange = { predicate -> relation = predicate },
                    onCustomLabelChange = { label -> customLabel = label },
                    modifier = Modifier.padding(top = 16.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.person_is_self),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isSelf,
                        onCheckedChange = { checked -> isSelf = checked }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedName = name.trim()
                val trimmedCustom = customLabel.trim()
                // 自定义称呼非空时以输入为准（谓词记 OTHER）；否则用选中谓词
                val effectiveRelation = if (trimmedCustom.isNotEmpty()) {
                    RelationPredicate.OTHER
                } else {
                    relation
                }
                onConfirm(trimmedName, effectiveRelation, trimmedCustom, isSelf)
                onDismiss()
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
```

> 删除占位 import `AlignmentLine`（实际未用）——以编译为准，保留必要 import。

- [ ] **Step 2: GalleryScreen 改用公共对话框**

把 `GalleryScreen.kt` 885-990 行整段（`if (renamingPersonGroup != null) { AlertDialog(...) }`）替换为：

```kotlin
    // ── 人物分组编辑对话框（公共组件）──
    val renamingGroup = renamingPersonGroup
    if (renamingGroup != null) {
        PersonRenameDialog(
            initialName = renamingPersonName,
            initialRelation = renamingPersonRelation,
            initialCustomLabel = renamingPersonCustomLabel,
            initialIsSelf = renamingPersonIsSelf,
            onConfirm = { name, relation, customLabel, isSelf ->
                val personId = renamingGroup.titleValue.toLongOrNull() ?: return@PersonRenameDialog
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
            },
            onDismiss = { renamingPersonGroup = null }
        )
    }
```

> `renamingPersonName/Relation/CustomLabel/IsSelf` 仍由 `onGroupTitleClick`（740-765 行）回填初值，保持不变。删除 GalleryScreen 现已多余的 import（如 `AlertDialog`/`RelationSource` 若不再直接使用——以编译为准）。

- [ ] **Step 3: 编译 + 既有单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；既有单测 PASS。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/common/PersonRenameDialog.kt \
        app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt
git commit -m "refactor(person): 抽取公共 PersonRenameDialog，GalleryScreen 改用 applyPersonEdit

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: PersonViewModel（人物列表 + 封面 uri）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/person/PersonCoverResolverTest.kt`（纯映射逻辑）

- [ ] **Step 1: 抽出可测的封面映射纯函数（先写测试）**

```kotlin
package com.mamba.picme.features.person

import com.mamba.picme.data.local.entity.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonCoverResolverTest {
    @Test
    fun mapsCoverUriByPersonId() {
        val persons = listOf(
            PersonEntity(personId = 1, coverMediaId = 10),
            PersonEntity(personId = 2, coverMediaId = null),
            PersonEntity(personId = 3, coverMediaId = 30)
        )
        val uriById = mapOf(10L to "content://a", 30L to "content://c")
        val resolved = PersonCoverResolver.resolveCoverUris(persons, uriById)
        assertEquals("content://a", resolved[1L])
        assertEquals(null, resolved[2L])
        assertEquals("content://c", resolved[3L])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.person.PersonCoverResolverTest"`
Expected: FAIL — `PersonCoverResolver` 未定义。

- [ ] **Step 3: 实现 PersonCoverResolver + PersonViewModel**

`app/src/main/java/com/mamba/picme/features/person/PersonCoverResolver.kt`：

```kotlin
package com.mamba.picme.features.person

import com.mamba.picme.data.local.entity.PersonEntity

/** 纯映射：persons × (coverMediaId→uri) → personId→coverUri。便于 JVM 单测。 */
object PersonCoverResolver {
    fun resolveCoverUris(
        persons: List<PersonEntity>,
        uriByMediaId: Map<Long, String>
    ): Map<Long, String?> {
        return persons.associate { person ->
            person.personId to person.coverMediaId?.let { id -> uriByMediaId[id] }
        }
    }
}
```

`app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt`：

```kotlin
package com.mamba.picme.features.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.PersonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonViewModel(
    private val personRepository: PersonRepository,
    private val db: AppDatabase
) : ViewModel() {

    private val _persons = MutableStateFlow<List<PersonEntity>>(emptyList())
    val persons: StateFlow<List<PersonEntity>> = _persons.asStateFlow()

    private val _coverUris = MutableStateFlow<Map<Long, String?>>(emptyMap())
    val coverUris: StateFlow<Map<Long, String?>> = _coverUris.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val all = personRepository.getAllPersons()
            _persons.value = all
            val ids = all.mapNotNull { person -> person.coverMediaId }.distinct()
            val uriByMediaId = withContext(Dispatchers.IO) {
                if (ids.isEmpty()) emptyMap()
                else db.mediaDao().getMediaByIds(ids).associate { entity -> entity.id to entity.uri }
            }
            _coverUris.value = PersonCoverResolver.resolveCoverUris(all, uriByMediaId)
        }
    }

    fun applyEdit(
        personId: Long,
        name: String,
        customLabel: String,
        isSelf: Boolean,
        relation: com.mamba.picme.domain.person.RelationPredicate?,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            personRepository.applyPersonEdit(personId, name, relation, customLabel, isSelf)
            load() // 刷新
            onDone()
        }
    }

    /** 该人物的照片数（来自 entity.faceCount）。 */
    fun faceCount(person: PersonEntity): Int = person.faceCount
}
```

> `db.mediaDao().getMediaByIds(ids)` 已存在（`scheduleRegenerate` 用过）。`PersonEntity.coverMediaId/faceCount` 已存在。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.person.PersonCoverResolverTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/person/ \
        app/src/test/java/com/mamba/picme/features/person/PersonCoverResolverTest.kt
git commit -m "feat(person): PersonViewModel + PersonCoverResolver（人物列表与封面 uri）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: PersonScreen（封面网格 + 重命名）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/person/PersonScreen.kt`
- Modify: `res/values*/strings.xml`（新增 `people_title`、`people_default_name`、`people_photos_count`）

- [ ] **Step 1: 新增文案（4 locale 同步）**

每个 `values*/strings.xml` 新增 3 条：

values/：
```xml
    <string name="people_title">People</string>
    <string name="people_default_name">Person #%1$d</string>
    <string name="people_photos_count">%1$d photos</string>
```
values-zh/ & values-zh-rCN/：
```xml
    <string name="people_title">人物</string>
    <string name="people_default_name">人物 #%1$d</string>
    <string name="people_photos_count">%1$d 张照片</string>
```
values-zh-rTW/：
```xml
    <string name="people_title">人物</string>
    <string name="people_default_name">人物 #%1$d</string>
    <string name="people_photos_count">%1$d 張照片</string>
```

- [ ] **Step 2: 新建 PersonScreen**

```kotlin
package com.mamba.picme.features.person

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRenameDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.load() }

    val persons by viewModel.persons.collectAsState()
    val coverUris by viewModel.coverUris.collectAsState()
    var editing by remember { mutableStateOf<PersonEntity?>(null) }
    var editRelation by remember { mutableStateOf<RelationPredicate?>(null) }
    var editCustomLabel by remember { mutableStateOf("") }
    var editIsSelf by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(4.dp)
        ) {
            items(items = persons, key = { person -> person.personId }) { person ->
                PersonCoverCell(
                    person = person,
                    coverUri = coverUris[person.personId],
                    onClick = {
                        editing = person
                        editRelation = null
                        editCustomLabel = ""
                        editIsSelf = person.isSelf
                        // 回填关系（异步取一次；简单起见 UI 侧不预填 customLabel，留空）
                    }
                )
            }
        }
    }

    val target = editing
    if (target != null) {
        PersonRenameDialog(
            initialName = target.name.orEmpty(),
            initialRelation = editRelation,
            initialCustomLabel = editCustomLabel,
            initialIsSelf = editIsSelf,
            onConfirm = { name, relation, customLabel, isSelf ->
                viewModel.applyEdit(target.personId, name, customLabel, isSelf, relation)
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun PersonCoverCell(
    person: PersonEntity,
    coverUri: String?,
    onClick: () -> Unit
) {
    val name = person.name ?: stringResource(R.string.people_default_name, person.personId)
    val count = stringResource(R.string.people_photos_count, person.faceCount)
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 底部渐变名条（简易实现：半透明底 + 文本）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(4.dp)
        ) {
            Text(
                text = "$name · $count",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
```

> 已命名优先排序可在 `getAllPersons()` 后 `.sortedWith(compareByDescending<...> {it.name != null}.thenBy {...})`；v1 用 updatedAt 倒序即可。`rememberScaffoldState` import 未用，以编译为准删除。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/person/PersonScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(person): PersonScreen 封面网格 + 点封面重命名（4 locale 文案）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 导航 + 相册入口 + 设置一级入口 + AI 记忆移除人物关系

**Files:**
- Modify: `navigation/Screen.kt`、`MainActivity.kt`、`features/gallery/components/GalleryTopBar.kt`、`features/gallery/GalleryScreen.kt`、`features/settings/SettingsScreen.kt`、`features/settings/MemoryFactsScreen.kt`
- Modify: `res/values*/strings.xml`（`people_entry`、`people_entry_desc`、`gallery_people_entry`）

- [ ] **Step 1: 路由**

`navigation/Screen.kt` 在 `MemoryFacts` 之后加：

```kotlin
    data object People : Screen("people")
```

- [ ] **Step 2: 文案（4 locale）**

values/：`<string name="people_entry">People</string>`、`<string name="people_entry_desc">View and name face clusters</string>`、`<string name="gallery_people_entry">People</string>`
values-zh/ & values-zh-rCN/：`人物` / `查看并命名人脸聚类` / `人物`
values-zh-rTW/：`人物` / `查看並命名人臉聚類` / `人物`

- [ ] **Step 3: MainActivity 接线**

在 `MainActivity.kt` NavHost 内（`Screen.MemoryFacts` composable 之后）加：

```kotlin
                            composable(Screen.People.route) {
                                PersonScreen(
                                    viewModel = app.container.personViewModel,
                                    onNavigateBack = { /* navController.popBackStack() */ }
                                )
                            }
```

并在 `AppContainer`（`di/AppContainer.kt`）暴露：

```kotlin
    val personViewModel: com.mamba.picme.features.person.PersonViewModel by lazy {
        com.mamba.picme.features.person.PersonViewModel(personRepository, db)
    }
```

> `onNavigateBack` 实际用 `navController.popBackStack()`（把 navController 传入或用 lambda 捕获，参照其它 Screen 接线）。`db`/`personRepository` 已是 AppContainer 现有成员。

- [ ] **Step 4: 相册顶栏人物入口**

`GalleryTopBar.kt` 参数加 `onNavigateToPeople: () -> Unit = {}`；在 `actions` 的非选择模式分支里（设置图标之前）加：

```kotlin
                IconButton(onClick = onNavigateToPeople) {
                    Icon(
                        androidx.compose.material.icons.Icons.Rounded.AccountCircle,
                        contentDescription = stringResource(R.string.gallery_people_entry)
                    )
                }
```

`GalleryScreen.kt` 调用 `GalleryTopBar(...)` 处补 `onNavigateToPeople = onNavigateToPeople`（GalleryScreen 新增同名参数 `onNavigateToPeople: () -> Unit = {}`，由 `MainActivity` 的 `composable(Screen.Gallery.route){}` 传入 `navController.navigate(Screen.People.route)`）。

- [ ] **Step 5: 设置一级人物入口**

`SettingsScreen.kt`：
- `SettingsMainMenu` 与 `SettingsCategoryGrid` 增加 `onNavigateToPeople: () -> Unit` 参数（贯穿调用链，参照 `onNavigateToMemoryFacts`）。
- 在 `SettingsCategoryGrid`（1093 行 `settings_ai_memory` 项附近）加：

```kotlin
        CategoryGridItem(R.string.people_entry, R.string.people_entry_desc, Icons.Rounded.AccountCircle, onNavigateToPeople),
```

- `SettingsScreen` 主入口与 `MainActivity` 的 `composable(Screen.Settings.route){}` 传入 `onNavigateToPeople = { navController.navigate(Screen.People.route) }`。

- [ ] **Step 6: AI 记忆移除人物关系 section**

`MemoryFactsScreen.kt`：删除「人物关系 section」（115-135 行 `relations_header`/`relations_empty`/`items(relations)`）及其 `editingRelation` 对话框、`PersonRelationRow` 调用；`MemoryFactsViewModel` 中 `relations`/`removeRelation` 相关可保留（不强制删，避免回归），仅 UI 不再展示。section header 改为只保留「事实记忆」。

> 关系编辑已迁入人物页重命名对话框（Task 2/4）。AI 记忆专注事实记忆。

- [ ] **Step 7: 编译 + 单测 + 设备 ui-driver 验证**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；单测 PASS。

设备（用户实地 / `/ui-driver`）：
- 相册顶栏人物图标 → 进入人物页，封面网格显示。
- 点封面 → 重命名对话框 → 改名/标关系/标"我" → 保存 → 刷新。
- 设置一级「人物」入口可达；AI 记忆页只剩事实记忆。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
        app/src/main/java/com/mamba/picme/MainActivity.kt \
        app/src/main/java/com/mamba/picme/di/AppContainer.kt \
        app/src/main/java/com/mamba/picme/features/gallery/components/GalleryTopBar.kt \
        app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt \
        app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt \
        app/src/main/java/com/mamba/picme/features/settings/MemoryFactsScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(person): 人物页导航+相册入口+设置一级入口；AI记忆移除人物关系

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 文档同步 + 收尾验证

**Files:**
- Modify: `PRODUCT.md`（人物章节：独立人物页 + 入口）、`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`（人物页入口）、`docs/01-PRODUCT/FEATURES.md`（交互规则，若涉及）

- [ ] **Step 1: 文档同步**

更新 `PRODUCT.md` 人物记忆章节：新增「人物」页（封面网格 + 重命名/关系/我）+ 相册入口 + 设置一级入口；AI 记忆专注事实记忆。引用 spec §3.4/§3.5。

- [ ] **Step 2: 全量门槛 + i18n 自检**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
i18n 自检：`grep -rnE "people_title|people_entry|gallery_people_entry" app/src/main/res/values*/strings.xml`（4 套齐全）。

- [ ] **Step 3: Commit**

```bash
git add PRODUCT.md docs/
git commit -m "docs: 人物页入口与命名（Plan C）产品/架构文档同步

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 自审（Self-Review）

- **Spec 覆盖**：§3.4 人物页 → Task 3/4；§3.5 入口与导航 → Task 5；命名收口 → Task 1/2。两处偏差（人脸裁剪、点进看照片）已在开头声明并延后。
- **占位符**：无 TBD；每步含完整代码/命令。少数 import 以"编译为准"微调已注明。
- **类型一致**：`PersonRenameDialog(initialName, initialRelation, initialCustomLabel, initialIsSelf, onConfirm, onDismiss)` 在 Task 2/4 一致；`applyPersonEdit(personId, name, relation, customLabel, isSelf)` 在 Task 1/2/3 一致；`PersonCoverResolver.resolveCoverUris(persons, uriByMediaId)` Task 3 测与实现一致；`Screen.People.route="people"` Task 5 一致。
- **i18n**：`people_title/people_default_name/people_photos_count/people_entry/people_entry_desc/gallery_people_entry` 共 6 key × 4 locale。
- **回归风险**：GalleryScreen 改用公共对话框须保留 `onGroupTitleClick` 回填逻辑（Task 2 已注明不变）；MemoryFacts 仅删 UI section，ViewModel 保留降低回归。
