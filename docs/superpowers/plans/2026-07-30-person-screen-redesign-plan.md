# 人物页 UI/UX 重设计实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将「人物」页从密集 3 列网格改为舒展的垂直列表，并在页面内闭环完成「改名 / 编辑人物信息 / 选封面」三项操作。

**Architecture:** 数据层复用 `PersonRepository` 与 `PersonDao`，仅新增 `updateCover` 收口；UI 层拆分出 `PersonListItem`、`PersonCoverPickerSheet`、`PersonInfoSheet` 三个独立组件；`PersonScreen` 改为 `LazyColumn` 并协调行内编辑态与两个 Bottom Sheet。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Coil, Room, Robolectric + JUnit 4, Gradle.

---

## 文件结构

| 文件 | 动作 | 说明 |
|------|------|------|
| `app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt` | 修改 | 新增 `updateCover(personId, mediaId)` |
| `app/src/test/java/com/mamba/picme/domain/person/PersonRepositoryTest.kt` | 修改 | 新增 `updateCover` 测试 |
| `app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt` | 修改 | 加载关系、维护编辑态、新增三个业务方法 |
| `app/src/main/java/com/mamba/picme/features/person/components/PersonListItem.kt` | 创建 | 列表项 UI + 行内改名编辑态 |
| `app/src/main/java/com/mamba/picme/features/person/components/PersonCoverPickerSheet.kt` | 创建 | 底部封面选择 Sheet |
| `app/src/main/java/com/mamba/picme/features/person/components/PersonInfoSheet.kt` | 创建 | 底部人物信息/关系编辑 Sheet |
| `app/src/main/java/com/mamba/picme/features/person/PersonScreen.kt` | 修改 | 从网格改为列表，集成新组件 |
| `app/src/main/res/values/strings.xml` | 修改 | 新增英文文案 |
| `app/src/main/res/values-zh/strings.xml` | 修改 | 新增简体中文文案 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 修改 | 新增繁体中文文案 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 修改 | 新增简体中文文案 |

---

## Task 1: Repository 新增 `updateCover` 收口

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/person/PersonRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**

在 `PersonRepositoryTest.kt` 末尾新增：

```kotlin
@Test
fun `updateCover changes coverMediaId and updates timestamp`() = runTest {
    val personId = insertPerson("小宝")
    db.mediaDao().insertMedia(
        com.mamba.picme.data.model.MediaEntity(
            id = 100L,
            uri = "content://test/100",
            mediaType = "image",
            mimeType = "image/jpeg",
            displayName = "test.jpg",
            dateAdded = 1L,
            dateModified = 1L,
            captureDate = 1L
        )
    )

    repository.updateCover(personId, 100L)

    val person = db.personDao().getPerson(personId)
    assertNotNull(person)
    assertEquals(100L, person!!.coverMediaId)
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.person.PersonRepositoryTest.updateCover changes coverMediaId and updates timestamp"
```

Expected: FAIL with `Unresolved reference: updateCover`

- [ ] **Step 3: 最小实现**

在 `PersonRepository.kt` 中 `applyPersonEdit` 之后新增：

```kotlin
/** 更新人物封面（收口，供 UI 层调用） */
suspend fun updateCover(personId: Long, mediaId: Long) {
    personDao.updateCoverMedia(personId, mediaId)
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.person.PersonRepositoryTest.updateCover changes coverMediaId and updates timestamp"
```

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/person/PersonRepository.kt app/src/test/java/com/mamba/picme/domain/person/PersonRepositoryTest.kt
git commit -m "feat(person): add updateCover repository API with test"
```

---

## Task 2: ViewModel 加载关系并暴露业务方法

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt`

- [ ] **Step 1: 修改 ViewModel 状态与加载逻辑**

将 `PersonViewModel.kt` 替换为以下完整内容（保留 factory 与 reconcileAndLoad）：

```kotlin
package com.mamba.picme.features.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
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

    private val _covers = MutableStateFlow<Map<Long, PersonCover>>(emptyMap())
    val covers: StateFlow<Map<Long, PersonCover>> = _covers.asStateFlow()

    private val _relations = MutableStateFlow<Map<Long, RelationDisplayItem>>(emptyMap())
    val relations: StateFlow<Map<Long, RelationDisplayItem>> = _relations.asStateFlow()

    private val _editingPersonId = MutableStateFlow<Long?>(null)
    val editingPersonId: StateFlow<Long?> = _editingPersonId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val all = personRepository.getAllPersons()
            val ids = all.mapNotNull { person -> person.coverMediaId }.distinct()
            val resolved = withContext(Dispatchers.IO) {
                if (ids.isEmpty()) emptyMap()
                else {
                    val media = db.mediaDao().getMediaByIds(ids)
                    PersonCoverResolver.resolve(
                        all,
                        media.associate { entity -> entity.id to entity.uri },
                        media.associate { entity -> entity.id to entity.faceFocusY }
                    )
                }
            }
            val relationMap = withContext(Dispatchers.IO) {
                all.associate { person ->
                    val relation = personRepository.getRelationToSelf(person.personId)
                    person.personId to relationToDisplay(person, relation)
                }
            }
            _covers.value = resolved
            _relations.value = relationMap
            _persons.value = PersonCoverResolver.filterCoverable(all, resolved)
        }
    }

    fun reconcileAndLoad() {
        viewModelScope.launch {
            personRepository.reconcilePersons()
            load()
        }
    }

    fun startEditing(personId: Long) {
        _editingPersonId.value = personId
    }

    fun stopEditing() {
        _editingPersonId.value = null
    }

    fun updateName(personId: Long, name: String) {
        viewModelScope.launch {
            try {
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    personRepository.renamePerson(personId, trimmed)
                }
                stopEditing()
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun updateCover(personId: Long, mediaId: Long) {
        viewModelScope.launch {
            try {
                personRepository.updateCover(personId, mediaId)
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun updatePersonInfo(
        personId: Long,
        relation: RelationPredicate?,
        customLabel: String,
        isSelf: Boolean
    ) {
        viewModelScope.launch {
            try {
                val person = _persons.value.find { it.personId == personId } ?: return@launch
                val name = person.name ?: ""
                personRepository.applyPersonEdit(personId, name, relation, customLabel, isSelf)
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun relationToDisplay(
        person: PersonEntity,
        relation: com.mamba.picme.data.local.entity.PersonRelationEntity?
    ): RelationDisplayItem? {
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

    companion object {
        fun factory(personRepository: PersonRepository, db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PersonViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return PersonViewModel(personRepository, db) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt
git commit -m "feat(person): load relations and expose edit/cover actions in ViewModel"
```

---

## Task 3: 新增字符串资源（四语）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: 在 `values/strings.xml` 中新增**

```xml
<string name="person_select_cover_title">Select cover</string>
<string name="person_info_title">Person info</string>
<string name="person_save">Save</string>
<string name="person_cancel">Cancel</string>
<string name="people_empty">No people found</string>
<string name="person_photo_count">%1$d photos</string>
<string name="person_edit_name_hint">Tap to name</string>
<string name="person_error_save_failed">Save failed: %1$s</string>
```

- [ ] **Step 2: 在三个中文资源文件中新增对应翻译**

```xml
<string name="person_select_cover_title">选择封面</string>
<string name="person_info_title">人物信息</string>
<string name="person_save">保存</string>
<string name="person_cancel">取消</string>
<string name="people_empty">未找到人物</string>
<string name="person_photo_count">%1$d 张照片</string>
<string name="person_edit_name_hint">点击命名</string>
<string name="person_error_save_failed">保存失败：%1$s</string>
```

- [ ] **Step 3: 编译检查资源**

```bash
./gradlew :app:mergeDebugResources
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat(person): add i18n strings for list redesign"
```

---

## Task 4: 创建 `PersonListItem`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/person/components/PersonListItem.kt`

- [ ] **Step 1: 创建文件并写入组件**

```kotlin
package com.mamba.picme.features.person.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.features.common.personRelationLabelRes
import com.mamba.picme.features.person.PersonCover

@Composable
fun PersonListItem(
    person: PersonEntity,
    cover: PersonCover?,
    relation: RelationDisplayItem?,
    isEditingName: Boolean,
    onCoverClick: () -> Unit,
    onNameClick: () -> Unit,
    onNameSave: (String) -> Unit,
    onNameCancel: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        CoverThumbnail(
            cover = cover,
            contentDescription = person.name ?: stringResource(R.string.people_default_name, person.personId),
            onClick = onCoverClick
        )
        Spacer(modifier = Modifier.width(16.dp))
        InfoColumn(
            person = person,
            relation = relation,
            isEditingName = isEditingName,
            onNameClick = onNameClick,
            onNameSave = onNameSave,
            onNameCancel = onNameCancel,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onInfoClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.person_info_title)
            )
        }
    }
}

@Composable
private fun CoverThumbnail(
    cover: PersonCover?,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        val uri = cover?.coverUri
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(cover.faceFocusY),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
private fun InfoColumn(
    person: PersonEntity,
    relation: RelationDisplayItem?,
    isEditingName: Boolean,
    onNameClick: () -> Unit,
    onNameSave: (String) -> Unit,
    onNameCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (isEditingName) {
            NameEditor(
                initialName = person.name.orEmpty(),
                onSave = onNameSave,
                onCancel = onNameCancel
            )
        } else {
            val hasName = !person.name.isNullOrBlank()
            Text(
                text = if (hasName) person.name!! else stringResource(R.string.person_edit_name_hint),
                color = if (hasName) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onNameClick)
            )
        }
        Text(
            text = stringResource(R.string.person_photo_count, person.faceCount),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (relation != null) {
            val label = relation.customLabel
                ?: stringResource(personRelationLabelRes(relation.predicate))
            val isSelf = relation.predicate == null && person.isSelf
            AssistChip(
                onClick = {},
                label = { Text(label, fontSize = 12.sp) },
                colors = if (isSelf) {
                    AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun NameEditor(
    initialName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember(initialName) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave(text) }),
        trailingIcon = {
            Row {
                IconButton(onClick = { onSave(text) }) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
}
```

注意：需要导入 `androidx.compose.foundation.background`，上面 `CoverThumbnail` 用到了 `background`。请确保 import 包含：

```kotlin
import androidx.compose.foundation.background
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/person/components/PersonListItem.kt
git commit -m "feat(person): add PersonListItem with inline name editing"
```

---

## Task 5: 创建 `PersonCoverPickerSheet`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/person/components/PersonCoverPickerSheet.kt`

- [ ] **Step 1: 创建文件并写入组件**

```kotlin
package com.mamba.picme.features.person.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.data.model.MediaEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonCoverPickerSheet(
    photos: List<MediaEntity>,
    onSelect: (MediaEntity) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            text = stringResource(R.string.person_select_cover_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            items(photos, key = { it.id }) { photo ->
                CoverCandidate(
                    photo = photo,
                    onClick = { onSelect(photo) }
                )
            }
        }
    }
}

@Composable
private fun CoverCandidate(
    photo: MediaEntity,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/person/components/PersonCoverPickerSheet.kt
git commit -m "feat(person): add bottom sheet for cover selection"
```

---

## Task 6: 创建 `PersonInfoSheet`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/person/components/PersonInfoSheet.kt`

- [ ] **Step 1: 创建文件并写入组件**

```kotlin
package com.mamba.picme.features.person.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRelationPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoSheet(
    relation: RelationDisplayItem?,
    isSelf: Boolean,
    onSave: (RelationPredicate?, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    var currentRelation by remember(relation) {
        mutableStateOf(relation?.predicate)
    }
    var customLabel by remember(relation) {
        mutableStateOf(relation?.customLabel.orEmpty())
    }
    var currentIsSelf by remember(isSelf) { mutableStateOf(isSelf) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.person_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            PersonRelationPicker(
                selectedPredicate = currentRelation,
                customLabel = customLabel,
                onPredicateChange = { currentRelation = it },
                onCustomLabelChange = { customLabel = it },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.person_is_self),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = currentIsSelf,
                    onCheckedChange = { currentIsSelf = it }
                )
            }
            Button(
                onClick = {
                    val effectiveRelation = if (customLabel.isNotBlank()) {
                        RelationPredicate.OTHER
                    } else {
                        currentRelation
                    }
                    onSave(effectiveRelation, customLabel, currentIsSelf)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text(stringResource(R.string.person_save))
            }
        }
    }
}
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/person/components/PersonInfoSheet.kt
git commit -m "feat(person): add bottom sheet for person info editing"
```

---

## Task 7: 重构 `PersonScreen`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/person/PersonScreen.kt`

- [ ] **Step 1: 用完整新实现替换文件**

```kotlin
package com.mamba.picme.features.person

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.features.person.components.PersonCoverPickerSheet
import com.mamba.picme.features.person.components.PersonInfoSheet
import com.mamba.picme.features.person.components.PersonListItem

/**
 * 「人物」页：垂直列表展示全部人脸聚类。
 * 支持行内改名、底部 Sheet 编辑关系/「我」标记、底部 Sheet 选择封面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.reconcileAndLoad() }

    val persons by viewModel.persons.collectAsState()
    val covers by viewModel.covers.collectAsState()
    val relations by viewModel.relations.collectAsState()
    val editingPersonId by viewModel.editingPersonId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var infoTarget by remember { mutableStateOf<PersonEntity?>(null) }
    var coverTarget by remember { mutableStateOf<PersonEntity?>(null) }
    var photos by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(coverTarget) {
        val target = coverTarget
        photos = if (target != null) {
            viewModel.db.mediaDao().getMediaByPerson(target.personId)
        } else {
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 16.dp
                ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = persons,
                    key = { person -> person.personId }
                ) { person ->
                    PersonListItem(
                        person = person,
                        cover = covers[person.personId],
                        relation = relations[person.personId],
                        isEditingName = editingPersonId == person.personId,
                        onCoverClick = { coverTarget = person },
                        onNameClick = { viewModel.startEditing(person.personId) },
                        onNameSave = { name -> viewModel.updateName(person.personId, name) },
                        onNameCancel = { viewModel.stopEditing() },
                        onInfoClick = { infoTarget = person }
                    )
                }
            }
        }
    }

    infoTarget?.let { person ->
        PersonInfoSheet(
            relation = relations[person.personId],
            isSelf = person.isSelf,
            onSave = { relation, customLabel, isSelf ->
                viewModel.updatePersonInfo(person.personId, relation, customLabel, isSelf)
            },
            onDismiss = { infoTarget = null }
        )
    }

    coverTarget?.let { person ->
        if (photos.isNotEmpty()) {
            PersonCoverPickerSheet(
                photos = photos,
                onSelect = { photo ->
                    viewModel.updateCover(person.personId, photo.id)
                    coverTarget = null
                },
                onDismiss = { coverTarget = null }
            )
        }
    }
}
```

注意：`PersonScreen` 直接访问 `viewModel.db` 不合适，违反封装。请在 `PersonViewModel` 中新增 `suspend fun loadPhotosByPerson(personId: Long): List<MediaEntity>`，然后在 `LaunchedEffect` 中通过 `viewModel` 调用。修改如下：

在 `PersonViewModel` 中新增：

```kotlin
suspend fun loadPhotosByPerson(personId: Long): List<MediaEntity> =
    withContext(Dispatchers.IO) {
        db.mediaDao().getMediaByPerson(personId)
    }
```

然后 `PersonScreen` 的 `LaunchedEffect` 改为：

```kotlin
LaunchedEffect(coverTarget) {
    val target = coverTarget
    photos = if (target != null) {
        viewModel.loadPhotosByPerson(target.personId)
    } else {
        emptyList()
    }
}
```

- [ ] **Step 2: 编译检查**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/person/PersonScreen.kt app/src/main/java/com/mamba/picme/features/person/PersonViewModel.kt
git commit -m "feat(person): rewrite PersonScreen as vertical list with sheets"
```

---

## Task 8: 编译与代码质量检查

- [ ] **Step 1: 全量编译**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 单元测试**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL，测试通过。

- [ ] **Step 3: 代码风格检查（如 detekt/ktlint 未设置阻断）**

```bash
./gradlew :app:ktlintCheck
```

Expected: 无新增严重风格问题（允许 baseline 内已有问题）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "chore(person): verify build and tests after redesign"
```

---

## Task 9: 设备验证

- [ ] **Step 1: 安装 Debug APK 到已连接设备**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL，APK 安装成功。

- [ ] **Step 2: 打开「人物」页并截图**

```bash
adb shell am start -n com.mamba.picme/.MainActivity
sleep 3
adb shell input tap <人物入口坐标>
sleep 2
adb shell screencap -p /sdcard/person_redesign.png
adb pull /sdcard/person_redesign.png .kimi/screenshots/person_redesign.png
```

如果无法直接点击，手动导航到人物页后执行：

```bash
adb shell screencap -p /sdcard/person_redesign.png
adb pull /sdcard/person_redesign.png .kimi/screenshots/person_redesign.png
```

- [ ] **Step 3: 验证三项交互**

1. 点击某人物名字 → 出现输入框，修改后保存，列表刷新。
2. 点击某人物封面 → 底部 Sheet 弹出，选择另一张照片 → 封面更新。
3. 点击 ⋮ 更多 → 底部 Sheet 弹出，修改关系/「这是我」 → 保存后关系标签更新。

- [ ] **Step 4: 提交验证截图**

```bash
git add .kimi/screenshots/person_redesign.png
git commit -m "docs(person): add redesign verification screenshot"
```

---

## Self-Review

- **Spec coverage:**
  - 垂直列表布局 → Task 4 + Task 7
  - 行内改名 → Task 4
  - 底部 Sheet 选封面 → Task 5 + Task 7
  - 底部 Sheet 编辑关系/「我」 → Task 6 + Task 7
  - 关系标签显示 → Task 2（加载关系） + Task 4（UI）
  - I18N → Task 3
  - 性能/隐私 → Task 7（本地 DB、Coil）
- **Placeholder scan:** 无 TBD/TODO。
- **Type consistency:** `updateCover`、`PersonCover`、`RelationDisplayItem` 在 Repository/ViewModel/UI 中命名一致。
